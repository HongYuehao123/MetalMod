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
     * What {@link #setWindowHints()} actually asked GLFW for, recorded so the surface can say in its
     * startup line whether the window is logical-resolution or Retina-backed.
     *
     * <p>Written once, during window creation, and only read afterwards.
     */
    private static volatile boolean windowResolutionSynced;

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

    /**
     * Whether the Metal window's render targets follow its logical resolution.
     *
     * <p>Latched during Metal window creation. The 2K choice is the default; the Retina-size
     * alternative preserves more edge coverage at a higher rendering cost.
     */
    public static boolean windowResolutionSynced() {
        return windowResolutionSynced;
    }

    /** @return a backend to offer, or null when disabled or the native substrate is unavailable. */
    public static MetalBackend tryCreate() {
        if (!isEnabled()) {
            return null;
        }
        if (!MetalNative.isAvailable()) {
            return null;
        }
        System.out.println("[MetalMod] Metal backend ENABLED. This is still an in-progress backend; "
                + "disable preferMetalBackend to play on the default backend.");
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

        // Retina backing, off. macOS gives a 5K panel a 2x backing scale, so GLFW reports a
        // 2560x1440 fullscreen mode as a 5120x2880 framebuffer. The engine sizes MainTarget from
        // that framebuffer (GameRenderer) and the swapchain from the same numbers
        // (Minecraft.renderFrame), so the chosen resolution silently becomes a 5K render target -
        // which is also MetalFX's output size, and therefore its cost. Clearing this hint helps in
        // windowed mode; WindowResolution also maps the engine's size reads and resize callback,
        // because macOS can restore Retina backing when entering fullscreen. Core Animation scales
        // the resulting drawable to the panel.
        //
        // This is a window-creation hint: it is read once, by glfwCreateWindow, so changing it needs
        // a restart. It is also macOS-only, hence the platform guard - on other platforms GLFW
        // ignores the hint, and asserting the intent is better than pretending it applied.
        boolean sync = MetalConfig.INSTANCE.syncWindowResolution()
                && com.mojang.blaze3d.platform.MacosUtil.IS_MACOS;
        if (sync) {
            GLFW.glfwWindowHint(GLFW.GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW.GLFW_FALSE);
        }
        windowResolutionSynced = sync;
        System.out.println("[MetalMod] window resolution sync " + (sync ? "ON" : "OFF")
                + (sync
                        ? " (targets follow the window's logical resolution, not the Retina backing)"
                        : " (targets follow the Retina backing resolution - expected on a 2x display)")
                + configOverrideNote());
    }

    /** Names the launch flag when one is in force, so the log explains a value the file disagrees with. */
    private static String configOverrideNote() {
        return System.getProperty("metalmod.syncWindowResolution") != null
                ? " [-Dmetalmod.syncWindowResolution is set]" : "";
    }

    @Override
    public void handleWindowCreationErrors(GLFWErrorCapture.Error error) throws BackendCreationException {
        windowResolutionSynced = false;
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
            windowResolutionSynced = false;
            throw new BackendCreationException(
                    "No Metal device available",
                    BackendCreationException.Reason.OTHER);
        }
        return new GpuDevice(device, criticalShaderLoader);
    }
}
