package net.metalmod.metalfx;

import net.metalmod.backend.MetalNative;

import java.lang.foreign.MemorySegment;

/**
 * A MetalFX temporal scaler, sized for one input/output pair.
 *
 * <p>Like the spatial scaler, it is not resizable: the input and output dimensions, the four pixel
 * formats and the descriptor decisions are baked into the effect when it is created, so a window
 * resize or a render-scale change replaces it. {@link #matches} is what lets the owner keep one
 * scaler across the frames where nothing changed.
 *
 * <p><b>What is different from spatial.</b> A temporal scaler keeps history, so it takes three inputs
 * rather than one - colour, depth and motion - plus the per-frame projection jitter and a reset flag.
 * The reset is not optional bookkeeping: a camera cut, a world change or a resize makes the previous
 * frames unrelated to this one, and a scaler left to blend them produces a visible smear rather than
 * an error.
 *
 * <p><b>Why reactive mask is off.</b> The descriptor option is only meaningful with a producer, and
 * this backend has none: the mask wants to flag pixels whose motion is unreliable - alpha-blended
 * surfaces and independently moving geometry - and there is no per-object velocity or coverage buffer
 * to build it from. Enabling the option without a mask texture would be a claim the code cannot back,
 * so it stays off and the transparency question stays open rather than answered wrongly.
 */
public final class MetalFxTemporalScaler implements AutoCloseable {

    private final MemorySegment handle;
    private final long colorFormat;
    private final long depthFormat;
    private final long motionFormat;
    private final long outputFormat;
    private final int inputWidth;
    private final int inputHeight;
    private final int outputWidth;
    private final int outputHeight;
    private final boolean reactiveMask;
    private final boolean jitteredMotion;

    private boolean closed;

    private MetalFxTemporalScaler(MemorySegment handle, long colorFormat, long depthFormat,
                                  long motionFormat, long outputFormat,
                                  int inputWidth, int inputHeight, int outputWidth, int outputHeight,
                                  boolean reactiveMask, boolean jitteredMotion) {
        this.handle = handle;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
        this.motionFormat = motionFormat;
        this.outputFormat = outputFormat;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.reactiveMask = reactiveMask;
        this.jitteredMotion = jitteredMotion;
    }

    /** Whether this device can scale temporally between these formats at all. */
    public static boolean isSupported(MemorySegment device, long colorFormat, long depthFormat,
                                      long motionFormat, long outputFormat) {
        if (device == null || device.address() == 0) return false;
        if (!MetalNative.isAvailable() || !MetalNative.fxTemporalAvailable()) return false;
        return MetalNative.fxTemporalSupported(device, colorFormat, depthFormat, motionFormat,
                outputFormat);
    }

    /**
     * Create a scaler, or return null with the reason recorded by {@link MetalFx}.
     *
     * <p>{@code depthReversed} is false for this backend: it reports {@code isZZeroToOne}, so the
     * engine's projection maps near to 0 and far to 1.
     */
    public static MetalFxTemporalScaler create(MemorySegment device,
                                               long colorFormat, long depthFormat,
                                               long motionFormat, long outputFormat,
                                               int inputWidth, int inputHeight,
                                               int outputWidth, int outputHeight,
                                               boolean depthReversed) {
        if (device == null || device.address() == 0) {
            MetalFx.setUnavailableReason("no Metal device");
            return null;
        }
        if (!MetalNative.fxTemporalAvailable()) {
            MetalFx.setUnavailableReason("the native library has no MetalFX temporal surface");
            return null;
        }
        MemorySegment handle = MetalNative.fxTemporalCreate(device, colorFormat, depthFormat,
                motionFormat, outputFormat, inputWidth, inputHeight, outputWidth, outputHeight,
                depthReversed,
                /*dynamicResolution=*/false, 1.0f, 1.0f,
                /*reactiveMask=*/false, 0L,
                /*jitteredMotion=*/false);
        if (handle == null || handle.address() == 0) {
            String reason = MetalNative.fxLastError();
            MetalFx.setUnavailableReason(reason.isEmpty()
                    ? "MetalFX rejected the temporal scaler configuration" : reason);
            return null;
        }
        return new MetalFxTemporalScaler(handle, colorFormat, depthFormat, motionFormat, outputFormat,
                inputWidth, inputHeight, outputWidth, outputHeight, false, false);
    }

    /** Whether this scaler is already the one these dimensions and formats need. */
    public boolean matches(long colorFormat, long depthFormat, long motionFormat, long outputFormat,
                           int inputWidth, int inputHeight, int outputWidth, int outputHeight) {
        return !this.closed
                && this.colorFormat == colorFormat && this.depthFormat == depthFormat
                && this.motionFormat == motionFormat && this.outputFormat == outputFormat
                && this.inputWidth == inputWidth && this.inputHeight == inputHeight
                && this.outputWidth == outputWidth && this.outputHeight == outputHeight;
    }

    /**
     * Encode one temporal upscale into a caller-owned command buffer.
     *
     * <p>Encoding is deliberately the owner's job, exactly as it is for the spatial scaler: the
     * upscale has to land in the frame's own commit order, behind the motion dispatch and the passes
     * that wrote the colour and depth, and ahead of the blit that presents the result. Only the caller
     * knows where that is.
     *
     * <p>{@code jitterX}/{@code jitterY} are in input-texture pixels and must be the values the frame
     * was actually rendered with; a mismatch shows up as a permanently soft or vibrating image rather
     * than as an error.
     *
     * @return true when the effect accepted the frame
     */
    public boolean encode(MemorySegment commandBuffer, MemorySegment colorTexture,
                          MemorySegment depthTexture, MemorySegment motionTexture,
                          MemorySegment outputTexture, float jitterX, float jitterY, boolean reset) {
        if (this.closed) return false;
        return MetalNative.fxTemporalEncode(this.handle, commandBuffer, colorTexture, depthTexture,
                motionTexture, outputTexture, jitterX, jitterY, reset) == 0;
    }

    /** The native handle, for the backend's own diagnostics. */
    public MemorySegment handle() {
        return this.handle;
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

    public boolean usesReactiveMask() {
        return this.reactiveMask;
    }

    public boolean usesJitteredMotion() {
        return this.jitteredMotion;
    }

    /** A one-line description for the F3 section and the startup log. */
    public String describe() {
        return this.inputWidth + "x" + this.inputHeight + " -> " + this.outputWidth + "x"
                + this.outputHeight + " temporal (depth " + this.depthFormat + ", motion "
                + this.motionFormat + ")";
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        MetalNative.fxTemporalRelease(this.handle);
    }
}
