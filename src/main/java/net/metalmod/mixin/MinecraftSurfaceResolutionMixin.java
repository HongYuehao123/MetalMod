package net.metalmod.mixin;

import net.metalmod.metalfx.WindowResolution;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;

/**
 * Keeps the swapchain configuration in step with the window's logical resolution.
 *
 * <p>The engine asks GLFW for the framebuffer size here, inside {@code renderFrame}, and builds the
 * {@code GpuSurface.Configuration} from it. That is a second, independent read from the one
 * {@code Window.refreshFramebufferSize} caches, so the redirect in {@code WindowMixin} does not cover
 * it: without this, the Metal target would follow the window resolution while the CAMetalLayer
 * drawable stayed at the Retina size, and presentation would scale 2K into 5K on the GPU - the cost
 * this setting exists to remove.
 *
 * <p>Both call sites are redirected to the same rule, so the engine can read either one and get the
 * same answer. See {@link WindowResolution}.
 */
@Mixin(Minecraft.class)
public class MinecraftSurfaceResolutionMixin {

    @Redirect(method = "renderFrame",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwGetFramebufferSize(J[I[I)V"))
    private void metalmod$syncSwapchainSize(long window, int[] width, int[] height) {
        GLFW.glfwGetFramebufferSize(window, width, height);
        WindowResolution.applyArray(window, width, height);
    }
}
