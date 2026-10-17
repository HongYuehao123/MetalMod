import net.metalmod.lighting.LightClusterGrid;
import net.metalmod.lighting.LightSnapshot;
import net.metalmod.lighting.PointLight;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** CPU microbenchmark of existing lighting APIs; no Minecraft process or Metal device is created. */
public final class LightingBenchmark {
    private static volatile Object sink;
    private static volatile long checksum;
    private static int assertions;
    private static final int VARIANTS = 16;
    private static final long WARMUP_MS = Long.getLong("lighting.bench.warmupMs", 300);
    private static final long SAMPLE_MS = Long.getLong("lighting.bench.sampleMs", 75);
    private static final int SAMPLES = Integer.getInteger("lighting.bench.samples", 7);

    private record Scenario(String name, LightSnapshot[] snapshots) {}
    @FunctionalInterface private interface Operation { void run(int iteration); }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        require(WARMUP_MS > 0 && SAMPLE_MS > 0 && SAMPLES >= 3, "valid benchmark durations");
        List<Scenario> scenarios = new ArrayList<>();
        scenarios.add(scenario("empty", 0, false, false));
        for (int count : new int[] {1, 16, 64}) {
            scenarios.add(scenario("scattered_" + count, count, false, false));
            scenarios.add(scenario("overlapping_" + count, count, true, false));
        }
        scenarios.add(scenario("synthetic_full_window_64", 64, true, true));
        for (Scenario scenario : scenarios) {
            for (LightSnapshot snapshot : scenario.snapshots()) verify(snapshot);
        }
        System.out.println("LIGHTING BENCHMARK CORRECTNESS PASSED: " + assertions + " assertions");
        if (Arrays.asList(args).contains("--verify-only")) return;

        System.out.printf("# java=%s; vm=%s; os=%s/%s; processors=%d%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors());
        System.out.printf("# JVM arguments=%s%n", ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.printf("# warmup_ms=%d; sample_ms=%d; samples=%d; fixed_camera_variants=%d%n",
                WARMUP_MS, SAMPLE_MS, SAMPLES, VARIANTS);
        System.out.println("# Timings are CPU wall-time per operation averaged within each batch; p95 is across batch means, not frame-time p95.");
        System.out.println("# Allocation, GC and direct-buffer cleanup are included; source discovery, snapshots, GPU upload and rendering are excluded.");
        System.out.println("scenario,lights,operation,median_us_per_op,p95_batch_us_per_op,min_us_per_op,max_us_per_op,total_measured_ops,cells_touched,assignments,evictions,max_occupancy,direct_payload_bytes_per_op");
        for (Scenario scenario : scenarios) {
            LightSnapshot[] snapshots = scenario.snapshots();
            LightClusterGrid grid = new LightClusterGrid();
            grid.build(snapshots[0]);
            LightClusterGrid.Stats stats = grid.stats();
            int count = snapshots[0].lights().size();
            measure(scenario, "snapshot_encode", i -> sink = snapshots[i & (VARIANTS - 1)].encode(),
                    stats, LightSnapshot.BYTES + count * PointLight.BYTES);
            measure(scenario, "grid_build", i -> {
                grid.build(snapshots[i & (VARIANTS - 1)]);
                sink = grid;
            }, stats, 0);
            grid.build(snapshots[0]);
            measure(scenario, "cluster_pack_cpu", i -> {
                // Matches the CPU packing in MetalDevice.uploadClusterTexture, without GPU upload.
                float[] texels = grid.encodeTexels();
                float[] records = grid.encodeRecords(snapshots[0]);
                System.arraycopy(records, 0, texels, LightClusterGrid.recordTexel(0) * 4, records.length);
                ByteBuffer bytes = ByteBuffer.allocateDirect(texels.length * Float.BYTES)
                        .order(ByteOrder.nativeOrder());
                bytes.asFloatBuffer().put(texels);
                sink = bytes;
                sink = grid.encodeGridUniform();
            }, stats, grid.encodedBytes() + 16);
        }
        System.out.println("# checksum=" + checksum);
        System.out.println("LIGHTING BENCHMARK COMPLETE");
    }

    private static Scenario scenario(String name, int count, boolean overlapping, boolean fullWindow) {
        LightSnapshot[] snapshots = new LightSnapshot[VARIANTS];
        // Fixed seed, no random generator or source allocation inside a measured operation.
        for (int variant = 0; variant < VARIANTS; variant++) {
            double cameraX = 30_000_000.25 + variant / 32.0;
            double cameraY = 64.25 + variant / 64.0;
            double cameraZ = -30_000_000.75 + variant / 32.0;
            List<PointLight> lights = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                double x = overlapping ? 2 : ((index * 17) % 49) - 24;
                double y = overlapping ? 2 : ((index * 11) % 21) - 10;
                double z = overlapping ? 2 : ((index * 23) % 49) - 24;
                lights.add(new PointLight(cameraX + x, cameraY + y, cameraZ + z,
                        fullWindow ? 128 : 8, 1, 0.75f, 0.5f, 1 - index / 128.0f));
            }
            snapshots[variant] = new LightSnapshot(cameraX, cameraY, cameraZ, lights);
        }
        return new Scenario(name, snapshots);
    }

