package net.metalmod.lighting;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Cluster-grid tests: the bounded, deterministic behaviour the plan requires before the GPU path can
 * be trusted. Runs without a game or a Metal device, so an eviction or an out-of-range index is
 * caught by arithmetic rather than by looking at a screen.
 */
public final class LightClusterTest {

    public static int runTests() {
        try {
            windowCoversTheSearchRadius();
            cellMappingIsStableUnderCameraMovement();
            lightsLandInTheCellsTheirVolumeTouches();
            fullCellsKeepTheStrongestAndCountTheRest();
            publishedIndicesAreAlwaysInRange();
            emptyAndOrphanedLights();
            aFullCellDoesNotDisturbItsNeighbour();
            largeWorldOriginsKeepTheirCells();
            System.out.println("PASS clustered light grid: bounds, stability, overflow, layout and origins");
            return 0;
        } catch (AssertionError error) {
            error.printStackTrace();
            return 1;
        }
    }

    /** A source within the collector's search radius must always fit inside the window. */
    private static void windowCoversTheSearchRadius() {
        require(LightClusterGrid.EXTENT >= 4 * LightCollector.SEARCH_RADIUS,
                "window half-extent must cover a source 2*radius away from the camera");
        require(LightClusterGrid.BYTES == 16 + LightClusterGrid.CELLS * (1 + LightClusterGrid.ENTRIES_PER_CELL) * 4,
                "cluster table layout: header, then one cell quad");
        require(LightClusterGrid.TEXELS <= 16384, "the data texture must fit one row of a 2D texture");
    }

    /**
     * The cell a fixed world point maps to must not change while the camera stays inside one cluster
     * cell, and the window must be snapped to the cluster size rather than following the camera
     * continuously - that is what stops illumination swimming.
     */
    private static void cellMappingIsStableUnderCameraMovement() {
        LightClusterGrid grid = new LightClusterGrid();
        PointLight light = new PointLight(10, 20, 30, 4, 1, 1, 1, 1);
        double first = 0;
        for (int step = 0; step < 8; step++) {
            grid.build(new LightSnapshot(1 + step, 2, 3, List.of(light)));
            double base = grid.baseRelativeX();
            // The origin is the camera-relative position of a 16-block boundary: a multiple of the
            // cluster size, offset by the camera.
            double world = 1 + step + base;
            require(Math.abs(world / LightClusterGrid.CLUSTER_SIZE
                    - Math.rint(world / LightClusterGrid.CLUSTER_SIZE)) < 1e-9,
                    "window origin is snapped to the cluster size");
            if (step == 0) {
                first = base;
            }
            require(Math.abs(base - first) <= LightClusterGrid.CLUSTER_SIZE,
                    "origin moves by at most one cluster while the camera stays inside one");
        }
    }

    /** Every cell a light's influence box overlaps must list it; cells it cannot reach must not. */
    private static void lightsLandInTheCellsTheirVolumeTouches() {
        LightClusterGrid grid = new LightClusterGrid();
        // Camera at the origin, so the window runs from -64 to +64 in each axis.
        PointLight near = new PointLight(0, 0, 0, 4, 1, 1, 1, 1);
        PointLight far = new PointLight(60, 60, 60, 4, 1, 1, 1, 1);
        grid.build(new LightSnapshot(0, 0, 0, List.of(near, far)));
        LightClusterGrid.Stats stats = grid.stats();
        require(stats.lights() == 2 && stats.orphaned() == 0, "both lights reach the window");
        require(stats.assigned() >= 2, "both lights are assigned to at least one cell");
        int centre = cellIndex(4, 4, 4);      // (0,0,0) sits in the middle cell of an 8-cell window
        require(grid.cellCount(centre) == 1 && grid.entry(centre, 0) == 0,
                "the near light owns the centre cell");
        int corner = cellIndex(7, 7, 7);
        require(grid.cellCount(corner) >= 1 && grid.entry(corner, 0) == 1,
                "the far light owns the far corner cell");
        require(grid.entry(centre, 1) == -1, "unused entries read back as absent");
    }

