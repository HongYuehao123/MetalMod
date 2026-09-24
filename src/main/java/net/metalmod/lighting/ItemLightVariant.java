package net.metalmod.lighting;

import com.mojang.blaze3d.pipeline.RenderPipeline;

/**
 * The lighting variant for the vanilla item pipelines: the item in the player's hand, and item
 * entities lying in the world.
 *
 * <p>Items are a fourth shader pair, {@code core/item.{vsh,fsh}}, which is why a torch in hand stayed
 * flat while the terrain, the particles and the mobs around it all responded. The pair is the same
 * shape as {@code core/entity}, so it reuses that adapter and differs only by its recorded hashes.
 *
 * <p>{@code Position} is camera-relative world space by the same argument as everywhere else:
 * {@code item.vsh} computes {@code sphericalVertexDistance = fog_spherical_distance(Position)} and
 * places the vertex with {@code ProjMat * ModelViewMat * vec4(Position, 1.0)}, the identical
 * expression terrain reaches through its own camera-relative {@code pos}. Drawn items that stand in
 * the world have to fog with distance, so the coordinate cannot be model-local.
 *
 * <h2>Why the inventory is not lit by this</h2>
 *
 * <p>Vanilla renders inventory and GUI item previews through these same two pipelines, so no
 * pipeline-level test can separate them from the held and dropped items. What separates them is the
 * contribution rule itself: the dynamic term fills only the lightmap headroom vanilla left, and the
 * GUI renders items fully lit, so there is no headroom and the addition is exactly zero. That is an
 * explicit policy rather than an accident of coordinates, and {@code RenderCheck} asserts it - a
 * fully lit lightmap must produce a pixel-identical frame. Anything that ever renders an item with a
 * dimmed lightmap outside the world would be affected, which is the limitation to keep in mind.
 */
public final class ItemLightVariant {

    public static final String DYNAMIC_VERSION = "item-dynamic-lights-v1";
    public static final String CLUSTERED_VERSION = "item-clustered-lights-v1";

    /** Fingerprints of the preprocessed vanilla 26.2 item pair. */
    private static final String VERTEX_HASH =
            "0094cd6478807ab45775eec8537c4bd6c92caa80d11401bc3a7e04e4580355eb";
    private static final String FRAGMENT_HASH =
            "873f72ae43d8a276c6c3affb1c9cbc23e8e50d8711658452f6173c1a6183b154";

    private ItemLightVariant() {}

    /** Whether this pipeline is the vanilla item pair. */
    public static boolean eligible(RenderPipeline pipeline) {
        return "minecraft:core/item".equals(pipeline.getVertexShader().toString())
                && "minecraft:core/item".equals(pipeline.getFragmentShader().toString());
    }

    /** The single-light adaptation, used as the base every other variant transforms. */
    public static TerrainLightVariant.Sources adapt(String vertex, String fragment) {
        return EntityLightVariant.adaptPair(vertex, fragment, VERTEX_HASH, FRAGMENT_HASH);
    }

    /** A bounded, count-prefixed array of point lights per fragment. */
    public static TerrainLightVariant.Sources adaptDynamic(String vertex, String fragment) {
        TerrainLightVariant.Sources base = adapt(vertex, fragment);
        return base == null ? null : TerrainLightVariant.dynamic(base);
    }

    /** The clustered variant: one 16-block cell's index list per fragment. */
    public static TerrainLightVariant.Sources adaptClustered(String vertex, String fragment) {
        TerrainLightVariant.Sources base = adaptDynamic(vertex, fragment);
        return base == null ? null : TerrainLightVariant.clustered(base);
    }
}