    private static void measure(Scenario scenario, String operation, Operation action,
                                LightClusterGrid.Stats stats, int directPayloadBytes) {
        batch(action, WARMUP_MS);
        double[] samples = new double[SAMPLES];
        long operations = 0;
        for (int sample = 0; sample < SAMPLES; sample++) {
            long[] measured = batch(action, SAMPLE_MS);
            samples[sample] = measured[0] / (double) measured[1] / 1_000;
            operations += measured[1];
        }
        Arrays.sort(samples);
        System.out.printf("%s,%d,%s,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%d%n",
                scenario.name(), scenario.snapshots()[0].lights().size(), operation,
                samples[SAMPLES / 2], samples[(int) Math.ceil(SAMPLES * 0.95) - 1], samples[0],
                samples[SAMPLES - 1], operations, stats.cellsTouched(), stats.assigned(),
                stats.evicted(), stats.occupancyMax(), directPayloadBytes);
    }

    private static long[] batch(Operation action, long milliseconds) {
        long started = System.nanoTime();
        long elapsed;
        int iterations = 0;
        // Check the clock only once per 32 operations, not once per tiny grid rebuild.
        do {
            for (int i = 0; i < 32; i++) action.run(iterations++);
            elapsed = System.nanoTime() - started;
        } while (elapsed < milliseconds * 1_000_000);
        // Volatile publication makes every result escape. Also read real output after each batch.
        Object result = sink;
        if (result instanceof ByteBuffer bytes) checksum += bytes.getInt(0);
        if (result instanceof LightClusterGrid grid) {
            checksum += grid.lightCount() + grid.cellCount((iterations / 32) % LightClusterGrid.CELLS);
        }
        return new long[] {elapsed, iterations};
    }

