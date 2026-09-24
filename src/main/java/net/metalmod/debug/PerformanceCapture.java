package net.metalmod.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalNative;
import net.metalmod.backend.MetalTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** F8 -> five-second countdown -> the route (or 45 s free) -> background export. Client-thread owned. */
public final class PerformanceCapture {
    /**
     * How long a capture with no route recorded in this dimension runs for.
     *
     * <p>A route-bound capture does not use this: it runs for the route plus {@link #ROUTE_TAIL}, so a
     * forty-second route is no longer recorded for a full minute and its last stage does not carry
     * twenty seconds of stationary frames into the per-stage statistics.
     */
    private static final long FREE_DURATION = 45_000_000_000L;

    /**
     * Settled hold at the final waypoint after a route's last stage ends.
     *
     * <p>The tail is deliberate - a stationary stretch is the most comparable sample there is - but it
     * is a tail, not half the capture. Three seconds is still well over a hundred frames.
     */
    private static final long ROUTE_TAIL = 3_000_000_000L;

    /** The limit for the capture in progress: route length plus the tail, or the free duration. */
    private static long durationNanos = FREE_DURATION;
    private static final long COUNTDOWN = 5_000_000_000L;
    private static PerformanceRecording recording;
    private static Arena arena;
    private static MemorySegment nativeSample;
    private static List<GarbageCollectorMXBean> collectors;
    private static long readyAt, startedAt, lastFrame, lastFfi, lastCompile, lastCompileCount;
    private static long lastGcCount, lastGcMillis;
    private static int lastSeconds = -1;
    private static boolean nativeEnabled, previousPaused, previousScreen, previousFocused;
    private static String metadata, prefix;
    private static Path outputRoot;
    private static volatile boolean saving;
    private static Thread writerThread;
    private static boolean frameHookSeen;
    private static CaptureRoute route;
    private static CaptureRoutePlayer routePlayer;
    private static volatile String status = "F8: record performance";

    private PerformanceCapture() {}

    public static String status() { return status; }
    public static boolean isRecording() { return recording != null; }

