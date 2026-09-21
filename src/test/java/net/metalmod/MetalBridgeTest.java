package net.metalmod;

import net.metalmod.ffi.MetalBridge;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MetalBridgeTest {

    @Test
    public void testNativeBridgeLoading() {
        assertTrue(MetalBridge.isAvailable(), "MetalBridge should be available on macOS 26+: " + MetalBridge.getLoadError());
    }

    @Test
    public void testCapabilityQueries() {
        if (!MetalBridge.isAvailable()) return;

        // Test Spatial Scaler capability
        boolean spatialSupported = MetalBridge.isSpatialSupported(960, 540, 1920, 1080);
        assertTrue(spatialSupported, "MetalFX Spatial Scaler should be supported on Apple Silicon");

        // Test Temporal Scaler capability
        boolean temporalSupported = MetalBridge.isTemporalSupported(960, 540, 1920, 1080);
        assertTrue(temporalSupported, "MetalFX Temporal Scaler should be supported on Apple Silicon");

        // Test Frame Gen capability
        boolean frameGenSupported = MetalBridge.isFrameGenSupported(1920, 1080);
        System.out.println("[MetalMod Test] Frame Generation Supported: " + frameGenSupported);
        assertTrue(frameGenSupported, "Metal 4 Frame Generation should be supported on Apple Silicon macOS 26+");
    }
}
