package net.metalmod.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2 transient memory: a real shared MTLBuffer used as a bump arena.
 *
 * <p>The engine streams vertices and uniforms through this each frame. Allocations are sub-buffers
 * of one MTLBuffer (sharing its handle), so a slice is a genuine GPU resource while remaining
 * CPU-writable. The cursor wraps when the arena is exhausted; a wrap can only invalidate data
 * nothing has consumed yet.
 */
public final class MetalTransientMemory implements TransientMemory {

    private static final long CAPACITY = 64L << 20;

    private final MetalBuffer arena;
    private long cursor;
    private boolean closed;

    public MetalTransientMemory(MetalDevice device) {
        MemorySegment handle = MetalNative.bufferCreate(device.deviceHandle(), CAPACITY);
        MemorySegment data = (handle.address() == 0)
                ? MemorySegment.NULL
                : MetalNative.bufferContents(handle, CAPACITY);
        if (handle.address() == 0 || data.address() == 0) {
            MetalDevice.reportResourceFailure("transient arena " + CAPACITY + " bytes");
        }
        this.arena = new MetalBuffer(GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST,
                CAPACITY, handle, data, true, null);
    }

    private synchronized MetalBuffer allocate(long size, long alignment, int usage) {
        long safeSize = Math.max(1L, size);
        long align = alignment <= 1L ? 1L : alignment;
        long aligned = (this.cursor + align - 1L) & -align;
        if (aligned + safeSize > CAPACITY) {
            this.cursor = 0L;
            aligned = 0L;
        }
        this.cursor = aligned + safeSize;
        int effective = usage != 0 ? usage : (GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM);
        return MetalBuffer.sub(effective, safeSize, this.arena, aligned);
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long lifetime, long flags) {
        return allocate(size, alignment, 0).data().asByteBuffer();
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage,
                                                     long lifetime, long flags) {
        MetalBuffer buffer = allocate(size, alignment, usage);
        GpuBufferSlice slice = buffer.slice(0L, buffer.size());
        return new GpuBufferSlice.MappedView(slice, buffer.data().asByteBuffer(), () -> {
        });
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, int usage,
                                      long lifetime, long flags) {
        MetalBuffer buffer = allocate(size, alignment, usage);
        return buffer.slice(0L, buffer.size());
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage,
                                                       long lifetime, long flags) {
        MetalBuffer buffer = allocate(size, alignment, usage);
        GpuBufferSlice slice = buffer.slice(0L, buffer.size());
        return new GpuBufferSlice.MappedView(slice, buffer.data().asByteBuffer(), () -> {
        });
    }

    private GpuBufferSlice upload(List<ByteBuffer> data, long alignment, int usage) {
        long total = 0L;
        for (ByteBuffer buffer : data) {
            total += buffer.remaining();
        }
        MetalBuffer target = allocate(total, alignment, usage);
        if (target.isMapped()) {
            ByteBuffer view = target.data().asByteBuffer();
            for (ByteBuffer buffer : data) {
                view.put(buffer.duplicate());
            }
        }
        return target.slice(0L, target.size());
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage,
                                        long lifetime, long flags) {
        return upload(data, alignment, usage);
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage,
                                    long lifetime, long flags) {
        return upload(data, alignment, usage);
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
        List<GpuBufferSlice> slices = new ArrayList<>(data.size());
        for (ByteBuffer buffer : data) {
            slices.add(upload(List.of(buffer), alignment, usage));
        }
        return slices;
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
        List<GpuBufferSlice> slices = new ArrayList<>(data.size());
        for (ByteBuffer buffer : data) {
            slices.add(upload(List.of(buffer), alignment, usage));
        }
        return slices;
    }

    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.arena.close();
    }
}