    public static void toggle(Minecraft minecraft) {
        if (!frameHookSeen) {
            notify(minecraft, "Capture frame hook is unavailable; check latest.log for mixin errors.");
            return;
        }
        if (saving) {
            notify(minecraft, "Still saving the previous capture.");
            return;
        }
        if (recording != null) {
            finish(minecraft, "stopped with F8", false);
            return;
        }
        if (minecraft.level == null || minecraft.player == null) {
            notify(minecraft, "Load a world before recording performance.");
            return;
        }
        if (CaptureRouteRecorder.isActive()) {
            notify(minecraft, "A route is being recorded; F7 stops it first.");
            return;
        }
        try {
            // Allocate before the countdown so this setup does not become a measured hitch.
            recording = new PerformanceRecording(36_000, captureColumns());
            collectors = ManagementFactory.getGarbageCollectorMXBeans();
            outputRoot = minecraft.gameDirectory.toPath().resolve("debug/metalmod");
            String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
            nativeEnabled = "Metal".equals(backend) && MetalNative.captureAvailable();
            if (nativeEnabled) {
                arena = Arena.ofConfined();
                nativeSample = arena.allocate(ValueLayout.JAVA_LONG, MetalNative.CAPTURE_METRICS.size());
            }
            prefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                    + "-" + backend.replaceAll("[^A-Za-z0-9_-]", "_");
            route = CaptureRouteStore.load(minecraft);
            routePlayer = route == null ? null : new CaptureRoutePlayer(route);
            boolean prep = !"false".equalsIgnoreCase(System.getProperty("metalmod.capturePrep", "true"));
            metadata = "Backend: " + backend + "\nNative metrics: " + nativeEnabled
                    + "\nMinecraft: " + minecraft.getLaunchedVersion()
                    + "\nOS: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                    + "\nJava: " + System.getProperty("java.version")
                    + "\nResolution at start: " + minecraft.getWindow().getWidth() + "x" + minecraft.getWindow().getHeight()
                    + "\nRender distance: " + minecraft.options.renderDistance().get()
                    + "\nVsync: " + minecraft.options.enableVsync().get()
                    + "\nFPS limit: " + minecraft.options.framerateLimit().get()
                    // Recorded because it is a launch setting that changes what is measured, and
                    // identifying which run had it on otherwise means digging through the game logs.
                    + "\nRender target storage: " + (MetalTexture.privateTexturesEnabled()
                            ? "private (" + MetalDevice.privateTextureCount() + " textures claimed)"
                            : "shared (default; -Dmetalmod.privateTextures=all enables private)")
                    + "\nDimension: " + CaptureRouteStore.dimension(minecraft)
                    + "\nRoute: " + (route == null
                            ? "none for this dimension (F7 records one); move manually"
                            : route.describe())
                    + "\nWorld prep: " + (route == null ? "n/a" : prep ? "on" : "off")
                    + (route == null ? "" : "\nKnown routes: " + CaptureRouteStore.describeAvailable(minecraft))
                    + "\nCapture limit: " + limitSeconds() + " seconds or 36000 frames"
                    + "\nBoundary: after GpuSurface.present(), on any backend. First partial interval discarded."
                    + "\nCensus diagnostics at start: " + (nativeEnabled && MetalDevice.censusEnabled());
            // Bound the run by the route it is about to play. A fixed limit records dead time past the
            // end of a short route, and that dead time is labelled with the final stage.
            durationNanos = route == null
                    ? FREE_DURATION
                    : route.totalDwellMs() * 1_000_000L + ROUTE_TAIL;
            startedAt = lastFrame = 0;
            lastSeconds = -1;
            readyAt = System.nanoTime() + COUNTDOWN;
            if (routePlayer != null) {
                // Prepare the world and move to the first waypoint while the countdown runs, so the
                // first measured stage is already settled instead of measuring its own arrival.
                if (prep) CaptureRoutePlayer.prepare(minecraft);
                routePlayer.arm(minecraft);
                status = "Capture starts in 5s at waypoint 1/" + route.stageCount() + "; F8 stops";
            } else {
                status = "Capture starts in 5s; F8 stops";
            }
            notify(minecraft, status);
        } catch (RuntimeException error) {
            fail(minecraft, error);
        }
    }

