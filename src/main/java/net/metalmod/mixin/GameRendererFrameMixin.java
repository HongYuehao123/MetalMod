package net.metalmod.mixin;

import net.metalmod.metalfx.ProjectionJitter;
import net.metalmod.metalfx.RenderScaleSettings;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two per-frame hooks Phase 7 needs before anything else in the frame runs: the jitter boundary,
 * and the record of whether this frame draws a level at all.
 *
 * <p><b>Jitter.</b> The offset has to be in force while the level's projection is <em>built</em>,
 * which happens during extraction - earlier than the frame's render call, and earlier than the Phase
 * 7A hooks. This pair on {@code GameRenderer.extract} is therefore the frame's own boundary as far as
 * jitter is concerned. Gated on {@link RenderScaleSettings#temporalEnabled()}, which is true only when
 * a temporal scaler is actually going to run this frame: a jittered projection that nothing
 * accumulates is a sub-pixel offset every frame and nothing in return.
 *
 * <p><b>Whether a level is drawn.</b> The colour clear at the top of {@code render} is split between
 * the level's target and the engine's own, and it happens <em>before</em> the engine decides whether to
 * draw a level. Without knowing that decision, a frame that draws only the interface would clear the
 * small target and never clear the large one - the interface would draw over the previous frame. So the
 * decision is made here, before the clear, using the same three conditions the engine uses.
 */
@Mixin(GameRenderer.class)
public class GameRendererFrameMixin {

    @Inject(method = "extract", at = @At("HEAD"))
    private void metalmod$beginFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        // Nothing about the camera has been captured for this frame yet. A frame that draws no level
        // must not reuse the previous frame's camera as if it were current - that would publish
        // motion for a frame that never rendered one.
        net.metalmod.metalfx.SceneMotion.beginFrame();
        // The frame boundary for the level's target: anything the settings screen or a window resize
        // asked for is built here, and the target it replaced is retired rather than destroyed,
        // because the previous frame's command buffers may still be reading it. Doing this inside a
        // frame instead is what crashed the render thread when the window was maximised.
        // A rebuilt target invalidates every pipeline the engine compiled while it was a different
        // size. Without this the frame keeps drawing with a pipeline built against the old target
        // until something else happens to clear the cache - which is exactly the reported symptom: a
        // toggle leaves the sky wrong, and F3+T fixes it because a resource reload clears the pipeline
        // cache and recompiles. Doing it here costs one recompile per toggle instead of a full resource
        // reload, and it is the same invalidation the reload performs.
        if (net.metalmod.metalfx.WorldRenderTarget.applyPending()) {
            net.metalmod.backend.MetalDevice device = net.metalmod.backend.MetalDevice.active();
            if (device != null) {
                device.invalidatePipelines();
                System.out.println("[MetalMod] render target changed: compiled pipelines invalidated");
            }
        }
        // Scaling only runs where MetalFX can actually do it - and which effect can run depends on
        // what was asked for, because a temporal scaler additionally needs a motion source this
        // machine and this dylib can produce. Asked here, before the frame graph is built, so a
        // machine without a usable scaler renders natively instead of rendering small into a target
        // nothing would present, and a machine without motion falls back to Spatial.
        net.metalmod.metalfx.WorldRenderTarget.setScalingAvailable(
                net.metalmod.metalfx.WorldRenderTarget.metalFxUsable(
                        net.metalmod.backend.MetalDevice.active()));
        // Whether this frame is scaled at all, decided here and never revisited. A frame that changes
        // its mind halfway through is a frame whose passes were built against one target and executed
        // against another.
        net.metalmod.metalfx.WorldRenderTarget.decideScalingForFrame();
        net.metalmod.metalfx.WorldRenderTarget.beginFrame(
                renderLevel && willDrawLevel());
        // The jitter has to be in force while the level's projection is *built*, which happens during
        // extraction - earlier than the frame's render call and earlier than any Phase 7A hook. This
        // is therefore the frame's own boundary as far as jitter is concerned. Gated on the effect
        // actually running: a jittered projection with no temporal accumulation to justify it is a
        // sub-pixel offset every frame and nothing in return.
        if (RenderScaleSettings.temporalEnabled()
                && net.metalmod.metalfx.WorldRenderTarget.frameHasLevel()) {
            ProjectionJitter.beginFrame();
        }
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void metalmod$endJitterFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        if (ProjectionJitter.active()) {
            ProjectionJitter.endFrame();
        }
    }

    /**
     * The engine's own condition for drawing a level, read from the same sources it reads.
     *
     * <p>{@code render} computes {@code isGameLoadFinished() && renderLevel && level != null} after the
     * clear; this evaluates the same three terms before it. Kept in one method with the engine's
     * expression quoted, because the alternative - inferring it later - is what makes a frame that
     * draws only the interface show the previous frame.
     */
    private static boolean willDrawLevel() {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        return minecraft != null && minecraft.isGameLoadFinished() && minecraft.level != null;
    }
}
