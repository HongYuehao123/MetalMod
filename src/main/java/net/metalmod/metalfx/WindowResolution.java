package net.metalmod.metalfx;

import net.metalmod.backend.MetalBackend;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/**
 * Answers "what size is the framebuffer" with the window's logical resolution instead of the Retina
 * backing store, when {@code syncWindowResolution} is on.
 *
 * <h2>Why this exists</h2>
 *
 * <p>macOS gives a 5K panel a 2x backing scale, so a 2560x1440 fullscreen mode reports a 5120x2880
 * {@code glfwGetFramebufferSize}. The engine takes that number as the render resolution in two
 * separate places: {@code Window.refreshFramebufferSize} caches it, and {@code Window.getWidth()}
 * returns the cache, which is what {@code GameRenderer} builds {@code MainTarget} from and what
 * {@code Minecraft.renderFrame} configures the swapchain with. A 2K setting therefore becomes a 5K
 * render target and a 5K MetalFX output - and MetalFX's temporal scaler is what pays for the output
 * pixels (docs/temporal-performance.md).
 *
 * <p>Clearing GLFW's own Retina hint does not fix it: the hint is honoured while the window is
 * windowed, but macOS rebuilds the backing store from the display's scale when it switches modes, so
 * the hint has no effect in the fullscreen case that matters. Redirecting the query is what makes the
 * reported size deterministic. The same rule covers both size queries and the framebuffer resize
 * callback, which writes its dimensions directly into the engine's cache.
 *
 * <p>The answer is the window's <em>logical</em> size. In fullscreen that is the video mode chosen in
 * Settings; windowed, it is the window size. Either way the render target follows the resolution the
 * window is at rather than the panel's backing store, and Core Animation scales the result up to the
 * panel.
 */
public final class WindowResolution {

    private WindowResolution() {
    }

    /**
     * The size the engine should treat as the framebuffer size, for the {@code int[]} out-params GLFW
     * uses in {@code Window.refreshFramebufferSize}.
     *
     * <p>Falls back to the real query whenever the sync is off, the window handle is missing, the
     * logical size is unavailable, or it is already the same as the backing size. Switching the
     * setting off restores the stock behavior exactly.
     */
    public static void applyArray(long window, int[] width, int[] height) {
        if (width == null || height == null || width.length == 0 || height.length == 0) {
            return;
        }
        long[] result = resolve(window, width[0], height[0]);
        width[0] = (int) result[0];
        height[0] = (int) result[1];
    }

    /** GLFW's resize callback bypasses refreshFramebufferSize and writes its arguments directly. */
    public static int callbackWidth(long window, int framebufferWidth) {
        return resolveDimension(window, framebufferWidth, true);
    }

    /** The callback's height follows the same rule as its width. */
    public static int callbackHeight(long window, int framebufferHeight) {
        return resolveDimension(window, framebufferHeight, false);
    }

    /**
     * The logical size to report, or the GLFW size unchanged when the sync does not apply.
     *
     * @return {@code {width, height}}
     */
    private static long[] resolve(long window, int glfwWidth, int glfwHeight) {
        int width = glfwWidth;
        int height = glfwHeight;

        if (MetalBackend.windowResolutionSynced() && window != 0L) {
            int logicalWidth = 0;
            int logicalHeight = 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer widthBuf = stack.mallocInt(1);
                IntBuffer heightBuf = stack.mallocInt(1);
                GLFW.glfwGetWindowSize(window, widthBuf, heightBuf);
                logicalWidth = widthBuf.get(0);
                logicalHeight = heightBuf.get(0);
            } catch (Throwable ignored) {
                // A window in teardown can fail to answer; the stock size is still correct then.
            }
            if (logicalWidth > 0 && logicalHeight > 0
                    && (logicalWidth != width || logicalHeight != height)) {
                if (Boolean.getBoolean("metalmod.logWindowResize")) {
                    System.out.println("[MetalMod] framebuffer size " + width + "x" + height
                            + " -> " + logicalWidth + "x" + logicalHeight + " (window logical size)");
                }
                width = logicalWidth;
                height = logicalHeight;
            }
        }
        return new long[] {width, height};
    }

    private static int resolveDimension(long window, int framebufferSize, boolean width) {
        if (!MetalBackend.windowResolutionSynced() || window == 0L || framebufferSize <= 0) {
            return framebufferSize;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer logicalWidth = stack.mallocInt(1);
            IntBuffer logicalHeight = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(window, logicalWidth, logicalHeight);
            int logicalSize = width ? logicalWidth.get(0) : logicalHeight.get(0);
            return logicalSize > 0 ? logicalSize : framebufferSize;
        } catch (Throwable ignored) {
            return framebufferSize;
        }
    }
}
