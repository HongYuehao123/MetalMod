package net.metalmod.debug;

import net.metalmod.backend.MetalDevice;

/**
 * The capture's lighting columns must mean what their names say.
 *
 * <p>Each field of the stats record is given a distinct value, so a name and a value that disagree
 * cannot pass by coincidence - which is the only way this mistake surfaces in a real capture: a column
 * that reads plausibly and measures something else.
 */
public final class LightingCaptureColumnsTest {

    public static int runTests() {
        try {
            // Distinct per field, in declaration order, so a swapped pair shows up as a mismatch.
            MetalDevice.LightingStats stats = new MetalDevice.LightingStats(
                    true, true, 101, 102, 103, 104, 105, 106L, 107, 108L, 109, 110L,
                    111, 112, 113, 114, 115, 116);
            String[] expected = {
                    "light_enabled", "light_clustered", "light_published", "light_dropped",
                    "light_buried", "light_examined", "light_allocated", "light_extract_ns",
                    "light_cluster_builds", "light_cluster_build_ns", "light_uploads",
                    "light_upload_bytes", "light_occupancy_max", "light_occupancy_mean_x100",
                    "light_cells_touched", "light_overflowed", "light_evicted", "light_unreachable"};
            require(LightingCaptureColumns.NAMES.size() == expected.length,
                    "the column list matches the mapped values: " + LightingCaptureColumns.NAMES.size()
                            + " names, " + expected.length + " mapped");
            for (int i = 0; i < expected.length; i++) {
                require(expected[i].equals(LightingCaptureColumns.NAMES.get(i)),
                        "column " + i + " is " + expected[i]);
            }
            require(LightingCaptureColumns.value(stats, 0) == 1, "enabled reads as 1");
            require(LightingCaptureColumns.value(stats, 1) == 1, "clustered reads as 1");
            // Fields are 101..116 from column 2 on, so column i must read 99 + i: a swapped pair
            // would land on a neighbour's value and fail here.
            for (int i = 2; i < expected.length; i++) {
                require(LightingCaptureColumns.value(stats, i) == 99 + i,
                        expected[i] + " carries its own field, not a neighbour's");
            }
            require(LightingCaptureColumns.value(stats, expected.length) == -1,
                    "an index past the list reports unavailable rather than a stale number");

            // The flag columns are booleans, so the false case has to read as 0 rather than as "off by
            // omission" - a capture where disabled frames carried no value would be unreadable.
            MetalDevice.LightingStats off = new MetalDevice.LightingStats(
                    false, false, 0, 0, 0, 0, 0, 0L, 0, 0L, 0, 0L, 0, 0, 0, 0, 0, 0);
            require(LightingCaptureColumns.value(off, 0) == 0
                    && LightingCaptureColumns.value(off, 1) == 0, "disabled reads as 0, not by omission");
            System.out.println("PASS lighting capture columns: names, order and values agree");
            return 0;
        } catch (AssertionError error) {
            error.printStackTrace();
            return 1;
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
