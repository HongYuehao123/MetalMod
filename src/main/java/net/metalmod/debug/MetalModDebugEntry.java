package net.metalmod.debug;

import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;
import net.metalmod.memory.UnifiedMemoryManager;
import net.minecraft.client.Minecraft;
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
        DebugScreenRegistration.recordDisplayCall();

        // The first line has to answer "is Metal drawing this frame?". It used to report the
        // *MetalFX frame pipeline* under the bare label "Pipeline", so a session rendering happily on
        // Metal still showed `Pipeline: inactive (Vulkan interop not registered)` - the upscaler is
        // inactive by design, but nothing said so, and the line read as if the backend were off.
        // The engine's own DeviceInfo is the authoritative answer: it names the backend it selected.
        String backend = "none";
        com.mojang.blaze3d.systems.GpuDevice device =
                com.mojang.blaze3d.systems.RenderSystem.tryGetDevice();
        if (device != null && device.getDeviceInfo() != null) {
            backend = device.getDeviceInfo().backendName();
        }
        boolean metalActive = "Metal".equals(backend);
        displayer.addLine("§6[MetalMod]§r Backend: " + (metalActive
                ? "§aMetal§r (active)"
                : "§7" + backend + "§r (Metal not in use)"));

        // The framebuffer the backend is actually working with, straight from the engine's window.
        String resolution = "?";
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getWindow() != null) {
            resolution = minecraft.getWindow().getWidth() + "x" + minecraft.getWindow().getHeight();
        }
        displayer.addLine("§6[MetalMod]§r Resolution: §b" + resolution);

        // Frame timing and the CPU/GPU split. "wait" is the time the render thread spent blocked in
        // nextDrawable, i.e. waiting on the GPU; the rest of the frame interval is CPU work. Wait
        // near the frame time means GPU-bound, wait near zero means CPU-bound. (A fast vsync-capped
        // frame also waits for the display, so only read "GPU-bound" when the frame is slower than
        // that.) Draws is the number that grows underground.
        float frameMs = net.metalmod.backend.MetalDevice.lastFrameMs();
        float waitMs = net.metalmod.backend.MetalDevice.lastAcquireWaitMs();
        String bound = frameMs <= 0.0f ? ""
                : waitMs >= frameMs * 0.5f ? " §7(GPU-bound)§r"
                : waitMs <= frameMs * 0.15f ? " §7(CPU-bound)§r" : " §7(mixed)§r";
        displayer.addLine("§6[MetalMod]§r Frame §b" + oneDecimal(frameMs) + " ms avg§r | GPU wait §b"
                + oneDecimal(waitMs) + " ms avg§r | §b" + net.metalmod.backend.MetalDevice.lastFrameDraws()
                + "§r draws §7(" + net.metalmod.backend.MetalDevice.lastCommandBuffers()
                + " render passes)§r" + bound);

        // The backend's health counters. These are the numbers that explain a black or missing
        // object: a shader sampling something nothing bound, an attribute dropped from the vertex
        // descriptor, or a pipeline that failed to build and is therefore skipping its draws.
        int unbound = net.metalmod.backend.MetalDevice.unboundBindingCount();
        int unmapped = net.metalmod.backend.MetalDevice.missingVertexAttributeCount();
        int failed = net.metalmod.backend.MetalDevice.pipelineFailureCount();
        String health = "§a0§r";
        if (unbound != 0 || unmapped != 0 || failed != 0) {
            health = "§c" + unbound + "/" + unmapped + "/" + failed + "§r";
        }
        displayer.addLine("§6[MetalMod]§r unbound/missingAttr/failed: " + health
                + "§7 (0/0/0 = bindings, missing vertex attributes, pipeline builds)§r");

        if (MetalConfig.INSTANCE.enableUnifiedMemoryPool && MetalBridge.isAvailable()) {
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
}
