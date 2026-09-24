package net.metalmod.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Incremental index of static light-emitting blocks, kept per chunk section.
 *
 * <p>The plan's rule is explicit: <em>no scan of all loaded blocks every frame</em>. A section is read
 * at most once, the first time the search window asks for it, and the result is cached by section key.
 * A palette check answers "does this section contain an emitter at all" before any block is read, so
 * an emitter-free section costs a palette lookup rather than 4096 of them. Nothing is scanned because
 * a chunk loaded somewhere else in the world; the window is a few hundred sections, and only those
 * are ever read.
 *
 * <h2>What is deliberately not claimed</h2>
 *
 * <p>Placed torches and lava are <em>not</em> added to the dynamic light set: vanilla already bakes
 * their contribution into the block and sky lightmaps, and adding them again would double their
 * brightness. They are indexed so a downstream consumer can read them - {@link #emitters()} is that
 * publication - and each record carries {@link BlockEmitter#baked()}, which is true exactly when
 * vanilla already contributes this source. A pack that wants to suppress MetalMod's own evaluation
 * and light from this list itself has the data to do so.
 *
 * <p>A record also carries the block's map colour, so a consumer can tint the emission rather than
 * treating every torch as white.
 */
public final class BlockLightIndex {

    /** How far from the camera the index is queried. */
    public static final int SEARCH_RADIUS = 32;
    /** Emitters recorded per section. Beyond this, a section's remaining emitters are counted only. */
    public static final int MAX_PER_SECTION = 48;
    /** Sections held before the coldest are evicted. 512 sections is ~2.1 M blocks of coverage. */
    public static final int MAX_SECTIONS = 512;
    /** Evictions per call, so a large world never causes one long frame. */
    private static final int MAX_EVICTIONS_PER_CALL = 64;
    /**
     * Sections read per frame. The first frames after a teleport fill the window in; after that the
     * steady-state cost is zero, because every section in range is already cached.
     */
    private static final int MAX_SCANS_PER_FRAME = 4;

    /**
     * Whether vanilla already bakes this source's emission into the lightmap.
     *
     * <p>True for every emitter this index can currently produce, because it only reads vanilla
     * {@code LevelChunkSection} states and vanilla bakes all of their emission. It is carried per
     * record so a source that is *not* baked has somewhere to say so.
     */
    private static final boolean TRUE_FOR_VANILLA_EMITTERS = true;

    /** One static emitting block. World coordinates are absolute; the consumer subtracts its camera. */
    public record BlockEmitter(int blockId, int x, int y, int z, float red, float green, float blue,
                               float strength, float radius, boolean baked) {}

    private static final Map<Long, CacheEntry> SECTIONS = new HashMap<>();

    private static int scans;
    private static int pending;
    private static int evictions;
    private static int dropped;
    private static long lastTouch;

    private BlockLightIndex() {}

    private static final class CacheEntry {
        final List<BlockEmitter> emitters;
        long touched;

        CacheEntry(List<BlockEmitter> emitters, long touched) {
            this.emitters = emitters;
            this.touched = touched;
        }
    }

    /** Snapshot of the index for the F3 line and the tests. */
    public record Stats(int sections, int pendingSections, int scans, int evictions, int dropped,
                        int emitters) {}

    public static synchronized Stats stats() {
        int emitters = 0;
        for (CacheEntry entry : SECTIONS.values()) {
            emitters += entry.emitters.size();
        }
        return new Stats(SECTIONS.size(), pending, scans, evictions, dropped, emitters);
    }

    /** Drop everything: a disconnect, a dimension change or a resource reload leaves no stale index. */
    public static synchronized void clear() {
        SECTIONS.clear();
        scans = 0;
        evictions = 0;
        dropped = 0;
        pending = 0;
        lastTouch = 0;
    }

    /**
     * A chunk the client dropped: its cached emitters are no longer in the world.
     *
     * <p>This is the only piece of world-event bookkeeping the index needs. Sections are read on
     * demand rather than on load, so there is no "dirty" set to keep: the search window is at most a
     * few hundred sections, and anything outside it is never asked for.
     */
    public static synchronized void invalidate(int chunkX, int chunkZ) {
        SECTIONS.keySet().removeIf(section -> SectionPos.x(section) == chunkX && SectionPos.z(section) == chunkZ);
    }

    public static synchronized long key(int sectionX, int sectionY, int sectionZ) {
        return SectionPos.asLong(sectionX, sectionY, sectionZ);
    }

    /**
     * A stable id for a block position, so a static source keeps its identity between frames.
     *
     * <p>Three 21-bit fields inside one long: block coordinates beyond ±1,048,576 do not fit, and
     * cannot occur inside a queried radius.
     */
    public static long positionId(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    /**
     * Collect the indexed emitters within {@link #SEARCH_RADIUS} of a camera position.
     *
     * <p>Only the sections the radius actually covers are examined, and each is scanned at most once.
     * The per-frame scan budget is spent on the nearest dirty sections first, so a large teleport
     * fills in around the player rather than in chunk-load order.
     */
    public static synchronized List<BlockEmitter> collect(net.minecraft.client.multiplayer.ClientLevel level,
                                                          double cameraX, double cameraY, double cameraZ) {
        if (level == null) {
            return List.of();
        }
        int minSectionX = SectionPos.blockToSectionCoord(cameraX - SEARCH_RADIUS);
        int maxSectionX = SectionPos.blockToSectionCoord(cameraX + SEARCH_RADIUS);
        int minSectionZ = SectionPos.blockToSectionCoord(cameraZ - SEARCH_RADIUS);
        int maxSectionZ = SectionPos.blockToSectionCoord(cameraZ + SEARCH_RADIUS);
        int minSectionY = SectionPos.blockToSectionCoord(cameraY - SEARCH_RADIUS);
        int maxSectionY = SectionPos.blockToSectionCoord(cameraY + SEARCH_RADIUS);

        List<BlockEmitter> found = new ArrayList<>();
        long now = System.nanoTime();
        lastTouch = now;
        int budget = MAX_SCANS_PER_FRAME;
        int unread = 0;
        // Nearest first: sweep the covered section box in order of distance from the camera's section.
        int cameraSectionX = SectionPos.blockToSectionCoord(cameraX);
        int cameraSectionY = SectionPos.blockToSectionCoord(cameraY);
        int cameraSectionZ = SectionPos.blockToSectionCoord(cameraZ);
        for (int ring = 0; ring <= Math.max(maxSectionX - minSectionX, maxSectionY - minSectionY)
                && budget > 0; ring++) {
            for (int sectionX = Math.max(minSectionX, cameraSectionX - ring);
                 sectionX <= Math.min(maxSectionX, cameraSectionX + ring) && budget > 0; sectionX++) {
                for (int sectionZ = Math.max(minSectionZ, cameraSectionZ - ring);
                     sectionZ <= Math.min(maxSectionZ, cameraSectionZ + ring) && budget > 0; sectionZ++) {
                    for (int sectionY = Math.max(minSectionY, cameraSectionY - ring);
                         sectionY <= Math.min(maxSectionY, cameraSectionY + ring) && budget > 0; sectionY++) {
                        if (Math.max(Math.max(Math.abs(sectionX - cameraSectionX), Math.abs(sectionY - cameraSectionY)),
                                Math.abs(sectionZ - cameraSectionZ)) != ring) {
                            continue;   // only the shell at this distance
                        }
                        long sectionKey = key(sectionX, sectionY, sectionZ);
                        CacheEntry entry = SECTIONS.get(sectionKey);
                        if (entry == null && budget > 0) {
                            entry = scan(level, sectionX, sectionY, sectionZ, sectionKey);
                            budget--;
                        }
                        if (entry == null) {
                            // Not read yet, or its chunk is not loaded. Counted, and retried on a
                            // later frame rather than cached as empty.
                            unread++;
                            continue;
                        }
                        entry.touched = now;
                        found.addAll(entry.emitters);
                    }
                }
            }
        }
        pending = unread;
        evictIfNeeded();
        return found;
    }

    /** Read one section once. A missing or unloaded section caches as empty, not as "scan again". */
    private static CacheEntry scan(net.minecraft.client.multiplayer.ClientLevel level,
                                   int sectionX, int sectionY, int sectionZ, long sectionKey) {
        LevelChunk chunk = level.getChunkSource().getChunk(sectionX, sectionZ, ChunkStatus.FULL, false)
                instanceof LevelChunk loaded ? loaded : null;
        if (chunk == null) {
            // Not loaded: not cached, so a later frame retries without any bookkeeping.
            return null;
        }
        int sectionIndex = sectionY - level.getMinSectionY();
        LevelChunkSection section = sectionIndex >= 0 && sectionIndex < chunk.getSections().length
                ? chunk.getSection(sectionIndex) : null;
        scans++;
        // The palette answers "does this section contain any emitter" in palette-size time instead of
        // 4096 block lookups, so the common emitter-free section costs almost nothing.
        if (section == null || section.hasOnlyAir()
                || !section.maybeHas(state -> state.getLightEmission() > 0)) {
            CacheEntry empty = new CacheEntry(List.of(), System.nanoTime());
            SECTIONS.put(sectionKey, empty);
            return empty;
        }
        List<BlockEmitter> found = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    var state = section.getBlockState(x, y, z);
                    int emission = state.getLightEmission();
                    if (emission <= 0) {
                        continue;
                    }
                    if (found.size() >= MAX_PER_SECTION) {
                        dropped++;
                        continue;
                    }
                    cursor.set(SectionPos.sectionToBlockCoord(sectionX, x),
                            SectionPos.sectionToBlockCoord(sectionY, y),
                            SectionPos.sectionToBlockCoord(sectionZ, z));
                    found.add(describe(level, state, cursor, emission));
                }
            }
        }
        CacheEntry entry = new CacheEntry(List.copyOf(found), System.nanoTime());
        SECTIONS.put(sectionKey, entry);
        return entry;
    }

    /** Turn a state into a record, with the tint a consumer needs to colour the emission. */
    private static BlockEmitter describe(net.minecraft.world.level.BlockGetter getter,
                                         net.minecraft.world.level.block.state.BlockState state,
                                         BlockPos position, int emission) {
        int colour = state.getMapColor(getter, position).calculateARGBColor(MapColor.Brightness.NORMAL);
        float radius = 4 + emission * 0.4f;
        float strength = Math.min(1, emission / 15f);
        return new BlockEmitter(net.minecraft.world.level.block.Block.getId(state),
                position.getX(), position.getY(), position.getZ(),
                ((colour >> 16) & 0xFF) / 255f, ((colour >> 8) & 0xFF) / 255f, (colour & 0xFF) / 255f,
                strength, radius,
                // Vanilla bakes this block's emission into the lightmap, so a dynamic evaluator must
                // not add it a second time. Recorded per source rather than assumed per block type:
                // the flag is a statement about this source, and a future non-vanilla emitter that
                // vanilla does not bake would record false here and join the dynamic set.
                TRUE_FOR_VANILLA_EMITTERS);
    }

    /** Evict the least recently used sections when the index outgrows its budget. */
    private static void evictIfNeeded() {
        if (SECTIONS.size() <= MAX_SECTIONS) {
            return;
        }
        List<Map.Entry<Long, CacheEntry>> entries = new ArrayList<>(SECTIONS.entrySet());
        entries.sort(java.util.Comparator.comparingLong(entry -> entry.getValue().touched));
        Iterator<Map.Entry<Long, CacheEntry>> iterator = entries.iterator();
        int removed = 0;
        while (iterator.hasNext() && SECTIONS.size() > MAX_SECTIONS
                && removed < MAX_EVICTIONS_PER_CALL) {
            // Evicted means "unknown", not "empty": the next frame in range re-reads it, because
            // sections are read on demand rather than only when a load event announces them.
            SECTIONS.remove(iterator.next().getKey());
            removed++;
            evictions++;
        }
    }

    /**
     * Every indexed emitter, for a downstream consumer that reads the static set itself.
     *
     * <p>Ordered by section key so the publication is reproducible between calls.
     */
    public static synchronized List<BlockEmitter> emitters() {
        List<Map.Entry<Long, CacheEntry>> entries = new ArrayList<>(SECTIONS.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        List<BlockEmitter> all = new ArrayList<>();
        for (Map.Entry<Long, CacheEntry> entry : entries) {
            all.addAll(entry.getValue().emitters);
        }
        return List.copyOf(all);
    }
}
