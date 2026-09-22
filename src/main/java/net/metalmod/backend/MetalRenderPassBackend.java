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

    private String pipelineName = "none";
    private MetalRenderPipeline pipeline;
    private int topology = 3;
    private MemorySegment indexBuffer = MemorySegment.NULL;
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
        this.pipeline = this.owner.device().pipelineFor(pipeline);
        this.pipelineName = pipeline.getLocation().toString();
        if (this.pipeline != null) {
            this.topology = this.pipeline.topology();
            MetalNative.renderPassSetPipeline(this.encoder, this.pipeline.handle());
        }
    }

    /** Resolve every recorded binding against the current pipeline and encode it. */
    private void applyBindings(boolean reportMissing) {
        if (this.pipeline == null) {
            return;
        }
        if (reportMissing) {
            reportMissingBindings();
        }
        for (Map.Entry<String, GpuBufferSlice> entry : this.uniforms.entrySet()) {
            String name = entry.getKey();
            int vb = this.pipeline.vertexBuffer(name);
            int fb = this.pipeline.fragmentBuffer(name);
            if (vb < 0 && fb < 0) {
                continue;
            }
            GpuBufferSlice slice = entry.getValue();
            MemorySegment handle = MetalCommandEncoderBackend.handleOf(slice.buffer());
            if (handle.address() == 0) {
                continue;
            }
            if (vb >= 0) MetalNative.renderPassSetVertexBuffer(this.encoder, handle, slice.offset(), vb);
            if (fb >= 0) MetalNative.renderPassSetFragmentBuffer(this.encoder, handle, slice.offset(), fb);
        }
        for (Map.Entry<String, GpuTextureView> entry : this.textures.entrySet()) {
            String name = entry.getKey();
            int vt = this.pipeline.vertexTexture(name);
            int ft = this.pipeline.fragmentTexture(name);
            if (vt < 0 && ft < 0) {
                continue;
            }
            MemorySegment texture = MetalCommandEncoderBackend.handleOf(entry.getValue());
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
    }

    @Override
    public void setUniform(String name, GpuBuffer buffer) {
        setUniform(name, buffer.slice());
    }

    @Override
    public void setUniform(String name, GpuBufferSlice slice) {
        if (slice != null) {
            this.uniforms.put(name, slice);
        }
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        MetalNative.renderPassSetScissor(this.encoder, x, y, width, height);
    }

    @Override
    public void disableScissor() {
        MetalNative.renderPassSetScissor(this.encoder, 0, 0, this.width, this.height);
    }

    @Override
    public void setVertexBuffer(int index, GpuBufferSlice slice) {
        if (slice == null) {
            return;
        }
        MemorySegment handle = MetalCommandEncoderBackend.handleOf(slice.buffer());
        MetalNative.renderPassSetVertexBuffer(this.encoder, handle, slice.offset(), index);
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        this.indexBuffer = MetalCommandEncoderBackend.handleOf(buffer);
        this.indexType = type == IndexType.INT ? 1 : 0;
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex,
                            int vertexOffset, int firstInstance) {
        if (!ready()) {
            return;
        }
        applyBindings(true);
        MetalNative.renderPassDrawIndexed(this.encoder, this.topology, this.indexBuffer, 0,
                this.indexType, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    @Override
    public void multiDrawIndexed(IntBuffer firstIndices, int indexCount, int instanceCount,
                                 int firstInstance) {
        if (!ready()) {
            return;
        }
        for (int i = 0; i < firstIndices.remaining(); i++) {
            applyBindings(true);
            MetalNative.renderPassDrawIndexed(this.encoder, this.topology, this.indexBuffer, 0,
                    this.indexType, indexCount, instanceCount,
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
            MetalNative.renderPassDrawIndexed(this.encoder, this.topology, this.indexBuffer, 0,
                    this.indexType, indexCounts.get(indexCounts.position() + i), instanceCount,
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
        setIndexBuffer(indexBuffer, indexType);
        for (RenderPass.Draw<T> draw : draws) {
            if (draw.vertexBuffer() != null) {
                setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
            }
            // This is the one path whose bindings are not fully expressed as setUniform/bindTexture:
            // per-draw data arrives through the draw's uniformUploaderConsumer, and the
            // dynamicUniforms collection names buffers filled that way. Reporting "unbound" here
            // would flag those false positives, so the diagnostic is skipped.
            applyBindings(false);
            MetalNative.renderPassDrawIndexed(this.encoder, this.topology, this.indexBuffer, 0,
                    this.indexType, draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
        }
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (!ready()) {
            return;
        }
        applyBindings(true);
        MetalNative.renderPassDraw(this.encoder, this.topology, firstVertex, vertexCount,
                instanceCount, firstInstance);
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, int vertexCount, int instanceCount,
                          int firstInstance) {
        if (!ready()) {
            return;
        }
        for (int i = 0; i < firstVertices.remaining(); i++) {
            applyBindings(true);
            MetalNative.renderPassDraw(this.encoder, this.topology,
                    firstVertices.get(firstVertices.position() + i), vertexCount, instanceCount, firstInstance);
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
            MetalNative.renderPassDraw(this.encoder, this.topology,
                    firstVertices.get(firstVertices.position() + i),
                    vertexCounts.get(vertexCounts.position() + i), instanceCount, 0);
        }
    }

    @Override
    public void drawIndirect(GpuBufferSlice slice, int drawCount) {
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