    /** A full cell keeps the strongest contributors, which are the earliest indices. */
    private static void fullCellsKeepTheStrongestAndCountTheRest() {
        LightClusterGrid grid = new LightClusterGrid();
        List<PointLight> lights = new ArrayList<>();
        int count = LightClusterGrid.ENTRIES_PER_CELL + 3;
        for (int index = 0; index < count; index++) {
            // All co-located, so they all want the same cell. Contribution order is list order.
            lights.add(new PointLight(0, 0, 0, 4, 1, 1, 1, 1));
        }
        grid.build(new LightSnapshot(0, 0, 0, lights));
        int centre = cellIndex(4, 4, 4);
        require(grid.cellCount(centre) == LightClusterGrid.ENTRIES_PER_CELL,
                "a cell never holds more entries than its bound");
        for (int entry = 0; entry < LightClusterGrid.ENTRIES_PER_CELL; entry++) {
            require(grid.entry(centre, entry) == entry, "entries keep contribution order and are bounded");
        }
        LightClusterGrid.Stats stats = grid.stats();
        require(stats.cellsOverflowing() >= 1 && stats.evicted() > 0,
                "overflow is counted rather than silent");
        require(stats.occupancyMax() == LightClusterGrid.ENTRIES_PER_CELL,
                "occupancy reports the bound actually reached");
    }

    /** No byte the grid publishes may address a record outside the light list. */
    private static void publishedIndicesAreAlwaysInRange() {
        LightClusterGrid grid = new LightClusterGrid();
        List<PointLight> lights = new ArrayList<>();
        for (int index = 0; index < LightSnapshot.CAPACITY; index++) {
            lights.add(new PointLight(index % 8 - 4, index % 5 - 2, index % 7 - 3, 6, 1, 1, 1, 1));
        }
        grid.build(new LightSnapshot(0, 0, 0, lights));
        float[] texels = grid.encodeTexels();
        require(texels.length == LightClusterGrid.TEXELS * 4, "texel block covers the declared table");
        int count = (int) texels[0];
        require(count == lights.size(), "header publishes the light count");
        require((int) texels[1] == LightClusterGrid.CLUSTERS_PER_AXIS
                && (int) texels[2] == LightClusterGrid.ENTRIES_PER_CELL, "header publishes the geometry");
        for (int cell = 0; cell < LightClusterGrid.CELLS; cell++) {
            int base = LightClusterGrid.cellBase(cell) * 4;
            int entries = (int) texels[base];
            require(entries >= 0 && entries <= LightClusterGrid.ENTRIES_PER_CELL, "cell count in range");
            for (int entry = 0; entry < LightClusterGrid.ENTRIES_PER_CELL; entry++) {
                int record = (int) texels[base + (1 + entry) * 4];
                require(record >= 0 && record < Math.max(1, count),
                        "cell " + cell + " entry " + entry + " -> record " + record);
                // Unused slots are clamped rather than left arbitrary, so even a consumer that read
                // past the count would stay inside the record region.
                require(record <= Math.max(0, count - 1), "unused slots stay clamped too");
            }
        }
        // The record region must be exactly as long as the header promises, or a clamped index could
        // still fall outside the texture.
        float[] records = grid.encodeRecords(new LightSnapshot(0, 0, 0, lights));
        require(LightClusterGrid.recordTexel(LightSnapshot.CAPACITY - 1) + 2 <= LightClusterGrid.TEXELS,
                "the table has room for a full light set");
        require(LightClusterGrid.recordTexel(0) + records.length / 4 == LightClusterGrid.TEXELS,
                "records exactly fill the tail of the table");
        require(LightClusterGrid.TEXELS_PER_ROW == LightClusterGrid.TEXELS
                && LightClusterGrid.TEXEL_ROWS == 1, "one row: a texel address is a flat index");
    }

