package net.metalmod.metalfx;

/** Immutable configuration sampled at a frame boundary. Output size is independent of world scale. */
public record UpscalingPlan(int outputWidth, int outputHeight, int worldWidth, int worldHeight,
                            double scale, String requestedEffect) {
    public static UpscalingPlan resolve(int width, int height, double scale, String effect) {
        double effectiveScale = MetalFx.OFF.equals(effect) ? 1.0 : scale;
        return new UpscalingPlan(width, height,
                Math.max(1, (int) Math.round(width * effectiveScale)),
                Math.max(1, (int) Math.round(height * effectiveScale)), effectiveScale, effect);
    }

    public boolean scaled() {
        return worldWidth != outputWidth || worldHeight != outputHeight;
    }

    public boolean temporalRequested() {
        return scaled() && MetalFx.TEMPORAL.equals(requestedEffect);
    }
}
