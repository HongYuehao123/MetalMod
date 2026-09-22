package net.metalmod;

import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;
import net.metalmod.render.JitterHelper;
import net.metalmod.render.VulkanFrameManager;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Standalone verification suite (no JUnit / no Minecraft required).
 *
 * The suite deliberately exercises the *per-frame* native entry point. The previous revision only
 * called metalmod_configure() and the capability queries, which is why a call-site bug that made
 * every single frame throw WrongMethodTypeException went unnoticed.
 */
public class StandaloneTestRunner {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("MetalMod Standalone Test Runner (macOS 26+ / Metal 4)");
        System.out.println("==================================================");

        testNativeBridgeLoading();
        testCapabilityQueriesHonourArguments();
        testHaltonJitter();
        testQualityPresets();
        testPipelineConfiguration();
        testFrameSubmissionContract();
        testUmaOwnershipRouting();

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

    /**
     * The capability queries used to cast their arguments to (void) and return whether the machine
     * has a Metal GPU, which made them always true. They must reject nonsensical requests.
     */
    private static void testCapabilityQueriesHonourArguments() {
        System.out.println("[TEST 2] Capability queries validate their arguments...");

        System.out.print("         valid 960x540 -> 1920x1080 spatial : ");
        check("valid spatial", MetalBridge.isSpatialSupported(960, 540, 1920, 1080), "(expected true)");

        System.out.print("         valid 960x540 -> 1920x1080 temporal: ");
        check("valid temporal", MetalBridge.isTemporalSupported(960, 540, 1920, 1080), "(expected true)");

        System.out.print("         zero dimensions rejected            : ");
        check("zero dims", !MetalBridge.isSpatialSupported(0, 0, 0, 0), "(expected false)");

        System.out.print("         input larger than output rejected    : ");
        check("downscale", !MetalBridge.isSpatialSupported(1920, 1080, 1280, 720), "(expected false)");

        System.out.print("         absurd dimensions rejected           : ");
        check("absurd dims", !MetalBridge.isSpatialSupported(1, 1, 999999, 999999), "(expected false)");

        System.out.print("         frame gen rejects zero dimensions    : ");
        check("fg zero dims", !MetalBridge.isFrameGenSupported(0, 0), "(expected false)");
    }

    private static void testHaltonJitter() {
        System.out.print("[TEST 3] Testing Halton(2, 3) subpixel camera jitter... ");
        int renderW = 1280;
        int renderH = 720;
        boolean ok = true;
        for (int i = 0; i < 32; i++) {
            JitterHelper.advance(renderW, renderH);
            float jx = JitterHelper.getJitterX();
            float jy = JitterHelper.getJitterY();
            if (jx < -0.5f || jx > 0.5f || jy < -0.5f || jy > 0.5f) {
                ok = false;
                break;
            }
        }
        JitterHelper.reset();
        boolean resetOk = JitterHelper.getJitterX() == 0.0f && JitterHelper.getJitterY() == 0.0f;
        check("jitter bounds", ok && resetOk, ok ? "(in [-0.5, 0.5], reset to zero)" : "");
    }

    private static void testQualityPresets() {
        System.out.print("[TEST 4] Testing Quality Preset Scaling Ratios... ");
        boolean ok = true;
        for (MetalConfig.QualityPreset preset : MetalConfig.QualityPreset.values()) {
            float scale = preset.getScale();
            if (scale <= 0.0f || scale > 1.0f) {
                ok = false;
                break;
            }
        }
        check("preset ratios", ok, "");
    }

