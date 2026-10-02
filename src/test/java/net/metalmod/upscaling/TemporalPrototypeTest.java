package net.metalmod.upscaling;

import java.lang.foreign.MemorySegment;
import net.metalmod.backend.MetalNative;

/** Checks sampling signs and the optional Panama temporal ABI against the real dylib. */
public final class TemporalPrototypeTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static int runTests() {
        MemorySegment device = MemorySegment.NULL, scaler = MemorySegment.NULL;
        try {
            var first = TemporalSampling.jitter(0);
            require(first.x() == 0 && Math.abs(first.y() + 1.0f / 6) < 1e-6, "first jitter sample");
            require(first.equals(TemporalSampling.jitter(16)), "deterministic jitter wrap/reset");
            for (int i = 0; i < 16; i++) {
                var sample = TemporalSampling.jitter(i);
                require(Math.abs(sample.x()) <= 0.5 && Math.abs(sample.y()) <= 0.5, "jitter pixel bounds");
            }
            // In NDC, +X is right and -Y is down: previous lookup must be up/left.
            var moved = TemporalSampling.motion(0.2f, -0.2f, 0, 0, 100, 100);
            require(moved.x() == -10 && moved.y() == -10, "right/down object: negative pixel motion");
            var still = TemporalSampling.motion(0.3f, 0.4f, 0.3f, 0.4f, 100, 80);
            require(still.x() == 0 && still.y() == 0, "stationary unjittered geometry");
            boolean rejected = false;
            try { TemporalSampling.motion(Float.NaN, 0, 0, 0, 100, 100); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "invalid geometry rejected");
            require(MetalNative.isAvailable(), "native library loaded");
            require(!MetalNative.temporalSupported(MemorySegment.NULL), "null capability denied");
            require(!MetalNative.temporalHealthy(MemorySegment.NULL), "null health denied");
            require(MetalNative.temporalEncode(MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                    0.25f, -0.25f, false, true) == -1, "encode ABI floats/bools/null rejection");
            device = MetalNative.deviceCreate();
            require(device.address() != 0, "host Metal device");
            if (MetalNative.temporalSupported(device)) {
                scaler = MetalNative.temporalCreate(device, 48, 36, 64, 48);
                require(scaler.address() != 0 && MetalNative.temporalHealthy(scaler), "real temporal FFI creation");
                for (int role=0; role<5; role++)
                    require(MetalNative.temporalTextureUsage(scaler, role) > 0, "required usage query " + role);
                require(MetalNative.temporalTextureUsage(scaler, 5) == -1, "invalid usage role denied");
                require(MetalNative.temporalCreate(device, 0, 32, 64, 48).address() == 0, "invalid dimensions denied");
            } else System.out.println("SKIP temporal FFI creation: device unsupported");
            System.out.println("PASS temporal prototype sampling signs and Panama ABI");
            return 0;
        } catch (AssertionError | RuntimeException e) { e.printStackTrace(); return 1; }
        finally {
            MetalNative.temporalRelease(scaler);
            if (device.address() != 0) MetalNative.deviceRelease(device);
        }
    }
}
