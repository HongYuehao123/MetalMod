package net.metalmod.client;

import net.fabricmc.api.ClientModInitializer;
import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;
import net.metalmod.render.VulkanFrameManager;

import java.lang.foreign.MemorySegment;

public class MetalModClient implements ClientModInitializer {

    public static final String MOD_ID = "metalmod";

    @Override
    public void onInitializeClient() {
        System.out.println("[MetalMod] Initializing MetalMod for macOS 26+ (Metal 4 & Vulkan backend)...");

        // Load configuration from config/metalmod.properties
        MetalConfig.INSTANCE.load();

        // Initialize native Metal bridge with auto window discovery
        if (MetalBridge.isAvailable()) {
            int res = MetalBridge.init(MemorySegment.NULL);
            if (res == 0) {
                System.out.println("[MetalMod] Native Metal 4 runtime initialized successfully.");
            } else {
                System.err.println("[MetalMod] Native initialization status: " + res);
            }
            VulkanFrameManager.getInstance().markConfigDirty();

            // Start background telemetry & macOS window title updater thread
            Thread titleThread = new Thread(() -> {
                try {
                    // Wait 2s for GLFW window to be created and displayed
                    Thread.sleep(2000);
                    while (!Thread.currentThread().isInterrupted()) {
                        VulkanFrameManager.getInstance().onFrameBegin();
                        MetalBridge.updateWindowTitle();
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException ignored) {
                }
            }, "MetalMod-TitleUpdater");
            titleThread.setDaemon(true);
            titleThread.start();
        } else {
            System.err.println("[MetalMod] MetalBridge unavailable: " + MetalBridge.getLoadError());
        }
    }
}
