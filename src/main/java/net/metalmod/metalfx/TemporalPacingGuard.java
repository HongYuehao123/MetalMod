package net.metalmod.metalfx;

/**
 * Gives Temporal a sustained chance to meet the display cadence before switching to Spatial.
 *
 * <p>The input is the drawable's actual presentation report, rather than CPU frame time. A single
 * missed refresh can come from loading a chunk or the OS; fifteen misses in a ten-second window
 * while Temporal is running indicate that this configuration has exhausted its frame budget.
 * Once Spatial takes over, it gets the same ten-second observation. If Spatial also misses the
 * refresh budget, the slowdown was not isolated to Temporal; restore the requested image quality
 * and suppress another switch for this world/configuration. If Spatial holds cadence, keep it.
 */
final class TemporalPacingGuard {
    private static final long WINDOW_NANOS = 10_000_000_000L;
    private static final long MIN_PRESENTED = 300;
    private static final long MIN_DROPPED = 15;
    private static final double MIN_DROP_FRACTION = 0.025;

    private long windowStart;
    private long startingPresented;
    private long startingDropped;
    private boolean fallback;
    private boolean fallbackSuppressed;

    boolean observe(long now, long presented, long dropped) {
        if (fallback) return true;
        if (fallbackSuppressed) return false;
        if (windowStart == 0 || presented < startingPresented || dropped < startingDropped) {
            windowStart = now;
            startingPresented = presented;
            startingDropped = dropped;
            return false;
        }
        if (now - windowStart < WINDOW_NANOS) return false;
        long frames = presented - startingPresented;
        long misses = dropped - startingDropped;
        fallback = frames >= MIN_PRESENTED && misses >= MIN_DROPPED
                && (double) misses / frames >= MIN_DROP_FRACTION;
        windowStart = now;
        startingPresented = presented;
        startingDropped = dropped;
        return fallback;
    }

    /** Returns true when Spatial's own missed refreshes disprove the Temporal-only diagnosis. */
    boolean observeSpatial(long now, long presented, long dropped) {
        if (!fallback) return false;
        if (windowStart == 0 || presented < startingPresented || dropped < startingDropped) {
            windowStart = now;
            startingPresented = presented;
            startingDropped = dropped;
            return false;
        }
        if (now - windowStart < WINDOW_NANOS) return false;
        long frames = presented - startingPresented;
        long misses = dropped - startingDropped;
        boolean sharedSlowdown = frames >= MIN_PRESENTED && misses >= MIN_DROPPED
                && (double) misses / frames >= MIN_DROP_FRACTION;
        windowStart = now;
        startingPresented = presented;
        startingDropped = dropped;
        if (sharedSlowdown) {
            fallback = false;
            fallbackSuppressed = true;
        }
        return sharedSlowdown;
    }

    boolean fallback() {
        return fallback;
    }

    void reset() {
        windowStart = 0;
        startingPresented = 0;
        startingDropped = 0;
        fallback = false;
        fallbackSuppressed = false;
    }
}
