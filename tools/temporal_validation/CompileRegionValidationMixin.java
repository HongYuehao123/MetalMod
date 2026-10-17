package net.metalmod.validation.mixin;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.metalmod.validation.RegionLifetimeValidation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Apply after the production return hook, then inspect bounded samples at the final gate. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask", priority = 500)
public abstract class CompileRegionValidationMixin {
    @Shadow private RenderSectionRegion region;

    @Inject(method = "doTask", at = @At("RETURN"), require = 1)
    private void validation$completed(CallbackInfoReturnable<?> ci) {
        RegionLifetimeValidation.sample(() -> this.region == null);
    }
}
