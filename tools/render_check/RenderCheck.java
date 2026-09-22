import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.metalmod.backend.MetalBuffer;
import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalNative;
import net.metalmod.backend.MetalRenderPipeline;
import net.metalmod.backend.MetalTexture;
import org.joml.Vector4f;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Render a real vanilla pipeline through MetalMod's own backend, offscreen, and check the pixels.
 *
 * <p>Everything else in this repo verifies that shaders compile and that reflection agrees with the
 * generated MSL. None of it proves a frame comes out right. This drives the actual backend path —
 * device, command encoder, render pass, uniform and vertex binding, draw, readback — with a real
 * Minecraft pipeline and asserts the colour that appears.
 *
 * <p>{@code gui.fsh} computes {@code fragColor = vertexColor * ColorModulator}, where
 * ColorModulator lives in the vertex shader's {@code DynamicTransforms} block. So a wrong uniform
 * binding shows up directly as the wrong colour, which is what makes it a useful probe.
 *
 * <p>Usage: tools/render_check/run.sh [instance-dir]
 */
public final class RenderCheck {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path instance = args.length > 0 ? Paths.get(args[0]) : defaultInstance();
        Path jar = instance.resolve(instance.getFileName() + ".jar");
        if (!Files.isRegularFile(jar)) {
            System.err.println("ERROR: client jar not found: " + jar);
            System.exit(2);
        }
        System.out.println("Offline render check — a real vanilla pipeline through MetalMod");
        System.out.println("  pipeline : minecraft:pipeline/gui");
        System.out.println();

        RenderPipeline pipeline = (RenderPipeline) Class
                .forName("net.minecraft.client.renderer.RenderPipelines")
                .getField("GUI").get(null);

        MetalDevice device = MetalDevice.create();
        if (device == null) {
            System.err.println("ERROR: no Metal device");
            System.exit(2);
        }

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ShaderSource source = (id, type) -> preprocess(zip, shaderPath(id.toString(), type));
            device.precompilePipeline(pipeline, source);
            MetalRenderPipeline compiled = device.pipelineFor(pipeline);
            check("pipeline compiled and registered", compiled != null, "");
            if (compiled == null) {
                report();
                return;
            }

            // White geometry, and the modulator decides what colour actually lands.
            drawAndCheck(device, pipeline, new float[]{0.0f, 0.0f, 1.0f, 1.0f},
                    "ColorModulator blue", 0, 0, 255);
            drawAndCheck(device, pipeline, new float[]{1.0f, 0.0f, 0.0f, 1.0f},
                    "ColorModulator red", 255, 0, 0);
            drawAndCheck(device, pipeline, new float[]{0.5f, 0.5f, 0.5f, 1.0f},
                    "ColorModulator grey", 128, 128, 128);

            // The terrain path. terrain.vsh places vertices with
            //   pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset
            // where CameraBlockPos/CameraOffset live in Globals - the block that shared its SPIR-V
            // binding with Fog, so a collision here moves the geometry rather than tinting it.
            RenderPipeline terrain = (RenderPipeline) Class
                    .forName("net.minecraft.client.renderer.RenderPipelines")
                    .getField("SOLID_TERRAIN").get(null);
            device.precompilePipeline(terrain, source);
            check("terrain pipeline compiled and registered", device.pipelineFor(terrain) != null, "");
            if (device.pipelineFor(terrain) != null) {
                terrainCheck(device, terrain);
            }

            // BUG-002's path. rendertype_lines.vsh expands a line in screen space:
            //   lineOffset = perpendicular * LineWidth / ScreenSize
            // and ScreenSize lives in Globals. If Globals were mis-bound (it shared a SPIR-V binding
            // with Fog before BUG-012), that divisor is garbage and the "line" becomes a huge quad -
            // which is exactly what BUG-002 describes. So: draw a 2px line and check that a pixel
            // well away from it is untouched.
            RenderPipeline lines = (RenderPipeline) Class
                    .forName("net.minecraft.client.renderer.RenderPipelines")
                    .getField("LINES").get(null);
            device.precompilePipeline(lines, source);
            check("lines pipeline compiled and registered", device.pipelineFor(lines) != null, "");
            if (device.pipelineFor(lines) != null) {
                linesCheck(device, lines);
            }

