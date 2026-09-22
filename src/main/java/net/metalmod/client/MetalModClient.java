package net.metalmod.client;

import net.fabricmc.api.ClientModInitializer;
import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;
import net.metalmod.render.VulkanFrameManager;

import net.metalmod.memory.UnifiedMemoryManager;

import java.lang.foreign.MemorySegment;

public class MetalModClient implements ClientModInitializer {

    public static final String MOD_ID = "metalmod";

    @Override
    public void onInitializeClient() {
        System.out.println("[MetalMod] Initializing MetalMod for macOS 26+ (Metal 4 & Vulkan backend)...");

        // Load configuration from config/metalmod.properties
        MetalConfig.INSTANCE.load();

        // Environment + expectation report first, so a log always shows what was attempted even
        // if the native library or the mixins never come up.
        net.metalmod.Diagnostics.reportEnvironment();

        // Register the F3 debug section and immediately read it back through Minecraft's own
        // public API. Registering is a three-part process (entry + accessor + visibility), and a
        // missing piece would otherwise only show up as silently absent F3 lines.
        boolean f3Registered = net.metalmod.debug.DebugScreenRegistration.register();
        boolean f3Verified = net.metalmod.debug.DebugScreenRegistration.verify();
        System.out.println("[MetalMod] F3 debug entry: registered=" + f3Registered
                + " verified=" + f3Verified + " id=" + net.metalmod.debug.MetalModDebugEntry.ID);
        if (!f3Verified) {
            System.err.println("[MetalMod] The F3 debug entry is NOT in the registry. The "
                    + "DebugScreenEntriesAccessor mixin did not apply; F3 will show no MetalMod section.");
        }

        if (!MetalBridge.isAvailable()) {
            System.err.println("[MetalMod] MetalBridge unavailable: " + MetalBridge.getLoadError());
            return;
        }

        // Initialize native Metal bridge with auto window discovery
        int res = MetalBridge.init(MemorySegment.NULL);
        if (res == 0) {
            System.out.println("[MetalMod] Native Metal 4 runtime initialized successfully.");
        } else {
            System.err.println("[MetalMod] Native initialization failed with status " + res
                    + "; the Metal pipeline will stay inactive.");
            return;
        }

        VulkanFrameManager.getInstance().markConfigDirty();

        // Seed the window title. If no render mixin ever applies, this text stays on screen, which
        // is the clearest possible signal that the hooks did not match this Minecraft build.
        MetalBridge.reportPipelineStatus("waiting for render hooks");

        // Initialize Apple Silicon Unified Memory Architecture engine
        UnifiedMemoryManager.getInstance().initialize();

        startTelemetryThread();
    }

    /**
     * Background thread for memory telemetry only.
     *
     * It deliberately does NOT touch the frame pipeline: frame submission, jitter advance and
     * pipeline reconfiguration all belong to the render thread. Driving VulkanFrameManager from
     * here previously reconfigured MTLTextures and MetalFX scalers concurrently with the frame
     * that was using them.
     */
    private void startTelemetryThread() {
        Thread thread = new Thread(() -> {
            try {
                // Wait for the window to be created and displayed.
                Thread.sleep(2000);
                boolean summaryReported = false;
                long started = System.currentTimeMillis();
                while (!Thread.currentThread().isInterrupted()) {
                    UnifiedMemoryManager.getInstance().updateTelemetry();
                    MetalBridge.updateWindowTitle();

                    // Once the game has been running for a while, report the definitive list of
                    // hooks that applied. Anything still showing '-' did not match and was
                    // silently skipped by Mixin (all injections use require = 0).
                    if (!summaryReported && System.currentTimeMillis() - started > 30_000) {
                        summaryReported = true;
                        System.out.println("[MetalMod] hook summary after 30s: "
                                + net.metalmod.Diagnostics.summary());
                        System.out.println(net.metalmod.backend.MetalDevice.resourceSummary());
                        System.out.println("[MetalMod] F3 section built "
                                + net.metalmod.debug.DebugScreenRegistration.displayCallCount()
                                + " time(s). 0 means F3 was never opened, or the entry is still hidden.");
                        if (!net.metalmod.Diagnostics.hasHook("GameRenderer.render")) {
                            System.out.println("[MetalMod] The per-frame hook did not apply, so the "
                                    + "FrameManager never runs (resolution will read as 0x0).");
                        }
                    }

                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "MetalMod-Telemetry");
        thread.setDaemon(true);
        thread.start();
    }
}
