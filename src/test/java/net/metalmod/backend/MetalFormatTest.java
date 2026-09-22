package net.metalmod.backend;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;

/**
 * Verifies every Minecraft -> Metal enum mapping against the values in the Metal headers.
 *
 * <p>These tables are pure functions with no runtime feedback: a wrong entry does not throw, it just
 * renders differently, and a wrong blend factor is nearly invisible in a still frame. So they are
 * pinned here. Expected values are transcribed from the macOS SDK, e.g.
 * {@code .../MacOSX.sdk/System/Library/Frameworks/Metal.framework/Headers/MTLRenderPipeline.h}.
 */
public final class MetalFormatTest {

    private static int failures;

    private MetalFormatTest() {
    }

    public static int runTests() {
        failures = 0;
        System.out.println("--------------------------------------------------");
        System.out.println("Metal enum mapping tests");
        System.out.println("--------------------------------------------------");

        testBlendFactors();
        testBlendOperations();
        testCompareFunctions();
        testPrimitiveTopologies();
        testWriteMasks();
        testSamplerAddressModes();
        testSamplerFilters();
        testMipFilterSelection();
        testTextureTypesAndUsage();

        return failures;
    }

    /**
     * MTLSamplerMipFilter (NotMipmapped=0, Nearest=1, Linear=2). The engine asks for mip selection by
     * supplying a maxLod; NotMipmapped silently ignores it, and it is the descriptor default.
     */
    private static void testMipFilterSelection() {
        check("mip filter constants match the header",
                MetalFormat.MIP_FILTER_NOT_MIPMAPPED == 0 && MetalFormat.MIP_FILTER_NEAREST == 1
                        && MetalFormat.MIP_FILTER_LINEAR == 2, "");

        String previous = System.getProperty("metalmod.mipFilter");
        try {
            System.clearProperty("metalmod.mipFilter");
            check("a sampler with a maxLod gets mipmapping",
                    MetalFormat.mtlSamplerMipFilter(true) == MetalFormat.MIP_FILTER_LINEAR,
                    "got " + MetalFormat.mtlSamplerMipFilter(true));
            check("a sampler without a maxLod does not",
                    MetalFormat.mtlSamplerMipFilter(false) == MetalFormat.MIP_FILTER_NOT_MIPMAPPED,
                    "got " + MetalFormat.mtlSamplerMipFilter(false));

            System.setProperty("metalmod.mipFilter", "off");
            check("-Dmetalmod.mipFilter=off forces level 0 even with a maxLod",
                    MetalFormat.mtlSamplerMipFilter(true) == MetalFormat.MIP_FILTER_NOT_MIPMAPPED,
                    "got " + MetalFormat.mtlSamplerMipFilter(true));

            System.setProperty("metalmod.mipFilter", "nearest");
            check("-Dmetalmod.mipFilter=nearest selects nearest",
                    MetalFormat.mtlSamplerMipFilter(false) == MetalFormat.MIP_FILTER_NEAREST,
                    "got " + MetalFormat.mtlSamplerMipFilter(false));
        } finally {
            if (previous == null) {
                System.clearProperty("metalmod.mipFilter");
            } else {
                System.setProperty("metalmod.mipFilter", previous);
            }
        }
    }

    /**
     * MTLSamplerAddressMode: ClampToEdge=0, MirrorClampToEdge=1, Repeat=2, MirrorRepeat=3. These
     * were once swapped, which inverted every sampler's address mode.
     */
    private static void testSamplerAddressModes() {
        check("CLAMP_TO_EDGE -> 0",
                MetalFormat.mtlSamplerAddress(AddressMode.CLAMP_TO_EDGE) == 0,
                "got " + MetalFormat.mtlSamplerAddress(AddressMode.CLAMP_TO_EDGE));
        check("REPEAT -> 2",
                MetalFormat.mtlSamplerAddress(AddressMode.REPEAT) == 2,
                "got " + MetalFormat.mtlSamplerAddress(AddressMode.REPEAT));
        check("address constants match the header",
                MetalFormat.ADDRESS_CLAMP_TO_EDGE == 0 && MetalFormat.ADDRESS_REPEAT == 2
                        && MetalFormat.ADDRESS_MIRROR_REPEAT == 3,
                "clamp=" + MetalFormat.ADDRESS_CLAMP_TO_EDGE + " repeat=" + MetalFormat.ADDRESS_REPEAT
                        + " mirror=" + MetalFormat.ADDRESS_MIRROR_REPEAT);
    }

