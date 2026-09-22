package net.metalmod.backend;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/** No native view object is needed for the Phase 1 placeholders; the base class holds the range. */
public final class MetalTextureView extends GpuTextureView {

    private boolean closed;

    public MetalTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
    }

    @Override
    public void close() {
        this.closed = true;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }
}
