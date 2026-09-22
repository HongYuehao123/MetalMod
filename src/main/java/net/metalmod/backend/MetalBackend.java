package net.metalmod.backend;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import net.metalmod.config.MetalConfig;
import org.lwjgl.glfw.GLFW;

/**
 * The Metal graphics backend offered to Minecraft alongside OpenGL and Vulkan.
 *
 * <p>Failure is safe by construction: if the native library is missing the backend is never added,
 * and if device creation fails it throws BackendCreationException, which makes Minecraft close the
 * window and fall through to the next backend in PreferredGraphicsApi.getBackendsToTry().
 */
public final class MetalBackend implements GpuBackend {

    /**
     * Whether the Metal backend should be offered at all.
     *
     * <p>Off by default: the backend presents cleared frames but cannot draw the game yet, so
     * leaving it on would make Minecraft unusable. Enable per-install with
     * `preferMetalBackend=true` in `config/metalmod.properties`, or per-launch with
     * `-Dmetalmod.metalBackend=true`.
     */
    public static boolean isEnabled() {
        if (Boolean.getBoolean("metalmod.metalBackend")) {
            return true;
        }
        return MetalConfig.INSTANCE.preferMetalBackend;
    }

    /** @return a backend to offer, or null when disabled or the native substrate is unavailable. */
    public static MetalBackend tryCreate() {
        if (!isEnabled()) {
            return null;
        }
        if (!MetalNative.isAvailable()) {
            return null;
        }
        System.out.println("[MetalMod] Metal backend ENABLED (first light): draws are inert, so the "
                + "game will show a flat clear colour. Disable preferMetalBackend to play normally.");
        return new MetalBackend();
    }

    @Override
    public String getName() {
        return "Metal";
    }

    @Override
    public void setWindowHints() {
        // No GL context: the CAMetalLayer is attached to the window's content view instead.
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
    }

    @Override
    public void handleWindowCreationErrors(GLFWErrorCapture.Error error) throws BackendCreationException {
        if (error != null) {
            throw new BackendCreationException(
                    "GLFW_ERROR: 0x" + Integer.toHexString(error.error()),
                    BackendCreationException.Reason.GLFW_ERROR);
        }
        throw new BackendCreationException(
                "Failed to create window for Metal",
                BackendCreationException.Reason.GLFW_ERROR);
    }

    @Override
    public GpuDevice createDevice(long window, ShaderSource shaderSource,
                                  GpuDebugOptions debugOptions, Runnable criticalShaderLoader)
            throws BackendCreationException {
        MetalDevice device = MetalDevice.create();
        if (device == null) {
            throw new BackendCreationException(
                    "No Metal device available",
                    BackendCreationException.Reason.OTHER);
        }
        return new GpuDevice(device, criticalShaderLoader);
    }
}
