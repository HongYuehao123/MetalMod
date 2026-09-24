package net.metalmod.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A recorded capture route: a fixed sequence of places to stand, in order, for fixed durations.
 *
 * <p>This exists because a comparison is only as good as its route. Two earlier capture pairs were
 * unusable - one run took a different path from the other, so the difference in the scene was larger
 * than the difference between the backends being compared. A route is recorded once per dimension,
 * saved next to the captures, and replayed identically by every later capture, so the only things
 * that change between runs are the dimension and the backend.
 *
 * <p>Recording samples the player's position and collapses stretches where they did not move into a
 * single waypoint whose dwell is however long they stayed there. Standing still is what produces a
 * measurement; flying between two places produces one waypoint per sample, which replays faithfully
 * as a sequence of teleports but is rarely what a comparison wants.
 *
 * <p>Nothing here touches a Minecraft type, so the collapsing rules and the file format are
 * unit-testable without a game or a GPU.
 */
public final class CaptureRoute {
    /** Written to {@code route_stage} for frames with no route active. */
    public static final int NO_STAGE = -1;

    /** Positions within this many blocks of each other while recording count as the same waypoint. */
    public static final double DEFAULT_MERGE_RADIUS = 1.0;

    /** One sampled client position while recording. */
    public record Sample(String dimension, double x, double y, double z, float yaw, float pitch,
                         long atNanos) {
    }

    /** Where to stand, facing where, for how long. */
    public record Waypoint(String dimension, double x, double y, double z, float yaw, float pitch,
                           long dwellMs) {

        public String describe() {
            return String.format(Locale.ROOT, "%s at %.1f %.1f %.1f yaw %.0f pitch %.0f for %.1fs",
                    dimension, x, y, z, yaw, pitch, dwellMs / 1000.0);
        }
    }

    /** The on-disk document. Public fields because that is what Gson maps, and what a human edits. */
    private static final class Document {
        String name;
        int sampleMs;
        double mergeRadius;
        String recorded;
        String minecraft;
        List<Waypoint> waypoints;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String name;
    private final List<Waypoint> waypoints;
    private final long totalDwellMs;

    public CaptureRoute(String name, List<Waypoint> waypoints) {
        this.name = name == null || name.isEmpty() ? "route" : name;
        this.waypoints = List.copyOf(waypoints);
        this.totalDwellMs = this.waypoints.stream().mapToLong(Waypoint::dwellMs).sum();
    }

    public String name() {
        return this.name;
    }

    public List<Waypoint> waypoints() {
        return this.waypoints;
    }

    public int stageCount() {
        return this.waypoints.size();
    }

    public long totalDwellMs() {
        return this.totalDwellMs;
    }

    public Waypoint waypoint(int stage) {
        return this.waypoints.get(stage);
    }

    /** True when this route cannot drive a capture and should be ignored rather than replayed. */
    public boolean isEmpty() {
        return this.waypoints.isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Recording
    // ---------------------------------------------------------------------------------------------

    private static boolean sameSpot(Sample a, Sample b, double mergeRadius) {
        if (!a.dimension().equals(b.dimension())) return false;
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz <= mergeRadius * mergeRadius;
    }

    /**
     * Collapse sampled positions into waypoints.
     *
     * <p>A run is a maximal stretch of samples within {@code mergeRadius} of the run's first sample.
     * Its dwell is the wall time from its first to its last sample, and its position is the first
     * sample: the point the player arrived at, which is what a teleport can reproduce exactly. A run
     * of a single sample has no measured duration, so it gets one sample interval.
     */
    public static CaptureRoute collapse(String name, List<Sample> samples, long sampleMs,
                                        double mergeRadius) {
        List<Waypoint> waypoints = new ArrayList<>();
        int index = 0;
        while (index < samples.size()) {
            Sample first = samples.get(index);
            int end = index + 1;
            while (end < samples.size() && sameSpot(first, samples.get(end), mergeRadius)) {
                end++;
            }
            long dwellMs = end - index > 1
                    ? (samples.get(end - 1).atNanos() - first.atNanos()) / 1_000_000L
                    : Math.max(1, sampleMs);
            waypoints.add(new Waypoint(first.dimension(), first.x(), first.y(), first.z(),
                    first.yaw(), first.pitch(), Math.max(1, dwellMs)));
            index = end;
        }
        return new CaptureRoute(name, waypoints);
    }

    // ---------------------------------------------------------------------------------------------
    // File format
    // ---------------------------------------------------------------------------------------------

    public static CaptureRoute load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            Document document = GSON.fromJson(reader, Document.class);
            if (document == null || document.waypoints == null) return null;
            CaptureRoute route = new CaptureRoute(
                    document.name != null ? document.name : fileName(file), document.waypoints);
            return route.isEmpty() ? null : route;
        } catch (com.google.gson.JsonParseException error) {
            throw new IOException("not a valid route file: " + error.getMessage(), error);
        }
    }

    public void save(Path file, String recorded, String minecraft, int sampleMs, double mergeRadius)
            throws IOException {
        Files.createDirectories(file.getParent());
        Document document = new Document();
        document.name = this.name;
        document.sampleMs = sampleMs;
        document.mergeRadius = mergeRadius;
        document.recorded = recorded;
        document.minecraft = minecraft;
        document.waypoints = this.waypoints;
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(document, writer);
        }
    }

    private static String fileName(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    /** A route name safe to use as a file name. */
    public static String sanitize(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        return cleaned.isEmpty() ? "route" : cleaned;
    }

    /** One line summarising the route, for the capture status and the summary header. */
    public String describe() {
        return "route '" + this.name + "': " + this.waypoints.size() + " waypoints, "
                + String.format(Locale.ROOT, "%.1fs", this.totalDwellMs / 1000.0);
    }
}
