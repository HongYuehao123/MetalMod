package net.metalmod.debug;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Bounded, allocation-free sample storage. Formatting and file I/O happen only after capture. */
public final class PerformanceRecording {

    /**
     * Column indices of the base fields.
     *
     * <p>The capture writes samples by index and the CSV header is generated from
     * {@link #BASE_COLUMNS}, so the two must stay in step. They are named constants rather than
     * literals because that contract is otherwise silent when it breaks: inserting a base column
     * would shift every later value into the wrong column, with nothing refusing to compile. The
     * column test pins each constant to its header name.
     */
    public static final int COL_ELAPSED_NS = 0;
    public static final int COL_FRAME_NS = 1;
    public static final int COL_PAUSED = 2;
    public static final int COL_SCREEN_OPEN = 3;
    public static final int COL_WINDOW_ACTIVE = 4;
    public static final int COL_WIDTH = 5;
    public static final int COL_HEIGHT = 6;
    public static final int COL_PLAYER_X = 7;
    public static final int COL_PLAYER_Y = 8;
    public static final int COL_PLAYER_Z = 9;
    public static final int COL_FFI_CALLS = 10;
    public static final int COL_PIPELINE_COMPILES = 11;
    public static final int COL_PIPELINE_COMPILE_NS = 12;
    public static final int COL_GC_COLLECTIONS = 13;
    public static final int COL_GC_REPORTED_MS = 14;
    public static final int COL_CENSUS_ACTIVE = 15;
    /**
     * Which waypoint of a replayed route the frame belongs to, or {@link CaptureRoute#NO_STAGE}.
     *
     * <p>Appended rather than inserted so an existing analysis script keeps working: every earlier
     * column stays at its index. It is a frame label, not a counter, so the counter-averages section
     * skips it.
     */
    public static final int COL_ROUTE_STAGE = 16;

    public static final List<String> BASE_COLUMNS = List.of(
            "elapsed_ns", "frame_ns", "paused", "screen_open", "window_active", "width", "height",
            "player_x", "player_y", "player_z", "ffi_calls", "pipeline_compiles", "pipeline_compile_ns",
            "gc_collections", "gc_reported_ms", "census_active", "route_stage");

    /** Base columns that label a frame rather than measure it, so they are not averaged. */
    private static final List<String> FRAME_LABELS = List.of("route_stage");

    /** Index of the first backend-supplied column; the base columns come before it. */
    public static final int COL_FIRST_NATIVE = BASE_COLUMNS.size();

    /** First column the "counter/API averages" section summarises: everything from ffi_calls on. */
    public static final int COL_FIRST_COUNTER = COL_FFI_CALLS;

    private final List<String> columns;
    private final long[] data;
    private final int capacity;
    private int size;

