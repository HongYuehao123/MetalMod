package net.metalmod.debug;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalNative;
import net.metalmod.metalfx.RenderScaleSettings;
import net.metalmod.metalfx.WorldRenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Measures what the GPU actually costs, on demand, so render scaling can be judged without a
 * stopwatch and without trusting a frame rate.
 *
 * <h2>Why a frame rate cannot answer the question</h2>
 *
 * <p>Render scaling only pays when the frame is limited by the world's fragment work. On a display
 * that paces the frame - which is every display, at its refresh rate - the frame rate stops moving
 * long before the GPU is saturated, so "the frame rate did not improve" is equally consistent with
 * "the GPU is idle" and "the GPU is maxed out". Those two want opposite decisions, and the difference
 * is invisible from outside.
 *
 * <p>So this times the work itself, with the GPU synchronised: a full-target fill at the render
 * resolution and at native resolution, and the MetalFX upscale. The fill is the cheapest write there
 * is, so it is a floor rather than a prediction - and that is what makes it decisive. If even the
 * floor at native resolution is a large slice of the frame budget, the world is not what is costing
 * the frame, and no amount of render scaling will help.
 *
 * <p>Triggered by F9, and reported both as a toast and in the log, because the number is only useful
 * next to the scene it was measured in.
 */
public final class GpuCostProbe {

    /** Enough repetitions for the GPU's own clocks to dominate the measurement. */
    private static final int PASSES = 40;

    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();

    private GpuCostProbe() {
    }

    /** Run the measurement and report it. Called from the F9 key hook, on the render thread. */
    public static void run(Minecraft minecraft) {
        MetalDevice device = MetalDevice.active();
        if (device == null) {
            report(minecraft, "MetalMod GPU cost", "no Metal device is active");
            return;
        }
        RenderTarget world = WorldRenderTarget.worldTarget();
        RenderTarget main = minecraft != null && minecraft.gameRenderer != null
                ? minecraft.gameRenderer.mainRenderTarget() : null;
        if (main == null) {
            report(minecraft, "MetalMod GPU cost", "no main render target yet");
            return;
        }

        // The floor at each resolution, and the scaler.
        double nativeFill = MetalNative.gpuTimeFill(device.queueHandle(),
                textureHandle(main), PASSES);
        double scaledFill = world == null ? Double.NaN
                : MetalNative.gpuTimeFill(device.queueHandle(), textureHandle(world), PASSES);
        double upscale = Double.NaN;
        if (world != null) {
            upscale = WorldRenderTarget.gpuTimeUpscale(device, world, main, PASSES);
        }

        StringBuilder log = new StringBuilder(String.format(java.util.Locale.ROOT,
                "[MetalMod] GPU cost probe | window %dx%d | scale %s",
                main.width, main.height, RenderScaleSettings.percentLabel()));
        log.append(String.format(java.util.Locale.ROOT,
                " | fill native %dx%d %.3f ms", main.width, main.height, nativeFill));
        if (world != null) {
            log.append(String.format(java.util.Locale.ROOT,
                    " | fill scaled %dx%d %.3f ms | MetalFX upscale %.3f ms",
                    world.width, world.height, scaledFill, upscale));
        } else {
            log.append(" | no scaled target (scaling off, or unavailable)");
        }
        // The frame budget at the display's rate is the context that makes the numbers mean something.
        log.append(String.format(java.util.Locale.ROOT,
                " | frame budget 16.7 ms at 60 Hz, 6.9 ms at 144 Hz"));
        System.out.println(log);

        report(minecraft, "GPU cost", summary(nativeFill, scaledFill, upscale, world, main));
    }

    private static String summary(double nativeFill, double scaledFill, double upscale,
                                  RenderTarget world, RenderTarget main) {
        if (world == null) {
            return String.format(java.util.Locale.ROOT,
                    "native fill %.2f ms - scaling is off, so there is nothing to compare",
                    nativeFill);
        }
        // The trade, computed rather than asserted. An absolute threshold here was a mistake: the
        // first version called scaling worthless whenever a native fill came in under 2 ms, which
        // fired on a machine whose own frame times showed scaling saving ten. A clear is a floor, not
        // a frame, so only the comparison between the two configurations is meaningful.
        if (upscale < 0.0) {
            return String.format(java.util.Locale.ROOT,
                    "native fill %.2f ms, scaled fill %.2f ms - the upscale could not be timed",
                    nativeFill, scaledFill);
        }
        double saved = nativeFill - (scaledFill + upscale);
        String verdict = saved > 0.5
                ? String.format(java.util.Locale.ROOT,
                        "fills + upscale save %.2f ms per frame", saved)
                : String.format(java.util.Locale.ROOT,
                        "fills + upscale cost %.2f ms more per frame", -saved);
        return String.format(java.util.Locale.ROOT,
                "native fill %.2f ms, scaled fill %.2f, upscale %.2f - %s (a fill is a floor, not a"
                        + " frame: read it with F3's frame time)", nativeFill, scaledFill, upscale,
                verdict);
    }

