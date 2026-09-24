package net.metalmod.debug;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Pins the route rules that make a capture comparable: how a recorded walk collapses into waypoints,
 * that the file round-trips, and that a replayed stage reaches both the CSV and the summary.
 *
 * <p>These are the parts a wrong answer would silently ruin. A collapse that merged two places into
 * one waypoint, or a JSON field Gson dropped on the way back, would produce a capture that looks
 * fine and compares two different scenes.
 */
public final class CaptureRouteTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private static int failures;

    private static void check(String what, boolean condition, String detail) {
        if (condition) {
            System.out.println("PASS " + what);
        } else {
            failures++;
            System.out.println("FAIL " + what + (detail.isEmpty() ? "" : " -> " + detail));
        }
    }

    private static CaptureRoute.Sample at(String dimension, double x, double y, double z, long ms) {
        return new CaptureRoute.Sample(dimension, x, y, z, 90.0f, 12.0f, ms * 1_000_000L);
    }

    public static int runTests() {
        System.out.println("[TEST] Capture route recording, collapsing and replay metadata");
        failures = 0;

        // Standing still is one waypoint whose dwell is how long the player stood there.
        CaptureRoute standing = CaptureRoute.collapse("s", List.of(
                at(OVERWORLD, 10, 70, 20, 0),
                at(OVERWORLD, 10, 70, 20, 500),
                at(OVERWORLD, 10, 70, 20, 1000),
                at(OVERWORLD, 10, 70, 20, 1500),
                at(OVERWORLD, 100, 60, 20, 2000),
                at(OVERWORLD, 100, 60, 20, 2500)), 500, 1.0);
        check("a stationary stretch collapses to one waypoint", standing.stageCount() == 2,
                "stages=" + standing.stageCount());
        check("the waypoint keeps the arrival position and the measured dwell",
                standing.waypoint(0).x() == 10 && standing.waypoint(0).dwellMs() == 1500,
                standing.waypoint(0).describe());
        check("the second run keeps its own dwell", standing.waypoint(1).dwellMs() == 500,
                standing.waypoint(1).describe());
        check("total dwell is the sum of the stages", standing.totalDwellMs() == 2000,
                Long.toString(standing.totalDwellMs()));

        // A run of one sample has no measured duration, so it gets one sample interval rather than 0.
        CaptureRoute singles = CaptureRoute.collapse("s", List.of(
                at(OVERWORLD, 0, 70, 0, 0),
                at(OVERWORLD, 50, 70, 0, 500)), 500, 1.0);
        check("a single-sample run gets one sample interval",
                singles.stageCount() == 2 && singles.waypoint(0).dwellMs() == 500,
                singles.waypoint(0).describe());

        // A dimension change is a different place even at identical coordinates.
        CaptureRoute acrossDimensions = CaptureRoute.collapse("s", List.of(
                at(OVERWORLD, 5, 70, 5, 0),
                at(OVERWORLD, 5, 70, 5, 500),
                at(NETHER, 5, 70, 5, 1000),
                at(NETHER, 5, 70, 5, 1500)), 500, 1.0);
        check("a dimension change starts a new waypoint",
                acrossDimensions.stageCount() == 2
                        && NETHER.equals(acrossDimensions.waypoint(1).dimension()), "");

        // Rotation is part of the scene, so it survives the round trip with the position.
        check("rotation is carried into the waypoint",
                standing.waypoint(0).yaw() == 90.0f && standing.waypoint(0).pitch() == 12.0f, "");

        try {
            Path root = Files.createTempDirectory("metalmod-route-test-");
            try {
                Path file = root.resolve("overworld.json");
                standing.save(file, "2026-01-01T00:00", "test", 500, 1.0);
                CaptureRoute loaded = CaptureRoute.load(file);
                check("a saved route loads back", loaded != null && loaded.stageCount() == 2, "load failed");
                if (loaded != null) {
                    check("the name survives the round trip", "s".equals(loaded.name()), loaded.name());
                    check("positions survive the round trip",
                            loaded.waypoint(0).x() == 10 && loaded.waypoint(1).x() == 100, "");
                    check("dwells survive the round trip",
                            loaded.waypoint(0).dwellMs() == 1500 && loaded.waypoint(1).dwellMs() == 500, "");
                    check("rotation survives the round trip",
                            loaded.waypoint(1).yaw() == 90.0f && loaded.waypoint(1).pitch() == 12.0f, "");
                    check("the dimension survives the round trip",
                            OVERWORLD.equals(loaded.waypoint(0).dimension()), "");
                }
                // An empty waypoint list is not a route; loading one must not produce a replay driver.
                Path empty = root.resolve("empty.json");
                Files.writeString(empty, "{\"name\":\"empty\",\"waypoints\":[]}");
                check("an empty route loads as nothing to replay", CaptureRoute.load(empty) == null, "");
            } finally {
                deleteTree(root);
            }
        } catch (Exception error) {
            failures++;
            System.out.println("FAIL route file round trip -> " + error);
        }

        check("route names are safe as file names",
                "minecraft.overworld".equals(CaptureRoute.sanitize("minecraft.overworld"))
                        && "a_b".equals(CaptureRoute.sanitize("a:b")), CaptureRoute.sanitize("a:b"));

        checkStageReporting();
        checkRouteNameFallback();

        System.out.println(failures == 0 ? "[TEST] capture route OK" : "[TEST] " + failures + " FAILED");
        return failures;
    }

    /** A replayed stage must reach both the CSV and the per-waypoint summary. */
    private static void checkStageReporting() {
        CaptureRoute route = new CaptureRoute("overworld", List.of(
                new CaptureRoute.Waypoint(OVERWORLD, 1.0, 70.0, 2.0, 0f, 0f, 1000),
                new CaptureRoute.Waypoint(OVERWORLD, 3.0, 40.0, 4.0, 0f, 0f, 1000)));
        PerformanceRecording recording = new PerformanceRecording(4, List.of("draws"));
        for (int frame = 0; frame < 4; frame++) {
            int stage = frame < 2 ? 0 : 1;
            recording.put(PerformanceRecording.COL_FRAME_NS, 10_000_000L + frame);
            recording.put(PerformanceRecording.COL_PAUSED, 0);
            recording.put(PerformanceRecording.COL_SCREEN_OPEN, 0);
            recording.put(PerformanceRecording.COL_WINDOW_ACTIVE, 1);
            recording.put(PerformanceRecording.COL_PLAYER_X, stage == 0 ? 1 : 3);
            recording.put(PerformanceRecording.COL_PLAYER_Y, stage == 0 ? 70 : 40);
            recording.put(PerformanceRecording.COL_PLAYER_Z, stage == 0 ? 2 : 4);
            recording.put(PerformanceRecording.COL_ROUTE_STAGE, stage);
            recording.commitFrame();
        }
        String summary = recording.summary("Backend: Metal", "test", route);
        check("the summary breaks the capture down by waypoint",
                summary.contains("Per-waypoint gameplay frames"), "");
        check("every waypoint gets a row", summary.contains("    0  ") && summary.contains("    1  "), "");
        check("the waypoint it was replayed from is in the row",
                summary.contains("1.0 70.0 2.0") && summary.contains("3.0 40.0 4.0"), "");
        check("the summary names the route",
                summary.contains("route 'overworld': 2 waypoints"), "");
        // Draw count is what says whether a stage measured a scene at all; with no draws column it
        // must say so rather than print a misleading zero.
        check("a stage with no draw data reports it as unavailable",
                summary.contains("Per-waypoint gameplay frames") && summary.contains("      -  "), "");
        check("no route means no per-waypoint section",
                !recording.summary("Backend: Metal", "test", null).contains("Per-waypoint"), "");

        try {
            Path root = Files.createTempDirectory("metalmod-stage-test-");
            try {
                Path output = recording.write(root, "stage", "Backend: Metal", "test", route);
                List<String> csv = Files.readAllLines(output.resolve("frames.csv"));
                // Located by name rather than by position: backend columns follow the base ones, so
                // route_stage is not the last column in the file even though it is last in the base set.
                int stageColumn = List.of(csv.get(0).split(",")).indexOf("route_stage");
                check("route_stage is a CSV column", stageColumn >= 0, csv.get(0));
                check("each row carries its stage",
                        stageColumn >= 0 && "0".equals(csv.get(1).split(",")[stageColumn])
                                && "1".equals(csv.get(4).split(",")[stageColumn]), csv.get(4));
            } finally {
                deleteTree(root);
            }
        } catch (Exception error) {
            failures++;
            System.out.println("FAIL route stage CSV -> " + error);
        }
    }

    /** With no route recorded the capture must still work, and say so rather than failing. */
    private static void checkRouteNameFallback() {
        check("a null route name still produces a usable name",
                "route".equals(new CaptureRoute(null, List.of()).name()), "");
        check("an empty route reports itself empty",
                new CaptureRoute("x", List.of()).isEmpty(), "");
    }

    /** The capture writer makes a directory per capture, so cleanup has to recurse. */
    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private CaptureRouteTest() {
    }
}
