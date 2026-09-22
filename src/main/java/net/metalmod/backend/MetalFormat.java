package net.metalmod.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;

/**
 * The one place the Minecraft resource enums become Metal enums.
 *
 * <p>Everything is expressed as raw integer values from the Metal headers, so the native layer can
 * cast directly and there is no second mapping table to drift. Minecraft 26.2's {@link GpuFormat}
 * has no sRGB variants (sRGB is a shader concern there), so no sRGB formats appear here.
 */
public final class MetalFormat {

    private MetalFormat() {
    }

    // MTLTextureType
    public static final int TEXTURE_TYPE_1D = 0;
    public static final int TEXTURE_TYPE_1D_ARRAY = 1;
    public static final int TEXTURE_TYPE_2D = 2;
    public static final int TEXTURE_TYPE_2D_ARRAY = 3;
    public static final int TEXTURE_TYPE_2D_MULTISAMPLE = 4;
    public static final int TEXTURE_TYPE_CUBE = 5;
    public static final int TEXTURE_TYPE_CUBE_ARRAY = 6;
    public static final int TEXTURE_TYPE_3D = 7;

    // MTLTextureUsage
    public static final int TEXTURE_USAGE_SHADER_READ = 1;
    public static final int TEXTURE_USAGE_SHADER_WRITE = 2;
    public static final int TEXTURE_USAGE_RENDER_TARGET = 4;
    public static final int TEXTURE_USAGE_PIXEL_FORMAT_VIEW = 16;

    // MTLSamplerAddressMode
    public static final int ADDRESS_REPEAT = 0;
    public static final int ADDRESS_MIRROR_REPEAT = 1;
    public static final int ADDRESS_CLAMP_TO_EDGE = 2;

    // MTLSamplerMinMagFilter
    public static final int FILTER_NEAREST = 0;
    public static final int FILTER_LINEAR = 1;

