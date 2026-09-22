package net.metalmod.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.DeviceType;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.HintsAndWorkarounds;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Phase 2 Metal device backend.
 *
 * <p>Real: device, command queue, surface, textures, texture views, buffers and samplers. Still
 * placeholder: render pipelines and the draw path (Phase 3), so a compiled pipeline reports valid
 * without compiling shaders.
 */
public final class MetalDevice implements GpuDeviceBackend {

    private final MemorySegment device;
    private final MemorySegment queue;
    private final DeviceInfo info;
    private final MetalTransientMemory transientMemory;
    private final MetalShaderCompiler shaderCompiler = new MetalShaderCompiler();
    private final Map<RenderPipeline, MetalRenderPipeline> pipelines = new HashMap<>();

    // The engine supplies a ShaderSource for the static pipelines and none at all for the
    // post-processing chain, which arrives through the one-argument precompilePipeline(pipeline).
    // Keep the most recent source - lookups are by identifier, so any one will do - and use it both
    // for those passes and for the pipelines the engine binds without precompiling at all (the
    // loading-screen 'mojang_logo', for instance), so they draw instead of being silently skipped.
    private ShaderSource lastShaderSource;
    private final Set<RenderPipeline> failedPipelines =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private int pipelineLogCount;
    private int pipelineFailureLogCount;
    private static int pipelineFailureCount;
    private boolean closed;

    // Resource creation failures are counted and logged a bounded number of times: one unsupported
    // format must not spam the log or take the game down.
    private static int resourceFailureCount;
    private static int resourceFailureLogCount;

    // Resource counters, so "the game created all its resources" is a measurable claim rather than
    // an absence of errors. Static because the game only ever has one Metal device.
    private static final java.util.concurrent.atomic.AtomicLong TEXTURE_COUNT = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong TEXTURE_VIEW_COUNT = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong BUFFER_COUNT = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong SAMPLER_COUNT = new java.util.concurrent.atomic.AtomicLong();

    public static String resourceSummary() {
        return "[MetalMod] Metal resources created: textures=" + TEXTURE_COUNT.get()
                + " views=" + TEXTURE_VIEW_COUNT.get()
                + " buffers=" + BUFFER_COUNT.get()
                + " samplers=" + SAMPLER_COUNT.get()
                + " failures=" + resourceFailureCount
                + " pipelineFailures=" + pipelineFailureCount
                + " unboundBindings=" + unboundBindingCount()
                + " missingVertexAttributes=" + missingVertexAttributeCount()
                + " slotCollisions=" + slotCollisionCount()
                + " bindingKindMismatches=" + bindingKindMismatchCount()
                + " indexedFans=" + indexedFanCount()
                + " | " + SHADER_COMPILER_SUMMARY.get();
    }

    // Set by MetalDevice so the telemetry summary can report shader cache effectiveness.
    static final java.util.concurrent.atomic.AtomicReference<String> SHADER_COMPILER_SUMMARY =
            new java.util.concurrent.atomic.AtomicReference<>("shader pairs: n/a");

    static synchronized void reportResourceFailure(String what) {
        resourceFailureCount++;
        if (resourceFailureLogCount < 20) {
            resourceFailureLogCount++;
            System.err.println("[MetalMod] Metal resource creation failed: " + what
                    + " (further failures counted silently)");
        }
    }

    public static synchronized int resourceFailureCount() {
        return resourceFailureCount;
    }

    // ---------------------------------------------------------------------------------------------
    // Frame timing and draw census
    //
    // The 30 s telemetry line reports health counters; these answer the performance question the
    // health counters cannot: is a frame CPU-bound or GPU-bound, and how many draws does it encode?
    // The render thread writes them and F3 reads them, so volatile publication is enough. GPU time
    // is accumulated natively (see mmm_gpu_frame_time_ms) and lags about a frame.
    // ---------------------------------------------------------------------------------------------

    private static int drawsThisFrame;
    private static volatile int lastFrameDraws;
    private static volatile float lastFrameMs;
    private static volatile float lastGpuMs;
    private static volatile long lastGpuBuffers;
    private static long lastFrameNanos;

