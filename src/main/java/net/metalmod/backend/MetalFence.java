package net.metalmod.backend;

import com.mojang.blaze3d.buffers.GpuFence;

/** Phase 1 fence: submission is synchronous from the CPU's point of view, so completion is immediate. */
public final class MetalFence implements GpuFence {

    private boolean closed;

    @Override
    public void close() {
        this.closed = true;
    }

    @Override
    public boolean awaitCompletion(long timeoutNanos) {
        return !this.closed;
    }
}
