package net.metalmod.upscaling;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalFormat;
import net.metalmod.backend.MetalNative;
import net.metalmod.backend.MetalTexture;
import java.lang.foreign.MemorySegment;
import java.util.Locale;
import java.util.function.BiConsumer;

/** Owns the world-only spatial reference. Never changes Window, extraction, culling or input sizes. */
public final class MetalFxCoordinator implements AutoCloseable {
    public record Stats(boolean requested, int strength, String effective, String reason,
                        int sceneWidth, int sceneHeight, int outputWidth, int outputHeight,
                        long generation, long creates, long failures, long encodes, long recoveries,
                        long retirements, long worldHooks, long uiHooks, long gpuDurationNs) {
        public double pixelPercent() {
            return new UpscalingSettings.Dimensions(sceneWidth, sceneHeight).pixelPercent(outputWidth, outputHeight);
        }
        public String summary() {
            return String.format(Locale.ROOT, "SR %s strength %d%% | %s | %dx%d -> %dx%d (%.1f%% pixels) | %s",
                    requested ? "On" : "Off", strength, effective, sceneWidth, sceneHeight,
                    outputWidth, outputHeight, pixelPercent(), reason);
        }
    }
    private static volatile Stats stats = new Stats(false, 25, "Native", "not initialized", 0,0,0,0,0,0,0,0,0,0,0,0,-1);
    public static Stats stats() { return stats; }
    private record Key(UpscalingSettings.Snapshot settings, int width, int height, long device, long format,
                       long reload) {}
    private Key key;
    private boolean failed, rendering;
    private long reload, generation, creates, failures, encodes, recoveries, retirements, worldHooks, uiHooks;
    private MetalDevice device;
    private TextureTarget scene;
    private MemorySegment scaler = MemorySegment.NULL;
    private int levelWidth, levelHeight;
    private String reason = "Off", failureReason = "";

    /** Called at the initial target clear, before Globals upload, world passes and post chains. */
    public RenderTarget begin(RenderTarget output, boolean world, BiConsumer<Integer, Integer> resizeLevel) {
        worldHooks++;
        // Developer-only lifecycle stress: recreate real scene/scaler resources in a running world.
        int recreateEvery = Integer.getInteger("metalmod.fxRecreateEvery", 0);
        if (world && recreateEvery > 0 && worldHooks % recreateEvery == 0) invalidate();
        UpscalingSettings.Snapshot settings = UpscalingSettings.current();
        MetalDevice active = MetalDevice.active();
        int ow = output.width, oh = output.height;
        UpscalingSettings.Dimensions size = UpscalingSettings.dimensions(ow, oh, settings);
        long format = output.getColorTexture() == null ? 0 : MetalFormat.mtlPixelFormat(output.getColorTexture().getFormat());
        Key next = new Key(settings, ow, oh, active == null ? 0 : active.deviceHandle().address(), format, reload);
        if (!next.equals(key)) {
            retire();
            key = next;
            failed = false;
        }
        rendering = false;
        if (!world && scene != null) retire();
        reason = !world ? "no world" : active == null ? "Metal unavailable"
                : ow <= 0 || oh <= 0 ? "surface suspended" : !settings.enabled() ? "Off"
                : settings.strength() == 0 ? "100% bypass" : failed ? failureReason : "";
        if (reason.isEmpty()) {
            if (scaler.address() != 0 && !MetalNative.fxHealthy(scaler)) fail("GPU error; native fallback");
            if (!failed && scene == null) {
                device = active;
                creates++;
                if (Boolean.getBoolean("metalmod.fxDeny") || !MetalNative.fxSupported(active.deviceHandle())) {
                    fail("MetalFX unsupported/denied");
                } else {
                    MemorySegment candidate = MetalNative.fxCreate(active.deviceHandle(), size.width(), size.height(), ow, oh, format);
                    if (candidate.address() == 0 || Boolean.getBoolean("metalmod.fxFailCreate")) {
                        MetalNative.fxRelease(candidate);
                        fail("MetalFX creation failed");
                    } else {
                        // Keep native/preset bypass untouched. This launch-only switch permits
                        // matched A/B captures without changing the upscaling mode or scene scale.
                        boolean antialias = !"false".equalsIgnoreCase(System.getProperty("metalmod.fxAntialias", "true"));
                        MetalNative.fxSetAntialias(candidate, antialias);
                        TextureTarget target = null;
                        try {
                            target = new TextureTarget("MetalMod scene", size.width(), size.height(), true,
                                    output.getColorTexture().getFormat());
                            if (!(target.getColorTexture() instanceof MetalTexture color) || !color.isValid()
                                    || !(target.getDepthTexture() instanceof MetalTexture depth) || !depth.isValid())
                                throw new IllegalStateException("scene attachments unavailable");
                            // Validate the complete generation before publishing it or reducing scene rendering.
                            MemorySegment cb = MetalNative.commandBufferCreate(active.queueHandle());
                            int rc;
                            try { rc = MetalNative.fxEncode(candidate, cb, color.handle(),
                                    ((MetalTexture) output.getColorTexture()).handle(), true); }
                            finally { MetalNative.commandBufferRelease(cb); } // uncommitted validation work
                            if (rc != 0) throw new IllegalStateException("texture contract status " + rc);
                            scene = target;
                            scaler = candidate;
                            generation++;
                            System.out.println("[MetalMod] MetalFX generation " + generation + " scene "
                                    + size.width() + "x" + size.height() + " -> " + ow + "x" + oh
                                    + " | spatial SDR perceptual | input AA " + (antialias ? "On" : "Off")
                                    + " | shared scene/native UI, private FX output");
                        } catch (RuntimeException e) {
                            if (target != null) target.destroyBuffers();
                            MetalNative.fxRelease(candidate);
                            fail("scene setup failed: " + e.getMessage());
                        }
                    }
                }
            }
            if (!failed && scene != null) {
                rendering = true;
                reason = "";
            }
        }
        int lw = rendering ? size.width() : ow, lh = rendering ? size.height() : oh;
        if (levelWidth == 0 && !rendering) { levelWidth = ow; levelHeight = oh; }
        if (lw > 0 && lh > 0 && (lw != levelWidth || lh != levelHeight)) {
            resizeLevel.accept(lw, lh);
            levelWidth = lw; levelHeight = lh;
        }
        publish(settings, ow, oh);
        return rendering ? scene : output;
    }