    /** Count one encoded draw. Called from the render pass backend, on the render thread. */
    static void countDraw() {
        drawsThisFrame++;
    }

    /**
     * Close out a frame at present: publish the CPU frame interval, the GPU time accumulated since
     * the previous present, and the draw count, then reset the counters for the next frame.
     */
    public static void endFrame() {
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            lastFrameMs = (float) ((now - lastFrameNanos) / 1_000_000.0);
        }
        lastFrameNanos = now;
        lastGpuMs = (float) MetalNative.gpuFrameTimeMs();
        lastGpuBuffers = MetalNative.gpuBufferCount();
        MetalNative.resetGpuFrameTime();
        lastFrameDraws = drawsThisFrame;
        drawsThisFrame = 0;
    }

    /** Wall-clock interval between the last two presents, in milliseconds. */
    public static float lastFrameMs() {
        return lastFrameMs;
    }

    /** GPU busy time accumulated for the previous frame, in milliseconds. */
    public static float lastGpuMs() {
        return lastGpuMs;
    }

    /** How many command buffers that GPU time covered; a high count is submission overhead. */
    public static long lastGpuBuffers() {
        return lastGpuBuffers;
    }

    /** Draw calls encoded in the last completed frame. */
    public static int lastFrameDraws() {
        return lastFrameDraws;
    }

    // A shader that samples a texture the engine never bound reads garbage - usually black, which
    // looks like a rendering bug rather than a binding bug. Count the distinct (pipeline, kind, name)
    // combinations once each so the failure is visible instead of silent. Report-only: binding
    // behaviour is unchanged.
    private static final java.util.Set<String> reportedUnbound = new java.util.HashSet<>();
    private static int unboundCount;
    private static int unboundLogCount;

    static synchronized void reportUnboundBinding(String pipeline, String kind, String name) {
        if (reportedUnbound.size() >= 64 || !reportedUnbound.add(pipeline + "|" + kind + "|" + name)) {
            return;
        }
        unboundCount++;
        if (unboundLogCount < 20) {
            unboundLogCount++;
            System.err.println("[MetalMod] unbound " + kind + " '" + name + "' in " + pipeline
                    + ": the shader reads it and nothing was bound (Metal returns undefined data)");
        }
    }

    public static synchronized int unboundBindingCount() {
        return unboundCount;
    }

    // Two shader resources resolving to one Metal slot means whichever is bound last wins and the
    // other reads its data. glslang emits duplicate SPIR-V bindings (every shader importing
    // fog.glsl gets Fog at binding 0 alongside another block at binding 0), so slots are assigned
    // from a counter instead. This guard makes a regression of that visible rather than silent.
    private static final java.util.Set<String> reportedSlotCollisions = new java.util.HashSet<>();
    private static int slotCollisionCount;

    static synchronized void reportSlotCollision(String stage, String kind,
                                                 String first, String second, int slot) {
        if (reportedSlotCollisions.size() >= 32
                || !reportedSlotCollisions.add(stage + "|" + kind + "|" + slot)) {
            return;
        }
        slotCollisionCount++;
        System.err.println("[MetalMod] " + kind + " slot collision in the " + stage
                + " stage: '" + first + "' and '" + second + "' both use Metal slot " + slot
                + ", so one will overwrite the other");
    }

    public static synchronized int slotCollisionCount() {
        return slotCollisionCount;
    }

    // The pipeline's BindGroupLayouts state, authoritatively, how the engine will bind each uniform:
    // UNIFORM_BUFFER means it hands us a GpuBufferSlice, TEXEL_BUFFER means a GpuBuffer. If the
    // reflection produced a different kind, the name is bound through the wrong path - the
    // CloudFaces case, where the engine passes a buffer and the reflection produced a texture.
    private static final java.util.Set<String> reportedKindMismatches = new java.util.HashSet<>();
    private static int bindingKindMismatchCount;

    static synchronized void reportBindingKindMismatch(String pipeline, String name,
                                                       String declared, String reflected) {
        if (reportedKindMismatches.size() >= 64
                || !reportedKindMismatches.add(pipeline + "|" + name)) {
            return;
        }
        bindingKindMismatchCount++;
        System.err.println("[MetalMod] binding kind mismatch in " + pipeline + ": '" + name
                + "' is declared " + declared + " but reflected as " + reflected
                + ", so it will not be bound correctly");
    }

    // The reflection map says where a resource was asked to land; the MSL says where SPIRV-Cross
    // actually put it. Those disagreeing means we bind one slot while the shader reads another, and
    // it is invisible in both directions - the name is present, so nothing looks unbound. This is
    // how BUG-012 survived its first fix: with duplicate SPIR-V bindings SPIRV-Cross applied one
    // block's binding to another, and only the MSL showed it.
    private static final java.util.Set<String> reportedSlotMismatches = new java.util.HashSet<>();
    private static int slotMismatchCount;

    static synchronized void reportSlotMismatch(String shader, String stage, String kind,
                                                String name, int reflected, int msl) {
        if (reportedSlotMismatches.size() >= 64
                || !reportedSlotMismatches.add(shader + "|" + kind + "|" + name)) {
            return;
        }
        slotMismatchCount++;
        System.err.println("[MetalMod] " + kind + " slot mismatch in " + shader + " (" + stage
                + "): reflection says '" + name + "' is at " + reflected + " but the generated MSL"
                + " puts it at " + msl + ", so it would be bound to the wrong slot");
    }

    public static synchronized int slotMismatchCount() {
        return slotMismatchCount;
    }

    public static synchronized int bindingKindMismatchCount() {
        return bindingKindMismatchCount;
    }

    // Metal rejects a pipeline whose vertex function reads an attribute the vertex descriptor does
    // not provide ("Vertex attribute X(N) is missing from the vertex descriptor"). Catch it before
    // Metal does, so the pipeline and the attribute are named.
    //
    // The inverse - a VertexFormat element the shader has no input for - is NOT a problem and is
    // deliberately not reported: Minecraft's vertex formats declare more than a given shader uses
    // (rendertype_entity_shadow carries UV1/UV2/Normal but its shader declares only
    // Position/Color/UV0), and dropping the extras is correct.
    private static final java.util.Set<String> reportedMissingAttributes = new java.util.HashSet<>();
    private static int missingAttributeCount;

    static synchronized void reportMissingVertexAttribute(String pipeline, String name) {
        if (reportedMissingAttributes.size() >= 64
                || !reportedMissingAttributes.add(pipeline + "|" + name)) {
            return;
        }
        missingAttributeCount++;
        System.err.println("[MetalMod] " + pipeline + " reads vertex attribute '" + name
                + "' but its vertex descriptor does not provide it, so Metal will reject the pipeline");
    }

    public static synchronized int missingVertexAttributeCount() {
        return missingAttributeCount;
    }

    // Metal has no triangle fan, so a non-indexed fan is expanded into a triangle list by the native
    // side. An *indexed* fan cannot be expanded that way - its vertex order lives in the index
    // buffer - and no vanilla pipeline indexed-draws one, so report it rather than draw something
    // plausible but wrong.
    private static final java.util.Set<String> reportedIndexedFans = new java.util.HashSet<>();
    private static int indexedFanCount;

    // One line per distinct (colour target, pipeline) pair, so the question "which pipeline draws
    // into which target" is answered by a log rather than by reading the engine. The frame graph
    // maps its named targets onto a pool, so the label alone does not say where translucency is
    // rendered or where the composite reads from.
    private static final java.util.Set<String> notedPipelineTargets = new java.util.HashSet<>();

    static synchronized void notePipelineTarget(String target, String pipeline) {
        if (target == null || pipeline == null || !notedPipelineTargets.add(target + " <- " + pipeline)) {
            return;
        }
        System.out.println("[MetalMod] draw '" + pipeline + "' -> target '" + target + "'");
    }

    static synchronized void reportIndexedFan(String pipeline) {
        if (!reportedIndexedFans.add(pipeline)) {
            return;
        }
        indexedFanCount++;
        System.err.println("[MetalMod] " + pipeline + " indexed-draws a triangle fan, which Metal "
                + "cannot represent; drawing it as a triangle list instead");
    }

    public static synchronized int indexedFanCount() {
        return indexedFanCount;
    }

    public static synchronized int pipelineFailureCount() {
        return pipelineFailureCount;
    }

    // The engine clears its main render target with the sky/background colour; capture it so the
    // first-light clear on the drawable is the colour the renderer actually chose, not a constant.
    private final float[] lastClearColor = {0.06f, 0.09f, 0.16f, 1.0f};

    private MetalDevice(MemorySegment device, MemorySegment queue, DeviceInfo info) {
        this.device = device;
        this.queue = queue;
        this.info = info;
        this.transientMemory = new MetalTransientMemory(this);
    }

    public static MetalDevice create() {
        MemorySegment device = MetalNative.deviceCreate();
        if (device == null || device.address() == 0) {
            return null;
        }
        MemorySegment queue = MetalNative.queueCreate(device);
        if (queue == null || queue.address() == 0) {
            MetalNative.deviceRelease(device);
            return null;
        }
        // The present blit must use the same queue as the render passes so the GPU sees them in
        // commit order; two queues can race and wedge.
        MetalNative.layerSetPresentQueue(queue);

        String[] strings = MetalNative.deviceInfo(device);
        long maxTexture = MetalNative.deviceMaxTextureSize(device);
        long maxBuffer = MetalNative.deviceMaxBufferSize(device);

        DeviceLimits limits = new DeviceLimits(
                /* maxAnisotropy */ 16,
                /* minUniformOffsetAlignment */ 256,
                (int) Math.min(maxTexture, Integer.MAX_VALUE),
                maxBuffer,
                // RenderPass refuses a batched draw whose count exceeds this, with an
                // IllegalArgumentException - so 0 does not mean "no batching", it means "calling
                // multiDrawIndexed at all throws". Vanilla never does, which is why it went
                // unnoticed, but it is a trap for any mod that batches. MetalRenderPassBackend loops
                // over the draw list, so the honest answer is "as many as you give me", and the
                // Vulkan backend reports exactly this fallback (Integer.MAX_VALUE) when
                // VK_EXT_multi_draw is unavailable.
                /* maxMultiDrawDirectInterleavedDrawCount */ Integer.MAX_VALUE,
                // The number of colour targets the pipeline builder actually covers. Reporting
                // Metal's own 8 would let the engine accept a multi-target pass that then renders
                // only its first attachment, which is a silent wrong frame rather than an error;
                // CommandEncoder.createRenderPass checks this value before the pass is created.
                /* maxColorAttachments */ MetalRenderPipeline.MAX_COLOR_ATTACHMENTS);
        // Reported per feature rather than all-false, because the engine uses these to decide which
        // paths it may take. Each value is a claim about what this backend actually implements:
        //
        //   shaderDrawParameters         no vanilla consumer; MetalMod neither provides nor needs it.
        //   multiDrawDirectInterleaved   } implemented: MetalRenderPassBackend loops over the draw
        //   multiDrawDirectSeparate      } list and issues one indexed draw per entry. RenderPass
        //                                gates multiDrawIndexed/multiDraw on these flags *as well as*
        //                                the draw-count limit, so reporting them false left those
        //                                methods throwing even after the limit was corrected.
        //   multiDrawIndirect            } NOT implemented - the two indirect draws below are no-ops,
        //   drawIndirect                 } so these must stay false or the engine would take a path
        //                                that silently draws nothing.
        //   nonZeroFirstInstance         TRUE: both native draws forward firstInstance as
        //                                baseInstance, and metalmod_smoke proves [[instance_id]]
        //                                includes it (instance colour 3 comes back for
        //                                firstInstance=3). Reporting false made the engine refuse to
        //                                pass a non-zero firstInstance at all - a needless limit.
        //   persistentMapping            false is correct: buffers are shared storage and written
        //                                directly, which is why writeToBufferIsSlow is also false and
        //                                StagingBuffer picks its CPU path.
        DeviceFeatures features = new DeviceFeatures(
                /* shaderDrawParameters */ false,
                /* multiDrawDirectInterleaved */ true,
                /* multiDrawDirectSeparate */ true,
                /* multiDrawIndirect */ false,
                /* drawIndirect */ false,
                /* nonZeroFirstInstance */ true,
                // persistentMapping false is still correct: buffers are shared storage and written
                // directly, and the engine only picks the persistently-mapped staging path when it
                // is true *and* writeToBufferIsSlow is true. Vulkan reports false for the latter
                // too, so both backends use StagingBuffer.Cpu; what made that path wrong was the
                // synchronous writeToBuffer, not the staging choice (BUG-023).
                /* persistentMapping */ false);
        DeviceInfo info = new DeviceInfo(
                strings[0], strings[1], strings[2],
                /* isZZeroToOne */ true,
                "Metal",
                /* timestampPeriod */ 1.0f,
                limits, features,
                Set.of(),
                new HintsAndWorkarounds(false, false),
                DeviceType.INTEGRATED);

        System.out.println("[MetalMod] Metal device: " + strings[0]
                + " | maxTexture=" + maxTexture + " | maxBuffer=" + maxBuffer);
        return new MetalDevice(device, queue, info);
    }

    // One 2D texture per texel-buffer backing buffer, keyed by handle and size. The data changes
    // every frame (the cloud faces are rebuilt), so the bytes are re-uploaded on each use while the
    // texture itself is reused.
    private final Map<Long, MetalTexture> texelTextures = new HashMap<>();

    /**
     * Present a texel-buffer uniform's bytes as a 2D texture the shader can read.
     *
     * <p>SPIRV-Cross emits {@code texture2d<T>} plus {@code spvTexelBufferCoord()} for these, not a
     * native {@code texture_buffer<T>} — and Metal cannot view a buffer as a 2D texture anyway, so
     * the bytes are copied in. Cached per backing buffer to avoid a texture allocation per frame.
     */
    public synchronized MetalTexture texelTexture(MetalBuffer buffer, long offset, long length,
                                                  GpuFormat format, int bytesPerTexel) {
        int perTexel = Math.max(1, bytesPerTexel);
        int texels = (int) Math.max(1L, length / perTexel);
        long needed = (long) texels * perTexel;
        if (buffer == null || !buffer.isMapped() || offset < 0
                || offset + needed > buffer.data().byteSize()) {
            return null;
        }
        // The shader flattens a linear texel index with `tc % W, tc / W` for a literal W
        // (MetalShaderCompiler.TEXEL_BUFFER_WIDTH), so the texture has to be W wide and as tall as
        // the data needs. It used to be `texels x 1`, which reads the wrong texels past W texels and
        // is rejected by Metal outright once `texels` passes the device's 16384 limit - the cloud
        // buffer reaches six figures, so entering a world aborted on texture creation.
        int width = MetalShaderCompiler.TEXEL_BUFFER_WIDTH;
        int height = (texels + width - 1) / width;
        // Keyed by handle, texel count and format: two pipelines could present the same buffer with
        // different formats, and the cached texture would then be the wrong one.
        long key = buffer.handle().address() * 1_000_003L + texels * 31L + format.ordinal();
        MetalTexture texture = this.texelTextures.get(key);
        if (texture == null) {
            texture = new MetalTexture(this, GpuTexture.USAGE_TEXTURE_BINDING,
                    "texel buffer", format, width, height, 1, 1);
            if (!texture.isValid()) {
                return null;
            }
            this.texelTextures.put(key, texture);
        }
        // Uploaded as whole rows plus a last partial one. Padding the tail to a full row would read
        // past the end of the backing buffer, which is a ring allocation the engine owns.
        int fullRows = texels / width;
        if (fullRows > 0) {
            long bytes = (long) width * fullRows * perTexel;
            MetalNative.textureReplaceRegionRaw(texture.handle(), 0, 0, 0, 0, width, fullRows,
                    buffer.dataSlice(offset, bytes), (long) width * perTexel);
        }
        int remainder = texels % width;
        if (remainder > 0) {
            long start = offset + (long) width * fullRows * perTexel;
            long bytes = (long) remainder * perTexel;
            MetalNative.textureReplaceRegionRaw(texture.handle(), 0, 0, 0, fullRows, remainder, 1,
                    buffer.dataSlice(start, bytes), bytes);
        }
        return texture;
    }

    public MemorySegment deviceHandle() {
        return this.device;
    }

    public MemorySegment queueHandle() {
        return this.queue;
    }

    public MetalTransientMemory transientMemoryObject() {
        return this.transientMemory;
    }

    public synchronized void setLastClearColor(float r, float g, float b, float a) {
        this.lastClearColor[0] = r;
        this.lastClearColor[1] = g;
        this.lastClearColor[2] = b;
        this.lastClearColor[3] = a;
    }

    public synchronized float[] copyLastClearColor() {
        return this.lastClearColor.clone();
    }

    // -----------------------------------------------------------------------------------------
    // GpuDeviceBackend
    // -----------------------------------------------------------------------------------------

    @Override
    public GpuSurfaceBackend createSurface(long window) {
        long nsWindow = 0L;
        try {
            nsWindow = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(window);
        } catch (Throwable t) {
            System.err.println("[MetalMod] glfwGetCocoaWindow failed: " + t);
        }
        if (nsWindow == 0L) {
            throw new IllegalStateException("glfwGetCocoaWindow returned NULL; cannot attach a CAMetalLayer");
        }
        MemorySegment layer = MetalNative.layerCreateForNsWindow(nsWindow);
        if (layer == null || layer.address() == 0) {
            throw new IllegalStateException("Failed to create a CAMetalLayer on the game window content view");
        }
        System.out.println("[MetalMod] CAMetalLayer attached to the GLFW NSWindow content view.");
        return new MetalSurfaceBackend(this, layer);
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return new MetalCommandEncoderBackend(this);
    }

    @Override
    public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV,
                                    FilterMode minFilter, FilterMode magFilter,
                                    int maxAnisotropy, OptionalDouble maxLod) {
        SAMPLER_COUNT.incrementAndGet();
        return new MetalSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(Supplier<String> label, int usage, GpuFormat format,
                                    int width, int height, int depthOrLayers, int mipLevels) {
        String name = label == null ? "unnamed" : label.get();
        TEXTURE_COUNT.incrementAndGet();
        return new MetalTexture(this, usage, name, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTexture createTexture(String label, int usage, GpuFormat format,
                                    int width, int height, int depthOrLayers, int mipLevels) {
        TEXTURE_COUNT.incrementAndGet();
        return new MetalTexture(this, usage, label, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture) {
        TEXTURE_VIEW_COUNT.incrementAndGet();
        return new MetalTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        TEXTURE_VIEW_COUNT.incrementAndGet();
        return new MetalTextureView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
        BUFFER_COUNT.incrementAndGet();
        long safeSize = Math.max(1L, size);
        MemorySegment handle = MetalNative.bufferCreate(this.device, safeSize);
        MemorySegment memory = (handle.address() == 0)
                ? MemorySegment.NULL
                : MetalNative.bufferContents(handle, safeSize);
        if (memory.address() == 0) {
            reportResourceFailure("buffer size=" + safeSize + " usage=" + usage);
        }
        return new MetalBuffer(usage, safeSize, handle, memory, true, null);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
        BUFFER_COUNT.incrementAndGet();
        int requested = data.remaining();
        long safeSize = Math.max(1L, requested);
        MemorySegment handle = MetalNative.bufferCreate(this.device, safeSize);
        MemorySegment memory = (handle.address() == 0)
                ? MemorySegment.NULL
                : MetalNative.bufferContents(handle, safeSize);
        if (memory.address() == 0) {
            reportResourceFailure("buffer (initial data) size=" + safeSize + " usage=" + usage);
        } else if (requested > 0) {
            memory.asByteBuffer().put(data.duplicate());
        }
        return new MetalBuffer(usage, safeSize, handle, memory, true, null);
    }

    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public synchronized CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, ShaderSource shaderSource) {
        if (shaderSource != null) {
            this.lastShaderSource = shaderSource;
        }
        String key = pipeline.getLocation().toString();
        // The one-argument GpuDevice.precompilePipeline(pipeline) - which is what PostChain uses for
        // every post-processing pass - arrives here with a null source. Resolving it to the source
        // the engine gave us for the static pipelines is what lets those passes compile eagerly.
        // Returning a marker instead left them entirely to the lazy draw path, which silently draws
        // nothing at all until some source has been seen.
        ShaderSource effective = shaderSource != null ? shaderSource : this.lastShaderSource;
        if (effective == null) {
            // Genuinely nothing to compile with. Counted rather than treated as ready, so the
            // telemetry shows it instead of the pass quietly disappearing.
            reportResourceFailure("no shader source available yet for " + key);
            return new MetalCompiledPipeline(true);
        }
        MetalRenderPipeline compiled = MetalRenderPipeline.create(this, this.shaderCompiler, pipeline, effective);
        SHADER_COMPILER_SUMMARY.set(this.shaderCompiler.cacheSummary());
        if (compiled != null) {
            MetalRenderPipeline previous = this.pipelines.put(pipeline, compiled);
            if (previous != null) {
                previous.close();
            }
            this.failedPipelines.remove(pipeline);
            if (this.pipelineLogCount < 30) {
                this.pipelineLogCount++;
                System.out.println("[MetalMod] metal pipeline compiled: " + key);
            }
        } else {
            this.failedPipelines.add(pipeline);
            // Failures must NOT share the success budget. The engine precompiles the in-world
            // pipelines (terrain, lightmap, clouds) only after ~30 have already been logged, so a
            // shared cap silently swallows exactly the failures worth seeing - and a failed pipeline
            // just draws nothing, which reads as missing geometry rather than a shader error.
            pipelineFailureCount++;
            if (this.pipelineFailureLogCount < 100) {
                this.pipelineFailureLogCount++;
                System.err.println("[MetalMod] metal pipeline FAILED (draws with it are skipped): " + key);
            }
        }
        // Always valid: ShaderManager throws when a precompiled pipeline reports invalid, which would
        // take the game down before it can render anything. A failed pipeline simply has no native
        // state, and draws through it are skipped.
        return new MetalCompiledPipeline(true);
    }

    /**
     * The compiled pipeline for a RenderPipeline, or null when compilation failed. Compiles lazily on
     * first use for pipelines the engine never precompiled.
     */
    public synchronized MetalRenderPipeline pipelineFor(RenderPipeline pipeline) {
        MetalRenderPipeline existing = this.pipelines.get(pipeline);
        if (existing != null) {
            return existing;
        }
        if (this.failedPipelines.contains(pipeline) || this.lastShaderSource == null) {
            return null;
        }
        MetalRenderPipeline compiled = MetalRenderPipeline.create(this, this.shaderCompiler, pipeline,
                this.lastShaderSource);
        SHADER_COMPILER_SUMMARY.set(this.shaderCompiler.cacheSummary());
        if (compiled == null) {
            this.failedPipelines.add(pipeline);
            pipelineFailureCount++;
            if (this.pipelineFailureLogCount < 100) {
                this.pipelineFailureLogCount++;
                System.err.println("[MetalMod] metal pipeline FAILED (draws with it are skipped): "
                        + pipeline.getLocation() + ": " + MetalNative.lastError());
            }
            return null;
        }
        this.pipelines.put(pipeline, compiled);
        if (this.pipelineLogCount < 40) {
            this.pipelineLogCount++;
            System.out.println("[MetalMod] metal pipeline compiled lazily: " + pipeline.getLocation());
        }
        return compiled;
    }

    @Override
    public void clearPipelineCache() {
        for (MetalRenderPipeline compiled : this.pipelines.values()) {
            compiled.close();
        }
        this.pipelines.clear();
        this.failedPipelines.clear();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.queue != null && this.queue.address() != 0) {
            MetalNative.queueRelease(this.queue);
        }
        this.clearPipelineCache();
        for (MetalTexture texture : this.texelTextures.values()) {
            texture.close();
        }
        this.texelTextures.clear();
        this.shaderCompiler.close();
        this.transientMemory.close();
        if (this.device != null && this.device.address() != 0) {
            MetalNative.deviceRelease(this.device);
        }
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new MetalQueryPool(size);
    }

    @Override
    public long getTimestampNow() {
        return System.nanoTime();
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return this.info;
    }
}
