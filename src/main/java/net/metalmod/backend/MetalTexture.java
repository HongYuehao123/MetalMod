package net.metalmod.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

import java.lang.foreign.MemorySegment;

/**
 * A real MTLTexture. Phase 2 replaces the Phase 1 placeholder: creation now allocates GPU storage
 * with the mapped pixel format, texture type (2D / array / cube), mip level count and usage.
 *
 * <p><b>Storage mode.</b> A texture the CPU never uploads into is created with {@code
 * MTLStorageModePrivate}; one the engine declares {@code USAGE_COPY_DST} stays {@code
 * MTLStorageModeShared}, because that path writes the texture's bytes directly (see {@link
 * #needsSharedStorage}). Private storage is what lets the GPU keep a render target in tile memory
 * and, on Apple silicon, compress it; shared storage is CPU-coherent memory that the GPU reads and
 * writes through the same path. Render targets and depth buffers are the resources that matter here,
 * and they are exactly the ones the engine does not upload into.
 *
 * <p>The decision is a claim about engine behaviour, so it is checked rather than assumed. An upload
 * aimed at a private texture cannot work - the bytes are in GPU-private memory - so it is refused
 * and reported through {@link MetalDevice#reportPrivateCpuAccess}, which surfaces on F3 and in the
 * resource summary; a misclassified texture is a visible counter, not a silently blank one. Readback
 * does not have to be refused: {@code copyTextureToBuffer} blits the region into a shared staging
 * texture first, so reading a private render target works without the engine having declared
 * {@code USAGE_COPY_SRC}.
 */
public final class MetalTexture extends GpuTexture {

    /** {@code -Dmetalmod.privateTextures=false} forces every texture back to shared storage. */
    private static final boolean PRIVATE_TEXTURES =
            !"false".equalsIgnoreCase(System.getProperty("metalmod.privateTextures", "true"));

    /**
     * Whether a texture with this usage genuinely requires CPU-visible storage, as a pure policy
     * question.
     *
     * <p>Either copy flag does. {@code USAGE_COPY_DST} covers {@code writeToTexture} and
     * {@code copyBufferToTexture}, which hand the texture's own bytes to the CPU and cannot be
     * redirected into private memory. {@code USAGE_COPY_SRC} is kept conservative rather than
     * optimal: readback itself is staged and would work from a private texture, but a texture that
     * is uploaded *and* copied from - an atlas filling its mip chain is the obvious case - declares
     * only {@code COPY_SRC} on the source side, and the flags cannot distinguish it from a pure
     * render target. Keeping both shared costs one render target its private storage; treating
     * {@code COPY_SRC} as private risks silently losing an upload.
     *
     * <p>Separate from {@link #usesSharedStorage} so the rule can be tested without depending on how
     * the process was launched.
     */
    public static boolean needsSharedStorage(int usage) {
        return (usage & (GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC)) != 0;
    }

    /**
     * Whether this process creates textures with CPU-visible storage for a given usage.
     *
     * <p>Always true for a usage that genuinely needs it, whatever the switch says: the switch can
     * only make storage more conservative, never less.
     */
    public static boolean usesSharedStorage(int usage) {
        return !privateTexturesEnabled() || needsSharedStorage(usage);
    }

    public static boolean privateTexturesEnabled() {
        return PRIVATE_TEXTURES;
    }

    private final MetalDevice device;
    private final MemorySegment handle;
    private final boolean ownsHandle;
    private final boolean sharedStorage;
    private boolean closed;

    public MetalTexture(MetalDevice device, int usage, String label, GpuFormat format,
                        int width, int height, int depthOrLayers, int mipLevels) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;

        long pixelFormat = MetalFormat.mtlPixelFormat(format);
        int textureType = MetalFormat.mtlTextureType(usage, depthOrLayers);
        int mtlUsage = MetalFormat.mtlTextureUsage(usage);
        boolean shared = usesSharedStorage(usage);
        this.sharedStorage = shared;

        MemorySegment created = MetalNative.textureCreateFull(device.deviceHandle(), pixelFormat,
                width, height, depthOrLayers, mipLevels, textureType, shared, mtlUsage);
        this.handle = created == null ? MemorySegment.NULL : created;
        this.ownsHandle = this.handle.address() != 0;
        if (!this.ownsHandle) {
            MetalDevice.reportResourceFailure("texture '" + label + "' format=" + format
                    + " size=" + width + "x" + height + "x" + depthOrLayers + " mips=" + mipLevels);
        } else if (!shared) {
            MetalDevice.notePrivateTexture(label, format, width, height, depthOrLayers);
        }
    }

    /** False only when the CPU is refused access to this texture's contents. */
    public boolean isSharedStorage() {
        return this.sharedStorage;
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
