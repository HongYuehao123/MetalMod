package net.metalmod.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import net.metalmod.metalfx.RenderScaleSettings;
import net.metalmod.metalfx.WorldRenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Render-resolution scaling (ROADMAP Phase 7A): the level renders into its own smaller target, the
 * interface keeps drawing into the engine's native one.
 *
 * <p><b>The problem this solves.</b> Minecraft renders the level and the interface into one target,
 * and everything about the interface - the GUI scale, every scissor rectangle, every text position -
 * is expressed in terms of that target's dimensions. Resizing it therefore cannot scale the world
 * without scaling the interface with it, which is why the pre-Phase-5 attempt at render scaling broke
 * the GUI and was reverted.
 *
 * <p><b>How the split is expressed.</b> {@link GameRenderer#mainRenderTarget()} is the single place
 * the level and the interface both ask for their target. Answering with the scaled target while the
 * level renders and with the real one at every other moment splits them with one hook, without either
 * side knowing the other exists. The scope flag and the upscale are driven by
 * {@link LevelRendererScalingMixin}, which wraps exactly the level's own render call.
 *
 * <p><b>What is deliberately not redirected.</b> The depth buffer. The colour-and-depth clear that
 * opens {@code render} would otherwise clear the small target's depth and leave the main target's
 * depth holding the previous frame's interface depth - and the interface the engine draws at the end
 * of this same method depth-tests against that buffer. So this mixin takes the clear's depth half for
 * the main target and lets its colour half follow the redirect.
 */
@Mixin(GameRenderer.class)
public class GameRendererScalingMixin {

    @Shadow
    private RenderTarget mainRenderTarget;

    @Inject(method = "mainRenderTarget", at = @At("RETURN"), cancellable = true)
    private void metalmod$scaleWorldTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget world = WorldRenderTarget.worldTargetIfRenderingLevel();
        if (world != null) {
            cir.setReturnValue(world);
        }
    }

    /**
     * Split the frame's opening clear: colour to the scaled world target, depth to the native one.
     *
     * <p>{@code render} clears both together, and the depth clear is not part of the redirect for the
     * reason above. Outside the level - which is where the interface's own colour override clear runs
     * - nothing is split and the call is the engine's own.
     */
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearColorAndDepthTextures"
                            + "(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;"
                            + "Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void metalmod$clearSplit(CommandEncoder encoder, GpuTexture color, Vector4fc clearColor,
                                     GpuTexture depth, double depthValue) {
        RenderTarget world = WorldRenderTarget.worldTarget();
        if (world == null || !WorldRenderTarget.frameHasLevel()) {
            // Feature off, or a frame with no level in it. Either way the engine's own clear is the
            // right one, and this is the path the menu and the loading screen take.
            encoder.clearColorAndDepthTextures(color, clearColor, depth, depthValue);
            return;
        }
        // The level's colour and depth are the world target's; the main target's depth is the
        // interface's, so it is cleared here rather than at the end of the level - the engine clears
        // the interface's depth buffer at that point itself.
        encoder.clearColorAndDepthTextures(world.getColorTexture(), clearColor,
                world.getDepthTexture(), depthValue);
        encoder.clearDepthTexture(this.mainRenderTarget.getDepthTexture(), depthValue);
    }

    /**
     * Follow the engine's own resize.
     *
     * <p>Hooked at {@code resize} as well as per frame so the scaled target is rebuilt at the same
     * moment the main one is: a frame that rendered between the two would otherwise render at the old
     * size and be upscaled into a mismatched target.
     */
    @Inject(method = "resize", at = @At("RETURN"))
    private void metalmod$resizeWorldTarget(int width, int height, CallbackInfo ci) {
        if (!RenderScaleSettings.active()) {
            return;
        }
        WorldRenderTarget.onEngineResize(width, height);
    }

    /**
     * Adopt a render-scale change made in the settings screen at the next frame boundary.
     *
     * <p>The upscale itself is driven by {@link LevelRendererScalingMixin}, which is the point in the
     * frame where the level's colour is complete and the interface has not drawn. This hook only
     * rebuilds the target when the setting moved, which needs the frame to be over: the pass that was
     * rendering into the old target was already created when the change arrived.
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void metalmod$adoptScaleChange(net.minecraft.client.DeltaTracker deltaTracker,
                                           boolean renderLevel, CallbackInfo ci) {
        WorldRenderTarget.refresh(this.mainRenderTarget.width, this.mainRenderTarget.height);
    }
}
