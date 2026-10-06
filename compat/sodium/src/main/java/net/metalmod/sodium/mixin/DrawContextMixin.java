package net.metalmod.sodium.mixin;

import net.metalmod.sodium.MetalDrawContext;
import net.metalmod.sodium.MetalDrawBatch;

import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=DrawContext.class, remap=false)
public abstract class DrawContextMixin {
    @Inject(method="create", at=@At("HEAD"), cancellable=true, require=1)
    private static void metalmod$create(CallbackInfoReturnable<DrawContext> callback) {
        if (MetalDrawContext.active()) callback.setReturnValue(new MetalDrawContext());
    }
}
