package net.metalmod.metalfx;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalFormat;
import net.metalmod.backend.MetalNative;
import net.metalmod.backend.MetalTexture;
import net.metalmod.backend.MetalTextureView;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The second render target Phase 7 renders the world into, and the upscale that returns it to native.
 *
 * <p><b>Why a second target at all.</b> Minecraft renders the level and the interface into one target:
 * {@code GameRenderer.mainRenderTarget()}. Shrinking that target shrinks the interface with it - HUD
 * text, tooltips and every scissor rectangle in the game are expressed in terms of the target's own
 * dimensions - so render-resolution scaling cannot be done by resizing it. Phase 7A therefore gives the
 * level its own target at the scaled resolution, upscales <em>that</em> into the native-resolution main
 * target, and lets the interface draw afterwards at native resolution, where it has always drawn.
 *
 * <p><b>Where the swap happens.</b> {@code GameRenderer.mainRenderTarget()} answers with this target
 * while the level is rendering and with the real one at every other moment, scoped by
 * {@link #enterLevel()} / {@link #leaveLevel()}. The mixins that drive it are
 * {@code GameRendererScalingMixin} and {@code LevelRendererScalingMixin}.
 *
 * <h2>Lifecycle rules</h2>
 *
 * <p>Three rules, each learned from a crash:
 *
 * <ol>
 *   <li><b>A target is built only at a frame boundary.</b> {@link #refresh} records what the settings
 *       and the window call for; {@link #applyPending} builds it, from the head of a frame before
 *       anything imports a target. {@code LevelRenderer.render} imports the target into a frame graph
 *       at its head and the passes execute at the end of the same call, holding whatever they captured
 *       - so replacing the target in between hands a pass a buffer that no longer exists.</li>
 *   <li><b>A replaced target is never destroyed.</b> The engine's resource pool can serve the same
 *       physical target to two frame-graph descriptors of the same shape, so freeing one out from
 *       under it kills a pass that believes it still owns the buffers. Replaced targets are held and
 *       dropped by reference only.</li>
 *   <li><b>A frame decides once whether it scales, and never changes its mind.</b>
 *       {@link #decideScalingForFrame} answers before anything is imported, and
 *       {@link #worldTargetIfRenderingLevel} reads nothing else. A frame that switches halfway through
 *       is a frame whose passes were built against one target and executed against another.</li>
 * </ol>
 *
 * <p>Everything here is a no-op while the render scale is 1.0. That is the feature-off path, and it
 * stays byte-for-byte the pre-Phase-7 frame rather than "the same but through an extra blit".
 */
public final class WorldRenderTarget {

    /** 1.0 leaves the world exactly where it was: in the engine's own main target. */
    private static final double DISABLED_SCALE = 1.0;

    /** The format every render target in the engine uses, and what MetalFX is asked to scale. */
    private static final long NATIVE_FORMAT = MetalFormat.mtlPixelFormat(GpuFormat.RGBA8_UNORM);

    // ---------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------

    /** The scaled level target, or null when the world is rendered natively. */
    private static volatile RenderTarget target;

    /** The MetalFX effect for the current sizes, created on first use and reused after that. */
    private static volatile MetalFxScaler scaler;

    private static volatile double appliedScale = DISABLED_SCALE;

    /**
     * Targets replaced and no longer used.
     *
     * <p><b>Held, never destroyed.</b> A {@code RenderTarget} here has already been swapped out of the
     * redirect, so nothing renders into it again - but the engine's {@code CrossFrameResourcePool} may
     * have taken it, and two frame-graph descriptors with the same size and format are served the same
     * physical target. Destroying one out from under that pool frees buffers the engine believes it
     * still owns, and the next pass to use them dies with {@code colorTexture is null} - which is
     * exactly the crash this class kept producing one frame after a resize.
     *
     * <p>The cost of holding them is one allocation per scale or window change, released when the
     * device closes or when the collector gets to them. Two render targets of the same size and format
     * are also physically interchangeable, so the pool gets real use out of them rather than waste.
     */
    private static final List<RenderTarget> retired = new ArrayList<>();

    // What the settings and the window currently call for. Written by refresh(), read by applyPending().
    private static int requestedNativeWidth;
    private static int requestedNativeHeight;
    private static double requestedScale = DISABLED_SCALE;
    private static boolean pendingChange;

    /**
     * Ask the engine to reload its resources, the way F3+T does.
     *
     * <p>Added on evidence: toggling the render scale left the sky brighter and less saturated, and
     * pressing F3+T fixed it. A resource reload clears the engine's pipeline cache and recompiles the
     * static pipelines, so a pipeline built while the render target was a different size is replaced.
     * Whatever else a reload refreshes, that is the effect that was demonstrably needed, and asking for
     * the reload is cheaper than reasoning about which cached thing was stale.
     *
     * <p>Deferred through the engine rather than performed here: a reload touches resources the frame
     * may be using, and {@code delayTextureReload} is the engine's own way to run one at a safe point.
     * Requested once per scale change, not per frame.
     */
    private static void requestResourceReload() {
        if (reloadRequested) {
            return;
        }
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft == null) {
                return;
            }
            reloadRequested = true;
            minecraft.delayTextureReload().whenComplete((unused, error) -> {
                reloadRequested = false;
                if (error != null) {
                    System.err.println("[MetalMod] resource reload after the render-scale change failed: "
                            + error);
                } else {
                    System.out.println("[MetalMod] resource reload completed after the render-scale"
                            + " change");
                }
            });
            System.out.println("[MetalMod] render scale changed: reloading resources so nothing cached"
                    + " against the old target survives");
        } catch (Throwable t) {
            reloadRequested = false;
            System.err.println("[MetalMod] could not request a resource reload: " + t);
        }
    }

    /** Whether a reload has been asked for and not yet completed. */
    private static volatile boolean reloadRequested;

    /** Why scaling is not running, when it is configured but cannot be. Empty when all is well. */
    private static volatile String unavailableReason = "";

    /** Whether MetalFX can scale at all in this session. Decided once per frame. */
    private static volatile boolean scalerUsable;

    /**
     * Whether the frame being rendered is scaled.
     *
     * <p>The redirect's only gate, decided once at the frame boundary. See rule 3 in the class comment.
     */
    private static volatile boolean scaleThisFrame;

    /** Whether the level's own render call is in progress on this thread. */
    private static final ThreadLocal<Boolean> RENDERING_LEVEL = ThreadLocal.withInitial(() -> false);

    /**
     * Whether this frame draws a level at all.
     *
     * <p>Recorded at the head of the frame, before the engine's opening clear, because that clear is
     * split between the level's target and the engine's own and the split is only correct when a level
     * is coming.
     */
    private static final ThreadLocal<Boolean> FRAME_HAS_LEVEL = ThreadLocal.withInitial(() -> false);

    private static final AtomicLong scaledFrames = new AtomicLong();
    private static final AtomicLong failedFrames = new AtomicLong();
    private static volatile String lastUpscaleError = "";

    private WorldRenderTarget() {
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle: request, then apply at the boundary
    // ---------------------------------------------------------------------------------------------

    /**
     * Record the level target the current settings and window call for.
     *
     * <p><b>This never changes the target.</b> {@link #applyPending} builds it, and only ever runs at a
     * frame boundary. See rule 1 in the class comment for why that separation is a crash fix rather
     * than tidiness.
     */
    public static synchronized void refresh(int nativeWidth, int nativeHeight) {
        if (nativeWidth <= 0 || nativeHeight <= 0) {
            return;
        }
        requestedNativeWidth = nativeWidth;
        requestedNativeHeight = nativeHeight;
        double previousScale = requestedScale;
        requestedScale = RenderScaleSettings.renderScale();
        // The render scale setting itself changed, as opposed to a window resize. That is a change in
        // how the whole frame is produced, and the engine caches resources against it, so the reload
        // the user can trigger by hand (F3+T) is requested here instead - deferred by the engine onto
        // the main thread, so it lands between frames rather than inside one.
        if (previousScale != requestedScale) {
            requestResourceReload();
        }

        // The common case: what is built already agrees with what is asked for. This must stay free of
        // allocation - it runs every frame.
        RenderTarget current = target;
        if (current != null
                && Math.abs(requestedScale - appliedScale) < 1e-9
                && current.width == scaledWidth()
                && current.height == scaledHeight()) {
            return;
        }
        if (current == null && requestedScale == DISABLED_SCALE) {
            appliedScale = requestedScale;
            return;
        }
        pendingChange = true;
    }

    /**
     * Build whatever {@link #refresh} asked for. Called from the head of a frame, and from teardown.
     *
     * @return true when the target was replaced.
     */
    public static synchronized boolean applyPending() {
        if (!pendingChange) {
            return false;
        }
        pendingChange = false;

        RenderTarget previous = target;
        RenderTarget built = null;
        if (requestedScale != DISABLED_SCALE) {
            int width = scaledWidth();
            int height = scaledHeight();
            try {
                built = new MainTarget(width, height);
                if (built.getColorTextureView() == null || built.getDepthTextureView() == null) {
                    // A target without attachments cannot be rendered into. Keep the engine's own and
                    // report it, rather than failing later inside a pass.
                    System.err.println("[MetalMod] the scaled world target " + width + "x" + height
                            + " came back without attachments; rendering at native resolution");
                    built = null;
                }
            } catch (Throwable t) {
                System.err.println("[MetalMod] could not create the scaled world target "
                        + width + "x" + height + ": " + t);
                built = null;
            }
        }

        appliedScale = built != null ? requestedScale : DISABLED_SCALE;
        target = built;
        // The render target changed shape, so anything the engine built against the old one is now
        // stale. See the frame hook for what is done about it.
        rebuiltForCaller = true;
        // A replaced target has no history, and neither does the scaler that consumed it.
        releaseScaler();
        // Retired, never destroyed - see the field's comment. The engine's resource pool may have
        // taken this target, and it is the pool's to free.
        if (previous != null) {
            retired.add(previous);
        }
        if (built != null) {
            System.out.println("[MetalMod] render scale " + String.format(java.util.Locale.ROOT,
                    "%.2f", appliedScale) + ": world renders at " + built.width + "x" + built.height
                    + ", upscaled to " + requestedNativeWidth + "x" + requestedNativeHeight);
        } else if (previous != null) {
            System.out.println("[MetalMod] render scaling off: the world renders at native resolution");
        }
        return true;
    }

    /**
     * Decide once, at the frame boundary, whether this frame is scaled at all.
     *
     * <p>Called after {@link #applyPending} and after the scaler check, before anything is imported. A
     * window size that has moved since the target was built means the target is for the wrong size, so
     * this frame renders natively and the next boundary rebuilds. That costs one frame at native
     * resolution during a resize, which is the difference between that and a frame graph holding a
     * target whose buffers were destroyed underneath it.
     */
    public static void decideScalingForFrame() {
        RenderTarget current = target;
        boolean sizeMatches = current != null
                && current.width == scaledWidth()
                && current.height == scaledHeight();
        boolean usable = scalerUsable && current != null && sizeMatches;
        if (!usable && scaleThisFrame && RenderScaleSettings.active()) {
            String have = current == null ? "absent" : current.width + "x" + current.height;
            String want = scaledWidth() + "x" + scaledHeight();
            System.out.println("[MetalMod] rendering this frame at native resolution: the scaled target"
                    + " is " + have + " against a window that wants " + want);
        }
        scaleThisFrame = usable;
    }

    /**
     * Whether the target was rebuilt since the last frame, so dependent caches must be dropped.
     *
     * <p>Recorded rather than acted on here, because the action belongs to the device: this class owns
     * the target, the device owns the compiled pipelines, and the frame boundary is the only point at
     * which either can be changed safely.
     */
    private static volatile boolean rebuiltForCaller;

    /** Take the "the target changed" flag, clearing it. True when the caller must drop its caches. */
    public static boolean consumeRebuiltFlag() {
        boolean flag = rebuiltForCaller;
        rebuiltForCaller = false;
        return flag;
    }

    /** Record whether MetalFX can scale in this session. Called at the frame boundary. */
    public static void setScalingAvailable(boolean usable) {
        scalerUsable = usable;
        if (!usable && RenderScaleSettings.active()) {
            System.out.println("[MetalMod] render scaling requested but MetalFX cannot run here ("
                    + (unavailableReason.isEmpty() ? "no usable scaler" : unavailableReason)
                    + "); rendering at native resolution");
        }
    }

    /** Whether MetalFX can scale at all here, independent of what this frame decided. */
    public static boolean scalerAvailable() {
        return scalerUsable;
    }

    /**
     * Whether MetalFX can scale for the current configuration.
     *
     * <p>Asked at the frame boundary, before the level's frame graph is built, so a machine that cannot
     * scale renders natively rather than rendering small into a target nothing would present.
     */
    public static boolean metalFxUsable(MetalDevice device) {
        if (device == null || !RenderScaleSettings.active()) {
            return false;
        }
        if (!MetalFxScaler.isSupported(device.deviceHandle(), NATIVE_FORMAT, NATIVE_FORMAT)) {
            if (unavailableReason.isEmpty()) {
                unavailableReason = MetalFx.unavailableReason();
            }
            return false;
        }
        unavailableReason = "";
        return true;
    }

    /** Why scaling is not running, when it is configured but cannot be. Empty when all is well. */
    public static String unavailableReason() {
        return unavailableReason;
    }

    /** The engine's window resized. Only records the request - see {@link #refresh}. */
    public static synchronized void onEngineResize(int nativeWidth, int nativeHeight) {
        refresh(nativeWidth, nativeHeight);
    }

    /** The last chance to record the right size before the level's frame graph is built. */
    public static synchronized void ensureBeforeLevel(int nativeWidth, int nativeHeight) {
        refresh(nativeWidth, nativeHeight);
    }

    /** Whether a rebuild is waiting for the next frame boundary. For diagnostics and tests. */
    public static boolean hasPendingChange() {
        return pendingChange;
    }

    /**
     * Drop every reference. Called when the device is torn down.
     *
     * <p>Deliberately does not destroy: the buffers belong to whichever {@code MainTarget} owns them,
     * and the engine's resource pool may be one of the owners. Dropping the references is what this
     * class is entitled to do; freeing is not.
     */
    public static synchronized void close() {
        target = null;
        pendingChange = false;
        scaleThisFrame = false;
        releaseScaler();
        retired.clear();
    }

    private static void releaseScaler() {
        MetalFxScaler existing = scaler;
        scaler = null;
        if (existing != null) {
            existing.close();
        }
    }

    private static int scaledWidth() {
        return RenderScaleSettings.active() ? RenderScaleSettings.scaledSize(requestedNativeWidth)
                : requestedNativeWidth;
    }

    private static int scaledHeight() {
        return RenderScaleSettings.active() ? RenderScaleSettings.scaledSize(requestedNativeHeight)
                : requestedNativeHeight;
    }

    // ---------------------------------------------------------------------------------------------
    // The frame's two ends
    // ---------------------------------------------------------------------------------------------

    /** Record whether this frame will draw a level. Called at the head of the frame. */
    public static void beginFrame(boolean hasLevel) {
        FRAME_HAS_LEVEL.set(hasLevel);
    }

    /** Whether this frame will draw a level. */
    public static boolean frameHasLevel() {
        return FRAME_HAS_LEVEL.get();
    }

    /**
     * Enter the level's render call: until {@link #leaveLevel}, the engine's main render target answers
     * with the scaled one.
     */
    public static void enterLevel() {
        RENDERING_LEVEL.set(true);
    }

    /** Leave the level's render call. */
    public static void leaveLevel() {
        RENDERING_LEVEL.set(false);
    }

    /** Whether the level's own render call is in progress on this thread. */
    public static boolean renderingLevel() {
        return RENDERING_LEVEL.get();
    }

    /** The target the level renders into, or null when scaling is off. */
    public static RenderTarget worldTarget() {
        return target;
    }

    /**
     * The target the engine should answer with right now: the scaled one during a scaled frame's level
     * pass, otherwise null, meaning "keep your own".
     *
     * <p>A null answer is not an error and must not be treated as one - it is the feature-off path,
     * the interface path and the resize frame, all of which are normal.
     */
    public static RenderTarget worldTargetIfRenderingLevel() {
        if (!RENDERING_LEVEL.get() || !scaleThisFrame) {
            return null;
        }
        return target;
    }

    /** Whether the world is being rendered at a scaled resolution this frame. */
    public static boolean active() {
        return target != null && scaleThisFrame;
    }

    /** Whether this frame is scaled. */
    public static boolean scalingThisFrame() {
        return scaleThisFrame;
    }

    /** Whether the redirect is honoured this frame. The name the diagnostics use. */
    public static boolean scalingAvailable() {
        return scaleThisFrame;
    }

    // ---------------------------------------------------------------------------------------------
    // The upscale
    // ---------------------------------------------------------------------------------------------

    /**
     * Return the level's low-resolution colour to the native-resolution main target.
     *
     * <p>Runs once per frame, between the level and the interface. The colour is the only thing scaled:
     * the interface draws over it in the main target afterwards, and the main target's own depth buffer
     * is the interface's.
     *
     * <p><b>MetalFX or native, and nothing in between.</b> There is deliberately no linear-blit
     * fallback. One existed - a straight texture copy from the small target to the large one, for the
     * case where MetalFX is unavailable or switched off - and it was removed: it was a second upscaling
     * path that had to be correct in its own right, it measured several times slower than the effect it
     * stood in for, and it produced visibly wrong frames. A fallback that is both slower and less
     * correct than the thing it falls back to is not a safety net.
     *
     * <p>When MetalFX cannot run, the world is not scaled at all: the redirect stays off, the level
     * renders at native resolution, and the frame costs what it cost before Phase 7.
     */
    public static boolean upscale(RenderTarget main) {
        RenderTarget world = target;
        if (world == null || main == null) {
            return false;
        }
        MetalDevice device = MetalDevice.active();
        if (device == null) {
            lastUpscaleError = "no active Metal device";
            failedFrames.incrementAndGet();
            return false;
        }
        if (world.width == main.width && world.height == main.height) {
            // The scale rounds back to native, so there is nothing for an upscaler to do. This cannot
            // normally happen - a scale of 1.0 allocates no target - but a tiny window can land here,
            // and the right answer is to leave the main target alone rather than copy over it.
            lastUpscaleError = "the scaled target matches the native size; nothing to upscale";
            return false;
        }

        MetalFxScaler effect = ensureScaler(device, world, main.width, main.height);
        if (effect == null) {
            lastUpscaleError = MetalFx.unavailableReason();
            failedFrames.incrementAndGet();
            return false;
        }

        MemorySegment source = textureHandle(world.getColorTextureView());
        MemorySegment destination = textureHandle(main.getColorTextureView());
        if (source.address() == 0 || destination.address() == 0) {
            lastUpscaleError = "a colour view had no Metal texture behind it";
            failedFrames.incrementAndGet();
            return false;
        }

        // One FFI call that creates its own command buffer, encodes and commits on the device queue:
        // the frame's own encoders are already submitted by now, so the upscale lands behind them in
        // commit order without this side having to reach into the engine's encoder.
        boolean ok = false;
        try {
            ok = effect.run(device.queueHandle(), source, destination) == 0;
            if (!ok) {
                lastUpscaleError = "MetalFX refused the upscale";
            }
        } catch (Throwable t) {
            lastUpscaleError = String.valueOf(t);
        }
        if (ok) {
            lastUpscaleError = "";
            scaledFrames.incrementAndGet();
        } else {
            failedFrames.incrementAndGet();
        }
        return ok;
    }

    /**
     * MetalFX's spatial scaler for the current sizes, created on first use and reused after that.
     *
     * <p>Null means "no scaler", which is not fatal here: the caller records why, and the frame renders
     * natively rather than presenting something unscaled.
     */
    private static MetalFxScaler ensureScaler(MetalDevice device, RenderTarget world, int outputWidth,
                                              int outputHeight) {
        MetalFxScaler existing = scaler;
        if (existing != null && existing.matches(NATIVE_FORMAT, NATIVE_FORMAT, world.width, world.height,
                outputWidth, outputHeight)) {
            return existing;
        }
        if (existing != null) {
            existing.close();
            scaler = null;
        }
        if (!MetalFxScaler.isSupported(device.deviceHandle(), NATIVE_FORMAT, NATIVE_FORMAT)) {
            MetalFx.setUnavailableReason("this device cannot scale "
                    + world.getColorTexture().getFormat() + " to " + GpuFormat.RGBA8_UNORM);
            return null;
        }
        MetalFxScaler created = MetalFxScaler.create(device.deviceHandle(), NATIVE_FORMAT, NATIVE_FORMAT,
                world.width, world.height, outputWidth, outputHeight, 0);
        scaler = created;
        if (created != null) {
            System.out.println("[MetalMod] MetalFX spatial scaler: " + created.describe());
        }
        return created;
    }

    private static MemorySegment textureHandle(GpuTextureView view) {
        return view instanceof MetalTextureView metal ? metal.handle() : MemorySegment.NULL;
    }

    // ---------------------------------------------------------------------------------------------
    // Offline measurement
    // ---------------------------------------------------------------------------------------------

    /**
     * Time the upscale with the GPU synchronised, for the offline cost check.
     *
     * <p>Returns {@code {meanMs, worstMs}}, or null when the effect could not be built. Each iteration
     * is fenced, so the number is execution rather than submission - the whole reason this exists is
     * that an in-game frame rate paced by the display cannot tell a scaler that costs a millisecond
     * from one that costs a frame.
     */
    public static double[] timeUpscale(MetalDevice device, RenderTarget world, RenderTarget main,
                                       int iterations) {
        MetalFxScaler effect = ensureScaler(device, world, main.width, main.height);
        if (effect == null) {
            return null;
        }
        MemorySegment source = textureHandle(world.getColorTextureView());
        MemorySegment destination = textureHandle(main.getColorTextureView());
        if (source.address() == 0 || destination.address() == 0) {
            return null;
        }
        double total = 0.0;
        double worst = 0.0;
        for (int i = 0; i < iterations; i++) {
            MemorySegment fence = MetalNative.fenceCreate(device.queueHandle());
            long started = System.nanoTime();
            effect.run(device.queueHandle(), source, destination);
            MetalNative.fenceWait(fence, Long.MAX_VALUE);
            double millis = (System.nanoTime() - started) / 1_000_000.0;
            MetalNative.fenceRelease(fence);
            total += millis;
            worst = Math.max(worst, millis);
        }
        return new double[]{total / iterations, worst};
    }

    /**
     * Time clearing a target, as a stand-in for "what does touching this many pixels cost".
     *
     * <p>A clear is the cheapest full-target write there is, so it measures the memory path rather than
     * any shading. That is what makes it useful here: if a fraction of the pixels does not cost
     * meaningfully less to write, the frame is not pixel-bound and render scaling has nothing to save.
     */
    public static double timeClear(MetalDevice device, RenderTarget target, int iterations) {
        MemorySegment color = target.getColorTexture() instanceof MetalTexture metal
                ? metal.handle() : MemorySegment.NULL;
        if (color.address() == 0) {
            return Double.NaN;
        }
        double total = 0.0;
        for (int i = 0; i < iterations; i++) {
            MemorySegment fence = MetalNative.fenceCreate(device.queueHandle());
            long started = System.nanoTime();
            MetalNative.clearTextures(device.queueHandle(), color, true, 0.1f, 0.2f, 0.3f, 1.0f,
                    null, false, 0.0);
            MetalNative.fenceWait(fence, Long.MAX_VALUE);
            total += (System.nanoTime() - started) / 1_000_000.0;
            MetalNative.fenceRelease(fence);
        }
        return total / iterations;
    }

    // ---------------------------------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------------------------------

    /**
     * How long this configuration's upscale takes on the GPU, in milliseconds, or negative when it
     * could not be measured.
     *
     * <p>Kept here because the scaler belongs to this class: the probe asks for a number, not for the
     * handle.
     */
    public static double gpuTimeUpscale(MetalDevice device, RenderTarget world, RenderTarget main,
                                        int passes) {
        MetalFxScaler effect = ensureScaler(device, world, main.width, main.height);
        if (effect == null) {
            return -1.0;
        }
        return MetalNative.gpuTimeUpscale(effect.handle(), device.queueHandle(),
                textureHandle(world.getColorTextureView()),
                textureHandle(main.getColorTextureView()), passes);
    }

    /**
     * Mean colour of both targets over the same part of the picture, for BUG-029.
     *
     * <p>Returns {@code {worldR, worldG, worldB, mainR, mainG, mainB}} over the top strip of each, or
     * null when there is nothing scaled to compare. The strip is the sky in any normal view.
     *
     * <p>This is the measurement that splits the remaining possibilities: if the two agree, the
     * difference the in-game sampler sees is introduced by the upscale; if they already disagree, the
     * scaled pass renders the sky differently and the cause is upstream of the effect.
     */
    public static double[] compareSkyStrip(int nativeWidth, int nativeHeight, RenderTarget main) {
        RenderTarget world = target;
        MetalDevice device = MetalDevice.active();
        if (world == null || main == null || device == null) {
            return null;
        }
        double[] fromWorld = stripMean(device, world.getColorTexture());
        double[] fromMain = stripMean(device, main.getColorTexture());
        if (fromWorld == null || fromMain == null) {
            return null;
        }
        return new double[]{fromWorld[0], fromWorld[1], fromWorld[2],
                fromMain[0], fromMain[1], fromMain[2]};
    }

    /**
     * Mean colour of the top tenth of a texture, sampled on a coarse grid.
     *
     * <p>Sampled rather than read whole because the point is a mean over a large area, and a large
     * target copied whole is tens of megabytes per call for a number that a few thousand samples
     * already pin down.
     */
    private static double[] stripMean(MetalDevice device, com.mojang.blaze3d.textures.GpuTexture texture) {
        if (!(texture instanceof MetalTexture metal) || !metal.isValid()) {
            return null;
        }
        int width = texture.getWidth(0);
        int height = Math.max(1, texture.getHeight(0) / 10);
        var readback = device.createBuffer(() -> "sky strip",
                com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ
                        | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST,
                (long) width * height * 4);
        try {
            var encoder = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToBuffer(texture, readback, 0L, null, 0, 0, 0, width, height);
            encoder.submit();
            java.nio.ByteBuffer px = ((net.metalmod.backend.MetalBuffer) readback).data()
                    .asByteBuffer().order(java.nio.ByteOrder.nativeOrder());
            double r = 0, g = 0, b = 0;
            long n = 0;
            for (int y = 0; y < height; y += 8) {
                for (int x = 0; x < width; x += 8) {
                    int at = (y * width + x) * 4;
                    r += px.get(at) & 0xFF;
                    g += px.get(at + 1) & 0xFF;
                    b += px.get(at + 2) & 0xFF;
                    n++;
                }
            }
            return n == 0 ? null : new double[]{r / n, g / n, b / n};
        } catch (Throwable t) {
            return null;
        } finally {
            readback.close();
        }
    }

    /** The Metal texture behind the scaled colour view, for diagnostics and tests. */
    public static MetalTexture colorTexture() {
        RenderTarget world = target;
        if (world == null) return null;
        return world.getColorTexture() instanceof MetalTexture metal ? metal : null;
    }

    public static long scaledFrameCount() {
        return scaledFrames.get();
    }

    public static long failedFrameCount() {
        return failedFrames.get();
    }

    public static String lastUpscaleError() {
        return lastUpscaleError;
    }

    /**
     * One line describing the resolution and the path, for the periodic frame-rate log.
     *
     * <p>Deliberately compact: it is appended to a line that already carries the rate and the drawable
     * wait, and its whole job is to say which configuration produced those numbers.
     */
    public static String describeForLog() {
        RenderTarget world = target;
        if (world == null) {
            return RenderScaleSettings.active() && !scaleThisFrame
                    ? "native resolution this frame (scaled target not usable)"
                    : "native resolution, no scaling";
        }
        return RenderScaleSettings.percentLabel() + " " + world.width + "x" + world.height
                + " -> native, MetalFX spatial (upscaled " + scaledFrames.get() + ")";
    }

    /** The scaled world size, or the native size when scaling is off. For the settings page. */
    public static String describe(int nativeWidth, int nativeHeight) {
        RenderTarget world = target;
        if (world == null) {
            return nativeWidth + "x" + nativeHeight + " (native)";
        }
        return world.width + "x" + world.height + " -> " + nativeWidth + "x" + nativeHeight
                + " MetalFX spatial";
    }

    /**
     * What the next frame will do, for the settings screen to report before it happens.
     *
     * <p>A prediction, and labelled as one: the live target answers what the frame is using *now*, and
     * the difference between the two is exactly what a settings change is.
     */
    public static int[] pendingSize(int nativeWidth, int nativeHeight) {
        if (nativeWidth <= 0 || nativeHeight <= 0 || !RenderScaleSettings.active()) {
            return null;
        }
        return new int[]{RenderScaleSettings.scaledSize(nativeWidth),
                RenderScaleSettings.scaledSize(nativeHeight)};
    }

    /** The live target's own size, or null when nothing is scaled. For the settings page. */
    public static int[] effectiveSize() {
        RenderTarget world = target;
        if (world == null) {
            return null;
        }
        return new int[]{world.width, world.height};
    }
}