    private static void verify(LightSnapshot snapshot) {
        LightClusterGrid grid = new LightClusterGrid();
        grid.build(snapshot);
        float[] texels = grid.encodeTexels();
        float[] records = grid.encodeRecords(snapshot);
        ByteBuffer encoded = snapshot.encode();
        ByteBuffer origin = grid.encodeGridUniform();
        int count = snapshot.lights().size();
        require(encoded.remaining() == LightSnapshot.BYTES, "snapshot size");
        require(encoded.getInt(0) == count && encoded.getInt(4) == LightSnapshot.ABI_VERSION, "snapshot header");
        require(texels.length == LightClusterGrid.TEXELS * 4, "texture length");
        require(texels[0] == count && texels[1] == LightClusterGrid.CLUSTERS_PER_AXIS, "texture header");
        require(texels[2] == (count == 0 ? -1 : 1) * LightClusterGrid.ENTRIES_PER_CELL, "empty marker");
        require(origin.remaining() == 16 && origin.getFloat(12) == LightClusterGrid.CLUSTER_SIZE, "grid uniform");
        double baseX = snapshot.cameraX() + grid.baseRelativeX();
        double baseY = snapshot.cameraY() + grid.baseRelativeY();
        double baseZ = snapshot.cameraZ() + grid.baseRelativeZ();
        require(baseX % LightClusterGrid.CLUSTER_SIZE == 0 && baseY % LightClusterGrid.CLUSTER_SIZE == 0
                && baseZ % LightClusterGrid.CLUSTER_SIZE == 0, "large-world grid alignment");
        int expectedAssigned = 0;
        int expectedEvicted = 0;
        int expectedTouched = 0;
        for (int cell = 0; cell < LightClusterGrid.CELLS; cell++) {
            int x = cell % LightClusterGrid.CLUSTERS_PER_AXIS;
            int y = cell / LightClusterGrid.CLUSTERS_PER_AXIS % LightClusterGrid.CLUSTERS_PER_AXIS;
            int z = cell / (LightClusterGrid.CLUSTERS_PER_AXIS * LightClusterGrid.CLUSTERS_PER_AXIS);
            double left = baseX + x * LightClusterGrid.CLUSTER_SIZE;
            double bottom = baseY + y * LightClusterGrid.CLUSTER_SIZE;
            double back = baseZ + z * LightClusterGrid.CLUSTER_SIZE;
            int used = 0;
            for (int index = 0; index < count; index++) {
                PointLight light = snapshot.lights().get(index);
                // Independent half-open cell / closed influence-box intersection oracle.
                if (light.x() + light.radius() < left || light.x() - light.radius() >= left + LightClusterGrid.CLUSTER_SIZE
                        || light.y() + light.radius() < bottom || light.y() - light.radius() >= bottom + LightClusterGrid.CLUSTER_SIZE
                        || light.z() + light.radius() < back || light.z() - light.radius() >= back + LightClusterGrid.CLUSTER_SIZE) continue;
                if (used < LightClusterGrid.ENTRIES_PER_CELL) {
                    require(grid.entry(cell, used) == index, "cell contribution order");
                    require(texels[(LightClusterGrid.cellBase(cell) + 1 + used) * 4] == index, "encoded cell index");
                    used++;
                    expectedAssigned++;
                } else expectedEvicted++;
            }
            require(grid.cellCount(cell) == used && texels[LightClusterGrid.cellBase(cell) * 4] == used, "cell occupancy");
            if (used > 0) expectedTouched++;
            for (int entry = used; entry < LightClusterGrid.ENTRIES_PER_CELL; entry++) {
                require(grid.entry(cell, entry) == -1, "unused CPU entry");
                require(texels[(LightClusterGrid.cellBase(cell) + 1 + entry) * 4] == 0, "unused texture entry");
            }
        }
        LightClusterGrid.Stats stats = grid.stats();
        require(stats.assigned() == expectedAssigned && stats.evicted() == expectedEvicted
                && stats.cellsTouched() == expectedTouched && stats.orphaned() == 0, "grid statistics");
        for (int index = 0; index < count; index++) {
            PointLight light = snapshot.lights().get(index);
            float[] expected = {(float) (light.x() - snapshot.cameraX()), (float) (light.y() - snapshot.cameraY()),
                    (float) (light.z() - snapshot.cameraZ()), light.radius(), light.red(), light.green(), light.blue(), light.intensity()};
            for (int component = 0; component < 8; component++) {
                require(encoded.getFloat(16 + index * PointLight.BYTES + component * 4) == expected[component], "snapshot record");
                require(records[index * 8 + component] == expected[component], "texture record");
            }
        }
        for (int offset = 16 + count * PointLight.BYTES; offset < encoded.limit(); offset += 4) {
            require(encoded.getInt(offset) == 0, "unused snapshot record");
        }
        for (int offset = count * 8; offset < records.length; offset++) require(records[offset] == 0, "unused texture record");
        grid.build(snapshot);
        require(Arrays.equals(texels, grid.encodeTexels()), "deterministic rebuild");
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
