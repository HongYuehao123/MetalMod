package net.metalmod.lighting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * A bounded uniform-grid cluster list over the camera-relative light set.
 *
 * <p>This is the CPU half of clustered forward lighting: every light's influence volume is assigned
 * to the cells it touches, and the GPU then evaluates only the entries of the cell a fragment lands
 * in instead of the whole light list. The grid is deliberately small and fully bounded — a fixed
 * {@link #CLUSTERS_PER_AXIS}<sup>3</sup> window centred on the camera, {@link #ENTRIES_PER_CELL}
 * entries per cell — because the plan's rule is that exhaustion must never produce out-of-bounds
 * reads or an arbitrary selection.
 *
 * <h2>Why the window is this size</h2>
 *
 * <p>The collector gathers sources within {@link LightCollector#SEARCH_RADIUS} of the camera, and a
 * source can illuminate a surface up to its own radius away, so an influencing source can sit
 * {@code 2 * SEARCH_RADIUS} from the camera. The window's half-extent is therefore
 * {@code EXTENT / 2 >= 2 * SEARCH_RADIUS}; a smaller window would drop edge lights and make their
 * illumination pop.
 *
 * <h2>Stability</h2>
 *
 * <p>The window origin is snapped to {@link #CLUSTER_SIZE} blocks — the chunk-section size — and
 * narrowed against the camera with the same subtraction the light records use. The cell a fragment
 * lands in is therefore stable as the camera moves: only the window's discrete position changes,
 * never the mapping inside it, so illumination cannot swim.
 *
 * <h2>Determinism under overflow</h2>
 *
 * <p>Lights arrive sorted by estimated contribution (the collector's order), so a full cell keeps the
 * strongest contributors and entries stay in contribution order. Ties break on stable source id in
 * the collector. Every eviction and every light whose influence volume misses the window entirely is
 * counted, and {@link #stats()} reports the numbers, so an exhausted grid is visible rather than
 * silent. {@link #serialize} never publishes a record index above the light count, so even a
 * corrupted count cannot make the shader read outside the record array.
 */
public final class LightClusterGrid {

    /**
     * Extent of the square window, in blocks, centred on the camera.
     *
     * <p>{@code EXTENT >= 4 * LightCollector.SEARCH_RADIUS} is the invariant that keeps every
     * influencing source inside the window. {@code LightClusterTest} asserts it.
     */
    public static final double EXTENT = 128;
    public static final int CLUSTERS_PER_AXIS = 8;
    public static final double CLUSTER_SIZE = EXTENT / CLUSTERS_PER_AXIS;
    /**
     * How many lights may light one cell.
     *
     * <p>Chosen from the contribution model rather than from a byte budget: the per-fragment sum is
     * clamped to 1, and each light contributes at most its intensity times its falloff squared, so a
     * spot reached by this many sources is saturated in practice and any further source would be
     * provably wasted work. It is a cap on lights <em>per cell</em>, not on lights in the world - the
     * snapshot capacity is what bounds how many exist at once, and the two are independent.
     *
     * <p>The shader breaks out of its loop at the cell's actual entry count, so a sparse cell costs
     * the same as it did at a smaller cap; only a genuinely crowded cell evaluates this many.
     */
    public static final int ENTRIES_PER_CELL = 16;
    public static final int CELLS = CLUSTERS_PER_AXIS * CLUSTERS_PER_AXIS * CLUSTERS_PER_AXIS;
    /** ivec4 header, then one ivec4 per cell. */
    public static final int HEADER_SLOTS = 4;
    private static final int CELL_STRIDE = 1 + ENTRIES_PER_CELL;
    public static final int SLOTS = HEADER_SLOTS + CELLS * CELL_STRIDE;
    public static final int BYTES = SLOTS * 4;

    /** Records the data texture has room for: one light set's worst case, plus slack. */
    public static final int MAX_RECORDS = LightSnapshot.CAPACITY + 8;
    /**
     * GPU layout: one header texel, then one count texel plus one index texel per entry for each
     * cell, then two RGBA texels per light record.
     *
     * <p><b>One index per texel, deliberately.</b> Packing four indices into one RGBA texel would be
     * four times smaller, but reading the k-th component of a vec4 needs a constant index in GLSL, and
     * the entry number here is a loop variable. A dynamic <em>texel</em> coordinate is fine; a dynamic
     * <em>component</em> is not. The earlier packing worked around that by reading {@code .y}, {@code
     * .z} and {@code .w} from a single texel - which is exactly three entries however many the cell
     * claimed to hold, so the constant said four, the encoder wrote five floats per cell, and the
     * fourth landed on the next cell's count texel and was overwritten.
     */
    public static final int HEADER_TEXELS = 1;
    public static final int CELL_TEXELS = 1 + ENTRIES_PER_CELL;
    public static final int CELLS_TEXELS = CELLS * CELL_TEXELS;
    public static final int RECORD_BASE = HEADER_TEXELS + CELLS_TEXELS;
    public static final int RECORD_TEXELS = MAX_RECORDS * 2;
    public static final int TEXELS = RECORD_BASE + RECORD_TEXELS;
    /** One row holds the header, every cell and every record, so a texel address is a flat index. */
    public static final int TEXELS_PER_ROW = TEXELS;
    public static final int TEXEL_ROWS = 1;

    public static final String GRID_UNIFORM = "MetalModLightGrid";
    public static final String DATA_UNIFORM = "MetalModLightData";
    /** The bounded light list itself, unchanged from 6B, so both variants share one publication. */
    public static final String LIST_UNIFORM = LightSnapshot.UNIFORM;

    /**
     * Header (light count, clusters per axis, entries per cell, cluster size) followed by one
     * {@code ivec4} per cell: entry count, then the light record index of each entry in contribution
     * order. Read on the GPU through {@code floatBitsToInt}, which is exact for these small integers.
     */
    private final int[] slots = new int[SLOTS];
    private final boolean[] overflowed = new boolean[CELLS];
    private final boolean[] lightSeen = new boolean[LightSnapshot.CAPACITY];
    private int lightsWithACell;

    private double baseRelativeX, baseRelativeY, baseRelativeZ;
    private int lightCount;
    private int assigned;
    private int evicted;
    private int cellsTouched;
    private int cellsOverflowing;
    private int orphaned;

    /** Snapshot of one build, for the F3 line and the tests. */
    public record Stats(int lights, int assigned, int orphaned, int cellsTouched,
                        int cellsOverflowing, int evicted, int occupancyMax, int occupancyMeanTimes100) {}

    public LightClusterGrid() {
        clear();
    }

    public int lightCount() { return this.lightCount; }
    public double baseRelativeX() { return this.baseRelativeX; }
    public double baseRelativeY() { return this.baseRelativeY; }
    public double baseRelativeZ() { return this.baseRelativeZ; }

    /** Publish an empty grid, as if no source were active. */
    public void clear() {
        Arrays.fill(this.slots, 0);
        Arrays.fill(this.overflowed, false);
        this.slots[0] = 0;
        this.slots[1] = CLUSTERS_PER_AXIS;
        this.slots[2] = ENTRIES_PER_CELL;
        this.slots[3] = (int) CLUSTER_SIZE;
        this.lightCount = 0;
        this.assigned = 0;
        this.evicted = 0;
        this.cellsTouched = 0;
        this.cellsOverflowing = 0;
        this.orphaned = 0;
        this.baseRelativeX = 0;
        this.baseRelativeY = 0;
        this.baseRelativeZ = 0;
        this.lightsWithACell = 0;
        Arrays.fill(this.lightSeen, false);
    }

    public Stats stats() {
        int max = 0;
        long total = 0;
        for (int cell = 0; cell < CELLS; cell++) {
            int count = this.slots[HEADER_SLOTS + cell * CELL_STRIDE];
            max = Math.max(max, count);
            total += count;
        }
        int mean = this.cellsTouched == 0 ? 0 : (int) (total * 100 / this.cellsTouched);
        return new Stats(this.lightCount, this.assigned, this.orphaned, this.cellsTouched,
                this.cellsOverflowing, this.evicted, max, mean);
    }

    /** The record index stored at one cell entry, or -1 when that entry is unused. */
    public int entry(int cell, int entry) {
        if (cell < 0 || cell >= CELLS || entry < 0 || entry >= ENTRIES_PER_CELL) {
            throw new IndexOutOfBoundsException("cell " + cell + " entry " + entry);
        }
        int base = HEADER_SLOTS + cell * CELL_STRIDE;
        return entry < this.slots[base] ? this.slots[base + 1 + entry] : -1;
    }

    public int cellCount(int cell) {
        return this.slots[HEADER_SLOTS + cell * CELL_STRIDE];
    }

    /**
     * Rebuild the grid for one camera-relative light set, in contribution order (strongest first).
     *
     * <p>The origin is snapped to the cluster size and narrowed against the camera last, because
     * {@code (double) (3.0e7 - 3.0e7)} is exact while {@code (float) 3.0e7} is not.
     */
    public void build(LightSnapshot snapshot) {
        List<PointLight> lights = snapshot.lights();
        clear();
        this.lightCount = lights.size();
        this.slots[0] = lights.size();

        double baseX = Math.floor((snapshot.cameraX() - EXTENT / 2) / CLUSTER_SIZE) * CLUSTER_SIZE;
        double baseY = Math.floor((snapshot.cameraY() - EXTENT / 2) / CLUSTER_SIZE) * CLUSTER_SIZE;
        double baseZ = Math.floor((snapshot.cameraZ() - EXTENT / 2) / CLUSTER_SIZE) * CLUSTER_SIZE;
        this.baseRelativeX = baseX - snapshot.cameraX();
        this.baseRelativeY = baseY - snapshot.cameraY();
        this.baseRelativeZ = baseZ - snapshot.cameraZ();

        for (int index = 0; index < lights.size(); index++) {
            PointLight light = lights.get(index);
            double radius = light.radius();
            // Unclamped cell range first. A light whose influence box misses the window entirely must
            // be counted as unreachable, not clamped into an edge cell it cannot actually light.
            int rawMinX = rawCell(light.x() - radius - baseX);
            int rawMinY = rawCell(light.y() - radius - baseY);
            int rawMinZ = rawCell(light.z() - radius - baseZ);
            int rawMaxX = rawCell(light.x() + radius - baseX);
            int rawMaxY = rawCell(light.y() + radius - baseY);
            int rawMaxZ = rawCell(light.z() + radius - baseZ);
            if (rawMaxX < 0 || rawMinX >= CLUSTERS_PER_AXIS
                    || rawMaxY < 0 || rawMinY >= CLUSTERS_PER_AXIS
                    || rawMaxZ < 0 || rawMinZ >= CLUSTERS_PER_AXIS) {
                continue;
            }
            int minX = clampCell(rawMinX);
            int minY = clampCell(rawMinY);
            int minZ = clampCell(rawMinZ);
            int maxX = clampCell(rawMaxX);
            int maxY = clampCell(rawMaxY);
            int maxZ = clampCell(rawMaxZ);
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        assign(index, (z * CLUSTERS_PER_AXIS + y) * CLUSTERS_PER_AXIS + x);
                    }
                }
            }
        }
        this.orphaned = lights.size() - this.lightsWithACell;
    }

    private static int rawCell(double coordinate) {
        return (int) Math.floor(coordinate / CLUSTER_SIZE);
    }

    private static int clampCell(int cell) {
        return Math.max(0, Math.min(CLUSTERS_PER_AXIS - 1, cell));
    }

    /** Add a light to one cell, keeping the strongest and counting what it displaces. */
    private void assign(int lightIndex, int cell) {
        int base = HEADER_SLOTS + cell * CELL_STRIDE;
        int used = this.slots[base];
        for (int entry = 0; entry < used; entry++) {
            if (this.slots[base + 1 + entry] == lightIndex) {
                return;   // already there through another axis of the same box
            }
        }
        if (!this.lightSeen[lightIndex]) {
            this.lightSeen[lightIndex] = true;
            this.lightsWithACell++;
        }
        if (used < ENTRIES_PER_CELL) {
            // Entries stay in index order, which is contribution order, so entry 0 is the strongest.
            this.slots[base + 1 + used] = lightIndex;
            this.slots[base] = used + 1;
            this.assigned++;
            if (used == 0) {
                this.cellsTouched++;
            }
            return;
        }
        // Full cell: the light being added is the weakest that reaches this cell, so drop it.
        this.evicted++;
        if (!this.overflowed[cell]) {
            this.overflowed[cell] = true;
            this.cellsOverflowing++;
        }
    }

    /**
     * Encode the block for the GPU.
     *
     * <p>Every published slot holds a valid record index: unused slots are zero, a cell's count never
     * exceeds the entries written after it, and no index above the light count is ever stored. A
     * consumer that clamps by the count as well therefore cannot read outside the record array even
     * if the two buffers were to disagree.
     */
    /**
     * Pack the header and the cell table into an RGBA32F texel block.
     *
     * <p>A texture rather than an indexed uniform array: the table is tens of kilobytes, past a
     * uniform buffer's portable budget on this backend, and {@code texelFetch} addresses it with plain
     * integer arithmetic instead of relying on array stride rules inside a uniform block.
     *
     * <p>Texel 0 is four scalars — light count, clusters per axis, entries per cell (negative marks
     * "nothing published"), cluster size. Texel {@code 1 + cell} holds a cell's entry count in
     * {@code x} and its record indices in {@code yzw}. Record indices are clamped to the published
     * light count, so no texel can send the shader outside the record region.
     */
    public float[] encodeTexels() {
        int bound = Math.max(0, this.lightCount - 1);
        float[] texels = new float[TEXELS * 4];
        texels[0] = this.lightCount;
        texels[1] = CLUSTERS_PER_AXIS;
        texels[2] = this.lightCount == 0 ? -ENTRIES_PER_CELL : ENTRIES_PER_CELL;
        texels[3] = (int) CLUSTER_SIZE;
        for (int cell = 0; cell < CELLS; cell++) {
            int base = HEADER_SLOTS + cell * CELL_STRIDE;
            int out = cellBase(cell) * 4;
            texels[out] = this.slots[base];
            int used = this.slots[base];
            for (int entry = 0; entry < ENTRIES_PER_CELL; entry++) {
                // Only the entries the cell actually holds are written. A cell's slots are its own
                // texels now, so there is nothing beyond them to overwrite - which is the property the
                // old packing broke.
                texels[out + (1 + entry) * 4] = entry < used
                        ? Math.max(0, Math.min(bound, this.slots[base + 1 + entry])) : 0;
            }
        }
        return texels;
    }

    /** Bytes one published table occupies on the GPU, for the scaling record's upload accounting. */
    public int encodedBytes() {
        return TEXELS * 16;
    }

    /** The first texel of a cell's own block: its entry count, then one texel per entry. */
    public static int cellBase(int cell) {
        return HEADER_TEXELS + cell * CELL_TEXELS;
    }

    /** The record region of the same texture: two RGBA texels per camera-relative light. */
    public float[] encodeRecords(LightSnapshot snapshot) {
        java.util.List<PointLight> lights = snapshot.lights();
        float[] texels = new float[MAX_RECORDS * 2 * 4];
        for (int index = 0; index < lights.size() && index < MAX_RECORDS; index++) {
            PointLight light = lights.get(index);
            int base = index * 8;
            texels[base] = (float) (light.x() - snapshot.cameraX());
            texels[base + 1] = (float) (light.y() - snapshot.cameraY());
            texels[base + 2] = (float) (light.z() - snapshot.cameraZ());
            texels[base + 3] = light.radius();
            texels[base + 4] = light.red();
            texels[base + 5] = light.green();
            texels[base + 6] = light.blue();
            texels[base + 7] = light.intensity();
        }
        return texels;
    }

    /** Where a record's position/radius texel starts, in flat texel units. */
    public static int recordTexel(int record) {
        return RECORD_BASE + record * 2;
    }

    /**
     * The cluster window as one vec4: origin in the camera-relative float frame, then cell size.
     *
     * <p>The origin is narrowed against the camera, so the shader compares it with the interpolated
     * camera-relative position directly - at any world coordinate, including one of order 3e7.
     */
    public ByteBuffer encodeGridUniform() {
        return ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
                .putFloat((float) this.baseRelativeX)
                .putFloat((float) this.baseRelativeY)
                .putFloat((float) this.baseRelativeZ)
                .putFloat((float) CLUSTER_SIZE)
                .flip();
    }
}
