package net.metalmod.mixin;

import net.metalmod.Diagnostics;
import net.metalmod.client.gui.MetalModConfigScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a MetalMod entry to the vanilla Options screen.
 *
 * <p>The options screen lays its buttons out with a {@code HeaderAndFooterLayout} that is built in the
 * constructor and re-populated by {@code init()}, and it registers each button with the screen through
 * a separate visit step. Rather than depend on that internal bookkeeping, this adds one button the
 * ordinary way - {@code addRenderableWidget} - and positions it in the free space between the button
 * grid and the footer, repositioning it whenever vanilla repositions its own.
 *
 * <p>The mixin extends {@link Screen} so the protected widget and position members are callable; Mixin
 * discards the constructor, which exists only to satisfy the compiler.
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMetalModMixin extends Screen {

    /** Gap between the bottom of the button grid area and the footer, in pixels. */
    @Unique private static final int METALMOD_BOTTOM_INSET = 57;
    @Unique private static final int METALMOD_BUTTON_WIDTH = 200;

    @Unique private Button metalmod$entry;

    protected OptionsScreenMetalModMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void metalmod$addOptionsEntry(CallbackInfo ci) {
        // Reported like every other hook, because "the button is not there" and "the injection did
        // not apply" look identical from the screen and are told apart by this line in the log.
        Diagnostics.hook("OptionsScreen.init");
        this.metalmod$entry = Button.builder(Component.literal("MetalMod..."),
                button -> this.minecraft.setScreenAndShow(new MetalModConfigScreen(this)))
                .width(METALMOD_BUTTON_WIDTH)
                .build();
        this.addRenderableWidget(this.metalmod$entry);
        metalmod$placeEntry();
    }

    /** Vanilla re-lays its own buttons on resize; ours has to follow. */
    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void metalmod$repositionOptionsEntry(CallbackInfo ci) {
        metalmod$placeEntry();
    }

    @Unique
    private void metalmod$placeEntry() {
        if (this.metalmod$entry == null) {
            return;
        }
        this.metalmod$entry.setPosition(this.width / 2 - METALMOD_BUTTON_WIDTH / 2,
                this.height - METALMOD_BOTTOM_INSET);
    }
}
