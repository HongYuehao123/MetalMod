package net.metalmod.backend;

import com.mojang.blaze3d.buffers.GpuFence;

import java.lang.foreign.MemorySegment;

/**
 * A real fence over an {@code MTLSharedEvent}.
 *
 * <p>Minecraft uses a fence to decide when a ring-buffer slot may be overwritten, and waits on it
 * with an unbounded timeout — {@code MappableRingBuffer.rotate} calls
 * {@code awaitCompletion(Long.MAX_VALUE)} before recycling a slot. The previous implementation
 * returned {@code true} immediately on the reasoning that "submission is synchronous", but MetalMod
 * only <em>commits</em> command buffers; it does not wait for them. So the CPU could overwrite data
 * the GPU was still reading, which surfaces as intermittent corruption rather than a clean failure.
 */
public final class MetalFence implements GpuFence {

    private final MemorySegment handle;
    private boolean closed;

    public MetalFence(MemorySegment handle) {
        this.handle = handle == null ? MemorySegment.NULL : handle;
    }

    @Override
    public boolean awaitCompletion(long timeoutNanos) {
        if (this.closed) {
            return true;
        }
        if (this.handle.address() == 0) {
            // No fence handle: refuse to block rather than hang the render thread.
            return true;
        }
        return MetalNative.fenceWait(this.handle, timeoutNanos);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.handle.address() != 0) {
            MetalNative.fenceRelease(this.handle);
        }
    }
}
