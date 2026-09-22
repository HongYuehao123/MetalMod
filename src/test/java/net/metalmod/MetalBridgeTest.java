package net.metalmod;

import net.metalmod.ffi.MetalBridge;
import net.metalmod.render.VulkanFrameManager;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.*;

public class MetalBridgeTest {

    @Test
    public void testNativeBridgeLoading() {
        assertTrue(MetalBridge.isAvailable(), "MetalBridge should be available on macOS 26+: " + MetalBridge.getLoadError());
    }

    /**
     * The capability queries used to ignore their arguments entirely (they were cast to (void) and
     * the result was just "does this machine have a Metal GPU"), so every assertion here passed
     * trivially. They must now validate the requested configuration.
     */
    @Test
    public void testCapabilityQueries() {
        if (!MetalBridge.isAvailable()) return;

        assertTrue(MetalBridge.isSpatialSupported(960, 540, 1920, 1080),
                "MetalFX Spatial Scaler should support 960x540 -> 1920x1080");
        assertTrue(MetalBridge.isTemporalSupported(960, 540, 1920, 1080),
                "MetalFX Temporal Scaler should support 960x540 -> 1920x1080");

        assertFalse(MetalBridge.isSpatialSupported(0, 0, 0, 0), "zero dimensions must be rejected");
        assertFalse(MetalBridge.isSpatialSupported(1920, 1080, 1280, 720),
                "spatial scaling must reject an input larger than its output");
        assertFalse(MetalBridge.isSpatialSupported(1, 1, 999999, 999999),
                "impossible dimensions must be rejected");
        assertFalse(MetalBridge.isFrameGenSupported(0, 0), "zero dimensions must be rejected");
    }

    /**
     * Regression test: MetalBridge.processFrame threw WrongMethodTypeException on every call
     * because a conditional expression inside an invokeExact call site compiled to an (Object,...)
     * descriptor. This is the per-frame entry point, so the mod could never do any work.
     */
    @Test
    public void testProcessFrameDoesNotThrow() {
        if (!MetalBridge.isAvailable()) return;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfg = arena.allocate(MetalBridge.CONFIG_LAYOUT);
            cfg.set(ValueLayout.JAVA_INT, 0, 1280);
            cfg.set(ValueLayout.JAVA_INT, 4, 720);
            cfg.set(ValueLayout.JAVA_INT, 8, 1920);
            cfg.set(ValueLayout.JAVA_INT, 12, 1080);
            cfg.set(ValueLayout.JAVA_INT, 16, 1); // SPATIAL
            cfg.set(ValueLayout.JAVA_FLOAT, 24, 0.5f);
            MetalBridge.configure(cfg);

            MemorySegment params = arena.allocate(MetalBridge.FRAME_PARAMS_LAYOUT);
            params.set(ValueLayout.JAVA_LONG, 0, 1L);

            int rc = assertDoesNotThrow(
                    () -> MetalBridge.processFrame(null, null, null, null, params),
                    "processFrame must never throw from the render thread");

            assertTrue(rc == VulkanFrameManager.STATUS_OK
                            || rc == VulkanFrameManager.STATUS_NO_PRESENTATION
                            || rc == VulkanFrameManager.STATUS_NO_VULKAN_INTEROP,
                    "processFrame must report a defined status, got " + rc);
        }
    }

    /**
     * A VkImage handle must never be reinterpreted as an Objective-C object. Before this was
     * guarded, passing a synthetic handle crashed the process with SIGSEGV in objc_retain.
     */
    @Test
    public void testBogusImageHandleIsRefused() {
        if (!MetalBridge.isAvailable()) return;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment params = arena.allocate(MetalBridge.FRAME_PARAMS_LAYOUT);
            MemorySegment bogus = MemorySegment.ofAddress(0x7L);
            int rc = assertDoesNotThrow(
                    () -> MetalBridge.processFrame(bogus, null, null, null, params));
            assertNotEquals(VulkanFrameManager.STATUS_OK, rc,
                    "a handle that is not a registered VkImage must not be treated as success");
        }
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
