package net.metalmod.mixin;

import net.metalmod.render.VulkanFrameManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = {
    "com.mojang.blaze3d.pipeline.RenderTarget",
    "net.minecraft.class_276"
})
public abstract class RenderTargetMixin {

    private boolean isMainTarget() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getMainRenderTarget() == (Object) this) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return this.getClass().getSimpleName().contains("Main");
    }

    @ModifyVariable(
        method = {"resize", "method_1234"},
        at = @At("HEAD"),
        ordinal = 0,
        argsOnly = true,
        require = 0
    )
    private int modifyResizeWidth(int width) {
        if (isMainTarget()) {
            VulkanFrameManager mgr = VulkanFrameManager.getInstance();
            mgr.updateDimensions(width, mgr.getNativeHeight());
            return mgr.getScaledWidth(width);
        }
        return width;
    }

    @ModifyVariable(
        method = {"resize", "method_1234"},
        at = @At("HEAD"),
        ordinal = 1,
        argsOnly = true,
        require = 0
    )
    private int modifyResizeHeight(int height) {
        if (isMainTarget()) {
            VulkanFrameManager mgr = VulkanFrameManager.getInstance();
            return mgr.getScaledHeight(height);
        }
        return height;
    }

    @Inject(
        method = {"blitToScreen", "method_1237"},
        at = @At("HEAD"),
        require = 0
    )
    private void onBlitToScreen(int width, int height, CallbackInfo ci) {
        if (isMainTarget()) {
            VulkanFrameManager.getInstance().onFramePresent(70.0f, 0.05f, 1000.0f);
        }
    }
}
