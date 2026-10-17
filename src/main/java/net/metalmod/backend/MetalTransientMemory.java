package net.metalmod.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import com.mojang.blaze3d.util.TransientBlockAllocator;

/**
 * Shared transient blocks. A full block spills instead of wrapping within a frame. Blocks rotate
 * only at a frame boundary and return to the free list after that frame's GPU ticket completes.
 * The engine allocator also implements the minimum-size and size-multiple allocation contract.
 */
public final class MetalTransientMemory implements TransientMemory, AutoCloseable {
    private static final long CAPACITY = 64L << 20;
    private final MetalDevice device;
    private final long maxBlockSize;
    private final TransientBlockAllocator<MetalBuffer> blocks;
    private final ArrayDeque<Retirement> retirements = new ArrayDeque<>();
    private boolean used;
    private boolean closed;
    private record Retirement(MemorySegment fence, Runnable recycle) {}

    public MetalTransientMemory(MetalDevice device) { this(device, CAPACITY); }

    MetalTransientMemory(MetalDevice device, long capacity) {
        this.device = device;
        this.maxBlockSize = MetalNative.deviceMaxBufferSize(device.deviceHandle()) - 16;
        this.blocks = new TransientBlockAllocator<>(capacity, 1L << 20,
                TransientBlockAllocator.Allocator.create(this::createBlock, MetalBuffer::close));
    }

    private MetalBuffer createBlock(long capacity) {
        if (capacity <= 0 || capacity > this.maxBlockSize)
            throw new IllegalArgumentException("Transient block exceeds device limit: " + capacity);
        MemorySegment handle = MetalNative.bufferCreate(this.device.deviceHandle(), capacity + 16);
        MemorySegment data = handle.address() == 0 ? MemorySegment.NULL
                : MetalNative.bufferContents(handle, capacity);
        if (handle.address() == 0 || data.address() == 0) {
            if (handle.address() != 0) MetalNative.bufferRelease(handle);
            throw new IllegalStateException("Could not allocate transient block: " + capacity);
        }
        return new MetalBuffer(GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST,
                capacity, handle, data, true, null).diagnosticLabel("transient staging arena");
    }

    private synchronized MetalBuffer allocate(long size, long alignment, int usage,
                                              long minimumSize, long sizeMultiple) {
        if (this.closed) throw new IllegalStateException("Transient memory is closed");
        if (size < 0 || size > this.maxBlockSize || minimumSize < 0 || minimumSize > size || sizeMultiple <= 0
                || alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("Invalid transient allocation bounds/alignment");
        }
        var allocation = this.blocks.allocate(Math.max(1L, size), alignment,
                Math.max(1L, minimumSize), sizeMultiple);
        this.used = true;
        int effective = usage != 0 ? usage : (GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM);
        return MetalBuffer.sub(effective, allocation.size(), allocation.block(), allocation.offset());
    }

    /** The caller has finished encoding every buffer consumer for this frame. */
    public synchronized void endFrame() {
        if (this.closed) return;
        while (!this.retirements.isEmpty() && MetalNative.fenceWait(this.retirements.peek().fence(), 0)) {
            recycleOldest();
        }
        if (!this.used) return;
        MemorySegment fence = MetalNative.fenceCreate(this.device.queueHandle());
        if (fence.address() == 0) throw new IllegalStateException("Could not fence transient blocks");
        this.retirements.add(new Retirement(fence, this.blocks.rotate()));
        this.used = false;
        // Bound retained generations without ever overwriting a pending reader.
        if (this.retirements.size() >= 3) {
            if (!MetalNative.fenceWait(this.retirements.peek().fence(), Long.MAX_VALUE)) {
                throw new IllegalStateException("Transient retirement did not complete");
            }
            recycleOldest();
        }
    }

    private void recycleOldest() {
        Retirement retired = this.retirements.remove();
        MetalNative.fenceRelease(retired.fence());
        retired.recycle().run();
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long minimumSize, long sizeMultiple) {
        return allocate(size, alignment, 0, minimumSize, sizeMultiple).data().asByteBuffer().order(java.nio.ByteOrder.nativeOrder());
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage,
                                                     long minimumSize, long sizeMultiple) {
        MetalBuffer buffer = allocate(size, alignment, usage, minimumSize, sizeMultiple);
        GpuBufferSlice slice = buffer.slice(0L, buffer.size());
        return new GpuBufferSlice.MappedView(slice,
                buffer.data().asByteBuffer().order(java.nio.ByteOrder.nativeOrder()), () -> {
        });
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, int usage,
                                      long minimumSize, long sizeMultiple) {
        MetalBuffer buffer = allocate(size, alignment, usage, minimumSize, sizeMultiple);
        return buffer.slice(0L, buffer.size());
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage,
                                                       long minimumSize, long sizeMultiple) {
        MetalBuffer buffer = allocate(size, alignment, usage, minimumSize, sizeMultiple);
        GpuBufferSlice slice = buffer.slice(0L, buffer.size());
        return new GpuBufferSlice.MappedView(slice,
                buffer.data().asByteBuffer().order(java.nio.ByteOrder.nativeOrder()), () -> {
        });
    }

    private GpuBufferSlice upload(List<ByteBuffer> data, long alignment, int usage,
                                  long minimumSize, long sizeMultiple) {
        long total = 0L;
        for (ByteBuffer buffer : data) {
            total = Math.addExact(total, buffer.remaining());
        }
        MetalBuffer target = allocate(total, alignment, usage, total, 1);
        if (target.isMapped()) {
            long telemetry = net.metalmod.debug.ResourceTelemetry.begin();
            ByteBuffer view = target.mappedBytes();
            for (ByteBuffer buffer : data) {
                view.put(buffer.duplicate());
            }
            net.metalmod.debug.ResourceTelemetry.end(telemetry, "buffer.transient-upload",
                    target.diagnosticLabel(), "buffer", total);
        }
        return target.slice(0L, target.size());
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage,
                                        long minimumSize, long sizeMultiple) {
        return upload(data, alignment, usage, minimumSize, sizeMultiple);
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage,
                                    long minimumSize, long sizeMultiple) {
        return upload(data, alignment, usage, minimumSize, sizeMultiple);
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
        List<GpuBufferSlice> slices = new ArrayList<>(data.size());
        for (ByteBuffer buffer : data) {
            slices.add(upload(List.of(buffer), alignment, usage, buffer.remaining(), 1));
        }
        return slices;
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
        List<GpuBufferSlice> slices = new ArrayList<>(data.size());
        for (ByteBuffer buffer : data) {
            slices.add(upload(List.of(buffer), alignment, usage, buffer.remaining(), 1));
        }
        return slices;
    }

    public synchronized void close() {
        if (this.closed) return;
        MetalNative.queueSynchronize(this.device.queueHandle());
        while (!this.retirements.isEmpty()) {
            MetalNative.fenceWait(this.retirements.peek().fence(), Long.MAX_VALUE);
            recycleOldest();
        }
        this.blocks.close();
        this.closed = true;
    }
}
