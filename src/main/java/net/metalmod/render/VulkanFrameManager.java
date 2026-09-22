package net.metalmod.render;

import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Coordinates the native Metal frame pipeline.
 *
 * Threading contract: every method that touches native pipeline state (configuration, frame
 * submission, jitter) must run on the render thread. Minecraft's render thread is captured the
 * first time {@link #onFrameBegin()} runs; configuration requested from any other thread (the
 * config GUI, the telemetry thread) is deferred and applied at the start of the next frame.
 */
public final class VulkanFrameManager {

    private static final VulkanFrameManager INSTANCE = new VulkanFrameManager();

    // Mirrors the metalmod_process_frame return codes in native/src/metalmod_internal.h
    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_PARAMS = -1;
    public static final int STATUS_NO_RUNTIME = -2;
    public static final int STATUS_NO_VULKAN_INTEROP = -3;
    public static final int STATUS_NO_COLOR_TEXTURE = -4;
    public static final int STATUS_MISSING_DEPTH_OR_MOTION = -5;
    public static final int STATUS_NO_PRESENTATION = -6;

    private static final long STATUS_LOG_INTERVAL_NANOS = 5_000_000_000L;

    private volatile int nativeWidth = 1920;
    private volatile int nativeHeight = 1080;
    private volatile int renderWidth = 1920;
    private volatile int renderHeight = 1080;

    private static final long FPS_WINDOW_NANOS = 500_000_000L; // 0.5 s

    private long frameIndex = 0;
    private long lastFrameNanos = 0;
    private long fpsWindowStartNanos = 0;
    private int fpsWindowFrames = 0;
    private volatile boolean configDirty = true;
    private volatile boolean cameraCut = false;

    // Active Vulkan Image handles (pointers). These stay NULL until the render backend registers
    // real VkImage handles via setVulkanImages(); nothing may invent them.
    private volatile MemorySegment currentWorldImage = MemorySegment.NULL;
    private volatile MemorySegment currentDepthImage = MemorySegment.NULL;
    private volatile MemorySegment currentMotionImage = MemorySegment.NULL;
    private volatile MemorySegment currentUIImage = MemorySegment.NULL;

    // Telemetry cache
    private volatile float cachedRenderFPS = 0.0f;
    private volatile float cachedPresentedFPS = 0.0f;
    private volatile float cachedGpuFrameTimeMs = 0.0f;

    // Pipeline status
    private volatile int pipelineStatus = STATUS_NO_VULKAN_INTEROP;
    private volatile boolean pipelineActive = false;
    private long lastStatusLogNanos = 0;
    private volatile boolean presentFailureReported = false;
    private volatile String lastReportedStatusText = null;

    // Camera data captured from the live projection matrix
    private volatile float lastFieldOfView = 70.0f;
    private volatile float lastNearPlane = 0.05f;
    private volatile float lastFarPlane = 1000.0f;

    // Persistent off-heap zero-allocation buffers
    private final Arena persistentArena = Arena.ofShared();
    private final MemorySegment persistentConfigSegment = MetalBridge.isAvailable() ? persistentArena.allocate(MetalBridge.CONFIG_LAYOUT) : MemorySegment.NULL;
    private final MemorySegment persistentParamsSegment = MetalBridge.isAvailable() ? persistentArena.allocate(MetalBridge.FRAME_PARAMS_LAYOUT) : MemorySegment.NULL;
    private final MemorySegment persistentTelemetrySegment = MetalBridge.isAvailable() ? persistentArena.allocate(MetalBridge.TELEMETRY_LAYOUT) : MemorySegment.NULL;

    public static VulkanFrameManager getInstance() {
        return INSTANCE;
    }

    public void updateDimensions(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (this.nativeWidth != width || this.nativeHeight != height) {
            this.nativeWidth = width;
            this.nativeHeight = height;
            recalculateRenderResolution();
            this.configDirty = true;
        }
    }

    /**
     * Called from Minecraft.resizeDisplay(). Only marks the resolution stale: it must NOT request
     * another resize, or the two would feed back into each other.
     */
    public void onDisplayResized() {
        this.configDirty = true;
    }

