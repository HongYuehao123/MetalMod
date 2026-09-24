package net.metalmod.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * F7: record where the player stands, so that captures can replay it.
 *
 * <p>Samples position every {@link CaptureRouteStore#SAMPLE_MS} and collapses stationary stretches
 * into waypoints. Press F7 to start, walk or fly the route, press F7 to stop; the route is saved
 * under the current dimension's name and every later F8 capture in that dimension replays it.
 */
public final class CaptureRouteRecorder {

    /** Safety bound: 100k samples is nearly 14 hours at the default interval. */
    private static final int MAX_SAMPLES = 100_000;

    private static final List<CaptureRoute.Sample> samples = new ArrayList<>();
    private static boolean active;
    private static long lastSampleAt;
    private static String name;
    private static String status = "F7: record a capture route";

    private CaptureRouteRecorder() {
    }

    public static boolean isActive() {
        return active;
    }

    public static String status() {
        return status;
    }

    /** Samples captured so far; shown on F3 while recording. */
    public static int sampleCount() {
        return samples.size();
    }

    public static void toggle(Minecraft minecraft) {
        if (active) {
            stop(minecraft);
        } else {
            start(minecraft);
        }
    }

    private static void start(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            notify(minecraft, "Load a world before recording a route.");
            return;
        }
        if (PerformanceCapture.isRecording()) {
            notify(minecraft, "A performance capture is running; F8 stops it first.");
            return;
        }
        samples.clear();
        name = CaptureRouteStore.name(minecraft);
        lastSampleAt = 0;
        active = true;
        status = "Recording route '" + name + "'; F7 stops";
        notify(minecraft, status + ". Stand still where you want a measurement.");
    }

    /** Called at the presentation boundary, so the samples line up with the frames a capture sees. */
    public static void sample(Minecraft minecraft, long now) {
        if (!active || minecraft.player == null) {
            return;
        }
        if (now - lastSampleAt < CaptureRouteStore.SAMPLE_MS * 1_000_000L) {
            return;
        }
        lastSampleAt = now;
        if (samples.size() >= MAX_SAMPLES) {
            stop(minecraft);
            return;
        }
        samples.add(new CaptureRoute.Sample(CaptureRouteStore.dimension(minecraft),
                minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                minecraft.player.getYRot(), minecraft.player.getXRot(), now));
    }

    private static void stop(Minecraft minecraft) {
        active = false;
        if (samples.size() < 2) {
            status = "Route '" + name + "' discarded: only " + samples.size() + " sample(s)";
            notify(minecraft, status);
            samples.clear();
            return;
        }
        CaptureRoute route = CaptureRoute.collapse(name, samples,
                CaptureRouteStore.SAMPLE_MS, CaptureRoute.DEFAULT_MERGE_RADIUS);
        samples.clear();
        try {
            Path file = CaptureRouteStore.save(minecraft, route, CaptureRouteStore.SAMPLE_MS,
                    CaptureRoute.DEFAULT_MERGE_RADIUS);
            status = route.describe() + "; F7 records again";
            notify(minecraft, "Route saved: " + file.toAbsolutePath());
            notify(minecraft, route.describe() + ". F8 now replays it.");
        } catch (IOException error) {
            status = "Route save failed; see latest.log";
            System.err.println("[MetalMod] could not save route: " + error);
            notify(minecraft, status);
        }
    }

    public static void close() {
        active = false;
        samples.clear();
    }

    private static void notify(Minecraft minecraft, String message) {
        minecraft.gui.hud.setOverlayMessage(Component.literal("[MetalMod] " + message), false);
        minecraft.showDebugChat(Component.literal("[MetalMod] " + message));
    }
}
