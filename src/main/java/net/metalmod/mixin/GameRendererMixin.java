package net.metalmod.mixin;

import net.metalmod.Diagnostics;
import net.metalmod.render.VulkanFrameManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-frame hook.
 *
 * Retargeted to the real API. The previous revision injected into
 * {@code GameRenderer.render} by looking for {@code method_3192}, and into
 * {@code getBasicProjectionMatrix} by looking for {@code method_3198}. Neither the intermediary
 * names nor {@code getBasicProjectionMatrix} exist in this build (verified with javap), and the
 * non-existent {@code class_757} target additionally caused Mixin to reject the whole mixin.
 *
 * Real signature: {@code public void render(DeltaTracker, boolean)}.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void metalmod$onFrameBegin(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        Diagnostics.hook("GameRenderer.render");
        VulkanFrameManager.getInstance().onFrameBegin();
    }

    @Inject(method = "resize", at = @At("RETURN"))
    private void metalmod$onRendererResize(int width, int height, CallbackInfo ci) {
        Diagnostics.hook("GameRenderer.resize");
        VulkanFrameManager.getInstance().onDisplayResized();
    }
}
