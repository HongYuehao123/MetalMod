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

    // Pipelines are mostly compiled ahead of time through precompilePipeline(), but the engine also
    // binds pipelines it never precompiled (the loading-screen 'mojang_logo', for instance). Keep the
    // most recent ShaderSource - lookups are by identifier, so any one will do - and compile on first
    // use so those pipelines draw instead of being silently skipped.
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
                + " unmappedVertexAttributes=" + unmappedAttributeCount()
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

    // A VertexFormat element whose name has no matching shader input is silently left out of the
    // vertex descriptor, and Metal then feeds the shader whatever the buffer happens to hold at that
    // attribute index. That is a nasty failure mode: the geometry is drawn, just wrongly. The line
    // shader, for instance, reads LineWidth - garbage there means a line expands to a screen-filling
    // quad rather than a hairline.
    private static final java.util.Set<String> reportedUnmappedAttributes = new java.util.HashSet<>();
    private static int unmappedAttributeCount;
    private static int unmappedAttributeLogCount;

    static synchronized void reportUnmappedVertexAttribute(String pipeline, String element) {
        if (reportedUnmappedAttributes.size() >= 64
                || !reportedUnmappedAttributes.add(pipeline + "|" + element)) {
            return;
        }
        unmappedAttributeCount++;
        if (unmappedAttributeLogCount < 20) {
            unmappedAttributeLogCount++;
            System.err.println("[MetalMod] vertex attribute '" + element + "' in " + pipeline
                    + " has no matching shader input, so it is missing from the vertex descriptor "
                    + "and the shader reads undefined data");
        }
    }

    public static synchronized int unmappedAttributeCount() {
        return unmappedAttributeCount;
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
                /* maxMultiDrawDirectInterleavedDrawCount */ 0,
                /* maxColorAttachments */ 8);
        DeviceFeatures features = new DeviceFeatures(
                false, false, false, false, false, false, false);
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
        // The engine announces some pipelines (the blur chain) without a ShaderSource. There is
        // nothing to compile, and those passes are skipped by the draw path.
        if (shaderSource == null) {
            return new MetalCompiledPipeline(true);
        }
        MetalRenderPipeline compiled = MetalRenderPipeline.create(this, this.shaderCompiler, pipeline, shaderSource);
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
