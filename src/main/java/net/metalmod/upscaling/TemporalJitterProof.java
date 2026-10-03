package net.metalmod.upscaling;

import org.joml.Matrix4f;
import net.metalmod.config.MetalConfig;

/** Scene-only temporal sampling; hand and GUI remain unjittered. */
public final class TemporalJitterProof {
    public record Stats(boolean applied, long generation, long frames, int width, int height,
                        float jitterX, float jitterY, long worldUploads, long handUploads) {}
    private static volatile Boolean sessionChoice;

    /** UI choice beats a launch flag, then the saved temporal preference. */
    public static boolean requested() {
        Boolean choice = sessionChoice;
        if (choice != null) return choice;
        String flag = System.getProperty("metalmod.temporalUpscaling",System.getProperty("metalmod.temporalJitterProof"));
        if ("true".equalsIgnoreCase(flag) || "false".equalsIgnoreCase(flag))
            return Boolean.parseBoolean(flag);
        return MetalConfig.INSTANCE.enableTemporalUpscaling;
    }

    /** Apply next rendered frame and remember the preference; the coordinator retires the old algorithm generation. */
    public static synchronized void chooseEnabled(boolean enabled) {
        sessionChoice = enabled;
        MetalConfig.INSTANCE.enableTemporalUpscaling = enabled;
        MetalConfig.INSTANCE.save();
    }

    private static volatile Stats stats = new Stats(false, 0, 0, 0, 0, 0, 0, 0, 0);
    public static Stats stats() { return stats; }
    private boolean active;
    private long generation = -1, frames, worldUploads, handUploads;
    private int width, height;
    private TemporalSampling.Offset jitter;

    /** Called only after an actual reduced scene generation has been selected. */
    public void begin(boolean enabled, long nextGeneration, int nextWidth, int nextHeight) {
        active = enabled && nextWidth > 0 && nextHeight > 0;
        if (!active) {
            generation = -1; frames = worldUploads = handUploads = 0;
            if (stats.applied()) stats = new Stats(false, 0, 0, 0, 0, 0, 0, 0, 0);
            return;
        }
        if (generation != nextGeneration || width != nextWidth || height != nextHeight)
            frames = worldUploads = handUploads = 0;
        generation = nextGeneration; width = nextWidth; height = nextHeight;
        jitter = TemporalSampling.jitter(frames++);
        publish();
    }
    public boolean active() { return active; }
    /** Returns the original object on the ordinary path; never mutates extracted camera matrices. */
    public Matrix4f projection(Matrix4f original, boolean hand) {
        if (!active || hand) return original;
        worldUploads++;
        Matrix4f copy = TemporalSampling.jitterProjection(original, jitter, width, height);
        publish();
        return copy;
    }
    /** Close the scene-only interval before GUI composition; keep last-frame diagnostic counters. */
    public void end() { active = false; }
    public void reset() { begin(false, 0, 0, 0); }
    private void publish() {
        stats = new Stats(true, generation, frames, width, height, jitter.x(), jitter.y(), worldUploads, handUploads);
    }
}
