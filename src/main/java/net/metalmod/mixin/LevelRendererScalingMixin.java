package net.metalmod.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.metalmod.metalfx.WorldRenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The scope of the render-resolution redirect, and the point the level's colour returns to native.
 *
 * <p>{@code LevelRenderer.render} is exactly the level's own frame: it imports the engine's main
 * target into its frame graph, builds the sky, main, cloud, weather and always-on-top passes, runs
 * the translucency and entity-outline post chains, and executes. Nothing the interface draws happens
 * inside it, and everything the interface draws happens after it. That makes its two ends the only
 * two points that matter for Phase 7A:
 *
 * <ul>
 *   <li><b>HEAD</b> - from here until RETURN, {@code GameRenderer.mainRenderTarget()} answers with the
 *       scaled target, so the frame graph, the deferred targets it allocates at that size, and both
 *       post chains all work at the render resolution without knowing about it.</li>
 *   <li><b>RETURN</b> - the level's colour is complete and the interface has not drawn. The upscale
 *       runs here, leaving the native target holding a full-resolution world for the interface to
 *       draw over. Doing it later would put the interface underneath the upscale.</li>
 * </ul>
 *
 * <p>Both hooks are needed even though the scale is off by default: with no scaled target,
 * {@code enterLevel} only records the scope and {@code upscale} returns false immediately, so the
 * feature-off path is the engine's own frame plus two calls.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererScalingMixin {

    @Shadow
    private GameRenderer gameRenderer;

    @Inject(method = "render", at = @At("HEAD"))
    private void metalmod$beginScaledLevel(GraphicsResourceAllocator resourceAllocator,
                                           DeltaTracker deltaTracker, boolean renderBlockOutline,
                                           CameraRenderState cameraRenderState,
                                           Matrix4fc frustumMatrix, GpuBufferSlice fogBuffer,
                                           Vector4f clearColor, boolean isShadowPass, CallbackInfo ci) {
        // Sized before the frame graph imports it: the passes capture its dimensions when they are
        // built, so it has to be final by here rather than by the end of the method.
        RenderTarget main = this.gameRenderer.mainRenderTarget();
        WorldRenderTarget.ensureBeforeLevel(main.width, main.height);
        WorldRenderTarget.enterLevel();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void metalmod$endScaledLevel(GraphicsResourceAllocator resourceAllocator,
                                         DeltaTracker deltaTracker, boolean renderBlockOutline,
                                         CameraRenderState cameraRenderState,
                                         Matrix4fc frustumMatrix, GpuBufferSlice fogBuffer,
                                         Vector4f clearColor, boolean isShadowPass, CallbackInfo ci) {
        // Left before the upscale: the upscale asks the engine for its main target, and it must get
        // the real one rather than the target it is about to write into.
        WorldRenderTarget.leaveLevel();
        RenderTarget main = this.gameRenderer.mainRenderTarget();
        WorldRenderTarget.upscale(main);
        net.metalmod.metalfx.ScaleHotkey.sampleIfRequested(
                net.minecraft.client.Minecraft.getInstance());
    }
}
