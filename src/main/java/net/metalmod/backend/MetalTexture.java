package net.metalmod.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

import java.lang.foreign.MemorySegment;

/**
 * A real MTLTexture. Phase 2 replaces the Phase 1 placeholder: creation now allocates GPU storage
 * with the mapped pixel format, texture type (2D / array / cube), mip level count and usage.
 *
 * <p>All textures use shared storage, so the CPU upload and readback paths work without a staging
 * blit. Apple silicon has unified memory, so the cost is lower than on a discrete GPU; a
 * private/staging split is a Phase 3 performance task.
 */
public final class MetalTexture extends GpuTexture {

    private final MetalDevice device;
    private final MemorySegment handle;
    private final boolean ownsHandle;
    private boolean closed;

    public MetalTexture(MetalDevice device, int usage, String label, GpuFormat format,
                        int width, int height, int depthOrLayers, int mipLevels) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;

        long pixelFormat = MetalFormat.mtlPixelFormat(format);
        int textureType = MetalFormat.mtlTextureType(usage, depthOrLayers);
        int mtlUsage = MetalFormat.mtlTextureUsage(usage);

        MemorySegment created = MetalNative.textureCreateFull(device.deviceHandle(), pixelFormat,
                width, height, depthOrLayers, mipLevels, textureType, true, mtlUsage);
        this.handle = created == null ? MemorySegment.NULL : created;
        this.ownsHandle = this.handle.address() != 0;
        if (!this.ownsHandle) {
            MetalDevice.reportResourceFailure("texture '" + label + "' format=" + format
                    + " size=" + width + "x" + height + "x" + depthOrLayers + " mips=" + mipLevels);
        }
    }

    public MetalDevice device() {
        return this.device;
    }

    public MemorySegment handle() {
        return this.handle;
    }

    public boolean isValid() {
        return this.handle.address() != 0;
    }

    /** Bytes per pixel of the mapped Metal format (3-channel formats map to a 4-channel format). */
    public int bytesPerPixel() {
        return getFormat().blockSize();
    }

    /** False for the 3-channel formats, whose mapped Metal format has a different row stride. */
    public boolean exactSizeMapping() {
        return MetalFormat.isExactSizeMapping(getFormat());
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
