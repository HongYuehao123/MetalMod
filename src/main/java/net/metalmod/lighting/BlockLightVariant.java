package net.metalmod.lighting;

import com.mojang.blaze3d.pipeline.RenderPipeline;

/**
 * The lighting variant for the vanilla block pipelines: blocks being moved by a piston, and falling
 * blocks.
 *
 * <p>These are the only consumers of {@code core/block.{vsh,fsh}} - the render types are
 * {@code solid_moving_block}, {@code cutout_moving_block} and {@code translucent_moving_block} - so
 * this is world geometry, not anything on a screen. That is worth stating because the pair also
 * imports the lightmap and a vertex colour, which makes it look like the item pair; it is not.
 *
 * <p>The pair is flat-shaded like {@code core/particle}, so it reuses that adapter. The one
 * difference is the position: {@code block.vsh} builds {@code pos = Position + ModelOffset} and
 * computes its fog from {@code pos}, so {@code Position} alone is not the camera-relative world
 * position - the per-draw model offset has to be added first. That is a different shape from the
 * other families, where {@code Position} is already in that frame, and it is exactly the kind of
 * difference that would put the light in the wrong place if it were assumed.
 */
public final class BlockLightVariant {

    public static final String DYNAMIC_VERSION = "block-dynamic-lights-v1";
    public static final String CLUSTERED_VERSION = "block-clustered-lights-v1";

    /** Fingerprints of the preprocessed vanilla 26.2 block pair. */
    private static final String VERTEX_HASH =
            "2eed5b0eb216c47528d3cd603641a2ef1d19039f9c4951c6349dcf2503fc05db";
    private static final String FRAGMENT_HASH =
            "d84c42dfe4e6da85985e149e11dac97f7b9f885a495f0f8e6670a79f4c11a7e5";

    /** The camera-relative world position for this pair: the vertex plus its per-draw model offset. */
    private static final String POSITION_EXPRESSION = "Position + ModelOffset";

    private BlockLightVariant() {}

    /** Whether this pipeline is the vanilla block pair. */
    public static boolean eligible(RenderPipeline pipeline) {
        return "minecraft:core/block".equals(pipeline.getVertexShader().toString())
                && "minecraft:core/block".equals(pipeline.getFragmentShader().toString());
    }

    /** The single-light adaptation, used as the base every other variant transforms. */
    public static TerrainLightVariant.Sources adapt(String vertex, String fragment) {
        return TerrainLightVariant.adaptPair(vertex, fragment, VERTEX_HASH, FRAGMENT_HASH,
                POSITION_EXPRESSION, TerrainLightVariant.FLAT_FRAGMENT_ANCHOR,
                TerrainLightVariant.FLAT_SURFACE);
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
