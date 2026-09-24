package net.metalmod.mixin;

import net.metalmod.metalfx.SceneMotion;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records every visible entity's rendered position for the temporal motion field (ROADMAP Phase 7B).
 *
 * <p><b>Why entities need this at all.</b> The depth-reprojection producer describes anything that
 * was, relative to the camera, where it is now. An entity that walks, falls or is thrown is not: its
 * depth says only where it is, so the reprojection carries it along with the terrain and the temporal
 * filter leaves a trail behind it. The engine knows where it was, because it interpolates between the
 * previous and the current tick every frame - so the answer is already computed here, and this hook
 * only has to hand it over.
 *
 * <p><b>Why {@code extractEntity} and not the render state.</b> {@code EntityRenderState} is the type
 * the frame is assembled from, but it carries no identity: two cows are the same record. This is the
 * one place where the entity object and its extracted state are both in hand, so the previous frame's
 * position can be looked up by entity id rather than matched by proximity - and a wrong match would
 * be worse than no stamp, since it would give one animal another's velocity.
 *
 * <p><b>World coordinates, deliberately.</b> The state's {@code x}/{@code y}/{@code z} are the
 * interpolated world position, which is the frame-independent form: the camera's own movement is
 * already carried by the matrices, so storing world positions keeps this hook from depending on which
 * camera was current when it ran.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityMotionMixin {

    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void metalmod$recordEntityMotion(Entity entity, float partialTick,
                                             CallbackInfoReturnable<EntityRenderState> cir) {
        EntityRenderState state = cir.getReturnValue();
        if (state == null || entity == null) {
            return;
        }
        // An invisible entity is not drawn, so a stamp for it would cover pixels it does not own.
        // Its bounding box is still recorded through recordEntity's own check.
        SceneMotion.recordEntity(entity.getId(), state.x, state.y, state.z,
                state.boundingBoxWidth, state.boundingBoxHeight, state.isInvisible);
    }
}