    /** MTLSamplerMinMagFilter: Nearest=0, Linear=1. */
    private static void testSamplerFilters() {
        check("NEAREST -> 0", MetalFormat.mtlSamplerFilter(FilterMode.NEAREST) == 0,
                "got " + MetalFormat.mtlSamplerFilter(FilterMode.NEAREST));
        check("LINEAR -> 1", MetalFormat.mtlSamplerFilter(FilterMode.LINEAR) == 1,
                "got " + MetalFormat.mtlSamplerFilter(FilterMode.LINEAR));
    }

    /**
     * MTLTextureType and MTLTextureUsage bit values. TextureTypeTextureBuffer is 9 - Metal does
     * support buffer textures, but nothing creates one yet, which is why Sodium's
     * {@code isamplerBuffer} has no path.
     */
    private static void testTextureTypesAndUsage() {
        check("MTLTextureType2D == 2", MetalFormat.TEXTURE_TYPE_2D == 2, "");
        check("MTLTextureType2DArray == 3", MetalFormat.TEXTURE_TYPE_2D_ARRAY == 3, "");
        check("MTLTextureTypeCube == 5", MetalFormat.TEXTURE_TYPE_CUBE == 5, "");
        check("MTLTextureType3D == 7", MetalFormat.TEXTURE_TYPE_3D == 7, "");
        check("MTLTextureTypeTextureBuffer == 9 (unused, needed by Sodium)",
                MetalFormat.TEXTURE_TYPE_TEXTURE_BUFFER == 9,
                "got " + MetalFormat.TEXTURE_TYPE_TEXTURE_BUFFER);
        check("MTLTextureUsage bits (read=1, write=2, render=4, view=16)",
                MetalFormat.TEXTURE_USAGE_SHADER_READ == 1
                        && MetalFormat.TEXTURE_USAGE_SHADER_WRITE == 2
                        && MetalFormat.TEXTURE_USAGE_RENDER_TARGET == 4
                        && MetalFormat.TEXTURE_USAGE_PIXEL_FORMAT_VIEW == 16, "");
    }

    /**
     * MTLBlendFactor order is not the GL order: SourceAlphaSaturated is 10, and the constant
     * (blend colour/alpha) factors are 11..14.
     */
    private static void testBlendFactors() {
        Object[][] expected = {
                {BlendFactor.ZERO, 0},
                {BlendFactor.ONE, 1},
                {BlendFactor.SRC_COLOR, 2},
                {BlendFactor.ONE_MINUS_SRC_COLOR, 3},
                {BlendFactor.SRC_ALPHA, 4},
                {BlendFactor.ONE_MINUS_SRC_ALPHA, 5},
                {BlendFactor.DST_COLOR, 6},
                {BlendFactor.ONE_MINUS_DST_COLOR, 7},
                {BlendFactor.DST_ALPHA, 8},
                {BlendFactor.ONE_MINUS_DST_ALPHA, 9},
                {BlendFactor.SRC_ALPHA_SATURATE, 10},
                {BlendFactor.CONSTANT_COLOR, 11},
                {BlendFactor.ONE_MINUS_CONSTANT_COLOR, 12},
                {BlendFactor.CONSTANT_ALPHA, 13},
                {BlendFactor.ONE_MINUS_CONSTANT_ALPHA, 14},
        };
        for (Object[] row : expected) {
            BlendFactor factor = (BlendFactor) row[0];
            int want = (Integer) row[1];
            int got = MetalFormat.mtlBlendFactor(factor);
            check("blend factor " + factor + " -> " + want, got == want, "got " + got);
        }
        // Every factor must map, and no two may collide.
        check("all 15 blend factors covered",
                BlendFactor.values().length == expected.length,
                "MC has " + BlendFactor.values().length);
    }

    /** MTLBlendOperation: Add=0, Subtract=1, ReverseSubtract=2, Min=3, Max=4. */
    private static void testBlendOperations() {
        Object[][] expected = {
                {BlendOp.ADD, 0}, {BlendOp.SUBTRACT, 1}, {BlendOp.REVERSE_SUBTRACT, 2},
                {BlendOp.MIN, 3}, {BlendOp.MAX, 4},
        };
        for (Object[] row : expected) {
            BlendOp op = (BlendOp) row[0];
            int want = (Integer) row[1];
            int got = MetalFormat.mtlBlendOp(op);
            check("blend op " + op + " -> " + want, got == want, "got " + got);
        }
    }

