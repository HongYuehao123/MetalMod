package net.metalmod.upscaling;

import java.util.UUID;

/** Stable entity identity attached to an extracted state, never inferred from a buffer address. */
public interface TemporalEntityIdentity {
    UUID metalmod$temporalIdentity();
    void metalmod$temporalIdentity(UUID identity);
}
