package net.metalmod.mixin;

import net.metalmod.debug.PerformanceCapture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class PerformanceCaptureMixin {
    // Verified against the real 26.2 client: one GpuSurface.present call per rendered frame.
    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V", shift = At.Shift.AFTER))
    private void metalmod$captureFrame(boolean renderLevel, CallbackInfo ci) {
        PerformanceCapture.framePresented((Minecraft) (Object) this);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void metalmod$finishCapture(CallbackInfo ci) {
        PerformanceCapture.close((Minecraft) (Object) this);
    }
}
