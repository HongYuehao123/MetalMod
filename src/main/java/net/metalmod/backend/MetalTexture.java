package net.metalmod.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

/**
 * Placeholder texture for Phase 1.
 *
 * <p>Phase 1 proves device + surface + present, with the renderer's draw calls inert. No pass ever
 * samples or renders into these, so they carry no native MTLTexture yet; the metadata the engine
 * validates (format, usage, dimensions, mip levels) is passed through exactly. Phase 2 gives this
 * a real handle.
 */
public final class MetalTexture extends GpuTexture {

    private boolean closed;

    public MetalTexture(int usage, String label, GpuFormat format,
                        int width, int height, int depthOrLayers, int mipLevels) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
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
