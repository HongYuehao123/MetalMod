package net.metalmod.lighting;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * The environment that surrounds the local light set, published for downstream consumers.
 *
 * <p>A point light says nothing about the world it sits in. A consumer that wants to combine the
 * dynamic contribution with the sky needs the dimension, the time of day, the weather and the
 * dimension's own ambient term, and it needs them in one place with a version it can check.
 *
 * <p>This is deliberately a value published per frame from the level, not a shader semantic: the
 * sky stays vanilha's, and nothing here adds a second directional or ambient contribution on top of
 * the baked lightmap. It is metadata, and it says so.
 *
 * <p>{@link #VERSION} is the contract. A consumer that reads a record with a version it does not
 * know must fall back to its own defaults rather than guess at field meanings.
 */
public record EnvironmentRecord(int version, String dimension, int minY, int maxY,
                                boolean hasSkyLight, boolean hasCeiling, float ambientLight,
                                long gameTime, float dayFraction, int skyDarken,
                                float rainLevel, float thunderLevel) {

    /** Bump when a field's meaning or units change. */
    public static final int VERSION = 1;
    /** Ticks in one Minecraft day; {@link #dayFraction} is the position within it. */
    public static final int TICKS_PER_DAY = 24000;

    public static final EnvironmentRecord UNKNOWN =
            new EnvironmentRecord(VERSION, "unknown", 0, 0, true, false, 0, 0, 0, 0, 0, 0);

    /** True when this record came from a real level rather than the unknown default. */
    public boolean known() {
        return !"unknown".equals(this.dimension);
    }

    /**
     * Read the environment for this frame.
     *
     * <p>Every value comes from the level's own state, so a consumer sees the same numbers the game
     * is rendering with - including the interpolated rain and thunder levels the sky uses.
     */
    public static EnvironmentRecord capture(ClientLevel level, float partialTick) {
        if (level == null) {
            return UNKNOWN;
        }
        DimensionType type = level.dimensionType();
        long gameTime = level.getGameTime();
        long dayTime = Math.floorMod(gameTime, TICKS_PER_DAY);
        return new EnvironmentRecord(VERSION,
                level.dimension().identifier().toString(),
                type.minY(), type.minY() + type.height(),
                type.hasSkyLight(), type.hasCeiling(), type.ambientLight(),
                gameTime, dayTime / (float) TICKS_PER_DAY, level.getSkyDarken(),
                level.getRainLevel(partialTick), level.getThunderLevel(partialTick));
    }

    /**
     * One-line form for F3.
     *
     * <p>Deliberately short: it is printed verbatim into a debug page that has no room to spare. The
     * version and the dimension's namespace prefix are dropped - the version is in the published
     * record for a consumer to check, and the prefix is the same on every line - and the flags that
     * are normally true are named only when they are not.
     */
    public String summary() {
        String name = this.dimension.startsWith("minecraft:")
                ? this.dimension.substring("minecraft:".length()) : this.dimension;
        return name + " | day " + twoDecimals(this.dayFraction) + " | darken " + this.skyDarken
                + " | rain " + twoDecimals(this.rainLevel)
                + (this.hasSkyLight ? "" : " | no sky");
    }

    private static String twoDecimals(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
