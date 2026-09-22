package net.metalmod.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4fc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/** Phase 3 command encoder: owns a Metal command buffer, records render passes into it, submits. */
public final class MetalCommandEncoderBackend implements CommandEncoderBackend {

    private final MetalDevice device;
    private MemorySegment commandBuffer = MemorySegment.NULL;
    private MemorySegment currentEncoder = MemorySegment.NULL;

    public MetalCommandEncoderBackend(MetalDevice device) {
        this.device = device;
    }

    public MetalDevice device() {
        return this.device;
    }

    private MemorySegment ensureCommandBuffer() {
        if (this.commandBuffer.address() == 0) {
            this.commandBuffer = MetalNative.commandBufferCreate(this.device.queueHandle());
        }
        return this.commandBuffer;
    }

    static MemorySegment handleOf(GpuBuffer buffer) {
        return buffer instanceof MetalBuffer metal ? metal.handle() : MemorySegment.NULL;
    }

    static MemorySegment handleOf(GpuBufferSlice slice) {
        return slice == null ? MemorySegment.NULL : handleOf(slice.buffer());
    }

    static MemorySegment handleOf(GpuTextureView view) {
        return view instanceof MetalTextureView metal ? metal.handle() : MemorySegment.NULL;
    }

    private static MetalBuffer bufferOf(GpuBufferSlice slice) {
        return slice != null && slice.buffer() instanceof MetalBuffer metal ? metal : null;
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
        if (this.commandBuffer.address() != 0) {
            MetalNative.commandBufferCommit(this.commandBuffer);
            MetalNative.commandBufferRelease(this.commandBuffer);
            this.commandBuffer = MemorySegment.NULL;
        }
    }

