package net.metalmod.memory;

import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;
import org.lwjgl.system.MemoryUtil;

import java.lang.foreign.MemorySegment;

/**
 * Custom LWJGL MemoryAllocator powered by Apple Silicon Unified Memory Architecture (UMA).
 * Routes 100% of LWJGL, Minecraft, and Sodium off-heap direct allocations into 16 KB page-aligned
 * zero-copy unified memory with MADV_FREE_REUSABLE decommit.
 */
public class MetalMemoryAllocator implements MemoryUtil.MemoryAllocator {

    @Override
    public long malloc(long size) {
        if (size <= 0) return 0L;
        if (MetalConfig.INSTANCE.enableUnifiedMemoryPool && MetalBridge.isAvailable()) {
            MemorySegment seg = MetalBridge.allocateUnifiedBuffer(size);
            if (!seg.equals(MemorySegment.NULL)) {
                return seg.address();
            }
        }
        return 0L;
    }

    @Override
    public long calloc(long num, long size) {
        if (num <= 0 || size <= 0) return 0L;
        if (MetalConfig.INSTANCE.enableUnifiedMemoryPool && MetalBridge.isAvailable()) {
            MemorySegment seg = MetalBridge.allocateUnifiedCalloc(num, size);
            if (!seg.equals(MemorySegment.NULL)) {
                return seg.address();
            }
        }
        return 0L;
    }

    @Override
    public long realloc(long ptr, long size) {
        if (size <= 0) {
            free(ptr);
            return 0L;
        }
        if (ptr == 0L) {
            return malloc(size);
        }
        if (MetalConfig.INSTANCE.enableUnifiedMemoryPool && MetalBridge.isAvailable()) {
            MemorySegment seg = MetalBridge.reallocateUnified(ptr, size);
            if (!seg.equals(MemorySegment.NULL)) {
                return seg.address();
            }
        }
        return 0L;
    }

    @Override
    public void free(long ptr) {
        if (ptr == 0L) return;
        if (MetalConfig.INSTANCE.enableUnifiedMemoryPool && MetalBridge.isAvailable()) {
            MetalBridge.freeUnifiedBufferAddress(ptr);
        }
    }

    @Override
    public long aligned_alloc(long alignment, long size) {
        if (size <= 0) return 0L;
        if (MetalConfig.INSTANCE.enableUnifiedMemoryPool && MetalBridge.isAvailable()) {
            MemorySegment seg = MetalBridge.allocateUnifiedAligned(alignment, size);
            if (!seg.equals(MemorySegment.NULL)) {
                return seg.address();
            }
        }
        return malloc(size);
    }

    @Override
    public void aligned_free(long ptr) {
        free(ptr);
    }
}
