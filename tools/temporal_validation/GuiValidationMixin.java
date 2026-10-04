package net.metalmod.validation.mixin;

import net.metalmod.client.gui.MetalModMetalFxScreen;
import net.metalmod.validation.FrameGenerationValidation;
import net.minecraft.client.gui.components.Button;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only: after exercising the real button, keep external clicks out of its timed assertion. */
@Mixin(MetalModMetalFxScreen.class)
public class GuiValidationMixin {
    @Shadow private Button frameGeneration;
    @Inject(method="refresh",at=@At("RETURN"),require=1)
    private void validation$controlledSettings(CallbackInfo ci) {
        if(Boolean.getBoolean("metalmod.frameGenerationValidation")
                &&FrameGenerationValidation.controlledSettings&&frameGeneration!=null)
            frameGeneration.active=false;
    }
}
