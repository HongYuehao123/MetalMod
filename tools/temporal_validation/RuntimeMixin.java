package net.metalmod.validation.mixin;

import net.metalmod.validation.TemporalInputValidation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class RuntimeMixin {
    @Inject(method="render",at=@At("RETURN"),require=1)
    private void validation$rendered(DeltaTracker delta,boolean world,CallbackInfo ci) {
        TemporalInputValidation.afterRender();
    }
}
