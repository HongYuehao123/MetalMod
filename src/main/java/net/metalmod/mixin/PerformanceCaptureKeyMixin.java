package net.metalmod.mixin;

import net.metalmod.debug.PerformanceCapture;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class PerformanceCaptureKeyMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void metalmod$captureKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.key() == GLFW.GLFW_KEY_F8 && event.modifiers() == 0
                && window == minecraft.getWindow().handle() && minecraft.gui.screen() == null
                && minecraft.level != null && minecraft.isWindowActive()) {
            if (action == GLFW.GLFW_PRESS) PerformanceCapture.toggle(minecraft);
            ci.cancel(); // Repeat/release cannot start a second capture or leak a held key.
        }
    }
}
