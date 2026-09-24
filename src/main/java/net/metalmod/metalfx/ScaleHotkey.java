package net.metalmod.metalfx;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The render-scale A/B hotkey: one press compares scaled against native in the same scene.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The only honest way to answer "is the upscaler costing me frames" is to switch it off and on
 * without moving, because the frame time depends on where you are standing far more than on anything
 * this feature does. Two sessions, or two positions, cannot be compared; the numbers differ by more
 * than the effect being measured.
 *
 * <p>So F10 flips the render scale between native and the last scaled setting, in place, with the F3
 * frame time on screen. The comparison is then two readings a second apart in one spot:
 *
 * <ul>
 *   <li>native frame time <em>lower</em> than scaled &rarr; the scaler costs more than the pixels it
 *       saves, and scaling is a pessimisation here;</li>
 *   <li>about equal &rarr; the frame is not limited by the world's pixels at all, and scaling is
 *       trading sharpness for nothing;</li>
 *   <li>scaled clearly lower &rarr; the feature is doing its job.</li>
 * </ul>
 *
 * <p>The first setting is remembered, so the toggle is a comparison rather than a reset: F10 twice
 * returns to exactly the configuration that was in use.
 */
public final class ScaleHotkey {

    /** The scaled setting F10 returns to, or 0 when the current state is native. */
    private static volatile double rememberedScale;

    private ScaleHotkey() {
    }

    /**
     * Flip between native and the remembered scaled setting, and say which way it went.
     *
     * <p>Reports through the chat line rather than a toast, because the reading the user is about to
     * take is on F3 and the two should not compete for the same corner of the screen.
     */
    public static void toggle(Minecraft minecraft) {
        double current = RenderScaleSettings.renderScale();
        double next;
        if (current >= 1.0) {
            // Turning scaling back on: use whatever was last chosen, so the comparison is like for
            // like rather than always landing on one preset.
            next = rememberedScale > 0.0 && rememberedScale < 1.0
                    ? rememberedScale
                    : RenderScaleSettings.PRESETS[RenderScaleSettings.PRESETS.length - 1];
        } else {
            rememberedScale = current;
            next = 1.0;
        }
        RenderScaleSettings.chooseRenderScale(next);

        String message = String.format(java.util.Locale.ROOT,
                "MetalMod: render scale %s - compare the F3 frame time%s",
                RenderScaleSettings.percentLabel(),
                next >= 1.0 ? " (native, no upscale)" : " (MetalFX spatial)");
        if (minecraft != null && minecraft.gui != null && minecraft.gui.hud != null) {
            // The action bar, not chat: the reading the user is about to take is on F3, and the two
            // should not compete for the same corner of the screen. The boolean is the fade animation,
            // which this wants off - a comparison reading should not be moving.
            minecraft.gui.hud.setOverlayMessage(Component.literal(message), false);
        }
        System.out.println("[MetalMod] " + message);
        // Sample the next frame's pixels, so the comparison includes what the picture itself looks
        // like and not only how fast it was produced. Deferred by one frame because the target is
        // rebuilt at the boundary.
        if (minecraft != null) {
            SAMPLES_REMAINING.set(2);
        }
    }

    /** Frames left to sample after a toggle, or 0. Read by the frame hook. */
    private static final java.util.concurrent.atomic.AtomicInteger SAMPLES_REMAINING =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Take a pending pixel sample, if one was asked for. Called once per frame after rendering. */
    public static void sampleIfRequested(net.minecraft.client.Minecraft minecraft) {
        if (SAMPLES_REMAINING.get() <= 0) {
            return;
        }
        if (SAMPLES_REMAINING.decrementAndGet() == 0) {
            net.metalmod.debug.GpuCostProbe.sampleFrame(minecraft);
        }
    }

    /** The scale F10 will return to. For diagnostics. */
    public static double rememberedScale() {
        return rememberedScale;
    }
}
