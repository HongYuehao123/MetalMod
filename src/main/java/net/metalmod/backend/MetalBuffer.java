package net.metalmod.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * A real MTLBuffer in shared storage. Mapping hands out a ByteBuffer over the buffer's CPU-visible
 * memory, so uniform writes and ring-buffer uploads work without a staging copy.
 *
 * <p>A sub-buffer shares its parent's MTLBuffer handle and owns nothing; this is what lets the
 * transient arena slice large allocations without creating one MTLBuffer per request.
 */
public final class MetalBuffer extends GpuBuffer {

    private final MemorySegment handle;
    private final MemorySegment data;
    private final boolean ownsHandle;
    private final long baseOffset;
    @SuppressWarnings("unused")
    private final Object owner;
    private boolean closed;

    public MetalBuffer(int usage, long size, MemorySegment handle, MemorySegment data,
                       boolean ownsHandle, Object owner) {
        this(usage, size, handle, data, ownsHandle, owner, 0L);
    }

    public MetalBuffer(int usage, long size, MemorySegment handle, MemorySegment data,
                       boolean ownsHandle, Object owner, long baseOffset) {
        super(usage, size);
        this.handle = handle == null ? MemorySegment.NULL : handle;
        this.data = data == null ? MemorySegment.NULL : data;
        this.ownsHandle = ownsHandle;
        this.baseOffset = baseOffset;
        this.owner = owner;
    }

    public static MetalBuffer sub(int usage, long size, MetalBuffer parent, long offset) {
        MemorySegment slice = (parent.data.address() == 0)
                ? MemorySegment.NULL
                : parent.data.asSlice(offset, size);
        return new MetalBuffer(usage, size, parent.handle, slice, false, parent,
                parent.baseOffset + offset);
    }

    /**
     * Byte offset of this buffer inside the MTLBuffer that {@link #handle()} refers to.
     *
     * <p>A sub-buffer shares its parent's handle, so a {@code GpuBufferSlice} over it reports an
     * offset relative to the sub-buffer while the handle points at the parent. Anything that binds
     * the handle on the GPU must add this, or it binds the parent's start instead. The CPU paths do
     * not need it: they read through {@link #data()}, which is already offset.
     */
    public long baseOffset() {
        return this.baseOffset;
    }

    public MemorySegment handle() {
        return this.handle;
    }

    public MemorySegment data() {
        return this.data;
    }

    public boolean isValid() {
        return this.handle.address() != 0;
    }

    public boolean isMapped() {
        return this.data.address() != 0;
    }

    /** A native view of [offset, offset+length) inside this buffer's CPU memory, or NULL. */
    public MemorySegment dataSlice(long offset, long length) {
        if (this.data.address() == 0) {
            return MemorySegment.NULL;
        }
        return this.data.asSlice(offset, length);
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        GpuBufferSlice slice = slice(offset, length);
        ByteBuffer mapped;
        if (this.data.address() == 0) {
            mapped = ByteBuffer.allocateDirect((int) Math.max(0L, Math.min(length, Integer.MAX_VALUE)));
        } else {
            // Native order is essential: the caller writes floats without changing the buffer order,
            // and the GPU reads the MTLBuffer bytes as little-endian. A big-endian view byte-swaps
            // every uniform, which is why ColorModulator arrived as ~0 and everything drew black.
            mapped = this.data.asSlice(offset, length).asByteBuffer().order(java.nio.ByteOrder.nativeOrder());
        }
        return new GpuBufferSlice.MappedView(slice, mapped, () -> {
        });
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.ownsHandle) {
            MetalNative.bufferRelease(this.handle);
        }
    }
}
