package net.metalmod.mixin;

import net.metalmod.metalfx.SceneMotion;
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer;
import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records a pushed block's rendered position for the temporal motion field (ROADMAP Phase 7B).
 *
 * <p>A piston's pushed block is a block entity: its block position never changes while it moves, and
 * the movement lives entirely in the interpolated offset the renderer draws it at. That makes it the
 * one moving block with a stable identity and an exact previous position - {@code getXOff(partialTick)}
 * and its siblings are the interpolated offsets, so consecutive frames differ by exactly the movement
 * that happened between them.
 *
 * <p>Without this the block reprojects as if the piston had never fired, which is a one-block object
 * dragging a trail across the screen for the two ticks the animation lasts.
 *
 * <p>The stamp covers the block's own cube. A piston draws a base and a head; both are moved by the
 * same offsets and both are within the block the stamp covers, so one stamp is the right shape for
 * both rather than an approximation of them.
 */
@Mixin(PistonHeadRenderer.class)
public class PistonMotionMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/level/block/piston/"
                    + "PistonMovingBlockEntity;Lnet/minecraft/client/renderer/blockentity/state/"
                    + "PistonHeadRenderState;FLnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
            at = @At("RETURN"))
    private void metalmod$recordPistonMotion(PistonMovingBlockEntity blockEntity,
                                             PistonHeadRenderState state, float partialTick,
                                             net.minecraft.world.phys.Vec3 cameraPos,
                                             net.minecraft.client.renderer.feature
                                                     .ModelFeatureRenderer.CrumblingOverlay overlay,
                                             CallbackInfo ci) {
        BlockPos position = state.blockPos;
        if (position == null) {
            return;
        }
        SceneMotion.recordBlock(position.asLong(),
                position.getX() + state.xOffset,
                position.getY() + state.yOffset,
                position.getZ() + state.zOffset,
                1.0f);
    }
}