    @Override
    public TransientMemory transientMemory() {
        return this.device.transientMemoryObject();
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        MemorySegment cb = ensureCommandBuffer();
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
        int count = colors.size();
        int width = 0;
        int height = 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment colorTextures = arena.allocate(ValueLayout.ADDRESS, Math.max(1, count));
            MemorySegment loadClear = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, count));
            MemorySegment clearColors = arena.allocate(ValueLayout.JAVA_FLOAT, Math.max(4, count * 4));

            for (int i = 0; i < count; i++) {
                RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
                GpuTextureView view = attachment.textureView();
                if (view == null) {
                    colorTextures.setAtIndex(ValueLayout.ADDRESS, i, MemorySegment.NULL);
                    loadClear.setAtIndex(ValueLayout.JAVA_INT, i, 0);
                    continue;
                }
                colorTextures.setAtIndex(ValueLayout.ADDRESS, i, handleOf(view));
                Optional<Vector4fc> clear = attachment.clearValue();
                if (clear != null && clear.isPresent()) {
                    Vector4fc value = clear.get();
                    loadClear.setAtIndex(ValueLayout.JAVA_INT, i, 1);
                    clearColors.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4, value.x());
                    clearColors.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4 + 1, value.y());
                    clearColors.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4 + 2, value.z());
                    clearColors.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4 + 3, value.w());
                } else {
                    loadClear.setAtIndex(ValueLayout.JAVA_INT, i, 0);
                }
                if (width == 0) {
                    width = view.getWidth(0);
                    height = view.getHeight(0);
                }
            }

            MemorySegment depthTexture = MemorySegment.NULL;
            boolean depthClear = false;
            double depthValue = 0.0;
            RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
            if (depth != null && depth.textureView() != null) {
                depthTexture = handleOf(depth.textureView());
                OptionalDouble clear = depth.clearValue();
                if (clear != null && clear.isPresent()) {
                    depthClear = true;
                    depthValue = clear.getAsDouble();
                }
                if (width == 0) {
                    width = depth.textureView().getWidth(0);
                    height = depth.textureView().getHeight(0);
                }
            }
            if (descriptor.renderArea != null) {
                width = descriptor.renderArea.width();
                height = descriptor.renderArea.height();
            }

            MemorySegment encoder = MetalNative.renderPassBegin(cb, count, colorTextures, loadClear,
                    clearColors, depthTexture, depthClear, depthValue, Math.max(1, width), Math.max(1, height));
            this.currentEncoder = encoder;
            if (encoder.address() == 0) {
                System.err.println("[MetalMod] render pass begin failed");
            }
            return new MetalRenderPassBackend(this, encoder, Math.max(1, width), Math.max(1, height));
        }
    }

    @Override
    public void submitRenderPass() {
        if (this.currentEncoder.address() != 0) {
            MetalNative.renderPassEnd(this.currentEncoder);
            this.currentEncoder = MemorySegment.NULL;
        }
    }

    // Clears -------------------------------------------------------------------------------------

    @Override
    public void clearColorTexture(GpuTexture colorTexture, Vector4fc color) {
        recordClear(color);
        MetalTexture metal = textureOf(colorTexture);
        if (metal != null) {
            MetalNative.clearTextures(this.device.queueHandle(), metal.handle(), true,
                    color.x(), color.y(), color.z(), color.w(), MemorySegment.NULL, false, 0.0);
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc color,
                                           GpuTexture depthTexture, double depth) {
        recordClear(color);
        MetalTexture colorMetal = textureOf(colorTexture);
        MetalTexture depthMetal = textureOf(depthTexture);
        MetalNative.clearTextures(this.device.queueHandle(),
                colorMetal == null ? MemorySegment.NULL : colorMetal.handle(), colorMetal != null,
                color == null ? 0f : color.x(), color == null ? 0f : color.y(),
                color == null ? 0f : color.z(), color == null ? 0f : color.w(),
                depthMetal == null ? MemorySegment.NULL : depthMetal.handle(), depthMetal != null, depth);
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc color,
                                           GpuTexture depthTexture, double depth,
                                           int x, int y, int width, int height) {
        clearColorAndDepthTextures(colorTexture, color, depthTexture, depth);
    }

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double depth) {
        MetalTexture metal = textureOf(depthTexture);
        if (metal != null) {
            MetalNative.clearTextures(this.device.queueHandle(), MemorySegment.NULL, false,
                    0f, 0f, 0f, 0f, metal.handle(), true, depth);
        }
    }

    // Copies -------------------------------------------------------------------------------------

    @Override
    public void writeToBuffer(GpuBufferSlice slice, ByteBuffer data) {
        MetalBuffer buffer = bufferOf(slice);
        if (buffer == null || !buffer.isMapped() || data == null || data.remaining() <= 0) {
            return;
        }
        buffer.dataSlice(slice.offset(), data.remaining()).asByteBuffer().put(data.duplicate());
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
        MemorySegment.copy(src.dataSlice(source.offset(), length), 0L,
                dst.dataSlice(target.offset(), length), 0L, length);
    }

    @Override
    public void writeToTexture(GpuTexture texture, ByteBuffer data, int mipLevel,
                               int x, int y, int width, int height, int depthOrLayers) {
        MetalTexture metal = textureOf(texture);
        if (metal == null || data == null) {
            return;
        }
        MetalNative.textureReplaceRegion(metal.handle(), mipLevel, depthOrLayers, x, y, width, height,
                data.duplicate(), (long) width * metal.bytesPerPixel());
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
        MetalNative.textureReplaceRegionRaw(dst.handle(), targetMipLevel, targetDepthOrLayers,
                targetX, targetY, targetWidth, targetHeight, src.dataSlice(offsetBytes, needed), rowBytes);
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
                MetalNative.textureReadRegion(metal.handle(), sourceMipLevel, 0, x, y, width, height,
                        buffer.dataSlice(targetOffset, needed), needed, rowBytes);
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
            if (MetalNative.textureReadRegion(src.handle(), sourceMipLevel, depthOrLayers, x, y, width,
                    height, temp, size, rowBytes) == 0) {
                MetalNative.textureReplaceRegionRaw(dst.handle(), targetMipLevel, depthOrLayers, x, y,
                        width, height, temp, rowBytes);
            }
        }
    }

    @Override
    public GpuFence createFence() {
        return new MetalFence();
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
