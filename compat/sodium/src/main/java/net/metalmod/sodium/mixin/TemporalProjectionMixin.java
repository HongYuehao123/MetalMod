package net.metalmod.sodium.mixin;

import net.metalmod.sodium.MetalDrawContext;
import net.metalmod.upscaling.TemporalProjectionAccess;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sodium caches the unjittered projection before vanilla uploads its separate jittered buffer. */
@Mixin(value = GameRenderer.class, priority = 1500)
public abstract class TemporalProjectionMixin {
    // Applied after Sodium's priority-1000 mixin adds this getter. The adapter's version guard
    // restricts the target to the verified 0.9.2 ABI; require=1 rejects a changed producer.
    @Inject(method = "sodium$getProjectionMatrix", at = @At("RETURN"), cancellable = true,
            remap = false, require = 1)
    private void metalmod$terrainProjection(CallbackInfoReturnable<Matrix4fc> ci) {
        if (MetalDrawContext.active()) {
            ci.setReturnValue(((TemporalProjectionAccess) this).metalmod$sceneProjection(ci.getReturnValue()));
        }
    }
}