    /**
     * Map a Minecraft texture format to an {@code MTLPixelFormat} raw value.
     *
     * <p>The three-channel 8/16/32-bit formats have no Metal equivalent; they map to the
     * four-channel format of the same component type. Nothing samples them yet, and the upload path
     * detects the resulting size mismatch and skips rather than writing a wrongly strided region.
     */
    public static long mtlPixelFormat(GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> 10;            // MTLPixelFormatR8Unorm
            case R8_SNORM -> 12;            // MTLPixelFormatR8Snorm
            case RG8_UNORM -> 30;           // MTLPixelFormatRG8Unorm
            case RG8_SNORM -> 32;           // MTLPixelFormatRG8Snorm
            case RGB8_UNORM -> 70;          // no RGB8; rgba8Unorm
            case RGB8_SNORM -> 72;          // no RGB8; rgba8Snorm
            case RGBA8_UNORM -> 70;         // MTLPixelFormatRGBA8Unorm
            case RGBA8_SNORM -> 72;         // MTLPixelFormatRGBA8Snorm
            case R16_UNORM -> 20;           // MTLPixelFormatR16Unorm
            case R16_SNORM -> 22;           // MTLPixelFormatR16Snorm
            case RG16_UNORM -> 60;          // MTLPixelFormatRG16Unorm
            case RG16_SNORM -> 62;          // MTLPixelFormatRG16Snorm
            case RGB16_UNORM -> 110;        // no RGB16; rgba16Unorm
            case RGB16_SNORM -> 112;        // no RGB16; rgba16Snorm
            case RGBA16_UNORM -> 110;       // MTLPixelFormatRGBA16Unorm
            case RGBA16_SNORM -> 112;       // MTLPixelFormatRGBA16Snorm
            case R8_UINT -> 13;             // MTLPixelFormatR8Uint
            case R8_SINT -> 14;             // MTLPixelFormatR8Sint
            case RG8_UINT -> 33;            // MTLPixelFormatRG8Uint
            case RG8_SINT -> 34;            // MTLPixelFormatRG8Sint
            case RGB8_UINT -> 73;           // no RGB8; rgba8Uint
            case RGB8_SINT -> 74;           // no RGB8; rgba8Sint
            case RGBA8_UINT -> 73;          // MTLPixelFormatRGBA8Uint
            case RGBA8_SINT -> 74;          // MTLPixelFormatRGBA8Sint
            case R16_UINT -> 23;            // MTLPixelFormatR16Uint
            case R16_SINT -> 24;            // MTLPixelFormatR16Sint
            case RG16_UINT -> 63;           // MTLPixelFormatRG16Uint
            case RG16_SINT -> 64;           // MTLPixelFormatRG16Sint
            case RGB16_UINT -> 113;         // no RGB16; rgba16Uint
            case RGB16_SINT -> 114;         // no RGB16; rgba16Sint
            case RGBA16_UINT -> 113;        // MTLPixelFormatRGBA16Uint
            case RGBA16_SINT -> 114;        // MTLPixelFormatRGBA16Sint
            case R32_UINT -> 53;            // MTLPixelFormatR32Uint
            case R32_SINT -> 54;            // MTLPixelFormatR32Sint
            case RG32_UINT -> 103;          // MTLPixelFormatRG32Uint
            case RG32_SINT -> 104;          // MTLPixelFormatRG32Sint
            case RGB32_UINT -> 123;         // no RGB32; rgba32Uint
            case RGB32_SINT -> 124;         // no RGB32; rgba32Sint
            case RGBA32_UINT -> 123;        // MTLPixelFormatRGBA32Uint
            case RGBA32_SINT -> 124;        // MTLPixelFormatRGBA32Sint
            case R16_FLOAT -> 25;           // MTLPixelFormatR16Float
            case RG16_FLOAT -> 65;          // MTLPixelFormatRG16Float
            case RGB16_FLOAT -> 115;        // no RGB16; rgba16Float
            case RGBA16_FLOAT -> 115;       // MTLPixelFormatRGBA16Float
            case R32_FLOAT -> 55;           // MTLPixelFormatR32Float
            case RG32_FLOAT -> 105;         // MTLPixelFormatRG32Float
            case RGB32_FLOAT -> 125;        // no RGB32; rgba32Float
            case RGBA32_FLOAT -> 125;       // MTLPixelFormatRGBA32Float
            case RGB10A2_UNORM -> 90;       // MTLPixelFormatRGB10A2Unorm
            case RGB10A2_UINT -> 91;        // MTLPixelFormatRGB10A2Uint
            case RG11B10_FLOAT -> 92;       // MTLPixelFormatRG11B10Float
            case D32_FLOAT -> 252;          // MTLPixelFormatDepth32Float
            case D32_FLOAT_S8_UINT -> 260;  // MTLPixelFormatDepth32Float_Stencil8
            case D24_UNORM_S8_UINT -> 255;  // MTLPixelFormatDepth24Unorm_Stencil8
            case D16_UNORM -> 250;          // MTLPixelFormatDepth16Unorm
            case S8_UINT -> 253;            // MTLPixelFormatStencil8
        };
    }

    /** Minecraft texture usage bits to {@code MTLTextureUsage} bits. */
    public static int mtlTextureUsage(int mcUsage) {
        int usage = 0;
        if ((mcUsage & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
            usage |= TEXTURE_USAGE_SHADER_READ | TEXTURE_USAGE_PIXEL_FORMAT_VIEW;
        }
        if ((mcUsage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            usage |= TEXTURE_USAGE_RENDER_TARGET;
        }
        return usage == 0 ? TEXTURE_USAGE_SHADER_READ : usage;
    }

    /** Pick the Metal texture type for a Minecraft texture description. */
    public static int mtlTextureType(int mcUsage, int depthOrLayers) {
        if ((mcUsage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 && depthOrLayers >= 6) {
            return TEXTURE_TYPE_CUBE;
        }
        if (depthOrLayers > 1) {
            return TEXTURE_TYPE_2D_ARRAY;
        }
        return TEXTURE_TYPE_2D;
    }

    public static int mtlSamplerAddress(AddressMode mode) {
        return mode == AddressMode.REPEAT ? ADDRESS_REPEAT : ADDRESS_CLAMP_TO_EDGE;
    }

    public static int mtlSamplerFilter(FilterMode mode) {
        return mode == FilterMode.NEAREST ? FILTER_NEAREST : FILTER_LINEAR;
    }

    /**
     * Whether the Metal pixel size equals the Minecraft block size. The 3-channel formats map to a
     * 4-channel Metal format, so a tightly packed upload would have the wrong row stride; those are
     * skipped until a real conversion path exists.
     */
    public static boolean isExactSizeMapping(GpuFormat format) {
        return switch (format) {
            case RGB8_UNORM, RGB8_SNORM, RGB8_UINT, RGB8_SINT,
                 RGB16_UNORM, RGB16_SNORM, RGB16_UINT, RGB16_SINT, RGB16_FLOAT,
                 RGB32_UINT, RGB32_SINT, RGB32_FLOAT -> false;
            default -> true;
        };
    }

    /** Map a vertex element's GpuFormat to an MTLVertexFormat raw value. */
    public static int mtlVertexFormat(GpuFormat format) {
        int count = Math.max(1, format.componentCount());
        return switch (format.componentType()) {
            case UNORM_8 -> count <= 2 ? 7 : count == 3 ? 8 : 9;      // UChar[2-4]Normalized
            case SNORM_8 -> count <= 2 ? 10 : count == 3 ? 11 : 12;   // Char[2-4]Normalized
            case UINT_8 -> count <= 2 ? 1 : count == 3 ? 2 : 3;       // UChar[2-4]
            case SINT_8 -> count <= 2 ? 4 : count == 3 ? 5 : 6;       // Char[2-4]
            case UINT_16 -> count <= 2 ? 13 : count == 3 ? 14 : 15;   // UShort[2-4]
            case SINT_16 -> count <= 2 ? 16 : count == 3 ? 17 : 18;   // Short[2-4]
            case UNORM_16 -> count <= 2 ? 19 : count == 3 ? 20 : 21;  // UShort[2-4]Normalized
            case SNORM_16 -> count <= 2 ? 22 : count == 3 ? 23 : 24;  // Short[2-4]Normalized
            case FLOAT_16 -> count == 1 ? 28 : count == 2 ? 25 : count == 3 ? 26 : 27;
            case UINT_32 -> count == 1 ? 36 : count == 2 ? 37 : count == 3 ? 38 : 39;
            case SINT_32 -> count == 1 ? 32 : count == 2 ? 33 : count == 3 ? 34 : 35;
            case FLOAT_32 -> count == 1 ? 28 : count == 2 ? 29 : count == 3 ? 30 : 31;
            default -> 31; // OPAQUE formats never appear as vertex elements
        };
    }

    /** MTLCompareFunction. */
    public static int mtlCompare(CompareOp op) {
        return switch (op) {
            case NEVER_PASS -> 0;
            case LESS_THAN -> 1;
            case EQUAL -> 2;
            case LESS_THAN_OR_EQUAL -> 3;
            case GREATER_THAN -> 4;
            case NOT_EQUAL -> 5;
            case GREATER_THAN_OR_EQUAL -> 6;
            case ALWAYS_PASS -> 7;
        };
    }

    /** MTLBlendFactor. */
    public static int mtlBlendFactor(BlendFactor factor) {
        return switch (factor) {
            case ZERO -> 0;
            case ONE -> 1;
            case SRC_COLOR -> 2;
            case ONE_MINUS_SRC_COLOR -> 3;
            case SRC_ALPHA -> 4;
            case ONE_MINUS_SRC_ALPHA -> 5;
            case DST_COLOR -> 6;
            case ONE_MINUS_DST_COLOR -> 7;
            case DST_ALPHA -> 8;
            case ONE_MINUS_DST_ALPHA -> 9;
            case CONSTANT_COLOR -> 10;
            case ONE_MINUS_CONSTANT_COLOR -> 11;
            case CONSTANT_ALPHA -> 12;
            case ONE_MINUS_CONSTANT_ALPHA -> 13;
            case SRC_ALPHA_SATURATE -> 14;
        };
    }

    /** MTLBlendOperation. */
    public static int mtlBlendOp(BlendOp op) {
        return switch (op) {
            case ADD -> 0;
            case SUBTRACT -> 1;
            case REVERSE_SUBTRACT -> 2;
            case MIN -> 3;
            case MAX -> 4;
        };
    }

    /** MTLPrimitiveType. */
    public static int mtlTopology(PrimitiveTopology topology) {
        return switch (topology) {
            case POINTS -> 0;
            case LINES, DEBUG_LINES -> 1;
            case DEBUG_LINE_STRIP -> 2;
            case TRIANGLES, TRIANGLE_FAN, QUADS -> 3;
            case TRIANGLE_STRIP -> 4;
        };
    }

    /** MTLTriangleFillMode. */
    public static int mtlFillMode(PolygonMode mode) {
        return mode == PolygonMode.WIREFRAME ? 1 : 0;
    }

    /** MC write-mask bits (R=1,G=2,B=4,A=8) to MTLColorWriteMask (A=1,B=2,G=4,R=8). */
    public static int mtlWriteMask(int mcMask) {
        int mask = 0;
        if ((mcMask & 1) != 0) mask |= 8;
        if ((mcMask & 2) != 0) mask |= 4;
        if ((mcMask & 4) != 0) mask |= 2;
        if ((mcMask & 8) != 0) mask |= 1;
        return mask;
    }
}
