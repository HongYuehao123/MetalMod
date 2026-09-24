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

        // 3. MetalFX, first among the live settings and on its own page, because it is the one whose
        // effect is visible in the frame itself: render resolution, the upscaler, and the live status
        // that says which path actually ran. The two pages below it change what the world is lit by
        // and how it is measured; this one changes how many pixels it is drawn with.
        Button upscalingButton = Button.builder(
                Component.literal("MetalFX Upscaling: " + upscalingSummary() + "..."), btn ->
                        this.minecraft.setScreenAndShow(new MetalModUpscalingConfigScreen(this)))
                .bounds(centerX - buttonWidth / 2, startY + 48, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(upscalingButton);

        // 4. Lighting, also live: the switches that decide what the world is lit by.
        Button lightingButton = Button.builder(Component.literal("Lighting..."), btn ->
                this.minecraft.setScreenAndShow(new MetalModLightingConfigScreen(this)))
                .bounds(centerX - buttonWidth / 2, startY + 72, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(lightingButton);

        // Capture closes the menu and allows five seconds to resume before recording.
        Button captureButton = Button.builder(Component.literal(
                net.metalmod.debug.PerformanceCapture.isRecording()
                        ? "Stop performance recording" : "Record performance (60 seconds)"), btn -> {
            if (this.minecraft != null) {
                net.metalmod.debug.PerformanceCapture.toggle(this.minecraft);
                if (this.minecraft.level != null) this.minecraft.setScreenAndShow(null);
            }
        }).bounds(centerX - buttonWidth / 2, startY + 96, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(captureButton);

        // Done.
        Button doneButton = Button.builder(Component.literal("Done"), btn -> {
            onClose();
        }).bounds(centerX - 100, startY + 128, 200, buttonHeight).build();
        this.addRenderableWidget(doneButton);
    }

    /**
     * One short caption, so the main screen says what the sub-page is set to without opening it.
     *
     * <p>It also says whether the scaler is live or fell back, because the caption is the only thing
     * visible from here and "75% spatial" that never ran would otherwise read as working.
     */
    private static String upscalingSummary() {
        if (!net.metalmod.metalfx.RenderScaleSettings.active()) {
            return "off";
        }
        if (!net.metalmod.metalfx.WorldRenderTarget.scalerAvailable()) {
            return "unavailable";
        }
        long fx = net.metalmod.metalfx.WorldRenderTarget.scaledFrameCount();
        return net.metalmod.metalfx.RenderScaleSettings.percentLabel() + (fx > 0 ? " MetalFX" : " pending");
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
