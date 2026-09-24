package net.metalmod.client.gui;

import net.metalmod.backend.MetalBackend;
import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalNative;
import net.metalmod.metalfx.MetalFx;
import net.metalmod.metalfx.MetalFxScaler;
import net.metalmod.metalfx.RenderScaleSettings;
import net.metalmod.metalfx.SceneMotion;
import net.metalmod.metalfx.UpscalingNotifier;
import net.metalmod.metalfx.WorldRenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * MetalFX: the render-resolution and upscaling controls (ROADMAP Phase 7A).
 *
 * <h2>Why this page looks like a control panel and not a list of switches</h2>
 *
 * <p>Every other MetalMod setting either cannot be checked without instrumentation (the lighting
 * switches need F3) or is a one-off (the backend toggle). Render scale is different: its whole
 * purpose is a visible and measurable change to the frame, and the questions a user actually has are
 * "what size is the world rendering at", "did MetalFX take it", and "is that speed-up real". So the
 * page answers all three directly - the live sizes come from the target the frame is using, the
 * effect's own report says which path ran, and a frame counter says whether it is still running -
 * instead of leaving them to be inferred from a config value.
 *
 * <h2>What applies when</h2>
 *
 * <p>Both settings apply at the next frame boundary, without a restart. A render-scale change rebuilds
 * the level's render target, and the rebuild is deferred to the frame boundary rather than done here
 * because the pass that was rendering into the old target already exists by the time this screen runs.
 *
 * <p>A scaler the machine refuses is not an error: the frame renders at native resolution instead and
 * this page says so, because "the switch is on but nothing happened" is otherwise unexplainable.
 */
public class MetalModUpscalingConfigScreen extends Screen {

    private static final int ROW = 22;
    private static final int LABEL_WIDTH = 150;
    private static final int BUTTON_WIDTH = 110;

    private final Screen parent;
    private Button scaleDownButton;
    private Button scaleUpButton;
    private Button upscalerCycleButton;
    private Button noticeButton;
    private MultiLineTextWidget statusWidget;

    /** Rebuilt every frame from the live state; kept so the widget is only touched when it changes. */
    private String lastStatus = "";

