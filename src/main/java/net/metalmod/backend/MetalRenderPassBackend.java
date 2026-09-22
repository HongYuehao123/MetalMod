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

import java.nio.IntBuffer;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * Phase 1 render pass: accepts every command and encodes nothing.
 *
 * <p>This is what makes the walking skeleton boot without a real pipeline layer. The engine records
 * a full frame of draws; they are discarded, so the render target only ever holds its clear colour,
 * which the present path shows. Phase 3 replaces the no-ops with real MTLRenderCommandEncoder
 * encoding. The state is tracked only so a future phase can assert ordering.
 */
public final class MetalRenderPassBackend implements RenderPassBackend {

    @Override
    public void pushDebugGroup(Supplier<String> label) {
    }

    @Override
    public void popDebugGroup() {
    }

    @Override
    public void setPipeline(RenderPipeline pipeline) {
    }

    @Override
    public void bindTexture(String name, GpuTextureView textureView, GpuSampler sampler) {
    }

    @Override
    public void setUniform(String name, GpuBuffer buffer) {
    }

    @Override
    public void setUniform(String name, GpuBufferSlice slice) {
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
    }

    @Override
    public void disableScissor() {
    }

    @Override
    public void setVertexBuffer(int index, GpuBufferSlice slice) {
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex,
                            int vertexOffset, int firstInstance) {
    }

    @Override
    public void multiDrawIndexed(IntBuffer firstIndices, int indexCount, int instanceCount,
                                 int firstInstance) {
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndices, IntBuffer indexCounts,
                                 IntBuffer vertexOffsets, int instanceCount) {
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice slice, int drawCount) {
    }

    @Override
    public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, GpuBuffer indexBuffer,
                                        IndexType indexType, Collection<String> dynamicUniforms,
                                        T pushConstant) {
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, int vertexCount, int instanceCount,
                          int firstInstance) {
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int instanceCount) {
    }

    @Override
    public void drawIndirect(GpuBufferSlice slice, int drawCount) {
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
