package net.metalmod.metalfx;

import net.metalmod.backend.MetalNative;

import java.lang.foreign.MemorySegment;

/**
 * A MetalFX spatial scaler, sized for one input/output pair.
 *
 * <p>A scaler is not resizable: its input and output dimensions are baked into the effect when it is
 * created, so a window resize replaces it. {@link #matches} is what lets the owner keep one scaler
 * across the frames where nothing changed instead of rebuilding it per frame.
 *
 * <p>Encoding is deliberately the owner's job - this class does not create or commit command
 * buffers. The upscale has to land in the frame's own commit order, behind the passes that wrote the
 * input and ahead of the blit that presents it, and only the caller knows where that is.
 */
public final class MetalFxScaler implements AutoCloseable {

    private final MemorySegment handle;
    private final MemorySegment device;
    private final long colorFormat;
    private final long outputFormat;
    private final int inputWidth;
    private final int inputHeight;
    private final int outputWidth;
    private final int outputHeight;
    private final long colorUsage;
    private final long outputUsage;
    private final int colorProcessingMode;

    private boolean closed;

    private MetalFxScaler(MemorySegment handle, MemorySegment device, long colorFormat,
                          long outputFormat, int inputWidth, int inputHeight,
                          int outputWidth, int outputHeight, long colorUsage, long outputUsage,
                          int colorProcessingMode) {
        this.handle = handle;
        this.device = device;
        this.colorFormat = colorFormat;
        this.outputFormat = outputFormat;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.colorUsage = colorUsage;
        this.outputUsage = outputUsage;
        this.colorProcessingMode = colorProcessingMode;
    }

    /**
     * Whether this device can scale between these formats at all.
     *
     * <p>Asked before a scaler is created so the backend can fall back to its own blit instead of
     * presenting nothing when MetalFX is unavailable.
     */
    public static boolean isSupported(MemorySegment device, long colorFormat, long outputFormat) {
        if (device == null || device.address() == 0) return false;
        if (!MetalNative.isAvailable()) return false;
        return MetalNative.fxSpatialSupported(device, colorFormat, outputFormat);
    }

    /**
     * Create a scaler, or return null with the reason recorded by {@link MetalFx}.
     */
    public static MetalFxScaler create(MemorySegment device, long colorFormat, long outputFormat,
                                       int inputWidth, int inputHeight,
                                       int outputWidth, int outputHeight,
                                       int colorProcessingMode) {
        if (device == null || device.address() == 0) {
            MetalFx.setUnavailableReason("no Metal device");
            return null;
        }
        if (!MetalNative.metalFxAvailable()) {
            MetalFx.setUnavailableReason("the native library has no MetalFX surface");
            return null;
        }
        if (!MetalNative.fxSpatialRunAvailable()) {
            MetalFx.setUnavailableReason("the native library has no MetalFX upscale entry point");
            return null;
        }
        MemorySegment handle = MetalNative.fxSpatialCreate(device, colorFormat, outputFormat,
                inputWidth, inputHeight, outputWidth, outputHeight, colorProcessingMode);
        if (handle == null || handle.address() == 0) {
            String reason = MetalNative.fxLastError();
            MetalFx.setUnavailableReason(reason.isEmpty()
                    ? "MetalFX rejected the scaler configuration" : reason);
            return null;
        }
        long[] usage = MetalNative.fxSpatialTextureUsage(handle);
        if (usage == null) {
            MetalNative.fxSpatialRelease(handle);
            MetalFx.setUnavailableReason("the scaler did not report its texture usage requirements");
            return null;
        }
        return new MetalFxScaler(handle, device, colorFormat, outputFormat, inputWidth, inputHeight,
                outputWidth, outputHeight, usage[0], usage[1], colorProcessingMode);
    }

    /** Whether this scaler is already the one these dimensions and formats need. */
    public boolean matches(long colorFormat, long outputFormat, int inputWidth, int inputHeight,
                           int outputWidth, int outputHeight) {
        return !this.closed
                && this.colorFormat == colorFormat && this.outputFormat == outputFormat
                && this.inputWidth == inputWidth && this.inputHeight == inputHeight
                && this.outputWidth == outputWidth && this.outputHeight == outputHeight;
    }

    /**
     * Run one upscale on the device queue.
     *
     * <p>The whole step is one native call: it creates its own command buffer, encodes the effect,
     * commits it and releases it. That is what keeps the upscale ordered behind the passes that wrote
     * the input - it joins the same queue in commit order - without this side having to reach into the
     * engine's command encoder, which already owns whatever it has recorded.
     */
    public int run(MemorySegment queue, MemorySegment colorTexture, MemorySegment outputTexture) {
        if (this.closed) return -1;
        return MetalNative.fxSpatialRun(this.handle, queue, colorTexture, outputTexture);
    }

    /**
     * Encode one upscale into a caller-owned command buffer, for tests and for a caller that needs to
     * put the effect in the middle of its own pass.
     *
     * @param contentWidth  region of the input actually filled this frame, or 0 for all of it.
     */
    public boolean encode(MemorySegment commandBuffer, MemorySegment colorTexture,
                          MemorySegment outputTexture, int contentWidth, int contentHeight) {
        if (this.closed) return false;
        return MetalNative.fxSpatialEncode(this.handle, commandBuffer, colorTexture, outputTexture,
                contentWidth, contentHeight) == 0;
    }

    /** The native handle, for the backend's own timing and diagnostics. */
    public MemorySegment handle() {
        return this.handle;
    }

    /** The input texture usage bits MetalFX requires; a render target handed to it must declare them. */
    public long colorTextureUsage() {
        return this.colorUsage;
    }

    public long outputTextureUsage() {
        return this.outputUsage;
    }

    public int inputWidth() {
        return this.inputWidth;
    }

    public int inputHeight() {
        return this.inputHeight;
    }

    public int outputWidth() {
        return this.outputWidth;
    }

    public int outputHeight() {
        return this.outputHeight;
    }

    /** A one-line description for the F3 section and the startup log. */
    public String describe() {
        return this.inputWidth + "x" + this.inputHeight + " -> " + this.outputWidth + "x"
                + this.outputHeight + " (colour mode " + this.colorProcessingMode + ")";
    }

    /**
     * Which pixel formats the effect was actually built for, as MTLPixelFormat raw values.
     *
     * <p>Reported rather than assumed: the settings screen shows this next to the sizes, so a user
     * checking "did MetalFX accept this configuration" reads the effect's own answer instead of the
     * one the code intended to ask for.
     */
    public String rawFormats() {
        return "MTLPixelFormat " + this.colorFormat + " -> " + this.outputFormat;
    }

    /** How many upscales this process has run, counted where the run happens. */
    public long framesRun() {
        return MetalNative.fxSpatialRunCount();
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        MetalNative.fxSpatialRelease(this.handle);
    }
}
