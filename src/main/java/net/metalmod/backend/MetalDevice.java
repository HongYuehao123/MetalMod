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
import net.metalmod.lighting.LightClusterGrid;
import net.metalmod.lighting.LightingSettings;
import net.metalmod.lighting.LightCollector;
import net.metalmod.lighting.LightSnapshot;
import net.metalmod.lighting.PointLight;

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

    // Startup-only experimental switches. The snapshot they publish is immutable, so one buffer per
    // frame can safely be shared by every draw in that frame - and only when a pipeline actually
    // declares the light block, which the render pass checks through the compiled pipeline.
    // Sampled when the device is created, then re-sampled from the settings screen. Deliberately not
    // final: a lighting variant is chosen at pipeline *compile* time, so a toggle has to be able to
    // invalidate the compiled pipelines and let them rebuild - see applyPendingLightingSettings().
    // volatile because the settings screen writes them and the render thread reads them.
    private volatile boolean pointLightProof = LightingSettings.pointLightProof();
    private volatile boolean dynamicLights = LightingSettings.dynamicLights();
    // Cluster lists are opt-in on top of dynamic lights, so the flat list stays available as a
    // comparison baseline and as a fallback if the clustered path misbehaves in a scene.
    private volatile boolean clusteredLights = LightingSettings.clusteredLights();

    /** Set from the settings screen; adopted at the next frame boundary by the render thread. */
    private static final java.util.concurrent.atomic.AtomicBoolean LIGHTING_REBUILD =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The device the game is currently drawing with, for the F3 section and the settings screen.
     *
     * <p>The game has exactly one; the offline tools create several in a row, and they only ever read
     * the one they hold, so this is a diagnostic pointer rather than shared state.
     */
    private static volatile MetalDevice ACTIVE;

    /** Ask the render thread to adopt the current lighting settings at the next frame boundary. */
    public static void requestLightingRebuild() {
        LIGHTING_REBUILD.set(true);
    }

    /** Whether the game currently has a live Metal device, and with which lighting switches. */
    public static MetalDevice active() {
        return ACTIVE;
    }

    /**
     * Adopt any lighting change the settings screen requested.
     *
     * <p>Called at the end of a presented frame, never mid-frame: the change drops every compiled
     * pipeline, and a render pass created earlier in the same frame still holds the old handle. The
     * compiled *shader pairs* are kept - the pair cache is keyed on the variant and its sources, so a
     * variant change compiles the new pair and leaves the old one cached for switching back.
     */
    public void applyPendingLightingSettings() {
        if (!LIGHTING_REBUILD.compareAndSet(true, false)) {
            return;
        }
        boolean proof = LightingSettings.pointLightProof();
        boolean dynamic = LightingSettings.dynamicLights();
        boolean clustered = LightingSettings.clusteredLights();
        if (proof == this.pointLightProof && dynamic == this.dynamicLights
                && clustered == this.clusteredLights) {
            return;
        }
        this.pointLightProof = proof;
        this.dynamicLights = dynamic;
        this.clusteredLights = clustered;
        invalidatePipelines();
        if (!dynamic) {
            // Nothing reads the set any more, and leaving it published would keep a stale light on F3.
            LightCollector.clear();
        }
        System.out.println("[MetalMod] lighting settings applied: " + LightingSettings.summary());
    }

    /**
     * Drop every compiled pipeline, keeping the compiled shader pairs.
     *
     * <p>What a lighting toggle needs: the pipeline objects embed the chosen variant, while the pair
     * cache is already keyed on variant and source. Cheaper than {@link #clearPipelineCache()}, which
     * is for a resource reload where the shader text itself changed.
     */
    public synchronized void invalidatePipelines() {
        for (MetalRenderPipeline compiled : this.pipelines.values()) {
            compiled.close();
        }
        this.pipelines.clear();
        this.failedPipelines.clear();
        this.pointLightFallbacks.clear();
    }
    private final LightClusterGrid clusterGrid = new LightClusterGrid();
    private GpuSampler backendSampler;
    private static volatile LightClusterGrid.Stats PUBLISHED_CLUSTER_STATS =
            new LightClusterGrid.Stats(0, 0, 0, 0, 0, 0, 0, 0);
    private GpuBuffer pointLightProofBuffer;
    // Three buffer sets, rotated per presented frame. A set is only rewritten after the fence taken
    // when it was last used has signalled, so a frame the GPU is still reading is never overwritten.
    private final LightFrame[] lightFrames = new LightFrame[3];
    private final MemorySegment[] lightFences = new MemorySegment[3];
    private int lightFrameIndex;
    private LightFrame currentLightFrame;
    private long uploadedLightGeneration = -1;
    private boolean currentFrameClustered;
    private final Set<String> pointLightFallbacks = new HashSet<>();
    private final Set<String> lightingVariants = new HashSet<>();
    private int variantLogCount;

    /** One frame's published light set: the flat record list, plus the clustered grid and texture. */
    private static final class LightFrame {
        MetalBuffer list;
        MetalBuffer grid;
        GpuTexture data;
        GpuTextureView dataView;
    }

    public boolean pointLightProofEnabled() { return this.pointLightProof; }
    public boolean dynamicLightsEnabled() { return this.dynamicLights; }
    public boolean clusteredLightsEnabled() { return this.clusteredLights; }

    /**
     * The light-set buffer for this frame, encoded from the published snapshot.
     *
     * <p>One upload per frame, not per light and not per draw: the snapshot is an immutable record,
     * and every pipeline that declares the block is handed the same buffer. The upload is refreshed
     * the first time the buffer is asked for after any change to the frame, so a set published
     * between two draws of one frame still reaches the GPU rather than being dropped with the old
     * contents.
     */
    GpuBuffer dynamicLightsBuffer() {
        return publishLightFrame(false).list;
    }

    /**
     * The cluster data texture: header, per-cell record indices and the light records.
     *
     * <p>A texture, not a uniform array: the table is tens of kilobytes, and {@code texelFetch}
     * addresses it with integer arithmetic that does not depend on array stride rules. One texture is
     * reused per frame and re-uploaded only when the published light set changes.
     */
    GpuTextureView lightDataView() {
        publishLightFrame(true);
        return this.currentLightFrame.dataView;
    }

    /** The texture object behind {@link #lightDataView()}, for the render pass's deferred binding. */
    GpuTexture lightDataTexture() {
        publishLightFrame(true);
        return this.currentLightFrame.data;
    }

    /** The cluster window origin in the camera-relative float frame, as one vec4. */
    GpuBuffer lightGridBuffer() {
        return publishLightFrame(true).grid;
    }

    /**
     * Upload the published light set for this frame, once, into its ring slot.
     *
     * <p>{@code clustered} is part of what has been uploaded: one frame can contain both a clustered
     * pipeline and a plain light-set pipeline, and each must be given the buffers it declares.
     */
    private LightFrame publishLightFrame(boolean clustered) {
        if (!this.dynamicLights) throw new IllegalStateException("Dynamic lights are disabled");
        if (clustered && !this.clusteredLights) {
            throw new IllegalStateException("Clustered lights are disabled");
        }
        long generation = LightCollector.generation();
        if (this.currentLightFrame == null) {
            this.currentLightFrame = claimLightFrame();
            this.uploadedLightGeneration = -1;
        } else if (clustered != this.currentFrameClustered && this.uploadedLightGeneration == generation) {
            // The other representation has not been built for this generation yet.
            this.uploadedLightGeneration = -1;
        }
        if (generation != this.uploadedLightGeneration) {
            LightSnapshot snapshot = LightCollector.current();
            upload(this.currentLightFrame.list, "MetalMod dynamic lights", snapshot.encode());
            if (this.clusteredLights) {
                this.clusterGrid.build(snapshot);
                upload(this.currentLightFrame.grid, "MetalMod light grid",
                        this.clusterGrid.encodeGridUniform());
                uploadClusterTexture(snapshot);
                PUBLISHED_CLUSTER_STATS = this.clusterGrid.stats();
            }
            this.uploadedLightGeneration = generation;
            this.currentFrameClustered = clustered;
        }
        return this.currentLightFrame;
    }

    /** Write the cluster header, cell table and light records into this frame's data texture. */
    private void uploadClusterTexture(LightSnapshot snapshot) {
        LightFrame frame = this.currentLightFrame;
        if (frame.data == null || frame.dataView == null) return;
        float[] texels = this.clusterGrid.encodeTexels();
        float[] records = this.clusterGrid.encodeRecords(snapshot);
        System.arraycopy(records, 0, texels, LightClusterGrid.recordTexel(0) * 4, records.length);
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocateDirect(texels.length * 4)
                .order(java.nio.ByteOrder.nativeOrder());
        bytes.asFloatBuffer().put(texels);
        bytes.position(0).limit(texels.length * 4);
        try {
            CommandEncoderBackend encoder = createCommandEncoder();
            encoder.writeToTexture(frame.data, bytes, 0, 0, 0, 0,
                    LightClusterGrid.TEXELS_PER_ROW, LightClusterGrid.TEXEL_ROWS);
            encoder.submit();
        } catch (Throwable failure) {
            // The clustered variant must not take the frame down: fall back to an unreachable source
            // distribution (an empty grid), so terrain renders with vanilla baked light instead.
            reportResourceFailure("light data texture upload: " + failure);
            frame.dataView = null;
        }
    }

    private static void upload(MetalBuffer buffer, String label, java.nio.ByteBuffer data) {
        if (!buffer.isMapped()) throw new IllegalStateException(label + " is unmapped");
        buffer.mappedBytes().put(data);
    }

    /** Wait for this slot's previous frame, then hand back the buffer set to write into. */
    private LightFrame claimLightFrame() {
        MemorySegment fence = this.lightFences[this.lightFrameIndex];
        if (fence != null && fence.address() != 0) {
            if (!MetalNative.fenceWait(fence, Long.MAX_VALUE)) {
                throw new IllegalStateException("Timed out waiting to reuse a dynamic light buffer");
            }
            MetalNative.fenceRelease(fence);
            this.lightFences[this.lightFrameIndex] = null;
        }
        LightFrame frame = this.lightFrames[this.lightFrameIndex];
        if (frame == null) {
            frame = new LightFrame();
            this.lightFrames[this.lightFrameIndex] = frame;
        }
        if (frame.list == null) {
            frame.list = (MetalBuffer) createBuffer(() -> "MetalMod dynamic lights",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, LightSnapshot.BYTES);
        }
        if (this.clusteredLights && frame.grid == null) {
            // Allocated only when the clustered variant is on, so the flat path pays nothing for it.
            frame.grid = (MetalBuffer) createBuffer(() -> "MetalMod light grid",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, 16);
        }
        if (this.clusteredLights && frame.data == null) {
            frame.data = createTexture("MetalMod light data",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA32_FLOAT, LightClusterGrid.TEXELS_PER_ROW, LightClusterGrid.TEXEL_ROWS,
                    1, 1);
            if (frame.data != null) {
                frame.dataView = createTextureView(frame.data);
            }
        }
        return frame;
    }

    /** Whatever the cluster grid measured on its last build, for the tests. */
    public LightClusterGrid.Stats clusterStats() {
        return this.clusterGrid.stats();
    }

    /**
     * The same numbers after the last GPU publication, for the F3 section.
     *
     * <p>Static because the debug screen has no device reference; the game only ever has one Metal
     * device, and this is a diagnostic snapshot rather than state anything renders from.
     */
    public static LightClusterGrid.Stats lastClusterStats() {
        return PUBLISHED_CLUSTER_STATS;
    }

    /**
     * Close the light frame at present: fence the slot in use and advance the ring.
     *
     * <p>Called on the present path, so a light set is published at most once per presented frame,
     * and a frame that never presents never leaves a buffer unfenced.
     */
    public void endDynamicLightFrame() {
        if (this.currentLightFrame == null) return;
        MemorySegment fence = MetalNative.fenceCreate(this.queue);
        if (fence == null || fence.address() == 0) {
            throw new IllegalStateException("Could not fence dynamic light buffer reuse");
        }
        this.lightFences[this.lightFrameIndex] = fence;
        this.currentLightFrame = null;
        this.lightFrameIndex = (this.lightFrameIndex + 1) % this.lightFrames.length;
    }

    /**
     * Report once per pipeline that a lighting variant was applied to it.
     *
     * <p>The positive counterpart of {@link #reportPointLightFallback}: without it a log shows that
     * pipelines compiled but not that any of them were lit, which is the question a support log is
     * actually asking.
     */
    void noteLightingVariant(String variant, String pipeline) {
        if (this.variantLogCount < 12 && this.lightingVariants.add(variant + "|" + pipeline)) {
            this.variantLogCount++;
            System.out.println("[MetalMod] lighting variant " + variant + " applied to " + pipeline);
        }
    }

    /** Report once per pipeline that a terrain shader could not be recognised and was left alone. */
    void reportPointLightFallback(String pipeline) {
        if (this.pointLightFallbacks.add(pipeline)) {
            System.err.println("[MetalMod] point-light proof skipped for " + pipeline
                    + ": unrecognized terrain shader source; keeping supplied shaders");
        }
    }

    /** The 6A proof's synthetic source: camera-centred, radius 8, amber, unshadowed. */
    GpuBuffer pointLightProofBuffer() {
        if (!this.pointLightProof) throw new IllegalStateException("Point-light proof is disabled");
        if (this.pointLightProofBuffer == null) {
            this.pointLightProofBuffer = createBuffer(() -> "MetalMod synthetic point light",
                    GpuBuffer.USAGE_UNIFORM,
                    new PointLight(0, 0, 0, 8, 1, 0.65f, 0.3f, 1).relativeTo(0, 0, 0));
            System.out.println("[MetalMod] point-light proof: camera-centred, radius 8, terrain only, unshadowed");
        }
        return this.pointLightProofBuffer;
    }

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
                + " privateTextures=" + privateTextureCount
                + " privateCpuAccess=" + privateCpuAccessCount
                + " failures=" + resourceFailureCount
                + " pipelineFailures=" + pipelineFailureCount
                + " unboundBindings=" + unboundBindingCount()
                + " missingVertexAttributes=" + missingVertexAttributeCount()
                + " slotCollisions=" + slotCollisionCount()
                + " bindingKindMismatches=" + bindingKindMismatchCount()
                + " indexedFans=" + indexedFanCount()
                + " | " + SHADER_COMPILER_SUMMARY.get();
    }

    // Textures created with private storage, i.e. the ones the CPU is refused access to. Counted and
    // listed a bounded number of times: the list says which resources the storage-mode rule actually
    // claimed, which is the evidence for whether it is doing anything on a given scene.
    private static int privateTextureCount;
    private static int privateTextureLogCount;

    static synchronized void notePrivateTexture(String label, Object format, int width, int height,
                                                int depthOrLayers, int usage) {
        privateTextureCount++;
        if (privateTextureLogCount < 12) {
            privateTextureLogCount++;
            System.err.println("[MetalMod] private storage: '" + label + "' " + format + " "
                    + width + "x" + height + "x" + depthOrLayers + " usage=" + usage);
        }
    }

    public static synchronized int privateTextureCount() {
        return privateTextureCount;
    }

    // A CPU upload or readback aimed at a private texture. It cannot work - the bytes live in
    // GPU-private memory - so it is counted loudly rather than silently dropping the upload. Any
    // non-zero value means the storage-mode rule misclassified a texture and this is the bug report.
    private static int privateCpuAccessCount;
    private static int privateCpuAccessLogCount;

    static synchronized void reportPrivateCpuAccess(String operation, String label) {
        privateCpuAccessCount++;
        if (privateCpuAccessLogCount < 20) {
            privateCpuAccessLogCount++;
            System.err.println("[MetalMod] " + operation + " on private texture '" + label
                    + "' refused - this texture needs USAGE_COPY_SRC/USAGE_COPY_DST"
                    + " (further attempts counted silently)");
        }
    }

    public static synchronized int privateCpuAccessCount() {
        return privateCpuAccessCount;
    }

    // Set by MetalDevice so the telemetry summary can report shader cache effectiveness.
    static final java.util.concurrent.atomic.AtomicReference<String> SHADER_COMPILER_SUMMARY =
            new java.util.concurrent.atomic.AtomicReference<>("shader pairs: n/a");

    static synchronized void reportResourceFailure(String what) {
        resourceFailureCount++;
        if (resourceFailureLogCount < 20) {
            resourceFailureLogCount++;
            System.err.println("[MetalMod] Metal resource operation failed: " + what
                    + " (further failures counted silently)");
        }
    }

    public static synchronized int resourceFailureCount() {
        return resourceFailureCount;
    }

    // ---------------------------------------------------------------------------------------------
    // Frame timing and drawable acquisition (F3 averages).
    //
    // Drawable acquisition measures one wait site, not the CPU/GPU split. Fence waits, readbacks
    // and command-buffer allocation can also stall the render thread. F8 records those separately.
    // A large drawable wait may also be display pacing. No actual GPU execution time is reported;
    // summing command-buffer GPUStartTime/GPUEndTime spans over-counts overlapping execution.
    //
    // All figures are window averages (~1s). The draw count is instantaneous: it describes the frame
    // just encoded, and averaging it would only hide a spike.
    // ---------------------------------------------------------------------------------------------

    private static final int TIMING_WINDOW_FRAMES = 60;

    private static int drawsThisFrame;
    private static int commandBuffersThisFrame;
    private static int copiesThisFrame;
    private static int fencesThisFrame;
    private static int framesInWindow;
    private static double frameMsSumInWindow;
    private static double acquireWaitMsSumInWindow;
    private static long commandBuffersSumInWindow;
    private static long copiesSumInWindow;
    private static long fencesSumInWindow;
    private static long ffiSumInWindow;
    private static long lastFfiSample;

    private static volatile int lastFrameDraws;
    private static volatile float lastFrameMs;
    private static volatile float lastAcquireWaitMs;
    private static volatile long lastCommandBuffers;
    private static volatile long lastCopies;
    private static volatile long lastFences;
    private static volatile long lastFfiCalls;
    private static long lastFrameNanos;

    /** Count one encoded draw. Called from the render pass backend, on the render thread. */
    static void countDraw() {
        drawsThisFrame++;
    }

    /** Count one committed command buffer, so F3 shows how much submission work a frame does. */
    static void countCommandBuffer() {
        commandBuffersThisFrame++;
    }

    /**
     * A buffer upload/copy (mesh staging, uniform write) or a texture copy. Each commits a
     * command buffer; only CPU buffer writes allocate staging. F8 records individual frame
     * counters and durations to investigate a possible relationship with hitches.
     */
    static void countCopy() {
        copiesThisFrame++;
    }

    /** A fence, which allocates a shared event and commits a signal command buffer. */
    static void countFence() {
        fencesThisFrame++;
    }

    /**
     * Record how long acquireNextTexture() took, in milliseconds, including drawable/display wait.
     */
    static void noteAcquireWait(double millis) {
        acquireWaitMsSumInWindow += Math.max(0.0, millis);
    }

    /**
     * Close out a frame at present: accumulate the interval, the acquire wait and the command-buffer
     * count into the current timing window and, once the window is full, publish the averages.
     */
    public static void endFrame() {
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            frameMsSumInWindow += (now - lastFrameNanos) / 1_000_000.0;
        }
        lastFrameNanos = now;
        lastFrameDraws = drawsThisFrame;
        drawsThisFrame = 0;
        commandBuffersSumInWindow += commandBuffersThisFrame;
        commandBuffersThisFrame = 0;
        copiesSumInWindow += copiesThisFrame;
        copiesThisFrame = 0;
        fencesSumInWindow += fencesThisFrame;
        fencesThisFrame = 0;
        long ffiNow = MetalNative.ffiCallCount();
        ffiSumInWindow += ffiNow - lastFfiSample;
        lastFfiSample = ffiNow;
        framesInWindow++;
        noteCensusFrame();

        if (framesInWindow >= TIMING_WINDOW_FRAMES) {
            lastFrameMs = (float) (frameMsSumInWindow / framesInWindow);
            lastAcquireWaitMs = (float) (acquireWaitMsSumInWindow / framesInWindow);
            lastCommandBuffers = commandBuffersSumInWindow / framesInWindow;
            lastCopies = copiesSumInWindow / framesInWindow;
            lastFences = fencesSumInWindow / framesInWindow;
            lastFfiCalls = ffiSumInWindow / framesInWindow;
            framesInWindow = 0;
            frameMsSumInWindow = 0.0;
            acquireWaitMsSumInWindow = 0.0;
            commandBuffersSumInWindow = 0L;
            copiesSumInWindow = 0L;
            fencesSumInWindow = 0L;
            ffiSumInWindow = 0L;
        }
    }

    /** Average wall-clock frame interval over the last timing window, in milliseconds. */
    public static float lastFrameMs() {
        return lastFrameMs;
    }

    /**
     * Average time per frame in acquireNextTexture(). This is one wait site, not total GPU wait.
     */
    public static float lastAcquireWaitMs() {
        return lastAcquireWaitMs;
    }

    /** Average render-pass command buffers per frame; a high count is submission overhead. */
    public static long lastCommandBuffers() {
        return lastCommandBuffers;
    }

    /** Draw calls encoded in the frame just presented; this is the number that grows underground. */
    public static int lastFrameDraws() {
        return lastFrameDraws;
    }

    /**
     * Average native (Panama) calls per frame. Every draw makes several, so this is the number that
     * says whether the FFI path is load-bearing - it is the one the invokeExact wrappers in
     * {@link MetalNative} exist to keep cheap.
     */
    public static long lastFfiCalls() {
        return lastFfiCalls;
    }

    /**
     * Average buffer uploads/copies and texture copies per frame. These are activity counts,
     * not allocation counts; F8 retains the individual frames instead of averaging away hitches.
     */
    public static long lastCopies() {
        return lastCopies;
    }

    /** Average fences per frame; each allocates a shared event and commits a signal command buffer. */
    public static long lastFences() {
        return lastFences;
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
    //
    // It is a bring-up census, not a steady-state diagnostic, and it sits in the hottest path there
    // is: every draw calls this, and every call builds a key string and takes this monitor. So
    // collection stops by itself after a few seconds (about 600 frames), by which time every
    // pipeline the session uses has been seen. `-Dmetalmod.census=on` keeps it running for a
    // dedicated debugging session, and the same switch governs the unbound-binding report.
    private static final java.util.Set<String> notedPipelineTargets = new java.util.HashSet<>();

    private static final boolean CENSUS_FORCED =
            "on".equalsIgnoreCase(System.getProperty("metalmod.census", ""));
    private static volatile boolean censusEnabled = true;
    private static long censusFrames;

    /** Whether the per-draw bring-up census (target log, unbound-binding report) is still running. */
    public static boolean censusEnabled() {
        return censusEnabled;
    }

    private static void noteCensusFrame() {
        if (censusEnabled && !CENSUS_FORCED && ++censusFrames > 600) {
            censusEnabled = false;
            System.out.println("[MetalMod] pipeline/target census stopped after " + censusFrames
                    + " frames (it runs per draw; -Dmetalmod.census=on keeps it).");
        }
    }

    static synchronized void notePipelineTarget(String target, String pipeline) {
        if (!censusEnabled) {
            return;
        }
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
        MetalDevice created = new MetalDevice(device, queue, info);
        ACTIVE = created;
        return created;
    }

    // One 2D texture per texel-buffer backing buffer, keyed by handle and size. The data changes
    // every frame (the cloud faces are rebuilt), so the bytes are re-uploaded on each use while the
    // texture itself is reused.
    private final Map<Long, MetalTexture> texelTextures = new HashMap<>();

    // Shared textures used to bring a private render target's contents back to the CPU. Small and
    // few (screenshots and diagnostics ask for one region size), so they are cached rather than
    // allocated per readback.
    private final Map<Long, MetalTexture> readbackStagingTextures = new HashMap<>();

    /**
     * A shared, CPU-readable texture of exactly this format and size, for reading a private texture
     * back through a blit. Null if it could not be created.
     */
    synchronized MetalTexture readbackStaging(GpuFormat format, int width, int height) {
        long key = ((long) format.ordinal() * 1_000_003L + width) * 1_000_003L + height;
        MetalTexture texture = this.readbackStagingTextures.get(key);
        if (texture == null) {
            // COPY_DST keeps it out of the private path: this is the one texture that must stay
            // CPU-visible, and it is declared as such rather than special-cased.
            texture = new MetalTexture(this, GpuTexture.USAGE_TEXTURE_BINDING
                    | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
                    "readback staging", format, width, height, 1, 1);
            if (!texture.isValid()) {
                return null;
            }
            this.readbackStagingTextures.put(key, texture);
        }
        return texture;
    }

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
            // USAGE_COPY_DST is what this really is: the rows below are uploaded from the CPU, so the
            // texture must keep shared storage. Declaring only USAGE_TEXTURE_BINDING would let the
            // storage-mode rule make it private and silently drop every upload.
            texture = new MetalTexture(this, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
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

    /**
     * A shared nearest-neighbour sampler for textures the backend binds itself.
     *
     * <p>The clustered light table is read with {@code texelFetch}, which ignores filtering; a sampler
     * object still has to be bound with the texture for the shader's signature to be complete.
     */
    public GpuSampler defaultSampler() {
        if (this.backendSampler == null) {
            this.backendSampler = createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, java.util.OptionalDouble.empty());
        }
        return this.backendSampler;
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
            memory.asByteBuffer().order(java.nio.ByteOrder.nativeOrder()).put(data.duplicate());
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
        // The compiled shader pairs are keyed on source text, so a resource reload that changes a
        // shader under the same identifier must drop them too - and the fallback log is per source
        // set, so it starts over as well.
        this.shaderCompiler.clearCache();
        this.pointLightFallbacks.clear();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (ACTIVE == this) {
            ACTIVE = null;
        }
        if (this.queue != null && this.queue.address() != 0) {
            MetalNative.queueRelease(this.queue);
        }
        this.clearPipelineCache();
        if (this.pointLightProofBuffer != null) this.pointLightProofBuffer.close();
        // Every in-flight light buffer is fenced: wait before releasing the memory it holds.
        for (MemorySegment fence : this.lightFences) {
            if (fence != null && fence.address() != 0) {
                MetalNative.fenceWait(fence, Long.MAX_VALUE);
                MetalNative.fenceRelease(fence);
            }
        }
        for (LightFrame frame : this.lightFrames) {
            if (frame == null) continue;
            if (frame.list != null) frame.list.close();
            if (frame.grid != null) frame.grid.close();
            if (frame.dataView != null) frame.dataView.close();
            if (frame.data != null) frame.data.close();
        }
        for (MetalTexture texture : this.texelTextures.values()) {
            texture.close();
        }
        this.texelTextures.clear();
        for (MetalTexture texture : this.readbackStagingTextures.values()) {
            texture.close();
        }
        this.readbackStagingTextures.clear();
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