    public void updateFromMinecraft() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.getWindow() != null) {
                // In this build getWidth()/getHeight() ARE the framebuffer size in physical
                // pixels; getFramebufferWidth() no longer exists.
                int fbW = mc.getWindow().getWidth();
                int fbH = mc.getWindow().getHeight();
                if (fbW > 0 && fbH > 0 && (fbW != this.nativeWidth || fbH != this.nativeHeight)) {
                    updateDimensions(fbW, fbH);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Signal that the configuration changed so the native pipeline is rebuilt on the next frame.
     *
     * This deliberately does NOT resize anything. Scaling the main render target is not viable in
     * this architecture: the GUI lays out against the window size, so a smaller target makes its
     * scissor rectangles exceed the render area and the click handler throws
     * ("Scissor ... is out of bounds for render area"), which leaves the screen unresponsive.
     * Internal-resolution rendering needs a separate world target - see README "Known limitations".
     */
    public void markConfigDirty() {
        this.configDirty = true;
        recalculateRenderResolution();
    }

    public void triggerCameraCut() {
        this.cameraCut = true;
    }

    /** Record the live camera field of view (degrees) for temporal / frame-gen motion vectors. */
    public void setFieldOfView(float fovDegrees) {
        if (fovDegrees > 0.0f && Float.isFinite(fovDegrees)) {
            this.lastFieldOfView = fovDegrees;
        }
    }

    public void setClipPlanes(float nearPlane, float farPlane) {
        if (nearPlane > 0.0f && Float.isFinite(nearPlane)) this.lastNearPlane = nearPlane;
        if (farPlane > nearPlane && Float.isFinite(farPlane)) this.lastFarPlane = farPlane;
    }

    /**
     * Internal render resolution.
     *
     * Equal to the window framebuffer size, because scaling is not applied: shrinking the main
     * render target pushes the GUI's scissor rectangles outside the render area and breaks input
     * (see README, "Internal resolution scaling is not implemented"). Deriving a smaller number
     * here would only report a resolution that nothing renders at - which is exactly what made the
     * old title bar read "3840x2160 -> 5120x2880" while F3 said something else.
     *
     * Stage 0 replaces this with a separate world target, at which point the scaled size becomes
     * real.
     */
    private void recalculateRenderResolution() {
        this.renderWidth = this.nativeWidth;
        this.renderHeight = this.nativeHeight;
    }

    public int getRenderWidth() {
        return renderWidth;
    }

    public int getRenderHeight() {
        return renderHeight;
    }

    public int getNativeWidth() {
        return nativeWidth;
    }

    public int getNativeHeight() {
        return nativeHeight;
    }

    /**
     * Register the Vulkan images for the current frame.
     *
     * These must be real VkImage handles from the render backend, and are only meaningful once
     * {@link MetalBridge#registerVulkanDevice} has been called with the live VkDevice and
     * vkExportMetalObjectsEXT. Passing a texture id or any other synthetic value here would be
     * dereferenced as a native object by the driver.
     */
    public void setVulkanImages(
            MemorySegment worldImage,
            MemorySegment depthImage,
            MemorySegment motionImage,
            MemorySegment uiImage
    ) {
        this.currentWorldImage = worldImage;
        this.currentDepthImage = depthImage;
        this.currentMotionImage = motionImage;
        this.currentUIImage = uiImage;
    }

    public void onFrameBegin() {
        updateFromMinecraft();
        frameIndex++;

        // Real render cadence, measured on the render thread.
        //
        // Counted over a rolling window rather than an exponential average: a single multi-second
        // stall (a freeze, a chunk-load spike) would otherwise drag an exponential average down for
        // tens of frames and under-report the rate badly.
        long now = System.nanoTime();
        if (fpsWindowStartNanos == 0L) {
            fpsWindowStartNanos = now;
        }
        fpsWindowFrames++;
        long elapsed = now - fpsWindowStartNanos;
        if (elapsed >= FPS_WINDOW_NANOS) {
            cachedRenderFPS = (float) (fpsWindowFrames * 1_000_000_000.0 / elapsed);
            fpsWindowStartNanos = now;
            fpsWindowFrames = 0;
        }
        lastFrameNanos = now;

        if (MetalConfig.INSTANCE.scalingMode == MetalConfig.ScalingMode.TEMPORAL) {
            JitterHelper.advance(renderWidth, renderHeight);
        } else {
            JitterHelper.reset();
        }

        ensureConfigured();
        publishPipelineStatus();
    }

    /**
     * Publish what the pipeline is actually doing.
     *
     * The window title is the only status channel that does not depend on a mixin, so it must never
     * go stale. Previously it was only updated from onFramePresent(), which is no longer called,
     * leaving "waiting for render hooks" on screen even once every hook had applied.
     */
    private void publishPipelineStatus() {
        String status = "pipeline: " + getPipelineStatusText();
        if (!status.equals(lastReportedStatusText)) {
            lastReportedStatusText = status;
            MetalBridge.reportPipelineStatus(status);
        }
    }

    /**
     * Apply a pending configuration change. Runs on the render thread only, because it rebuilds
     * MTLTextures and MetalFX scalers that the frame pipeline is using.
     */
    private void ensureConfigured() {
        if (!configDirty) return;
        if (!MetalBridge.isAvailable() || persistentConfigSegment.equals(MemorySegment.NULL)) return;

        writeConfig();
        // Clear the flag even on failure: retrying a rejected configuration every frame would
        // rebuild the whole pipeline 60+ times a second.
        configDirty = false;

        int rc = MetalBridge.configure(persistentConfigSegment);
        if (rc != 0) {
            logStatus("metalmod_configure failed", rc);
        }
    }

    private void writeConfig() {
        MetalConfig config = MetalConfig.INSTANCE;
        persistentConfigSegment.set(ValueLayout.JAVA_INT, 0, renderWidth);
        persistentConfigSegment.set(ValueLayout.JAVA_INT, 4, renderHeight);
        persistentConfigSegment.set(ValueLayout.JAVA_INT, 8, nativeWidth);
        persistentConfigSegment.set(ValueLayout.JAVA_INT, 12, nativeHeight);
        persistentConfigSegment.set(ValueLayout.JAVA_INT, 16, config.scalingMode.getId());
        persistentConfigSegment.set(ValueLayout.JAVA_BOOLEAN, 20, config.frameGeneration);
        persistentConfigSegment.set(ValueLayout.JAVA_FLOAT, 24, config.sharpness);
        persistentConfigSegment.set(ValueLayout.JAVA_BOOLEAN, 28, config.enableHDR);
        persistentConfigSegment.set(ValueLayout.JAVA_BOOLEAN, 29, config.enableUIOverlay);
        persistentConfigSegment.set(ValueLayout.JAVA_INT, 32, config.targetDisplayFPS);
        persistentConfigSegment.set(ValueLayout.JAVA_BOOLEAN, 36, config.enableUnifiedMemoryPool);
        persistentConfigSegment.set(ValueLayout.JAVA_BOOLEAN, 37, config.enableMemoryPressureHandler);
    }

    /**
     * Submit the frame to the native pipeline. Called at the head of RenderTarget.blitToScreen.
     */
    public void onFramePresent(float fov, float nearPlane, float farPlane) {
        ensureConfigured();

        if (!MetalBridge.isAvailable() || persistentParamsSegment.equals(MemorySegment.NULL)) return;

        if (fov > 0.0f && Float.isFinite(fov)) lastFieldOfView = fov;
        if (nearPlane > 0.0f && Float.isFinite(nearPlane)) lastNearPlane = nearPlane;
        if (farPlane > nearPlane && Float.isFinite(farPlane)) lastFarPlane = farPlane;

        persistentParamsSegment.set(ValueLayout.JAVA_LONG, 0, frameIndex);
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 8, lastFrameDeltaSeconds());
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 12, JitterHelper.getJitterX());
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 16, JitterHelper.getJitterY());
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 20, lastNearPlane);
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 24, lastFarPlane);
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 28, lastFieldOfView);
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 32, (float) renderWidth / (float) renderHeight);
        persistentParamsSegment.set(ValueLayout.JAVA_BOOLEAN, 36, cameraCut);
        persistentParamsSegment.set(ValueLayout.JAVA_BOOLEAN, 37, true); // Reversed-Z

        cameraCut = false;

        // Only hand over image handles when the native side can actually consume them; passing
        // anything else is dereferenced as a native object and crashes the process.
        MemorySegment color = MetalBridge.hasVulkanInterop() ? currentWorldImage : MemorySegment.NULL;
        MemorySegment depth = MetalBridge.hasVulkanInterop() ? currentDepthImage : MemorySegment.NULL;
        MemorySegment motion = MetalBridge.hasVulkanInterop() ? currentMotionImage : MemorySegment.NULL;
        MemorySegment ui = MetalBridge.hasVulkanInterop() ? currentUIImage : MemorySegment.NULL;

        int rc;
        try {
            rc = MetalBridge.processFrame(color, depth, motion, ui, persistentParamsSegment);
        } catch (Throwable t) {
            onPresentFailure(t);
            return;
        }

        pipelineStatus = rc;
        boolean wasActive = pipelineActive;
        pipelineActive = (rc == STATUS_OK);
        if (!pipelineActive && (wasActive || rc != STATUS_NO_PRESENTATION)) {
            logStatus("frame pipeline inactive", rc);
        }

        // Keep the window title current. This is the only status channel that does not depend on a
        // mixin applying, so it is what distinguishes "hooks never ran" from "ran and did nothing".
        String statusText = pipelineActive ? "pipeline: active" : "pipeline: " + describeStatus(rc);
        if (!statusText.equals(lastReportedStatusText)) {
            lastReportedStatusText = statusText;
            MetalBridge.reportPipelineStatus(statusText);
        }

        // Readback telemetry periodically (zero allocations)
        if ((frameIndex & 15) == 0 && !persistentTelemetrySegment.equals(MemorySegment.NULL)) {
            try {
                MetalBridge.getTelemetry(persistentTelemetrySegment);
                cachedPresentedFPS = persistentTelemetrySegment.get(ValueLayout.JAVA_FLOAT, 4);
                cachedGpuFrameTimeMs = persistentTelemetrySegment.get(ValueLayout.JAVA_FLOAT, 8);
            } catch (Throwable ignored) {
            }
        }
    }

    private float lastFrameDeltaSeconds() {
        return 1.0f / Math.max(1.0f, cachedRenderFPS > 0.0f ? cachedRenderFPS : 60.0f);
    }

    /**
     * Report a failure raised while submitting a frame. Never rethrows: an exception escaping a
     * mixin callback would propagate into Minecraft's render loop.
     */
    public void onPresentFailure(Throwable t) {
        pipelineActive = false;
        pipelineStatus = STATUS_NO_RUNTIME;
        if (!presentFailureReported) {
            presentFailureReported = true;
            System.err.println("[MetalMod] Frame submission failed; the native pipeline is disabled "
                    + "for this session. Cause: " + t);
        }
    }

    private void logStatus(String what, int rc) {
        long now = System.nanoTime();
        if (now - lastStatusLogNanos < STATUS_LOG_INTERVAL_NANOS) return;
        lastStatusLogNanos = now;
        System.out.println("[MetalMod] " + what + " (status " + rc + ": " + describeStatus(rc) + ")");
    }

    public static String describeStatus(int rc) {
        return switch (rc) {
            case STATUS_OK -> "active";
            case STATUS_INVALID_PARAMS -> "invalid frame parameters";
            case STATUS_NO_RUNTIME -> "Metal runtime unavailable";
            case STATUS_NO_VULKAN_INTEROP -> "Vulkan interop not registered";
            case STATUS_NO_COLOR_TEXTURE -> "color texture export failed";
            case STATUS_MISSING_DEPTH_OR_MOTION -> "depth/motion vectors unavailable";
            case STATUS_NO_PRESENTATION -> "MetalMod does not own presentation";
            default -> "unknown status";
        };
    }

    public float getRenderFPS() {
        return cachedRenderFPS;
    }

    public float getPresentedFPS() {
        return cachedPresentedFPS;
    }

    public float getGpuFrameTimeMs() {
        return cachedGpuFrameTimeMs;
    }

    /** Whether the native pipeline actually processed the last frame successfully. */
    public boolean isPipelineActive() {
        return pipelineActive;
    }

    public int getPipelineStatus() {
        return pipelineStatus;
    }

    /**
     * Why the pipeline is (or is not) doing anything, as a single shared computation.
     *
     * The F3 overlay and the window title must report the same reason. They previously disagreed
     * because the overlay read a status field that stopped being updated when the old present hook
     * was removed, while the title computed its own answer.
     */
    public String getPipelineStatusText() {
        if (!MetalBridge.isAvailable()) {
            return "native library unavailable";
        }
        if (MetalConfig.INSTANCE.scalingMode == MetalConfig.ScalingMode.OFF
                && !MetalConfig.INSTANCE.frameGeneration) {
            return "disabled (all features off)";
        }
        if (!MetalBridge.hasVulkanInterop()) {
            return "inactive (Vulkan interop not registered)";
        }
        return "inactive (MetalMod does not own presentation)";
    }
}
