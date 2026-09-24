package net.metalmod.lighting;

import com.mojang.blaze3d.pipeline.RenderPipeline;

/**
 * The lighting variant for the vanilla entity pipelines.
 *
 * <p>Entities use {@code core/entity.{vsh,fsh}}, a third shader pair, which is why a mob standing in a
 * torch's light stayed dark while the terrain and the particles around it lit up.
 *
 * <h2>The position frame</h2>
 *
 * <p>{@code Position} is camera-relative world space, the same frame the light records are published
 * in. That is read off the shaders rather than assumed: {@code entity.vsh} computes
 * {@code sphericalVertexDistance = fog_spherical_distance(Position)} and places the vertex with
 * {@code ProjMat * ModelViewMat * vec4(Position, 1.0)}, while {@code terrain.vsh} reaches the identical
 * expression through {@code pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset} and
 * computes its fog from {@code pos}. Fog distance is distance from the camera, both draws share the
 * same {@code ModelViewMat}, and both are correct in game - so the two expressions denote the same
 * thing, and entity positions are already relative to the camera. Entity rotation and limb animation
 * are baked into the vertex data, which is why no model transform appears here.
 *
 * <h2>What differs from terrain and particles</h2>
 *
 * <p>The entity fragment stage builds its colour over several statements instead of one: the surface
 * sample is then multiplied by the per-face vertex colour, {@code ColorModulator}, the overlay and the
 * baked lightmap. So the variant captures the surface sample where it is taken and adds the dynamic
 * contribution immediately before fog, once every multiplicative term has been applied.
 *
 * <p>The headroom term comes from the {@code lightMapColor} varying rather than from a value computed
 * in the vertex stage, so entities need one fewer varying than terrain. That varying only exists when
 * {@code EMISSIVE} is undefined, which is why emissive pipelines are excluded rather than adapted:
 * their whole point is to ignore the lightmap, so there is no headroom to fill and no albedo to
 * relight.
 */
public final class EntityLightVariant {

    public static final String DYNAMIC_VERSION = "entity-dynamic-lights-v1";
    public static final String CLUSTERED_VERSION = "entity-clustered-lights-v1";

    /** Fingerprints of the preprocessed vanilla 26.2 entity pair. */
    private static final String VERTEX_HASH =
            "64efffe7721df2046c7893126903e64f769c74d876834b68bb37ef0f1d39c187";
    private static final String FRAGMENT_HASH =
            "c8c8d58b822813aa740ea354247734ee418c6f1bcf7c077c261bf234b543f1b7";

    /** The vertex stage's only use of the camera-relative position, and a unique anchor. */
    private static final String POSITION_ANCHOR = "gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);";
    /** Where the fragment stage takes its surface sample. */
    private static final String SURFACE_ANCHOR = "    vec4 color = texture(Sampler0, texCoord0);";
    /** Where the fragment stage has finished shading and is about to fog. */
    private static final String APPLY_ANCHOR = "    fragColor = apply_fog(";

    private EntityLightVariant() {}

    /**
     * Whether this pipeline uses the vanilla entity pair and can carry the dynamic contribution.
     *
     * <p>Emissive pipelines are deliberately excluded: they do not sample the lightmap, so there is no
     * baked headroom to fill and nothing to relight. That is an explicit exclusion policy, not an
     * oversight - the plan asks for one.
     */
    public static boolean eligible(RenderPipeline pipeline) {
        return usesEntityPair(pipeline)
                && !pipeline.getShaderDefines().flags().contains("EMISSIVE");
    }

    /** Whether this pipeline is on the entity pair at all, emissive or not. */
    public static boolean usesEntityPair(RenderPipeline pipeline) {
        return "minecraft:core/entity".equals(pipeline.getVertexShader().toString())
                && "minecraft:core/entity".equals(pipeline.getFragmentShader().toString());
    }

    /** The single-light adaptation, used as the base every other variant transforms. */
    public static TerrainLightVariant.Sources adapt(String vertex, String fragment) {
        return adaptPair(vertex, fragment, VERTEX_HASH, FRAGMENT_HASH);
    }

    /**
     * The model-shaped adapter, shared by every vanilla pair that shades like an entity.
     *
     * <p>{@code core/entity} and {@code core/item} are the same shape line for line - the camera-
     * relative {@code Position}, the same {@code vec4 color = texture(Sampler0, texCoord0);} opening,
     * the same {@code fragColor = apply_fog(} ending, and the same {@code lightMapColor} /
     * {@code overlayColor} varyings. Only the recorded source fingerprints differ, so the two families
     * differ only by the hashes passed here. Keeping one copy of the light maths is what stops them
     * drifting apart.
     */
    static TerrainLightVariant.Sources adaptPair(String vertex, String fragment,
                                                  String vertexHash, String fragmentHash) {
        if (vertex == null || fragment == null
                || !TerrainLightVariant.fingerprint(vertex).equals(vertexHash)
                || !TerrainLightVariant.fingerprint(fragment).equals(fragmentHash)) {
            return null;
        }
        if (!vertex.contains(POSITION_ANCHOR) || !vertex.contains(TerrainLightVariant.MAIN_ANCHOR)
                || !fragment.contains(SURFACE_ANCHOR) || !fragment.contains(APPLY_ANCHOR)
                || !fragment.contains(TerrainLightVariant.MAIN_ANCHOR)) {
            return null;
        }
        String litVertex = vertex
                .replace(TerrainLightVariant.MAIN_ANCHOR, "out vec3 metalmodPosition;\n"
                        + "out vec3 metalmodTint;\n"
                        + TerrainLightVariant.MAIN_ANCHOR + "\n")
                .replace(POSITION_ANCHOR, POSITION_ANCHOR + "\n"
                        + "    metalmodPosition = Position;\n"
                        + "    metalmodTint = Color.rgb;\n");
        // Keep the raw surface sample: everything the fragment does to `color` afterwards is shading,
        // and the dynamic contribution is added to the shaded result so it cannot double-apply it.
        String litFragment = fragment
                .replace(TerrainLightVariant.MAIN_ANCHOR, "in vec3 metalmodPosition;\n"
                        + "in vec3 metalmodTint;\n"
                        + TerrainLightVariant.POINT_LIGHT_BLOCK
                        + TerrainLightVariant.MAIN_ANCHOR + "\n")
                .replace(SURFACE_ANCHOR, "    vec4 metalmodSurface = texture(Sampler0, texCoord0);\n"
                        + "    vec4 color = metalmodSurface;")
                // The entity stage has no separate "baked" varying: the lightmap colour is already
                // interpolated here, so the headroom is taken from it directly. The rest of the light
                // maths is the shared text, which is what keeps the three families from drifting.
                .replace(APPLY_ANCHOR, "    vec3 metalmodBaked = lightMapColor.rgb;\n"
                        + TerrainLightVariant.SINGLE_FALLOFF
                        + TerrainLightVariant.SINGLE_APPLY
                        + APPLY_ANCHOR);
        return new TerrainLightVariant.Sources(litVertex, litFragment);
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
