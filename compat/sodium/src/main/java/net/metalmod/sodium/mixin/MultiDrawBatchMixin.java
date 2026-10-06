package net.metalmod.sodium.mixin;

import net.metalmod.sodium.MetalDrawContext;
import net.metalmod.sodium.MetalDrawBatch;

import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=MultiDrawBatch.class, remap=false)
public abstract class MultiDrawBatchMixin {
    @Inject(method="newBatch", at=@At("HEAD"), cancellable=true, require=1)
    private static void metalmod$batch(int capacity, CallbackInfoReturnable<MultiDrawBatch> callback) {
        if (MetalDrawContext.active()) callback.setReturnValue(new MetalDrawBatch(capacity));
    }
}