    /** Reconstruct after outlines/post effects and before native-depth clear and GUI rendering. */
    public void finish(RenderTarget output) {
        uiHooks++;
        if (!rendering) {
            if (key != null) publish(key.settings, output.width, output.height);
            return;
        }
        rendering = false;
        boolean injected = Boolean.getBoolean("metalmod.fxFailEncode");
        int rc = injected ? -6 : encode(output, false);
        if (rc == 0) encodes++;
        else {
            fail("MetalFX encode failed " + rc + "; native next frame");
            // Never present unfinished FX output. This generation already validated the plain pass.
            if (encode(output, true) != 0)
                throw new IllegalStateException("MetalFX recovery unavailable; refusing incomplete frame");
            recoveries++;
        }
        publish(key.settings, output.width, output.height, scene.width, scene.height);
    }
    private int encode(RenderTarget output, boolean plain) {
        MemorySegment cb = MetalNative.commandBufferCreate(device.queueHandle());
        if (cb.address() == 0) return -7;
        try {
            int rc = MetalNative.fxEncode(scaler, cb, ((MetalTexture) scene.getColorTexture()).handle(),
                    ((MetalTexture) output.getColorTexture()).handle(), plain);
            if (rc == 0) { MetalDevice.countCommandBuffer(); MetalNative.commandBufferCommit(cb); }
            return rc;
        } finally { MetalNative.commandBufferRelease(cb); }
    }
    private void fail(String message) {
        if (!failed) { failures++; System.err.println("[MetalMod] Super Resolution: " + message); }
        failed = true; reason = failureReason = message;
    }
    private void publish(UpscalingSettings.Snapshot settings, int ow, int oh) {
        publish(settings, ow, oh, rendering ? scene.width : ow, rendering ? scene.height : oh);
    }
    private void publish(UpscalingSettings.Snapshot settings, int ow, int oh, int sw, int sh) {
        stats = new Stats(settings.enabled(), settings.strength(), sw != ow || sh != oh
                ? failed ? "Plain recovery" : "MetalFX spatial" : "Native", reason,
                sw, sh, ow, oh, generation, creates, failures, encodes, recoveries, retirements, worldHooks, uiHooks,
                !failed && (sw != ow || sh != oh) ? MetalNative.fxGpuDuration(scaler) : -1);
    }
    /** A relevant resource change is an explicit retry event; no per-frame creation retries. */
    public void invalidate() { reload++; levelWidth = levelHeight = 0; }
    private void retire() {
        if (scene != null || scaler.address() != 0) {
            // Bounded retirement: wait only at configuration transitions, never once per steady frame.
            MetalNative.queueSynchronize(device.queueHandle());
            if (scene != null) scene.destroyBuffers();
            MetalNative.fxRelease(scaler);
            retirements++;
        }
        scene = null; scaler = MemorySegment.NULL; device = null;
    }
    @Override public void close() { retire(); key = null; rendering = false; }
}
