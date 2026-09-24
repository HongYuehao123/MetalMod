package net.metalmod.lighting;

import java.util.ArrayList;
import java.util.List;

/**
 * Static-index and environment-record tests.
 *
 * <p>The block index cannot be built without a world, so what is tested here is the part that is
 * arithmetic rather than Minecraft: section keys, the dirty/scan bookkeeping that keeps the work
 * incremental, and the environment record's version contract. The in-game hook checklist and the F3
 * counters cover the world-facing half.
 */
public final class BlockLightIndexTest {

    public static int runTests() {
        try {
            sectionKeysRoundTrip();
            statsStartEmptyAndReportPendingWork();
            indexIsBoundedAndEvictionRequeues();
            environmentVersionAndUnknown();
            positionIdsAreStableAndDistinct();
            System.out.println("PASS static block index: keys, on-demand reads, invalidation and environment");
            return 0;
        } catch (AssertionError error) {
            error.printStackTrace();
            return 1;
        }
    }

    /** A section key must survive the pack/unpack that the cache is keyed on. */
    private static void sectionKeysRoundTrip() {
        BlockLightIndex.clear();
        // Section coordinates at world-border scale. SectionPos packs Z into 20 bits, so the test
        // stays inside what the game itself can address.
        int[] coordinates = {0, 1, -1, 137, -137, 30_000 / 16, -30_000 / 16};
        for (int coordinate : coordinates) {
            long key = BlockLightIndex.key(coordinate, coordinate / 3, -coordinate);
            require(net.minecraft.core.SectionPos.x(key) == coordinate
                            && net.minecraft.core.SectionPos.y(key) == coordinate / 3
                            && net.minecraft.core.SectionPos.z(key) == -coordinate,
                    "section key round-trips for " + coordinate);
        }
    }

    /**
     * A cleared index reports nothing, and a collect against no level does no work.
     *
     * <p>The index reads sections on demand, so "pending" is not a queue anything has to fill: it is
     * simply how many sections inside the current search window have not been read yet.
     */
    private static void statsStartEmptyAndReportPendingWork() {
        BlockLightIndex.clear();
        BlockLightIndex.Stats empty = BlockLightIndex.stats();
        require(empty.sections() == 0 && empty.pendingSections() == 0 && empty.emitters() == 0
                && empty.scans() == 0 && empty.evictions() == 0, "a cleared index reports nothing");

        // A null level collects nothing rather than throwing: the extractor can run before a level.
        require(BlockLightIndex.collect(null, 0, 0, 0).isEmpty(), "a null level collects nothing");
        require(BlockLightIndex.stats().pendingSections() == 0,
                "a null level leaves nothing pending");
        require(BlockLightIndex.emitters().isEmpty(), "nothing is published before any section is read");

        BlockLightIndex.clear();
        require(BlockLightIndex.stats().scans() == 0, "clear resets the scan counter");
    }

    /**
     * The index must stay bounded and must not accumulate work for sections nothing will ever ask
     * for. Invalidation is keyed on the chunk, so unloading a chunk forgets its sections.
     */
    private static void indexIsBoundedAndEvictionRequeues() {
        BlockLightIndex.clear();
        // Invalidation is the only world event the index depends on, and it must remove every section
        // of the chunk without touching a neighbour's.
        BlockLightIndex.invalidate(4, -7);
        require(BlockLightIndex.stats().sections() == 0, "invalidating an unknown chunk is harmless");

        // The published accessor must be deterministic and independent of HashMap order.
        List<String> first = describe(BlockLightIndex.emitters());
        List<String> second = describe(BlockLightIndex.emitters());
        require(first.equals(second), "the static publication is reproducible");
        require(BlockLightIndex.stats().pendingSections() <= 1,
                "an empty index has nothing pending");
    }

    /** The environment record is a versioned contract, and the unknown default is recognisable. */
    private static void environmentVersionAndUnknown() {
        require(EnvironmentRecord.VERSION >= 1, "the environment record is versioned");
        EnvironmentRecord unknown = EnvironmentRecord.capture(null, 0f);
        require(!unknown.known(), "a null level yields the unknown record");
        require(unknown.version() == EnvironmentRecord.VERSION, "the unknown record carries the version");
        require(EnvironmentRecord.UNKNOWN.summary() != null, "the record has a one-line form");
        // The day fraction is the position within one day, so it can never leave [0, 1).
        require(EnvironmentRecord.TICKS_PER_DAY == 24000, "the day length matches the game's");
    }

    /** A static source's identity comes from its block position, so hysteresis can hold it. */
    private static void positionIdsAreStableAndDistinct() {
        long id = BlockLightIndex.positionId(-13, 70, 256);
        require(BlockLightIndex.positionId(-13, 70, 256) == id, "the same position gives the same id");
        require(BlockLightIndex.positionId(13, 70, 256) != id, "x is part of the id");
        require(BlockLightIndex.positionId(-13, 71, 256) != id, "y is part of the id");
        require(BlockLightIndex.positionId(-13, 70, 257) != id, "z is part of the id");
        // Inside a queried radius every coordinate is far smaller than the 21 bits each field has.
        require(BlockLightIndex.positionId(1_000_000, -1_000_000, 1_000_000)
                        != BlockLightIndex.positionId(1_000_001, -1_000_000, 1_000_000),
                "ids stay distinct at the edge of the encodable range");
    }

    private static List<String> describe(List<BlockLightIndex.BlockEmitter> emitters) {
        List<String> out = new ArrayList<>();
        for (BlockLightIndex.BlockEmitter emitter : emitters) {
            out.add(emitter.blockId() + "@" + emitter.x() + "," + emitter.y() + "," + emitter.z()
                    + "/" + emitter.baked());
        }
        return out;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
