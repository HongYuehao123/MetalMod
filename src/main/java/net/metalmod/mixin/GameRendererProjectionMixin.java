package net.metalmod.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.metalmod.metalfx.SceneMotion;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Records the projection matrix the level is actually rendered with, for the temporal scaler's
 * scene contract (ROADMAP Phase 7B).
 *
 * <p><b>Why here and not the camera.</b> {@code renderLevel} starts from
 * {@code CameraRenderState.projectionMatrix} and folds the frame's view bob and the portal/nausea
 * screen effect into it before handing the result to the device. Rebuilding the matrix from the
 * camera state would omit both, and view bobbing would then appear as several pixels of wrong motion
 * at the edges of the screen - the case temporal upscaling is most visible in.
 *
 * <p><b>Why this call.</b> {@code ProjectionMatrixBuffer.getBuffer(Matrix4f)} receives exactly that
 * finished matrix, and the {@code Matrix4f} overload has one caller in the whole client. The
 * {@code Projection} overload used by the hand, the HUD, the panorama and every post chain is a
 * different method and is not touched. The original is called unchanged: this observes the matrix,
 * it does not substitute one.
 */
@Mixin(GameRenderer.class)
public class GameRendererProjectionMixin {

    @Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer"
                            + "(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private GpuBufferSlice metalmod$captureLevelProjection(ProjectionMatrixBuffer buffer,
                                                           Matrix4f matrix) {
        SceneMotion.captureProjection(matrix);
        return buffer.getBuffer(matrix);
    }
}
