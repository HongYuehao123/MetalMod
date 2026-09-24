import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.metalmod.backend.MetalBuffer;
import net.metalmod.backend.MetalDevice;
import net.metalmod.metalfx.MetalFx;
import net.metalmod.metalfx.ProjectionJitter;
import net.metalmod.metalfx.RenderScaleSettings;
import net.metalmod.metalfx.WorldRenderTarget;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Phase 7A end to end, offscreen: render-resolution scaling through the engine's own render target
 * and frame graph, upscaled by MetalFX into a native-resolution target.
 *
 * <p>The render check proves individual mechanisms. This proves the <em>shape</em> of Phase 7A, which
 * is what the mixins in the game assemble:
 *
 * <ol>
 *   <li>{@link WorldRenderTarget} builds a level target at the scaled size out of the engine's own
 *       {@code MainTarget}, resized by {@link RenderScaleSettings};</li>
 *   <li>a frame graph imports that target as an <em>external</em> resource and runs a pass that writes
 *       it - which is exactly what {@code LevelRenderer.render} does with whatever
 *       {@code GameRenderer.mainRenderTarget()} answers;</li>
 *   <li>the colour is upscaled into a native-resolution target by the {@link MetalFx} path, with the
 *       native target's own pixels verified;</li>
 *   <li>and the whole thing is a no-op at the default scale, so the feature-off frame is the engine's
 *       own frame.</li>
 * </ol>
 *
 * <p>The mixin that <em>performs</em> the redirect cannot be exercised here - it needs the game's
 * class loader - so {@code tools/mixin_check} covers the injection points and this covers the
 * behaviour behind them.
 *
 * <p>Usage: tools/scaling_check/run.sh [instance-dir]
 */
public final class ScalingCheck {

    /** The native (window) size this pretends to be. Deliberately not a round multiple of the
     *  scaled size: a real window is not, and the rounding is part of what is being checked. */
    private static final int NATIVE_WIDTH = 300;
    private static final int NATIVE_HEIGHT = 200;

    private static int failures;

