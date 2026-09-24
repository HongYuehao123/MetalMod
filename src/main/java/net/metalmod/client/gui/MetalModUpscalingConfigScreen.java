package net.metalmod.client.gui;

import net.metalmod.backend.MetalBackend;
import net.metalmod.backend.MetalDevice;
import net.metalmod.metalfx.MetalFx;
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
 * <h2>Edits are staged, and applied when the page is left</h2>
 *
 * <p>Stepping the scale or cycling the effect changes what this page <em>shows it will do</em>, not
 * what the renderer is doing. The draft is applied on the way out - by {@code Done} or by {@code Esc},
 * both of which arrive at {@link #onClose()} - and not before.
 *
 * <p>That is a deliberate reversal of the earlier behaviour, which applied each click immediately.
 * A render-scale change rebuilds the level's render target, replaces the scaler and asks the engine
 * for a resource reload; doing that per click meant that walking the scale from 85% to 50% rebuilt
 * the frame four times and reloaded resources twice, so the page stuttered while the user was still
 * deciding, and the live status underneath was describing a configuration nobody had settled on. One
 * apply per visit is also what the user asked for: the setting changes when they leave the page, not
 * while they are still on it.
 *
 * <p>A scaler the machine refuses is not an error: the frame renders at native resolution instead and
 * this page says so, because "the switch is on but nothing happened" is otherwise unexplainable.
 */
public class MetalModUpscalingConfigScreen extends Screen {

    private static final int ROW = 22;
    private static final int LABEL_WIDTH = 150;

    private final Screen parent;

    /**
     * What the page is proposing, until the page is left.
     *
     * <p>Initialised once, at construction, rather than in {@code init()}: the engine re-initialises a
     * screen on every window resize, and re-reading the committed settings there would silently throw
     * away a draft the user was still editing.
     */
    private double draftScale = RenderScaleSettings.renderScale();
    private String draftUpscaler = RenderScaleSettings.upscaler();
    private boolean draftNotice = RenderScaleSettings.showNotice();

    private Button scaleDownButton;
    private Button scaleUpButton;
    private Button upscalerCycleButton;
    private Button noticeButton;
    private Button doneButton;
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
                    this.draftNotice = !this.draftNotice;
                    refresh();
                }).bounds(buttonX, y, 142, 20).build());
        y += ROW + 6;

        // 4. Live status: the answers, not the settings.
        this.statusWidget = this.addRenderableWidget(new MultiLineTextWidget(left, y,
                Component.literal(""), this.font).setMaxWidth(360).setMaxRows(8));
        y += 104;

        this.doneButton = addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> onClose()).bounds(centreX - 100, this.height - 30, 200, 20).build());
        refresh();
    }

    private void label(int x, int y, String text) {
        this.addRenderableWidget(new StringWidget(x, y, LABEL_WIDTH, 20,
                Component.literal(text), this.font));
    }

    // ---------------------------------------------------------------------------------------------
    // The draft
    // ---------------------------------------------------------------------------------------------

    /**
     * Step to the next preset sharper ({@code +1}) or faster ({@code -1}).
     *
     * <p>A value that is not on a preset - a launch flag, or a hand-edited file - snaps onto the list
     * from the side it is being stepped towards, so the first click always lands on something the rest
     * of the page can describe.
     */
    private void stepScale(int direction) {
        double current = this.draftScale;
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
        this.draftScale = scale;
        refresh();
    }

    /** Off, Spatial, Temporal, and around again. */
    private void cycleUpscaler() {
        this.draftUpscaler = switch (this.draftUpscaler) {
            case MetalFx.OFF -> MetalFx.SPATIAL;
            case MetalFx.SPATIAL -> MetalFx.TEMPORAL;
            default -> MetalFx.OFF;
        };
        refresh();
    }

    /** Whether the page is proposing a different frame than the one being rendered. */
    private boolean renderDraftDiffers() {
        return Math.abs(this.draftScale - RenderScaleSettings.renderScale()) > 1e-9
                || !this.draftUpscaler.equals(RenderScaleSettings.upscaler());
    }

    /** Whether the page is proposing anything at all, including the notice. */
    private boolean draftDiffers() {
        return renderDraftDiffers() || this.draftNotice != RenderScaleSettings.showNotice();
    }

    /**
     * Hand the draft to the settings, in the order the rest of the frame reads them.
     *
     * <p>The notice is applied first so that the announcement below honours the value the user just
     * chose rather than the one being replaced - turning the notice off and leaving should not produce
     * one last notice.
     *
     * @return whether the frame changed, which is what decides if the change is announced
     */
    private boolean applyDraft() {
        boolean renderChanged = false;
        if (this.draftNotice != RenderScaleSettings.showNotice()) {
            RenderScaleSettings.chooseNotice(this.draftNotice);
        }
        if (Math.abs(this.draftScale - RenderScaleSettings.renderScale()) > 1e-9) {
            RenderScaleSettings.chooseRenderScale(this.draftScale);
            renderChanged = true;
        }
        if (!this.draftUpscaler.equals(RenderScaleSettings.upscaler())) {
            RenderScaleSettings.chooseUpscaler(this.draftUpscaler);
            renderChanged = true;
        }
        return renderChanged;
    }

    // ---------------------------------------------------------------------------------------------
    // Live status
    // ---------------------------------------------------------------------------------------------

    /** Refresh the captions and the live status. Called on every change and on every frame. */
    private void refresh() {
        if (this.scaleDownButton != null) {
            this.scaleDownButton.active = this.draftScale
                    > RenderScaleSettings.PRESETS[RenderScaleSettings.PRESETS.length - 1] + 1e-6;
        }
        if (this.scaleUpButton != null) {
            this.scaleUpButton.active = this.draftScale < 1.0 - 1e-6;
        }
        if (this.upscalerCycleButton != null) {
            this.upscalerCycleButton.setMessage(Component.literal(switch (this.draftUpscaler) {
                case MetalFx.TEMPORAL -> "MetalFX temporal";
                case MetalFx.OFF -> "Off (native)";
                default -> "MetalFX spatial";
            }));
            // Always clickable, including at 100%: the scale is what turns scaling on or off, and a
            // disabled row here would hide the only control that says which scaler is chosen.
            this.upscalerCycleButton.active = true;
        }
        if (this.noticeButton != null) {
            this.noticeButton.setMessage(Component.literal(this.draftNotice ? "ON" : "OFF"));
        }
        if (this.doneButton != null) {
            // Says what leaving does, because leaving is what applies.
            this.doneButton.setMessage(Component.literal(
                    draftDiffers() ? "Apply and go back" : "Done"));
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
     * the failure this page exists to make impossible - and now that the page holds a draft, the
     * distinction is the point: this section describes the frame you are looking at, and the pending
     * line underneath describes the one you will get when you leave.
     */
    private String status() {
        StringBuilder text = new StringBuilder();
        boolean metal = MetalDevice.active() != null;
        text.append("Renderer: ").append(metal ? "Metal" : "not Metal");

        boolean pending = renderDraftDiffers();
        if (pending) {
            text.append("   Pending: ").append(describeDraft())
                    .append(" - applied when you leave this page, by Done or Esc.");
        } else if (draftDiffers()) {
            text.append("   Pending: the change notice - applied when you leave this page.");
        }

        if (!RenderScaleSettings.active()) {
            text.append("   Scale: 100% - the world renders at native resolution and nothing is"
                    + " scaled.");
            if (!pending) {
                text.append("   Pick a scale below 100% to render the world smaller and upscale it;"
                        + " the interface stays at native resolution either way.");
            }
            return text.toString();
        }

        int[] size = RenderScaleSettings.effectiveSize();
        text.append("   Running now: ").append(RenderScaleSettings.percentLabel());
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

        // Temporal's own answers, from the effect that is running rather than from the draft.
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

    /** The draft in the same words the running status uses, so the two lines can be compared. */
    private String describeDraft() {
        String scale = this.draftScale >= 1.0 ? "100% (off)"
                : Math.round(this.draftScale * 100) + "%";
        String effect = switch (this.draftUpscaler) {
            case MetalFx.TEMPORAL -> "MetalFX temporal";
            case MetalFx.OFF -> "no upscaler";
            default -> "MetalFX spatial";
        };
        return scale + ", " + effect;
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

    /**
     * Leave the page, applying the draft.
     *
     * <p>Both ways out arrive here - the button and {@code Esc} - so "leaving the page" is one event
     * rather than two paths that could disagree.
     */
    @Override
    public void onClose() {
        boolean renderChanged = applyDraft();
        if (renderChanged) {
            // Announced here rather than on every click: a notice per click described a frame that was
            // never rendered, and the honest moment to say "from the next frame" is now.
            UpscalingNotifier.announceCurrent();
        }
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
