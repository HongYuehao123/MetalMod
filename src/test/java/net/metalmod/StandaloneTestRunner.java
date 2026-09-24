package net.metalmod;

import net.metalmod.ffi.MetalBridge;

import java.lang.foreign.MemorySegment;

/**
 * Standalone verification suite (no JUnit / no Minecraft required).
 *
 * Exercises the retained native surface - library loading and the Apple Silicon UMA pool - plus the
 * backend's own tables and render-pass tests. The per-frame MetalFX / Vulkan-interop checks that
 * used to live here tested the retired frame pipeline (ROADMAP.md §4); that native code and its
 * bindings are gone, so those sections are gone with them.
 */
public class StandaloneTestRunner {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("MetalMod Standalone Test Runner (macOS 26+ / Metal 4)");
        System.out.println("==================================================");

        testNativeBridgeLoading();
        testUmaOwnershipRouting();
        failures += net.metalmod.backend.MetalRenderPassBackendTest.runTests();
        failures += net.metalmod.backend.MetalFormatTest.runTests();
        failures += net.metalmod.backend.MetalTextureStorageTest.runTests();
        failures += net.metalmod.debug.PerformanceRecordingTest.runTests();

        UnifiedMemoryTest.runTests();

        System.out.println("==================================================");
        if (failures == 0) {
            System.out.println("ALL TESTS PASSED SUCCESSFULLY!");
        } else {
            System.out.println(failures + " TEST(S) FAILED.");
        }
        System.out.println("==================================================");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void check(String label, boolean condition, String detail) {
        if (condition) {
            System.out.println("PASS" + (detail.isEmpty() ? "" : " " + detail));
        } else {
            failures++;
            System.out.println("FAIL (" + label + ")");
        }
    }

    private static void testNativeBridgeLoading() {
        System.out.print("[TEST 1] Loading libmetalmod.dylib via Panama FFI... ");
        if (!MetalBridge.isAvailable()) {
            failures++;
            System.out.println("FAIL: " + MetalBridge.getLoadError());
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static void testUmaOwnershipRouting() {
        System.out.println("[TEST 2] UMA allocator ownership routing...");

        MemorySegment buffer = MetalBridge.allocateUnifiedBuffer(4096);
        long address = buffer.address();

        System.out.print("         fresh allocation is owned           : ");
        check("owns fresh", address != 0 && MetalBridge.ownsUnifiedBuffer(address), "");

        System.out.print("         reported size covers the request     : ");
        check("size query", MetalBridge.unifiedBufferSize(address) >= 4096,
                "(" + MetalBridge.unifiedBufferSize(address) + " bytes)");

        long foreign = 0xDEADBEEF0L;
        System.out.print("         foreign pointer is not claimed       : ");
        check("owns foreign", !MetalBridge.ownsUnifiedBuffer(foreign), "");

        System.out.print("         realloc of a foreign pointer fails   : ");
        MemorySegment result = MetalBridge.reallocateUnified(foreign, 8192);
        check("realloc foreign", result.equals(MemorySegment.NULL),
                "(must not silently discard the caller's data)");

        MetalBridge.freeUnifiedBuffer(buffer);
        System.out.print("         freed allocation is no longer owned  : ");
        check("owns freed", !MetalBridge.ownsUnifiedBuffer(address), "");
    }
}
