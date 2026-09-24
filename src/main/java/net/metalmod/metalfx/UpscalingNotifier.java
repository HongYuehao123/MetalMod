package net.metalmod.metalfx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * The in-world confirmation that an upscaling change took effect.
 *
 * <p>This exists because "did it actually do anything?" is the question a render-scale change raises,
 * and until now the only answer was the F3 overlay - which a player has to know to open, and which
 * does not say *when* the change landed. A toast names the resolution the next frame will render at,
 * so the effect of the setting is visible without leaving the world.
 *
 * <p>It is deliberately a toast rather than a HUD line of our own. Minecraft 26.2 builds the HUD from
 * a render state that screens submit into; reaching around that to draw would put a foreign pass in
 * the middle of the interface. The toast manager is the engine's own supported channel, it is
 * already positioned and timed, and it costs nothing to keep.
 *
 * <p>Shown only when the setting changes or the screen is closed, never per frame: a toast per frame
 * would be a slideshow.
 */
public final class UpscalingNotifier {

    /**
     * One id for every message, so a burst of changes replaces the last toast instead of stacking.
     * The default five-second display time is what this wants - long enough to read, short enough not
     * to cover the hotbar for a whole session.
     */
    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();

    private UpscalingNotifier() {
    }

    /** Announce the current configuration, if the user has the notice turned on. */
    public static void announce(String headline) {
        if (!RenderScaleSettings.showNotice()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gui == null || minecraft.gui.toastManager() == null) {
            return;
        }
        String detail = detail();
        minecraft.gui.toastManager().addToast(new SystemToast(TOAST_ID,
                Component.literal("MetalMod: " + headline),
                Component.literal(detail)));
    }

    /**
     * What the change will do, in one line.
     *
     * <p>Reports what the next frame will do rather than what the last one did: a change announced at
     * the settings screen has not reached a frame yet, and a notice that described the previous state
     * would be worse than no notice at all.
     */
    private static String detail() {
        if (!RenderScaleSettings.active()) {
            return "native resolution - the world is not being scaled";
        }
        int[] size = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getWindow() != null) {
            size = net.metalmod.metalfx.WorldRenderTarget.pendingSize(
                    minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
        }
        String target = size == null ? "world at " + RenderScaleSettings.percentLabel()
                : "world " + size[0] + "x" + size[1] + " -> native";
        return target + ", " + RenderScaleSettings.upscaler() + " ("
                + (MetalFx.available() ? "MetalFX" : "blit fallback") + ") - from the next frame";
    }

    /**
     * Announce the outcome of the settings screen.
     *
     * <p>Called when the screen closes, which is the one moment the user is definitely looking at the
     * world again and has just made a decision.
     */
    public static void announceCurrent() {
        announce(RenderScaleSettings.active()
                ? "Render scale " + RenderScaleSettings.percentLabel()
                : "Render scaling off");
    }
}
