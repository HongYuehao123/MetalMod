package net.metalmod.upscaling;

import java.lang.foreign.MemorySegment;
import net.metalmod.backend.MetalNative;

/** Checks the optional Phase 7C ABI against the packaged native library. GPU pixels live in smoke. */
public final class FrameInterpolationTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static int runTests() {
        MemorySegment device = MemorySegment.NULL, interpolator = MemorySegment.NULL;
        try {
            var nil = MemorySegment.NULL;
            require(!MetalNative.interpolationSupported(nil), "NULL support denied");
            require(!MetalNative.interpolationHealthy(nil), "NULL health denied");
            require(MetalNative.interpolationTextureUsage(nil, 0) == -1, "NULL usage denied");
            require(MetalNative.interpolationEncode(nil,nil,nil,nil,nil,nil,nil,nil,0L,1L,
                    1f/60,0.05f,1000f,70f,0.25f,-0.25f,true,false) == -1, "long/float/bool encode ABI");
            device = MetalNative.deviceCreate();
            require(device.address() != 0, "host Metal device");
            if (MetalNative.interpolationSupported(device)) {
                require(MetalNative.interpolationCreate(device,0,48,64,48).address() == 0, "invalid dimensions");
                interpolator = MetalNative.interpolationCreate(device,32,24,64,48);
                require(interpolator.address() != 0 && MetalNative.interpolationHealthy(interpolator), "real interpolator");
                for (int role=0;role<5;role++)
                    require(MetalNative.interpolationTextureUsage(interpolator,role) > 0, "usage role " + role);
                require(MetalNative.interpolationTextureUsage(interpolator,5) == -1, "unknown usage role");
            } else System.out.println("SKIP interpolation FFI creation: device unsupported");
            System.out.println("PASS frame interpolation capability and Panama ABI");
            return 0;
        } catch (RuntimeException | AssertionError error) {
            error.printStackTrace(); return 1;
        } finally {
            MetalNative.interpolationRelease(interpolator);
            if (device.address() != 0) MetalNative.deviceRelease(device);
        }
    }
}
