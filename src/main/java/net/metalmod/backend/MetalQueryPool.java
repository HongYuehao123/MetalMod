package net.metalmod.backend;

import com.mojang.blaze3d.systems.GpuQueryPool;

import java.util.OptionalLong;

/** Phase 1 timestamp pool. Timestamps are not recorded yet, so every query reads empty. */
public final class MetalQueryPool implements GpuQueryPool {

    private final int size;
    private boolean closed;

    public MetalQueryPool(int size) {
        this.size = size;
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public OptionalLong getValue(int index) {
        return OptionalLong.empty();
    }

    @Override
    public OptionalLong[] getValues(int from, int to) {
        int count = Math.max(0, to - from);
        OptionalLong[] values = new OptionalLong[count];
        for (int i = 0; i < count; i++) {
            values[i] = OptionalLong.empty();
        }
        return values;
    }

    @Override
    public void close() {
        this.closed = true;
    }
}
