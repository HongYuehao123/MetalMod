package net.metalmod.client.gui;

import net.metalmod.upscaling.MetalFxCoordinator;
import net.metalmod.upscaling.UpscalingSettings;
import net.metalmod.upscaling.TemporalJitterProof;
import net.metalmod.upscaling.FrameGenerationSettings;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** MetalFX reconstruction and experimental frame-generation controls. */
public final class MetalModMetalFxScreen extends Screen {
    private final Screen parent;
    private Button toggle, strength, temporalTest, frameGeneration;
    private MultiLineTextWidget status, frameGenerationStatus;
    public MetalModMetalFxScreen(Screen parent) {
        super(Component.literal("MetalMod: MetalFX")); this.parent = parent;
    }
    @Override protected void init() {
        int x = this.width / 2 - 150, y = Math.max(28, Math.min(this.height / 6, this.height - 212));
        toggle = addRenderableWidget(Button.builder(Component.empty(), b -> {
            UpscalingSettings.chooseEnabled(!UpscalingSettings.current().enabled()); refresh();
        }).bounds(x, y, 300, 20).build());
        strength = addRenderableWidget(Button.builder(Component.empty(), b -> {
            UpscalingSettings.chooseStrength(UpscalingSettings.nextStrength(UpscalingSettings.current().strength())); refresh();
        }).bounds(x, y + 24, 300, 20).build());
        strength.setTooltip(Tooltip.create(Component.literal("Reduction in each world-render dimension. Higher strength renders fewer scene pixels. HUD and input stay at native resolution. Editable while Off.")));
        temporalTest = addRenderableWidget(Button.builder(Component.empty(), b -> {
            TemporalJitterProof.chooseEnabled(!TemporalJitterProof.requested()); refresh();
        }).bounds(x, y + 48, 300, 20).build());
        temporalTest.setTooltip(Tooltip.create(Component.literal("Reconstructs the world with MetalFX temporal history. Requires Super Resolution On at strength 25/33/50. Hand and HUD stay native.")));
        frameGeneration = addRenderableWidget(Button.builder(Component.empty(), b -> {
            FrameGenerationSettings.chooseEnabled(!FrameGenerationSettings.current().enabled()); refresh();
        }).bounds(x, y + 72, 300, 20).build());
        frameGeneration.setTooltip(Tooltip.create(Component.literal(
                "Experimental MetalFX interpolation for sustained slow rendering. Requires Metal, VSync and a world. Preserves full-rate real rendering when possible. Entities, particles, beams, hand and HUD retain native pixels. Unreliable interpolation returns to real frames.")));
        frameGenerationStatus = addRenderableWidget(new MultiLineTextWidget(x, y + 98, Component.empty(), this.font)
                .setMaxWidth(300).setMaxRows(2));
        status = addRenderableWidget(new MultiLineTextWidget(x, y + 128, Component.empty(), this.font)
                .setMaxWidth(300).setMaxRows(5));
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
        refresh();
    }
    @Override public void tick() { refresh(); }
    private void refresh() {
        var s = UpscalingSettings.current();
        if (toggle == null) return;
        toggle.setMessage(Component.literal("Super Resolution: " + (s.enabled() ? "ON" : "OFF")));
        strength.setMessage(Component.literal("Strength: " + s.strength() + "% (render " + (100-s.strength()) + "%)"));
        temporalTest.setMessage(Component.literal("Temporal Upscaling: " + (TemporalJitterProof.requested() ? "ON" : "OFF")));
        boolean fg = FrameGenerationSettings.current().enabled();
        boolean available = FrameGenerationSettings.gameplayAvailable();
        frameGeneration.active = available;
        frameGeneration.setMessage(Component.literal("Frame Generation: " + (fg ? "ON" : "OFF")
                + (available ? "" : " (Unavailable)")));
        String fgStatus = available ? (fg ? FrameGenerationSettings.status().reason() : "Off")
                : FrameGenerationSettings.unavailableReason();
        frameGenerationStatus.setMessage(Component.literal(fgStatus + "\nExperimental; requires VSync and a world."));
        var live = MetalFxCoordinator.stats();
        String effective = live.effective();
        status.setMessage(Component.literal("Requested render scale: " + s.scale() + "%\n"
                + effective + ": " + live.sceneWidth() + "x" + live.sceneHeight() + " -> "
                + live.outputWidth() + "x" + live.outputHeight() + "\nScene pixels: "
                + String.format(java.util.Locale.ROOT, "%.1f%%", live.pixelPercent())
                + "\nTemporal: " + (TemporalJitterProof.stats().applied() ? "active (world history)"
                        : TemporalJitterProof.requested() ? fg ? "spatial input during frame generation" : "waiting for reduced world" : "Off")
                + (live.reason().isEmpty() ? "" : "\n" + live.reason())));
    }
    @Override public void onClose() { if (this.minecraft != null) this.minecraft.setScreenAndShow(this.parent); }
}
