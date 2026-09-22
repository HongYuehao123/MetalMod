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
    private boolean viewportFlipped;
    private String currentTargetLabel = "?";

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

            String targetLabel = "?";
            if (count > 0 && colors.get(0).textureView() != null
                    && colors.get(0).textureView().texture() != null) {
                targetLabel = colors.get(0).textureView().texture().getLabel();
            }

            MemorySegment encoder = MetalNative.renderPassBegin(cb, count, colorTextures, loadClear,
                    clearColors, depthTexture, depthClear, depthValue, Math.max(1, width), Math.max(1, height));
            boolean atlasTarget = needsYFlip(targetLabel);
            noteRenderTarget(targetLabel, atlasTarget,
                    loadClear.getAtIndex(ValueLayout.JAVA_INT, 0) != 0, depthClear);
            if (encoder.address() != 0 && atlasTarget) {
                // TextureAtlas.uploadInitialContents() composites every sprite into the atlas with a
                // render pass per mip level, using a projection built by
                // TextureAtlasSprite.uploadSpriteUbo(): new Matrix4f().ortho2D(0, w, 0, h). That maps
                // atlas row 0 to NDC y = -1, i.e. it assumes a Y-down (Vulkan) NDC. Metal's NDC is
                // Y-up, so the composited atlas came out vertically mirrored and the sprite UVs
                // (v = y / atlasHeight) sampled the wrong sprite - the "unselected button" sampled a
                // status/icon sprite. Flip the viewport for every atlas mip pass (each is composited
                // independently from the sprite's own mip image) so the stored atlas matches the UVs.
                MetalNative.renderPassSetViewport(encoder, 0.0, (double) height, (double) width, -(double) height);
            }
            this.viewportFlipped = atlasTarget;
            this.currentTargetLabel = targetLabel;

            this.currentEncoder = encoder;
            if (encoder.address() == 0) {
                System.err.println("[MetalMod] render pass begin failed");
            }
            return new MetalRenderPassBackend(this, encoder, Math.max(1, width), Math.max(1, height));
        }
    }

    /**
     * Whether passes rendering into this target need the Y-flipped viewport.
     *
     * <p>Minecraft composites sprites and the lightmap into offscreen textures with a projection that
     * assumes a Y-down NDC - {@code TextureAtlasSprite.uploadSpriteUbo} builds
     * {@code new Matrix4f().ortho2D(0, w, 0, h)}, which maps data row 0 to NDC y = -1 - and then
     * samples those textures with {@code v = row / height}, where {@code v = 0} is the first data
     * row. Metal's NDC y = -1 is the *last* framebuffer row, so every one of those passes must flip
     * or the texture is stored mirrored. That produced two shipped bugs: the block atlas sampling a
     * neighbouring sprite, and the lightmap rendering the world at night under a day sky (BUG-022).
     *
     * <p>The label is the only signal available at this point, because the matrix that needs the flip
     * arrives later as the {@code Projection} uniform and the pipelines involved are ordinary texture
     * pipelines. A missed label is therefore a silent, wrong-looking frame - which is exactly how the
     * GUI item atlas, labelled {@code "UI items atlas"} and rendering inventory item icons, came out
     * upside down while the block atlases were fine. Every distinct label is logged once by
     * {@link #noteRenderTarget}, so the set is read off a run rather than reasoned about.
     */
    private static boolean needsYFlip(String label) {
        return label != null && (label.contains("/atlas/") || label.contains("UI items atlas"));
    }

    // One line per distinct render target, so a target that is rendered into without the flip can be
    // seen rather than guessed at. A "?" label means the pass had no colour texture to name.
    private static final java.util.Set<String> notedRenderTargets = new java.util.HashSet<>();

    private static void noteRenderTarget(String label, boolean flipped, boolean colorClear,
                                         boolean depthClear) {
        // Keyed by label *and* how it was loaded, so a target that is cleared in one pass and loaded
        // in another reports both rather than whichever came first.
        String key = label + "|" + colorClear + "|" + depthClear;
        synchronized (notedRenderTargets) {
            if (!notedRenderTargets.add(key)) {
                return;
            }
        }
        System.out.println("[MetalMod] render target '" + label + "' yFlip=" + flipped
                + " clear=" + colorClear + " depthClear=" + depthClear);
    }

    /**
     * Whether the current pass already has a Y-flipped viewport, so a second flip is not applied.
     * The atlas path decides this when the pass is created; screenquad pipelines decide it later.
     */
    /** The colour target of the pass currently being recorded, for the pipeline/target census. */
    String currentTargetLabel() {
        return this.currentTargetLabel;
    }

    boolean viewportFlipped() {
        return this.viewportFlipped;
    }

    /** Records that the current pass's viewport has been flipped. */
    void markViewportFlipped() {
        this.viewportFlipped = true;
    }

    @Override
    public void submitRenderPass() {
        if (this.currentEncoder.address() == 0) {
            return;
        }
        MetalNative.renderPassEnd(this.currentEncoder);
        this.currentEncoder = MemorySegment.NULL;

        // The engine records a render pass on one CommandEncoder but then calls submit() on a
        // different one, so deferring the commit to submit() lost every draw: only the standalone
        // clears (which commit immediately) ever reached the GPU. Commit here instead.
        if (this.commandBuffer.address() != 0) {
            MetalNative.commandBufferCommit(this.commandBuffer);
            MetalNative.commandBufferRelease(this.commandBuffer);
            this.commandBuffer = MemorySegment.NULL;
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
        // A rectangle, not the whole attachment: Metal's load-action clear would wipe everything
        // outside it. GuiItemAtlas clears one slot at a time into the GUI item atlas, so clearing
        // the whole texture there erased every slot already rendered. Vulkan uses VkClearRect here.
        recordClear(color);
        MetalTexture colorMetal = textureOf(colorTexture);
        MetalTexture depthMetal = textureOf(depthTexture);
        MetalNative.clearTexturesRegion(this.device.queueHandle(),
                colorMetal == null ? MemorySegment.NULL : colorMetal.handle(), colorMetal != null,
                color == null ? 0f : color.x(), color == null ? 0f : color.y(),
                color == null ? 0f : color.z(), color == null ? 0f : color.w(),
                depthMetal == null ? MemorySegment.NULL : depthMetal.handle(), depthMetal != null,
                depth, x, y, width, height);
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
                               int depthOrLayers, int x, int y, int width, int height) {
        MetalTexture metal = textureOf(texture);
        if (metal == null || data == null) {
            return;
        }
        // The interface order is (mipLevel, depthOrLayers, x, y, width, height); reading it as
        // (mip, x, y, width, height, layers) makes the region and its bytes-per-row garbage.
        int layers = Math.max(1, texture.getDepthOrLayers());
        int slice = layers > 1 ? Math.max(0, Math.min(depthOrLayers, layers - 1)) : 0;
        int mipWidth = Math.max(1, texture.getWidth(mipLevel));
        int mipHeight = Math.max(1, texture.getHeight(mipLevel));
        int safeX = Math.max(0, Math.min(x, mipWidth - 1));
        int safeY = Math.max(0, Math.min(y, mipHeight - 1));
        int safeWidth = Math.max(1, Math.min(width, mipWidth - safeX));
        int safeHeight = Math.max(1, Math.min(height, mipHeight - safeY));
        MetalNative.textureReplaceRegion(metal.handle(), mipLevel, slice, safeX, safeY,
                safeWidth, safeHeight, data.duplicate(), (long) safeWidth * metal.bytesPerPixel());
    }

    @Override
    public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY,
                                    int sourceRowLength, int sourceHeight, GpuTexture target,
                                    int targetX, int targetY, int targetWidth, int targetHeight,
                                    int targetMipLevel, int targetDepthOrLayers) {
        MetalBuffer src = bufferOf(source);
        MetalTexture dst = textureOf(target);
        if (src == null || dst == null || !src.isMapped()) {
            return;
        }
        int bytesPerPixel = dst.bytesPerPixel();
        // sourceRowLength is the source row stride in texels (0 means tightly packed), and
        // (sourceX, sourceY) is the sub-region origin inside that source image. The last two
        // parameters are (mipLevel, depthOrLayers).
        int rowLength = sourceRowLength > 0 ? sourceRowLength : Math.max(1, targetWidth);
        long rowBytes = (long) rowLength * bytesPerPixel;
        long offsetBytes = source.offset()
                + ((long) Math.max(0, sourceY) * rowLength + Math.max(0, sourceX)) * bytesPerPixel;
        int mipWidth = Math.max(1, target.getWidth(targetMipLevel));
        int mipHeight = Math.max(1, target.getHeight(targetMipLevel));
        int safeX = Math.max(0, Math.min(targetX, mipWidth - 1));
        int safeY = Math.max(0, Math.min(targetY, mipHeight - 1));
        int safeWidth = Math.max(1, Math.min(targetWidth, mipWidth - safeX));
        int safeHeight = Math.max(1, Math.min(targetHeight, mipHeight - safeY));
        long needed = rowBytes * safeHeight;
        if (offsetBytes < 0 || offsetBytes + needed > src.data().byteSize()) {
            // The staging buffer is tighter than the declared row length; fall back to the region
            // width so a short final mip does not abort the whole upload.
            rowLength = Math.max(1, safeWidth);
            rowBytes = (long) rowLength * bytesPerPixel;
            offsetBytes = source.offset()
                    + ((long) Math.max(0, sourceY) * rowLength + Math.max(0, sourceX)) * bytesPerPixel;
            needed = rowBytes * safeHeight;
            if (offsetBytes < 0 || offsetBytes + needed > src.data().byteSize()) {
                return;
            }
        }
        int layers = Math.max(1, target.getDepthOrLayers());
        int slice = layers > 1 ? Math.max(0, Math.min(targetDepthOrLayers, layers - 1)) : 0;
        MetalNative.textureReplaceRegionRaw(dst.handle(), targetMipLevel, slice, safeX, safeY,
                safeWidth, safeHeight, src.dataSlice(offsetBytes, needed), rowBytes);
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
            MetalNative.queueSynchronize(this.device.queueHandle());
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
        // A blit, not a CPU round trip. This call initialises each translucency layer's depth buffer
        // from the main depth buffer, so where it lands in the frame is load-bearing:
        // queueSynchronize plus a readback plus an upload is a separate, synchronised CPU operation
        // that stalls on a full-size depth buffer and does not sit at the point the engine assumes.
        // It also copies mipmapped atlas regions, where a readback of the wrong level silently
        // corrupts the atlas.
        //
        // Slice before level, matching mmm_copy_texture_to_texture: passing them the other way round
        // copies the wrong mip and an out-of-range slice for any texture with more than one level.
        // A blit, not a CPU round trip. This call initialises each translucency layer's depth buffer
        // from the main depth buffer, and the round trip it replaced wrote shared memory from the
        // CPU - which races the GPU still reading the previous frame's contents of that texture.
        // A stale value differs from the current one only while the camera moves, which is exactly
        // when the artefact appeared.
        int result = MetalNative.copyTextureToTexture(this.device.queueHandle(), src.handle(),
                depthOrLayers, sourceMipLevel, x, y, dst.handle(), depthOrLayers, targetMipLevel,
                x, y, width, height, 1);
        if (result != 0) {
            MetalDevice.reportResourceFailure("copyTextureToTexture returned " + result
                    + " mip " + sourceMipLevel + "->" + targetMipLevel
                    + " region " + width + "x" + height + " slice " + depthOrLayers);
        }
    }

    @Override
    public GpuFence createFence() {
        // Signals when everything committed before this point has completed, which is what the
        // engine's ring buffers wait on before recycling a slot.
        return new MetalFence(MetalNative.fenceCreate(this.device.queueHandle()));
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
