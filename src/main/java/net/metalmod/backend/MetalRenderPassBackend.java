package net.metalmod.backend;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.metalmod.lighting.LightClusterGrid;
import net.metalmod.lighting.LightSnapshot;
import net.metalmod.lighting.PointLight;
import org.lwjgl.PointerBuffer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Phase 3 render pass: encodes draws into a Metal render command encoder.
 *
 * <p>Uniform and texture bindings are recorded by name and resolved at draw time against the
 * *current* pipeline. They must be deferred: the engine records bindings before (or independently
 * of) setPipeline, and a name has no binding index until the pipeline is known. Resolving eagerly
 * against whatever pipeline happened to be current bound uniforms to the wrong slots - or nowhere -
 * so the shader read zeros and every GUI quad collapsed to nothing.
 */
public final class MetalRenderPassBackend implements RenderPassBackend {

    private final MetalCommandEncoderBackend owner;
    private final MemorySegment encoder;
    private final int width;
    private final int height;

    private final Map<String, GpuBufferSlice> uniforms = new HashMap<>();
    private final Map<String, GpuTextureView> textures = new HashMap<>();
    private final Map<String, GpuSampler> samplers = new HashMap<>();

    // Names whose binding changed since the last draw. Only these are handed to Metal. Iterating
    // everything that was ever bound - which is what the backend used to do - costs a map walk per
    // draw for no work: a terrain pass sets one uniform per section and leaves the other dozen
    // bindings alone. Both sets are emptied by applyBindings and refilled from scratch whenever the
    // pipeline changes, because a name resolves to different slots under a different pipeline.
    private final java.util.Set<String> dirtyUniforms = new java.util.HashSet<>();
    private final java.util.Set<String> dirtyTextures = new java.util.HashSet<>();

    private String pipelineName = "none";
    private MetalRenderPipeline pipeline;
    private RenderPipeline lastEnginePipeline;
    private int topology = 3;

    // The last vertex-buffer binding handed to this encoder, so a repeated bind is skipped. Only
    // the attribute slots go through here; uniform buffers are bound above them at
    // VERTEX_BUFFER_INDEX_OFFSET and are not tracked.
    private int lastVertexBufferSlot = -1;
    private long lastVertexBufferHandle;
    private long lastVertexBufferOffset;
    private MemorySegment indexBuffer = MemorySegment.NULL;
    private long indexBufferOffset;
    private int indexType = 1;

    public MetalRenderPassBackend(MetalCommandEncoderBackend owner, MemorySegment encoder,
                                  int width, int height) {
        this.owner = owner;
        this.encoder = encoder;
        this.width = width;
        this.height = height;
    }

    private boolean ready() {
        return this.encoder.address() != 0 && this.pipeline != null;
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
        if (this.encoder.address() == 0) {
            return;
        }
        String text = label == null ? "" : label.get();
        try (Arena arena = Arena.ofConfined()) {
            MetalNative.renderPassPushDebugGroup(this.encoder, arena.allocateFrom(text == null ? "" : text));
        }
    }

    @Override
    public void popDebugGroup() {
        if (this.encoder.address() == 0) {
            return;
        }
        MetalNative.renderPassPopDebugGroup(this.encoder);
    }

    @Override
    public void setPipeline(RenderPipeline pipeline) {
        if (pipeline == this.lastEnginePipeline) {
            // Same pipeline as the previous draw, so the encoder already holds its state and every
            // binding was applied under it. Consecutive chunk-section draws hit this path, and
            // returning here skips the synchronized pipeline lookup, two string allocations, a
            // synchronized census call and five native state writes per draw. A different pipeline -
            // even one that resolves to the same Metal state - takes the full path, so the encoder
            // can never be stale.
            return;
        }
        this.lastEnginePipeline = pipeline;
        MetalRenderPipeline resolved = this.owner.device().pipelineFor(pipeline);
        this.pipeline = resolved;
        // Slots differ per pipeline, so everything already set has to be handed to Metal again.
        this.dirtyUniforms.addAll(this.uniforms.keySet());
        this.dirtyTextures.addAll(this.textures.keySet());
        if (resolved == null) {
            this.pipelineName = pipeline.getLocation().toString();
            return;
        }
        this.pipelineName = resolved.name();
        this.topology = resolved.topology();
        MetalNative.renderPassSetPipeline(this.encoder, resolved.handle());
        MetalDevice.notePipelineTarget(this.owner.currentTargetLabel(), this.pipelineName);
        flipViewportForScreenquad(resolved);
        // MetalMod-owned light buffers, bound only for pipelines actually built with a lighting
        // variant - the compiled pipeline answers that from its variant, not from its name. A
        // test-provided uniform of the same name wins, which is how the offscreen check drives one
        // light at a time instead of going through the world snapshot.
        if (resolved.usesPointLight() && !this.uniforms.containsKey(PointLight.UNIFORM)) {
            setUniform(PointLight.UNIFORM, this.owner.device().pointLightProofBuffer());
        }
        if (resolved.usesDynamicLights() && !this.uniforms.containsKey(LightSnapshot.UNIFORM)) {
            setUniform(LightSnapshot.UNIFORM, this.owner.device().dynamicLightsBuffer());
        }
        if (resolved.usesClusteredLights()) {
            if (!this.uniforms.containsKey(LightClusterGrid.GRID_UNIFORM)) {
                setUniform(LightClusterGrid.GRID_UNIFORM, this.owner.device().lightGridBuffer());
            }
            // The cluster table is a texture the device owns, not engine state, so it is bound here
            // the same way the device-owned light buffers are - and only when the compiled pipeline
            // was actually built from the clustered source.
            if (!this.textures.containsKey(LightClusterGrid.DATA_UNIFORM)) {
                GpuTextureView data = this.owner.device().lightDataView();
                if (data != null) {
                    bindTexture(LightClusterGrid.DATA_UNIFORM, data, this.owner.device().defaultSampler());
                }
            }
        }
    }

