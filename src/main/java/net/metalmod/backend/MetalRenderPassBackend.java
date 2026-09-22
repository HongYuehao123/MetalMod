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

import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.function.Supplier;

/** Phase 3 render pass: binds the compiled pipeline and encodes draws into the Metal encoder. */
public final class MetalRenderPassBackend implements RenderPassBackend {

    private final MetalCommandEncoderBackend owner;
    private final MemorySegment encoder;
    private final int width;
    private final int height;

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
    }

    @Override
    public void popDebugGroup() {
    }

    @Override
    public void setPipeline(RenderPipeline pipeline) {
        this.pipeline = this.owner.device().pipelineFor(pipeline);
        if (this.pipeline != null) {
            this.topology = this.pipeline.topology();
            MetalNative.renderPassSetPipeline(this.encoder, this.pipeline.handle());
        }
    }

    @Override
    public void bindTexture(String name, GpuTextureView textureView, GpuSampler sampler) {
        if (!ready()) {
            return;
        }
        MemorySegment texture = MetalCommandEncoderBackend.handleOf(textureView);
        MemorySegment samplerHandle = sampler instanceof MetalSampler metal ? metal.handle() : MemorySegment.NULL;
        int vt = this.pipeline.vertexTexture(name);
        int ft = this.pipeline.fragmentTexture(name);
        if (vt >= 0) MetalNative.renderPassSetVertexTexture(this.encoder, texture, vt);
        if (ft >= 0) MetalNative.renderPassSetFragmentTexture(this.encoder, texture, ft);
        int vs = this.pipeline.vertexSampler(name);
        int fs = this.pipeline.fragmentSampler(name);
        if (vs >= 0) MetalNative.renderPassSetVertexSampler(this.encoder, samplerHandle, vs);
        if (fs >= 0) MetalNative.renderPassSetFragmentSampler(this.encoder, samplerHandle, fs);
    }

    @Override
    public void setUniform(String name, GpuBuffer buffer) {
        setUniform(name, buffer.slice());
    }

    @Override
    public void setUniform(String name, GpuBufferSlice slice) {
        if (!ready() || slice == null) {
            return;
        }
        MemorySegment handle = MetalCommandEncoderBackend.handleOf(slice.buffer());
        if (handle.address() == 0) {
            return;
        }
        int vb = this.pipeline.vertexBuffer(name);
        int fb = this.pipeline.fragmentBuffer(name);
        if (vb >= 0) MetalNative.renderPassSetVertexBuffer(this.encoder, handle, slice.offset(), vb);
        if (fb >= 0) MetalNative.renderPassSetFragmentBuffer(this.encoder, handle, slice.offset(), fb);
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
        MetalNative.renderPassDrawIndexed(this.encoder, this.topology, this.indexBuffer, 0,
                this.indexType, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    @Override
    public void multiDrawIndexed(IntBuffer firstIndices, int indexCount, int instanceCount,
                                 int firstInstance) {
        for (int i = 0; i < firstIndices.remaining(); i++) {
            drawIndexed(indexCount, instanceCount, firstIndices.get(firstIndices.position() + i), 0, firstInstance);
        }
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndices, IntBuffer indexCounts,
                                 IntBuffer vertexOffsets, int instanceCount) {
        int draws = Math.min(firstIndices.remaining(), Math.min(indexCounts.remaining(), vertexOffsets.remaining()));
        for (int i = 0; i < draws; i++) {
            drawIndexed(indexCounts.get(indexCounts.position() + i), instanceCount,
                    (int) firstIndices.get(firstIndices.position() + i),
                    vertexOffsets.get(vertexOffsets.position() + i), 0);
        }
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice slice, int drawCount) {
        // Indirect draws land in a later pass; the direct path covers vanilla and Sodium's default.
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
                setVertexBuffer(draw.slot() == 0 ? 0 : draw.slot(), draw.vertexBuffer().slice());
            }
            drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
        }
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (!ready()) {
            return;
        }
        MetalNative.renderPassDraw(this.encoder, this.topology, firstVertex, vertexCount,
                instanceCount, firstInstance);
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, int vertexCount, int instanceCount,
                          int firstInstance) {
        for (int i = 0; i < firstVertices.remaining(); i++) {
            draw(vertexCount, instanceCount, firstVertices.get(firstVertices.position() + i), firstInstance);
        }
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int instanceCount) {
        int draws = Math.min(firstVertices.remaining(), vertexCounts.remaining());
        for (int i = 0; i < draws; i++) {
            draw(vertexCounts.get(vertexCounts.position() + i), instanceCount,
                    firstVertices.get(firstVertices.position() + i), 0);
        }
    }

    @Override
    public void drawIndirect(GpuBufferSlice slice, int drawCount) {
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
