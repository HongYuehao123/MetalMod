package net.metalmod.metalfx;

import net.metalmod.config.MetalConfig;

/**
 * The render-resolution and upscaler settings, resolved the same way the lighting switches are.
 *
 * <p>Three things can decide a value, in this order: an <b>in-game choice</b> made on the Upscaling
 * screen, a <b>{@code -D} launch flag</b>, then the <b>saved config file</b>. The order matters for
 * the same reason it does in {@code LightingSettings}: a launch flag has to be able to seed a
 * session for the offline tools without freezing the setting against the user.
 *
 * <p>Reads are resolved live. The renderer samples the scale when it builds or rebuilds the world
 * target, so a change takes effect at the next frame boundary rather than needing a restart.
 */
public final class RenderScaleSettings {

    public static final String PROPERTY_RENDER_SCALE = "metalmod.renderScale";
    public static final String PROPERTY_UPSCALER = "metalmod.upscaler";

    /**
     * The scale factors the screen offers, as fractions of the native resolution.
     *
     * <p>Deliberately a short list of round numbers rather than a free slider: each step is a real
     * trade of pixels for frame time, and a value the user can name ("two thirds") is easier to
     * compare between sessions than an arbitrary percentage.
     */
    public static final double[] PRESETS = {1.0, 0.85, 0.75, 0.67, 0.5};

    /** Below this the world is too coarse to be worth rendering at all; above 1 is supersampling. */
    public static final double MIN_SCALE = 0.25;
    public static final double MAX_SCALE = 1.0;

    /**
     * Session choices made on the Upscaling screen. Null means "not chosen yet", which is what lets a
     * launch flag or the saved file decide until the user disagrees.
     */
    private static volatile Double sessionScale;
    private static volatile String sessionUpscaler;

    private RenderScaleSettings() {
    }

    /** Whether the user launched with a flag for this setting. Informational; it does not lock it. */
    public static boolean overridden(String property) {
        return System.getProperty(property) != null;
    }

    /**
     * The fraction of the native resolution the world is rendered at.
     *
     * <p>1.0 is the feature-off path: the world renders straight into the native target and nothing
     * is scaled, so leaving this alone keeps exactly the pre-Phase-7 frame.
     */
    public static double renderScale() {
        Double resolved = sessionScale;
        if (resolved == null) {
            String flag = System.getProperty(PROPERTY_RENDER_SCALE);
            if (flag != null) {
                try {
                    resolved = Double.parseDouble(flag);
                } catch (NumberFormatException e) {
                    System.err.println("[MetalMod] ignoring -D" + PROPERTY_RENDER_SCALE + "=" + flag
                            + ": not a number");
                }
            }
        }
        if (resolved == null) {
            resolved = MetalConfig.INSTANCE.renderScale;
        }
        return clamp(resolved);
    }

    /**
     * Whether render-resolution scaling is active at all.
     *
     * <p>Asked as a predicate rather than left to callers comparing doubles. It tests the scale, not a
     * rounded dimension: a dimension of one pixel rounds to one at every scale, so a predicate built
     * from {@link #scaledSize} would answer "active" at 1.0 and send the frame down the path that
     * allocates a second, identical target.
     */
    public static boolean active() {
        return renderScale() != 1.0;
    }

    /**
     * The scale rounded to the nearest pixel at this dimension.
     *
     * <p>Clamped to at least one pixel and at most the native dimension: a zero-sized attachment is
     * rejected by Metal, and the scale is already clamped to 1.0 so the upper bound only matters for
     * a dimension of zero, which a minimised window produces.
     */
    public static int scaledSize(int dimension) {
        double scale = renderScale();
        int scaled = (int) Math.round(dimension * scale);
        return Math.max(1, Math.min(dimension, scaled));
    }

    /**
     * Whether a temporal effect is actually running, which is what gates projection jitter.
     *
     * <p>False for every mode the backend can currently run. A temporal scaler reconstructs from
     * <em>motion</em> - where each pixel was last frame - and the level does not publish that yet, so
     * temporal upscaling falls back to spatial rather than being fed vectors it does not have. The
     * native scaler and its history lifecycle exist and are exercised; the producer is the missing
     * half, and the roadmap's integration contract names it.
     *
     * <p>Deliberately one predicate rather than a second setting: jitter and the temporal scaler must
     * agree about whether the frame is temporal, and two flags that can disagree is how a jittered
     * projection ends up with no accumulation to justify it.
     */
    public static boolean temporalEnabled() {
        return false;
    }

