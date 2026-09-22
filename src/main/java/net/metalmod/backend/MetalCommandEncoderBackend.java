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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * Phase 2 command encoder.
 *
 * <p>Uploads, copies, clears and readbacks are real now. All resources use shared storage on this
 * backend, so they are implemented as CPU copies via `replaceRegion`/`getBytes` rather than blit
 * or render encoders. That is correct and simple; a GPU-side blit and private storage for
 * GPU-only resources are Phase 3 performance work.
 */
public final class MetalCommandEncoderBackend implements CommandEncoderBackend {

    private final MetalDevice device;

    public MetalCommandEncoderBackend(MetalDevice device) {
        this.device = device;
    }

    private static MetalBuffer bufferOf(GpuBufferSlice slice) {
        if (slice == null || !(slice.buffer() instanceof MetalBuffer buffer)) {
            return null;
        }
        return buffer;
    }

    private static MetalTexture textureOf(GpuTexture texture) {
        if (texture instanceof MetalTexture metal && metal.isValid() && metal.exactSizeMapping()) {
            return metal;
        }
        return null;
    }

    private void recordClear(Vector4fc color) {
        if (color != null) {
            this.device.setLastClearColor(color.x(), color.y(), color.z(), color.w());
        }
    }

    @Override
    public void submit() {
        // CPU-side copies complete before this call; there is no queued GPU work of our own yet.
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
        MetalTexture metal = textureOf(colorTexture);
        if (metal == null) {
            return;
        }
        MetalNative.clearTextures(this.device.queueHandle(), metal.handle(), true,
                color.x(), color.y(), color.z(), color.w(),
                MemorySegment.NULL, false, 0.0);
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc color,
                                           GpuTexture depthTexture, double depth) {
        recordClear(color);
        MetalTexture colorMetal = textureOf(colorTexture);
        MetalTexture depthMetal = textureOf(depthTexture);
        if (colorMetal == null && depthMetal == null) {
            return;
        }
        MetalNative.clearTextures(this.device.queueHandle(),
                colorMetal == null ? MemorySegment.NULL : colorMetal.handle(), colorMetal != null,
                color.x(), color.y(), color.z(), color.w(),
                depthMetal == null ? MemorySegment.NULL : depthMetal.handle(), depthMetal != null, depth);
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc color,
                                           GpuTexture depthTexture, double depth,
                                           int x, int y, int width, int height) {
        // Metal clears whole attachments; the scissored variant is only used for partial clears,
        // which no current caller does. Fall back to the full clear.
        clearColorAndDepthTextures(colorTexture, color, depthTexture, depth);
    }

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double depth) {
        MetalTexture metal = textureOf(depthTexture);
        if (metal == null) {
            return;
        }
        MetalNative.clearTextures(this.device.queueHandle(), MemorySegment.NULL, false,
                0f, 0f, 0f, 0f, metal.handle(), true, depth);
    }

    @Override
    public void writeToBuffer(GpuBufferSlice slice, ByteBuffer data) {
        MetalBuffer buffer = bufferOf(slice);
        if (buffer == null || !buffer.isMapped() || data == null) {
            return;
        }
        int length = data.remaining();
        if (length <= 0) {
            return;
        }
        buffer.dataSlice(slice.offset(), length).asByteBuffer().put(data.duplicate());
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        MetalBuffer src = bufferOf(source);
        MetalBuffer dst = bufferOf(target);
        if (src == null || dst == null || !src.isMapped() || !dst.isMapped()) {
            return;
        }
        long length = Math.min(source.length(), target.length());
        if (length <= 0) {
            return;
        }
        MemorySegment from = src.dataSlice(source.offset(), length);
        MemorySegment to = dst.dataSlice(target.offset(), length);
        MemorySegment.copy(from, 0L, to, 0L, length);
    }

    @Override
    public void writeToTexture(GpuTexture texture, ByteBuffer data, int mipLevel,
                               int x, int y, int width, int height, int depthOrLayers) {
        MetalTexture metal = textureOf(texture);
        if (metal == null || data == null) {
            return;
        }
        long bytesPerRow = (long) width * metal.bytesPerPixel();
        MetalNative.textureReplaceRegion(metal.handle(), mipLevel, depthOrLayers, x, y, width, height,
                data.duplicate(), bytesPerRow);
    }

    @Override
    public void copyBufferToTexture(GpuBufferSlice source, int sourceOffset, int sourceRowLength,
                                    int sourceHeight, int sourceMipLevel, GpuTexture target,
                                    int targetX, int targetY, int targetWidth, int targetHeight,
                                    int targetDepthOrLayers, int targetMipLevel) {
        MetalBuffer src = bufferOf(source);
        MetalTexture dst = textureOf(target);
        if (src == null || dst == null || !src.isMapped()) {
            return;
        }
        int bytesPerPixel = dst.bytesPerPixel();
        long rowBytes = (long) sourceRowLength * bytesPerPixel;
        long offsetBytes = source.offset() + (long) sourceOffset * bytesPerPixel;
        long needed = rowBytes * Math.max(1, targetHeight);
        if (offsetBytes < 0 || offsetBytes + needed > src.data().byteSize()) {
            return;
        }
        MemorySegment data = src.dataSlice(offsetBytes, needed);
        MetalNative.textureReplaceRegionRaw(dst.handle(), targetMipLevel, targetDepthOrLayers,
                targetX, targetY, targetWidth, targetHeight, data, rowBytes);
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer target, long targetOffset,
                                    Runnable onComplete, int sourceMipLevel) {
        copyTextureToBuffer(source, target, targetOffset, onComplete, sourceMipLevel,
                0, 0, source.getWidth(sourceMipLevel), source.getHeight(sourceMipLevel));
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer target, long targetOffset,
                                    Runnable onComplete, int sourceMipLevel,
                                    int x, int y, int width, int height) {
        MetalTexture metal = textureOf(source);
        if (metal != null && target instanceof MetalBuffer buffer && buffer.isMapped()) {
            long rowBytes = (long) width * metal.bytesPerPixel();
            long needed = rowBytes * height;
            if (targetOffset >= 0 && targetOffset + needed <= buffer.data().byteSize()) {
                MemorySegment destination = buffer.dataSlice(targetOffset, needed);
                MetalNative.textureReadRegion(metal.handle(), sourceMipLevel, 0, x, y, width, height,
                        destination, needed, rowBytes);
            }
        }
        if (onComplete != null) {
            onComplete.run();
        }
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture target, int sourceMipLevel,
                                     int targetMipLevel, int x, int y, int width, int height,
                                     int depthOrLayers) {
        MetalTexture src = textureOf(source);
        MetalTexture dst = textureOf(target);
        if (src == null || dst == null) {
            return;
        }
        long rowBytes = (long) width * src.bytesPerPixel();
        long size = rowBytes * height;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment temp = arena.allocate(size);
            int rc = MetalNative.textureReadRegion(src.handle(), sourceMipLevel, depthOrLayers,
                    x, y, width, height, temp, size, rowBytes);
            if (rc == 0) {
                MetalNative.textureReplaceRegionRaw(dst.handle(), targetMipLevel, depthOrLayers,
                        x, y, width, height, temp, rowBytes);
            }
        }
    }

    @Override
    public GpuFence createFence() {
        return new MetalFence();
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        // GPU timestamps land with the render-pass encoding in Phase 3.
    }
}
