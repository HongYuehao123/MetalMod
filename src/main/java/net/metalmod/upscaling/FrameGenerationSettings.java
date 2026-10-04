package net.metalmod.upscaling;

import net.metalmod.config.MetalConfig;

/** Frame-generation preference, sampled at real-render boundaries independently of upscaling. */
public final class FrameGenerationSettings {
    public record Snapshot(boolean enabled, long revision) {}
    public record Status(boolean pacingActive, String reason) {}
    private record Choice(Boolean enabled, long revision) {}
    private static volatile Choice choice = new Choice(null, 0);
    private static volatile Status status = new Status(false, "waiting for a Metal surface");
    private FrameGenerationSettings() {}

    /** Gameplay uses the ordinary render-thread presenter and native interpolation producer.
     * Native interpolation/display-link foundation tests do not establish gameplay availability.
     */
    public static boolean gameplayAvailable() { return true; }
    public static String unavailableReason() { return "MetalFX interpolation unavailable"; }

    /** UI > new launch flag > legacy display-link flag > saved preference. */
    public static Snapshot resolve(Boolean ui, String flag, String legacyFlag, boolean saved, long revision) {
        boolean enabled = saved;
        if ("true".equalsIgnoreCase(legacyFlag) || "false".equalsIgnoreCase(legacyFlag))
            enabled = Boolean.parseBoolean(legacyFlag);
        if ("true".equalsIgnoreCase(flag) || "false".equalsIgnoreCase(flag))
            enabled = Boolean.parseBoolean(flag);
        if (ui != null) enabled = ui;
        return new Snapshot(enabled, revision);
    }
    public static Snapshot current() {
        Choice c = choice;
        Snapshot requested = resolve(c.enabled, System.getProperty("metalmod.frameGeneration"),
                System.getProperty("metalmod.displayLink"), MetalConfig.INSTANCE.enableFrameGeneration, c.revision);
        // The legacy display-link flag requests interpolation; it never transfers drawable ownership.
        return new Snapshot(gameplayAvailable() && requested.enabled(), requested.revision());
    }
    public static synchronized void chooseEnabled(boolean enabled) {
        enabled = gameplayAvailable() && enabled;
        choice = new Choice(enabled, choice.revision + 1);
        MetalConfig.INSTANCE.enableFrameGeneration = enabled;
        MetalConfig.INSTANCE.save();
    }
    public static Status status() { return status; }
    /** Only the surface/render thread publishes effective operation; a GUI click changes preference only. */
    public static void publish(boolean pacingActive, String reason) { status = new Status(pacingActive, reason); }
}