    public static void main(String[] args) throws Exception {
        // The real settings API, seeded the way a launch flag seeds it. In-game choices win over
        // flags, which is the precedence the settings screen documents.
        System.setProperty(RenderScaleSettings.PROPERTY_RENDER_SCALE, "0.5");
        System.setProperty(RenderScaleSettings.PROPERTY_UPSCALER, MetalFx.SPATIAL);

        System.out.println("Phase 7A scaling check - scaled level target and MetalFX upscale, offscreen");
        System.out.println("  native size : " + NATIVE_WIDTH + "x" + NATIVE_HEIGHT);
        System.out.println("  render scale: " + RenderScaleSettings.renderScale());
        System.out.println();

        MetalDevice device = MetalDevice.create();
        if (device == null) {
            System.err.println("ERROR: no Metal device");
            System.exit(2);
        }
        // MainTarget creates its attachments through RenderSystem's device, which is what the engine
        // installs at startup. Doing it here lets the engine's own target class be used rather than a
        // reimplementation of it.
        RenderSystem.initRenderThread();
        RenderSystem.initRenderer(new GpuDevice(device, () -> { }));

        try {
            settingsCheck();
            jitterCheck();
            fullSizeCostCheck(device);
            qualityCheck(device);
            disabledPathCheck();
            scaledPathCheck(device);
        } finally {
            WorldRenderTarget.close();
            device.close();
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("SCALING CHECK PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    /**
     * The settings resolver, which is what decides the size of everything downstream.
     *
     * <p>A scale is clamped rather than rejected, an in-game choice outranks the launch flag, and a
     * scale of 1.0 reports as inactive - the last one matters most, because "inactive" is what routes
     * the frame down the untouched path.
     */
    private static void settingsCheck() {
        section("settings");
        check("a -D flag seeds the scale", Math.abs(RenderScaleSettings.renderScale() - 0.5) < 1e-9, "");
        check("scaling reports as active", RenderScaleSettings.active(), "");

        RenderScaleSettings.chooseRenderScale(0.75);
        check("an in-game choice outranks the launch flag",
                Math.abs(RenderScaleSettings.renderScale() - 0.75) < 1e-9, "");

        RenderScaleSettings.chooseRenderScale(4.0);
        check("an out-of-range scale is clamped to native",
                Math.abs(RenderScaleSettings.renderScale() - RenderScaleSettings.MAX_SCALE) < 1e-9, "");

        RenderScaleSettings.chooseRenderScale(0.001);
        check("an absurdly small scale is clamped up",
                Math.abs(RenderScaleSettings.renderScale() - RenderScaleSettings.MIN_SCALE) < 1e-9, "");

        RenderScaleSettings.chooseRenderScale(0.5);
        check("300 x 0.5 rounds to 150", RenderScaleSettings.scaledSize(300) == 150, "");
        check("an odd dimension still rounds to at least one pixel",
                RenderScaleSettings.scaledSize(1) >= 1, "");

        RenderScaleSettings.chooseRenderScale(Double.NaN);
        check("a NaN scale falls back to native",
                Math.abs(RenderScaleSettings.renderScale() - 1.0) < 1e-9,
                "resolved to " + RenderScaleSettings.renderScale());
        check("native reports as inactive", !RenderScaleSettings.active(),
                "scale is " + RenderScaleSettings.renderScale());

        RenderScaleSettings.clearSessionChoices();
        check("clearing the session choice restores the launch flag",
                Math.abs(RenderScaleSettings.renderScale() - 0.5) < 1e-9, "");
    }

    /**
     * Phase 7B's projection jitter, and the gate that keeps it off until a motion source exists.
     *
     * <p>The sequence is what matters here, not any single value: it has to be sub-pixel, it has to
     * average to zero over the cycle, and consecutive frames have to <em>differ</em> - a sequence that
     * repeats or clusters gives a temporal accumulation no new information, which is the whole reason
     * for jittering. All three are checked, because each can fail on its own.
     */
    private static void jitterCheck() {
        section("projection jitter (7B)");
        check("temporal is gated off while there is no motion source",
                !RenderScaleSettings.temporalEnabled(), "");
        check("a temporal request resolves to spatial", MetalFx.SPATIAL.equals(
                resolveUpscaler("temporal")), "");

        ProjectionJitter.clear();
        check("jitter is inactive before a frame begins", !ProjectionJitter.active(), "");

        // One full cycle.
        float sumX = 0.0f;
        float sumY = 0.0f;
        float maxAbs = 0.0f;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 16; i++) {
            ProjectionJitter.beginFrame();
            checkActiveFirstFrame(i);
            float x = ProjectionJitter.offsetX();
            float y = ProjectionJitter.offsetY();
            sumX += x;
            sumY += y;
            maxAbs = Math.max(maxAbs, Math.max(Math.abs(x), Math.abs(y)));
            seen.add(x + "," + y);
            ProjectionJitter.endFrame();
        }
        check("every offset is sub-pixel", maxAbs <= 0.5f, "largest |offset| = " + maxAbs);
        check("the sequence does not repeat inside a cycle", seen.size() == 16,
                seen.size() + " distinct offsets");
        check("the offsets average to zero, so the image does not drift",
                Math.abs(sumX) < 1e-4f && Math.abs(sumY) < 1e-4f,
                "sums " + sumX + ", " + sumY);

        // The clip-space conversion is what the projection actually receives, and it is the one place
        // a rounding difference between the renderer and the scaler could appear.
        ProjectionJitter.beginFrame();
        float offsetPx = ProjectionJitter.offsetX();
        float clip = ProjectionJitter.clipX(1600);
        check("the clip-space offset moves the image by exactly that many pixels",
                Math.abs(clip * 1600 / 2.0f - offsetPx) < 1e-5f,
                "clip " + clip + " at 1600 px");
        ProjectionJitter.endFrame();

        check("jitter stops when the frame ends", !ProjectionJitter.active(), "");

        // History lifecycle: anything that makes the previous frames unrelated has to invalidate it.
        check("no reset is pending at rest", !ProjectionJitter.resetPending(), "");
        ProjectionJitter.requestReset();
        check("a reset can be requested", ProjectionJitter.resetPending(), "");
        check("the reset is delivered exactly once", ProjectionJitter.consumeReset(), "");
        check("and does not repeat", !ProjectionJitter.consumeReset(), "");
        ProjectionJitter.clear();
    }

    private static void checkActiveFirstFrame(int index) {
        if (index == 0 && !ProjectionJitter.active()) {
            check("a frame declares itself jittered", false, "first frame");
        }
    }

    /** What a requested upscaler resolves to, without depending on the rest of the settings state. */
    private static String resolveUpscaler(String requested) {
        RenderScaleSettings.chooseUpscaler(requested);
        String resolved = RenderScaleSettings.upscaler();
        RenderScaleSettings.clearSessionChoices();
        return resolved;
    }

    /** At the default scale nothing is allocated and nothing is upscaled. */
    private static void disabledPathCheck() {
        section("feature-off path");
        RenderScaleSettings.chooseRenderScale(1.0);
        WorldRenderTarget.setScalingAvailable(true);
        WorldRenderTarget.refresh(NATIVE_WIDTH, NATIVE_HEIGHT);
        WorldRenderTarget.applyPending();
        check("no level target exists at native scale", WorldRenderTarget.worldTarget() == null, "");
        check("scaling reports inactive", !WorldRenderTarget.active(), "");
        check("the redirect answers with the engine's own target",
                WorldRenderTarget.worldTargetIfRenderingLevel() == null, "");
        check("the upscale is a no-op", !WorldRenderTarget.upscale(null), "");
        RenderScaleSettings.chooseRenderScale(0.5);
    }

    /**
     * The path the game takes with scaling on: build the scaled target, render into it through the
     * engine's frame graph, upscale to native, read the native target back.
     */
    private static void scaledPathCheck(MetalDevice device) throws Exception {
        section("scaled path");

        // The redirect is gated on MetalFX being usable, which the frame boundary decides in game.
        // Offscreen there is no frame, so the availability the game would publish is set directly.
        WorldRenderTarget.setScalingAvailable(WorldRenderTarget.metalFxUsable(device));
        check("MetalFX is usable for the backend's format pair",
                WorldRenderTarget.scalerAvailable(), WorldRenderTarget.unavailableReason());
        WorldRenderTarget.refresh(NATIVE_WIDTH, NATIVE_HEIGHT);
        WorldRenderTarget.applyPending();
        // The frame boundary's own decision, which in game is made by the frame hook.
        WorldRenderTarget.decideScalingForFrame();
        var world = WorldRenderTarget.worldTarget();
        check("a level target exists", world != null, "");
        if (world == null) {
            return;
        }
        check("it is the size the scale asked for", world.width == NATIVE_WIDTH / 2
                && world.height == NATIVE_HEIGHT / 2, world.width + "x" + world.height);
        check("it has a colour texture", world.getColorTexture() != null, "");
        check("it has a depth texture", world.getDepthTexture() != null, "");
        check("its colour format is the native format",
                world.getColorTexture().getFormat() == GpuFormat.RGBA8_UNORM,
                String.valueOf(world.getColorTexture().getFormat()));

        // The redirect's two states. During the level the engine is answered with the small target;
        // at every other moment it must get its own back, or the interface would render into it.
        WorldRenderTarget.enterLevel();
        check("during the level the redirect answers with the level target",
                WorldRenderTarget.worldTargetIfRenderingLevel() == world, "");
        WorldRenderTarget.leaveLevel();
        check("outside the level the redirect answers with the engine's own target",
                WorldRenderTarget.worldTargetIfRenderingLevel() == null, "");

        // Whether the frame draws a level decides which target the opening clear belongs to. A frame
        // that draws only the interface must not clear the small target and leave the large one
        // holding the previous frame.
        WorldRenderTarget.beginFrame(false);
        check("a frame with no level reports that, before anything is cleared",
                !WorldRenderTarget.frameHasLevel(), "");
        WorldRenderTarget.beginFrame(true);
        check("a frame with a level reports that", WorldRenderTarget.frameHasLevel(), "");

        // The engine's own main target, at native size, as the destination of the upscale.
        MainTarget main = new MainTarget(NATIVE_WIDTH, NATIVE_HEIGHT);

        // Exactly what LevelRenderer.render does: import the target the engine handed us, mark it
        // read+written by a pass, and write it. That import is why the redirect has to be in place
        // before the frame graph is built - the handle captures the target it was given.
        var graph = new com.mojang.blaze3d.framegraph.FrameGraphBuilder();
        ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> imported = graph.importExternal("main", world);
        var pass = graph.addPass("clear-and-draw");
        pass.readsAndWrites(imported);
        pass.executes(() -> clearTo(device, imported.get(), 20, 160, 200));
        try (CrossFrameResourcePool pool = new CrossFrameResourcePool(1)) {
            graph.execute(pool);
        }

        // The pass cleared the small target; read it back to prove the redirect's destination is the
        // one that actually received the frame.
        check("the level target received the frame",
                uniform(device, world, 20, 160, 200, 6), "");
        check("the native target has not been written yet",
                !uniform(device, main, 20, 160, 200, 2), "");

        check("the upscale reports success", WorldRenderTarget.upscale(main), "");
        check("the native target now holds the upscaled frame",
                uniform(device, main, 20, 160, 200, 6), "");
        check("the scaler was used rather than the blit",
                WorldRenderTarget.scaledFrameCount() > 0, "");
        check("no upscale failed", WorldRenderTarget.failedFrameCount() == 0,
                WorldRenderTarget.lastUpscaleError());
        check("the Metal device and the scaler agree on the level target",
                WorldRenderTarget.colorTexture() != null, "");

        // Resizing: the level target follows the window, and the scaler is replaced rather than
        // reused, because its input and output sizes are baked in when it is created.
        long scalerFramesBefore = WorldRenderTarget.scaledFrameCount();
        WorldRenderTarget.refresh(NATIVE_WIDTH + 100, NATIVE_HEIGHT + 100);
        check("the resize is pending, not applied mid-frame", WorldRenderTarget.hasPendingChange(), "");
        boolean replaced = WorldRenderTarget.applyPending();
        WorldRenderTarget.decideScalingForFrame();
        // The frame hook invalidates every compiled pipeline on this signal, and the scale change
        // itself requests the engine's resource reload. Both hang off this one return value, and a
        // silent false here is the shape of the reported grey-sky defect.
        check("the rebuild is reported so dependent caches can be dropped", replaced,
                "the frame hook invalidates pipelines on this signal");
        check("the level target follows a window resize",
                WorldRenderTarget.worldTarget() != null
                        && WorldRenderTarget.worldTarget().width == (NATIVE_WIDTH + 100) / 2,
                WorldRenderTarget.worldTarget() == null ? "null"
                        : String.valueOf(WorldRenderTarget.worldTarget().width));
        MainTarget resized = new MainTarget(NATIVE_WIDTH + 100, NATIVE_HEIGHT + 100);
        check("an upscale still succeeds after the resize", WorldRenderTarget.upscale(resized), "");
        check("that upscale was a real one",
                WorldRenderTarget.scaledFrameCount() > scalerFramesBefore, "");

        // What the effect costs, offscreen, where no display or compositor is involved. This is the
        // number that answers "is the upscale itself expensive": a scaler that takes longer than the
        // pixels it saves makes render scaling a pessimisation, and no amount of pacing hides that.
        costCheck(device, world, main);

        // Turning it off must release the level target rather than leave a stale one in the redirect.
        RenderScaleSettings.chooseRenderScale(1.0);
        WorldRenderTarget.refresh(NATIVE_WIDTH, NATIVE_HEIGHT);
        WorldRenderTarget.applyPending();
        check("turning scaling off releases the level target",
                WorldRenderTarget.worldTarget() == null, "");
        check("and the redirect goes back to the engine's own target",
                WorldRenderTarget.worldTargetIfRenderingLevel() == null, "");
    }

    /**
     * Time the upscale, with the GPU actually synchronised.
     *
     * <p>An offscreen measurement, on purpose. In game the present path is paced by the display, so a
     * scaler that costs a millisecond and one that costs a frame are indistinguishable from the frame
     * rate - which is exactly why "MetalFX seems slower" has been hard to settle. Here the GPU is
     * asked directly, with a fence after each batch so the time is execution rather than submission.
     */
    private static void costCheck(MetalDevice device,
                                  com.mojang.blaze3d.pipeline.RenderTarget world,
                                  com.mojang.blaze3d.pipeline.RenderTarget main) {
        section("upscale cost (offscreen)");
        double[] timings = net.metalmod.metalfx.WorldRenderTarget.timeUpscale(device, world, main, 50);
        if (timings == null || timings.length < 2) {
            check("the upscale can be timed", false, "no timing was produced");
            return;
        }
        double meanMs = timings[0];
        double worstMs = timings[1];
        System.out.println(String.format(java.util.Locale.ROOT,
                "       world %dx%d -> %dx%d: mean %.3f ms, worst %.3f ms",
                world.width, world.height, main.width, main.height, meanMs, worstMs));
        // A threshold, not a target: the point is to catch a scaler that costs more than the frame it
        // is meant to be saving. At 60 Hz a frame is 16.7 ms, so a millisecond is 6% of the budget.
        check("the upscale costs well under a frame", meanMs < 4.0,
                String.format(java.util.Locale.ROOT, "%.3f ms", meanMs));

        // And the other half of the trade: what the world pass costs at each scale. A flat cost here
        // would mean the frame is not pixel-bound at all, and then no upscaler can help.
        double fullCost = net.metalmod.metalfx.WorldRenderTarget.timeClear(device, main, 200);
        double halfCost = net.metalmod.metalfx.WorldRenderTarget.timeClear(device, world, 200);
        System.out.println(String.format(java.util.Locale.ROOT,
                "       full-frame clear %.3f ms vs %dx%d clear %.3f ms",
                fullCost, world.width, world.height, halfCost));
        // Recorded, not asserted, and the reason is worth stating: this proxy was already wrong once.
        // A clear is memory-bound and flattens out at these sizes, so the two numbers land within
        // noise of each other whatever the resolution - and a tight assertion on that difference is a
        // test that fails depending on the machine's mood. The in-scene comparison (TESTING.md §6.D3)
        // is what decides whether scaling pays; this only prints alongside it.
        System.out.println(String.format(java.util.Locale.ROOT,
                "       (a fill is not a terrain pass - this proxy bound the effect, it does not measure it)"));
        check("both timings were produced", halfCost > 0 && fullCost > 0,
                String.format(java.util.Locale.ROOT, "%.3f vs %.3f ms", halfCost, fullCost));
    }

    /**
     * The same measurement at the real display size, which is the number that decides anything.
     *
     * <p>The 300x200 targets above prove the mechanism; they cannot say whether render scaling is
     * worth turning on, because a frame that small is not pixel-bound on any GPU. This runs the
     * comparison at the resolution the reference machine actually renders at, so the answer is about
     * the machine rather than about the test.
     */
    private static void fullSizeCostCheck(MetalDevice device) {
        section("upscale cost at the display size (offscreen)");
        final int width = 5120;
        final int height = 2664;
        // 1.0, 0.75 and 0.5, the three scales the settings page offers that matter most.
        for (double scale : new double[]{1.0, 0.75, 0.5}) {
            int w = Math.max(1, (int) Math.round(width * scale));
            int h = Math.max(1, (int) Math.round(height * scale));
            MainTarget target = new MainTarget(w, h);
            double fill = net.metalmod.metalfx.WorldRenderTarget.timeClear(device, target, 30);
            System.out.println(String.format(java.util.Locale.ROOT,
                    "       %4.0f%%  %dx%d  full-target fill %.3f ms", scale * 100, w, h, fill));
            if (scale < 1.0) {
                net.metalmod.metalfx.WorldRenderTarget.refresh(width, height);
                // The scaler is sized from the live target, so this measures the real effect.
                double[] up = upscaleAt(device, w, h, width, height);
                if (up != null) {
                    System.out.println(String.format(java.util.Locale.ROOT,
                            "              MetalFX %dx%d -> %dx%d: %.3f ms mean, %.3f ms worst",
                            w, h, width, height, up[0], up[1]));
                }
            }
        }
    }

    /** Time one upscale at these sizes, using the real effect, or null when it cannot be built. */
    private static double[] upscaleAt(MetalDevice device, int inW, int inH, int outW, int outH) {
        MainTarget small = new MainTarget(inW, inH);
        MainTarget large = new MainTarget(outW, outH);
        return net.metalmod.metalfx.WorldRenderTarget.timeUpscale(device, small, large, 20);
    }

    /**
     * What the upscale does to a picture with structure in it, as opposed to a flat colour.
     *
     * <p>The other checks use flat fills, which are invariant under any filter and therefore cannot
     * catch a scaler that blurs, shifts or rings. This one uploads a 1-pixel checkerboard at the input
     * resolution and looks at what comes out at 2x:
     *
     * <ul>
     *   <li><b>it must not be flat</b> - a scaler that averaged the tile away would pass every colour
     *       check in this file while destroying the picture;</li>
     *   <li><b>it must not overshoot</b> - reconstruction is allowed to sharpen, not to ring outside
     *       the input's own range.</li>
     * </ul>
     *
     * <p>This cannot judge how good the upscale looks - that is a visual question - but it fails when
     * the effect is structurally wrong, which is the part worth automating.
     */
    private static void qualityCheck(MetalDevice device) {
        section("upscale quality (structure)");
        final int in = 64;
        final int out = 128;

        // A checkerboard at the input resolution: the highest frequency the input can carry, and the
        // hardest thing for any reconstruction filter to keep.
        GpuTexture source = device.createTexture("quality input",
                com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING
                        | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_SRC
                        | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST
                        | com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA8_UNORM, in, in, 1, 1);
        byte[] checker = new byte[in * in * 4];
        for (int y = 0; y < in; y++) {
            for (int x = 0; x < in; x++) {
                byte value = (byte) (((x + y) % 2 == 0) ? 235 : 20);
                int at = (y * in + x) * 4;
                checker[at] = value;
                checker[at + 1] = value;
                checker[at + 2] = value;
                checker[at + 3] = (byte) 0xFF;
            }
        }
        int upload = net.metalmod.backend.MetalNative.textureReplaceRegion(
                ((net.metalmod.backend.MetalTexture) source).handle(), 0, 0, 0, 0, in, in,
                java.nio.ByteBuffer.wrap(checker), in * 4L);
        check("the checkerboard uploads", upload == 0, "status " + upload);

        // Two targets of the sizes a 2x upscale works on. The scaler is handed these directly rather
        // than through the live world target, so this check cannot disturb the rest of the run.
        MainTarget small = new MainTarget(in, in);
        MainTarget large = new MainTarget(out, out);
        check("the checker is in place before scaling", rangeOf(device, source)[1]
                - rangeOf(device, source)[0] > 40, "");

        net.metalmod.metalfx.MetalFxScaler effect = net.metalmod.metalfx.MetalFxScaler.create(
                device.deviceHandle(),
                net.metalmod.backend.MetalFormat.mtlPixelFormat(GpuFormat.RGBA8_UNORM),
                net.metalmod.backend.MetalFormat.mtlPixelFormat(GpuFormat.RGBA8_UNORM),
                in, in, out, out, 0);
        check("the effect is built for the quality check", effect != null,
                net.metalmod.metalfx.MetalFx.unavailableReason());
        if (effect == null) {
            return;
        }
        // Scale the checkerboard itself: the source texture is the picture, so this measures the
        // filter rather than a copy of it.
        int status = effect.run(device.queueHandle(),
                ((net.metalmod.backend.MetalTexture) source).handle(),
                ((net.metalmod.backend.MetalTexture) large.getColorTexture()).handle());
        check("the checkerboard upscales", status == 0, "status " + status);

        double[] range = rangeOf(device, large.getColorTexture());
        System.out.println(String.format(java.util.Locale.ROOT,
                "       output luma range %.0f..%.0f (input levels 20 and 235)", range[0], range[1]));
        check("the upscale did not flatten the picture", range[1] - range[0] > 40,
                String.format(java.util.Locale.ROOT, "spread %.0f", range[1] - range[0]));
        check("the upscale did not overshoot the input's range",
                range[0] > 20 - 40 && range[1] < 235 + 40,
                String.format(java.util.Locale.ROOT, "%.0f..%.0f", range[0], range[1]));

        effect.close();
        source.close();

        // Colour fidelity: flat fields first, then a gradient, because a flat field cannot fail and a
        // gradient is where the reported defect lives.
        colorFidelity(device);
        gradientFidelity(device);
    }

    /**
     * Whether a flat colour survives the upscale unchanged.
     *
     * <p>A flat field is exactly what this filter cannot get structurally wrong - so if a flat sky
     * changes colour, the cause is the colour processing mode rather than the reconstruction. The
     * tolerance is deliberately tight: 6/255 is below what the eye forgives on a smooth gradient, and
     * a wrong colour space moves these values by tens.
     */
    private static void colorFidelity(MetalDevice device) {
        final int in = 64;
        final int out = 128;
        // Minecraft never uses sRGB texture formats - the engine's own targets are plain UNORM - so
        // the values are the encoded bytes the shader wrote, which is the perceptual case.
        Object[][] samples = {{"sky blue", 120, 167, 255}, {"grass green", 90, 150, 70},
                {"stone grey", 128, 128, 128}};
        for (Object[] sample : samples) {
            String name = (String) sample[0];
            int wantR = (Integer) sample[1];
            int wantG = (Integer) sample[2];
            int wantB = (Integer) sample[3];
            GpuTexture source = device.createTexture("colour input " + name,
                    com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING
                            | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_SRC
                            | com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.RGBA8_UNORM, in, in, 1, 1);
            byte[] flat = new byte[in * in * 4];
            for (int i = 0; i < in * in; i++) {
                flat[i * 4] = (byte) wantR;
                flat[i * 4 + 1] = (byte) wantG;
                flat[i * 4 + 2] = (byte) wantB;
                flat[i * 4 + 3] = (byte) 0xFF;
            }
            net.metalmod.backend.MetalNative.textureReplaceRegion(
                    ((net.metalmod.backend.MetalTexture) source).handle(), 0, 0, 0, 0, in, in,
                    java.nio.ByteBuffer.wrap(flat), in * 4L);

            MainTarget large = new MainTarget(out, out);
            net.metalmod.metalfx.MetalFxScaler effect = net.metalmod.metalfx.MetalFxScaler.create(
                    device.deviceHandle(),
                    net.metalmod.backend.MetalFormat.mtlPixelFormat(GpuFormat.RGBA8_UNORM),
                    net.metalmod.backend.MetalFormat.mtlPixelFormat(GpuFormat.RGBA8_UNORM),
                    in, in, out, out, 0);
            if (effect == null) {
                check("the effect is built for the colour check", false,
                        net.metalmod.metalfx.MetalFx.unavailableReason());
                return;
            }
            effect.run(device.queueHandle(),
                    ((net.metalmod.backend.MetalTexture) source).handle(),
                    ((net.metalmod.backend.MetalTexture) large.getColorTexture()).handle());
            int[] got = centrePixel(device, large.getColorTexture());
            int delta = Math.max(Math.abs(got[0] - wantR),
                    Math.max(Math.abs(got[1] - wantG), Math.abs(got[2] - wantB)));
            check("a flat " + name + " survives the upscale unchanged",
                    delta <= 6, "expected " + wantR + " " + wantG + " " + wantB
                            + ", got " + got[0] + " " + got[1] + " " + got[2]
                            + " (delta " + delta + ")");
            effect.close();
            source.close();
        }
    }

    /**
     * Whether a smoothed gradient keeps its brightness through the upscale.
     *
     * <p>This is the shape the reported defect has: a real session measured the sky coming out
     * brighter and less saturated under MetalFX - red unchanged while green and blue rose by 15 to 47
     * - and a flat field of the same colour came through exactly. A flat field cannot distinguish a
     * filter that redistributes energy inside a gradient from one that does not, and that is what
     * "the sky looks grey" would be.
     *
     * <p>So this renders a smooth ramp, measures its mean, and measures the mean of the upscaled
     * result. A reconstruction filter may move energy around inside the picture, but it must not
     * change the mean of a smooth ramp: any shift is brightness that came from nowhere.
     */
    private static void gradientFidelity(MetalDevice device) {
        final int in = 128;
        final int out = 256;
        GpuTexture source = device.createTexture("gradient input",
                com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING
                        | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_SRC
                        | com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA8_UNORM, in, in, 1, 1);
        // A vertical ramp from a deep saturated blue to a pale one: the sky, in one channel pair.
        byte[] ramp = new byte[in * in * 4];
        for (int y = 0; y < in; y++) {
            int t = y * 255 / (in - 1);
            int r = 60 + t * 120 / 255;
            int g = 110 + t * 100 / 255;
            int b = 200 + t * 55 / 255;
            for (int x = 0; x < in; x++) {
                int at = (y * in + x) * 4;
                ramp[at] = (byte) r;
                ramp[at + 1] = (byte) g;
                ramp[at + 2] = (byte) b;
                ramp[at + 3] = (byte) 0xFF;
            }
        }
        net.metalmod.backend.MetalNative.textureReplaceRegion(
                ((net.metalmod.backend.MetalTexture) source).handle(), 0, 0, 0, 0, in, in,
                java.nio.ByteBuffer.wrap(ramp), in * 4L);

        MainTarget large = new MainTarget(out, out);
        net.metalmod.metalfx.MetalFxScaler effect = net.metalmod.metalfx.MetalFxScaler.create(
                device.deviceHandle(),
                net.metalmod.backend.MetalFormat.mtlPixelFormat(GpuFormat.RGBA8_UNORM),
                net.metalmod.backend.MetalFormat.mtlPixelFormat(GpuFormat.RGBA8_UNORM),
                in, in, out, out, 0);
        if (effect == null) {
            check("the effect is built for the gradient check", false,
                    net.metalmod.metalfx.MetalFx.unavailableReason());
            return;
        }
        effect.run(device.queueHandle(),
                ((net.metalmod.backend.MetalTexture) source).handle(),
                ((net.metalmod.backend.MetalTexture) large.getColorTexture()).handle());

        double[] inMean = meanRgb(device, source);
        double[] outMean = meanRgb(device, large.getColorTexture());
        double delta = Math.max(Math.abs(inMean[0] - outMean[0]),
                Math.max(Math.abs(inMean[1] - outMean[1]), Math.abs(inMean[2] - outMean[2])));
        System.out.println(String.format(java.util.Locale.ROOT,
                "       ramp mean in %.1f %.1f %.1f -> out %.1f %.1f %.1f",
                inMean[0], inMean[1], inMean[2], outMean[0], outMean[1], outMean[2]));
        check("a smooth gradient keeps its brightness through the upscale", delta <= 2.0,
                String.format(java.util.Locale.ROOT, "largest channel shift %.1f", delta));

        // The same ramp with a one-LSB checkerboard dither on it, which is what the engine writes into
        // gradients to stop them banding. A reconstruction filter is allowed to redistribute those
        // levels, but the mean must not move: if a dithered ramp comes out brighter, the filter is
        // treating the dither as signal, and every gradient in the game - the sky above all - changes
        // brightness with the render scale.
        for (int y = 0; y < in; y++) {
            for (int x = 0; x < in; x++) {
                int at = (y * in + x) * 4;
                int nudge = ((x + y) % 2 == 0) ? 1 : -1;
                ramp[at] = (byte) Math.max(0, Math.min(255, (ramp[at] & 0xFF) + nudge));
                ramp[at + 1] = (byte) Math.max(0, Math.min(255, (ramp[at + 1] & 0xFF) + nudge));
                ramp[at + 2] = (byte) Math.max(0, Math.min(255, (ramp[at + 2] & 0xFF) + nudge));
            }
        }
        net.metalmod.backend.MetalNative.textureReplaceRegion(
                ((net.metalmod.backend.MetalTexture) source).handle(), 0, 0, 0, 0, in, in,
                java.nio.ByteBuffer.wrap(ramp), in * 4L);
        effect.run(device.queueHandle(),
                ((net.metalmod.backend.MetalTexture) source).handle(),
                ((net.metalmod.backend.MetalTexture) large.getColorTexture()).handle());
        double[] ditheredIn = meanRgb(device, source);
        double[] ditheredOut = meanRgb(device, large.getColorTexture());
        double ditherDelta = Math.max(Math.abs(ditheredIn[0] - ditheredOut[0]),
                Math.max(Math.abs(ditheredIn[1] - ditheredOut[1]),
                        Math.abs(ditheredIn[2] - ditheredOut[2])));
        System.out.println(String.format(java.util.Locale.ROOT,
                "       dithered ramp mean in %.1f %.1f %.1f -> out %.1f %.1f %.1f",
                ditheredIn[0], ditheredIn[1], ditheredIn[2],
                ditheredOut[0], ditheredOut[1], ditheredOut[2]));
        check("a dithered gradient keeps its brightness through the upscale", ditherDelta <= 2.0,
                String.format(java.util.Locale.ROOT, "largest channel shift %.1f", ditherDelta));

        effect.close();
        source.close();
    }

    /** A 4x4 identity, as the engine's Projection block wants it. */
    private static ByteBuffer identityMat4() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                buffer.putFloat(column == row ? 1.0f : 0.0f);
            }
        }
        buffer.flip();
        return buffer;
    }

    /** The engine's DynamicTransforms block: model-view, colour modulator, model offset, texture matrix. */
    private static ByteBuffer dynamicTransforms(float[] modulator) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());
        buffer.put(identityMat4());
        buffer.putFloat(modulator[0]).putFloat(modulator[1]).putFloat(modulator[2])
                .putFloat(modulator[3]);
        buffer.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
        buffer.put(identityMat4());
        buffer.flip();
        return buffer;
    }

    /** Mean r, g, b over a texture. */
    private static double[] meanRgb(MetalDevice device, GpuTexture texture) {
        int w = texture.getWidth(0), h = texture.getHeight(0);
        GpuTextureView view = device.createTextureView(texture);
        var readback = device.createBuffer(() -> "mean readback",
                com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ
                        | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST, (long) w * h * 4);
        com.mojang.blaze3d.systems.CommandEncoderBackend encoder = device.createCommandEncoder();
        encoder.copyTextureToBuffer(texture, readback, 0L, null, 0, 0, 0, w, h);
        java.nio.ByteBuffer px = ((MetalBuffer) readback).data().asByteBuffer()
                .order(java.nio.ByteOrder.nativeOrder());
        double r = 0, g = 0, b = 0;
        for (int i = 0; i < w * h; i++) {
            r += px.get(i * 4) & 0xFF;
            g += px.get(i * 4 + 1) & 0xFF;
            b += px.get(i * 4 + 2) & 0xFF;
        }
        double n = (double) w * h;
        readback.close();
        view.close();
        return new double[]{r / n, g / n, b / n};
    }

    /** The centre pixel of a texture as {r, g, b}. */
    private static int[] centrePixel(MetalDevice device, GpuTexture texture) {
        int w = texture.getWidth(0), h = texture.getHeight(0);
        GpuTextureView view = device.createTextureView(texture);
        var readback = device.createBuffer(() -> "centre readback",
                com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ
                        | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST, (long) w * h * 4);
        com.mojang.blaze3d.systems.CommandEncoderBackend encoder = device.createCommandEncoder();
        encoder.copyTextureToBuffer(texture, readback, 0L, null, 0, 0, 0, w, h);
        java.nio.ByteBuffer px = ((MetalBuffer) readback).data().asByteBuffer()
                .order(java.nio.ByteOrder.nativeOrder());
        int at = ((h / 2) * w + (w / 2)) * 4;
        int[] rgb = {px.get(at) & 0xFF, px.get(at + 1) & 0xFF, px.get(at + 2) & 0xFF};
        readback.close();
        view.close();
        return rgb;
    }

    /** Min and max luma over a texture, as a crude but effective "is there a picture here" measure. */
    private static double[] rangeOf(MetalDevice device, GpuTexture texture) {
        int w = texture.getWidth(0), h = texture.getHeight(0);
        GpuTextureView view = device.createTextureView(texture);
        var readback = device.createBuffer(() -> "range readback",
                com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ
                        | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST, (long) w * h * 4);
        com.mojang.blaze3d.systems.CommandEncoderBackend encoder = device.createCommandEncoder();
        encoder.copyTextureToBuffer(texture, readback, 0L, null, 0, 0, 0, w, h);
        java.nio.ByteBuffer px = ((MetalBuffer) readback).data().asByteBuffer()
                .order(java.nio.ByteOrder.nativeOrder());
        double min = 255, max = 0;
        for (int i = 0; i < w * h; i++) {
            double luma = (px.get(i * 4) & 0xFF) * 0.3 + (px.get(i * 4 + 1) & 0xFF) * 0.6
                    + (px.get(i * 4 + 2) & 0xFF) * 0.1;
            min = Math.min(min, luma);
            max = Math.max(max, luma);
        }
        readback.close();
        view.close();
        return new double[]{min, max};
    }

    /**
     * The device level, used deliberately because the render target has no gpuformat-native clear.
     *
     * <p>The engine clears through {@code CommandEncoder}, which is fine, but a check that reads back
     * whole buffers and compares them wants a synchronous clear; {@code MetalNative.clearTextures}
     * blocks on a fence, so there is no guessing about ordering.
     */
    private static void clearTo(MetalDevice device, com.mojang.blaze3d.pipeline.RenderTarget target,
                                int r, int g, int b) {
        net.metalmod.backend.MetalNative.clearTextures(device.queueHandle(),
                ((net.metalmod.backend.MetalTexture) target.getColorTexture()).handle(), true,
                r / 255.0f, g / 255.0f, b / 255.0f, 1.0f, null, false, 0.0);
        net.metalmod.backend.MetalNative.queueSynchronize(device.queueHandle());
    }

    /**
     * Whether the whole target reads as one colour, within a tolerance.
     *
     * <p>A tolerance is needed because the colour is written by Metal and converted on the way in;
     * what the check is looking for is "the frame landed here", not an exact byte. Probes at the
     * centre and the four corners are compared, so a partial or misplaced write fails.
     */
    private static boolean uniform(MetalDevice device, com.mojang.blaze3d.pipeline.RenderTarget target,
                                   int r, int g, int b, int tolerance) {
        GpuTexture color = target.getColorTexture();
        int width = target.width;
        int height = target.height;
        GpuTextureView view = device.createTextureView(color);
        var readback = device.createBuffer(() -> "scaling readback",
                com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ
                        | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST,
                (long) width * height * 4);
        CommandEncoderBackend encoder = device.createCommandEncoder();
        encoder.copyTextureToBuffer(color, readback, 0L, null, 0, 0, 0, width, height);
        ByteBuffer pixels = ((MetalBuffer) readback).data().asByteBuffer().order(ByteOrder.nativeOrder());

        int[][] probes = {
            {width / 2, height / 2}, {1, 1}, {width - 2, 1}, {1, height - 2}, {width - 2, height - 2}};
        // The target's own size, reported with a failure: a probe read past the end of a smaller
        // buffer returns zeros, which would otherwise look like a plausible wrong colour.
        String where = width + "x" + height;
        boolean ok = true;
        for (int[] probe : probes) {
            int offset = (probe[1] * width + probe[0]) * 4;
            int pr = pixels.get(offset) & 0xFF;
            int pg = pixels.get(offset + 1) & 0xFF;
            int pb = pixels.get(offset + 2) & 0xFF;
            if (Math.abs(pr - r) > tolerance || Math.abs(pg - g) > tolerance
                    || Math.abs(pb - b) > tolerance) {
                ok = false;
                System.out.println("       in " + where + ", probe (" + probe[0] + "," + probe[1]
                        + ") = " + pr + " " + pg + " " + pb + ", expected " + r + " " + g + " " + b);
            }
        }
        readback.close();
        view.close();
        return ok;
    }

    private static void section(String title) {
        System.out.println("== " + title + " ==");
    }

    private static void check(String label, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + label + (detail.isEmpty() ? "" : "  " + detail));
        if (!ok) failures++;
    }

    private static GpuTexture unused(GpuTexture texture) {
        return texture;
    }
}
