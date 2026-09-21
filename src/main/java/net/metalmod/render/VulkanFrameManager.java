package net.metalmod.render;

import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public final class VulkanFrameManager {

    private static final VulkanFrameManager INSTANCE = new VulkanFrameManager();

    private int nativeWidth = 1920;
    private int nativeHeight = 1080;
    private int renderWidth = 1920;
    private int renderHeight = 1080;

    private long frameIndex = 0;
    private long lastFrameNanos = System.nanoTime();
    private boolean configDirty = true;
    private boolean cameraCut = false;

    // Active Vulkan Image handles (pointers)
    private MemorySegment currentWorldImage = MemorySegment.NULL;
    private MemorySegment currentDepthImage = MemorySegment.NULL;
    private MemorySegment currentMotionImage = MemorySegment.NULL;
    private MemorySegment currentUIImage = MemorySegment.NULL;

    // Telemetry cache
    private float cachedRenderFPS = 0.0f;
    private float cachedPresentedFPS = 0.0f;
    private float cachedGpuFrameTimeMs = 0.0f;

    // Persistent off-heap zero-allocation buffers
    private final Arena persistentArena = Arena.ofAuto();
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

    public void updateFromMinecraft() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.getWindow() != null) {
                int fbW = mc.getWindow().getFramebufferWidth();
                int fbH = mc.getWindow().getFramebufferHeight();
                if (fbW > 0 && fbH > 0 && (fbW != this.nativeWidth || fbH != this.nativeHeight)) {
                    updateDimensions(fbW, fbH);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public void markConfigDirty() {
        this.configDirty = true;
        recalculateRenderResolution();
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null) {
                mc.resizeDisplay();
            }
        } catch (Throwable ignored) {
        }
    }

    public int getScaledWidth(int width) {
        if (MetalConfig.INSTANCE.scalingMode == MetalConfig.ScalingMode.OFF) {
            return width;
        }
        float scale = MetalConfig.INSTANCE.preset.getScale();
        int scaled = Math.max(320, (int) (width * scale));
        if ((scaled & 1) != 0) scaled++;
        return scaled;
    }

    public int getScaledHeight(int height) {
        if (MetalConfig.INSTANCE.scalingMode == MetalConfig.ScalingMode.OFF) {
            return height;
        }
        float scale = MetalConfig.INSTANCE.preset.getScale();
        int scaled = Math.max(240, (int) (height * scale));
        if ((scaled & 1) != 0) scaled++;
        return scaled;
    }

    public void triggerCameraCut() {
        this.cameraCut = true;
    }

    private void recalculateRenderResolution() {
        MetalConfig config = MetalConfig.INSTANCE;
        if (config.scalingMode == MetalConfig.ScalingMode.OFF) {
            this.renderWidth = this.nativeWidth;
            this.renderHeight = this.nativeHeight;
        } else {
            float scale = config.preset.getScale();
            this.renderWidth = Math.max(320, (int) (this.nativeWidth * scale));
            this.renderHeight = Math.max(240, (int) (this.nativeHeight * scale));
            // MetalFX requires even dimensions
            if ((this.renderWidth & 1) != 0) this.renderWidth++;
            if ((this.renderHeight & 1) != 0) this.renderHeight++;
        }
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
        if (MetalConfig.INSTANCE.scalingMode == MetalConfig.ScalingMode.TEMPORAL) {
            JitterHelper.advance(renderWidth, renderHeight);
        }

        if (configDirty && MetalBridge.isAvailable()) {
            syncConfigToNative();
            configDirty = false;
        }
    }

    private void syncConfigToNative() {
        if (!MetalBridge.isAvailable() || persistentConfigSegment.equals(MemorySegment.NULL)) return;
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

        MetalBridge.configure(persistentConfigSegment);
    }

    public void onFramePresent(float fov, float nearPlane, float farPlane) {
        if (!MetalBridge.isAvailable() || persistentParamsSegment.equals(MemorySegment.NULL)) return;

        long now = System.nanoTime();
        float deltaTime = (float) ((now - lastFrameNanos) / 1_000_000_000.0);
        lastFrameNanos = now;

        persistentParamsSegment.set(ValueLayout.JAVA_LONG, 0, frameIndex);
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 8, deltaTime);
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 12, JitterHelper.getJitterX());
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 16, JitterHelper.getJitterY());
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 20, nearPlane);
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 24, farPlane);
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 28, fov);
        persistentParamsSegment.set(ValueLayout.JAVA_FLOAT, 32, (float) renderWidth / (float) renderHeight);
        persistentParamsSegment.set(ValueLayout.JAVA_BOOLEAN, 36, cameraCut);
        persistentParamsSegment.set(ValueLayout.JAVA_BOOLEAN, 37, true); // Reversed-Z

        cameraCut = false;

        MetalBridge.processFrame(
                currentWorldImage,
                currentDepthImage,
                currentMotionImage,
                currentUIImage,
                persistentParamsSegment
        );

        // Readback telemetry periodically (zero allocations)
        if ((frameIndex & 15) == 0 && !persistentTelemetrySegment.equals(MemorySegment.NULL)) {
            MetalBridge.getTelemetry(persistentTelemetrySegment);
            cachedRenderFPS = persistentTelemetrySegment.get(ValueLayout.JAVA_FLOAT, 0);
            cachedPresentedFPS = persistentTelemetrySegment.get(ValueLayout.JAVA_FLOAT, 4);
            cachedGpuFrameTimeMs = persistentTelemetrySegment.get(ValueLayout.JAVA_FLOAT, 8);
        }
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
}
