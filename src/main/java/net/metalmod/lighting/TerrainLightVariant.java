package net.metalmod.lighting;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * Explicit shader variants for the known vanilla terrain pair.
 *
 * <p>There is no text replacement across arbitrary resource-pack shaders: a variant is built only
 * after the pipeline identity matches {@link #eligible} <em>and</em> both preprocessed sources match
 * a recorded hash. Anything else keeps its original shaders and is reported as a fallback, so an
 * unrecognised terrain shader renders exactly as it does without MetalMod.
 *
 * <p>Two variants exist, and each is a separate compiled pipeline keyed by its own source text:
 *
 * <ul>
 *   <li>{@link #adapt} — one synthetic point light in a small uniform block (the 6A proof).</li>
 *   <li>{@link #adaptDynamic} — a bounded, count-prefixed array of point lights (6B, and 6C
 *       continues to publish into this representation).</li>
 * </ul>
 *
 * <p>Both evaluate the dynamic contribution <em>before</em> fog, preserve alpha, and fill only the
 * lightmap headroom vanilla baked light left, so the zero-light path stays pixel-identical.
 */
public final class TerrainLightVariant {

    public static final String VERSION = "terrain-point-light-v1";
    public static final String DYNAMIC_VERSION = "terrain-dynamic-lights-v1";
    public static final String CLUSTERED_VERSION = "terrain-clustered-lights-v1";

    private static final Set<String> PIPELINES = Set.of(
            "minecraft:pipeline/solid_terrain",
            "minecraft:pipeline/cutout_terrain",
            "minecraft:pipeline/translucent_terrain");

    // Fingerprints of the preprocessed vanilla 26.2 terrain pair. Comments, #line directives and
    // whitespace are removed first, so an unrelated formatting change does not defeat the match.
    private static final String VERTEX_HASH = "c8cc3e889f6068081932b461cd4dfc7a8c6f70661b16acade9a6578bba3f6f44";
    private static final String FRAGMENT_HASH = "e3b94b5035d960b6cc0be6841d11e3abc9de845f770c750bffc4bcc25142ed6c";

    // The anchors the variant rewrites. Both must be present in the hash-verified sources.
    private static final String VERTEX_ANCHOR = "vertexColor = Color * sample_lightmap(Sampler2, UV2);";
    private static final String FRAGMENT_ANCHOR =
            "vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize)"
                    + " : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;";
    static final String MAIN_ANCHOR = "void main() {";

    static final String POINT_LIGHT_BLOCK =
            "layout(std140) uniform MetalModPointLight {\n"
                    + "    vec4 MetalModPositionRadius;\n"
                    + "    vec4 MetalModColorIntensity;\n"
                    + "};\n";
    private static final String LIGHT_SET_BLOCK =
            "struct MetalModLightRecord {\n"
                    + "    vec4 positionRadius;\n"
                    + "    vec4 colorIntensity;\n"
                    + "};\n"
                    + "layout(std140) uniform MetalModLightSet {\n"
                    + "    ivec4 MetalModMeta;\n"
                    + "    MetalModLightRecord MetalModLights[" + LightSnapshot.CAPACITY + "];\n"
                    + "};\n";
    static final String SINGLE_APPLY_CLUSTERED =
            "    color.rgb += metalmodSurface.rgb * metalmodTint"
                    + " * (vec3(1.0) - clamp(metalmodBaked, 0.0, 1.0)) * clamp(metalmodLight, 0.0, 1.0);\n";
    private static final String CLUSTERED_APPLY_LINE = SINGLE_APPLY_CLUSTERED;
    static final String SINGLE_FALLOFF =
            "    float metalmodFalloff = max(0.0, 1.0 - distance(metalmodPosition,"
                    + " MetalModPositionRadius.xyz) / max(MetalModPositionRadius.w, 0.0001));\n"
                    + "    vec3 metalmodLight = MetalModColorIntensity.rgb * MetalModColorIntensity.a"
                    + " * metalmodFalloff * metalmodFalloff;\n";
    static final String SINGLE_APPLY =
            "    color.rgb += metalmodSurface.rgb * metalmodTint"
                    + " * (vec3(1.0) - clamp(metalmodBaked, 0.0, 1.0)) * metalmodLight;\n";

    private TerrainLightVariant() {}

    /** Whether this pipeline is one of the three vanilla terrain pipelines with the vanilla pair. */
    public static boolean eligible(RenderPipeline pipeline) {
        return PIPELINES.contains(pipeline.getLocation().toString())
                && "minecraft:core/terrain".equals(pipeline.getVertexShader().toString())
                && "minecraft:core/terrain".equals(pipeline.getFragmentShader().toString());
    }

    /**
     * The lit-colour statement shared by the flat-shaded families (particle and block).
     *
     * <p>Those two pairs form their colour in one statement and let the fragment stage apply
     * {@code ColorModulator}, unlike terrain (which tints through the vertex colour) and the
     * model-shaped pairs (which shade over several statements).
     */
    static final String FLAT_FRAGMENT_ANCHOR =
            "vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;";
    /** The surface sample for the flat-shaded families, before the vertex colour is applied. */
    static final String FLAT_SURFACE = "texture(Sampler0, texCoord0) * ColorModulator";

    /** The vanilla terrain surface sample, before the vertex colour is applied. */
    static final String TERRAIN_SURFACE =
            "(UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize)"
                    + " : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize))";

    /** The 6A variant: one camera-relative point light. Null when the sources are not the known pair. */
    public static Sources adapt(String vertex, String fragment) {
        return adaptPair(vertex, fragment, VERTEX_HASH, FRAGMENT_HASH,
                "pos", FRAGMENT_ANCHOR, TERRAIN_SURFACE);
    }

    /**
     * The family-agnostic single-light adapter.
     *
     * <p>Every vanilla geometry shader pair that MetalMod lights follows the same shape: the vertex
     * stage builds a camera-relative position and multiplies the vertex colour by the baked lightmap,
     * and the fragment stage forms one lit colour that is later fogged. Only three things differ
     * between families, and they are the parameters here: the recorded source hashes, the expression
     * that names the camera-relative position, and the expression that samples the surface before the
     * vertex colour is applied. Keeping the light maths in one place is what stops the families
     * drifting apart.
     *
     * @param positionExpression vertex-stage expression holding the camera-relative world position
     * @param fragmentAnchor     the fragment stage's original lit-colour statement
     * @param surfaceExpression  the fragment stage's surface sample, before {@code vertexColor}
     */
    static Sources adaptPair(String vertex, String fragment,
                             String vertexHash, String fragmentHash,
                             String positionExpression, String fragmentAnchor, String surfaceExpression) {
        if (vertex == null || fragment == null
                || !fingerprint(vertex).equals(vertexHash)
                || !fingerprint(fragment).equals(fragmentHash)) {
            return null;
        }
        if (!vertex.contains(VERTEX_ANCHOR) || !fragment.contains(fragmentAnchor)
                || !vertex.contains(MAIN_ANCHOR) || !fragment.contains(MAIN_ANCHOR)) {
            return null;
        }
        String litVertex = vertex
                .replace(MAIN_ANCHOR, "out vec3 metalmodPosition;\n"
                        + "out vec3 metalmodTint;\n"
                        + "out vec3 metalmodBaked;\n"
                        + MAIN_ANCHOR + "\n")
                .replace(VERTEX_ANCHOR, VERTEX_ANCHOR + "\n"
                        + "    metalmodPosition = " + positionExpression + ";\n"
                        + "    metalmodTint = Color.rgb;\n"
                        + "    metalmodBaked = sample_lightmap(Sampler2, UV2).rgb;\n");
        String litFragment = fragment
                .replace(MAIN_ANCHOR, "in vec3 metalmodPosition;\n"
                        + "in vec3 metalmodTint;\n"
                        + "in vec3 metalmodBaked;\n"
                        + POINT_LIGHT_BLOCK
                        + MAIN_ANCHOR + "\n")
                .replace(fragmentAnchor,
                        "    vec4 metalmodSurface = " + surfaceExpression + ";\n"
                                + "    vec4 color = metalmodSurface * vertexColor;\n"
                                + SINGLE_FALLOFF
                                + "    // Fill remaining lightmap headroom, preserving alpha and the exact zero-light path.\n"
                                + "    // This is vanilla working-space artistic lighting, not a linear/HDR material model.\n"
                                + SINGLE_APPLY);
        return new Sources(litVertex, litFragment);
    }

    /** The 6B variant: a bounded, count-prefixed array of point lights. */
    public static Sources adaptDynamic(String vertex, String fragment) {
        Sources base = adapt(vertex, fragment);
        return base == null ? null : dynamic(base);
    }

    /** Turn a single-light adaptation into the bounded flat light set. Family-agnostic. */
    static Sources dynamic(Sources base) {
        String litFragment = base.fragment().replace(POINT_LIGHT_BLOCK, LIGHT_SET_BLOCK);
        int lightAt = litFragment.indexOf(SINGLE_FALLOFF);
        int applyAt = litFragment.indexOf(SINGLE_APPLY);
        if (lightAt < 0 || applyAt < lightAt) {
            throw new IllegalStateException("light-set adapter lost its anchors: falloffAt=" + lightAt
                    + " applyAt=" + applyAt);
        }
        // The loop bound is the smaller of the published count and the declared array length, and the
        // index is the loop variable itself, so a bad count can never index out of bounds.
        litFragment = litFragment.substring(0, lightAt)
                + "vec3 metalmodLight = vec3(0.0);\n"
                + "    for (int metalmodIndex = 0; metalmodIndex < min(MetalModMeta.x, "
                + LightSnapshot.CAPACITY + "); ++metalmodIndex) {\n"
                + "        vec4 pr = MetalModLights[metalmodIndex].positionRadius;\n"
                + "        vec4 ci = MetalModLights[metalmodIndex].colorIntensity;\n"
                + "        float falloff = max(0.0, 1.0 - distance(metalmodPosition, pr.xyz) / max(pr.w, 0.0001));\n"
                + "        metalmodLight += ci.rgb * ci.a * falloff * falloff;\n"
                + "    }\n"
                + "    color.rgb += metalmodSurface.rgb * metalmodTint"
                + " * (vec3(1.0) - clamp(metalmodBaked, 0.0, 1.0)) * clamp(metalmodLight, 0.0, 1.0);\n"
                + litFragment.substring(applyAt + SINGLE_APPLY.length());
        return new Sources(base.vertex(), litFragment);
    }

    /**
     * The 6C variant: a bounded per-cluster index list over the same light records.
     *
     * <p>The fragment stage locates its 16-block cell from the camera-relative position, reads that
     * cell's entry count and record indices, and evaluates only those lights. Every access is bounded
     * by the count and every index is clamped, so an exhausted or corrupted cluster block cannot read
     * outside the light records — it can only produce a dimmer pixel.
     */
    public static Sources adaptClustered(String vertex, String fragment) {
        Sources base = adaptDynamic(vertex, fragment);
        return base == null ? null : clustered(base);
    }

    /** Turn the bounded flat light set into the clustered variant. Family-agnostic. */
    static Sources clustered(Sources base) {
        // The blocks carry instance names: GLSL only exposes a uniform block's members through an
        // instance, and a struct member array cannot be indexed off the block name itself.
        String blocks = "layout(std140) uniform " + LightClusterGrid.GRID_UNIFORM + " {\n"
                + "    vec4 MetalModGridBase;\n"
                + "} metalmodGrid;\n"
                + "uniform sampler2D " + LightClusterGrid.DATA_UNIFORM + ";\n";
        String accessors =
                "const int MetalModHeaderTexel = " + LightClusterGrid.HEADER_TEXELS + ";\n"
                        + "const int MetalModCellTexels = " + LightClusterGrid.CELL_TEXELS + ";\n"
                        + "const int MetalModRecordBase = " + LightClusterGrid.RECORD_BASE + ";\n"
                        + "const int MetalModEntriesPerCell = " + LightClusterGrid.ENTRIES_PER_CELL + ";\n"
                        // A flat texel fetch: the whole table is one row, so an element index is a
                        // texel index and no stride arithmetic can go wrong.
                        + "vec4 metalmodTexel(int element) {\n"
                        + "    return texelFetch(" + LightClusterGrid.DATA_UNIFORM + ","
                        + " ivec2(element, 0), 0);\n"
                        + "}\n"
                        + "vec4 metalmodMeta() {\n"
                        + "    return metalmodTexel(0);\n"
                        + "}\n"
                        + "int metalmodEntryCount(int cell) {\n"
                        + "    return int(metalmodTexel(MetalModHeaderTexel + cell * MetalModCellTexels).x);\n"
                        + "}\n"
                        // Every decoded index is clamped to the published light count, so a table that
                        // ever disagreed with the records still cannot read past them. Entry k lives in
                        // its own texel, so the index is a texel coordinate - the one kind of dynamic
                        // indexing GLSL does allow.
                        + "int metalmodRecordOf(int cell, int entry) {\n"
                        + "    int bound = max(int(metalmodMeta().x) - 1, 0);\n"
                        + "    int slot = MetalModHeaderTexel + cell * MetalModCellTexels + 1 + entry;\n"
                        + "    return clamp(int(metalmodTexel(slot).x), 0, bound);\n"
                        + "}\n"
                        + "vec4 metalmodRecordHalf(int record, int part) {\n"
                        + "    return metalmodTexel(MetalModRecordBase + record * 2 + part);\n"
                        + "}\n";
        int loopAt = base.fragment().indexOf("vec3 metalmodLight = vec3(0.0);");
        int applyAt = base.fragment().indexOf(SINGLE_APPLY_CLUSTERED);
        if (loopAt < 0 || applyAt < loopAt) {
            throw new IllegalStateException("clustered adapter lost its anchors: loopAt=" + loopAt
                    + " applyAt=" + applyAt);
        }
        String clustered = base.fragment().substring(0, loopAt)
                + "int metalmodAxis = int(metalmodMeta().y);\n"
                + "    int metalmodCellSize = max(int(metalmodGrid.MetalModGridBase.w), 1);\n"
                + "    ivec3 metalmodCell3 = ivec3(clamp(floor((metalmodPosition"
                + " - metalmodGrid.MetalModGridBase.xyz) / float(metalmodCellSize)),"
                + " vec3(0.0), vec3(float(metalmodAxis - 1))));\n"
                + "    int metalmodCell = (metalmodCell3.z * metalmodAxis + metalmodCell3.y)"
                + " * metalmodAxis + metalmodCell3.x;\n"
                + "    int metalmodEntries = clamp(metalmodEntryCount(metalmodCell), 0,"
                + " MetalModEntriesPerCell);\n"
                + "    vec3 metalmodLight = vec3(0.0);\n"
                + "    for (int metalmodEntry = 0; metalmodEntry < MetalModEntriesPerCell; ++metalmodEntry) {\n"
                + "        if (metalmodEntry >= metalmodEntries) break;\n"
                + "        int metalmodRecord = metalmodRecordOf(metalmodCell, metalmodEntry);\n"
                + "        vec4 pr = metalmodRecordHalf(metalmodRecord, 0);\n"
                + "        vec4 ci = metalmodRecordHalf(metalmodRecord, 1);\n"
                + "        float falloff = max(0.0, 1.0 - distance(metalmodPosition, pr.xyz) / max(pr.w, 0.0001));\n"
                + "        metalmodLight += ci.rgb * ci.a * falloff * falloff;\n"
                + "    }\n"
                + base.fragment().substring(applyAt);
        // Declare the grid block, the data texture and the accessors at global scope.
        clustered = clustered.replace("void main() {", blocks + accessors + "void main() {");
        if (!clustered.contains(LightClusterGrid.DATA_UNIFORM)
                || !clustered.contains(LightClusterGrid.GRID_UNIFORM)) {
            throw new IllegalStateException("clustered adapter did not declare its blocks");
        }
        return new Sources(base.vertex(), clustered);
    }

    /**
     * Fingerprint of a preprocessed shader source: comments, {@code #line} directives and all
     * whitespace removed, so only shader text is compared.
     */
    static String fingerprint(String source) {
        String normalised = source
                .replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "")
                .replaceAll("(?m)^\\s*#line[^\\r\\n]*", "")
                .replaceAll("\\s+", "");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public record Sources(String vertex, String fragment) {}
}
