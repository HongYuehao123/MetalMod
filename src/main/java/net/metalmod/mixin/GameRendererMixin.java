package net.metalmod.mixin;

import net.metalmod.config.MetalConfig;
import net.metalmod.render.JitterHelper;
import net.metalmod.render.VulkanFrameManager;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = {
    "net.minecraft.client.renderer.GameRenderer",
    "net.minecraft.class_757"
})
public class GameRendererMixin {

    @Inject(method = {"render", "method_3192"}, at = @At("HEAD"), require = 0)
    private void onRenderBegin(CallbackInfo ci) {
        VulkanFrameManager.getInstance().onFrameBegin();
    }

    @Inject(method = {"getBasicProjectionMatrix", "method_3198"}, at = @At("RETURN"), cancellable = true, require = 0)
    private void injectSubpixelJitter(float fov, CallbackInfoReturnable<Matrix4f> cir) {
        if (MetalConfig.INSTANCE.scalingMode == MetalConfig.ScalingMode.TEMPORAL) {
            Matrix4f matrix = cir.getReturnValue();
            if (matrix != null) {
                int renderW = VulkanFrameManager.getInstance().getRenderWidth();
                int renderH = VulkanFrameManager.getInstance().getRenderHeight();

                float jitterX = JitterHelper.getProjectionJitterX(renderW);
                float jitterY = JitterHelper.getProjectionJitterY(renderH);

                matrix.m20(matrix.m20() + jitterX);
                matrix.m21(matrix.m21() + jitterY);
                cir.setReturnValue(matrix);
            }
        }
    }
}
