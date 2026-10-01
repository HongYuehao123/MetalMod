package net.metalmod.client.gui;

import net.metalmod.upscaling.MetalFxCoordinator;
import net.metalmod.upscaling.UpscalingSettings;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Two player controls: the preference and the remembered per-dimension scene reduction. */
public final class MetalModSuperResolutionScreen extends Screen {
    private final Screen parent;
    private Button toggle, strength;
    private MultiLineTextWidget status;
    public MetalModSuperResolutionScreen(Screen parent) {
        super(Component.literal("MetalMod: Super Resolution")); this.parent = parent;
    }
    @Override protected void init() {
        int x = this.width / 2 - 150, y = Math.max(28, this.height / 6);
        toggle = addRenderableWidget(Button.builder(Component.empty(), b -> {
            UpscalingSettings.chooseEnabled(!UpscalingSettings.current().enabled()); refresh();
        }).bounds(x, y, 300, 20).build());
        strength = addRenderableWidget(Button.builder(Component.empty(), b -> {
            UpscalingSettings.chooseStrength(UpscalingSettings.nextStrength(UpscalingSettings.current().strength())); refresh();
        }).bounds(x, y + 24, 300, 20).build());
        strength.setTooltip(Tooltip.create(Component.literal("Reduction in each world-render dimension. Higher strength renders fewer scene pixels. HUD and input stay at native resolution. Editable while Off.")));
        status = addRenderableWidget(new MultiLineTextWidget(x, y + 52, Component.empty(), this.font)
                .setMaxWidth(300).setMaxRows(4));
        addRenderableWidget(new MultiLineTextWidget(x, y + 105, Component.literal(
                "Higher strength trades world detail for fewer scene pixels. HUD stays native. Changes apply next frame. MetalFX falls back to native if unavailable."), this.font)
                .setMaxWidth(300).setMaxRows(6));
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
        var live = MetalFxCoordinator.stats();
        String effective = live.effective().equals("Native") ? "Native" : "Super Resolution";
        status.setMessage(Component.literal("Requested render scale: " + s.scale() + "%\n"
                + effective + ": " + live.sceneWidth() + "x" + live.sceneHeight() + " -> "
                + live.outputWidth() + "x" + live.outputHeight() + "\nScene pixels: "
                + String.format(java.util.Locale.ROOT, "%.1f%%", live.pixelPercent())
                + (live.reason().isEmpty() ? "" : "\n" + live.reason())));
    }
    @Override public void onClose() { if (this.minecraft != null) this.minecraft.setScreenAndShow(this.parent); }
}
