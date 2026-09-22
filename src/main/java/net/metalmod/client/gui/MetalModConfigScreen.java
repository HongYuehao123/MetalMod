package net.metalmod.client.gui;

import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;
import net.metalmod.render.VulkanFrameManager;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MetalModConfigScreen extends Screen {

    private final Screen parent;
    private Button scalerButton;
    private Button presetButton;
    private Button frameGenButton;
    private Button displayRateButton;
    private Button metalBackendButton;

    public MetalModConfigScreen(Screen parent) {
        super(Component.literal("MetalMod: Apple Silicon Metal 4 Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 4;
        int buttonWidth = 240;
        int buttonHeight = 20;

        // 1. Upscaling Mode Button
        scalerButton = Button.builder(getScalerText(), btn -> {
            MetalConfig.ScalingMode[] modes = MetalConfig.ScalingMode.values();
            int next = (MetalConfig.INSTANCE.scalingMode.ordinal() + 1) % modes.length;
            MetalConfig.INSTANCE.scalingMode = modes[next];
            MetalConfig.INSTANCE.save();
            VulkanFrameManager.getInstance().markConfigDirty();
            MetalBridge.updateWindowTitle();
            btn.setMessage(getScalerText());
        }).bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(scalerButton);

        // 2. Quality Preset Button
        presetButton = Button.builder(getPresetText(), btn -> {
            MetalConfig.QualityPreset[] presets = MetalConfig.QualityPreset.values();
            int next = (MetalConfig.INSTANCE.preset.ordinal() + 1) % presets.length;
            MetalConfig.INSTANCE.preset = presets[next];
            MetalConfig.INSTANCE.save();
            VulkanFrameManager.getInstance().markConfigDirty();
            MetalBridge.updateWindowTitle();
            btn.setMessage(getPresetText());
        }).bounds(centerX - buttonWidth / 2, startY + 26, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(presetButton);

        // 3. Frame Generation Button (Metal 4)
        frameGenButton = Button.builder(getFrameGenText(), btn -> {
            MetalConfig.INSTANCE.frameGeneration = !MetalConfig.INSTANCE.frameGeneration;
            MetalConfig.INSTANCE.save();
            VulkanFrameManager.getInstance().markConfigDirty();
            MetalBridge.updateWindowTitle();
            btn.setMessage(getFrameGenText());
        }).bounds(centerX - buttonWidth / 2, startY + 52, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(frameGenButton);

        // 4. Target Refresh Rate Button
        displayRateButton = Button.builder(getDisplayRateText(), btn -> {
            MetalConfig.INSTANCE.targetDisplayFPS = (MetalConfig.INSTANCE.targetDisplayFPS == 120) ? 60 : 120;
            MetalConfig.INSTANCE.save();
            VulkanFrameManager.getInstance().markConfigDirty();
            MetalBridge.updateWindowTitle();
            btn.setMessage(getDisplayRateText());
        }).bounds(centerX - buttonWidth / 2, startY + 72, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(displayRateButton);

        // 5. Zero-Copy UMA Memory Pool Button
        Button umaButton = Button.builder(getUmaText(), btn -> {
            MetalConfig.INSTANCE.enableUnifiedMemoryPool = !MetalConfig.INSTANCE.enableUnifiedMemoryPool;
            MetalConfig.INSTANCE.save();
            VulkanFrameManager.getInstance().markConfigDirty();
            btn.setMessage(getUmaText());
        }).bounds(centerX - buttonWidth / 2, startY + 96, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(umaButton);

        // 6. Metal Renderer Backend Button. The backend is picked once at startup by
        // PreferredGraphicsApiMixin, so changing this only takes effect after a restart.
        metalBackendButton = Button.builder(getMetalBackendText(), btn -> {
            MetalConfig.INSTANCE.preferMetalBackend = !MetalConfig.INSTANCE.preferMetalBackend;
            MetalConfig.INSTANCE.save();
            btn.setMessage(getMetalBackendText());
        }).bounds(centerX - buttonWidth / 2, startY + 120, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(metalBackendButton);

        // 7. Done Button
        Button doneButton = Button.builder(Component.literal("Done"), btn -> {
            onClose();
        }).bounds(centerX - 100, startY + 152, 200, buttonHeight).build();
        this.addRenderableWidget(doneButton);
    }

    private Component getScalerText() {
        return Component.literal("Upscaling: " + MetalConfig.INSTANCE.scalingMode.getDisplayName()
                + (MetalConfig.INSTANCE.scalingMode == MetalConfig.ScalingMode.OFF ? "" : " (not applied)"));
    }

    private Component getPresetText() {
        return Component.literal("Quality Preset: " + MetalConfig.INSTANCE.preset.getDisplayName());
    }

    private Component getFrameGenText() {
        return Component.literal("Frame Generation: " + (MetalConfig.INSTANCE.frameGeneration ? "ON (Metal 4)" : "OFF")
                + (MetalConfig.INSTANCE.frameGeneration ? " (not applied)" : ""));
    }

    private Component getDisplayRateText() {
        return Component.literal("Target Display: " + MetalConfig.INSTANCE.targetDisplayFPS + " Hz (ProMotion)");
    }

    private Component getUmaText() {
        return Component.literal("Apple Silicon UMA Zero-Copy: " + (MetalConfig.INSTANCE.enableUnifiedMemoryPool ? "ON" : "OFF"));
    }

    private Component getMetalBackendText() {
        boolean on = MetalConfig.INSTANCE.preferMetalBackend;
        return Component.literal("Metal Renderer Backend: " + (on ? "ON" : "OFF")
                + (on ? " (restart to disable)" : " (restart to enable)"));
    }

    @Override
    public void onClose() {
        MetalConfig.INSTANCE.save();
        VulkanFrameManager.getInstance().markConfigDirty();
        MetalBridge.updateWindowTitle();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
