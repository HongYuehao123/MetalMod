package net.metalmod.lighting;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Local moving emitters only; placed blocks keep vanilla's baked lighting. */
public final class LightCollector {
    /** How far from the camera a moving source is gathered. Public: the cluster window must cover it. */
    public static final double SEARCH_RADIUS = 32;
    private static volatile LightSnapshot current = LightSnapshot.empty();
    private static final java.util.concurrent.atomic.AtomicLong PUBLISHED =
            new java.util.concurrent.atomic.AtomicLong();

    private LightCollector() {}

    public static LightSnapshot current() { return current; }

    /**
     * Monotonic count of published snapshots.
     *
     * <p>The GPU upload compares this against the value it last encoded, so a set published between
     * two draws of one frame still reaches the GPU while an unchanged set costs nothing.
     */
    public static long generation() { return PUBLISHED.get(); }

    public static void clear() {
        publish(LightSnapshot.empty());
        environment = EnvironmentRecord.UNKNOWN;
        HELD.clear();
        dropped = 0;
        occluded = 0;
    }

    /** Publish a complete extraction result. Used by the extractor and offscreen rendering checks. */
    public static void publish(LightSnapshot snapshot) {
        current = snapshot;
        PUBLISHED.incrementAndGet();
    }

    /** The highest-contribution sources kept for one frame. Beyond this the set is dropped, counted. */
    public static final int CAPACITY = LightSnapshot.CAPACITY;

    private static volatile EnvironmentRecord environment = EnvironmentRecord.UNKNOWN;
    private static int dropped;
    private static int occluded;
    private static int examined;
    private static int allocated;
    private static volatile long extractNanos;
    /** Source ids kept by the previous frame's selection, for hysteresis. */
    private static final java.util.Set<Long> HELD = new java.util.HashSet<>();
    /**
     * How much better a new source must be before it displaces a held one.
     *
     * <p>Without this, two sources of nearly equal contribution swap places as the player walks, and
     * the set - and therefore the lighting - flickers between them. A held source wins ties, so the
     * selection is stable while the difference is below the margin.
     */
    public static final double HYSTERESIS = 1.25;

    /** The environment published with the current light set, for downstream consumers. */
    public static EnvironmentRecord environment() { return environment; }

    /** How many candidate sources were dropped because the set was full, since the last reset. */
    public static int dropped() { return dropped; }

    /**
     * How many sources were dropped because they are buried in, or sealed by, opaque blocks.
     *
     * <p>Counted rather than silently removed: "the light went out" and "the light was never
     * collected" look the same on screen, and the number is what tells them apart.
     */
    public static int occluded() { return occluded; }

    /** Distinct sources the search examined this frame, before the capacity cut. */
    public static int examined() { return examined; }

    /**
     * Source records allocated this frame.
     *
     * <p>A proxy for the extraction path's allocation rate, which the scaling gate asks to record. It
     * counts the records built, not every object the query touches: the entity query and the candidate
     * list allocate too, and pretending otherwise would make the number look more authoritative than
     * it is.
     */
    public static int allocated() { return allocated; }

    /** Wall time spent in {@link #extract} for the last frame, in nanoseconds. */
    public static long extractNanos() { return extractNanos; }

    /**
     * Publish this frame's light set.
     *
     * <p>Collection reads the camera and the level and never the player's mode. The game-mode decision
     * belongs to the caller and lives in exactly one place: the extraction hook suppresses the whole
     * set while the local player is spectating, without touching the player's setting. Keeping that
     * check out of here is what leaves collection a pure function of the world, which is what the
     * offline checks drive.
     */
    public static void extract(ClientLevel level, Camera camera, float partialTick) {
        long startedAt = System.nanoTime();
        try {
            extractInternal(level, camera, partialTick);
        } finally {
            extractNanos = System.nanoTime() - startedAt;
        }
    }