    /** MTLCompareFunction: Never=0, Less=1, Equal=2, LessEqual=3, Greater=4, NotEqual=5, GreaterEqual=6, Always=7. */
    private static void testCompareFunctions() {
        Object[][] expected = {
                {CompareOp.NEVER_PASS, 0},
                {CompareOp.LESS_THAN, 1},
                {CompareOp.EQUAL, 2},
                {CompareOp.LESS_THAN_OR_EQUAL, 3},
                {CompareOp.GREATER_THAN, 4},
                {CompareOp.NOT_EQUAL, 5},
                {CompareOp.GREATER_THAN_OR_EQUAL, 6},
                {CompareOp.ALWAYS_PASS, 7},
        };
        for (Object[] row : expected) {
            CompareOp op = (CompareOp) row[0];
            int want = (Integer) row[1];
            int got = MetalFormat.mtlCompare(op);
            check("compare " + op + " -> " + want, got == want, "got " + got);
        }
    }

    /**
     * MTLPrimitiveType: Point=0, Line=1, LineStrip=2, Triangle=3, TriangleStrip=4.
     *
     * <p>{@code LINES} is Triangle, not Line, and {@code TRIANGLE_FAN} has no Metal primitive at all.
     * Both were wrong here first, which is why this test now says how they were settled rather than
     * just naming the values: Minecraft's own {@code PrimitiveTopology} reports
     * {@code indexCount(4) == 6} for {@code LINES} - the same 4-vertices-to-6-indices quad pattern as
     * {@code QUADS} - and {@code VulkanConst.toVk(LINES)} is
     * {@code VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST}. {@code rendertype_lines.vsh} confirms it in the
     * shader: it picks {@code +/- lineOffset} from {@code gl_VertexID % 2}, so four vertices make one
     * segment's quad. {@code TRIANGLE_FAN} is what {@code SkyRenderer.renderSkyDisc} uses for its
     * single non-indexed {@code draw(10, 1, 0, 0)}, so it must be expanded rather than truncated.
     */
    private static void testPrimitiveTopologies() {
        Object[][] expected = {
                {PrimitiveTopology.POINTS, 0},
                {PrimitiveTopology.LINES, 3},
                {PrimitiveTopology.DEBUG_LINES, 1},
                {PrimitiveTopology.DEBUG_LINE_STRIP, 2},
                {PrimitiveTopology.TRIANGLES, 3},
                {PrimitiveTopology.TRIANGLE_FAN, MetalFormat.TOPOLOGY_TRIANGLE_FAN},
                {PrimitiveTopology.QUADS, 3},
                {PrimitiveTopology.TRIANGLE_STRIP, 4},
        };
        for (Object[] row : expected) {
            PrimitiveTopology topology = (PrimitiveTopology) row[0];
            int want = (Integer) row[1];
            int got = MetalFormat.mtlTopology(topology);
            check("topology " + topology + " -> " + want, got == want, "got " + got);
        }
    }

    /** MTLColorWriteMask is A=1, B=2, G=4, R=8 - the reverse of Minecraft's R=1..A=8. */
    private static void testWriteMasks() {
        check("WRITE_RED -> 8", MetalFormat.mtlWriteMask(ColorTargetState.WRITE_RED) == 8,
                "got " + MetalFormat.mtlWriteMask(ColorTargetState.WRITE_RED));
        check("WRITE_GREEN -> 4", MetalFormat.mtlWriteMask(ColorTargetState.WRITE_GREEN) == 4,
                "got " + MetalFormat.mtlWriteMask(ColorTargetState.WRITE_GREEN));
        check("WRITE_BLUE -> 2", MetalFormat.mtlWriteMask(ColorTargetState.WRITE_BLUE) == 2,
                "got " + MetalFormat.mtlWriteMask(ColorTargetState.WRITE_BLUE));
        check("WRITE_ALPHA -> 1", MetalFormat.mtlWriteMask(ColorTargetState.WRITE_ALPHA) == 1,
                "got " + MetalFormat.mtlWriteMask(ColorTargetState.WRITE_ALPHA));
        check("WRITE_COLOR -> 14 (R|G|B)",
                MetalFormat.mtlWriteMask(ColorTargetState.WRITE_COLOR) == 14,
                "got " + MetalFormat.mtlWriteMask(ColorTargetState.WRITE_COLOR));
        check("WRITE_ALL -> 15", MetalFormat.mtlWriteMask(ColorTargetState.WRITE_ALL) == 15,
                "got " + MetalFormat.mtlWriteMask(ColorTargetState.WRITE_ALL));
        check("WRITE_NONE -> 0", MetalFormat.mtlWriteMask(ColorTargetState.WRITE_NONE) == 0,
                "got " + MetalFormat.mtlWriteMask(ColorTargetState.WRITE_NONE));
    }

    private static void check(String label, boolean condition, String detail) {
        if (condition) {
            System.out.println("PASS " + label);
        } else {
            failures++;
            System.out.println("FAIL " + label + " (" + detail + ")");
        }
    }
}