    /**
     * Flip the viewport for passes drawn with {@code core/screenquad}.
     *
     * <p>Every other pass carries Minecraft's Y convention in its projection matrix. {@code
     * screenquad.vsh} builds the full-screen triangle from {@code gl_VertexID} and sets
     * {@code gl_Position = uv * 2 - 1} directly, so there is nothing between it and the framebuffer
     * - and Vulkan's NDC y = +1 is the *last* framebuffer row where Metal's is the first.
     *
     * <p>That made the lightmap store itself vertically mirrored. {@code core/lightmap} writes a
     * full sky level at {@code texCoord.y = 1}, terrain samples it at {@code v = skyLevel/256 +
     * 0.5/16} - close to 1, and {@code v = 0} is the first row of the data - so the ground sampled
     * the row holding level 0. In game: a bright sky over a night-dark ground. The offscreen check
     * shows it exactly, with every corner swapped.
     *
     * <p>It is the same correction as the atlas path, for the same reason, and the two are kept
     * exclusive so a pass is never flipped twice. The pipeline answers the question itself, so no
     * string is built per draw.
     */
    private void flipViewportForScreenquad(MetalRenderPipeline pipeline) {
        if (this.owner.viewportFlipped() || !pipeline.isScreenquad()) {
            return;
        }
        MetalNative.renderPassSetViewport(this.encoder, 0.0, (double) this.height,
                (double) this.width, -(double) this.height);
        this.owner.markViewportFlipped();
    }

    /** Resolve every recorded binding against the current pipeline and encode it. */
    private void applyBindings(boolean reportMissing) {
        if (this.pipeline == null) {
            return;
        }
        if (reportMissing && MetalDevice.censusEnabled()) {
            reportMissingBindings();
        }
        // Only what changed since the previous draw is handed to Metal; the encoder keeps the rest.
        // A terrain pass re-binds its per-section block and nothing else.
        if (!this.dirtyUniforms.isEmpty()) {
            for (String name : this.dirtyUniforms) {
                GpuBufferSlice slice = this.uniforms.get(name);
                if (slice == null) {
                    continue;
                }
                int vb = this.pipeline.vertexBuffer(name);
                int fb = this.pipeline.fragmentBuffer(name);
                if (vb < 0 && fb < 0) {
                    continue;
                }
                MemorySegment handle = MetalCommandEncoderBackend.handleOf(slice.buffer());
                if (handle.address() == 0) {
                    continue;
                }
                long offset = absoluteOffset(slice);
                if (vb >= 0) MetalNative.renderPassSetVertexBuffer(this.encoder, handle, offset, vb);
                if (fb >= 0) MetalNative.renderPassSetFragmentBuffer(this.encoder, handle, offset, fb);
            }
            this.dirtyUniforms.clear();
        }
        if (!this.dirtyTextures.isEmpty()) {
            for (String name : this.dirtyTextures) {
                GpuTextureView view = this.textures.get(name);
                if (view == null) {
                    continue;
                }
                int vt = this.pipeline.vertexTexture(name);
                int ft = this.pipeline.fragmentTexture(name);
                if (vt < 0 && ft < 0) {
                    continue;
                }
                MemorySegment texture = MetalCommandEncoderBackend.handleOf(view);
                if (vt >= 0) MetalNative.renderPassSetVertexTexture(this.encoder, texture, vt);
                if (ft >= 0) MetalNative.renderPassSetFragmentTexture(this.encoder, texture, ft);

                GpuSampler sampler = this.samplers.get(name);
                MemorySegment samplerHandle = sampler instanceof MetalSampler metal
                        ? metal.handle() : MemorySegment.NULL;
                int vs = this.pipeline.vertexSampler(name);
                int fs = this.pipeline.fragmentSampler(name);
                if (vs >= 0) MetalNative.renderPassSetVertexSampler(this.encoder, samplerHandle, vs);
                if (fs >= 0) MetalNative.renderPassSetFragmentSampler(this.encoder, samplerHandle, fs);
            }
            this.dirtyTextures.clear();
        }
    }

