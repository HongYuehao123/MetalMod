package net.metalmod.mixin;

import net.metalmod.debug.CaptureRouteRecorder;
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
        if (window != minecraft.getWindow().handle() || minecraft.gui.screen() != null
                || minecraft.level == null || !minecraft.isWindowActive() || event.modifiers() != 0) {
            return;
        }
        // F8 records performance; F7 records the route a capture should replay. Both cancel the
        // event, so a repeat or release cannot start a second one or leak a held key.
        if (event.key() == GLFW.GLFW_KEY_F8) {
            if (action == GLFW.GLFW_PRESS) PerformanceCapture.toggle(minecraft);
            ci.cancel();
        } else if (event.key() == GLFW.GLFW_KEY_F7) {
            if (action == GLFW.GLFW_PRESS) CaptureRouteRecorder.toggle(minecraft);
            ci.cancel();
        }
    }
}
