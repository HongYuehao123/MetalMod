package net.metalmod.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Vector4fc;
import net.metalmod.Diagnostics;
import net.metalmod.upscaling.MetalFxCoordinator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Verified 26.2 boundary: scene + outline + post chain, reconstruct, then native GUI. */
@Mixin(GameRenderer.class)
public abstract class SuperResolutionMixin {
    @Shadow @Final @Mutable private RenderTarget mainRenderTarget;
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private net.minecraft.client.renderer.state.GameRenderState gameRenderState;
    @Unique private boolean metalmod$earlyTemporal;
    @Shadow private void tryTakeScreenshotIfNeeded() { throw new AssertionError(); }
    @Unique private boolean metalmod$deferredScreenshot;
    @Unique private final MetalFxCoordinator metalmod$fx = new MetalFxCoordinator();
    @Unique private RenderTarget metalmod$output;
    @Unique private GlobalSettingsUniform metalmod$uniform;
    @Unique private int metalmod$width, metalmod$height, metalmod$blur;
    @Unique private double metalmod$glint;
    @Unique private long metalmod$time;
    @Unique private DeltaTracker metalmod$delta;
    @Unique private Vec3 metalmod$camera;
    @Unique private boolean metalmod$rgss;

    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"), require = 1)
    private GpuBufferSlice metalmod$worldProjection(ProjectionMatrixBuffer buffer, Matrix4f projection) {
        this.metalmod$fx.captureTemporalProjection(projection,this.gameRenderState.levelRenderState);
        if (this.metalmod$fx.jitterProof().active()) Diagnostics.hook("TemporalProof.worldProjection");
        return buffer.getBuffer(this.metalmod$fx.jitterProof().projection(projection, false));
    }

    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"), require = 1)
    private GpuBufferSlice metalmod$handProjection(ProjectionMatrixBuffer buffer, Projection projection) {
        if (this.metalmod$fx.temporalActive() || !this.metalmod$fx.jitterProof().active()) return buffer.getBuffer(projection);
        Diagnostics.hook("TemporalProof.handProjection");
        return buffer.getBuffer(this.metalmod$fx.jitterProof().projection(projection.getMatrix(new Matrix4f()), true));
    }

    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"), require = 1)
    private void metalmod$preserveWorldDepth(CommandEncoder encoder, GpuTexture depth, double clear) {
        if (this.metalmod$fx.temporalActive() && this.metalmod$output!=null) {
            Diagnostics.hook("Temporal.worldReconstruction");
            RenderTarget output=this.metalmod$output;
            this.mainRenderTarget=output;this.metalmod$output=null;
            this.metalmod$fx.finish(output);this.metalmod$earlyTemporal=true;
            this.metalmod$uniform.update(this.metalmod$width,this.metalmod$height,this.metalmod$glint,
                    this.metalmod$time,this.metalmod$delta,this.metalmod$blur,this.metalmod$camera,this.metalmod$rgss);
            this.metalmod$bindSkyTarget(output);
            encoder.clearDepthTexture(output.getDepthTexture(),clear);
            return;
        }
        encoder.clearDepthTexture(depth, clear);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/systems/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V"), require = 1)
    private void metalmod$sceneClear(CommandEncoder encoder, GpuTexture color, Vector4fc clear,
                                     GpuTexture depth, double clearDepth, DeltaTracker delta, boolean renderLevel) {
        Diagnostics.hook("SuperResolution.world");
        this.metalmod$earlyTemporal=false;
        RenderTarget output = this.mainRenderTarget;
        RenderTarget target = this.metalmod$fx.begin(output, renderLevel && this.minecraft.level != null
                && this.minecraft.isGameLoadFinished(), this.minecraft.levelRenderer::resize);
        if (target != output) {
            this.metalmod$output = output;
            this.mainRenderTarget = target;
        }
        this.metalmod$bindSkyTarget(target);
        encoder.clearColorAndDepthTextures(target.getColorTexture(), clear, target.getDepthTexture(), clearDepth);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJLnet/minecraft/client/DeltaTracker;ILnet/minecraft/world/phys/Vec3;Z)V"), require = 1)
    private void metalmod$worldGlobals(GlobalSettingsUniform uniform, int width, int height, double glint,
                                       long time, DeltaTracker delta, int blur, Vec3 camera, boolean rgss,
                                       DeltaTracker renderDelta, boolean renderLevel) {
        RenderTarget target = this.mainRenderTarget;
        if (this.metalmod$output != null) {
            this.metalmod$uniform = uniform;
            this.metalmod$width = width; this.metalmod$height = height; this.metalmod$glint = glint;
            this.metalmod$time = time; this.metalmod$delta = delta; this.metalmod$blur = blur;
            this.metalmod$camera = camera; this.metalmod$rgss = rgss;
        }
        uniform.update(target.width, target.height, glint, time, delta, blur, camera, rgss);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"), require = 1)
    private void metalmod$nativeUi(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        Diagnostics.hook("SuperResolution.ui");
        if (this.metalmod$output != null) {
            RenderTarget output = this.metalmod$output;
            // Restore the engine target even if encoding rejects the frame.
            this.mainRenderTarget = output;
            this.metalmod$output = null;
            this.metalmod$fx.finish(output);
            this.metalmod$uniform.update(this.metalmod$width, this.metalmod$height, this.metalmod$glint,
                    this.metalmod$time, this.metalmod$delta, this.metalmod$blur, this.metalmod$camera, this.metalmod$rgss);
        } else if (!this.metalmod$earlyTemporal) this.metalmod$fx.finish(this.mainRenderTarget);
        this.metalmod$bindSkyTarget(this.mainRenderTarget);
        if (this.metalmod$deferredScreenshot) {
            this.metalmod$deferredScreenshot = false;
            this.tryTakeScreenshotIfNeeded();
        }
    }

    @Unique private void metalmod$bindSkyTarget(RenderTarget target) {
        if (net.metalmod.backend.MetalDevice.active() == null) return;
        var sky = this.minecraft.levelRenderer.skyRenderer();
        if (sky != null) ((SkyRendererTargetAccessor) sky).metalmod$setRenderTarget(target);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/GameRenderer;tryTakeScreenshotIfNeeded()V"), require = 1)
    private void metalmod$screenshot(GameRenderer renderer) {
        if (this.metalmod$output != null) this.metalmod$deferredScreenshot = true;
        else this.tryTakeScreenshotIfNeeded();
    }

    @Inject(method = {"setLevel", "preloadUiShader"}, at = @At("RETURN"), require = 1)
    private void metalmod$resourceChange(CallbackInfo ci) { this.metalmod$fx.invalidate(); }

    @Inject(method = "resize", at = @At("RETURN"), require = 1)
    private void metalmod$resize(int width, int height, CallbackInfo ci) { this.metalmod$fx.invalidate(); }

    @Inject(method = "close", at = @At("HEAD"), require = 1)
    private void metalmod$close(CallbackInfo ci) {
        if (this.metalmod$output != null) { this.mainRenderTarget = this.metalmod$output; this.metalmod$output = null; }
        this.metalmod$fx.close();
    }
}
