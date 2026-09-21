package net.metalmod.mixin;

import net.metalmod.config.MetalConfig;
import net.metalmod.render.VulkanFrameManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(targets = "net.minecraft.client.gui.components.DebugScreenOverlay")
public class DebugScreenOverlayMixin {

    @Inject(method = "extractLines", at = @At("HEAD"), require = 0)
    private void onExtractLines(GuiGraphicsExtractor graphicsExtractor, List<String> lines, boolean rightSide, CallbackInfo ci) {
        if (!rightSide && lines != null) {
            VulkanFrameManager mgr = VulkanFrameManager.getInstance();
            MetalConfig config = MetalConfig.INSTANCE;

            lines.add("");
            lines.add("§6[MetalMod Metal 4]§r Mode: " + config.scalingMode.getDisplayName() +
                      " | Frame Gen: " + (config.frameGeneration ? "§aON (Metal 4)§r" : "§cOFF§r"));
            lines.add("§6[MetalMod Resolution]§r Original Render: §e" + mgr.getRenderWidth() + "x" + mgr.getRenderHeight() +
                      "§r -> Target Display: §b" + mgr.getNativeWidth() + "x" + mgr.getNativeHeight() +
                      "§r (" + config.preset.getDisplayName() + ")");
            lines.add("§6[MetalMod Telemetry]§r Render FPS: §a" + String.format("%.1f", mgr.getRenderFPS()) +
                      "§r | Display FPS: §a" + String.format("%.1f", mgr.getPresentedFPS()) +
                      "§r | GPU Frame Time: §e" + String.format("%.2f ms", mgr.getGpuFrameTimeMs()) + "§r");

            if (config.enableUnifiedMemoryPool) {
                net.metalmod.memory.UnifiedMemoryManager mem = net.metalmod.memory.UnifiedMemoryManager.getInstance();
                long totalRam = mem.getTotalPhysicalMemory();
                long availRam = mem.getAvailableMemory();
                long resident = mem.getProcessResident();
                long metalAlloc = mem.getMetalAllocated();
                long metalMax = mem.getMetalMaxWorkingSet();
                long swap = mem.getSwapUsed();

                lines.add("§6[MetalMod UMA Memory]§r RAM: §a" + net.metalmod.memory.UnifiedMemoryManager.formatBytes(totalRam - availRam) +
                          "§r / " + net.metalmod.memory.UnifiedMemoryManager.formatBytes(totalRam) +
                          " | Footprint: §b" + net.metalmod.memory.UnifiedMemoryManager.formatBytes(resident) +
                          "§r | Pressure: " + mem.getPressureString());
                lines.add("§6[MetalMod Metal VRAM]§r Allocated: §e" + net.metalmod.memory.UnifiedMemoryManager.formatBytes(metalAlloc) +
                          "§r (Cap: " + net.metalmod.memory.UnifiedMemoryManager.formatBytes(metalMax) +
                          ") | Swap: §d" + net.metalmod.memory.UnifiedMemoryManager.formatBytes(swap) + "§r");
            }
        }
    }
}
