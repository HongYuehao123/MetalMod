package net.metalmod.backend;

import com.mojang.blaze3d.GpuFormat;
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
}
