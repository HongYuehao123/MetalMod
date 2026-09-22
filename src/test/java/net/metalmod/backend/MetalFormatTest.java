package net.metalmod.backend;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;

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

        return failures;
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

    /** MTLPrimitiveType: Point=0, Line=1, LineStrip=2, Triangle=3, TriangleStrip=4. */
    private static void testPrimitiveTopologies() {
        Object[][] expected = {
                {PrimitiveTopology.POINTS, 0},
                {PrimitiveTopology.LINES, 1},
                {PrimitiveTopology.DEBUG_LINES, 1},
                {PrimitiveTopology.DEBUG_LINE_STRIP, 2},
                {PrimitiveTopology.TRIANGLES, 3},
                {PrimitiveTopology.TRIANGLE_FAN, 3},
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
