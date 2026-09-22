package net.metalmod.client;

import net.fabricmc.api.ClientModInitializer;
import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;

import net.metalmod.memory.UnifiedMemoryManager;

public class MetalModClient implements ClientModInitializer {

    public static final String MOD_ID = "metalmod";

    @Override
    public void onInitializeClient() {
        System.out.println("[MetalMod] Initializing MetalMod for macOS 26+ (native Metal backend)...");

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

        // The native library now carries only the UMA/memory pool; the Metal renderer device is
        // created by MetalBackend through MetalNative when the engine selects the backend. Without
        // the library there is no memory telemetry to start, so stop here.
        if (!MetalBridge.isAvailable()) {
            System.err.println("[MetalMod] libmetalmod.dylib unavailable: " + MetalBridge.getLoadError()
                    + " (Metal backend and UMA telemetry are disabled).");
            return;
        }

        // Initialize Apple Silicon Unified Memory Architecture engine
        UnifiedMemoryManager.getInstance().initialize();

        startTelemetryThread();
    }

    /**
     * Background thread for memory telemetry only. It never touches rendering; every Metal object
     * belongs to the render thread and the backend that creates it.
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
