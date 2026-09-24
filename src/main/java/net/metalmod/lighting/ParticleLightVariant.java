package net.metalmod.lighting;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import java.util.Set;

/**
 * The lighting variant for the vanilla particle pipelines.
 *
 * <p>Particles are a separate shader pair from terrain, which is why a source that lights terrain does
 * not light the dust and flame around it. They are also the cheapest family to add, because their
 * vertex stage already carries a camera-relative position.
 *
 * <h2>Why {@code Position} is camera-relative</h2>
 *
 * <p>{@code particle.vsh} places its vertex with {@code ProjMat * ModelViewMat * vec4(Position, 1.0)},
 * with no {@code ChunkPosition}/{@code CameraBlockPos} term. Terrain reaches the same expression with
 * its own {@code pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset}, which is
 * world-minus-camera. Both multiply by the same {@code ModelViewMat}, so {@code Position} here must
 * already be camera-relative too - and it has to be, because every particle in a batch shares one
 * vertex buffer and one draw, so there is no per-particle model transform that could place a
 * model-local coordinate. The variant therefore passes {@code Position} through unchanged as the
 * lighting position, in the same frame the light records are published in.
 *
 * <h2>What differs from terrain</h2>
 *
 * <p>The fragment stage applies {@code ColorModulator} itself and discards {@code color.a < 0.1},
 * rather than terrain's {@code #ifdef ALPHA_CUTOUT}. The variant separates the modulated surface
 * sample from the vertex colour so the dynamic contribution is added to the surface while alpha and
 * the discard test are untouched.
 */
public final class ParticleLightVariant {

    public static final String DYNAMIC_VERSION = "particle-dynamic-lights-v1";
    public static final String CLUSTERED_VERSION = "particle-clustered-lights-v1";

    private static final Set<String> PIPELINES = Set.of(
            "minecraft:pipeline/opaque_particle",
            "minecraft:pipeline/translucent_particle");

    // Fingerprints of the preprocessed vanilla 26.2 particle pair, taken the same way the terrain
    // pair's are. A shaderpack or a resource pack that changes either one falls back to vanilla.
    private static final String VERTEX_HASH =
            "0f9b5507e8471af0cb855d31e6a4839cc07f6913f4a64cff1086d19487e9c8d8";
    private static final String FRAGMENT_HASH =
            "081622a5019fd38ae30ca805193d9bc1f4438d3d204d5a41fd3aaed07775dae2";

    // Shared with the block family, which forms its colour the same way. ColorModulator belongs to
    // the surface, not to the dynamic contribution: it is the pass-wide tint the engine sets
    // (including the white flash on a damage tick), so the light is multiplied by the same modulated
    // surface as everything else.
    private static final String FRAGMENT_ANCHOR = TerrainLightVariant.FLAT_FRAGMENT_ANCHOR;
    private static final String SURFACE = TerrainLightVariant.FLAT_SURFACE;

    private ParticleLightVariant() {}

    /** Whether this pipeline is one of the two vanilla particle pipelines with the vanilla pair. */
    public static boolean eligible(RenderPipeline pipeline) {
        return PIPELINES.contains(pipeline.getLocation().toString())
                && "minecraft:core/particle".equals(pipeline.getVertexShader().toString())
                && "minecraft:core/particle".equals(pipeline.getFragmentShader().toString());
    }

    /** The single-light adaptation, used as the base every other variant transforms. */
    public static TerrainLightVariant.Sources adapt(String vertex, String fragment) {
        return TerrainLightVariant.adaptPair(vertex, fragment, VERTEX_HASH, FRAGMENT_HASH,
                "Position", FRAGMENT_ANCHOR, SURFACE);
    }

    /** A bounded, count-prefixed array of point lights per fragment. */
    public static TerrainLightVariant.Sources adaptDynamic(String vertex, String fragment) {
        TerrainLightVariant.Sources base = adapt(vertex, fragment);
        return base == null ? null : TerrainLightVariant.dynamic(base);
    }

    /**
     * The clustered variant: one 16-block cell's index list per fragment.
     *
     * <p>Built on the flat set, not on the single-light form: the clustered rewrite replaces the flat
     * loop, so it needs the loop to be there.
     */
    public static TerrainLightVariant.Sources adaptClustered(String vertex, String fragment) {
        TerrainLightVariant.Sources base = adaptDynamic(vertex, fragment);
        return base == null ? null : TerrainLightVariant.clustered(base);
    }
}
