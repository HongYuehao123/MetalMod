package net.metalmod.backend;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.lang.foreign.MemorySegment;

/**
 * A real texture view. A full-range, same-format view reuses the parent handle; a mip-level range
 * creates an MTLTexture view so mipmapped sampling can bind the right levels.
 */
public final class MetalTextureView extends GpuTextureView {

    private final MemorySegment handle;
    private final boolean ownsHandle;
    private boolean closed;

    public MetalTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);

        if (!(texture instanceof MetalTexture metal)) {
            this.handle = MemorySegment.NULL;
            this.ownsHandle = false;
            return;
        }

        boolean fullRange = baseMipLevel == 0 && mipLevels == texture.getMipLevels();
        if (fullRange || !metal.isValid()) {
            this.handle = metal.handle();
            this.ownsHandle = false;
            return;
        }

        long pixelFormat = MetalFormat.mtlPixelFormat(texture.getFormat());
        int textureType = MetalFormat.mtlTextureType(texture.usage(), texture.getDepthOrLayers());
        MemorySegment created = MetalNative.textureCreateView(metal.handle(), pixelFormat, textureType,
                baseMipLevel, mipLevels, 0, texture.getDepthOrLayers());
        this.handle = created == null ? MemorySegment.NULL : created;
        this.ownsHandle = this.handle.address() != 0;
        if (!this.ownsHandle) {
            MetalDevice.reportResourceFailure("texture view base=" + baseMipLevel + " levels=" + mipLevels);
        }
    }

    public MemorySegment handle() {
        return this.handle;
    }

    public boolean isValid() {
        return this.handle.address() != 0;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.ownsHandle) {
            MetalNative.textureRelease(this.handle);
        }
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }
}
