package net.metalmod.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

import java.lang.foreign.MemorySegment;

/**
 * A real MTLTexture. Phase 2 replaces the Phase 1 placeholder: creation now allocates GPU storage
 * with the mapped pixel format, texture type (2D / array / cube), mip level count and usage.
 *
 * <p><b>Storage mode.</b> Every texture is created with {@code MTLStorageModeShared}, which is the
 * measured-faster configuration (see {@link #privateTexturesEnabled}). Private storage for render
 * attachments is behind a switch, off by default. Shared storage is CPU-coherent memory the GPU reads
 * and writes through the same path; private storage is what would let the GPU keep a render target in
 * tile memory and compress it, and it did not pay for itself here.
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

    /**
     * Private storage for render attachments. <b>Off by default, because it measured slower.</b>
     *
     * <p>An A/B on one route in one world, both runs on Metal minutes apart, put private storage 9%
     * behind on both stages: above ground 9.72 ms against 8.91 ms, and in the heavy underground stage
     * 16.40 ms against 15.06 ms. The later run was the faster one, so drift works against that result
     * rather than for it. Whatever tile-memory or compression benefit was expected did not appear on
     * this hardware, and the cost of a private render target that later passes sample is real. The
     * switch stays for a machine or a driver where the answer could differ.
     *
     * <p>Set {@code -Dmetalmod.privateTextures=all} to enable it, or {@code true} for the same thing.
     * Any other value, including a typo, leaves it off.
     */
    private static final boolean PRIVATE_TEXTURES =
            enablePrivateTextures(System.getProperty("metalmod.privateTextures", "false"));

    /** Only {@code all} and {@code true} enable it, so an unrecognised value fails safe. */
    static boolean enablePrivateTextures(String value) {
        return "all".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value);
    }

    /**
     * Whether a texture with this usage is a candidate for private storage.
     *
     * <p>Only a render attachment is. The copy flags cannot be used to tell an uploaded texture from
     * a rendered-into one: {@code RenderTarget} creates <em>both</em> its depth and its colour
     * texture with the full flag set - {@code COPY_DST | COPY_SRC | TEXTURE_BINDING |
     * RENDER_ATTACHMENT}, the constant 15 - so every render target in the game declares both copy
     * flags. Classifying on them claimed nothing at all (a real session reported
     * {@code privateTextures=0} while every render target stayed shared).
     *
     * <p>The render-attachment bit is a truthful signal, checked against the engine rather than
     * assumed: {@code TextureAtlas} builds the sprite sheet with {@code ANIMATE_SPRITE_BLIT} render
     * passes into the atlas mip views (usage 15), {@code Lightmap} renders its 16x16 texture with a
     * render pass (usage 13), and the textures the engine actually uploads from the CPU - plain
     * textures, dynamic textures and the cloud texel buffers - declare usage 5
     * ({@code COPY_DST | TEXTURE_BINDING}) and never the render-attachment bit. So atlases and
     * lightmaps being claimed here is correct, and the uploads that do happen are not.
     *
     * <p>Separate from {@link #usesSharedStorage} so the rule can be tested without depending on how
     * the process was launched.
     */
    public static boolean privateEligible(int usage) {
        return (usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0;
    }

    /**
     * Whether this process creates textures with CPU-visible storage for a given usage.
     *
     * <p>Always true for a usage that is not a candidate for private storage, whatever the switch
     * says: the switch can only make storage more conservative, never less.
     */
    public static boolean usesSharedStorage(int usage) {
        return !privateTexturesEnabled() || !privateEligible(usage);
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
            MetalDevice.notePrivateTexture(label, format, width, height, depthOrLayers, usage);
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
