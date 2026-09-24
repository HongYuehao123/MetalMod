package net.metalmod.metalfx;

/**
 * The projection jitter a temporal upscaler needs, and the lifecycle that keeps its history valid.
 *
 * <p><b>Why jitter at all.</b> A temporal scaler accumulates several frames into one output. If every
 * frame samples the same sub-pixel positions, the extra frames add nothing - the effect can only
 * converge on detail it has actually seen. Offsetting the projection by a fraction of a pixel each
 * frame, cycling through a low-discrepancy sequence, is what lets the accumulation resolve detail
 * beyond the render resolution. The offsets average to zero, so the image does not drift.
 *
 * <p><b>Where the offset is applied.</b> The level's projection matrix, and only the level's:
 * {@code CameraRenderState.projectionMatrix} is what the world passes read, while the interface has
 * its own projections. {@code CameraRenderStateJitterMixin} post-multiplies the translation in, and
 * is scoped by {@link #beginFrame} / {@link #endFrame} so nothing else that reads the same state sees
 * a jittered matrix.
 *
 * <p><b>Units.</b> The sequence is generated in <em>input-texture pixels</em>, because that is the
 * unit MetalFX takes its {@code jitterOffset} in, and converted to the NDC translation at the point of
 * use. One place converts, so a rounding difference cannot make the projection and the scaler disagree
 * about what the frame was rendered with.
 *
 * <p><b>History.</b> Accumulated frames are only valid while the image is continuous. A camera cut, a
 * world change, a dimension change or a resolution change all invalidate it, and the scaler has to be
 * told rather than left to blend the previous scene into the new one. {@link #requestReset} is what
 * records that; the effect sends it on the next encode.
 */
public final class ProjectionJitter {

    /**
     * A Halton (2,3) sequence, which is the standard choice for this: the two axes are uncorrelated,
     * it is cheap, and it distributes evenly over the few frames a temporal filter actually keeps.
     *
     * <p>Sixteen phases is more than any scaler accumulates, so the cycle is not what limits
     * convergence. The values are centred on zero so the jitter does not bias the image.
     */
    private static final int PHASES = 16;
    private static final float[] PHASE_X = new float[PHASES];
    private static final float[] PHASE_Y = new float[PHASES];

    static {
        // Halton index starts at 1. The raw sequence is in [0,1) but its *prefix* mean is not exactly
        // 0.5 - over sixteen samples the base-2 axis sums to 0.47 rather than 0.5 - and an offset that
        // does not average to zero is a slowly drifting image. So the prefix mean is subtracted rather
        // than assumed, which is exact by construction and costs one constant per axis.
        float[] rawX = new float[PHASES];
        float[] rawY = new float[PHASES];
        float meanX = 0.0f;
        float meanY = 0.0f;
        for (int i = 0; i < PHASES; i++) {
            rawX[i] = halton(i + 1, 2);
            rawY[i] = halton(i + 1, 3);
            meanX += rawX[i];
            meanY += rawY[i];
        }
        meanX /= PHASES;
        meanY /= PHASES;
        for (int i = 0; i < PHASES; i++) {
            PHASE_X[i] = rawX[i] - meanX;
            PHASE_Y[i] = rawY[i] - meanY;
        }
    }

    private static int frameIndex;
    private static volatile boolean resetRequested;

    /**
     * Whether an offset was put in force since the frame began.
     *
     * <p>Distinct from {@link #active()}, which is only true inside the extraction window the offset
     * is applied in. The motion producer runs at the other end of the same frame, after that window
     * has closed, and still has to know whether the projection it is handed carries the offset - so
     * that it can take it back out before building its matrices.
     */
    private static volatile boolean appliedThisFrame;

    /** Whether jitter is being applied this frame. Set by {@link #beginFrame}. */
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private ProjectionJitter() {
    }

    /**
     * Advance to this frame's phase and start applying it.
     *
     * <p>Called once per presented frame. The phase advances whether or not a temporal effect is
     * running, so switching the effect on does not restart the sequence - a visible hitch the first
     * time it is enabled.
     */
    public static void beginFrame() {
        ACTIVE.set(true);
        appliedThisFrame = true;
        frameIndex = (frameIndex + 1) % PHASES;
    }

    /** Stop applying the offset. Paired with {@link #beginFrame} in a finally. */
    public static void endFrame() {
        ACTIVE.set(false);
    }

    /** Whether {@link #beginFrame} ran this frame, so the projection carries the offset. */
    public static boolean appliedThisFrame() {
        return appliedThisFrame;
    }

    /** Whether a jittered matrix is expected right now, on this thread. */
    public static boolean active() {
        return ACTIVE.get();
    }

    /** This frame's offset in input-texture pixels, x then y. */
    public static float offsetX() {
        return PHASE_X[frameIndex];
    }

    public static float offsetY() {
        return PHASE_Y[frameIndex];
    }

    public static int phase() {
        return frameIndex;
    }

    /**
     * The offset expressed as a clip-space translation, which is what post-multiplying the projection
     * needs.
     *
     * <p>A translation of {@code (2 * pixelOffset / dimension)} in NDC moves the image by exactly that
     * many pixels, because NDC spans two units across the viewport.
     */
    public static float clipX(int width) {
        return width <= 0 ? 0.0f : 2.0f * offsetX() / width;
    }

    public static float clipY(int height) {
        return height <= 0 ? 0.0f : 2.0f * offsetY() / height;
    }

    // ---------------------------------------------------------------------------------------------
    // History lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Invalidate the temporal history at the next encode.
     *
     * <p>Called for anything that makes the previous frames unrelated to this one. Erring towards
     * resetting is the right bias: a reset costs one frame of convergence, while a missed reset blends
     * two different scenes together for as long as the filter's history lasts.
     */
    public static void requestReset() {
        resetRequested = true;
    }

    /** Take the pending reset, clearing it. True when this encode must discard history. */
    public static boolean consumeReset() {
        boolean pending = resetRequested;
        resetRequested = false;
        return pending;
    }

    /** Whether a reset is pending, without consuming it. For diagnostics. */
    public static boolean resetPending() {
        return resetRequested;
    }

    /** Forget the phase and any pending reset. For tests and for a device teardown. */
    public static void clear() {
        frameIndex = 0;
        resetRequested = false;
        appliedThisFrame = false;
        ACTIVE.set(false);
    }

    /**
     * The radical-inverse (Halton) sequence value for {@code index} in {@code base}, in [0,1).
     *
     * <p>Written out rather than taken from a library because it is nine lines and the definition is
     * the whole point: a "random" jitter would cluster, and clustered samples converge more slowly
     * than the regular grid the jitter exists to beat.
     */
    static float halton(int index, int base) {
        float result = 0.0f;
        float fraction = 1.0f / base;
        int i = index;
        while (i > 0) {
            result += (i % base) * fraction;
            i /= base;
            fraction /= base;
        }
        return result;
    }

    /** One-line summary for the log and the F3 section. */
    public static String summary() {
        return "phase " + frameIndex + "/" + PHASES
                + String.format(java.util.Locale.ROOT, " (%.3f, %.3f) px", offsetX(), offsetY());
    }
}
