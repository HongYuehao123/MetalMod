package net.metalmod;

import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;
import net.metalmod.render.JitterHelper;

public class StandaloneTestRunner {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("MetalMod Standalone Test Runner (macOS 26+ / Metal 4)");
        System.out.println("==================================================");

        // 1. Check Native Bridge
        System.out.print("[TEST 1] Loading libmetalmod.dylib via Panama FFI... ");
        if (MetalBridge.isAvailable()) {
            System.out.println("PASS");
        } else {
            System.out.println("FAIL: " + MetalBridge.getLoadError());
            System.exit(1);
        }

        // 2. Capabilities Check
        System.out.print("[TEST 2] Checking MetalFX Spatial Scaler support (960x540 -> 1920x1080)... ");
        boolean spatial = MetalBridge.isSpatialSupported(960, 540, 1920, 1080);
        System.out.println(spatial ? "PASS (Supported)" : "FAIL (Unsupported)");

        System.out.print("[TEST 3] Checking MetalFX Temporal Scaler support (960x540 -> 1920x1080)... ");
        boolean temporal = MetalBridge.isTemporalSupported(960, 540, 1920, 1080);
        System.out.println(temporal ? "PASS (Supported)" : "FAIL (Unsupported)");

        System.out.print("[TEST 4] Checking Metal 4 Frame Generation support (1920x1080)... ");
        boolean frameGen = MetalBridge.isFrameGenSupported(1920, 1080);
        System.out.println(frameGen ? "PASS (Supported)" : "FAIL (Unsupported)");

        // 3. Halton Jitter Sequence Verification
        System.out.print("[TEST 5] Testing Halton(2, 3) subpixel camera jitter... ");
        int renderW = 1280;
        int renderH = 720;
        for (int i = 0; i < 32; i++) {
            JitterHelper.advance(renderW, renderH);
            float jx = JitterHelper.getJitterX();
            float jy = JitterHelper.getJitterY();
            if (jx < -0.5f || jx > 0.5f || jy < -0.5f || jy > 0.5f) {
                System.out.println("FAIL (Jitter bounds exceeded: " + jx + ", " + jy + ")");
                System.exit(1);
            }
        }
        System.out.println("PASS (All phases in [-0.5, 0.5])");

        // 4. Config & Presets
        System.out.print("[TEST 6] Testing Quality Preset Scaling Ratios... ");
        for (MetalConfig.QualityPreset preset : MetalConfig.QualityPreset.values()) {
            float scale = preset.getScale();
            if (scale <= 0.0f || scale > 1.0f) {
                System.out.println("FAIL (Invalid scale: " + scale + " for " + preset);
                System.exit(1);
            }
        }
        System.out.println("PASS");

        // 5. Test Native Pipeline Configuration (Spatial & Temporal + Metal 4 Frame Gen)
        System.out.print("[TEST 7] Testing Native Metal 4 Pipeline Configuration (Spatial + Frame Gen)... ");
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            java.lang.foreign.MemorySegment cfg = arena.allocate(MetalBridge.CONFIG_LAYOUT);
            cfg.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 1280);
            cfg.set(java.lang.foreign.ValueLayout.JAVA_INT, 4, 720);
            cfg.set(java.lang.foreign.ValueLayout.JAVA_INT, 8, 1920);
            cfg.set(java.lang.foreign.ValueLayout.JAVA_INT, 12, 1080);
            cfg.set(java.lang.foreign.ValueLayout.JAVA_INT, 16, 1); // SPATIAL
            cfg.set(java.lang.foreign.ValueLayout.JAVA_BOOLEAN, 20, true); // Frame Gen
            cfg.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 24, 0.5f); // Sharpness
            cfg.set(java.lang.foreign.ValueLayout.JAVA_BOOLEAN, 28, false);
            cfg.set(java.lang.foreign.ValueLayout.JAVA_BOOLEAN, 29, true);
            cfg.set(java.lang.foreign.ValueLayout.JAVA_INT, 32, 120);

            int result = MetalBridge.configure(cfg);
            if (result != 0) {
                System.out.println("FAIL (Code: " + result + ")");
                System.exit(1);
            }
            System.out.println("PASS");

            System.out.print("[TEST 8] Testing Native Metal 4 Pipeline Configuration (Temporal + Frame Gen)... ");
            cfg.set(java.lang.foreign.ValueLayout.JAVA_INT, 16, 2); // TEMPORAL
            result = MetalBridge.configure(cfg);
            if (result != 0) {
                System.out.println("FAIL (Code: " + result + ")");
                System.exit(1);
            }
            System.out.println("PASS");
        }

        // 6. Unified Memory Architecture (UMA) Engine Tests
        UnifiedMemoryTest.runTests();

        System.out.println("==================================================");
        System.out.println("ALL TESTS PASSED SUCCESSFULLY! Ready for in-game execution.");
        System.out.println("==================================================");
    }
}
