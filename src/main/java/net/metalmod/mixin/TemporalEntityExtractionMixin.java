package net.metalmod.mixin;

import net.metalmod.upscaling.TemporalEntityIdentity;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class TemporalEntityExtractionMixin {
    @Inject(method="extractRenderState",at=@At("HEAD"),require=1)
    private void metalmod$identity(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        ((TemporalEntityIdentity)state).metalmod$temporalIdentity(entity.getUUID());
    }
}
