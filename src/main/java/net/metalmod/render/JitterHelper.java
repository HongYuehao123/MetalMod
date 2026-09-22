package net.metalmod.render;

public final class JitterHelper {

    private static final int PHASE_COUNT = 16;
    private static int currentPhase = 0;

    private static float currentJitterX = 0.0f;
    private static float currentJitterY = 0.0f;

    // Halton sequence generator for prime base
    private static float halton(int index, int base) {
        float f = 1.0f;
        float result = 0.0f;
        while (index > 0) {
            f /= base;
            result += f * (index % base);
            index /= base;
        }
        return result;
    }

    /**
     * Advance to next subpixel phase and compute jitter offsets.
     *
     * Must be called once per frame from the render thread only: the phase is global state that is
     * read when building the projection matrix.
     *
     * @param renderWidth Low-resolution input width (reserved for future per-resolution scaling)
     * @param renderHeight Low-resolution input height (reserved for future per-resolution scaling)
     */
    public static void advance(int renderWidth, int renderHeight) {
        currentPhase = (currentPhase + 1) % PHASE_COUNT;
        int sampleIndex = currentPhase + 1; // 1-indexed

        // Normalized offset in [-0.5, 0.5]
        float hx = halton(sampleIndex, 2) - 0.5f;
        float hy = halton(sampleIndex, 3) - 0.5f;

        // Pixel-space jitter
        currentJitterX = hx;
        currentJitterY = hy;
    }

    /**
     * Return to zero jitter. Called when temporal upscaling is not active so a stale phase cannot
     * leak into the projection matrix when the mode is switched on later.
     */
    public static void reset() {
        currentPhase = 0;
        currentJitterX = 0.0f;
        currentJitterY = 0.0f;
    }

    public static float getJitterX() {
        return currentJitterX;
    }

    public static float getJitterY() {
        return currentJitterY;
    }

    /**
     * Get camera projection matrix jitter offset for NDC (Normalized Device Coordinates).
     */
    public static float getProjectionJitterX(int renderWidth) {
        return (2.0f * currentJitterX) / (float) renderWidth;
    }

    public static float getProjectionJitterY(int renderHeight) {
        return (2.0f * currentJitterY) / (float) renderHeight;
    }
}
