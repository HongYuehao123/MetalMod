package net.metalmod.client.gui;

import net.metalmod.upscaling.MetalFxCoordinator;
import net.metalmod.upscaling.UpscalingSettings;
import net.metalmod.upscaling.TemporalJitterProof;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Reconstruction preference, scene strength and temporal history controls. */
public final class MetalModSuperResolutionScreen extends Screen {
    private final Screen parent;
    private Button toggle, strength, temporalTest;
    private MultiLineTextWidget status;
    public MetalModSuperResolutionScreen(Screen parent) {
        super(Component.literal("MetalMod: Super Resolution")); this.parent = parent;
    }
    @Override protected void init() {
        int x = this.width / 2 - 150, y = Math.max(28, Math.min(this.height / 6, this.height - 218));
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
        status = addRenderableWidget(new MultiLineTextWidget(x, y + 80, Component.empty(), this.font)
                .setMaxWidth(300).setMaxRows(5));
        addRenderableWidget(new MultiLineTextWidget(x, y + 140, Component.literal(
                "Temporal uses motion and frame history. Use Super Resolution On at strength 25/33/50. Hand and HUD stay native."), this.font)
                .setMaxWidth(300).setMaxRows(4));
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
        var live = MetalFxCoordinator.stats();
        String effective = live.effective();
        status.setMessage(Component.literal("Requested render scale: " + s.scale() + "%\n"
                + effective + ": " + live.sceneWidth() + "x" + live.sceneHeight() + " -> "
                + live.outputWidth() + "x" + live.outputHeight() + "\nScene pixels: "
                + String.format(java.util.Locale.ROOT, "%.1f%%", live.pixelPercent())
                + "\nTemporal: " + (TemporalJitterProof.stats().applied() ? "active (world history)"
                        : TemporalJitterProof.requested() ? "waiting for reduced world" : "Off")
                + (live.reason().isEmpty() ? "" : "\n" + live.reason())));
    }
    @Override public void onClose() { if (this.minecraft != null) this.minecraft.setScreenAndShow(this.parent); }
}
