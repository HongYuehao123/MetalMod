package net.metalmod.lighting;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.regex.Pattern;

/** Version-scoped Sodium 0.9.2 region ABI and dynamic-light adaptation; no Sodium dependency. */
public final class SodiumTerrainVariant {
    public static final String REGION = "MetalModSodiumRegion";
    public static final String POINT = "sodium-point-light-v1";
    public static final String DYNAMIC = "sodium-dynamic-lights-v1";
    public static final String CLUSTERED = "sodium-clustered-lights-v1";
    private static final Pattern REGION_DECLARATION = Pattern.compile(
            "#ifdef VULKAN\\s+layout\\(push_constant\\) uniform PC \\{\\s*"
            + "vec3 u_RegionOffset;\\s*int u_CurrentTime;\\s*uint u_RegionID;\\s*};\\s*"
            + "#else\\s*uniform vec3 u_RegionOffset;\\s*uniform int u_CurrentTime;\\s*"
            + "uniform uint u_RegionID;\\s*#endif");
    private static final String VERTEX_LIGHT = "v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);";

    private SodiumTerrainVariant() {}

    public static boolean eligible(RenderPipeline pipeline) {
        return Boolean.getBoolean("metalmod.sodiumAdapter")
                && "sodium:blocks/block_layer_opaque".equals(pipeline.getVertexShader().toString())
                && "sodium:blocks/block_layer_opaque".equals(pipeline.getFragmentShader().toString());
    }

    /** std140 ABI: xyz float at 0/4/8, signed time at 12, unsigned region ID at 16, padded to 32. */
    public static TerrainLightVariant.Sources adapt(String vertex, String fragment,
                                                   boolean point, boolean dynamic, boolean clustered) {
        var match = REGION_DECLARATION.matcher(vertex);
        if (!match.find() || !vertex.contains(VERTEX_LIGHT) || !fragment.contains("color *= v_Color;"))
            throw new IllegalArgumentException("Unsupported Sodium terrain shader pair");
        if (!"caf816ca2ddca1fda7e5d10802f1729518b10727c48c0e7b9a2bfc72bd813b12"
                .equals(TerrainLightVariant.fingerprint(vertex))
                || !"f1011e4796124bd889537a2ff2a384b4c55287b399f9bcc95f5c6e9205d5cd77"
                .equals(TerrainLightVariant.fingerprint(fragment)))
            throw new IllegalArgumentException("Unsupported Sodium terrain shader fingerprint; expected 0.9.2+mc26.2");
        vertex = match.replaceFirst("layout(std140) uniform " + REGION
                + " { vec3 u_RegionOffset; int u_CurrentTime; uint u_RegionID; };\n");
        if (!point && !dynamic) return new TerrainLightVariant.Sources(vertex, fragment);
        vertex = vertex.replace("void main() {", "out vec3 metalmodPosition;\nout vec3 metalmodTint;\n"
                + "out vec3 metalmodBaked;\nvoid main() {")
                .replace(VERTEX_LIGHT, VERTEX_LIGHT + "\nmetalmodPosition = position;\n"
                        + "metalmodTint = _vert_color.rgb;\n"
                        + "metalmodBaked = texture(u_LightTex, _vert_tex_light_coord).rgb;\n");
        fragment = fragment.replace("void main() {", "in vec3 metalmodPosition;\nin vec3 metalmodTint;\n"
                + "in vec3 metalmodBaked;\n" + TerrainLightVariant.POINT_LIGHT_BLOCK + "void main() {")
                .replace("color *= v_Color;", "vec4 metalmodSurface = color;\ncolor *= v_Color;\n"
                        + TerrainLightVariant.SINGLE_FALLOFF + TerrainLightVariant.SINGLE_APPLY);
        var result = new TerrainLightVariant.Sources(vertex, fragment);
        if (dynamic) result = TerrainLightVariant.dynamic(result);
        return dynamic && clustered ? TerrainLightVariant.clustered(result) : result;
    }
}