    /**
     * Report anything the shaders declare that nothing bound.
     *
     * <p>Without this, an unbound sampler is invisible: the draw succeeds, Metal reads undefined
     * data, and the object renders black - which reads as "the shader is wrong" rather than "the
     * binding never happened". Counting distinct names (MetalDevice caps and de-duplicates) keeps
     * this cheap enough to run per draw.
     */
    private void reportMissingBindings() {
        for (String name : this.pipeline.declaredTextures()) {
            if (!this.textures.containsKey(name)) {
                MetalDevice.reportUnboundBinding(this.pipelineName, "texture", name);
            }
        }
        for (String name : this.pipeline.declaredSamplers()) {
            if (this.pipeline.texelBuffer(name) != null) {
                // A buffer texture is read with texelFetch/read and has no sampler argument in the
                // MSL, so the engine never supplies one and there is nothing missing.
                continue;
            }
            if (!this.samplers.containsKey(name)) {
                MetalDevice.reportUnboundBinding(this.pipelineName, "sampler", name);
            }
        }
        for (String name : this.pipeline.declaredBuffers()) {
            if (!this.uniforms.containsKey(name)) {
                MetalDevice.reportUnboundBinding(this.pipelineName, "uniform buffer", name);
            }
        }
    }

    @Override
    public void bindTexture(String name, GpuTextureView textureView, GpuSampler sampler) {
        this.textures.put(name, textureView);
        if (sampler != null) {
            this.samplers.put(name, sampler);
        }
        this.dirtyTextures.add(name);
    }

    @Override
    public void setUniform(String name, GpuBuffer buffer) {
        setUniform(name, buffer.slice());
    }

    @Override
    public void setUniform(String name, GpuBufferSlice slice) {
        if (slice == null) {
            return;
        }
        // A texel-buffer uniform arrives as a GpuBuffer but the shader reads it as a 2D integer
        // texture, so present its bytes that way rather than as a uniform buffer.
        MetalRenderPipeline.TexelBuffer texel =
                this.pipeline == null ? null : this.pipeline.texelBuffer(name);
        if (texel != null && slice.buffer() instanceof MetalBuffer buffer) {
            MetalTexture texture = this.owner.device().texelTexture(buffer, slice.offset(),
                    slice.length(), texel.format(), Math.max(1, texel.format().blockSize()));
            if (texture != null) {
                this.textures.put(name, new MetalTextureView(texture, 0, texture.getMipLevels()));
                this.dirtyTextures.add(name);
                return;
            }
        }
        this.uniforms.put(name, slice);
        this.dirtyUniforms.add(name);
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        // The engine's scissor rectangle is bottom-up (GL convention): GuiRenderer converts its
        // top-down ScreenRectangle with `window.height - bottom`, and GlCommandEncoder hands the
        // values straight to glScissor. Metal's setScissorRect origin is top-left, so a normal pass
        // needs the Y converted - without it the Select World list scissor was mirrored and clipped
        // the top off its first entry (BUG-001).
        //
        // A pass whose viewport is already Y-flipped is the exception and must NOT be converted:
        // the flip has already mirrored the framebuffer mapping to match Minecraft's Y-down
        // convention, so the engine's y lands correctly when applied directly. That is what the
        // native region clear does (mmm_clear_textures_region applies y unchanged, which BUG-010
        // verified), and GuiItemAtlas composites each slot with a clear *and* a scissor at the same
        // coordinates - converting only the scissor wiped the slot it had just cleared, which is
        // why the inventory icons disappeared.
        int metalY = this.owner.viewportFlipped() ? y : this.height - (y + height);
        MetalNative.renderPassSetScissor(this.encoder, x, metalY, width, height);
    }

    @Override
    public void disableScissor() {
        MetalNative.renderPassSetScissor(this.encoder, 0, 0, this.width, this.height);
    }

