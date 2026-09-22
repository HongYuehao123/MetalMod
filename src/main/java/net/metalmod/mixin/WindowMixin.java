package net.metalmod.mixin;

import com.mojang.blaze3d.platform.Window;
import net.metalmod.Diagnostics;
import net.metalmod.render.VulkanFrameManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks framebuffer size changes.
 *
 * Retargeted to the real API: the method is {@code private void onFramebufferResize(long, int, int)},
 * not {@code onFramebufferSizeChanged}, and the old {@code net.minecraft.class_1041} target caused
 * Mixin to reject the whole mixin.
 */
@Mixin(Window.class)
public class WindowMixin {

    @Inject(method = "onFramebufferResize", at = @At("RETURN"))
    private void metalmod$onFramebufferResize(long window, int width, int height, CallbackInfo ci) {
        Diagnostics.hook("Window.onFramebufferResize");
        VulkanFrameManager.getInstance().updateDimensions(width, height);
    }
}