    public MetalModUpscalingConfigScreen(Screen parent) {
        super(Component.literal("MetalMod: MetalFX Upscaling"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        net.metalmod.Diagnostics.hook("MetalModUpscalingConfigScreen.init");
        int centreX = this.width / 2;
        int left = centreX - 260;
        int buttonX = left + LABEL_WIDTH + 8;
        int y = 32;

        // 1. Render scale. Two buttons rather than a cycling one, because the interesting action is
        //    "one step sharper" or "one step faster" and a value you have to cycle through to reach is
        //    a value you cannot aim at.
        label(left, y, "Render scale");
        this.scaleDownButton = addRenderableWidget(Button.builder(Component.literal("-"),
                b -> stepScale(-1)).bounds(buttonX, y, 30, 20).build());
        this.scaleUpButton = addRenderableWidget(Button.builder(Component.literal("+"),
                b -> stepScale(1)).bounds(buttonX + 34, y, 30, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Native"),
                b -> setScale(1.0)).bounds(buttonX + 68, y, 74, 20).build());
        y += ROW;

        // 2. Upscaler: MetalFX spatial, MetalFX temporal, or no scaling at all. Temporal is a real
        //    choice now that a motion source exists, and it is offered rather than hidden: it costs
        //    more than spatial and it is the only mode that resolves detail across frames, so which
        //    one is right is a judgement only the person looking at the screen can make.
        label(left, y, "Upscaler");
        this.upscalerCycleButton = addRenderableWidget(Button.builder(Component.literal(""),
                b -> cycleUpscaler()).bounds(buttonX, y, 142, 20).build());
        y += ROW;

        // 3. The in-world notice. On by default, and the reason this page can be checked without F3.
        label(left, y, "Show change notice");
        this.noticeButton = addRenderableWidget(Button.builder(Component.literal(""),
                b -> {
                    RenderScaleSettings.chooseNotice(!RenderScaleSettings.showNotice());
                    refresh();
                }).bounds(buttonX, y, 142, 20).build());
        y += ROW + 6;

        // 5. Live status: the answers, not the settings.
        this.statusWidget = this.addRenderableWidget(new MultiLineTextWidget(left, y,
                Component.literal(""), this.font).setMaxWidth(360).setMaxRows(7));
        y += 96;

        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(centreX - 100, this.height - 30, 200, 20).build());
        refresh();
    }

    private void label(int x, int y, String text) {
        this.addRenderableWidget(new StringWidget(x, y, LABEL_WIDTH, 20,
                Component.literal(text), this.font));
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    /**
     * Step to the next preset sharper ({@code +1}) or faster ({@code -1}).
     *
     * <p>A value that is not on a preset - a launch flag, or a hand-edited file - snaps onto the list
     * from the side it is being stepped towards, so the first click always lands on something the rest
     * of the page can describe.
     */
    private void stepScale(int direction) {
        double current = RenderScaleSettings.renderScale();
        double[] presets = RenderScaleSettings.PRESETS;
        double chosen = direction > 0 ? 1.0 : presets[presets.length - 1];
        for (int i = 0; i < presets.length; i++) {
            if (Math.abs(presets[i] - current) < 1e-6) {
                int next = Math.max(0, Math.min(presets.length - 1, i - direction));
                chosen = presets[next];
                break;
            }
            if (direction > 0 && presets[i] > current) {
                chosen = presets[i];
            } else if (direction < 0 && presets[i] < current) {
                chosen = presets[i];
                break;
            }
        }
        setScale(chosen);
    }

    private void setScale(double scale) {
        RenderScaleSettings.chooseRenderScale(scale);
        announce();
        refresh();
    }

    /** Off, Spatial, Temporal, and around again. */
    private void cycleUpscaler() {
        String current = RenderScaleSettings.upscaler();
        String next;
        if (MetalFx.OFF.equals(current)) {
            next = MetalFx.SPATIAL;
        } else if (MetalFx.SPATIAL.equals(current)) {
            next = MetalFx.TEMPORAL;
        } else {
            next = MetalFx.OFF;
        }
        RenderScaleSettings.chooseUpscaler(next);
        announce();
        refresh();
    }

    /**
     * Say what the change will do. The frame hooks apply it at the next boundary; this screen only
     * announces it, and the notice says "from the next frame" rather than claiming it already landed.
     */
    private void announce() {
        UpscalingNotifier.announceCurrent();
    }

    // ---------------------------------------------------------------------------------------------
    // Live status
    // ---------------------------------------------------------------------------------------------

    /** Refresh the captions and the live status. Called on every change and on every frame. */
    private void refresh() {
        if (this.scaleDownButton != null) {
            this.scaleDownButton.active = RenderScaleSettings.renderScale()
                    > RenderScaleSettings.PRESETS[RenderScaleSettings.PRESETS.length - 1] + 1e-6;
        }
        if (this.scaleUpButton != null) {
            this.scaleUpButton.active = RenderScaleSettings.renderScale() < 1.0 - 1e-6;
        }
        if (this.upscalerCycleButton != null) {
            String mode = RenderScaleSettings.upscaler();
            this.upscalerCycleButton.setMessage(Component.literal(switch (mode) {
                case MetalFx.TEMPORAL -> "MetalFX temporal";
                case MetalFx.OFF -> "Off (native)";
                default -> "MetalFX spatial";
            }));
            // Always clickable, including at 100%: the scale is what turns scaling on or off, and a
            // disabled row here would hide the only control that says which scaler is chosen.
            this.upscalerCycleButton.active = true;
        }
        if (this.noticeButton != null) {
            this.noticeButton.setMessage(Component.literal(
                    RenderScaleSettings.showNotice() ? "ON" : "OFF"));
        }
        if (this.statusWidget != null) {
            String status = status();
            if (!status.equals(this.lastStatus)) {
                this.lastStatus = status;
                this.statusWidget.setMessage(Component.literal(status));
            }
        }
    }

    /**
     * What is actually happening, in the order a user checks it.
     *
     * <p>Every number here comes from the running frame rather than from a setting: the sizes from the
     * target the renderer is using, the path and frame counts from the effect that ran. A page that
     * restated the settings would look identical whether the feature worked or not, which is exactly
     * the failure this page exists to make impossible.
     */
    private String status() {
        StringBuilder text = new StringBuilder();
        boolean metal = MetalDevice.active() != null;
        String backend = metal ? "Metal" : "not Metal";
        text.append("Renderer: ").append(backend);

        if (!RenderScaleSettings.active()) {
            text.append("   Scale: 100% - the world renders at native resolution and nothing is"
                    + " scaled.");
            text.append("   Pick a scale below 100% to render the world smaller and upscale it;"
                    + " the interface stays at native resolution either way.");
            return text.toString();
        }

        int[] size = RenderScaleSettings.effectiveSize();
        text.append("   Scale: ").append(RenderScaleSettings.percentLabel());
        if (!WorldRenderTarget.scalingThisFrame()) {
            text.append("   The scaled target is being rebuilt for the current window size; this"
                    + " frame renders at native resolution and scaling resumes at the next frame.");
        } else if (size == null) {
            text.append("   World target: not created yet - it appears when a level is next drawn.");
        } else {
            Minecraft minecraft = Minecraft.getInstance();
            int nativeW = minecraft != null && minecraft.getWindow() != null
                    ? minecraft.getWindow().getWidth() : 0;
            int nativeH = minecraft != null && minecraft.getWindow() != null
                    ? minecraft.getWindow().getHeight() : 0;
            text.append("   World: ").append(size[0]).append("x").append(size[1]);
            if (nativeW > 0) {
                text.append(" -> native ").append(nativeW).append("x").append(nativeH);
            }
        }

        long fx = WorldRenderTarget.scaledFrameCount();
        long failed = WorldRenderTarget.failedFrameCount();
        text.append("   Effect: MetalFX ").append(WorldRenderTarget.effectName());
        text.append("   Upscaled frames: ").append(fx);
        if (failed > 0) {
            text.append("   FAILED: ").append(failed).append(" - ")
                    .append(WorldRenderTarget.lastUpscaleError());
        }
        if (fx == 0 && failed == 0) {
            text.append("   (no frame has been upscaled yet)");
        }

        // Temporal's own answers. The motion line is the one that says which half of the motion field
        // is being published: camera reprojection covers a moving camera and static geometry, while
        // anything that moves on its own is Phase 8C's and still reprojects as if it were static.
        if (WorldRenderTarget.temporalActive()) {
            text.append("   Temporal: ").append(SceneMotion.summary());
        } else if (WorldRenderTarget.temporalRequested()
                && !WorldRenderTarget.temporalFallbackReason().isEmpty()) {
            text.append("   Temporal: unavailable - ")
                    .append(WorldRenderTarget.temporalFallbackReason())
                    .append(". MetalFX spatial is running instead.");
        } else if (MetalFx.TEMPORAL.equals(RenderScaleSettings.upscaler())) {
            text.append("   Temporal: selected; it starts when a scaled frame is next drawn.");
        }

        if (!WorldRenderTarget.scalerAvailable()) {
            text.append("   MetalFX: unavailable here - ")
                    .append(WorldRenderTarget.unavailableReason())
                    .append(". The world is rendering at native resolution.");
        }
        if (!MetalBackend.isEnabled()) {
            text.append("   The Metal backend is disabled, so none of this runs until it is enabled"
                    + " and the game restarted.");
        }
        return text.toString();
    }

    /**
     * Refresh the live status as the screen is extracted.
     *
     * <p>26.2 builds a screen from a render state rather than drawing it, and this is the hook that
     * runs once per frame while the page is open. Without it the counters would be a snapshot from the
     * moment the page opened, which is exactly the moment they are least informative - "is it still
     * running" has to be answerable from the one page that asks it.
     */
    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                   int mouseX, int mouseY, float partialTick) {
        refresh();
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        // Every change already saved itself; this is the way back, and the moment to confirm what the
        // world is about to do.
        UpscalingNotifier.announceCurrent();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