    /**
     * A cell's entries must not disturb its neighbour.
     *
     * <p>This is the assertion the old layout needed and did not have: it packed a cell into a single
     * RGBA texel but wrote one float per entry, so the highest entry landed on the next cell's count
     * texel and was overwritten - the capacity was silently three however many the constant claimed,
     * and the shader's third entry read as a duplicate of the second. Only a full cell shows it, so a
     * full cell is what is published here.
     */
    private static void aFullCellDoesNotDisturbItsNeighbour() {
        LightClusterGrid grid = new LightClusterGrid();
        List<PointLight> lights = new ArrayList<>();
        for (int index = 0; index < LightClusterGrid.ENTRIES_PER_CELL; index++) {
            lights.add(new PointLight(0, 0, 0, 4, 1, 1, 1, 1));
        }
        grid.build(new LightSnapshot(0, 0, 0, lights));
        float[] texels = grid.encodeTexels();
        int cell = cellIndex(4, 4, 4);
        require((int) texels[LightClusterGrid.cellBase(cell) * 4] == LightClusterGrid.ENTRIES_PER_CELL,
                "a full cell publishes its full count");
        for (int entry = 0; entry < LightClusterGrid.ENTRIES_PER_CELL; entry++) {
            int record = (int) texels[LightClusterGrid.cellBase(cell) * 4 + (1 + entry) * 4];
            require(record == entry,
                    "entry " + entry + " of a full cell publishes its own record, not a duplicate");
        }
        require((int) texels[LightClusterGrid.cellBase(cell + 1) * 4] == 0,
                "the neighbouring cell's count survives its neighbour's last entry");
    }

    /** An empty set publishes an empty grid; a source outside the window is counted, not indexed. */
    private static void emptyAndOrphanedLights() {
        LightClusterGrid grid = new LightClusterGrid();
        grid.build(LightSnapshot.empty());
        LightClusterGrid.Stats empty = grid.stats();
        require(empty.lights() == 0 && empty.cellsTouched() == 0 && empty.evicted() == 0,
                "an empty set publishes an empty grid");
        int centre = cellIndex(4, 4, 4);
        require(grid.cellCount(centre) == 0, "an empty grid has no occupied cells");
        require((int) grid.encodeTexels()[0] == 0, "count zero reaches the GPU");
        require(grid.encodeTexels()[2] < 0, "an empty table marks itself for the shader");

        // 1000 blocks out: far outside a 128-block window.
        grid.build(new LightSnapshot(0, 0, 0,
                List.of(new PointLight(1000, 0, 0, 8, 1, 1, 1, 1))));
        LightClusterGrid.Stats orphaned = grid.stats();
        require(orphaned.lights() == 1 && orphaned.orphaned() == 1 && orphaned.assigned() == 0
                && orphaned.cellsTouched() == 0, "an unreachable light is counted, not clamped in");
    }

    /** Snapping to a world grid must work when the world coordinate is order 3e7. */
    private static void largeWorldOriginsKeepTheirCells() {
        LightClusterGrid grid = new LightClusterGrid();
        double worldX = 30_000_000.25;
        // 16 blocks east of the camera, which is where the light and the lit fragment both are.
        PointLight light = new PointLight(worldX + 16, 64.5, -30_000_000.75, 4, 1, 1, 1, 1);
        grid.build(new LightSnapshot(worldX, 64, -30_000_000.5, List.of(light)));
        require(grid.stats().orphaned() == 0 && grid.stats().assigned() > 0,
                "a near light at a large world origin is still inside the window");
        // The fragment beside the light is one cell to the +x side of the window centre.
        int fragmentCell = cellIndex(5, 4, 4);
        require(grid.cellCount(fragmentCell) >= 1 && grid.entry(fragmentCell, 0) == 0,
                "the cell containing the lit fragment lists the light");
        // Cell arithmetic is on the site: base - camera must cancel the 3e7 exactly in doubles.
        double base = worldX + grid.baseRelativeX();
        require(Math.abs(base - Math.rint(base / LightClusterGrid.CLUSTER_SIZE)
                * LightClusterGrid.CLUSTER_SIZE) < 1e-6, "origin is snapped in world space");
    }

    private static int cellIndex(int x, int y, int z) {
        return (z * LightClusterGrid.CLUSTERS_PER_AXIS + y) * LightClusterGrid.CLUSTERS_PER_AXIS + x;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
