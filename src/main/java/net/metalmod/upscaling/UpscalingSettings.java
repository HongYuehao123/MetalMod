package net.metalmod.upscaling;

import net.metalmod.config.MetalConfig;

/** Immutable settings sampled once per real rendered frame. UI choices beat launch flags and file. */
public final class UpscalingSettings {
    public record Snapshot(boolean enabled, int strength, long revision) {
        public Snapshot {
            if (!validStrength(strength)) throw new IllegalArgumentException("Invalid strength: " + strength);
        }
        public int scale() { return enabled ? 100 - strength : 100; }
    }
    public record Dimensions(int width, int height) {
        public double pixelPercent(int outputWidth, int outputHeight) {
            return outputWidth <= 0 || outputHeight <= 0 ? 0
                    : 100.0 * width * height / ((double) outputWidth * outputHeight);
        }
    }
    private record Choice(Boolean enabled, Integer strength, long revision) {}
    private static volatile Choice choice = new Choice(null, null, 0);
    private UpscalingSettings() {}
    public static boolean validStrength(int value) { return value == 0 || value == 25 || value == 33 || value == 50; }
    public static int parseStrength(String value) {
        try { int strength = Integer.parseInt(value); return validStrength(strength) ? strength : 25; }
        catch (RuntimeException e) { return 25; }
    }
    public static int nextStrength(int value) {
        return switch (value) { case 0 -> 25; case 25 -> 33; case 33 -> 50; default -> 0; };
    }
    public static Snapshot resolve(Boolean enabledChoice, Integer strengthChoice, String enabledFlag,
                                   String strengthFlag, boolean savedEnabled, int savedStrength, long revision) {
        boolean on = enabledChoice != null ? enabledChoice
                : enabledFlag != null && (enabledFlag.equalsIgnoreCase("true") || enabledFlag.equalsIgnoreCase("false"))
                    ? Boolean.parseBoolean(enabledFlag) : savedEnabled;
        int strength = strengthChoice != null ? strengthChoice
                : strengthFlag != null ? parseStrength(strengthFlag)
                : validStrength(savedStrength) ? savedStrength : 25;
        return new Snapshot(on, strength, revision);
    }
    public static Snapshot current() {
        Choice c = choice;
        return resolve(c.enabled, c.strength, System.getProperty("metalmod.superResolution"),
                System.getProperty("metalmod.superResolutionStrength"),
                MetalConfig.INSTANCE.enableSuperResolution, MetalConfig.INSTANCE.superResolutionStrength, c.revision);
    }
    public static Dimensions dimensions(int width, int height, Snapshot settings) {
        if (width <= 0 || height <= 0) return new Dimensions(0, 0);
        int scale = settings.scale();
        return new Dimensions(Math.max(1, (int) ((long) width * scale / 100)),
                Math.max(1, (int) ((long) height * scale / 100)));
    }
    public static synchronized void chooseEnabled(boolean enabled) {
        Choice c = choice;
        choice = new Choice(enabled, c.strength, c.revision + 1);
        MetalConfig.INSTANCE.enableSuperResolution = enabled;
        MetalConfig.INSTANCE.save();
    }
    public static synchronized void chooseStrength(int strength) {
        if (!validStrength(strength)) throw new IllegalArgumentException("Invalid strength");
        Choice c = choice;
        choice = new Choice(c.enabled, strength, c.revision + 1);
        MetalConfig.INSTANCE.superResolutionStrength = strength;
        MetalConfig.INSTANCE.save();
    }
}
