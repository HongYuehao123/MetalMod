package net.metalmod.metalfx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalNative;
import net.metalmod.backend.MetalTextureView;
import java.lang.foreign.MemorySegment;

/** Owns the frame-boundary registration for 4x Spatial input coverage. Never touches the HUD,
 * native target or Temporal input. Queue completion precedes replacement of private companions. */
public final class SpatialInputAntialiasing {
    private static long registeredColor, registeredDepth;
    private static int samples;

    private SpatialInputAntialiasing() {}

    public static void configure(RenderTarget target, boolean spatial) {
        MemorySegment color = MemorySegment.NULL, depth = MemorySegment.NULL;
        if (net.metalmod.config.MetalConfig.INSTANCE.spatialAntialiasing() && spatial && target != null
                && target.getColorTextureView() instanceof MetalTextureView c
                && target.getDepthTextureView() instanceof MetalTextureView d) {
            color = c.handle(); depth = d.handle();
        }
        if (registeredColor == color.address() && registeredDepth == depth.address()) return;
        MetalDevice device = MetalDevice.active();
        if (device != null) MetalNative.queueSynchronize(device.queueHandle());
        int result = MetalNative.msaaWorld(color, depth);
        registeredColor = color.address(); registeredDepth = depth.address();
        samples = Math.max(result, 0);
        if (result < 0 && color.address() != 0) {
            System.err.println("[MetalMod] Spatial input coverage unavailable; retaining single-sample input");
        }
    }

    public static int samples() { return samples; }
    public static void close() { configure(null, false); }
}
