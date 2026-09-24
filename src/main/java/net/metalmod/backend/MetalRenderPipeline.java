package net.metalmod.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.metalmod.lighting.BlockLightVariant;
import net.metalmod.lighting.EntityLightVariant;
import net.metalmod.lighting.ItemLightVariant;
import net.metalmod.lighting.ParticleLightVariant;
import net.metalmod.lighting.TerrainLightVariant;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

/**
 * A compiled Metal render pipeline: MTLRenderPipelineState + MTLDepthStencilState, the MSL
 * libraries it came from, and the name to binding-index maps the render pass needs.
 */
public final class MetalRenderPipeline {

    private static final MemoryLayout LAYOUT_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("bufferIndex"),
            ValueLayout.JAVA_INT.withName("stride"),
            ValueLayout.JAVA_INT.withName("stepFunction"),
            ValueLayout.JAVA_INT.withName("stepRate"));
    private static final MemoryLayout ATTRIBUTE_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("location"),
            ValueLayout.JAVA_INT.withName("bufferIndex"),
            ValueLayout.JAVA_INT.withName("format"),
            ValueLayout.JAVA_INT.withName("offset"));

    private final MemorySegment handle;
    private final MemorySegment vertexLibrary;
    private final MemorySegment fragmentLibrary;
    private final Map<String, Integer> vertexBuffers;
    private final Map<String, Integer> fragmentBuffers;
    private final Map<String, Integer> vertexTextures;
    private final Map<String, Integer> fragmentTextures;
    private final Map<String, Integer> vertexSamplers;
    private final Map<String, Integer> fragmentSamplers;
    // Precomputed unions of the names the shaders declare, so the render pass can tell "the engine
    // never bound this" from "this pipeline does not use it" without rebuilding sets per draw.
    private final java.util.Set<String> declaredBuffers;
    private final java.util.Set<String> declaredTextures;
    private final java.util.Set<String> declaredSamplers;
    // Uniforms the pipeline declares TEXEL_BUFFER. The engine binds those with a GpuBuffer, but
    // SPIRV-Cross emits the emulated path (texture2d<T> + spvTexelBufferCoord), so the buffer's
    // bytes have to be presented as an ordinary 2D texture. Metal's native buffer texture is not an
    // option: creating one with the declared R8_SINT aborts (BUG-013).
    private final Map<String, TexelBuffer> texelBuffers;
    private final int topology;
    // Cached per pipeline rather than recomputed per draw. setPipeline runs once per draw, and
    // getLocation().toString() / getVertexShader().toString() allocate on every call - at 6-18k
    // draws a frame that is tens of thousands of short-lived strings.
    private final String name;
    private final boolean screenquad;
    // Which shader variant this pipeline was built from. The render pass binds the matching light
    // buffer from these flags rather than re-deriving them from the pipeline name per draw.
    private final boolean pointLight;
    private final boolean dynamicLights;
    private final boolean clusteredLights;
    private boolean closed;

    private MetalRenderPipeline(MemorySegment handle, MemorySegment vertexLibrary, MemorySegment fragmentLibrary,
                                Map<String, Integer> vertexBuffers, Map<String, Integer> fragmentBuffers,
                                Map<String, Integer> vertexTextures, Map<String, Integer> fragmentTextures,
                                Map<String, Integer> vertexSamplers, Map<String, Integer> fragmentSamplers,
                                int topology, Map<String, TexelBuffer> texelBuffers,
                                String name, boolean screenquad, boolean pointLight, boolean dynamicLights,
                                boolean clusteredLights) {
        this.handle = handle;
        this.vertexLibrary = vertexLibrary;
        this.fragmentLibrary = fragmentLibrary;
        this.vertexBuffers = vertexBuffers;
        this.fragmentBuffers = fragmentBuffers;
        this.vertexTextures = vertexTextures;
        this.fragmentTextures = fragmentTextures;
        this.vertexSamplers = vertexSamplers;
        this.fragmentSamplers = fragmentSamplers;
        this.declaredBuffers = union(vertexBuffers, fragmentBuffers);
        this.declaredTextures = union(vertexTextures, fragmentTextures);
        this.declaredSamplers = union(vertexSamplers, fragmentSamplers);
        this.topology = topology;
        this.texelBuffers = texelBuffers;
        this.name = name;
        this.screenquad = screenquad;
        this.pointLight = pointLight;
        this.dynamicLights = dynamicLights;
        this.clusteredLights = clusteredLights;
    }

    private static java.util.Set<String> union(Map<String, Integer> a, Map<String, Integer> b) {
        java.util.Set<String> names = new java.util.HashSet<>(a.keySet());
        names.addAll(b.keySet());
        return java.util.Set.copyOf(names);
    }

    /** Compile both stages and build the native pipeline. Returns null on any failure. */
    public static MetalRenderPipeline create(MetalDevice device, MetalShaderCompiler compiler,
                                             RenderPipeline pipeline, ShaderSource source) {
        long started = MetalNative.beginPipelineCapture();
        try {
            return createUnprofiled(device, compiler, pipeline, source);
        } finally {
            MetalNative.endPipelineCapture(started);
        }
    }

    private static MetalRenderPipeline createUnprofiled(MetalDevice device, MetalShaderCompiler compiler,
                                                        RenderPipeline pipeline, ShaderSource source) {
        try {
            // Inject the pipeline's own shader defines on top of the source the ShaderManager
            // produced. Without these, defines like PORTAL_LAYERS are undefined, and worse, a
            // vertex and fragment stage can end up with different defines and therefore different
            // varyings.
            //
            // The sources are fetched eagerly rather than lazily, because a lighting variant rewrites
            // them and the pair cache is keyed on the text (see PairKey). The fetch is a map lookup
            // the ShaderManager has already paid for.
            String vertex = source.get(pipeline.getVertexShader(), ShaderType.VERTEX);
            String fragment = source.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
            String variant = "vanilla";
            if (device.pointLightProofEnabled() || device.dynamicLightsEnabled()) {
                Adapted adapted = adaptForLighting(device, pipeline, vertex, fragment);
                if (adapted != null) {
                    vertex = adapted.vertex();
                    fragment = adapted.fragment();
                    variant = adapted.variant();
                    device.noteLightingVariant(variant, pipeline.getLocation().toString());
                } else if (TerrainLightVariant.eligible(pipeline) || ParticleLightVariant.eligible(pipeline)
                        || EntityLightVariant.eligible(pipeline) || ItemLightVariant.eligible(pipeline)
                        || BlockLightVariant.eligible(pipeline)) {
                    // A pipeline this backend could have lit, whose sources are not the verified
                    // vanilla pair (a shaderpack, or a different game version). It keeps its own
                    // shaders and is reported once, so the fallback is visible rather than silent.
                    device.reportPointLightFallback(pipeline.getLocation().toString());
                }
            }
            MetalShaderCompiler.CompiledPair pair = compiler.compilePair(
                    pipeline.getVertexShader(), pipeline.getFragmentShader(), pipeline.getShaderDefines(),
                    variant,
                    com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(
                            vertex, pipeline.getShaderDefines()),
                    com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(
                            fragment, pipeline.getShaderDefines()));
            MetalShaderCompiler.CompiledShader vs = pair.vertex();
            MetalShaderCompiler.CompiledShader fs = pair.fragment();
            verifyBindingKinds(pipeline, vs, fs);

            MemorySegment vlib = MetalNative.libraryCreate(device.deviceHandle(), vs.msl());
            if (vlib.address() == 0) {
                System.err.println("[MetalMod] vertex MSL failed for " + pipeline.getLocation()
                        + ": " + MetalNative.lastError());
                return null;
            }
            MemorySegment flib = MetalNative.libraryCreate(device.deviceHandle(), fs.msl());
            if (flib.address() == 0) {
                MetalNative.libraryRelease(vlib);
                System.err.println("[MetalMod] fragment MSL failed for " + pipeline.getLocation()
                        + ": " + MetalNative.lastError());
                return null;
            }

            // Only the first colour target is built. The render pass itself accepts as many
            // attachments as the engine gives it, but the pipeline state covers one, so any further
            // attachment would silently keep its clear value. MAX_COLOR_ATTACHMENTS is what the
            // device reports, so the engine rejects such a pass instead of rendering it wrong.
            ColorTargetState color = pipeline.getColorTargetState();
            long colorFormat = color != null ? MetalFormat.mtlPixelFormat(color.format()) : 0L;
            int writeMask = color != null ? MetalFormat.mtlWriteMask(color.writeMask()) : 15;
            int blendEnabled = 0;
            int srcColor = 1, dstColor = 0, opColor = 0, srcAlpha = 1, dstAlpha = 0, opAlpha = 0;
            if (color != null && color.blendFunction().isPresent()) {
                var blend = color.blendFunction().get();
                blendEnabled = 1;
                srcColor = MetalFormat.mtlBlendFactor(blend.color().sourceFactor());
                dstColor = MetalFormat.mtlBlendFactor(blend.color().destFactor());
                opColor = MetalFormat.mtlBlendOp(blend.color().op());
                srcAlpha = MetalFormat.mtlBlendFactor(blend.alpha().sourceFactor());
                dstAlpha = MetalFormat.mtlBlendFactor(blend.alpha().destFactor());
                opAlpha = MetalFormat.mtlBlendOp(blend.alpha().op());
            }

            DepthStencilState depth = pipeline.getDepthStencilState();
            long depthFormat = (depth != null && pipeline.wantsDepthTexture())
                    ? MetalFormat.mtlPixelFormat(GpuFormat.D32_FLOAT) : 0L;
            int depthCompare = depth != null ? MetalFormat.mtlCompare(depth.depthTest()) : 7;
            int depthWrite = depth != null && depth.writeDepth() ? 1 : 0;
            float biasScale = depth != null ? depth.depthBiasScaleFactor() : 0.0f;
            float biasConstant = depth != null ? depth.depthBiasConstant() : 0.0f;

            int topology = MetalFormat.mtlTopology(pipeline.getPrimitiveTopology());
            int cullMode = pipeline.isCull() ? 2 : 0;
            int fillMode = MetalFormat.mtlFillMode(pipeline.getPolygonMode());

            if (Boolean.getBoolean("metalmod.safeState")) {
                blendEnabled = 0;
                writeMask = 15;
                cullMode = 0;
                depthCompare = 7;
                depthWrite = 0;
            }

            try (Arena arena = Arena.ofConfined()) {
                VertexFormat[] formats = pipeline.getVertexFormatBindings();
                MemorySegment layouts = arena.allocate(LAYOUT_LAYOUT.byteSize() * Math.max(1, formats.length));
                MemorySegment attributes = arena.allocate(ATTRIBUTE_LAYOUT.byteSize() * 64);
                int layoutCount = 0;
                int attributeCount = 0;
                java.util.Set<String> coveredInputs = new java.util.HashSet<>();

                for (int slot = 0; slot < formats.length; slot++) {
                    VertexFormat format = formats[slot];
                    if (format == null || format.getVertexSize() <= 0) {
                        continue;
                    }
                    MemorySegment layout = layouts.asSlice(LAYOUT_LAYOUT.byteSize() * layoutCount, LAYOUT_LAYOUT.byteSize());
                    layout.set(ValueLayout.JAVA_INT, 0, slot);
                    layout.set(ValueLayout.JAVA_INT, 4, format.getVertexSize());
                    layout.set(ValueLayout.JAVA_INT, 8, format.getStepRate() > 0 ? 2 : 1);
                    layout.set(ValueLayout.JAVA_INT, 12, Math.max(1, format.getStepRate()));
                    layoutCount++;

                    for (VertexFormatElement element : format.getElements()) {
                        Integer location = vs.inputs().get(element.name());
                        if (location == null || attributeCount >= 64) {
                            continue;
                        }
                        coveredInputs.add(element.name());
                        MemorySegment attribute = attributes.asSlice(
                                ATTRIBUTE_LAYOUT.byteSize() * attributeCount, ATTRIBUTE_LAYOUT.byteSize());
                        attribute.set(ValueLayout.JAVA_INT, 0, location);
                        attribute.set(ValueLayout.JAVA_INT, 4, slot);
                        attribute.set(ValueLayout.JAVA_INT, 8, MetalFormat.mtlVertexFormat(element.format()));
                        attribute.set(ValueLayout.JAVA_INT, 12, element.offset());
                        attributeCount++;
                    }
                }

                // Every attribute the vertex function reads must exist in the descriptor, or Metal
                // refuses the pipeline; report which one rather than only surfacing Metal's message.
                for (String input : vs.inputs().keySet()) {
                    if (!coveredInputs.contains(input)) {
                        MetalDevice.reportMissingVertexAttribute(
                                pipeline.getLocation().toString(), input);
                    }
                }

                MemorySegment pipe = MetalNative.renderPipelineCreate(device.deviceHandle(),
                        vlib, "main0", flib, "main0",
                        colorFormat, writeMask, blendEnabled,
                        srcColor, dstColor, opColor, srcAlpha, dstAlpha, opAlpha,
                        depthFormat, depthCompare, depthWrite,
                        topology, 1 /* CCW */, cullMode, fillMode,
                        biasScale, biasConstant,
                        layouts, layoutCount, attributes, attributeCount);
                if (pipe.address() == 0) {
                    MetalNative.libraryRelease(vlib);
                    MetalNative.libraryRelease(flib);
                    System.err.println("[MetalMod] native pipeline creation failed for "
                            + pipeline.getLocation() + ": " + MetalNative.lastError());
                    return null;
                }
                return new MetalRenderPipeline(pipe, vlib, flib,
                        vs.vertexBuffers(), fs.fragmentBuffers(),
                        vs.textures(), fs.textures(), vs.samplers(), fs.samplers(), topology,
                        collectTexelBuffers(pipeline, vs, fs),
                        pipeline.getLocation().toString(),
                        "minecraft:core/screenquad".equals(pipeline.getVertexShader().toString()),
                        isPointLightVariant(variant),
                        isDynamicVariant(variant),
                        isClusteredVariant(variant));
            }
        } catch (Throwable t) {
            System.err.println("[MetalMod] pipeline compile failed for " + pipeline.getLocation() + ": " + t);
            return null;
        }
    }

    /** A lit variant's name and the two sources it replaces the originals with. */
    private record Adapted(String variant, String vertex, String fragment) {}

    // The render pass decides which light buffers to bind from these three questions, so every family
    // has to answer them. Deciding them here, from the variant the pipeline was actually built with,
    // is what keeps "the shader declares the block" and "the pass binds it" in step.
    private static boolean isPointLightVariant(String variant) {
        return TerrainLightVariant.VERSION.equals(variant);
    }

    private static boolean isDynamicVariant(String variant) {
        return TerrainLightVariant.DYNAMIC_VERSION.equals(variant)
                || TerrainLightVariant.CLUSTERED_VERSION.equals(variant)
                || ParticleLightVariant.DYNAMIC_VERSION.equals(variant)
                || ParticleLightVariant.CLUSTERED_VERSION.equals(variant)
                || EntityLightVariant.DYNAMIC_VERSION.equals(variant)
                || EntityLightVariant.CLUSTERED_VERSION.equals(variant)
                || BlockLightVariant.DYNAMIC_VERSION.equals(variant)
                || BlockLightVariant.CLUSTERED_VERSION.equals(variant)
                || ItemLightVariant.DYNAMIC_VERSION.equals(variant)
                || ItemLightVariant.CLUSTERED_VERSION.equals(variant);
    }

    private static boolean isClusteredVariant(String variant) {
        return TerrainLightVariant.CLUSTERED_VERSION.equals(variant)
                || ParticleLightVariant.CLUSTERED_VERSION.equals(variant)
                || EntityLightVariant.CLUSTERED_VERSION.equals(variant)
                || ItemLightVariant.CLUSTERED_VERSION.equals(variant)
                || BlockLightVariant.CLUSTERED_VERSION.equals(variant);
    }

    /**
     * Produce the lit sources for a pipeline, or null when it is not a family this backend lights or
     * its sources did not match a recorded pair.
     *
     * <p>Families are tried in turn rather than keyed off one list, because each carries its own
     * recorded hashes and its own expression for the camera-relative position. Clustered supersedes
     * the flat list where it is available; a synthetic single light is the proof path and applies to
     * terrain only, since it exists to validate the terrain adapter.
     */
    private static Adapted adaptForLighting(MetalDevice device, RenderPipeline pipeline,
                                            String vertex, String fragment) {
        boolean clustered = device.clusteredLightsEnabled();
        if (device.dynamicLightsEnabled()) {
            if (TerrainLightVariant.eligible(pipeline)) {
                TerrainLightVariant.Sources adapted = clustered
                        ? TerrainLightVariant.adaptClustered(vertex, fragment)
                        : TerrainLightVariant.adaptDynamic(vertex, fragment);
                return adapted == null ? null : new Adapted(
                        clustered ? TerrainLightVariant.CLUSTERED_VERSION
                                : TerrainLightVariant.DYNAMIC_VERSION,
                        adapted.vertex(), adapted.fragment());
            }
            if (ParticleLightVariant.eligible(pipeline)) {
                TerrainLightVariant.Sources adapted = clustered
                        ? ParticleLightVariant.adaptClustered(vertex, fragment)
                        : ParticleLightVariant.adaptDynamic(vertex, fragment);
                return adapted == null ? null : new Adapted(
                        clustered ? ParticleLightVariant.CLUSTERED_VERSION
                                : ParticleLightVariant.DYNAMIC_VERSION,
                        adapted.vertex(), adapted.fragment());
            }
            if (BlockLightVariant.eligible(pipeline)) {
                TerrainLightVariant.Sources adapted = clustered
                        ? BlockLightVariant.adaptClustered(vertex, fragment)
                        : BlockLightVariant.adaptDynamic(vertex, fragment);
                return adapted == null ? null : new Adapted(
                        clustered ? BlockLightVariant.CLUSTERED_VERSION
                                : BlockLightVariant.DYNAMIC_VERSION,
                        adapted.vertex(), adapted.fragment());
            }
            if (ItemLightVariant.eligible(pipeline)) {
                TerrainLightVariant.Sources adapted = clustered
                        ? ItemLightVariant.adaptClustered(vertex, fragment)
                        : ItemLightVariant.adaptDynamic(vertex, fragment);
                return adapted == null ? null : new Adapted(
                        clustered ? ItemLightVariant.CLUSTERED_VERSION
                                : ItemLightVariant.DYNAMIC_VERSION,
                        adapted.vertex(), adapted.fragment());
            }
            if (EntityLightVariant.eligible(pipeline)) {
                TerrainLightVariant.Sources adapted = clustered
                        ? EntityLightVariant.adaptClustered(vertex, fragment)
                        : EntityLightVariant.adaptDynamic(vertex, fragment);
                return adapted == null ? null : new Adapted(
                        clustered ? EntityLightVariant.CLUSTERED_VERSION
                                : EntityLightVariant.DYNAMIC_VERSION,
                        adapted.vertex(), adapted.fragment());
            }
            return null;
        }
        if (TerrainLightVariant.eligible(pipeline)) {
            TerrainLightVariant.Sources adapted = TerrainLightVariant.adapt(vertex, fragment);
            return adapted == null ? null
                    : new Adapted(TerrainLightVariant.VERSION, adapted.vertex(), adapted.fragment());
        }
        return null;
    }

    /** Describe every uniform the pipeline declares TEXEL_BUFFER, with the slot the shader reads it at. */
    private static Map<String, TexelBuffer> collectTexelBuffers(RenderPipeline pipeline,
                                                               MetalShaderCompiler.CompiledShader vs,
                                                               MetalShaderCompiler.CompiledShader fs) {
        Map<String, TexelBuffer> found = new java.util.HashMap<>();
        for (BindGroupLayout layout : pipeline.getBindGroupLayouts()) {
            for (BindGroupLayout.UniformDescription uniform : layout.getUniforms()) {
                if (uniform.type() != UniformType.TEXEL_BUFFER || uniform.gpuFormat() == null) {
                    continue;
                }
                Integer vertexSlot = vs.textures().get(uniform.name());
                Integer fragmentSlot = fs.textures().get(uniform.name());
                if (vertexSlot != null) {
                    found.put(uniform.name(), new TexelBuffer(uniform.gpuFormat(), vertexSlot, true));
                } else if (fragmentSlot != null) {
                    found.put(uniform.name(), new TexelBuffer(uniform.gpuFormat(), fragmentSlot, false));
                }
            }
        }
        return found;
    }

    /**
     * Cross-check the reflection against what the pipeline itself declares.
     *
     * <p>{@code BindGroupLayout} states how the engine will bind each uniform — {@code UNIFORM_BUFFER}
     * means it passes a {@code GpuBufferSlice}, {@code TEXEL_BUFFER} means a {@code GpuBuffer}. If the
     * reflection produced a different kind, the name is bound through the wrong path or not at all.
     * That is the CloudFaces case (BUG-013): declared TEXEL_BUFFER, reflected as a texture, bound as
     * neither. Checking it here means the whole pipeline set is covered rather than one shader at a
     * time.
     */
    private static void verifyBindingKinds(RenderPipeline pipeline,
                                           MetalShaderCompiler.CompiledShader vs,
                                           MetalShaderCompiler.CompiledShader fs) {
        String location = pipeline.getLocation().toString();
        for (BindGroupLayout layout : pipeline.getBindGroupLayouts()) {
            for (BindGroupLayout.UniformDescription uniform : layout.getUniforms()) {
                String name = uniform.name();
                boolean asBuffer = vs.vertexBuffers().containsKey(name)
                        || fs.fragmentBuffers().containsKey(name);
                boolean asTexture = vs.textures().containsKey(name)
                        || fs.textures().containsKey(name);
                // A BindGroupLayout is a superset: it lists every uniform the pipeline *may* use, and
                // SPIRV-Cross drops the ones a given shader does not reference. "Not reflected at
                // all" therefore means unused, which is fine - Metal does not require an argument
                // for a block the shader never reads.
                if (uniform.type() == UniformType.UNIFORM_BUFFER) {
                    // The engine binds these with a GpuBufferSlice, so they must be uniform buffers.
                    if (!asBuffer && asTexture) {
                        MetalDevice.reportBindingKindMismatch(location, name, "UNIFORM_BUFFER",
                                "a texture/sampler");
                    }
                    continue;
                }
                // TEXEL_BUFFER: SPIRV-Cross emits texture2d<T> + spvTexelBufferCoord and MetalMod
                // presents the buffer's bytes as a 2D texture, so a texture reflection is exactly
                // what the binding path expects. A *buffer* reflection would be the wrong one.
                if (asBuffer) {
                    MetalDevice.reportBindingKindMismatch(location, name, "TEXEL_BUFFER",
                            "a uniform buffer");
                }
            }
            for (String sampler : layout.getSamplers()) {
                if (!vs.samplers().containsKey(sampler) && !fs.samplers().containsKey(sampler)) {
                    MetalDevice.reportBindingKindMismatch(location, sampler, "SAMPLER", "nothing");
                }
            }
        }
    }

    /**
     * A uniform declared {@code TEXEL_BUFFER}: the engine hands us a {@link com.mojang.blaze3d.buffers.GpuBuffer}
     * and the shader reads it through a 2D integer texture.
     */
    /**
     * How many colour attachments this backend builds pipeline state for.
     *
     * <p>Reported through {@code DeviceLimits.maxColorAttachments}. The render pass takes a count and
     * attaches all of them, but the pipeline declares one - so this is the number of targets the
     * backend actually honours, and the engine uses it to reject a pass with more.
     */
    public static final int MAX_COLOR_ATTACHMENTS = 1;

    public record TexelBuffer(GpuFormat format, int textureSlot, boolean vertexStage) {
    }

    /** How to present a texel-buffer uniform, or null when the pipeline does not use one. */
    public TexelBuffer texelBuffer(String name) {
        return this.texelBuffers == null ? null : this.texelBuffers.get(name);
    }

    public MemorySegment handle() { return this.handle; }
    public int topology() { return this.topology; }

    /** The pipeline's location, resolved once at compile time. */
    public String name() { return this.name; }

    /**
     * Whether this is the full-screen-triangle pipeline ({@code minecraft:core/screenquad}), whose
     * vertex shader builds its position from the vertex id with no projection matrix, so the pass
     * needs the Y-flipped viewport. Precomputed because setPipeline runs once per draw.
     */
    public boolean isScreenquad() { return this.screenquad; }

    /** Whether this pipeline was built from the 6A single-point-light variant. */
    public boolean usesPointLight() { return this.pointLight; }

    /** Whether this pipeline was built from a bounded light-set variant (6B flat or 6C clustered). */
    public boolean usesDynamicLights() { return this.dynamicLights; }

    /** Whether this pipeline was built from the clustered variant, which reads the cluster blocks. */
    public boolean usesClusteredLights() { return this.clusteredLights; }

    public int vertexBuffer(String name) { return this.vertexBuffers.getOrDefault(name, -1); }
    public int fragmentBuffer(String name) { return this.fragmentBuffers.getOrDefault(name, -1); }
    public int vertexTexture(String name) { return this.vertexTextures.getOrDefault(name, -1); }
    public int fragmentTexture(String name) { return this.fragmentTextures.getOrDefault(name, -1); }
    public int vertexSampler(String name) { return this.vertexSamplers.getOrDefault(name, -1); }
    public int fragmentSampler(String name) { return this.fragmentSamplers.getOrDefault(name, -1); }

    /** GLSL names of every uniform buffer the two stages declare. */
    public java.util.Set<String> declaredBuffers() { return this.declaredBuffers; }

    /** GLSL names of every texture the two stages declare. */
    public java.util.Set<String> declaredTextures() { return this.declaredTextures; }

    /** GLSL names of every sampler the two stages declare. */
    public java.util.Set<String> declaredSamplers() { return this.declaredSamplers; }

    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.handle.address() != 0) {
            MetalNative.renderPipelineRelease(this.handle);
        }
        if (this.vertexLibrary.address() != 0) {
            MetalNative.libraryRelease(this.vertexLibrary);
        }
        if (this.fragmentLibrary.address() != 0) {
            MetalNative.libraryRelease(this.fragmentLibrary);
        }
    }
}
