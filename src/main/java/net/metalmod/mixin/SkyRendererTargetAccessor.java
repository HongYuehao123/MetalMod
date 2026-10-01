package net.metalmod.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SkyRenderer captures its target at construction; update it at both world/UI boundaries. */
@Mixin(SkyRenderer.class)
public interface SkyRendererTargetAccessor {
    @Mutable @Accessor("renderTarget")
    void metalmod$setRenderTarget(RenderTarget target);
}
