package net.metalmod.mixin;

import net.metalmod.metalfx.SceneMotion;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records each particle's rendered position, current and previous, for the temporal motion field.
 *
 * <p><b>Why particles are the worst case.</b> A particle is drawn with a depth test but without a
 * depth write, so the depth buffer behind it belongs to whatever surface is further away. The
 * depth-reprojection producer therefore gives a particle's pixels the <em>background's</em> motion -
 * which is wrong by however much the particle's depth differs, even for a particle that is not moving
 * at all. That is why every particle is stamped, and an entity's motion threshold is not applied here.
 *
 * <p><b>Why the extraction hook.</b> {@code extractRotatedQuad} is called once per particle per frame
 * with the camera-relative position the engine has already interpolated, so nothing is recomputed and
 * nothing is matched: the previous position is kept on the particle itself in a {@code @Unique} field.
 * The alternative - reading the particle's own {@code xo}/{@code x} and redoing the interpolation -
 * would be wrong on the frames where a tick lands between two rendered frames, which is one frame in
 * three at sixty frames per second.
 *
 * <p><b>Camera-relative, deliberately.</b> The value the extractor produces is relative to the
 * current camera, so the previous frame's value is relative to the previous camera. SceneMotion takes
 * the frame's own camera displacement back out before projecting, which is the same correction the
 * matrices apply to everything else.
 */
@Mixin(SingleQuadParticle.class)
public abstract class ParticleMotionMixin {

    @Unique
    private float metalmodPreviousX;
    @Unique
    private float metalmodPreviousY;
    @Unique
    private float metalmodPreviousZ;
    @Unique
    private boolean metalmodHasPrevious;

    /** The particle's world-space quad extent, which the engine already has. */
    @Shadow
    public abstract float getQuadSize(float partialTick);

    @Inject(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/level/"
                    + "QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
            at = @At("HEAD"))
    private void metalmod$recordParticleMotion(QuadParticleRenderState state, Quaternionf rotation,
                                               float x, float y, float z, float partialTick,
                                               CallbackInfo ci) {
        if (metalmodHasPrevious) {
            SceneMotion.recordParticle(x, y, z, getQuadSize(partialTick),
                    metalmodPreviousX, metalmodPreviousY, metalmodPreviousZ);
        }
        metalmodPreviousX = x;
        metalmodPreviousY = y;
        metalmodPreviousZ = z;
        metalmodHasPrevious = true;
    }
}