    /**
     * Sample the presented frame and report the colour of a few fixed points.
     *
     * <p>Added because "the sky looks grey with MetalFX on" is a claim about one flat, saturated region
     * of the picture, and the only way to settle it is to read the pixels the compositor is about to
     * show. The points are chosen to be unambiguous: high in the frame is sky, the lower middle is
     * ground, and the corners catch a pass that wrote only part of the target.
     *
     * <p>Runs after the frame's own encoding, so what it reads is the finished frame - upscale included
     * when scaling is on.
     */
    public static void sampleFrame(Minecraft minecraft) {
        MetalDevice device = MetalDevice.active();
        RenderTarget main = minecraft != null && minecraft.gameRenderer != null
                ? minecraft.gameRenderer.mainRenderTarget() : null;
        if (device == null || main == null) {
            return;
        }
        int w = main.width;
        int h = main.height;
        var readback = device.createBuffer(() -> "frame sample",
                com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ
                        | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST, (long) w * h * 4);
        try {
            var encoder = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToBuffer(main.getColorTexture(), readback, 0L, null, 0, 0, 0, w, h);
            encoder.submit();
            java.nio.ByteBuffer px = ((net.metalmod.backend.MetalBuffer) readback).data()
                    .asByteBuffer().order(java.nio.ByteOrder.nativeOrder());
            // Three sky points and one ground point, named, so two samples can be compared row by
            // row without the reader having to work out which coordinate was which.
            String[] names = {"sky-top", "sky-left", "sky-right", "ground"};
            int[][] points = {{w / 2, h / 20}, {w / 6, h / 10}, {5 * w / 6, h / 10}, {w / 2, 9 * h / 10}};
            StringBuilder text = new StringBuilder(String.format(java.util.Locale.ROOT,
                    "[MetalMod] frame sample %dx%d | %s", w, h,
                    WorldRenderTarget.describeForLog()));
            for (int i = 0; i < points.length; i++) {
                int at = (points[i][1] * w + points[i][0]) * 4;
                text.append(String.format(java.util.Locale.ROOT, " | %s %d %d %d", names[i],
                        px.get(at) & 0xFF, px.get(at + 1) & 0xFF, px.get(at + 2) & 0xFF));
            }
            System.out.println(text);

            // BUG-029: the same strip from the world target, before the upscale. Two lines that agree
            // mean the effect introduced the difference; two that already disagree mean the scaled pass
            // rendered the sky differently, and the cause is upstream of the effect.
            double[] both = WorldRenderTarget.compareSkyStrip(w, h, main);
            if (both != null) {
                System.out.println(String.format(java.util.Locale.ROOT,
                        "[MetalMod] sky strip mean | world (before upscale) %.1f %.1f %.1f"
                                + " | main (after) %.1f %.1f %.1f | difference %.1f %.1f %.1f",
                        both[0], both[1], both[2], both[3], both[4], both[5],
                        both[3] - both[0], both[4] - both[1], both[5] - both[2]));
            }
        } catch (Throwable t) {
            System.out.println("[MetalMod] frame sample failed: " + t);
        } finally {
            readback.close();
        }
    }

    private static java.lang.foreign.MemorySegment textureHandle(RenderTarget target) {
        return target.getColorTexture() instanceof net.metalmod.backend.MetalTexture metal
                ? metal.handle() : java.lang.foreign.MemorySegment.NULL;
    }

    private static void report(Minecraft minecraft, String title, String detail) {
        if (minecraft == null || minecraft.gui == null || minecraft.gui.toastManager() == null) {
            return;
        }
        minecraft.gui.toastManager().addToast(new SystemToast(TOAST_ID,
                Component.literal(title), Component.literal(detail)));
    }
}