    /**
     * Byte offset to hand Metal for a slice, i.e. the slice's own offset plus the sub-buffer's base
     * inside the shared MTLBuffer. {@code GpuBufferSlice} offsets are relative to the buffer object,
     * but a sub-buffer shares its parent's handle - so without the base, a transient arena
     * allocation would bind the arena's start (BUG-009). Zero for every buffer that owns its handle.
     */
    static long absoluteOffset(GpuBufferSlice slice) {
        long base = slice.buffer() instanceof MetalBuffer metal ? metal.baseOffset() : 0L;
        return base + slice.offset();
    }

    @Override
    public void setVertexBuffer(int index, GpuBufferSlice slice) {
        if (slice == null) {
            return;
        }
        MemorySegment handle = MetalCommandEncoderBackend.handleOf(slice.buffer());
        long offset = absoluteOffset(slice);
        // Terrain hands every section the same uber buffer at offset 0 - the per-section offset
        // travels as baseVertex, not as a vertex-buffer binding (LevelRenderer builds the Draw with
        // `slice.vertexBuffer()` and `baseVertex = vertexBufferOffset / vertexSize`). So the bind is
        // identical for every section in a layer and is dropped when it repeats, which removes one
        // of the three native calls a section draw makes. Compared by handle+offset rather than by
        // identity: GpuBuffer.slice() mints a fresh object each call.
        if (index == this.lastVertexBufferSlot
                && handle.address() == this.lastVertexBufferHandle
                && offset == this.lastVertexBufferOffset) {
            return;
        }
        this.lastVertexBufferSlot = index;
        this.lastVertexBufferHandle = handle.address();
        this.lastVertexBufferOffset = offset;
        MetalNative.renderPassSetVertexBuffer(this.encoder, handle, offset, index);
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        this.indexBuffer = MetalCommandEncoderBackend.handleOf(buffer);
        this.indexBufferOffset = buffer instanceof MetalBuffer metal ? metal.baseOffset() : 0L;
        this.indexType = type == IndexType.INT ? 1 : 0;
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex,
                            int vertexOffset, int firstInstance) {
        if (!ready()) {
            return;
        }
        applyBindings(true);
        encodeIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    @Override
    public void multiDrawIndexed(IntBuffer firstIndices, int indexCount, int instanceCount,
                                 int firstInstance) {
        if (!ready()) {
            return;
        }
        for (int i = 0; i < firstIndices.remaining(); i++) {
            applyBindings(true);
            encodeIndexed(indexCount, instanceCount,
                    firstIndices.get(firstIndices.position() + i), 0, firstInstance);
        }
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndices, IntBuffer indexCounts,
                                 IntBuffer vertexOffsets, int instanceCount) {
        if (!ready()) {
            return;
        }
        int draws = Math.min(firstIndices.remaining(), Math.min(indexCounts.remaining(), vertexOffsets.remaining()));
        for (int i = 0; i < draws; i++) {
            applyBindings(true);
            encodeIndexed(indexCounts.get(indexCounts.position() + i), instanceCount,
                    (int) firstIndices.get(firstIndices.position() + i),
                    vertexOffsets.get(vertexOffsets.position() + i), 0);
        }
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice slice, int drawCount) {
    }

    @Override
    public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, GpuBuffer indexBuffer,
                                        IndexType indexType, Collection<String> dynamicUniforms,
                                        T pushConstant) {
        if (!ready()) {
            return;
        }
        for (RenderPass.Draw<T> draw : draws) {
            // Per-draw uniforms arrive through the draw's own uploader consumer, which the backend
            // must invoke. Vanilla's chunk terrain is the main user: each draw's payload is the
            // GpuBufferSlice[] of section info, uploaded as the 'ChunkSection' uniform block
            // (ModelViewMat / ChunkPosition / TextureSize). Skipping this left ChunkSection unbound,
            // so terrain had no chunk position and no model-view matrix at all.
            uploadDrawUniforms(draw, pushConstant, this::setUniform);
            setIndexBuffer(effectiveIndexBuffer(draw, indexBuffer), effectiveIndexType(draw, indexType));
            if (draw.vertexBuffer() != null) {
                setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
            }
            // Safe to diagnose here now that the consumer has supplied its uniforms.
            applyBindings(true);
            encodeIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
        }
    }