    /** Runs at the surface presentation boundary, including submission of the final present pass. */
    public static void framePresented(Minecraft minecraft) {
        frameHookSeen = true;
        long now = System.nanoTime();
        // Route recording samples here too, so a route is sampled on exactly the frames a capture
        // would see rather than on some independent timer.
        CaptureRouteRecorder.sample(minecraft, now);
        if (recording == null) return;
        try {
            if (minecraft.level == null || minecraft.player == null) {
                finish(minecraft, "world closed", false);
                return;
            }
            if (now < readyAt) {
                updateStatus(minecraft, "Capture starts in ", (int) Math.ceil((readyAt - now) / 1e9));
                return;
            }
            if (lastFrame == 0) {
                if (nativeEnabled) MetalNative.captureSetEnabled(true);
                startedAt = lastFrame = now;
                lastFfi = MetalNative.ffiCallCount();
                lastCompile = lastCompileCount = 0;
                lastGcCount = gcTotal(false);
                lastGcMillis = gcTotal(true);
                rememberContext(minecraft);
                // Start the first waypoint's dwell clock at the first measured frame. The teleport
                // itself already happened during the countdown.
                if (routePlayer != null) routePlayer.startAt(now);
                updateStatus(minecraft, "Recording performance: ", limitSeconds());
                return;
            }
            recording.put(PerformanceRecording.COL_ELAPSED_NS, now - startedAt);
            recording.put(PerformanceRecording.COL_FRAME_NS, now - lastFrame);
            recording.put(PerformanceRecording.COL_PAUSED, previousPaused || minecraft.isPaused() ? 1 : 0);
            recording.put(PerformanceRecording.COL_SCREEN_OPEN, previousScreen || minecraft.gui.screen() != null ? 1 : 0);
            recording.put(PerformanceRecording.COL_WINDOW_ACTIVE, previousFocused && focused(minecraft) ? 1 : 0);
            recording.put(PerformanceRecording.COL_WIDTH, minecraft.getWindow().getWidth());
            recording.put(PerformanceRecording.COL_HEIGHT, minecraft.getWindow().getHeight());
            recording.put(PerformanceRecording.COL_PLAYER_X, minecraft.player.getBlockX());
            recording.put(PerformanceRecording.COL_PLAYER_Y, minecraft.player.getBlockY());
            recording.put(PerformanceRecording.COL_PLAYER_Z, minecraft.player.getBlockZ());
            if (nativeEnabled) {
                MetalNative.captureReadReset(nativeSample);
                long ffi = MetalNative.ffiCallCount();
                long compile = MetalNative.capturePipelineNanos();
                long compileCount = MetalNative.capturePipelineCount();
                recording.put(PerformanceRecording.COL_FFI_CALLS, ffi - lastFfi);
                recording.put(PerformanceRecording.COL_PIPELINE_COMPILES, compileCount - lastCompileCount);
                recording.put(PerformanceRecording.COL_PIPELINE_COMPILE_NS, compile - lastCompile);
                lastFfi = ffi;
                lastCompile = compile;
                lastCompileCount = compileCount;
                recording.put(PerformanceRecording.COL_CENSUS_ACTIVE, MetalDevice.censusEnabled() ? 1 : 0);
                for (int i = 0; i < MetalNative.CAPTURE_METRICS.size(); i++) {
                    recording.put(PerformanceRecording.COL_FIRST_NATIVE + i,
                            nativeSample.getAtIndex(ValueLayout.JAVA_LONG, i));
                }
                putLighting(recording);
            }
            long gcCount = gcTotal(false), gcMillis = gcTotal(true);
            recording.put(PerformanceRecording.COL_GC_COLLECTIONS, delta(gcCount, lastGcCount));
            recording.put(PerformanceRecording.COL_GC_REPORTED_MS, delta(gcMillis, lastGcMillis));
            lastGcCount = gcCount;
            lastGcMillis = gcMillis;
            if (routePlayer != null) {
                routePlayer.tick(minecraft, now);
                recording.put(PerformanceRecording.COL_ROUTE_STAGE, routePlayer.stage());
            } else {
                recording.put(PerformanceRecording.COL_ROUTE_STAGE, CaptureRoute.NO_STAGE);
            }
            rememberContext(minecraft);
            recording.commitFrame();
            lastFrame = now;
            if (now - startedAt >= durationNanos || recording.full()) {
                finish(minecraft, recording.full() ? "36000-frame capacity reached"
                        : limitSeconds() + " seconds elapsed", false);
            } else {
                updateStatus(minecraft, routePlayer == null ? "Recording performance: "
                        : "Recording " + routePlayer.status() + ": ",
                        (int) Math.ceil((durationNanos - now + startedAt) / 1e9));
            }
        } catch (RuntimeException error) {
            fail(minecraft, error);
        }
    }

    /**
     * The native columns plus the lighting ones, appended so native indices keep their positions.
     */
    private static java.util.List<String> captureColumns() {
        java.util.List<String> names = new java.util.ArrayList<>(MetalNative.CAPTURE_METRICS);
        names.addAll(LightingCaptureColumns.NAMES);
        return java.util.List.copyOf(names);
    }

    /** Write this frame's lighting figures into their columns, past the native block. */
    private static void putLighting(PerformanceRecording recording) {
        MetalDevice.LightingStats stats = MetalDevice.lightingStats();
        int base = PerformanceRecording.COL_FIRST_NATIVE + MetalNative.CAPTURE_METRICS.size();
        for (int i = 0; i < LightingCaptureColumns.NAMES.size(); i++) {
            recording.put(base + i, LightingCaptureColumns.value(stats, i));
        }
    }

    /** The capture limit in whole seconds, for the labels. */
    private static int limitSeconds() {
        return (int) Math.round(durationNanos / 1e9);
    }

    private static long delta(long current, long previous) {
        return current < 0 || previous < 0 ? -1 : Math.max(0, current - previous);
    }

