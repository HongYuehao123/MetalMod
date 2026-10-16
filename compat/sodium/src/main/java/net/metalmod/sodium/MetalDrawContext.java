package net.metalmod.sodium;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.mixin.core.GpuDeviceAccessor;
import net.caffeinemc.mods.sodium.mixin.core.RenderPassAccessor;
import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalRenderPassBackend;
import net.metalmod.lighting.SodiumTerrainVariant;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Native Metal region state; this class never calls OpenGL or Vulkan. */
public final class MetalDrawContext extends DrawContext {
    private final ByteBuffer regionBytes = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
    private MetalRenderPassBackend backend;
    private RenderPipeline renderPipeline;

    public static boolean active() {
        return ((GpuDeviceAccessor) RenderSystem.getDevice()).sodium$getBackend() instanceof MetalDevice;
    }

    @Override public void setContext(RenderPass pass, RenderPipeline pipeline) {
        this.pass = pass;
        this.backend = (MetalRenderPassBackend) ((RenderPassAccessor) pass).getBackend();
        this.renderPipeline = pipeline;
    }

    @Override public void updateData(RenderRegion region, CameraTransform camera) {
        if (SodiumTerrainVariant.emptyLightsEnabled()) {
            // Re-evaluate the immutable published light set before each region, including changes
            // within a pass. A variant switch invalidates inline bindings, so select it first.
            backend.setSodiumPipeline(this.renderPipeline);
        }
        regionBytes.putFloat(0, getCameraTranslation(region.getOriginX(), camera.intX, camera.fracX));
        regionBytes.putFloat(4, getCameraTranslation(region.getOriginY(), camera.intY, camera.fracY));
        regionBytes.putFloat(8, getCameraTranslation(region.getOriginZ(), camera.intZ, camera.fracZ));
        regionBytes.putInt(12, Math.toIntExact(System.currentTimeMillis() - region.getCreationTime()));
        regionBytes.putInt(16, region.getId());
        backend.setUniformBytes(SodiumTerrainVariant.REGION, regionBytes);
    }

    @Override public void rotate() {}
    @Override public void delete() { pass = null; backend = null; renderPipeline = null; }
    @Override public void endDraw() { pass = null; backend = null; renderPipeline = null; }
}
