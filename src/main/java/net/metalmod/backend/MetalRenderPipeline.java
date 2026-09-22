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
    private final int topology;
    private boolean closed;

    private MetalRenderPipeline(MemorySegment handle, MemorySegment vertexLibrary, MemorySegment fragmentLibrary,
                                Map<String, Integer> vertexBuffers, Map<String, Integer> fragmentBuffers,
                                Map<String, Integer> vertexTextures, Map<String, Integer> fragmentTextures,
                                Map<String, Integer> vertexSamplers, Map<String, Integer> fragmentSamplers,
                                int topology) {
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
    }

    private static java.util.Set<String> union(Map<String, Integer> a, Map<String, Integer> b) {
        java.util.Set<String> names = new java.util.HashSet<>(a.keySet());
        names.addAll(b.keySet());
        return java.util.Set.copyOf(names);
    }

    /** Compile both stages and build the native pipeline. Returns null on any failure. */
    public static MetalRenderPipeline create(MetalDevice device, MetalShaderCompiler compiler,
                                             RenderPipeline pipeline, ShaderSource source) {
        try {
            // Inject the pipeline's own shader defines on top of the source the ShaderManager
            // produced. Without these, defines like PORTAL_LAYERS are undefined, and worse, a
            // vertex and fragment stage can end up with different defines and therefore different
            // varyings. Both are supplied lazily so a shader-pair cache hit skips them entirely.
            MetalShaderCompiler.CompiledPair pair = compiler.compilePair(
                    pipeline.getVertexShader(), pipeline.getFragmentShader(), pipeline.getShaderDefines(),
                    () -> com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(
                            source.get(pipeline.getVertexShader(), ShaderType.VERTEX),
                            pipeline.getShaderDefines()),
                    () -> com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(
                            source.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT),
                            pipeline.getShaderDefines()));
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
                        vs.textures(), fs.textures(), vs.samplers(), fs.samplers(), topology);
            }
        } catch (Throwable t) {
            System.err.println("[MetalMod] pipeline compile failed for " + pipeline.getLocation() + ": " + t);
            return null;
        }
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
                if (asBuffer) {
                    continue;
                }
                // A BindGroupLayout is a superset: it lists every uniform the pipeline *may* use, and
                // SPIRV-Cross drops the ones a given shader does not reference. "Not reflected at
                // all" therefore means unused, which is fine - Metal does not require an argument
                // for a block the shader never reads. Only a resource reflected as the *wrong kind*
                // is a real mismatch.
                if (!asTexture && !vs.samplers().containsKey(name)
                        && !fs.samplers().containsKey(name)) {
                    continue;
                }
                MetalDevice.reportBindingKindMismatch(location, name, uniform.type().name(),
                        "a texture/sampler");
            }
            for (String sampler : layout.getSamplers()) {
                if (!vs.samplers().containsKey(sampler) && !fs.samplers().containsKey(sampler)) {
                    MetalDevice.reportBindingKindMismatch(location, sampler, "SAMPLER", "nothing");
                }
            }
        }
    }

    public MemorySegment handle() { return this.handle; }
    public int topology() { return this.topology; }
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
