package net.metalmod.client.gui;

import net.metalmod.backend.MetalBackend;
import net.metalmod.backend.MetalDevice;
import net.metalmod.lighting.LightingSettings;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;


/**
 * MetalMod's lighting settings.
 *
 * <p>A page of its own rather than more rows on the main screen, because lighting is the part of this
 * mod that will keep growing: source types, coverage, ownership and the cluster budget all belong
 * here, and the main screen should stay the short list of switches that change what the backend is.
 *
 * <h2>What applies when</h2>
 *
 * <p>Unlike the backend choice - which is fixed once the engine has picked a backend - these can be
 * changed while the game is running. A lighting variant is selected when a pipeline is <em>compiled</em>,
 * so a toggle invalidates the compiled pipelines and they rebuild on their next draw, at the end of
 * the frame in which the toggle was flipped. A setting given on the command line wins over the saved
 * value and locks its row: saying so on the screen matters, because otherwise a launch flag would look
 * like the toggle silently failing.
 */
public class MetalModLightingConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int LABEL_WIDTH = 220;
    private static final int TOGGLE_WIDTH = 90;
    private static final int NOTE_WIDTH = 340;

    private final Screen parent;
    private Button dynamicButton;
    private Button clusteredButton;
    private Button proofButton;

    public MetalModLightingConfigScreen(Screen parent) {
        super(Component.literal("MetalMod: Lighting"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        net.metalmod.Diagnostics.hook("MetalModLightingConfigScreen.init");
        int centreX = this.width / 2;
        int left = centreX - (LABEL_WIDTH + 8 + TOGGLE_WIDTH) / 2;
        int toggleX = left + LABEL_WIDTH + 8;
        int y = Math.max(36, this.height / 6);

        this.dynamicButton = toggleRow(left, y, toggleX, "Dynamic lighting",
                () -> LightingSettings.chooseDynamicLights(!LightingSettings.dynamicLights()));
        y += ROW_HEIGHT;

        this.clusteredButton = toggleRow(left, y, toggleX, "Clustered light lists",
                () -> LightingSettings.chooseClusteredLights(!LightingSettings.clusteredLights()));
        y += ROW_HEIGHT;

        this.proofButton = toggleRow(left, y, toggleX, "Point-light proof (diagnostic)",
                () -> LightingSettings.choosePointLightProof(!LightingSettings.pointLightProof()));
        y += ROW_HEIGHT + 10;

        this.addRenderableWidget(new MultiLineTextWidget(left, y, Component.literal(note()), this.font)
                .setMaxWidth(NOTE_WIDTH).setMaxRows(8));

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(centreX - 100, this.height - 32, 200, 20).build());
        refresh();
    }

    /**
     * One labelled on/off row.
     *
     * <p>Rows are never disabled. A launch flag used to lock them, which is exactly what made the
     * toggle look broken: the flag was resolved on every read, so the switch could not have changed
     * anything even if it had been clickable. A choice made here now wins over the flag for the rest
     * of the session, so the row stays live and the caption simply reports the truth.
     */
    private Button toggleRow(int left, int y, int toggleX, String label, Runnable onPress) {
        this.addRenderableWidget(new StringWidget(left, y, LABEL_WIDTH, 20,
                Component.literal(label), this.font));
        Button button = Button.builder(Component.empty(), pressed -> {
            onPress.run();
            MetalDevice.requestLightingRebuild();
            refresh();
        }).bounds(toggleX, y, TOGGLE_WIDTH, 20).build();
        return this.addRenderableWidget(button);
    }

    /** Recompute every caption from the value the running game will actually use. */
    private void refresh() {
        boolean dynamic = LightingSettings.dynamicLights();
        if (this.dynamicButton != null) {
            this.dynamicButton.setMessage(caption(dynamic));
        }
        if (this.clusteredButton != null) {
            // Clustering cannot be on while the set it clusters is off, so the row reads as unavailable.
            this.clusteredButton.active = dynamic;
            this.clusteredButton.setMessage(dynamic
                    ? caption(LightingSettings.clusteredLights())
                    : Component.literal("-"));
        }
        if (this.proofButton != null) {
            this.proofButton.setMessage(caption(LightingSettings.pointLightProof()));
        }
    }

    private static Component caption(boolean on) {
        return Component.literal(on ? "ON" : "OFF");
    }

    private String note() {
        StringBuilder text = new StringBuilder();
        text.append(MetalDevice.active() != null
                ? "Changes apply to the running game immediately."
                : "No Metal device is active; these apply when the Metal backend next runs.");
        if (!MetalBackend.isEnabled()) {
            text.append(" The Metal backend is currently disabled, so lighting has no effect until it "
                    + "is enabled and the game is restarted.");
        }
        if (LightingSettings.overridden(LightingSettings.PROPERTY_DYNAMIC_LIGHTS)
                || LightingSettings.overridden(LightingSettings.PROPERTY_CLUSTERED_LIGHTS)
                || LightingSettings.overridden(LightingSettings.PROPERTY_POINT_LIGHT_PROOF)) {
            text.append(" This session was started with a lighting launch flag. Changing a setting here"
                    + " overrides it from now on and is saved for the next launch.");
        }
        text.append(" Dynamic lighting lights terrain and particles from held or dropped "
                + "light-emitting items, up to 32 unshadowed sources. Placed blocks are "
                + "not added on top of vanilla's baked lightmap, and entities are not covered yet. "
                + "Clustered lists evaluate only the lights near each 16-block cell, which is what "
                + "keeps a large set affordable.");
        return text.toString();
    }

    @Override
    public void onClose() {
        // Every toggle already saved its own value; this is just the way back.
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
