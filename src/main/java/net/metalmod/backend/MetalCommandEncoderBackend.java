package net.metalmod.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;

/**
 * Phase 1 command encoder.
 *
 * <p>Submission and every copy/clear that targets a placeholder resource are no-ops. Clears still
 * record their colour, because the present path reuses it: with the draw path inert, the colour the
 * engine picks for its main render target is the most meaningful colour the first-light window can
 * show.
 */
public final class MetalCommandEncoderBackend implements CommandEncoderBackend {

    private final MetalDevice device;

    public MetalCommandEncoderBackend(MetalDevice device) {
        this.device = device;
    }

    private void recordClear(Vector4fc color) {
        if (color != null) {
            this.device.setLastClearColor(color.x(), color.y(), color.z(), color.w());
        }
    }

    @Override
    public void submit() {
        // Nothing is queued: placeholder resources own no GPU work, and the surface's clear+present
        // is committed from MetalSurfaceBackend in a single command buffer.
    }

    @Override
    public TransientMemory transientMemory() {
        return this.device.transientMemoryObject();
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        return new MetalRenderPassBackend();
    }

    @Override
    public void submitRenderPass() {
    }

    @Override
    public void clearColorTexture(GpuTexture colorTexture, Vector4fc color) {
        recordClear(color);
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc color,
                                           GpuTexture depthTexture, double depth) {
        recordClear(color);
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc color,
                                           GpuTexture depthTexture, double depth,
                                           int x, int y, int width, int height) {
        recordClear(color);
    }

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double depth) {
    }

    @Override
    public void writeToBuffer(GpuBufferSlice slice, ByteBuffer data) {
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
    }

    @Override
    public void writeToTexture(GpuTexture texture, ByteBuffer data, int mipLevel,
                               int x, int y, int width, int height, int depthOrLayers) {
    }

    @Override
    public void copyBufferToTexture(GpuBufferSlice source, int sourceOffset, int sourceRowLength,
                                    int sourceHeight, int sourceMipLevel, GpuTexture target,
                                    int targetX, int targetY, int targetWidth, int targetHeight,
                                    int targetDepthOrLayers, int targetMipLevel) {
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer target, long targetOffset,
                                    Runnable onComplete, int sourceMipLevel) {
        if (onComplete != null) {
            onComplete.run();
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer target, long targetOffset,
                                    Runnable onComplete, int sourceMipLevel,
                                    int x, int y, int width, int height) {
        if (onComplete != null) {
            onComplete.run();
        }
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture target, int sourceMipLevel,
                                     int targetMipLevel, int x, int y, int width, int height,
                                     int depthOrLayers) {
    }

    @Override
    public GpuFence createFence() {
        return new MetalFence();
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
