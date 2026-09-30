package net.metalmod.mixin;

import com.mojang.blaze3d.platform.Window;
import net.metalmod.Diagnostics;
import net.metalmod.metalfx.WindowResolution;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks framebuffer size changes for hook diagnostics, and keeps the engine's render resolution in
 * step with the window's instead of with the display's Retina backing store.
 *
 * <p>It used to publish the size to {@code VulkanFrameManager}, which is retired (ROADMAP.md §4);
 * the Metal backend sizes itself from the engine's own render-pass descriptors.
 *
 * <p>Retargeted to the real API: the method is {@code private void onFramebufferResize(long, int, int)},
 * not {@code onFramebufferSizeChanged}, and the old {@code net.minecraft.class_1041} target caused
 * Mixin to reject the whole mixin.
 *
 * <p>The resolution redirect and its rationale live in {@link WindowResolution}: this is the engine's
 * cached call site, and {@code Minecraft.renderFrame} is the other one. Both are redirected so the
 * engine cannot read the Retina size from either.
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    @Final @Shadow private long handle;

    @Inject(method = "onFramebufferResize", at = @At("RETURN"))
    private void metalmod$onFramebufferResize(long window, int width, int height, CallbackInfo ci) {
        Diagnostics.hook("Window.onFramebufferResize");
    }

    // The callback writes these arguments straight into Window.framebufferWidth/Height and invokes
    // framebufferSizeChanged. The query redirect below does not run on this path.
    @ModifyVariable(method = "onFramebufferResize", at = @At("HEAD"), argsOnly = true, index = 3)
    private int metalmod$syncCallbackWidth(int width) {
        return WindowResolution.callbackWidth(this.handle, width);
    }

    @ModifyVariable(method = "onFramebufferResize", at = @At("HEAD"), argsOnly = true, index = 4)
    private int metalmod$syncCallbackHeight(int height) {
        return WindowResolution.callbackHeight(this.handle, height);
    }

    /**
     * Replace the reported framebuffer size with the window's logical size, in the call the engine
     * caches its render resolution from.
     *
     * <p>The original is always called, and only the out-params are adjusted, so this observes the
     * real size before deciding. See {@link WindowResolution} for why the query rather than the field
     * is the thing to change.
     */
    @Redirect(method = "refreshFramebufferSize",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwGetFramebufferSize(J[I[I)V"))
    private void metalmod$syncFramebufferSize(long window, int[] width, int[] height) {
        GLFW.glfwGetFramebufferSize(window, width, height);
        WindowResolution.applyArray(window, width, height);
    }
}