    private static void extractInternal(ClientLevel level, Camera camera, float partialTick) {
        if (level == null || camera == null) { clear(); return; }
        examined = 0;
        allocated = 0;
        Vec3 view = camera.position();
        environment = EnvironmentRecord.capture(level, partialTick);
        List<Candidate> candidates = new ArrayList<>();
        // Query all entities whose source can reach the view, even when their own mesh is culled.
        for (Entity entity : level.getEntities((Entity) null, AABB.ofSize(view,
                SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2), e -> !e.isRemoved())) {
            if (entity instanceof ItemEntity dropped) {
                add(candidates, entity, dropped.getItem(), 0, partialTick, view);
            } else if (entity instanceof LivingEntity living) {
                add(candidates, entity, living.getMainHandItem(), 0, partialTick, view);
                add(candidates, entity, living.getOffhandItem(), 1, partialTick, view);
                // Glow squids are deliberately NOT a source. Vanilla has no entity light at all: light
                // is a block property, and the squid's glow is an emissive texture rather than a light
                // level. A synthetic squid light therefore invents illumination the game does not have,
                // and the case that exposes it is a squid sealed in wool - the room lights up while the
                // torch in the same wall lights nothing, which reads as a plain bug. Sources that
                // vanilla leaves dark are only added where the effect is wanted and legible: an item
                // the player is holding, or one they dropped.
            }
        }
        // Static emitting blocks come from the incremental section index, not from a per-frame scan,
        // and are marked as already baked into vanilla's lightmap so they are not added twice.
        for (BlockLightIndex.BlockEmitter emitter : BlockLightIndex.collect(level, view.x, view.y, view.z)) {
            if (emitter.baked()) {
                continue;
            }
            addStatic(candidates, emitter, view);
        }
        // A source buried in opaque material, or sealed into a cell with no opening, emits nothing:
        // vanilla's light has no first step to take, and an unshadowed evaluator would otherwise pour
        // it straight through the surrounding wall. See LightOcclusion for what this does not cover.
        occluded = 0;
        List<Candidate> exposed = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            PointLight light = candidate.light();
            if (LightOcclusion.isSealed(level, light.x(), light.y(), light.z())) {
                occluded++;
                continue;
            }
            exposed.add(candidate);
        }
        candidates = exposed;

        // Highest expected local contribution first, then stable entity/hand ID: overflow is
        // deterministic, and a held source wins unless a challenger beats it by the margin.
        candidates.sort(Comparator
                .comparingDouble((Candidate candidate) ->
                        candidate.contribution() * (HELD.contains(candidate.id()) ? HYSTERESIS : 1))
                .reversed()
                .thenComparingLong(Candidate::id));
        dropped = Math.max(0, candidates.size() - LightSnapshot.CAPACITY);
        List<Candidate> selected = candidates.stream().limit(LightSnapshot.CAPACITY).toList();
        HELD.clear();
        List<PointLight> lights = new ArrayList<>(selected.size());
        for (Candidate candidate : selected) {
            HELD.add(candidate.id());
            lights.add(candidate.light());
        }
        publish(new LightSnapshot(view.x, view.y, view.z, lights));
    }

    /**
     * Add one indexed static source.
     *
     * <p>Its id is the block position rather than an entity id, so it is stable across frames and
     * hysteresis can hold it. It is in the candidate list so a later ownership mode can select it;
     * today every vanilla emitter is flagged baked and skipped above, which is what keeps a placed
     * torch from being added on top of its own contribution.
     */
    private static void addStatic(List<Candidate> out, BlockLightIndex.BlockEmitter emitter, Vec3 camera) {
        double x = emitter.x() + 0.5;
        double y = emitter.y() + 0.5;
        double z = emitter.z() + 0.5;
        PointLight light = new PointLight(x, y, z, emitter.radius(),
                emitter.red(), emitter.green(), emitter.blue(), emitter.strength());
        long id = BlockLightIndex.positionId(emitter.x(), emitter.y(), emitter.z());
        double dx = x - camera.x;
        double dy = y - camera.y;
        double dz = z - camera.z;
        examined++;
        allocated++;
        out.add(new Candidate(id, dx * dx + dy * dy + dz * dz, emitter.radius(),
                emitter.strength(), light));
    }

    private static void add(List<Candidate> out, Entity entity, ItemStack stack, int slot,
                            float partialTick, Vec3 camera) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return;
        int emission = item.getBlock().defaultBlockState().getLightEmission();
        if (emission <= 0) return;
        Vec3 pos = entity instanceof LivingEntity living
                ? living.getEyePosition(partialTick) : entity.getPosition(partialTick);
        float radius = 4 + emission * 0.4f;
        float strength = Math.min(1, emission / 15f);
        PointLight light = new PointLight(pos.x, pos.y, pos.z,
                radius, 1, 0.72f, 0.42f, strength);
        examined++;
        allocated++;
        out.add(new Candidate(((long) entity.getId() << 2) | slot,
                pos.distanceToSqr(camera), radius, strength, light));
    }

    private record Candidate(long id, double distanceSquared, float radius, float intensity,
                             PointLight light) {
        double contribution() {
            double edge = Math.max(0, 1 - Math.sqrt(distanceSquared) / radius);
            return intensity * edge * edge;
        }
    }
}