    /**
     * Hand a draw's opaque payload to its own uploader consumer.
     *
     * <p>The payload is deliberately opaque to the backend: {@code RenderPass.drawMultipleIndexed}
     * passes it straight through, and {@code VulkanRenderPass} only ever forwards it to this same
     * consumer. For chunk terrain it is a {@code GpuBufferSlice[]}, and the consumer decides what
     * that means, pushing each uniform through {@link RenderPass.UniformUploader#upload}.
     */
    static <T> void uploadDrawUniforms(RenderPass.Draw<T> draw, T pushConstant,
                                       RenderPass.UniformUploader uploader) {
        java.util.function.BiConsumer<T, RenderPass.UniformUploader> consumer =
                draw.uniformUploaderConsumer();
        if (consumer == null || uploader == null) {
            return;
        }
        consumer.accept(pushConstant, uploader);
    }

    /** A draw may carry its own index buffer; {@code null} means use the pass-level one. */
    static GpuBuffer effectiveIndexBuffer(RenderPass.Draw<?> draw, GpuBuffer fallback) {
        return draw.indexBuffer() != null ? draw.indexBuffer() : fallback;
    }

    /** A draw may carry its own index type; {@code null} means use the pass-level one. */
    static IndexType effectiveIndexType(RenderPass.Draw<?> draw, IndexType fallback) {
        return draw.indexType() != null ? draw.indexType() : fallback;
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (!ready()) {
            return;
        }
        applyBindings(true);
        if (this.topology == MetalFormat.TOPOLOGY_TRIANGLE_FAN) {
            // Metal has no triangle fan. The native side expands it into an indexed triangle list
            // from a cached pattern that is prefix-stable, so one buffer serves every vertex count
            // without ever being rewritten between draws. Vanilla fans are the sky disc (10
            // vertices, 1 non-indexed draw) and sunrise/sunset.
            encodeFan(firstVertex, vertexCount, instanceCount, firstInstance);
            return;
        }
        encodeDraw(this.topology, firstVertex, vertexCount, instanceCount, firstInstance);
    }

    /**
     * The {@code MTLPrimitiveType} to use for indexed draws, with the fan sentinel resolved.
     *
     * <p>An <em>indexed</em> fan cannot be expanded the way a non-indexed one is, because its vertex
     * order lives in the index buffer. No vanilla pipeline indexed-draws a fan, so this resolves to a
     * triangle list and reports it once rather than reading a negative primitive type.
     */
    private int indexedTopology() {
        if (this.topology == MetalFormat.TOPOLOGY_TRIANGLE_FAN) {
            MetalDevice.reportIndexedFan(this.pipelineName);
            return 3;
        }
        return this.topology;
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, int vertexCount, int instanceCount,
                          int firstInstance) {
        if (!ready()) {
            return;
        }
        for (int i = 0; i < firstVertices.remaining(); i++) {
            applyBindings(true);
            encodeDraw(this.topology, firstVertices.get(firstVertices.position() + i), vertexCount,
                    instanceCount, firstInstance);
        }
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int instanceCount) {
        if (!ready()) {
            return;
        }
        int draws = Math.min(firstVertices.remaining(), vertexCounts.remaining());
        for (int i = 0; i < draws; i++) {
            applyBindings(true);
            encodeDraw(this.topology, firstVertices.get(firstVertices.position() + i),
                    vertexCounts.get(vertexCounts.position() + i), instanceCount, 0);
        }
    }

    /**
     * Encode one indexed draw and count it.
     *
     * <p>Every draw funnels through here (or {@link #encodeDraw}/{@link #encodeFan}), so the frame's
     * draw count - the number F3 reports and the one that grows underground, where far more sections
     * are visible - has a single source.
     */
    private void encodeIndexed(int indexCount, int instanceCount, int firstIndex,
                               int baseVertex, int firstInstance) {
        MetalDevice.countDraw();
        MetalNative.renderPassDrawIndexed(this.encoder, indexedTopology(), this.indexBuffer,
                this.indexBufferOffset, this.indexType, indexCount, instanceCount, firstIndex,
                baseVertex, firstInstance);
    }

    /** Encode one non-indexed draw and count it. */
    private void encodeDraw(int topology, int firstVertex, int vertexCount, int instanceCount,
                            int firstInstance) {
        MetalDevice.countDraw();
        MetalNative.renderPassDraw(this.encoder, topology, firstVertex, vertexCount, instanceCount,
                firstInstance);
    }

    /** Encode one expanded triangle fan and count it. */
    private void encodeFan(int firstVertex, int vertexCount, int instanceCount, int firstInstance) {
        MetalDevice.countDraw();
        MetalNative.renderPassDrawFan(this.encoder, firstVertex, vertexCount, instanceCount,
                firstInstance);
    }

    @Override
    public void drawIndirect(GpuBufferSlice slice, int drawCount) {
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
