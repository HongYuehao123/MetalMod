import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
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
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import net.metalmod.backend.MetalBuffer;
import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalNative;
import net.metalmod.backend.MetalRenderPipeline;
import net.metalmod.backend.MetalTexture;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

            // Entities. ENTITY_CUTOUT is compiled with PER_FACE_LIGHTING, so gl_FrontFacing picks
            // between the front and the back light colour - which makes a winding regression visible
            // as the dim back colour rather than as nothing. It also carries four uniform blocks
            // (Projection, DynamicTransforms, Lighting, Fog), three more chances for BUG-012's slot
            // collision to reappear, and the only vertex format in the game with a Normal attribute.
            RenderPipeline entity = (RenderPipeline) Class
                    .forName("net.minecraft.client.renderer.RenderPipelines")
                    .getField("ENTITY_CUTOUT").get(null);
            device.precompilePipeline(entity, source);
            check("entity pipeline compiled and registered", device.pipelineFor(entity) != null, "");
            if (device.pipelineFor(entity) != null) {
                entityCheck(device, entity);
            }

            // BUG-002's path. rendertype_lines.vsh expands a line in screen space:
            //   lineOffset = perpendicular * LineWidth / ScreenSize
            // and ScreenSize lives in Globals. If Globals were mis-bound (it shared a SPIR-V binding
            // with Fog before BUG-012), that divisor is garbage and the "line" becomes a huge quad -
            // which is exactly what BUG-002 describes.
            //
            // The pipeline also has to be drawn as triangles rather than as line primitives: the
            // shader offsets by gl_VertexID % 2, so four vertices make one segment's quad and
            // SECONDARY_BLOCK_OUTLINE - the block outline itself - is a LINES pipeline.
            RenderPipeline lines = (RenderPipeline) Class
                    .forName("net.minecraft.client.renderer.RenderPipelines")
                    .getField("LINES").get(null);
            device.precompilePipeline(lines, source);
            check("lines pipeline compiled and registered", device.pipelineFor(lines) != null, "");
            if (device.pipelineFor(lines) != null) {
                linesCheck(device, lines);
            }

            // Sky and sunrise/sunset. Metal has no triangle fan primitive, and the sky disc is a
            // single non-indexed fan draw, so this is where a topology mapping that is merely
            // "closest" shows up as a shape rather than as an error.
            RenderPipeline sky = (RenderPipeline) Class
                    .forName("net.minecraft.client.renderer.RenderPipelines")
                    .getField("SKY").get(null);
            device.precompilePipeline(sky, source);
            check("sky pipeline compiled and registered", device.pipelineFor(sky) != null, "");
            if (device.pipelineFor(sky) != null) {
                fanCheck(device, sky);
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

                // Mip selection. The terrain shader minifies the block atlas and samples it with
                // textureGrad/textureLod, so mips are on the hottest path in the game - and every
                // other check here uses a 1x1 texture with mipmapping off, so none of them touches
                // that code. BUG-008 was a hardcoded MTLSamplerMipFilterNotMipmapped, which nothing
                // at render level noticed.
                mipCheck(device, textured);
            }

            // copyTextureToTexture feeds the post-processing chain - the blur behind the menu is a
            // series of these - and the engine asks for sub-rectangles of it. A wrong row stride or
            // a dropped region would show up as the coarse, blocky blur BUG-001 mentions.
            textureCopyCheck(device);

            // The last mechanism BUG-001 implicates that is reachable offscreen: atlas compositing.
            // TextureAtlas composites sprites with ortho2D(0, w, 0, h), which puts atlas row 0 at
            // NDC y = -1 - a Y-down assumption. Metal's NDC is Y-up, so the engine flips the viewport
            // for targets whose label contains "/atlas/". Verify that flip actually happens, and that
            // the winding flip keeps a cull-enabled pipeline drawing through it.
            atlasFlipCheck(device, pipeline);

            // RenderPass gates multiDrawIndexed on the multiDrawDirectInterleaved feature *and* the
            // draw-count limit, throwing for either. Both were reported inaccurately, so this drives
            // Minecraft's own RenderPass wrapper - not just our backend - to prove the path is now
            // usable and that it actually draws every entry.
            multiDrawCheck(device, pipeline);

            // GUI clipping. A scissor that lands in the wrong place - or is flipped vertically -
            // would clip panels and text to the wrong region, which is the shape of BUG-001's
            // corrupted world-list entry. Metal's scissor origin is top-left, like Minecraft's.
            scissorCheck(device, pipeline);

            // Blend state. Half-alpha white over black must land halfway between the two, which
            // exercises blendEnabled, the factors and the op together.
            blendCheck(device, pipeline);

            // Every blend state vanilla actually uses. The single blend case above covers one
            // function; vanilla has ten, and BUG-006 corrected five blend-factor values that
            // vanilla never uses but a shaderpack would. All fifteen are rendered here and
            // compared against the blend equation evaluated on the CPU.
            blendMatrixCheck(device, source);

            // Index width and draw offsets. Every other check draws 32-bit indices from position 0,
            // but chunk and entity meshes are indexed with IndexType.SHORT and each chunk of a
            // shared vertex buffer is drawn by offsetting into it.
            shortIndexCheck(device, pipeline);
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
        solid(white, 255, 255, 255, 255);
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

        // The same quad with RGSS enabled. The texture is one flat colour, so rotating the sample
        // grid cannot change the answer - what this checks is that the branch runs at all, since a
        // broken textureLod, log2 or sampler state there would not produce a clean white.
        uniforms.get("Globals").close();
        uniforms.put("Globals", device.createBuffer(() -> "Globals",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, globals(true)));
        int[] rgss = renderQuad(device, pipeline, vertices, indices, uniforms, textures, true,
                "terrain rgss", new float[]{0.0f, 1.0f, 0.0f, 1.0f});
        check("terrain with RGSS filtering (UseRgss = 1) is still white -> R" + rgss[0] + " G"
                        + rgss[1] + " B" + rgss[2],
                rgss[0] > 250 && rgss[1] > 250 && rgss[2] > 250, "");

        for (GpuBuffer buffer : uniforms.values()) {
            buffer.close();
        }
        whiteView.close();
        white.close();
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

    /**
     * Draw a full-screen quad with the real entity pipeline and check the colour three times.
     *
     * <p>Everything is set so the maths is trivial: identity matrices, a white entity texture, a
     * white lightmap, and both light directions along the vertex normal, so
     * {@code minecraft_mix_light} saturates at 1. White out therefore means the 36-byte entity
     * vertex format - including the {@code Normal} attribute no other pipeline uses - and the
     * {@code Lighting} and {@code DynamicTransforms} blocks all arrived intact.
     *
     * <p>The overlay texel is opaque white because the shader blends the other way round from what
     * the name suggests: {@code color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a)}, so
     * alpha 1 keeps the entity's own colour and alpha 0 paints the overlay colour straight on.
     *
     * <p>{@code ENTITY_CUTOUT} is compiled with {@code PER_FACE_LIGHTING}, so the fragment shader
     * chooses the front or the back light colour from {@code gl_FrontFacing}. The back colour is
     * {@code Color * MINECRAFT_AMBIENT_LIGHT} = 102, so a winding regression cannot pass as white.
     *
     * <p>The second draw turns fog on. Every corner of the quad is at {@code length((1,1,1)) = 1.732},
     * and {@code sphericalVertexDistance} is {@code length(Position)} evaluated <em>per vertex</em>,
     * so the varying is the constant 1.732 across the whole quad rather than the 1.0 that the
     * interpolated position would suggest. With environmental fog from 0 to 2 that is a fog value of
     * exactly 0.866, and a blue FogColor therefore lands at 34 34 255. This is the draw that proves
     * the {@code Lighting} and {@code Fog} blocks got separate Metal slots (BUG-012): read the
     * Lighting bytes as Fog and FogColor's alpha becomes 0, which leaves the entity white.
     *
     * <p>The third draw uses a non-white ColorModulator, which is the only one of the four uniform
     * blocks the vertex and fragment stages <em>share</em> - it is bound at a different Metal slot in
     * each stage, so this proves the fragment stage's copy is bound too.
     */
    private static void entityCheck(MetalDevice device, RenderPipeline pipeline) {
        GpuTexture white = device.createTexture("entity white", GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        GpuTexture overlay = device.createTexture("entity overlay", GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        solid(white, 255, 255, 255, 255);
        solid(overlay, 255, 255, 255, 255);
        GpuTextureView whiteView = device.createTextureView(white);
        GpuTextureView overlayView = device.createTextureView(overlay);

        GpuBuffer vertices = device.createBuffer(() -> "entity vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, entityVertices());
        GpuBuffer indices = device.createBuffer(() -> "entity indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, indexBytes());
        GpuBuffer projection = device.createBuffer(() -> "Projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, identityMat4());
        GpuBuffer whiteModulator = device.createBuffer(() -> "DynamicTransforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                dynamicTransforms(new float[]{1.0f, 1.0f, 1.0f, 1.0f}));
        GpuBuffer tintedModulator = device.createBuffer(() -> "DynamicTransforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                dynamicTransforms(new float[]{0.5f, 0.25f, 1.0f, 1.0f}));
        // Both light directions point along +Z, which is the normal every vertex carries, so the
        // light sum is 1.2 and clamps to full brightness in both the front and the back colour.
        GpuBuffer lighting = device.createBuffer(() -> "Lighting",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, lighting());
        GpuBuffer noFog = device.createBuffer(() -> "Fog",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                fog(new float[]{0f, 0f, 0f, 0f}, 0f, 2f, 1000f, 2000f));
        GpuBuffer halfFog = device.createBuffer(() -> "Fog",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                fog(new float[]{0f, 0f, 1f, 1f}, 0f, 2f, 1000f, 2000f));

        Map<String, GpuTextureView> textures = new LinkedHashMap<>();
        textures.put("Sampler0", whiteView);      // entity texture
        textures.put("Sampler1", overlayView);    // overlay
        textures.put("Sampler2", whiteView);      // lightmap

        int[] lit = renderQuad(device, pipeline, vertices, indices,
                entityUniforms(projection, whiteModulator, lighting, noFog), textures, true,
                "entity", new float[]{0.0f, 1.0f, 0.0f, 1.0f});
        check("entity quad renders fully lit white, not the 102 back-face colour"
                        + " -> R" + lit[0] + " G" + lit[1] + " B" + lit[2],
                lit[0] > 250 && lit[1] > 250 && lit[2] > 250, "");

        int[] fogged = renderQuad(device, pipeline, vertices, indices,
                entityUniforms(projection, whiteModulator, lighting, halfFog), textures, true,
                "entity fog", new float[]{0.0f, 1.0f, 0.0f, 1.0f});
        check("entity fog mixes 86.6% of the FogColor over the whole quad -> R" + fogged[0]
                        + " G" + fogged[1] + " B" + fogged[2] + " (expected 34 34 255; white would"
                        + " mean the Fog block was bound to something else)",
                Math.abs(fogged[0] - 34) <= 3 && Math.abs(fogged[1] - 34) <= 3 && fogged[2] > 250, "");

        int[] tinted = renderQuad(device, pipeline, vertices, indices,
                entityUniforms(projection, tintedModulator, lighting, noFog), textures, true,
                "entity tint", new float[]{0.0f, 1.0f, 0.0f, 1.0f});
        check("entity ColorModulator reaches the fragment stage of the shared block -> R" + tinted[0]
                        + " G" + tinted[1] + " B" + tinted[2] + " (expected 128 64 255)",
                Math.abs(tinted[0] - 128) <= 3 && Math.abs(tinted[1] - 64) <= 3 && tinted[2] > 250, "");

        halfFog.close();
        noFog.close();
        lighting.close();
        tintedModulator.close();
        whiteModulator.close();
        projection.close();
        indices.close();
        vertices.close();
        overlayView.close();
        whiteView.close();
        overlay.close();
        white.close();
    }

    private static Map<String, GpuBuffer> entityUniforms(GpuBuffer projection, GpuBuffer transforms,
                                                         GpuBuffer lighting, GpuBuffer fog) {
        Map<String, GpuBuffer> uniforms = new LinkedHashMap<>();
        uniforms.put("Projection", projection);
        uniforms.put("DynamicTransforms", transforms);
        uniforms.put("Lighting", lighting);
        uniforms.put("Fog", fog);
        return uniforms;
    }

    /** Write one RGBA8 texel into a 1x1 texture, for checks that need a known sampled constant. */
    private static void solid(GpuTexture texture, int r, int g, int b, int a) {
        ByteBuffer pixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        pixel.put((byte) r).put((byte) g).put((byte) b).put((byte) a).flip();
        MetalNative.textureReplaceRegion(((MetalTexture) texture).handle(), 0, 0, 0, 0, 1, 1,
                pixel, 4L);
    }

    /**
     * 36-byte entity vertex: Position RGB32_FLOAT, Color RGBA8_UNORM, UV0 RG32_FLOAT,
     * UV1 RG16_SINT, UV2 RG16_SINT, Normal RGBA8_SNORM.
     */
    private static ByteBuffer entityVertices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 36).order(ByteOrder.nativeOrder());
        float[][] positions = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (float[] p : positions) {
            // Reversed-Z, so the near plane is 1. The corner order is the one the culling terrain
            // pipeline accepts as front-facing, so gl_FrontFacing must agree here too.
            buffer.putFloat(p[0]).putFloat(p[1]).putFloat(1.0f);
            buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);   // Color
            buffer.putFloat(0.0f).putFloat(0.0f);                      // UV0
            buffer.putShort((short) 0).putShort((short) 0);            // UV1: overlay
            buffer.putShort((short) 0).putShort((short) 0);            // UV2: lightmap
            buffer.put((byte) 0).put((byte) 0).put((byte) 127).put((byte) 0);   // Normal (0, 0, 1)
        }
        buffer.flip();
        return buffer;
    }

    /** std140 Lighting: vec3 Light0_Direction, vec3 Light1_Direction, both along +Z. */
    private static ByteBuffer lighting() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
        buffer.putFloat(0).putFloat(0).putFloat(1).putFloat(0);   // Light0_Direction + padding
        buffer.putFloat(0).putFloat(0).putFloat(1).putFloat(0);   // Light1_Direction + padding
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

    /** std140 Globals with the default texture filtering (RGSS off). */
    private static ByteBuffer globals() {
        return globals(false);
    }

    /**
     * std140 Globals: ivec3 CameraBlockPos, vec3 CameraOffset, vec2 ScreenSize, then four scalars.
     *
     * <p>{@code UseRgss} is {@code textureFiltering == RGSS} in the client's video settings; the
     * default is {@code NONE}, so terrain normally takes the {@code sampleNearest} branch of
     * {@code terrain.fsh}. Both are rendered here, because RGSS swaps in {@code log2},
     * {@code textureLod}, {@code smoothstep} and a {@code const vec2[]} array - a whole extra MSL
     * surface that nothing else touches.
     */
    private static ByteBuffer globals(boolean useRgss) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        buffer.putInt(0).putInt(0).putInt(0).putInt(0);          // CameraBlockPos + padding
        buffer.putFloat(0).putFloat(0).putFloat(0).putFloat(0);  // CameraOffset + padding
        buffer.putFloat(WIDTH).putFloat(HEIGHT);                 // ScreenSize
        buffer.putFloat(1.0f).putFloat(0.0f);                    // GlintAlpha, GameTime
        buffer.putInt(0).putInt(useRgss ? 1 : 0);                // MenuBlurRadius, UseRgss
        buffer.flip();
        return buffer;
    }

    /** std140 Fog: vec4 FogColor then six floats; the distances are far away so fog contributes nothing. */
    private static ByteBuffer fog() {
        return fog(new float[]{1f, 1f, 1f, 1f}, 1000f, 2000f, 1000f, 2000f);
    }

    /** std140 Fog with explicit values, for checks where fog has to contribute a known amount. */
    private static ByteBuffer fog(float[] color, float environmentalStart, float environmentalEnd,
                                  float renderDistanceStart, float renderDistanceEnd) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
        buffer.putFloat(color[0]).putFloat(color[1]).putFloat(color[2]).putFloat(color[3]);
        buffer.putFloat(environmentalStart).putFloat(environmentalEnd);
        buffer.putFloat(renderDistanceStart).putFloat(renderDistanceEnd);
        buffer.putFloat(2000).putFloat(2000);                     // sky end, clouds end
        buffer.flip();
        return buffer;
    }

    /**
     * Draw one line segment the way Minecraft does and check that it lands as a filled band.
     *
     * <p>{@code rendertype_lines} is <em>not</em> a line primitive. {@code rendertype_lines.vsh}
     * takes {@code Position} as the segment start and {@code Position + Normal} as its end, then
     * offsets the vertex perpendicular to the segment by {@code +/- LineWidth / ScreenSize} according
     * to {@code gl_VertexID % 2}. Four vertices therefore make one segment's quad, and Minecraft's own
     * {@code PrimitiveTopology.LINES} reports {@code indexCount(4) == 6} - the quad index pattern,
     * the same as {@code QUADS} - which is why the Vulkan backend maps it to a triangle list.
     *
     * <p>Drawing that as {@code MTLPrimitiveTypeLine} instead pairs the indices: (0,1) and (2,3)
     * become two short perpendicular ticks at the segment's ends and (2,0) becomes the band's top
     * edge, so the inside of the band is empty. The three sample points are chosen so that the wrong
     * topology fails, and so that a giant band - BUG-002's other candidate, from a mis-bound
     * {@code ScreenSize} in {@code Globals} - fails too.
     */
    private static void linesCheck(MetalDevice device, RenderPipeline pipeline) {
        GpuBuffer vertices = device.createBuffer(() -> "line vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, lineVertices());
        GpuBuffer indices = device.createBuffer(() -> "line indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, lineIndexBytes());
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

        // ScreenSize is 64 and LineWidth is 16, so the band is 16px tall: rows 24..40 of 64. The
        // centre row is inside it, a row 4px out is still inside, and a row 12px out is not.
        int[] rows = renderLines(device, pipeline, vertices, indices, uniforms);
        check("line band covers a row 4px above its centre -> R" + rows[0]
                        + " (line primitives would draw only the two edges and a diagonal)",
                rows[0] > 200, "");
        check("line band covers a row 4px below its centre -> R" + rows[1], rows[1] > 200, "");
        check("line band does not reach a row 12px outside it -> R" + rows[2]
                        + " (a giant band would)", rows[2] < 60, "");

        indices.close();
        vertices.close();
        for (GpuBuffer buffer : uniforms.values()) {
            buffer.close();
        }
    }

    /**
     * Four 24-byte line vertices making one segment: Position RGB32_FLOAT, Color RGBA8_UNORM,
     * Normal RGBA8_SNORM, LineWidth F32.
     *
     * <p>Start, start, end, end. The shader takes the segment's direction from
     * {@code Position + Normal} and only the normalised direction matters, so repeating each endpoint
     * and carrying the segment vector in {@code Normal} is what one segment's four vertices are.
     */
    private static ByteBuffer lineVertices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 24).order(ByteOrder.nativeOrder());
        for (float x : new float[]{-0.8f, -0.8f, 0.8f, 0.8f}) {
            buffer.putFloat(x).putFloat(0.0f).putFloat(1.0f);        // Position, reversed-Z near plane
            buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
            buffer.put((byte) 127).put((byte) 0).put((byte) 0).put((byte) 0);   // Normal (1, 0, 0)
            buffer.putFloat(16.0f);                                  // LineWidth in pixels
        }
        buffer.flip();
        return buffer;
    }

    /**
     * The six indices Minecraft uses for one line segment: its {@code sharedSequentialLines}
     * generator emits {@code i, i+1, i+2, i+3, i+2, i+1}.
     *
     * <p>It is not the quad pattern. {@code BufferBuilder} duplicates every vertex written to a
     * {@code LINES} buffer, so the four vertices of a segment are start, start, end, end, and with
     * the shader's alternating offset the corners land in the zig-zag order top-left, bottom-left,
     * top-right, bottom-right. The quad pattern {@code 0,1,2,0,2,3} over that order leaves a
     * V-shaped hole through the middle of the band; {@code 0,1,2,3,2,1} tiles it.
     */
    private static ByteBuffer lineIndexBytes() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder());
        for (int index : new int[]{0, 1, 2, 3, 2, 1}) {
            buffer.putInt(index);
        }
        buffer.flip();
        return buffer;
    }

    /** Render the band and return rows 4px above and below the centre and 12px outside it, as red. */
    private static int[] renderLines(MetalDevice device, RenderPipeline pipeline, GpuBuffer vertices,
                                     GpuBuffer indices, Map<String, GpuBuffer> uniforms) {
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
        pass.setIndexBuffer(indices, com.mojang.blaze3d.IndexType.INT);
        pass.drawIndexed(6, 1, 0, 0, 0);
        encoder.submitRenderPass();

        encoder.copyTextureToBuffer(color, readback, 0L, null, 0, 0, 0, WIDTH, HEIGHT);
        ByteBuffer pixels = ((MetalBuffer) readback).data().asByteBuffer().order(ByteOrder.nativeOrder());
        int aboveRow = red(pixels, WIDTH / 2, HEIGHT / 2 - 4);
        int belowRow = red(pixels, WIDTH / 2, HEIGHT / 2 + 4);
        int outsideRow = red(pixels, WIDTH / 2, HEIGHT / 2 - 12);
        readback.close();
        depthView.close();
        depth.close();
        colorView.close();
        color.close();
        return new int[]{aboveRow, belowRow, outsideRow};
    }

    /**
     * Draw a triangle fan the way the sky does, and check it fills the disc.
     *
     * <p>Metal has no fan primitive, so the backend expands one into an indexed triangle list. A
     * triangle <em>list</em> over the same ten vertices - which is what a plain topology mapping
     * gives - draws (0,1,2), (3,4,5) and (6,7,8) instead of the eight wedges around the centre, so
     * the middle of the disc comes out empty. Sampling the centre and eight points around it
     * separates the two. {@code SkyRenderer.renderSkyDisc} makes exactly this call, one non-indexed
     * {@code draw(10, 1, 0, 0)} of a centre plus nine rim vertices.
     */
    private static void fanCheck(MetalDevice device, RenderPipeline pipeline) {
        GpuBuffer vertices = device.createBuffer(() -> "fan vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, fanVertices());
        Map<String, GpuBuffer> uniforms = new LinkedHashMap<>();
        uniforms.put("Projection", device.createBuffer(() -> "Projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, identityMat4()));
        uniforms.put("DynamicTransforms", device.createBuffer(() -> "DynamicTransforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                dynamicTransforms(new float[]{1.0f, 1.0f, 1.0f, 1.0f})));
        // FogColor's alpha is 0, and sky.fsh colours the fragment with ColorModulator, so this both
        // keeps the output white and checks the Fog block is bound.
        uniforms.put("Fog", device.createBuffer(() -> "Fog",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                fog(new float[]{0f, 0f, 0f, 0f}, 0f, 1000f, 0f, 1000f)));

        ByteBuffer pixels = renderFanPixels(device, pipeline, vertices, 10, uniforms);
        int centre = red(pixels, WIDTH / 2, HEIGHT / 2);
        check("triangle fan covers its centre -> R" + centre
                        + " (a triangle list over the same 10 vertices leaves it empty)",
                centre > 200, "");

        int lit = 0;
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45.0);
            int x = WIDTH / 2 + (int) Math.round(16 * Math.cos(angle));
            int y = HEIGHT / 2 - (int) Math.round(16 * Math.sin(angle));
            if (red(pixels, x, y) > 200) {
                lit++;
            }
        }
        check("triangle fan covers all 8 sampled points around the centre -> " + lit + "/8", lit == 8, "");

        int outside = red(pixels, WIDTH - 4, HEIGHT - 4);
        check("nothing is drawn outside the fan's radius -> R" + outside, outside < 60, "");

        vertices.close();
        for (GpuBuffer buffer : uniforms.values()) {
            buffer.close();
        }
    }

    /**
     * Ten 12-byte sky vertices: a centre and nine rim points spanning the full circle, so the last
     * rim point repeats the first and the fan closes into a disc of 8 wedges. That is the shape
     * {@code SkyRenderer.renderSkyDisc} builds - it steps from -180 to +180 inclusive, where the two
     * ends are the same point - and a fan that stops one wedge short does not fill the disc.
     */
    private static ByteBuffer fanVertices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(10 * 12).order(ByteOrder.nativeOrder());
        buffer.putFloat(0.0f).putFloat(0.0f).putFloat(1.0f);          // centre, reversed-Z near plane
        for (int i = 0; i < 9; i++) {
            double angle = Math.toRadians(i * 45.0);
            buffer.putFloat((float) (0.8 * Math.cos(angle)));
            buffer.putFloat((float) (0.8 * Math.sin(angle)));
            buffer.putFloat(1.0f);
        }
        buffer.flip();
        return buffer;
    }

    /** Render one non-indexed draw and return the whole colour buffer, for checks that sample widely. */
    private static ByteBuffer renderFanPixels(MetalDevice device, RenderPipeline pipeline,
                                              GpuBuffer vertices, int vertexCount,
                                              Map<String, GpuBuffer> uniforms) {
        GpuTexture color = device.createTexture("fan", GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_COPY_SRC, pipeline.getColorTargetState().format(), WIDTH, HEIGHT, 1, 1);
        GpuTextureView colorView = device.createTextureView(color);
        GpuBuffer readback = device.createBuffer(() -> "readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) WIDTH * HEIGHT * 4);
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "fan")
                .withColorAttachment(colorView, Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f)))
                .withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        CommandEncoderBackend encoder = device.createCommandEncoder();
        RenderPassBackend pass = encoder.createRenderPass(descriptor);
        pass.setPipeline(pipeline);
        for (Map.Entry<String, GpuBuffer> uniform : uniforms.entrySet()) {
            pass.setUniform(uniform.getKey(), uniform.getValue().slice());
        }
        pass.setVertexBuffer(0, vertices.slice());
        pass.draw(vertexCount, 1, 0, 0);
        encoder.submitRenderPass();
        encoder.copyTextureToBuffer(color, readback, 0L, null, 0, 0, 0, WIDTH, HEIGHT);
        colorView.close();
        color.close();
        return ((MetalBuffer) readback).data().asByteBuffer().order(ByteOrder.nativeOrder());
    }

    /** The red channel of one pixel of a readback buffer. */
    private static int red(ByteBuffer pixels, int x, int y) {
        return pixels.get((y * WIDTH + x) * 4) & 0xFF;
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

    /**
     * Verify texture-to-texture copies, both whole and by rectangle.
     *
     * <p>The pattern is distinct per texel so a wrong row stride, a shifted origin or a region
     * written to the wrong place all produce a visible mismatch rather than an accidental pass.
     */
    private static void textureCopyCheck(MetalDevice device) {
        final int SIZE = 4;
        byte[] pattern = new byte[SIZE * SIZE * 4];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int at = (y * SIZE + x) * 4;
                pattern[at] = (byte) (x * 60 + 10);
                pattern[at + 1] = (byte) (y * 60 + 20);
                pattern[at + 2] = (byte) (x * 16 + y * 4 + 30);
                pattern[at + 3] = (byte) 255;
            }
        }
        GpuTexture source = device.createTexture("copy source", GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_SRC, GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
        GpuTexture target = device.createTexture("copy target", GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
        upload(source, pattern, SIZE);

        CommandEncoderBackend encoder = device.createCommandEncoder();
        encoder.copyTextureToTexture(source, target, 0, 0, 0, 0, SIZE, SIZE, 0);
        check("copyTextureToTexture copies the whole texture byte for byte",
                java.util.Arrays.equals(pattern, readback(target, SIZE)), "");

        // Now a 2x2 rectangle at (1,1): only those texels may change in the target.
        byte[] replacement = new byte[SIZE * SIZE * 4];
        for (int y = 1; y < 3; y++) {
            for (int x = 1; x < 3; x++) {
                int at = (y * SIZE + x) * 4;
                replacement[at] = (byte) 200;
                replacement[at + 1] = (byte) 100;
                replacement[at + 2] = (byte) 50;
                replacement[at + 3] = (byte) 255;
            }
        }
        // The source must actually differ in that rectangle, or the copy has nothing to change.
        byte[] sourcePattern = pattern.clone();
        for (int y = 1; y < 3; y++) {
            for (int x = 1; x < 3; x++) {
                int at = (y * SIZE + x) * 4;
                sourcePattern[at] = (byte) 200;
                sourcePattern[at + 1] = (byte) 100;
                sourcePattern[at + 2] = (byte) 50;
            }
        }
        upload(source, sourcePattern, SIZE);
        CommandEncoderBackend encoder2 = device.createCommandEncoder();
        encoder2.copyTextureToTexture(source, target, 0, 0, 1, 1, 2, 2, 0);

        byte[] expected = pattern.clone();
        for (int y = 1; y < 3; y++) {
            for (int x = 1; x < 3; x++) {
                int at = (y * SIZE + x) * 4;
                expected[at] = (byte) 200;
                expected[at + 1] = (byte) 100;
                expected[at + 2] = (byte) 50;
                expected[at + 3] = (byte) 255;
            }
        }
        byte[] got = readback(target, SIZE);
        boolean onlyRect = java.util.Arrays.equals(expected, got);
        check("a 2x2 copy at (1,1) changes exactly that rectangle", onlyRect, describe(got, SIZE));

        source.close();
        target.close();
    }

    private static void upload(GpuTexture texture, byte[] bytes, int size) {
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            java.lang.foreign.MemorySegment segment = arena.allocate(bytes.length);
            java.lang.foreign.MemorySegment.copy(
                    java.lang.foreign.MemorySegment.ofArray(bytes), 0L, segment, 0L, bytes.length);
            MetalNative.textureReplaceRegionRaw(((MetalTexture) texture).handle(), 0, 0, 0, 0, size, size,
                    segment, (long) size * 4);
        }
    }

    private static byte[] readback(GpuTexture texture, int size) {
        byte[] bytes = new byte[size * size * 4];
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            java.lang.foreign.MemorySegment segment = arena.allocate(bytes.length);
            if (MetalNative.textureReadRegion(((MetalTexture) texture).handle(), 0, 0, 0, 0, size, size,
                    segment, bytes.length, (long) size * 4) != 0) {
                return new byte[0];
            }
            java.lang.foreign.MemorySegment.copy(segment, 0L,
                    java.lang.foreign.MemorySegment.ofArray(bytes), 0L, bytes.length);
        }
        return bytes;
    }

    /** Render the difference as a grid of 0/1 so a mismatch says where, not just that. */
    private static String describe(byte[] got, int size) {
        if (got.length == 0) {
            return "(readback failed)";
        }
        StringBuilder out = new StringBuilder();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                out.append(String.format("%02x%02x ", got[(y * size + x) * 4] & 0xFF,
                        got[(y * size + x) * 4 + 1] & 0xFF));
            }
            out.append("\n      ");
        }
        return out.toString();
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

    /**
     * Render a quad covering only NDC y in [-1, 0] into a target labelled as an atlas, and check
     * where it lands.
     *
     * <p>MC's atlas projection maps its own row 0 to NDC y = -1. In a Y-down NDC that is the top row;
     * in Metal's Y-up NDC it would be the bottom, so the engine flips the viewport for
     * {@code /atlas/} targets. With the flip, NDC y = -1 must land in framebuffer row 0. The pipeline
     * used here culls, so this also proves the winding flip that a negative viewport requires.
     */
    private static void atlasFlipCheck(MetalDevice device, RenderPipeline pipeline) {
        // The label is what MetalCommandEncoderBackend keys the flip on, so it has to look like the
        // engine's own atlas labels.
        GpuTexture target = device.createTexture("minecraft:textures/atlas/render-check.png",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
        GpuTextureView view = device.createTextureView(target);
        GpuBuffer vertices = device.createBuffer(() -> "half quad",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, halfQuadVertices());
        GpuBuffer indices = device.createBuffer(() -> "half quad indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, indexBytes());
        GpuBuffer projection = device.createBuffer(() -> "Projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, identityMat4());
        GpuBuffer transforms = device.createBuffer(() -> "DynamicTransforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                dynamicTransforms(new float[]{1.0f, 0.0f, 0.0f, 1.0f}));   // red
        GpuBuffer readback = device.createBuffer(() -> "readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) WIDTH * HEIGHT * 4);

        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "atlas")
                .withColorAttachment(view, Optional.of(new Vector4f(0.0f, 1.0f, 0.0f, 1.0f)))  // green
                .withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        CommandEncoderBackend encoder = device.createCommandEncoder();
        RenderPassBackend pass = encoder.createRenderPass(descriptor);
        pass.setPipeline(pipeline);
        pass.setUniform("Projection", projection.slice());
        pass.setUniform("DynamicTransforms", transforms.slice());
        pass.setVertexBuffer(0, vertices.slice());
        pass.setIndexBuffer(indices, com.mojang.blaze3d.IndexType.INT);
        pass.drawIndexed(6, 1, 0, 0, 0);
        encoder.submitRenderPass();
        encoder.copyTextureToBuffer(target, readback, 0L, null, 0, 0, 0, WIDTH, HEIGHT);

        ByteBuffer pixels = ((MetalBuffer) readback).data().asByteBuffer().order(ByteOrder.nativeOrder());
        int topRow = pixels.get((8 * WIDTH + WIDTH / 2) * 4) & 0xFF;
        int topRowG = pixels.get((8 * WIDTH + WIDTH / 2) * 4 + 1) & 0xFF;
        int bottomRow = pixels.get((56 * WIDTH + WIDTH / 2) * 4) & 0xFF;
        int bottomRowG = pixels.get((56 * WIDTH + WIDTH / 2) * 4 + 1) & 0xFF;
        check("atlas target: NDC y=-1 lands in framebuffer row 0 (red at the top, was green if unflipped)"
                        + " -> top R" + topRow + " G" + topRowG + " / bottom R" + bottomRow + " G"
                        + bottomRowG,
                topRow > 200 && topRowG < 60 && bottomRowG > 200 && bottomRow < 60, "");

        readback.close();
        transforms.close();
        projection.close();
        indices.close();
        vertices.close();
        view.close();
        target.close();
    }

    /**
     * Draw two quads in one {@code multiDrawIndexed} call through Minecraft's {@code RenderPass}.
     *
     * <p>This is the path the feature flags gate. Before they were reported honestly, the wrapper
     * threw before reaching the backend at all, so this could not be called; now it must draw both
     * entries - left half and right half - from one index buffer.
     */
    private static void multiDrawCheck(MetalDevice device, RenderPipeline pipeline) {
        GpuTexture target = device.createTexture("multidraw", GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_COPY_SRC, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
        GpuTextureView view = device.createTextureView(target);
        GpuBuffer vertices = device.createBuffer(() -> "two quads",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, twoQuadVertices());
        GpuBuffer indices = device.createBuffer(() -> "two quad indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, twoQuadIndices());
        GpuBuffer projection = device.createBuffer(() -> "Projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, identityMat4());
        GpuBuffer transforms = device.createBuffer(() -> "DynamicTransforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                dynamicTransforms(new float[]{0.0f, 0.0f, 1.0f, 1.0f}));   // blue
        GpuBuffer readback = device.createBuffer(() -> "readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) WIDTH * HEIGHT * 4);

        Optional<Vector4fc> clear = Optional.of((Vector4fc) new Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
        java.util.List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments =
                new java.util.ArrayList<>();
        attachments.add(new RenderPassDescriptor.Attachment<>(view, clear));

        CommandEncoderBackend encoder = device.createCommandEncoder();
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "multidraw")
                .withColorAttachment(view, clear)
                .withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        RenderPassBackend backend = encoder.createRenderPass(descriptor);
        // Minecraft's own wrapper, so its feature and limit guards are exercised too.
        RenderPass pass = new RenderPass(backend, device, attachments, () -> {
        }, new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        pass.setPipeline(pipeline);
        pass.setUniform("Projection", projection.slice());
        pass.setUniform("DynamicTransforms", transforms.slice());
        pass.setVertexBuffer(0, vertices.slice());
        pass.setIndexBuffer(indices, com.mojang.blaze3d.IndexType.INT);
        pass.multiDrawIndexed(java.nio.IntBuffer.wrap(new int[]{0, 6}), 6, 1, 0);
        pass.close();
        encoder.submitRenderPass();
        encoder.copyTextureToBuffer(target, readback, 0L, null, 0, 0, 0, WIDTH, HEIGHT);

        ByteBuffer pixels = ((MetalBuffer) readback).data().asByteBuffer().order(ByteOrder.nativeOrder());
        int left = pixels.get((HEIGHT / 2 * WIDTH + WIDTH / 4) * 4 + 2) & 0xFF;
        int right = pixels.get((HEIGHT / 2 * WIDTH + 3 * WIDTH / 4) * 4 + 2) & 0xFF;
        check("multiDrawIndexed drew both entries (left B" + left + ", right B" + right + ")",
                left > 200 && right > 200, "");

        readback.close();
        transforms.close();
        projection.close();
        indices.close();
        vertices.close();
        view.close();
        target.close();
    }

    /**
     * Draw a full-screen quad with the scissor set to one quadrant and check that only it is drawn.
     *
     * <p>The scissor is the top-left quadrant in the render area's coordinates. If the rect were
     * translated or flipped on the way to Metal, the coloured quadrant would move with it.
     */
    private static void scissorCheck(MetalDevice device, RenderPipeline pipeline) {
        GpuTexture target = device.createTexture("scissor", GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_COPY_SRC, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
        GpuTextureView view = device.createTextureView(target);
        GpuBuffer vertices = device.createBuffer(() -> "scissor quad",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBytes());
        GpuBuffer indices = device.createBuffer(() -> "scissor indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, indexBytes());
        GpuBuffer projection = device.createBuffer(() -> "Projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, identityMat4());
        GpuBuffer transforms = device.createBuffer(() -> "DynamicTransforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                dynamicTransforms(new float[]{0.0f, 0.0f, 1.0f, 1.0f}));   // blue
        GpuBuffer readback = device.createBuffer(() -> "readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) WIDTH * HEIGHT * 4);

        Optional<Vector4fc> clear = Optional.of((Vector4fc) new Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
        java.util.List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments =
                new java.util.ArrayList<>();
        attachments.add(new RenderPassDescriptor.Attachment<>(view, clear));

        CommandEncoderBackend encoder = device.createCommandEncoder();
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "scissor")
                .withColorAttachment(view, clear)
                .withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        RenderPassBackend backend = encoder.createRenderPass(descriptor);
        RenderPass pass = new RenderPass(backend, device, attachments, () -> {
        }, new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        pass.setPipeline(pipeline);
        pass.setUniform("Projection", projection.slice());
        pass.setUniform("DynamicTransforms", transforms.slice());
        pass.setVertexBuffer(0, vertices.slice());
        pass.setIndexBuffer(indices, com.mojang.blaze3d.IndexType.INT);
        pass.enableScissor(0, 0, WIDTH / 2, HEIGHT / 2);
        pass.drawIndexed(6, 1, 0, 0, 0);
        pass.close();
        encoder.submitRenderPass();
        encoder.copyTextureToBuffer(target, readback, 0L, null, 0, 0, 0, WIDTH, HEIGHT);

        ByteBuffer pixels = ((MetalBuffer) readback).data().asByteBuffer().order(ByteOrder.nativeOrder());
        int inside = pixels.get(((HEIGHT / 4) * WIDTH + (WIDTH / 4)) * 4 + 2) & 0xFF;
        int outside = pixels.get(((3 * HEIGHT / 4) * WIDTH + (3 * WIDTH / 4)) * 4 + 2) & 0xFF;
        check("scissor(0,0,32,32) draws the top-left quadrant (B" + inside
                + ") and nothing else (opposite quadrant B" + outside + ")",
                inside > 200 && outside < 60, "");

        readback.close();
        transforms.close();
        projection.close();
        indices.close();
        vertices.close();
        view.close();
        target.close();
    }

    /**
     * Draw 50%-alpha white over black and check the result is midway.
     *
     * <p>A wrong blend factor or op moves the answer: ONE/ONE would be white, ZERO would leave black.
     */
    private static void blendCheck(MetalDevice device, RenderPipeline pipeline) {
        GpuTexture target = device.createTexture("blend", GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_COPY_SRC, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
        GpuTextureView view = device.createTextureView(target);
        GpuBuffer vertices = device.createBuffer(() -> "half-alpha quad",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, halfAlphaVertices());
        GpuBuffer indices = device.createBuffer(() -> "blend indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, indexBytes());
        Map<String, GpuBuffer> uniforms = new LinkedHashMap<>();
        uniforms.put("Projection", device.createBuffer(() -> "Projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, identityMat4()));
        uniforms.put("DynamicTransforms", device.createBuffer(() -> "DynamicTransforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                dynamicTransforms(new float[]{1.0f, 1.0f, 1.0f, 1.0f})));
        Map<String, GpuTextureView> textures = new LinkedHashMap<>();

        ByteBuffer pixels = renderQuadPixels(device, pipeline, vertices, indices, uniforms, textures);
        int centre = pixels.get(((HEIGHT / 2) * WIDTH + (WIDTH / 2)) * 4) & 0xFF;
        check("50%-alpha white over black blends to mid grey -> R" + centre + " (expected ~128)",
                centre > 120 && centre < 136, "");

        indices.close();
        vertices.close();
        view.close();
        target.close();
    }

    /**
     * Sample a mipmapped texture under minification, with and without mip filtering.
     *
     * <p>The texture's four levels are solid red, green, blue and white. The quad covers a quarter of
     * the target while mapping the whole of a 64x64 texture onto it, so each output pixel covers
     * sixteen texels and the level of detail is around 2 - the result must be blue, not red.
     *
     * <p>That is what makes this a test of mip <em>filtering</em> and not just of the mip chain: with
     * {@code maxLod} absent the sampler is {@code MTLSamplerMipFilterNotMipmapped} (BUG-008's
     * hardcoded value), every sample comes from level 0, and the same draw comes back red. The pair
     * of assertions pins both directions.
     */
    private static void mipCheck(MetalDevice device, RenderPipeline pipeline) {
        final int SIZE = 64;
        // Every level down to 1x1, because Metal will not sample a texture whose mip chain is
        // incomplete - it returns black, which is what the first version of this check saw.
        final int LEVELS = 7;
        GpuTexture texture = device.createTexture("mips", GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, LEVELS);
        int[][] levelColours = {
                {255, 0, 0, 255}, {0, 255, 0, 255}, {0, 0, 255, 255},
                {255, 255, 255, 255}, {255, 255, 255, 255}, {255, 255, 255, 255}, {255, 255, 255, 255},
        };
        for (int level = 0; level < LEVELS; level++) {
            int extent = SIZE >> level;
            ByteBuffer texels = ByteBuffer.allocateDirect(extent * extent * 4).order(ByteOrder.nativeOrder());
            for (int i = 0; i < extent * extent; i++) {
                texels.put((byte) levelColours[level][0]).put((byte) levelColours[level][1])
                        .put((byte) levelColours[level][2]).put((byte) levelColours[level][3]);
            }
            texels.flip();
            MetalNative.textureReplaceRegion(((MetalTexture) texture).handle(), level, 0, 0, 0,
                    extent, extent, texels, (long) extent * 4);
        }
        GpuTextureView view = device.createTextureView(texture);

        GpuBuffer vertices = device.createBuffer(() -> "mip vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, mipVertices());
        GpuBuffer indices = device.createBuffer(() -> "mip indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, indexBytes());
        Map<String, GpuBuffer> uniforms = new LinkedHashMap<>();
        uniforms.put("Projection", device.createBuffer(() -> "Projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, identityMat4()));
        uniforms.put("DynamicTransforms", device.createBuffer(() -> "DynamicTransforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                dynamicTransforms(new float[]{1.0f, 1.0f, 1.0f, 1.0f})));
        Map<String, GpuTextureView> textures = new LinkedHashMap<>();
        textures.put("Sampler0", view);

        int[] filtered = renderMipQuad(device, pipeline, vertices, indices, uniforms, textures, 4.0);
        check("a minified mipmapped texture samples a higher mip -> R" + filtered[0] + " G" + filtered[1]
                        + " B" + filtered[2] + " (level 0 alone would be red)",
                filtered[2] > 200 && filtered[0] < 60, "");

        int[] unfiltered = renderMipQuad(device, pipeline, vertices, indices, uniforms, textures, Double.NaN);
        check("the same draw with no maxLod stays on level 0 -> R" + unfiltered[0] + " G" + unfiltered[1]
                        + " B" + unfiltered[2],
                unfiltered[0] > 200 && unfiltered[2] < 60, "");

        view.close();
        indices.close();
        vertices.close();
        for (GpuBuffer buffer : uniforms.values()) {
            buffer.close();
        }
        texture.close();
    }

    /**
     * Draw a quarter-screen quad mapping the whole texture onto 16x16 pixels, so the texture is
     * minified four-to-one per axis, and return the centre pixel.
     */
    private static int[] renderMipQuad(MetalDevice device, RenderPipeline pipeline, GpuBuffer vertices,
                                       GpuBuffer indices, Map<String, GpuBuffer> uniforms,
                                       Map<String, GpuTextureView> textures, double maxLod) {
        GpuTexture color = device.createTexture("mip target", GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_COPY_SRC, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
        GpuTextureView colorView = device.createTextureView(color);
        GpuBuffer readback = device.createBuffer(() -> "readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) WIDTH * HEIGHT * 4);
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "mips")
                .withColorAttachment(colorView, Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f)))
                .withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        CommandEncoderBackend encoder = device.createCommandEncoder();
        RenderPassBackend pass = encoder.createRenderPass(descriptor);
        pass.setPipeline(pipeline);
        for (Map.Entry<String, GpuBuffer> uniform : uniforms.entrySet()) {
            pass.setUniform(uniform.getKey(), uniform.getValue().slice());
        }
        // A present maxLod is what turns mip filtering on; it is also how the engine asks for it.
        OptionalDouble lod = Double.isNaN(maxLod) ? OptionalDouble.empty() : OptionalDouble.of(maxLod);
        GpuSampler sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, lod);
        for (Map.Entry<String, GpuTextureView> texture : textures.entrySet()) {
            pass.bindTexture(texture.getKey(), texture.getValue(), sampler);
        }
        pass.setVertexBuffer(0, vertices.slice());
        pass.setIndexBuffer(indices, com.mojang.blaze3d.IndexType.INT);
        pass.drawIndexed(6, 1, 0, 0, 0);
        encoder.submitRenderPass();
        encoder.copyTextureToBuffer(color, readback, 0L, null, 0, 0, 0, WIDTH, HEIGHT);

        ByteBuffer pixels = ((MetalBuffer) readback).data().asByteBuffer().order(ByteOrder.nativeOrder());
        int at = ((HEIGHT / 2) * WIDTH + (WIDTH / 2)) * 4;
        int[] rgb = {pixels.get(at) & 0xFF, pixels.get(at + 1) & 0xFF, pixels.get(at + 2) & 0xFF};
        readback.close();
        sampler.close();
        colorView.close();
        color.close();
        return rgb;
    }

    /** 24-byte position_tex_color vertices covering NDC [-0.25, 0.25], mapping UV 0..1 across it. */
    private static ByteBuffer mipVertices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 24).order(ByteOrder.nativeOrder());
        float[][] corners = {
                {-0.25f, -0.25f, 0, 1},
                {0.25f, -0.25f, 1, 1},
                {0.25f, 0.25f, 1, 0},
                {-0.25f, 0.25f, 0, 0},
        };
        for (float[] c : corners) {
            buffer.putFloat(c[0]).putFloat(c[1]).putFloat(0.0f);
            buffer.putFloat(c[2]).putFloat(c[3]);
            buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
        }
        buffer.flip();
        return buffer;
    }

    /**
     * Render every blend state vanilla uses, plus the five factors BUG-006 corrected, and compare
     * each result against the blend equation evaluated on the CPU.
     *
     * <p>This is the one place where a wrong {@code MTLBlendFactor} or {@code MTLBlendOperation}
     * cannot hide. Wrong blending never throws and is easy to miss in a still frame, which is how
     * BUG-006 survived an SDK-header audit of every other table: the factors are only wrong for
     * pipelines that use them, and vanilla's ten blend functions happen to use the eight factors that
     * were already right.
     *
     * <p>The ten vanilla functions are read from a real pipeline that uses each one, so the test
     * cannot drift from what the engine asks for. The five extra cases are built here because
     * nothing in vanilla uses them - they are exactly what Phase 7's shaderpacks will hit, and this
     * is their only render-level evidence. The blend colour is not exposed by the {@code RenderPass}
     * API, so it stays at Metal's default of {@code (0,0,0,0)} and the four constant factors reduce
     * to 0 or 1 - which is still enough to tell each of them from the value BUG-006 had in its slot.
     *
     * <p>Every case draws through the same {@code core/gui} shader pair, so only the blend state
     * differs: source colour {@code (0.75, 0.5, 0.25, 0.75)} over a clear of
     * {@code (0.25, 0.5, 0.75, 0.5)}, chosen so the factors pull the four channels apart.
     */
    private static void blendMatrixCheck(MetalDevice device, ShaderSource source) throws Exception {
        List<Object[]> cases = new ArrayList<>();
        for (String[] entry : new String[][]{
                {"CRUMBLING", "solid terrain-adjacent multiply"},
                {"ENERGY_SWIRL", "additive"},
                {"GUI_TEXTURED_PREMULTIPLIED_ALPHA", "premultiplied alpha"},
                {"GUI_INVERT", "invert"},
                {"WORLD_BORDER", "additive by alpha"},
                {"LIGHTNING", "additive, alpha in both equations"},
                {"TRANSLUCENT_TERRAIN", "translucent"},
                {"ENTITY_OUTLINE_BLIT", "translucent colour, keep destination alpha"},
                {"GLINT", "source colour over destination"},
                {"VIGNETTE", "destination faded by the inverse source"},
        }) {
            RenderPipeline vanilla = (RenderPipeline) Class
                    .forName("net.minecraft.client.renderer.RenderPipelines")
                    .getField(entry[0]).get(null);
            cases.add(new Object[]{entry[0] + " (" + entry[1] + ")",
                    vanilla.getColorTargetState().blendFunction().orElseThrow()});
        }
        // The five factors BUG-006 corrected, in the same slots vanilla would have got wrong.
        cases.add(new Object[]{"SRC_ALPHA_SATURATE (was OneMinusBlendAlpha)",
                new BlendFunction(BlendFactor.SRC_ALPHA_SATURATE, BlendFactor.ONE_MINUS_SRC_ALPHA)});
        cases.add(new Object[]{"CONSTANT_COLOR (was SourceAlphaSaturated)",
                new BlendFunction(BlendFactor.CONSTANT_COLOR, BlendFactor.ONE_MINUS_SRC_ALPHA)});
        cases.add(new Object[]{"ONE_MINUS_CONSTANT_COLOR (was BlendColor)",
                new BlendFunction(BlendFactor.ONE_MINUS_CONSTANT_COLOR, BlendFactor.ONE_MINUS_SRC_ALPHA)});
        cases.add(new Object[]{"CONSTANT_ALPHA (was OneMinusBlendColor)",
                new BlendFunction(BlendFactor.CONSTANT_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA)});
        cases.add(new Object[]{"ONE_MINUS_CONSTANT_ALPHA (was BlendAlpha)",
                new BlendFunction(BlendFactor.ONE_MINUS_CONSTANT_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA)});

        VertexFormat format = ((RenderPipeline) Class.forName("net.minecraft.client.renderer.RenderPipelines")
                .getField("GUI").get(null)).getVertexFormatBinding(0);
        GpuBuffer vertices = device.createBuffer(() -> "blend vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, blendVertices());
        GpuBuffer indices = device.createBuffer(() -> "blend indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, indexBytes());
        GpuBuffer projection = device.createBuffer(() -> "Projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, identityMat4());
        GpuBuffer transforms = device.createBuffer(() -> "DynamicTransforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                dynamicTransforms(new float[]{1.0f, 1.0f, 1.0f, 1.0f}));
        Map<String, GpuBuffer> uniforms = new LinkedHashMap<>();
        uniforms.put("Projection", projection);
        uniforms.put("DynamicTransforms", transforms);

        int caseNumber = 0;
        for (Object[] entry : cases) {
            String label = (String) entry[0];
            BlendFunction blend = (BlendFunction) entry[1];
            // withLocation(String) is a path under minecraft:, so only lower-case [a-z0-9/._-].
            RenderPipeline pipeline = RenderPipeline.builder()
                    .withLocation("blend_check/case_" + caseNumber++)
                    .withVertexShader(Identifier.parse("minecraft:core/gui"))
                    .withFragmentShader(Identifier.parse("minecraft:core/gui"))
                    .withVertexBinding(0, format)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS)
                    .withCull(false)
                    .withColorTargetState(new ColorTargetState(Optional.of(blend),
                            GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .build();
            device.precompilePipeline(pipeline, source);
            if (device.pipelineFor(pipeline) == null) {
                check("blend " + label + " compiled", false, "");
                continue;
            }
            ByteBuffer pixels = renderQuadPixels(device, pipeline, vertices, indices, uniforms,
                    new LinkedHashMap<>(), SOURCE_BLEND_DESTINATION);
            int at = ((HEIGHT / 2) * WIDTH + (WIDTH / 2)) * 4;
            float[] got = {channel(pixels, at), channel(pixels, at + 1),
                    channel(pixels, at + 2), channel(pixels, at + 3)};
            float[] want = blend(BLEND_SOURCE, BLEND_DESTINATION, blend);
            for (int c = 0; c < 4; c++) {
                want[c] *= 255.0f;
            }
            boolean ok = true;
            for (int c = 0; c < 4 && ok; c++) {
                ok = Math.abs(got[c] - want[c]) <= 1.0f;
            }
            check("blend " + label + " -> " + hex(got) + " (expected " + hex(want) + ")", ok, "");
        }

        transforms.close();
        projection.close();
        indices.close();
        vertices.close();
    }

    /**
     * Source and destination for the blend matrix, far enough apart to separate the factors.
     *
     * <p>Rounded to what an RGBA8 attachment can hold before the equation is evaluated. The clear
     * colour and the vertex attribute both land in 8 bits, so modelling them as exact floats makes
     * the expectation miss by one wherever a factor multiplies two of them together - which would
     * hide a real off-by-one behind the tolerance.
     */
    private static final float[] BLEND_SOURCE = quantise(0.75f, 0.5f, 0.25f, 0.75f);
    private static final float[] BLEND_DESTINATION = quantise(0.25f, 0.5f, 0.75f, 0.5f);
    private static final float[] SOURCE_BLEND_DESTINATION = BLEND_DESTINATION;

    /** Round each component to the nearest value an 8-bit channel can represent. */
    private static float[] quantise(float... values) {
        float[] out = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Math.round(values[i] * 255.0f) / 255.0f;
        }
        return out;
    }

    private static float channel(ByteBuffer pixels, int at) {
        return pixels.get(at) & 0xFF;
    }

    /** Format four 0..255 channel values. */
    private static String hex(float[] rgba) {
        StringBuilder out = new StringBuilder();
        for (float value : rgba) {
            out.append(String.format("%3d ", Math.round(value)));
        }
        return out.toString().trim();
    }

    /**
     * Evaluate a blend function the way GL and Vulkan define it.
     *
     * <p>Written out rather than read from the backend, so it is an independent statement of what
     * the answer should be. {@code SRC_COLOR} and friends are per-channel; on the alpha channel
     * {@code SRC_COLOR} means the source alpha, which is what GL specifies.
     */
    private static float[] blend(float[] source, float[] destination, BlendFunction blend) {
        float[] out = new float[4];
        for (int c = 0; c < 4; c++) {
            BlendEquation equation = c < 3 ? blend.color() : blend.alpha();
            float s = blendFactor(equation.sourceFactor(), c, source, destination) * source[c];
            float d = blendFactor(equation.destFactor(), c, source, destination) * destination[c];
            out[c] = switch (equation.op()) {
                case ADD -> s + d;
                case SUBTRACT -> s - d;
                case REVERSE_SUBTRACT -> d - s;
                case MIN -> Math.min(s, d);
                case MAX -> Math.max(s, d);
            };
            out[c] = Math.max(0.0f, Math.min(1.0f, out[c]));
        }
        return out;
    }

    private static float blendFactor(BlendFactor factor, int channel, float[] source, float[] destination) {
        return switch (factor) {
            case ZERO -> 0.0f;
            case ONE -> 1.0f;
            case SRC_COLOR -> source[channel];
            case ONE_MINUS_SRC_COLOR -> 1.0f - source[channel];
            case DST_COLOR -> destination[channel];
            case ONE_MINUS_DST_COLOR -> 1.0f - destination[channel];
            case SRC_ALPHA -> source[3];
            case ONE_MINUS_SRC_ALPHA -> 1.0f - source[3];
            case DST_ALPHA -> destination[3];
            case ONE_MINUS_DST_ALPHA -> 1.0f - destination[3];
            // GL defines this factor as (i, i, i, 1) with i = min(As, 1 - Ad), so on the alpha
            // channel it is 1, not i. Metal matches; the check below confirms it.
            case SRC_ALPHA_SATURATE -> channel == 3 ? 1.0f : Math.min(source[3], 1.0f - destination[3]);
            // The RenderPass API never sets the blend colour, so it is Metal's default of zero.
            case CONSTANT_COLOR, CONSTANT_ALPHA -> 0.0f;
            case ONE_MINUS_CONSTANT_COLOR, ONE_MINUS_CONSTANT_ALPHA -> 1.0f;
        };
    }

    /** GUI-layout vertices: Position RGB32_FLOAT then Color RGBA8_UNORM, the blend matrix's source. */
    private static ByteBuffer blendVertices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 16).order(ByteOrder.nativeOrder());
        float[][] positions = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (float[] p : positions) {
            buffer.putFloat(p[0]).putFloat(p[1]).putFloat(0.0f);
            buffer.put((byte) Math.round(BLEND_SOURCE[0] * 255.0f));
            buffer.put((byte) Math.round(BLEND_SOURCE[1] * 255.0f));
            buffer.put((byte) Math.round(BLEND_SOURCE[2] * 255.0f));
            buffer.put((byte) Math.round(BLEND_SOURCE[3] * 255.0f));
        }
        buffer.flip();
        return buffer;
    }

    /**
     * Draw both halves of a shared vertex buffer with 16-bit indices, a non-zero {@code firstIndex}
     * and a non-zero base vertex.
     *
     * <p>{@code IndexType.least} picks {@code SHORT} for anything under 65 536 vertices, so chunk and
     * entity meshes are indexed 16 bits wide, and each chunk of a shared vertex buffer is drawn by
     * offsetting into it. Neither the index width nor either offset appears in any other check - they
     * all use 32-bit indices from position 0 - and both offsets are easy to get wrong, since
     * {@code firstIndex} has to be scaled by the index size on the way to Metal.
     *
     * <p>The left half is drawn from index 0 and the right half from index 6 - byte 12 in a 16-bit
     * buffer - with a base vertex of 4. A mis-scaled {@code firstIndex} reads past the end and leaves
     * the right half black; a dropped base vertex draws the left half twice and leaves it black too.
     * Both assertions were confirmed to fail when those were deliberately broken.
     */
    private static void shortIndexCheck(MetalDevice device, RenderPipeline pipeline) {
        GpuTexture target = device.createTexture("short index", GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_COPY_SRC, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
        GpuTextureView view = device.createTextureView(target);
        GpuBuffer vertices = device.createBuffer(() -> "short index vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, twoQuadVertices());
        GpuBuffer indices = device.createBuffer(() -> "short indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, shortIndexBytes());
        GpuBuffer projection = device.createBuffer(() -> "Projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, identityMat4());
        GpuBuffer transforms = device.createBuffer(() -> "DynamicTransforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                dynamicTransforms(new float[]{0.0f, 0.0f, 1.0f, 1.0f}));   // blue
        GpuBuffer readback = device.createBuffer(() -> "readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) WIDTH * HEIGHT * 4);

        Optional<Vector4fc> clear = Optional.of((Vector4fc) new Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
        CommandEncoderBackend encoder = device.createCommandEncoder();
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "short index")
                .withColorAttachment(view, clear)
                .withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        RenderPassBackend pass = encoder.createRenderPass(descriptor);
        pass.setPipeline(pipeline);
        pass.setUniform("Projection", projection.slice());
        pass.setUniform("DynamicTransforms", transforms.slice());
        pass.setVertexBuffer(0, vertices.slice());
        pass.setIndexBuffer(indices, com.mojang.blaze3d.IndexType.SHORT);
        pass.drawIndexed(6, 1, 0, 0, 0);    // left half from index 0
        pass.drawIndexed(6, 1, 6, 4, 0);    // right half: firstIndex 6 is byte 12, base vertex 4
        encoder.submitRenderPass();
        encoder.copyTextureToBuffer(target, readback, 0L, null, 0, 0, 0, WIDTH, HEIGHT);

        ByteBuffer pixels = ((MetalBuffer) readback).data().asByteBuffer().order(ByteOrder.nativeOrder());
        int left = pixels.get((HEIGHT / 2 * WIDTH + WIDTH / 4) * 4 + 2) & 0xFF;
        int right = pixels.get((HEIGHT / 2 * WIDTH + 3 * WIDTH / 4) * 4 + 2) & 0xFF;
        check("16-bit indices at firstIndex=6 draw the left half (B" + left
                + ") and baseVertex=4 draws the right half (B" + right + ")",
                left > 200 && right > 200, "");

        readback.close();
        transforms.close();
        projection.close();
        indices.close();
        vertices.close();
        view.close();
        target.close();
    }

    /** 12 unsigned shorts: the left quad's indices, then the same indices for the right quad. */
    private static ByteBuffer shortIndexBytes() {
        int[] values = {0, 1, 2, 0, 2, 3, 0, 1, 2, 0, 2, 3};
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * 2).order(ByteOrder.nativeOrder());
        for (int value : values) {
            buffer.putShort((short) value);
        }
        buffer.flip();
        return buffer;
    }

    /** GUI-layout vertices, white at half alpha, so the blend factor decides the result. */
    private static ByteBuffer halfAlphaVertices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 16).order(ByteOrder.nativeOrder());
        float[][] positions = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (float[] p : positions) {
            buffer.putFloat(p[0]).putFloat(p[1]).putFloat(0.0f);
            buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 128);
        }
        buffer.flip();
        return buffer;
    }

    /** Two GUI-layout quads side by side: 8 vertices covering the left and right halves. */
    private static ByteBuffer twoQuadVertices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 16).order(ByteOrder.nativeOrder());
        float[][] positions = {
                {-1, -1}, {0, -1}, {0, 1}, {-1, 1},     // left half
                {0, -1}, {1, -1}, {1, 1}, {0, 1},       // right half
        };
        for (float[] p : positions) {
            buffer.putFloat(p[0]).putFloat(p[1]).putFloat(0.0f);
            buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
        }
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer twoQuadIndices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.nativeOrder());
        for (int index : new int[]{0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7}) {
            buffer.putInt(index);
        }
        buffer.flip();
        return buffer;
    }

    /** GUI-layout vertices covering only NDC y in [-1, 0]: the "top half" in a Y-down convention. */
    private static ByteBuffer halfQuadVertices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 16).order(ByteOrder.nativeOrder());
        float[][] positions = {{-1, -1}, {1, -1}, {1, 0}, {-1, 0}};
        for (float[] p : positions) {
            buffer.putFloat(p[0]).putFloat(p[1]).putFloat(0.0f);
            buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
        }
        buffer.flip();
        return buffer;
    }

    /** Render the quad over black and return the whole colour buffer. */
    private static ByteBuffer renderQuadPixels(MetalDevice device, RenderPipeline pipeline,
                                               GpuBuffer vertices, GpuBuffer indices,
                                               Map<String, GpuBuffer> uniforms,
                                               Map<String, GpuTextureView> textures) {
        return renderQuadPixels(device, pipeline, vertices, indices, uniforms, textures,
                new float[]{0.0f, 0.0f, 0.0f, 1.0f});
    }

    /** Render the quad over the given clear colour and return the whole colour buffer. */
    private static ByteBuffer renderQuadPixels(MetalDevice device, RenderPipeline pipeline,
                                               GpuBuffer vertices, GpuBuffer indices,
                                               Map<String, GpuBuffer> uniforms,
                                               Map<String, GpuTextureView> textures, float[] clear) {
        GpuTexture color = device.createTexture("quad", GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_COPY_SRC, pipeline.getColorTargetState().format(), WIDTH, HEIGHT, 1, 1);
        GpuTextureView colorView = device.createTextureView(color);
        GpuBuffer readback = device.createBuffer(() -> "readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) WIDTH * HEIGHT * 4);
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "quad")
                .withColorAttachment(colorView, Optional.of(new Vector4f(clear[0], clear[1], clear[2], clear[3])))
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
