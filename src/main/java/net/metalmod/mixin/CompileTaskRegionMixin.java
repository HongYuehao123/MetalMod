package net.metalmod.mixin;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The task can outlive compilation while its upload completes; the region is no longer read. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class CompileTaskRegionMixin {
    @Shadow @Final @Mutable private RenderSectionRegion region;

    @Inject(method = "doTask", at = @At("RETURN"), require = 1)
    private void metalmod$releaseRegion(CallbackInfoReturnable<?> ci) {
        if (Boolean.parseBoolean(System.getProperty("metalmod.compileRegionDrop", "true"))) {
            this.region = null;
        }
    }
}
