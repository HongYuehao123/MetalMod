package net.metalmod.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * CPU-backed buffer for Phase 1. Draw calls are inert, so nothing reads it; mapping works so the
 * engine's ring buffers and uniform writes do not fault. Phase 2 replaces the storage with a real
 * shared-mode MTLBuffer.
 */
public final class MetalBuffer extends GpuBuffer {

    private final MemorySegment memory;
    private final long byteSize;
    private boolean closed;

    public MetalBuffer(int usage, long size, MemorySegment memory) {
        super(usage, size);
        this.byteSize = size;
        this.memory = memory;
    }

    public MemorySegment memory() {
        return this.memory;
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        GpuBufferSlice slice = slice(offset, length);
        ByteBuffer data;
        if (this.memory == null || this.memory.address() == 0) {
            data = ByteBuffer.allocateDirect((int) Math.max(0, Math.min(length, Integer.MAX_VALUE)));
        } else {
            data = this.memory.asSlice(offset, length).asByteBuffer();
        }
        return new GpuBufferSlice.MappedView(slice, data, () -> {
        });
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        this.closed = true;
    }

    public long capacity() {
        return this.byteSize;
    }
}
