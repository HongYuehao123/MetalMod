package net.metalmod.debug;

import net.metalmod.backend.MetalNative;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class PerformanceRecordingTest {
    public static int runTests() {
        System.out.println("[TEST] Performance capture statistics, export and native ABI");
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            // The capture writes samples by column index and the header is generated from
            // BASE_COLUMNS, so pin every index to its name. Without this, inserting a base column
            // would shift every value silently and no test would notice.
            checkColumn(PerformanceRecording.COL_ELAPSED_NS, "elapsed_ns");
            checkColumn(PerformanceRecording.COL_FRAME_NS, "frame_ns");
            checkColumn(PerformanceRecording.COL_PAUSED, "paused");
            checkColumn(PerformanceRecording.COL_SCREEN_OPEN, "screen_open");
            checkColumn(PerformanceRecording.COL_WINDOW_ACTIVE, "window_active");
            checkColumn(PerformanceRecording.COL_WIDTH, "width");
            checkColumn(PerformanceRecording.COL_HEIGHT, "height");
            checkColumn(PerformanceRecording.COL_PLAYER_X, "player_x");
            checkColumn(PerformanceRecording.COL_PLAYER_Y, "player_y");
            checkColumn(PerformanceRecording.COL_PLAYER_Z, "player_z");
            checkColumn(PerformanceRecording.COL_FFI_CALLS, "ffi_calls");
            checkColumn(PerformanceRecording.COL_PIPELINE_COMPILES, "pipeline_compiles");
            checkColumn(PerformanceRecording.COL_PIPELINE_COMPILE_NS, "pipeline_compile_ns");
            checkColumn(PerformanceRecording.COL_GC_COLLECTIONS, "gc_collections");
            checkColumn(PerformanceRecording.COL_GC_REPORTED_MS, "gc_reported_ms");
            checkColumn(PerformanceRecording.COL_CENSUS_ACTIVE, "census_active");
            check(PerformanceRecording.COL_FIRST_NATIVE == PerformanceRecording.BASE_COLUMNS.size()
                    && PerformanceRecording.COL_FIRST_NATIVE == PerformanceRecording.COL_CENSUS_ACTIVE + 1,
                    "native columns start immediately after the last base column");
            PerformanceRecording data = new PerformanceRecording(5, List.of("submissions"));
            sample(data, 10_000_000, 0, 0, 1);
            sample(data, 20_000_000, 0, 0, 1);
            sample(data, 900_000_000, 1, 0, 1);
            sample(data, 800_000_000, 0, 1, 1);
            sample(data, 700_000_000, 0, 0, 0);
            check(data.full(), "sample capacity must bound memory");
            String summary = data.summary("Backend: Vulkan", "test");
            check(summary.contains("Gameplay frames: 2"), "exclude paused/menu/unfocused intervals");
            check(summary.contains("Average: 15.000 ms"), "average must exclude menu stalls and use a dot");
            check(summary.contains("p99: 20.000 ms"), "nearest-rank percentile");
            check(summary.contains("Worst: 20.000 ms"), "worst gameplay frame");
            check(Double.isNaN(PerformanceRecording.percentile(new long[0], .99)), "empty percentile");
            PerformanceRecording empty = new PerformanceRecording(1, List.of());
            check(empty.summary("", "test").contains("No uninterrupted gameplay"), "empty report");

            Path root = Files.createTempDirectory("metalmod-capture-test-");
            try {
                Path output = data.write(root, "test", "Backend: Vulkan", "test");
                List<String> csv = Files.readAllLines(output.resolve("frames.csv"));
                check(csv.size() == 6, "CSV preserves all samples including excluded ones");
                check(csv.get(0).startsWith("frame,elapsed_ns,frame_ns,"), "column header");
                check(csv.get(1).endsWith(",-1"), "unavailable native metrics must not become zero");
                check(csv.get(3).startsWith("3,3000000,900000000,1,"), "raw hitch retained at correct row");
                check(Files.readString(output.resolve("summary.txt")).equals(summary), "report persisted");
                Files.delete(output.resolve("frames.csv"));
                Files.delete(output.resolve("summary.txt"));
                Files.delete(output);
            } finally {
                Files.delete(root);
            }

            check(MetalNative.captureAvailable(), "native capture symbols");
            try (Arena arena = Arena.ofConfined()) {
                var sample = arena.allocate(ValueLayout.JAVA_LONG, MetalNative.CAPTURE_METRICS.size());
                var device = MetalNative.deviceCreate();
                check(device.address() != 0, "Metal device unavailable (run native tests outside the sandbox)");
                var queue = MetalNative.queueCreate(device);
                MetalNative.captureSetEnabled(true);
                try {
                    MetalNative.queueSynchronize(queue);
                    MetalNative.captureReadReset(sample);
                    check(sample.getAtIndex(ValueLayout.JAVA_LONG,
                            MetalNative.CAPTURE_METRICS.indexOf("submissions")) == 1, "native snapshot ABI order");
                    MetalNative.captureReadReset(sample);
                    for (int i = 0; i < MetalNative.CAPTURE_METRICS.size(); i++) {
                        check(sample.getAtIndex(ValueLayout.JAVA_LONG, i) == 0, "native frame reset");
                    }
                } finally {
                    MetalNative.captureSetEnabled(false);
                    MetalNative.queueRelease(queue);
                    MetalNative.deviceRelease(device);
                }
            }
            System.out.println("PASS capture statistics, CSV export, unsupported metrics and native ABI");
            return 0;
        } catch (Throwable error) {
            error.printStackTrace();
            return 1;
        } finally {
            Locale.setDefault(original);
        }
    }

    private static void sample(PerformanceRecording data, long nanos, int paused, int menu, int focused) {
        data.put(0, (data.size() + 1L) * 1_000_000);
        data.put(1, nanos);
        data.put(2, paused);
        data.put(3, menu);
        data.put(4, focused);
        data.commitFrame();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void checkColumn(int index, String name) {
        String actual = PerformanceRecording.BASE_COLUMNS.get(index);
        check(actual.equals(name), "column " + index + " must be " + name + " but is " + actual);
    }
}