    public PerformanceRecording(int capacity, List<String> nativeColumns) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        List<String> names = new ArrayList<>(BASE_COLUMNS);
        names.addAll(nativeColumns);
        this.columns = List.copyOf(names);
        this.data = new long[Math.multiplyExact(capacity, columns.size())];
        Arrays.fill(this.data, -1); // -1 is unavailable, particularly for the Vulkan baseline.
    }

    public void put(int column, long value) {
        if (full() || column < 0 || column >= columns.size()) throw new IndexOutOfBoundsException();
        data[size * columns.size() + column] = value;
    }

    public void commitFrame() {
        if (full()) throw new IllegalStateException("capture buffer full");
        size++;
    }

    public int size() { return size; }
    public boolean full() { return size == capacity; }

    public long value(int row, String name) {
        return value(row, columns.indexOf(name));
    }

    /** Sample value by column index; -1 when the row or column is outside the recording. */
    public long value(int row, int column) {
        if (row < 0 || row >= size || column < 0 || column >= columns.size()) return -1;
        return data[row * columns.size() + column];
    }

    /** Nearest-rank percentile over a sorted array. Empty input has no measurement. */
    public static double percentile(long[] sorted, double percentile) {
        if (sorted.length == 0) return Double.NaN;
        int index = Math.max(0, Math.min(sorted.length - 1,
                (int) Math.ceil(percentile * sorted.length) - 1));
        return sorted[index];
    }

    private List<Integer> gameplayRows() {
        List<Integer> rows = new ArrayList<>();
        for (int row = 0; row < size; row++) {
            if (value(row, COL_FRAME_NS) > 0 && value(row, COL_PAUSED) == 0
                    && value(row, COL_SCREEN_OPEN) == 0 && value(row, COL_WINDOW_ACTIVE) == 1) rows.add(row);
        }
        return rows;
    }

    public String summary(String metadata, String reason, CaptureRoute route) {
        List<Integer> rows = gameplayRows();
        long[] times = rows.stream().mapToLong(r -> value(r, "frame_ns")).sorted().toArray();
        StringBuilder out = new StringBuilder("MetalMod performance capture\n\n")
                .append(metadata).append("\nStopped: ").append(reason)
                .append("\nFrames saved: ").append(size)
                .append("\nGameplay frames: ").append(rows.size())
                .append(" (paused, menu and unfocused intervals excluded from statistics; retained in CSV)\n");
        if (times.length > 0) {
            double average = Arrays.stream(times).average().orElse(0) / 1e6;
            out.append(String.format(Locale.ROOT,
                    "\nAverage: %.3f ms (%.1f FPS)\nMedian: %.3f ms\np95: %.3f ms\np99: %.3f ms\nWorst: %.3f ms\n",
                    average, 1000 / average, percentile(times, .5) / 1e6,
                    percentile(times, .95) / 1e6, percentile(times, .99) / 1e6, times[times.length - 1] / 1e6));
            out.append("Frames >33.3 ms: ").append(Arrays.stream(times).filter(t -> t > 33_333_333).count())
                    .append("\nFrames >50 ms: ").append(Arrays.stream(times).filter(t -> t > 50_000_000).count())
                    .append("\nFrames >100 ms: ").append(Arrays.stream(times).filter(t -> t > 100_000_000).count()).append('\n');

            if (route != null && !route.isEmpty()) {
                appendStageTable(out, rows, route);
            }

            out.append("\nCounter/API averages and maxima per gameplay frame (- = unavailable):\n");
            for (String name : columns.subList(COL_FIRST_COUNTER, columns.size())) {
                if (FRAME_LABELS.contains(name)) continue;
                long[] values = rows.stream().mapToLong(r -> value(r, name)).filter(v -> v >= 0).toArray();
                if (values.length == 0) continue;
                double scale = name.endsWith("_ns") ? 1e6 : 1;
                out.append(String.format(Locale.ROOT, "  %s%s: avg %.3f, max %.3f\n", name,
                        scale > 1 ? " (ms)" : "", Arrays.stream(values).average().orElse(0) / scale,
                        Arrays.stream(values).max().orElse(0) / scale));
            }
            rows.sort(Comparator.<Integer>comparingLong(r -> value(r, "frame_ns")).reversed());
            out.append("\nWorst gameplay frames (row is the 1-based CSV sample number):\n")
                    .append("row   stage   at_s   frame_ms   Y   draws   submits   upload_KiB   upload_ms   create_ms   fence_ms   queue_ms   drawable_ms   compile_ms\n");
            for (int row : rows.subList(0, Math.min(10, rows.size()))) {
                out.append(String.format(Locale.ROOT, "%d  %d  %.3f  %.3f  %d  %s  %s  %s  %s  %s  %s  %s  %s  %s\n",
                        row + 1, value(row, COL_ROUTE_STAGE), value(row, "elapsed_ns") / 1e9,
                        value(row, "frame_ns") / 1e6,
                        value(row, "player_y"), scaled(row, "draws", 1), scaled(row, "submissions", 1),
                        scaled(row, "buffer_upload_bytes", 1024), scaled(row, "upload_api_ns", 1e6),
                        scaled(row, "command_buffer_create_ns", 1e6), scaled(row, "fence_wait_ns", 1e6),
                        scaled(row, "queue_wait_ns", 1e6), scaled(row, "drawable_wait_ns", 1e6),
                        scaled(row, "pipeline_compile_ns", 1e6)));
            }
        } else {
            out.append("\nNo uninterrupted gameplay samples. Load a world, resume play and capture again.\n");
        }
        out.append("""

                How to read this capture:
                - frame_ns is the wall interval between successive surface presentation calls. It
                  includes intervening ticks, pacing and waits. It is not GPU execution time.
                - Native fields cover calls on the rendering thread, including presentation, utility
                  submissions and clears. Other threads and actual GPU execution are not measured.
                - upload_api_ns includes staging allocation, command-buffer creation and commit;
                  copy_api_ns also includes creation/commit. Do NOT add these overlapping timings.
                - command_buffer_create_ns and commit_ns are full API durations, not pure stall time.
                  A long create call may mean queue backpressure. fence_wait_ns, queue_wait_ns and
                  drawable_wait_ns measure their respective waits (drawable wait also includes display pacing).
                - buffer_upload_bytes counts staged CPU writes; texture_upload_bytes counts supplied
                  row-stride bytes. GPU buffer-copy bytes are separate; do not equate copies with allocations.
                - gc_reported_ms is a JVM collection-time delta, not a precise stop-the-world pause.
                - -1 in CSV means unavailable. Vulkan/OpenGL supply frame/context/GC fields only.
                - route_stage is the replayed waypoint a frame belongs to, or -1 with no route. Frames
                  sharing a stage index across two captures are the same place doing the same thing,
                  which is what makes those two captures comparable.
                - High upload activity during a hitch is a correlation, not proof of its cause.
                  Compare repeated routes with the same resolution, distance, FPS cap and vsync.
                - A capture adds some timing overhead. No sample formatting or file writes occur while
                  recording. Positions are block coordinates, and all output stays on this machine.
                """);
        return out.toString();
    }

    private String scaled(int row, String name, double scale) {
        long value = value(row, name);
        return value < 0 ? "-" : String.format(Locale.ROOT, "%.3f", value / scale);
    }

    /**
     * Per-waypoint statistics, which is the point of a route: two captures of the same route can be
     * compared waypoint by waypoint instead of as one average over a scene that differed.
     *
     * <p>The mean position is printed next to the waypoint's own position deliberately. Teleports run
     * with their output suppressed, so a bad coordinate or dimension fails quietly; a stage whose mean
     * position is nowhere near its waypoint is the visible symptom of that.
     */
    private void appendStageTable(StringBuilder out, List<Integer> rows, CaptureRoute route) {
        out.append("\nPer-waypoint gameplay frames (").append(route.describe()).append("):\n")
                .append("stage  frames   mean_ms  median_ms     p95_ms     max_ms    mean_x    mean_y    mean_z   waypoint\n");
        for (int stage = 0; stage < route.stageCount(); stage++) {
            List<Integer> stageRows = new ArrayList<>();
            for (int row : rows) {
                if (value(row, COL_ROUTE_STAGE) == stage) stageRows.add(row);
            }
            if (stageRows.isEmpty()) continue;
            long[] times = stageRows.stream().mapToLong(r -> value(r, COL_FRAME_NS)).sorted().toArray();
            CaptureRoute.Waypoint waypoint = route.waypoint(stage);
            out.append(String.format(Locale.ROOT,
                    "%5d  %6d  %8.2f  %9.2f  %8.2f  %8.2f  %8.1f  %8.1f  %8.1f   %.1f %.1f %.1f (dwell %dms)\n",
                    stage, stageRows.size(), Arrays.stream(times).average().orElse(0) / 1e6,
                    percentile(times, .5) / 1e6, percentile(times, .95) / 1e6,
                    times[times.length - 1] / 1e6,
                    stageRows.stream().mapToLong(r -> value(r, COL_PLAYER_X)).average().orElse(0),
                    stageRows.stream().mapToLong(r -> value(r, COL_PLAYER_Y)).average().orElse(0),
                    stageRows.stream().mapToLong(r -> value(r, COL_PLAYER_Z)).average().orElse(0),
                    waypoint.x(), waypoint.y(), waypoint.z(), waypoint.dwellMs()));
        }
    }

    /** Only called by the writer after ownership of this recording leaves the render thread. */
    public Path write(Path root, String prefix, String metadata, String reason, CaptureRoute route)
            throws IOException {
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, prefix + "-");
        try (BufferedWriter out = Files.newBufferedWriter(directory.resolve("frames.csv"))) {
            out.write("frame," + String.join(",", columns));
            out.newLine();
            for (int row = 0; row < size; row++) {
                out.write(Integer.toString(row + 1));
                for (int col = 0; col < columns.size(); col++) {
                    out.write(',');
                    out.write(Long.toString(data[row * columns.size() + col]));
                }
                out.newLine();
            }
        }
        Files.writeString(directory.resolve("summary.txt"), summary(metadata, reason, route));
        return directory;
    }
}
