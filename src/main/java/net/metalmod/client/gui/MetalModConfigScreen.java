package net.metalmod.client.gui;

import net.metalmod.config.MetalConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * MetalMod settings.
 *
 * <p>The MetalFX scaling/preset/frame-generation controls that used to be here drove the retired
 * MoltenVK-interop frame pipeline and did nothing once it was removed, so they are gone
 * (ROADMAP.md §4). What is left is the Metal backend toggle - which is the setting that actually
 * changes what renders - and the UMA memory options.
 */
public class MetalModConfigScreen extends Screen {

    private final Screen parent;
    private Button metalBackendButton;

    public MetalModConfigScreen(Screen parent) {
        super(Component.literal("MetalMod: Apple Silicon Metal Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 4;
        int buttonWidth = 240;
        int buttonHeight = 20;

        // 1. Apple Silicon UMA memory pool (telemetry on F3).
        Button umaButton = Button.builder(getUmaText(), btn -> {
            MetalConfig.INSTANCE.enableUnifiedMemoryPool = !MetalConfig.INSTANCE.enableUnifiedMemoryPool;
            MetalConfig.INSTANCE.save();
            btn.setMessage(getUmaText());
        }).bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(umaButton);

        // 2. Metal Renderer Backend. The backend is picked once at startup by
        // PreferredGraphicsApiMixin, so changing this only takes effect after a restart.
        metalBackendButton = Button.builder(getMetalBackendText(), btn -> {
            MetalConfig.INSTANCE.preferMetalBackend = !MetalConfig.INSTANCE.preferMetalBackend;
            MetalConfig.INSTANCE.save();
            btn.setMessage(getMetalBackendText());
        }).bounds(centerX - buttonWidth / 2, startY + 24, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(metalBackendButton);

        // 3. Lighting, on its own page: it is the part of the mod that keeps growing, and these are
        // the settings that can change while the game is running (unlike the backend above).
        Button lightingButton = Button.builder(Component.literal("Lighting..."), btn ->
                this.minecraft.setScreenAndShow(new MetalModLightingConfigScreen(this)))
                .bounds(centerX - buttonWidth / 2, startY + 48, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(lightingButton);

        // Capture closes the menu and allows five seconds to resume before recording.
        Button captureButton = Button.builder(Component.literal(
                net.metalmod.debug.PerformanceCapture.isRecording()
                        ? "Stop performance recording" : "Record performance (60 seconds)"), btn -> {
            if (this.minecraft != null) {
                net.metalmod.debug.PerformanceCapture.toggle(this.minecraft);
                if (this.minecraft.level != null) this.minecraft.setScreenAndShow(null);
            }
        }).bounds(centerX - buttonWidth / 2, startY + 72, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(captureButton);

        // Done.
        Button doneButton = Button.builder(Component.literal("Done"), btn -> {
            onClose();
        }).bounds(centerX - 100, startY + 104, 200, buttonHeight).build();
        this.addRenderableWidget(doneButton);
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
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
