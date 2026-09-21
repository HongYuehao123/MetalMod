package net.metalmod.mixin;

import net.metalmod.render.VulkanFrameManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = {
    "com.mojang.blaze3d.platform.Window",
    "net.minecraft.class_1041"
})
public class WindowMixin {

    @Inject(method = {"onFramebufferSizeChanged", "method_4490"}, at = @At("RETURN"), require = 0)
    private void onFramebufferSizeChanged(long window, int width, int height, CallbackInfo ci) {
        VulkanFrameManager.getInstance().updateDimensions(width, height);
    }
}
