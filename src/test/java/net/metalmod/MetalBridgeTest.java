package net.metalmod;

import net.metalmod.ffi.MetalBridge;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the retained native surface: loading the library and the Apple Silicon UMA pool.
 *
 * The MetalFX capability queries and {@code processFrame} tests that used to live here exercised
 * the retired MoltenVK-interop frame pipeline, which is gone (ROADMAP.md §4).
 */
public class MetalBridgeTest {

    @Test
    public void testNativeBridgeLoading() {
        assertTrue(MetalBridge.isAvailable(), "MetalBridge should be available on macOS 26+: " + MetalBridge.getLoadError());
    }

    @Test
    public void testUmaOwnershipRouting() {
        if (!MetalBridge.isAvailable()) return;

        MemorySegment buffer = MetalBridge.allocateUnifiedBuffer(4096);
        long address = buffer.address();
        assertNotEquals(0L, address, "UMA allocation should succeed");
        assertTrue(MetalBridge.ownsUnifiedBuffer(address), "a fresh allocation must be owned by the pool");
        assertTrue(MetalBridge.unifiedBufferSize(address) >= 4096, "size query must cover the request");

        // A foreign pointer must not be claimed, and reallocating it must fail rather than
        // silently allocating a fresh block and discarding the caller's data.
        assertFalse(MetalBridge.ownsUnifiedBuffer(0xDEADBEEF0L));
        assertTrue(MetalBridge.reallocateUnified(0xDEADBEEF0L, 8192).equals(MemorySegment.NULL));

        MetalBridge.freeUnifiedBuffer(buffer);
        assertFalse(MetalBridge.ownsUnifiedBuffer(address), "a freed allocation must no longer be owned");
    }
}