    private static long gcTotal(boolean time) {
        long sum = 0;
        for (GarbageCollectorMXBean collector : collectors) {
            long value = time ? collector.getCollectionTime() : collector.getCollectionCount();
            if (value < 0) return -1;
            sum += value;
        }
        return sum;
    }

    private static void rememberContext(Minecraft minecraft) {
        previousPaused = minecraft.isPaused();
        previousScreen = minecraft.gui.screen() != null;
        previousFocused = focused(minecraft);
    }

    private static boolean focused(Minecraft minecraft) {
        return minecraft.isWindowActive() && !minecraft.getWindow().isMinimized();
    }

    private static void updateStatus(Minecraft minecraft, String lead, int seconds) {
        if (seconds == lastSeconds) return;
        lastSeconds = seconds;
        status = lead + seconds + "s; F8 stops";
        minecraft.gui.hud.setOverlayMessage(Component.literal("[MetalMod] " + status), false);
    }

    public static void close(Minecraft minecraft) {
        try {
            CaptureRouteRecorder.close();
            if (recording != null) finish(minecraft, "game closing", true);
            // Minecraft may explicitly exit the JVM after close(), even with a non-daemon writer.
            if (writerThread != null) writerThread.join(5000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            // Shutting down: any export has to finish before the JVM can exit.
            fail(minecraft, error, true);
        }
    }

    private static void releaseNative() {
        try {
            if (nativeEnabled) MetalNative.captureSetEnabled(false);
        } finally {
            nativeEnabled = false;
            if (arena != null) arena.close();
            arena = null;
            nativeSample = null;
        }
    }

    private static void fail(Minecraft minecraft, RuntimeException error) {
        fail(minecraft, error, false);
    }

    /**
     * Report a capture failure.
     *
     * <p>Whatever was collected is still exported: a full-length run is expensive to lose, and the
     * failure reason belongs in the summary next to the samples. The exception is never rethrown -
     * it would escape into the render loop.
     */
    private static void fail(Minecraft minecraft, RuntimeException error, boolean synchronous) {
        System.err.println("[MetalMod] Performance capture failed: " + error);
        PerformanceRecording partial = recording;
        try {
            releaseNative();
        } catch (RuntimeException ignored) {
        }
        if (partial != null && partial.size() > 0 && !saving) {
            recording = partial;
            finish(minecraft, "failed: " + error, synchronous);
            return;
        }
        recording = null;
        status = "Capture failed; see latest.log";
        notify(minecraft, status);
    }

    private static void finish(Minecraft minecraft, String reason, boolean synchronous) {
        PerformanceRecording completed = recording;
        recording = null;
        releaseNative();
        if (completed.size() == 0) {
            status = "Capture cancelled; F8 records again";
            notify(minecraft, status);
            return;
        }
        // Ownership transfers once. No frame can mutate this recording while it is being written.
        String completedMetadata = metadata, completedPrefix = prefix;
        Path completedRoot = outputRoot;
        CaptureRoute completedRoute = route;
        route = null;
        routePlayer = null;
        saving = true;
        status = "Saving performance capture...";
        Runnable writer = () -> {
            String message;
            try {
                Path directory = completed.write(completedRoot, completedPrefix, completedMetadata, reason,
                        completedRoute);
                message = "Capture saved: " + directory.toAbsolutePath();
                status = "Capture saved in debug/metalmod; F8 records again";
            } catch (Exception error) {
                message = "Could not save capture: " + error;
                status = "Capture save failed; see latest.log";
            } finally {
                saving = false;
            }
            System.out.println("[MetalMod] " + message);
            String finalMessage = message;
            if (!synchronous) minecraft.execute(() -> notify(minecraft, finalMessage));
        };
        if (synchronous) writer.run();
        else {
            writerThread = new Thread(writer, "MetalMod-Capture-Writer");
            writerThread.start();
        }
    }

    private static void notify(Minecraft minecraft, String message) {
        minecraft.gui.hud.setOverlayMessage(Component.literal("[MetalMod] " + message), false);
        minecraft.showDebugChat(Component.literal("[MetalMod] " + message));
    }
}
