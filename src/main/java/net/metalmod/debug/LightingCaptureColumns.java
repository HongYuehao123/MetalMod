package net.metalmod.debug;

import net.metalmod.backend.MetalDevice;

import java.util.List;

/**
 * The lighting columns the performance capture adds, and the mapping from a column to its value.
 *
 * <p>Kept out of {@link PerformanceCapture} so it can be tested without a game: a column whose name
 * and value disagree is a measurement that reads correctly and means nothing, which is the failure
 * mode a capture is least likely to reveal and most likely to be trusted.
 */
public final class LightingCaptureColumns {

    /**
     * Appended after the native columns rather than merged, so the native indices keep their positions
     * and an analysis script written against an earlier capture still reads the same columns.
     */
    public static final List<String> NAMES = List.of(
            "light_enabled", "light_clustered", "light_published", "light_dropped", "light_buried",
            "light_examined", "light_allocated", "light_extract_ns", "light_cluster_builds",
            "light_cluster_build_ns", "light_uploads", "light_upload_bytes", "light_occupancy_max",
            "light_occupancy_mean_x100", "light_cells_touched", "light_overflowed", "light_evicted",
            "light_unreachable", "light_entity_query_ns", "light_block_index_ns");

    private LightingCaptureColumns() {}

    /** The value for one column, or -1 when the index is outside the list. */
    public static long value(MetalDevice.LightingStats stats, int index) {
        return switch (index) {
            case 0 -> stats.enabled() ? 1 : 0;
            case 1 -> stats.clustered() ? 1 : 0;
            case 2 -> stats.published();
            case 3 -> stats.dropped();
            case 4 -> stats.buried();
            case 5 -> stats.examined();
            case 6 -> stats.allocated();
            case 7 -> stats.extractNanos();
            case 8 -> stats.clusterBuilds();
            case 9 -> stats.clusterBuildNanos();
            case 10 -> stats.uploads();
            case 11 -> stats.uploadBytes();
            case 12 -> stats.occupancyMax();
            case 13 -> stats.occupancyMean100();
            case 14 -> stats.cellsTouched();
            case 15 -> stats.overflowed();
            case 16 -> stats.evicted();
            case 17 -> stats.unreachable();
            case 18 -> stats.entityQueryNanos();
            case 19 -> stats.blockIndexNanos();
            default -> -1;
        };
    }
}