    private static void testPipelineConfiguration() {
        System.out.println("[TEST 5] Testing native pipeline configuration (Spatial / Temporal)...");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfg = arena.allocate(MetalBridge.CONFIG_LAYOUT);
            cfg.set(ValueLayout.JAVA_INT, 0, 1280);
            cfg.set(ValueLayout.JAVA_INT, 4, 720);
            cfg.set(ValueLayout.JAVA_INT, 8, 1920);
            cfg.set(ValueLayout.JAVA_INT, 12, 1080);
            cfg.set(ValueLayout.JAVA_FLOAT, 24, 0.5f);
            cfg.set(ValueLayout.JAVA_INT, 32, 120);

            cfg.set(ValueLayout.JAVA_INT, 16, 1); // SPATIAL
            cfg.set(ValueLayout.JAVA_BOOLEAN, 20, false);
            System.out.print("         spatial            : ");
            check("configure spatial", MetalBridge.configure(cfg) == 0, "");

            cfg.set(ValueLayout.JAVA_INT, 16, 2); // TEMPORAL
            System.out.print("         temporal           : ");
            check("configure temporal", MetalBridge.configure(cfg) == 0, "");

            cfg.set(ValueLayout.JAVA_BOOLEAN, 20, true); // frame generation
            System.out.print("         temporal + frameGen: ");
            check("configure temporal+fg", MetalBridge.configure(cfg) == 0, "");

            cfg.set(ValueLayout.JAVA_INT, 16, 0); // OFF
            System.out.print("         off                : ");
            check("configure off", MetalBridge.configure(cfg) == 0, "");
        }
    }

    /**
     * Regression test for the per-frame call.
     *
     * MetalBridge.processFrame used to throw WrongMethodTypeException on every invocation because a
     * conditional expression inside an invokeExact call site compiled to an (Object,...) descriptor.
     * There is no Vulkan interop registered in this harness, so the native side must report a
     * defined status instead of crashing or throwing.
     */
    private static void testFrameSubmissionContract() {
        System.out.println("[TEST 6] Frame submission contract (regression: invokeExact call site)...");

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
            MemorySegment telemetry = arena.allocate(MetalBridge.TELEMETRY_LAYOUT);
            params.set(ValueLayout.JAVA_LONG, 0, 1L);
            params.set(ValueLayout.JAVA_FLOAT, 8, 1.0f / 60.0f);

            System.out.print("         hasVulkanInterop() reports false     : ");
            check("interop flag", !MetalBridge.hasVulkanInterop(), "(expected false in a bare harness)");

            System.out.print("         processFrame(NULL x4) does not throw : ");
            int rc;
            try {
                rc = MetalBridge.processFrame(null, null, null, null, params);
                check("processFrame no throw", true, "(status " + rc + ": "
                        + VulkanFrameManager.describeStatus(rc) + ")");
            } catch (Throwable t) {
                rc = Integer.MIN_VALUE;
                check("processFrame no throw", false, t.toString());
            }

            System.out.print("         status is a defined, non-crash code  : ");
            check("processFrame status", rc == VulkanFrameManager.STATUS_NO_PRESENTATION
                            || rc == VulkanFrameManager.STATUS_NO_VULKAN_INTEROP
                            || rc == VulkanFrameManager.STATUS_OK,
                    "(got " + rc + ")");

            System.out.print("         repeated submission stays stable     : ");
            boolean stable = true;
            for (int i = 0; i < 200; i++) {
                params.set(ValueLayout.JAVA_LONG, 0, i);
                int r = MetalBridge.processFrame(null, null, null, null, params);
                if (r != rc) {
                    stable = false;
                    break;
                }
            }
            check("stable repeats", stable, "");

            System.out.print("         telemetry readback                   : ");
            MetalBridge.getTelemetry(telemetry);
            float gpuMs = telemetry.get(ValueLayout.JAVA_FLOAT, 8);
            check("telemetry readback", gpuMs >= 0.0f && Float.isFinite(gpuMs),
                    String.format("(gpuFrameTimeMs=%.3f)", gpuMs));

            // A bogus image handle must be refused, never dereferenced. This used to return a
            // garbage pointer from a (__bridge id<MTLTexture>) cast and segfault in objc_retain.
            System.out.print("         bogus image handle refused, no crash : ");
            MemorySegment bogus = MemorySegment.ofAddress(0x7L);
            int bogusRc = MetalBridge.processFrame(bogus, null, null, null, params);
            check("bogus handle refused", bogusRc != VulkanFrameManager.STATUS_OK,
                    "(status " + bogusRc + ")");
        }
    }

    private static void testUmaOwnershipRouting() {
        System.out.println("[TEST 7] UMA allocator ownership routing...");

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