            // Sprite and text rendering both come down to "does texCoord0 sample the texel the
            // engine meant". A texture whose four texels are distinct colours makes an orientation
            // error - the atlas Y-flip of BUG-001's "wrong sprite" - impossible to miss.
            RenderPipeline textured = (RenderPipeline) Class
                    .forName("net.minecraft.client.renderer.RenderPipelines")
                    .getField("GUI_TEXTURED").get(null);
            device.precompilePipeline(textured, source);
            check("textured pipeline compiled and registered", device.pipelineFor(textured) != null, "");
            if (device.pipelineFor(textured) != null) {
                textureOrientationCheck(device, textured);
            }
        } finally {
            device.close();
        }
        report();
    }

    /** Draw one full-screen quad with the given ColorModulator and check the centre pixel. */
    private static void drawAndCheck(MetalDevice device, RenderPipeline pipeline, float[] modulator,
                                     String label, int expectR, int expectG, int expectB) {
        GpuBuffer vertices = device.createBuffer(() -> "vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBytes());
        GpuBuffer indices = device.createBuffer(() -> "indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, indexBytes());
        Map<String, GpuBuffer> uniforms = new LinkedHashMap<>();
        uniforms.put("Projection", device.createBuffer(() -> "Projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, identityMat4()));
        uniforms.put("DynamicTransforms", device.createBuffer(() -> "DynamicTransforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, dynamicTransforms(modulator)));

        int[] pixel = renderQuad(device, pipeline, vertices, indices, uniforms,
                new LinkedHashMap<>(), false, "gui", new float[]{0.0f, 0.0f, 0.0f, 1.0f});
        boolean ok = Math.abs(pixel[0] - expectR) <= 3 && Math.abs(pixel[1] - expectG) <= 3
                && Math.abs(pixel[2] - expectB) <= 3;
        check(label + " -> R" + pixel[0] + " G" + pixel[1] + " B" + pixel[2] + " (expected R"
                + expectR + " G" + expectG + " B" + expectB + ")", ok, "");
        vertices.close();
        indices.close();
        for (GpuBuffer buffer : uniforms.values()) {
            buffer.close();
        }
    }

    /**
     * Draw a full-screen quad with the real terrain pipeline and assert it comes out white.
     *
     * <p>Everything is set so the maths is trivial: identity matrices, zero camera position, no fog,
     * {@code UseRgss = 0}, {@code ChunkVisibility = 1}, and 1x1 white textures for both samplers. So
     * a white result means every block reached the shader with the value it was given. In particular
     * {@code Globals} - if it were still sharing a Metal slot with {@code Fog}, the camera position
     * would be fog data and the quad would be somewhere else entirely.
     */
    private static void terrainCheck(MetalDevice device, RenderPipeline pipeline) {
        GpuTexture white = device.createTexture("white", GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        ByteBuffer whitePixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        whitePixel.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
        MetalNative.textureReplaceRegion(((MetalTexture) white).handle(), 0, 0, 0, 0, 1, 1,
                whitePixel, 4L);
        GpuTextureView whiteView = device.createTextureView(white);

        GpuBuffer vertices = device.createBuffer(() -> "terrain vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, terrainVertices());
        GpuBuffer indices = device.createBuffer(() -> "terrain indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, indexBytes());

        Map<String, GpuBuffer> uniforms = new LinkedHashMap<>();
        uniforms.put("Projection", device.createBuffer(() -> "Projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, identityMat4()));
        uniforms.put("ChunkSection", device.createBuffer(() -> "ChunkSection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, chunkSection()));
        uniforms.put("Globals", device.createBuffer(() -> "Globals",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, globals()));
        uniforms.put("Fog", device.createBuffer(() -> "Fog",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, fog()));

        Map<String, GpuTextureView> textures = new LinkedHashMap<>();
        textures.put("Sampler0", whiteView);
        textures.put("Sampler2", whiteView);

        // Green clear, so "nothing was drawn here" is distinguishable from "the shader output black".
        int[] pixel = renderQuad(device, pipeline, vertices, indices, uniforms, textures, true,
                "terrain", new float[]{0.0f, 1.0f, 0.0f, 1.0f});
        boolean covered = !(pixel[1] > 200 && pixel[0] < 60 && pixel[2] < 60);
        boolean isWhite = pixel[0] > 250 && pixel[1] > 250 && pixel[2] > 250;
        check("terrain quad covers the centre (clear is green, so black means it drew something)",
                covered, "centre R" + pixel[0] + " G" + pixel[1] + " B" + pixel[2]);
        check("terrain quad is white -> R" + pixel[0] + " G" + pixel[1] + " B" + pixel[2],
                isWhite, "");
    }

    /** 28-byte terrain vertex: Position RGB32_FLOAT, Color RGBA8_UNORM, UV0 RG32_FLOAT, UV2 RG16_SINT. */
    private static ByteBuffer terrainVertices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 28).order(ByteOrder.nativeOrder());
        float[][] positions = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (float[] p : positions) {
            // Reversed-Z (the terrain pipeline tests GREATER_THAN_OR_EQUAL), so the near plane is 1.
            buffer.putFloat(p[0]).putFloat(p[1]).putFloat(1.0f);
            buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
            buffer.putFloat(0.0f).putFloat(0.0f);   // UV0
            buffer.putShort((short) 0).putShort((short) 0);   // UV2
        }
        buffer.flip();
        return buffer;
    }

    /** std140: mat4 ModelViewMat, float ChunkVisibility, ivec2 TextureSize, ivec3 ChunkPosition. */
    private static ByteBuffer chunkSection() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder());
        buffer.put(identityMat4());                  // ModelViewMat      0..64
        buffer.putFloat(1.0f);                       // ChunkVisibility  64..68
        buffer.putInt(0);                            // padding          68..72
        buffer.putInt(1).putInt(1);                  // TextureSize      72..80 (1x1)
        buffer.putInt(0).putInt(0).putInt(0);        // ChunkPosition    80..92
        buffer.putInt(0);                            // padding          92..96
        buffer.flip();
        return buffer;
    }

    /** std140 Globals: ivec3 CameraBlockPos, vec3 CameraOffset, vec2 ScreenSize, then four scalars. */
    private static ByteBuffer globals() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        buffer.putInt(0).putInt(0).putInt(0).putInt(0);          // CameraBlockPos + padding
        buffer.putFloat(0).putFloat(0).putFloat(0).putFloat(0);  // CameraOffset + padding
        buffer.putFloat(WIDTH).putFloat(HEIGHT);                 // ScreenSize
        buffer.putFloat(1.0f).putFloat(0.0f);                    // GlintAlpha, GameTime
        buffer.putInt(0).putInt(0);                              // MenuBlurRadius, UseRgss = 0
        buffer.flip();
        return buffer;
    }

    /** std140 Fog: vec4 FogColor then six floats; the distances are far away so fog contributes nothing. */
    private static ByteBuffer fog() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
        buffer.putFloat(1).putFloat(1).putFloat(1).putFloat(1);   // FogColor
        buffer.putFloat(1000).putFloat(2000);                     // environmental start/end
        buffer.putFloat(1000).putFloat(2000);                     // render distance start/end
        buffer.putFloat(2000).putFloat(2000);                     // sky end, clouds end
        buffer.flip();
        return buffer;
    }

    /**
     * Draw one thin line across the middle of the target and check it stays thin.
     *
     * <p>Two pixels wide, so the centre row must be lit and a row well above it must not be. A huge
     * offset - the BUG-002 symptom - lights the far pixel too.
     */
    private static void linesCheck(MetalDevice device, RenderPipeline pipeline) {
        GpuBuffer vertices = device.createBuffer(() -> "line vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, lineVertices());
        Map<String, GpuBuffer> uniforms = new LinkedHashMap<>();
        uniforms.put("Projection", device.createBuffer(() -> "Projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, identityMat4()));
        uniforms.put("DynamicTransforms", device.createBuffer(() -> "DynamicTransforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                dynamicTransforms(new float[]{1.0f, 1.0f, 1.0f, 1.0f})));
        uniforms.put("Globals", device.createBuffer(() -> "Globals",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, globals()));
        uniforms.put("Fog", device.createBuffer(() -> "Fog",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, fog()));

        // A 64x64 target with ScreenSize 64 and LineWidth 2 gives a 2px line, so the centre row is
        // lit and 8px away is clear.
        int[] rows = renderLines(device, pipeline, vertices, uniforms);
        int centreRow = rows[0];
        int farRow = rows[1];
        check("thin line lights the centre row -> R" + centreRow, centreRow > 200, "");
        check("thin line does not cover a row 8px away -> R" + farRow + " (a giant quad would)",
                farRow < 60, "");
        vertices.close();
        for (GpuBuffer buffer : uniforms.values()) {
            buffer.close();
        }
    }

    /** Two 24-byte line vertices: Position RGB32_FLOAT, Color RGBA8_UNORM, Normal RGBA8_SNORM, LineWidth F32. */
    private static ByteBuffer lineVertices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(2 * 24).order(ByteOrder.nativeOrder());
        // Start, then end. Normal carries the direction, which is how the shader finds the end point.
        for (float x : new float[]{-0.8f, 0.8f}) {
            buffer.putFloat(x).putFloat(0.0f).putFloat(1.0f);        // Position, reversed-Z near plane
            buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
            buffer.put((byte) 127).put((byte) 0).put((byte) 0).put((byte) 0);   // Normal (1, 0, 0)
            buffer.putFloat(2.0f);                                   // LineWidth in pixels
        }
        buffer.flip();
        return buffer;
    }

    /** Render the line and return the centre row and a row 8px above it, as red channel values. */
    private static int[] renderLines(MetalDevice device, RenderPipeline pipeline, GpuBuffer vertices,
                                     Map<String, GpuBuffer> uniforms) {
        GpuTexture color = device.createTexture("lines", GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_COPY_SRC, pipeline.getColorTargetState().format(), WIDTH, HEIGHT, 1, 1);
        GpuTextureView colorView = device.createTextureView(color);
        GpuTexture depth = device.createTexture("lines depth", GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.D32_FLOAT, WIDTH, HEIGHT, 1, 1);
        GpuTextureView depthView = device.createTextureView(depth);
        GpuBuffer readback = device.createBuffer(() -> "readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) WIDTH * HEIGHT * 4);

        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "lines")
                .withColorAttachment(colorView, Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f)))
                .withDepthAttachment(depthView, OptionalDouble.of(0.0))
                .withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        CommandEncoderBackend encoder = device.createCommandEncoder();
        RenderPassBackend pass = encoder.createRenderPass(descriptor);
        pass.setPipeline(pipeline);
        for (Map.Entry<String, GpuBuffer> uniform : uniforms.entrySet()) {
            pass.setUniform(uniform.getKey(), uniform.getValue().slice());
        }
        pass.setVertexBuffer(0, vertices.slice());
        pass.draw(2, 1, 0, 0);
        encoder.submitRenderPass();

        encoder.copyTextureToBuffer(color, readback, 0L, null, 0, 0, 0, WIDTH, HEIGHT);
        ByteBuffer pixels = ((MetalBuffer) readback).data().asByteBuffer().order(ByteOrder.nativeOrder());
        int centreRow = pixels.get(((HEIGHT / 2) * WIDTH + (WIDTH / 2)) * 4) & 0xFF;
        int farRow = pixels.get(((HEIGHT / 2 - 8) * WIDTH + (WIDTH / 2)) * 4) & 0xFF;
        readback.close();
        depthView.close();
        depth.close();
        colorView.close();
        color.close();
        return new int[]{centreRow, farRow};
    }

    /**
     * Draw a quad that maps each corner of the screen onto one texel of a 2x2 texture and check
     * which colour lands where.
     *
     * <p>Minecraft's UV origin is the texture's top-left, so NDC top-left must sample texel (0,0).
     * If the Y axis is flipped anywhere along the way - which is what a sprite sampling the wrong
     * row looks like - the colours come back in the wrong quadrants.
     */
    private static void textureOrientationCheck(MetalDevice device, RenderPipeline pipeline) {
        GpuTexture texture = device.createTexture("quadrants", GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 2, 2, 1, 1);
        ByteBuffer texels = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        texels.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 255);     // (0,0) red
        texels.put((byte) 0).put((byte) 255).put((byte) 0).put((byte) 255);     // (1,0) green
        texels.put((byte) 0).put((byte) 0).put((byte) 255).put((byte) 255);     // (0,1) blue
        texels.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255); // (1,1) white
        texels.flip();
        MetalNative.textureReplaceRegion(((MetalTexture) texture).handle(), 0, 0, 0, 0, 2, 2, texels, 8L);
        GpuTextureView view = device.createTextureView(texture);

        GpuBuffer vertices = device.createBuffer(() -> "uv vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, uvVertices());
        GpuBuffer indices = device.createBuffer(() -> "uv indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, indexBytes());
        Map<String, GpuBuffer> uniforms = new LinkedHashMap<>();
        uniforms.put("Projection", device.createBuffer(() -> "Projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, identityMat4()));
        uniforms.put("DynamicTransforms", device.createBuffer(() -> "DynamicTransforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                dynamicTransforms(new float[]{1.0f, 1.0f, 1.0f, 1.0f})));
        Map<String, GpuTextureView> textures = new LinkedHashMap<>();
        textures.put("Sampler0", view);

        ByteBuffer pixels = renderQuadPixels(device, pipeline, vertices, indices, uniforms, textures);
        // The framebuffer's row 0 is the top of the screen.
        checkQuadrant(pixels, WIDTH / 4, HEIGHT / 4, "top-left", 255, 0, 0);
        checkQuadrant(pixels, 3 * WIDTH / 4, HEIGHT / 4, "top-right", 0, 255, 0);
        checkQuadrant(pixels, WIDTH / 4, 3 * HEIGHT / 4, "bottom-left", 0, 0, 255);
        checkQuadrant(pixels, 3 * WIDTH / 4, 3 * HEIGHT / 4, "bottom-right", 255, 255, 255);

        vertices.close();
        indices.close();
        for (GpuBuffer buffer : uniforms.values()) {
            buffer.close();
        }
        view.close();
        texture.close();
    }

    private static void checkQuadrant(ByteBuffer pixels, int x, int y, String where,
                                      int expectR, int expectG, int expectB) {
        int offset = (y * WIDTH + x) * 4;
        int r = pixels.get(offset) & 0xFF;
        int g = pixels.get(offset + 1) & 0xFF;
        int b = pixels.get(offset + 2) & 0xFF;
        boolean ok = Math.abs(r - expectR) <= 3 && Math.abs(g - expectG) <= 3 && Math.abs(b - expectB) <= 3;
        check("texCoord samples " + where + " -> R" + r + " G" + g + " B" + b + " (expected R"
                + expectR + " G" + expectG + " B" + expectB + ")", ok, "");
    }

    /** 24-byte position_tex_color vertex: Position RGB32_FLOAT, UV0 RG32_FLOAT, Color RGBA8_UNORM. */
    private static ByteBuffer uvVertices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 24).order(ByteOrder.nativeOrder());
        // NDC corners paired with the UV that should land there: top-left is UV (0,0).
        float[][] corners = {
                {-1, -1, 0, 1},   // bottom-left -> v = 1
                {1, -1, 1, 1},    // bottom-right
                {1, 1, 1, 0},     // top-right
                {-1, 1, 0, 0},    // top-left -> v = 0
        };
        for (float[] c : corners) {
            buffer.putFloat(c[0]).putFloat(c[1]).putFloat(0.0f);
            buffer.putFloat(c[2]).putFloat(c[3]);
            buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
        }
        buffer.flip();
        return buffer;
    }

    /** Render the quad and return the whole colour buffer, for checks that sample several points. */
    private static ByteBuffer renderQuadPixels(MetalDevice device, RenderPipeline pipeline,
                                               GpuBuffer vertices, GpuBuffer indices,
                                               Map<String, GpuBuffer> uniforms,
                                               Map<String, GpuTextureView> textures) {
        GpuTexture color = device.createTexture("quad", GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_COPY_SRC, pipeline.getColorTargetState().format(), WIDTH, HEIGHT, 1, 1);
        GpuTextureView colorView = device.createTextureView(color);
        GpuBuffer readback = device.createBuffer(() -> "readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) WIDTH * HEIGHT * 4);
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "quad")
                .withColorAttachment(colorView, Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f)))
                .withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        CommandEncoderBackend encoder = device.createCommandEncoder();
        RenderPassBackend pass = encoder.createRenderPass(descriptor);
        pass.setPipeline(pipeline);
        for (Map.Entry<String, GpuBuffer> uniform : uniforms.entrySet()) {
            pass.setUniform(uniform.getKey(), uniform.getValue().slice());
        }
        GpuSampler sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        for (Map.Entry<String, GpuTextureView> texture : textures.entrySet()) {
            pass.bindTexture(texture.getKey(), texture.getValue(), sampler);
        }
        pass.setVertexBuffer(0, vertices.slice());
        pass.setIndexBuffer(indices, com.mojang.blaze3d.IndexType.INT);
        pass.drawIndexed(6, 1, 0, 0, 0);
        encoder.submitRenderPass();
        encoder.copyTextureToBuffer(color, readback, 0L, null, 0, 0, 0, WIDTH, HEIGHT);
        sampler.close();
        colorView.close();
        color.close();
        return ((MetalBuffer) readback).data().asByteBuffer().order(ByteOrder.nativeOrder());
    }

    /** Shared draw: render the quad and return the centre pixel as {r, g, b}. */
    private static int[] renderQuad(MetalDevice device, RenderPipeline pipeline, GpuBuffer vertices,
                                    GpuBuffer indices, Map<String, GpuBuffer> uniforms,
                                    Map<String, GpuTextureView> textures, boolean withDepth,
                                    String label, float[] clear) {
        GpuFormat colorFormat = pipeline.getColorTargetState().format();
        GpuTexture color = device.createTexture(label, GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_COPY_SRC, colorFormat, WIDTH, HEIGHT, 1, 1);
        GpuTextureView colorView = device.createTextureView(color);
        GpuTexture depth = null;
        GpuTextureView depthView = null;
        GpuBuffer readback = device.createBuffer(() -> "readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) WIDTH * HEIGHT * 4);

        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label)
                .withColorAttachment(colorView,
                        Optional.of(new Vector4f(clear[0], clear[1], clear[2], clear[3])))
                .withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        if (withDepth) {
            depth = device.createTexture(label + " depth", GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.D32_FLOAT, WIDTH, HEIGHT, 1, 1);
            depthView = device.createTextureView(depth);
            // Cleared to the far plane; the pipeline uses reversed-Z, so near is 1.0.
            descriptor.withDepthAttachment(depthView, OptionalDouble.of(0.0));
        }

        CommandEncoderBackend encoder = device.createCommandEncoder();
        RenderPassBackend pass = encoder.createRenderPass(descriptor);
        pass.setPipeline(pipeline);
        for (Map.Entry<String, GpuBuffer> uniform : uniforms.entrySet()) {
            pass.setUniform(uniform.getKey(), uniform.getValue().slice());
        }
        GpuSampler sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        for (Map.Entry<String, GpuTextureView> texture : textures.entrySet()) {
            pass.bindTexture(texture.getKey(), texture.getValue(), sampler);
        }
        pass.setVertexBuffer(0, vertices.slice());
        pass.setIndexBuffer(indices, com.mojang.blaze3d.IndexType.INT);
        pass.drawIndexed(6, 1, 0, 0, 0);
        encoder.submitRenderPass();

        encoder.copyTextureToBuffer(color, readback, 0L, null, 0, 0, 0, WIDTH, HEIGHT);
        ByteBuffer pixels = ((MetalBuffer) readback).data().asByteBuffer().order(ByteOrder.nativeOrder());
        int centre = ((HEIGHT / 2) * WIDTH + (WIDTH / 2)) * 4;
        int[] rgb = {pixels.get(centre) & 0xFF, pixels.get(centre + 1) & 0xFF,
                pixels.get(centre + 2) & 0xFF};
        readback.close();
        sampler.close();
        if (depthView != null) depthView.close();
        if (depth != null) depth.close();
        colorView.close();
        color.close();
        return rgb;
    }

    /** 4 vertices, slot 0 of the gui pipeline: Position RGB32_FLOAT then Color RGBA8_UNORM. */
    private static ByteBuffer vertexBytes() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 16).order(ByteOrder.nativeOrder());
        float[][] positions = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};   // counter-clockwise in NDC
        for (float[] p : positions) {
            buffer.putFloat(p[0]).putFloat(p[1]).putFloat(0.0f);
            buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);  // white
        }
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer indexBytes() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder());
        for (int index : new int[]{0, 1, 2, 0, 2, 3}) {
            buffer.putInt(index);
        }
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer identityMat4() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                buffer.putFloat(column == row ? 1.0f : 0.0f);
            }
        }
        buffer.flip();
        return buffer;
    }

    /** std140 DynamicTransforms: mat4 ModelViewMat, vec4 ColorModulator, vec3 ModelOffset, mat4 TextureMat. */
    private static ByteBuffer dynamicTransforms(float[] modulator) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());
        buffer.put(identityMat4());
        buffer.putFloat(modulator[0]).putFloat(modulator[1]).putFloat(modulator[2]).putFloat(modulator[3]);
        buffer.putFloat(0).putFloat(0).putFloat(0).putFloat(0);   // ModelOffset, padded to 16
        buffer.put(identityMat4());
        buffer.flip();
        return buffer;
    }

    private static String shaderPath(String identifier, ShaderType type) {
        String namespace = "minecraft";
        String path = identifier;
        int colon = identifier.indexOf(':');
        if (colon >= 0) {
            namespace = identifier.substring(0, colon);
            path = identifier.substring(colon + 1);
        }
        return "assets/" + namespace + "/shaders/" + path + (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
    }

    private static String preprocess(ZipFile zip, String path) {
        String raw = read(zip, path);
        if (raw == null) {
            return "";
        }
        java.util.Set<String> imported = new java.util.HashSet<>();
        GlslPreprocessor preprocessor = new GlslPreprocessor() {
            @Override
            public String applyImport(boolean isRelative, String importPath) {
                int colon = importPath.indexOf(':');
                String namespace = colon >= 0 ? importPath.substring(0, colon) : "minecraft";
                String location = colon >= 0 ? importPath.substring(colon + 1) : importPath;
                String key = namespace + ":" + location;
                if (!imported.add(key)) {
                    return null;
                }
                String file = location.substring(location.lastIndexOf('/') + 1);
                String text = read(zip, "assets/" + namespace + "/shaders/include/" + file);
                if (text == null) {
                    return "";
                }
                return text.replaceFirst("(?m)^\\s*#version[^\\n]*\\n", "");
            }
        };
        return String.join("\n", preprocessor.process(raw));
    }

    private static String read(ZipFile zip, String path) {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void check(String label, boolean condition, String detail) {
        System.out.println((condition ? "PASS  " : "FAIL  ") + label + (detail.isEmpty() ? "" : "  " + detail));
        if (!condition) {
            failures++;
        }
    }

    private static void report() {
        System.out.println();
        if (failures == 0) {
            System.out.println("RENDER CHECK PASSED");
        } else {
            System.out.println(failures + " RENDER CHECK(S) FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static Path defaultInstance() {
        String override = System.getenv("METALMOD_MC_INSTANCE");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"),
                "Documents/.minecraft/versions/MetalMod_Test_26.2");
    }
}
