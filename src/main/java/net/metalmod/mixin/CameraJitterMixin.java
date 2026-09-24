package net.metalmod.mixin;

import net.metalmod.metalfx.ProjectionJitter;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sub-pixel projection jitter for temporal upscaling (ROADMAP Phase 7B).
 *
 * <p>A temporal scaler accumulates several frames, and it can only resolve detail those frames
 * actually sampled. Offsetting each frame's projection by a fraction of a pixel along a Halton
 * sequence is what gives the accumulation new information to work with; the offsets average to zero,
 * so the image does not drift.
 *
 * <p><b>Why this hook.</b> {@code Camera.extractRenderState} writes
 * {@code CameraRenderState.projectionMatrix}, which is the matrix the world passes read. The interface
 * has projections of its own, so jittering
 * this one moves the world and leaves the HUD still - which is required, because a jittered HUD would
 * shimmer.
 *
 * <p><b>Why the cull frustum is unaffected.</b> The camera prepares its frustum from a
 * <em>separate</em> projection ({@code createProjectionMatrixForCulling}) earlier in this same method.
 * So the jitter moves what is drawn without moving what is selected: a sub-pixel offset must not
 * change which chunks are visible, or the drawn set would flicker frame to frame.
 *
 * <p><b>Why it is gated.</b> The offset is applied only while {@link ProjectionJitter#active()}, which
 * is true only for frames the renderer declares temporal. With no temporal effect running - every frame
 * until a motion source exists - the engine's own matrix is left byte-for-byte alone, and the
 * feature-off path stays the pre-Phase-7 path.
 */
@Mixin(Camera.class)
public class CameraJitterMixin {

    /** The camera's own projection, which knows the width and height it was set up with. */
    @Shadow
    private Projection projection;

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void metalmod$jitterProjection(CameraRenderState renderState, float partialTick,
                                           CallbackInfo ci) {
        if (!ProjectionJitter.active()) {
            return;
        }
        Matrix4f matrix = renderState.projectionMatrix;
        if (matrix == null || this.projection == null) {
            return;
        }
        // Expressed against the projection's own viewport, which is the resolution the level is
        // drawing at - and the unit MetalFX takes its jitter offset in, so the two cannot disagree.
        float clipX = ProjectionJitter.clipX(Math.round(this.projection.width()));
        float clipY = ProjectionJitter.clipY(Math.round(this.projection.height()));
        // Post-multiplied: the offset is a clip-space translation, so it composes after the projection.
        matrix.translate(clipX, clipY, 0.0f);
    }
}
