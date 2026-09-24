package net.metalmod.metalfx;

import net.metalmod.backend.MetalNative;

/**
 * What MetalFX can actually do on this machine, queried once and reported.
 *
 * <p>Phase 7 has to fall back cleanly on hardware or an OS without an effect, so every mode is
 * decided by a capability query rather than by an assumption about Apple silicon. The answers are
 * also what the F3 section and the settings screen show, so a user who picks a mode can see whether
 * the machine accepted it.
 */
public final class MetalFx {

    /** MetalFX spatial scaling (Phase 7A): one frame in, one upscaled frame out. */
    public static final String SPATIAL = "spatial";
    /** MetalFX temporal scaling (Phase 7B): needs colour, depth and motion, and keeps history. */
    public static final String TEMPORAL = "temporal";
    /** No MetalFX: the backend's own linear blit still upscales, it just does not reconstruct. */
    public static final String OFF = "off";

    private static volatile String unavailableReason = "not queried";

    private MetalFx() {
    }

    /** Whether the dylib exports the MetalFX surface at all. */
    public static boolean available() {
        return MetalNative.metalFxAvailable();
    }

    public static boolean temporalAvailable() {
        return MetalNative.fxTemporalAvailable();
    }

    public static String unavailableReason() {
        return unavailableReason;
    }

    public static void setUnavailableReason(String reason) {
        unavailableReason = reason;
    }
}
