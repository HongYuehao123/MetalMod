package net.metalmod.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 transient memory: a bump arena over one shared segment.
 *
 * <p>The engine streams vertices and uniforms through this each frame. Draws are inert, so the
 * contents are never consumed, but the slices must be valid and stable for the frame. The cursor
 * wraps when the arena is exhausted; a wrap can only invalidate data nothing reads yet.
 */
public final class MetalTransientMemory implements TransientMemory {

    private static final long CAPACITY = 64L << 20;

    private final MemorySegment arena;
    private long cursor;

    public MetalTransientMemory(MetalDevice device) {
        this.arena = device.arena().allocate(CAPACITY);
    }

    private synchronized MemorySegment allocate(long size, long alignment) {
        long safeSize = Math.max(1L, size);
        long align = alignment <= 1L ? 1L : alignment;
        long aligned = (this.cursor + align - 1L) & -align;
        if (aligned + safeSize > CAPACITY) {
            this.cursor = 0L;
            aligned = 0L;
        }
        this.cursor = aligned + safeSize;
        return this.arena.asSlice(aligned, safeSize);
    }

    private MetalBuffer temporaryBuffer(long size, long alignment, int usage) {
        MemorySegment memory = allocate(size, alignment);
        // The caller's usage bits are load-bearing: the engine validates them (for example
        // uploadStaging is used as a copy source and therefore requests USAGE_COPY_SRC). Dropping
        // them made CommandEncoder.copyBufferToTexture reject the slice.
        int effective = usage != 0 ? usage : (GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM);
        return new MetalBuffer(effective, Math.max(1L, size), memory);
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long lifetime, long flags) {
        return allocate(size, alignment).asByteBuffer();
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage,
                                                     long lifetime, long flags) {
        MetalBuffer buffer = temporaryBuffer(size, alignment, usage);
        GpuBufferSlice slice = buffer.slice(0L, Math.max(1L, size));
        return new GpuBufferSlice.MappedView(slice, buffer.memory().asByteBuffer(), () -> {
        });
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, int usage,
                                      long lifetime, long flags) {
        MetalBuffer buffer = temporaryBuffer(size, alignment, usage);
        return buffer.slice(0L, Math.max(1L, size));
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage,
                                                       long lifetime, long flags) {
        MetalBuffer buffer = temporaryBuffer(size, alignment, usage);
        GpuBufferSlice slice = buffer.slice(0L, Math.max(1L, size));
        return new GpuBufferSlice.MappedView(slice, buffer.memory().asByteBuffer(), () -> {
        });
    }

    private GpuBufferSlice upload(List<ByteBuffer> data, long alignment, int usage) {
        long total = 0L;
        for (ByteBuffer buffer : data) {
            total += buffer.remaining();
        }
        MetalBuffer target = temporaryBuffer(total, alignment, usage);
        ByteBuffer view = target.memory().asByteBuffer();
        for (ByteBuffer buffer : data) {
            view.put(buffer.duplicate());
        }
        return target.slice(0L, Math.max(1L, total));
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
}
