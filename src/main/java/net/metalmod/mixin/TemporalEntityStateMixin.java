package net.metalmod.mixin;

import net.metalmod.upscaling.TemporalEntityIdentity;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import java.util.UUID;

@Mixin(EntityRenderState.class)
public class TemporalEntityStateMixin implements TemporalEntityIdentity {
    @Unique private UUID metalmod$identity;
    @Override public UUID metalmod$temporalIdentity() { return metalmod$identity; }
    @Override public void metalmod$temporalIdentity(UUID value) { metalmod$identity=value; }
}