    /**
     * The requested upscaler: {@code "spatial"}, {@code "temporal"} or {@code "off"}.
     *
     * <p>This is a request, not a promise - {@link MetalFx#available()} and the capability queries
     * decide what the machine will actually run, and the backend reports the difference. Temporal is
     * currently resolved to spatial for the reason {@link #temporalEnabled()} gives.
     */
    public static String upscaler() {
        String resolved = sessionUpscaler;
        if (resolved == null) {
            resolved = System.getProperty(PROPERTY_UPSCALER);
        }
        if (resolved == null) {
            resolved = MetalConfig.INSTANCE.upscaler;
        }
        if (resolved == null) return MetalFx.SPATIAL;
        resolved = resolved.trim().toLowerCase(java.util.Locale.ROOT);
        if (resolved.equals(MetalFx.TEMPORAL)) {
            return MetalFx.SPATIAL;
        }
        if (resolved.equals(MetalFx.SPATIAL) || resolved.equals(MetalFx.OFF)) {
            return resolved;
        }
        System.err.println("[MetalMod] unknown upscaler '" + resolved + "'; using "
                + MetalFx.SPATIAL);
        return MetalFx.SPATIAL;
    }

    // --- in-game choices -------------------------------------------------------------------------

    /** Record and persist a render scale chosen in game. */
    public static void chooseRenderScale(double scale) {
        double clamped = clamp(scale);
        sessionScale = clamped;
        MetalConfig.INSTANCE.renderScale = clamped;
        MetalConfig.INSTANCE.save();
    }

    /** Record and persist an upscaler chosen in game. */
    public static void chooseUpscaler(String upscaler) {
        String normalized = upscaler == null ? MetalFx.SPATIAL
                : upscaler.trim().toLowerCase(java.util.Locale.ROOT);
        sessionUpscaler = normalized;
        MetalConfig.INSTANCE.upscaler = normalized;
        MetalConfig.INSTANCE.save();
    }

    /**
     * Whether an in-world notice announces an upscaling change.
     *
     * <p>On by default for the same reason the F8 capture closes the menu: a setting whose effect is
     * invisible is a setting nobody can check. Turning it off is for a player who has finished
     * checking and does not want a toast on every change.
     */
    public static boolean showNotice() {
        return MetalConfig.INSTANCE.upscalingNotice;
    }

    public static void chooseNotice(boolean on) {
        MetalConfig.INSTANCE.upscalingNotice = on;
        MetalConfig.INSTANCE.save();
    }

    /**
     * The size the world is being rendered at and the size it is presented at, or null when scaling
     * is off.
     *
     * <p>The live target's own answer rather than a recomputation from the setting, so the number the
     * settings screen and the notice show is the one the frame is actually using - which is the whole
     * point of showing it.
     */
    public static int[] effectiveSize() {
        com.mojang.blaze3d.pipeline.RenderTarget world =
                net.metalmod.metalfx.WorldRenderTarget.worldTarget();
        if (world == null) {
            return null;
        }
        return new int[]{world.width, world.height};
    }

    /** "75%" or "100% (off)", for a button caption. */
    public static String percentLabel() {
        double scale = renderScale();
        return scale >= 1.0 ? "100% (off)" : Math.round(scale * 100) + "%";
    }

    /** Forget in-game choices, so the launch flags and the saved file decide again. For tests. */
    public static void clearSessionChoices() {
        sessionScale = null;
        sessionUpscaler = null;
    }

    private static double clamp(double scale) {
        if (Double.isNaN(scale)) return 1.0;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    /** One-line summary for the log and the F3 section. */
    public static String summary() {
        return "renderScale=" + String.format(java.util.Locale.ROOT, "%.2f", renderScale())
                + " upscaler=" + upscaler();
    }
}
