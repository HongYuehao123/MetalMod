package net.metalmod.lighting;

import com.mojang.blaze3d.pipeline.RenderPipeline;

/** Verified vanilla cutout terrain only. The native pass publishes whether 4x coverage is active;
 * single-sample native and Temporal retain the original alpha test exactly. */
public final class SpatialCoverageVariant {
    public static final String UNIFORM = "MetalModCoverage";
    private static final String CUTOUT = "if (color.a < ALPHA_CUTOUT) {\n        discard;\n    }";

    private SpatialCoverageVariant() {}

    public static boolean verified(RenderPipeline pipeline, String vertex, String fragment) {
        return "minecraft:pipeline/cutout_terrain".equals(pipeline.getLocation().toString())
                && TerrainLightVariant.eligible(pipeline)
                && vertex != null && fragment != null
                && TerrainLightVariant.fingerprint(vertex).equals(
                    "c8cc3e889f6068081932b461cd4dfc7a8c6f70661b16acade9a6578bba3f6f44")
                && TerrainLightVariant.fingerprint(fragment).equals(
                    "e3b94b5035d960b6cc0be6841d11e3abc9de845f770c750bffc4bcc25142ed6c")
                && fragment.contains(CUTOUT);
    }

    /** Called after optional lighting adaptation, whose alpha/discard contract is unchanged. */
    public static String adaptVerified(String fragment) {
        if (!fragment.contains(CUTOUT)) throw new IllegalArgumentException("lost verified cutout anchor");
        return fragment.replace("void main() {", "layout(std140) uniform " + UNIFORM
                + " { vec4 MetalModCoverageState; };\nvoid main() {")
                .replace(CUTOUT, "if (MetalModCoverageState.x > 0.5) {\n"
                    + "        // Recover fractional threshold coverage before the MSAA resolve.\n"
                    + "        float width = max(fwidth(color.a), 1.0 / 255.0);\n"
                    + "        float thresholdCoverage = clamp((color.a - ALPHA_CUTOUT) / width + 0.5, 0.0, 1.0);\n"
                    + "        // A minified alpha mip already estimates leaf area. Hard-thresholding it\n"
                    + "        // loses that area, including constant distant mips with zero derivative.\n"
                    + "        float footprint = max(length(dFdx(texCoord0) * vec2(TextureSize)),\n"
                    + "                              length(dFdy(texCoord0) * vec2(TextureSize)));\n"
                    + "        float minified = clamp(log2(max(footprint, 1.0)), 0.0, 1.0);\n"
                    + "        color.a = mix(thresholdCoverage, color.a, minified);\n"
                    + "        if (color.a <= 0.0) discard;\n"
                    + "    } else " + CUTOUT);
    }
}
