package net.metalmod.debug;

import net.metalmod.upscaling.MetalFxCoordinator;
import java.util.List;

/** Appended F8 columns. Effective: 0 native, 1 spatial, 2 plain recovery. GPU ns is unavailable (-1). */
public final class UpscalingCaptureColumns {
    public static final List<String> NAMES = List.of("sr_requested", "sr_strength", "sr_effective",
            "sr_scene_width", "sr_scene_height", "sr_output_width", "sr_output_height",
            "sr_scene_pixels_percent_x100", "sr_generation", "sr_creates", "sr_failures", "sr_encodes",
            "sr_recoveries", "sr_retirements", "sr_world_hooks", "sr_ui_hooks", "sr_gpu_ns", "sr_reason");
    private UpscalingCaptureColumns() {}
    // Stable reason codes: 0 active, 1 Off, 2 100%, 3 menu, 4 no Metal, 5 suspended,
    // 6 unsupported, 7 creation, 8 scene setup, 9 GPU error, 10 encode failure, 11 uninitialized.
    public static int reasonCode(String reason) {
        if (reason.isEmpty()) return 0;
        return switch (reason) {
            case "Off" -> 1; case "100% bypass" -> 2; case "no world" -> 3;
            case "Metal unavailable" -> 4; case "surface suspended" -> 5;
            case "MetalFX unsupported/denied" -> 6; case "MetalFX creation failed" -> 7;
            case "GPU error; native fallback" -> 9;
            default -> reason.startsWith("scene setup") ? 8 : reason.startsWith("MetalFX encode") ? 10 : 11;
        };
    }
    public static long value(MetalFxCoordinator.Stats s, int index) {
        return switch(index) {
            case 0 -> s.requested() ? 1 : 0; case 1 -> s.strength();
            case 2 -> s.effective().equals("Native") ? 0 : s.effective().equals("MetalFX spatial") ? 1 : 2;
            case 3 -> s.sceneWidth(); case 4 -> s.sceneHeight(); case 5 -> s.outputWidth(); case 6 -> s.outputHeight();
            case 7 -> Math.round(s.pixelPercent()*100); case 8 -> s.generation(); case 9 -> s.creates();
            case 10 -> s.failures(); case 11 -> s.encodes(); case 12 -> s.recoveries(); case 13 -> s.retirements();
            case 14 -> s.worldHooks(); case 15 -> s.uiHooks(); case 16 -> -1;
            case 17 -> reasonCode(s.reason());
            default -> throw new IllegalArgumentException("Unknown SR capture column");
        };
    }
}
