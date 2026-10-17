package net.metalmod.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.metalmod.performance.VignetteNoOp;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserve world-border tint, nonzero darkening, and resource-pack shader replacements. */
@Mixin(Hud.class)
public abstract class VignetteNoOpMixin {
    @Redirect(method = "extractVignette", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIII)V"), require = 1)
    private void metalmod$skipVerifiedNoOp(GuiGraphicsExtractor graphics, RenderPipeline pipeline,
                                          Identifier texture, int x, int y, float u, float v,
                                          int width, int height, int textureWidth, int textureHeight, int color) {
        if (!VignetteNoOp.canSkip(pipeline, color))
            graphics.blit(pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight, color);
    }
}
