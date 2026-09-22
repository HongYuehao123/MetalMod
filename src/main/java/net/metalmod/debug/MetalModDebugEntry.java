package net.metalmod.debug;

import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;
import net.metalmod.memory.UnifiedMemoryManager;
import net.metalmod.render.VulkanFrameManager;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * MetalMod's F3 debug screen section.
 *
 * Since Minecraft 1.21.9+ the debug screen is no longer assembled by
 * {@code DebugScreenOverlay.extractLines} (that method still exists but is dead code). It is built
 * from a registry of {@link DebugScreenEntry} objects: the overlay iterates
 * {@code DebugScreenEntryList.getCurrentlyEnabled()} and calls {@link #display} on each.
 *
 * Adding lines therefore takes three parts - see {@link DebugScreenRegistration} for parts 2 and 3.
 */
public class MetalModDebugEntry implements DebugScreenEntry {

    /** Registry id. Must be a valid namespaced Identifier or the entry will not resolve. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("metalmod", "status");

    @Override
    public void display(DebugScreenDisplayer displayer,
                        Level clientLevel,
                        LevelChunk clientChunk,
                        LevelChunk serverChunk) {
        if (!MetalBridge.isAvailable()) {
            displayer.addLine("§6[MetalMod]§r native library not loaded: " + MetalBridge.getLoadError());
            return;
        }

        MetalConfig config = MetalConfig.INSTANCE;
        VulkanFrameManager mgr = VulkanFrameManager.getInstance();
        DebugScreenRegistration.recordDisplayCall();

        displayer.addLine("§6[MetalMod]§r Pipeline: " + (mgr.isPipelineActive()
                ? "§aactive§r"
                : "§cinactive§r (" + mgr.getPipelineStatusText() + ")"));

        // Report the request separately from the outcome. Nothing is scaled today, and saying
        // "MetalFX: Spatial" without that qualifier would imply an effect that is not happening.
        if (config.scalingMode != MetalConfig.ScalingMode.OFF || config.frameGeneration) {
            displayer.addLine("§6[MetalMod]§r Requested: §e" + config.scalingMode.getDisplayName()
                    + "§r / " + config.preset.getDisplayName()
                    + (config.frameGeneration ? " + frame gen" : "")
                    + " §7(not applied)§r");
        }

        displayer.addLine("§6[MetalMod]§r Resolution: §b"
                + mgr.getNativeWidth() + "x" + mgr.getNativeHeight()
                + "§r (internal scaling disabled)");

        displayer.addLine("§6[MetalMod]§r Render §a" + oneDecimal(mgr.getRenderFPS()) + " fps"
                + "§r | Window §a" + mgr.getNativeWidth() + "x" + mgr.getNativeHeight());

        // The backend's health counters. These are the numbers that explain a black or missing
        // object: a shader sampling something nothing bound, an attribute dropped from the vertex
        // descriptor, or a pipeline that failed to build and is therefore skipping its draws.
        int unbound = net.metalmod.backend.MetalDevice.unboundBindingCount();
        int unmapped = net.metalmod.backend.MetalDevice.unmappedAttributeCount();
        int failed = net.metalmod.backend.MetalDevice.pipelineFailureCount();
        String health = "§a0§r";
        if (unbound != 0 || unmapped != 0 || failed != 0) {
            health = "§c" + unbound + "/" + unmapped + "/" + failed + "§r";
        }
        displayer.addLine("§6[MetalMod]§r unbound/unmapped/failed: " + health
                + "§7 (0/0/0 = bindings, vertex attrs and pipelines all sound)§r");

        if (config.enableUnifiedMemoryPool) {
            UnifiedMemoryManager mem = UnifiedMemoryManager.getInstance();
            displayer.addLine("§6[MetalMod]§r UMA pool §aon§r | footprint §b"
                    + UnifiedMemoryManager.formatBytes(mem.getProcessResident())
                    + "§r | pressure " + mem.getPressureString());
        }

        // Surfaces problems without needing the game log: the F3 screen is the one place a user
        // reliably looks when something is wrong.
        String hooks = net.metalmod.Diagnostics.summary();
        if (hooks.indexOf('-') >= 0) {
            displayer.addLine("§6[MetalMod]§r hooks: " + hooks);
        }
    }

    /**
     * Show in both normal and reduced debug-info modes. This is diagnostic output, and hiding it
     * behind "reduced debug info" would make failures invisible exactly when they matter.
     */
    @Override
    public boolean isAllowed(boolean reducedDebugInfo) {
        return true;
    }

    private static String oneDecimal(float value) {
        return String.format("%.1f", value);
    }

    private static String twoDecimals(float value) {
        return String.format("%.2f", value);
    }
}
