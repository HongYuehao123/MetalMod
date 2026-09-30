package net.metalmod.metalfx;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import java.lang.foreign.MemorySegment;
import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalFormat;
import net.metalmod.backend.MetalNative;
import net.metalmod.backend.MetalTexture;
import net.metalmod.config.MetalConfig;

/** Full-resolution edge AA of the completed world, before Minecraft draws its interface. */
public final class WorldAntialiasing {
    private static MemorySegment scratch = MemorySegment.NULL;
    private static int width;
    private static int height;
    private static MetalDevice owner;
    private static long frames;
    private static String lastError = "";

    private WorldAntialiasing() {
    }

    public static boolean enabled() {
        return MetalConfig.INSTANCE.postAntialiasing();
    }

    public static long frameCount() {
        return frames;
    }

    public static String lastError() {
        return lastError;
    }

    public static synchronized boolean apply(RenderTarget main) {
        if (!enabled()) {
            release();
            return false;
        }
        if (main == null) return false;
        MetalDevice device = MetalDevice.active();
        if (device == null || !(main.getColorTexture() instanceof MetalTexture color)
                || !color.isValid()) return false;
        if (main.width <= 0 || main.height <= 0
                || main.getColorTexture().getFormat() != GpuFormat.RGBA8_UNORM) return false;
        if (!ensureScratch(device, main.width, main.height)) return false;
        int result = MetalNative.aaRun(device.deviceHandle(), device.queueHandle(), color.handle(), scratch);
        if (result != 0) {
            lastError = "AA pass returned " + result;
            return false;
        }
        lastError = "";
        frames++;
        return true;
    }

    private static boolean ensureScratch(MetalDevice device, int wantedWidth, int wantedHeight) {
        if (owner == device && scratch.address() != 0
                && width == wantedWidth && height == wantedHeight) return true;
        release();
        scratch = MetalNative.textureCreateFull(device.deviceHandle(),
                MetalFormat.mtlPixelFormat(GpuFormat.RGBA8_UNORM), wantedWidth, wantedHeight,
                1, 1, 2, true,
                MetalFormat.TEXTURE_USAGE_SHADER_READ | MetalFormat.TEXTURE_USAGE_RENDER_TARGET);
        if (scratch == null || scratch.address() == 0) {
            scratch = MemorySegment.NULL;
            lastError = "could not allocate the AA target";
            return false;
        }
        owner = device;
        width = wantedWidth;
        height = wantedHeight;
        System.out.println("[MetalMod] world FXAA at " + width + "x" + height + " before HUD");
        return true;
    }

    public static synchronized void release() {
        if (scratch.address() != 0) {
            if (owner != null && owner == MetalDevice.active()) {
                MetalNative.queueSynchronize(owner.queueHandle());
            }
            MetalNative.textureRelease(scratch);
        }
        scratch = MemorySegment.NULL;
        owner = null;
        width = height = 0;
    }
}
