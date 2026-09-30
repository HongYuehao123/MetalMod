package net.metalmod.backend;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuTexture;

import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.List;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

/**
 * Phase 3 surface: owns the CAMetalLayer attached to the game window.
 *
 * <p>acquireNextTexture() takes a drawable; blitFromTexture() records the colour view the engine
 * wants shown; present() blits that texture into the drawable (or clears a fallback colour when the
 * engine handed us no source). Encoding the blit and the present in one command buffer on the same
 * queue as the engine's render passes is required for ordering.
 */
public final class MetalSurfaceBackend implements GpuSurfaceBackend {

    // Fallback shown only when the engine presents without a source texture.
    private static final float[] FALLBACK_CLEAR = {0.06f, 0.09f, 0.16f, 1.0f};

    private final MetalDevice device;
    private final MemorySegment layer;
    /** The GLFW window handle, kept only so the one-off resolution line can report the window's own sizes. */
    private final long window;

    private boolean configured;
    private int width;
    private int height;
    private MemorySegment drawable = MemorySegment.NULL;
    private MemorySegment sourceTexture = MemorySegment.NULL;
    private final float[] clearColor = FALLBACK_CLEAR.clone();
    private boolean closed;
    private boolean firstPresentLogged;

    public MetalSurfaceBackend(MetalDevice device, MemorySegment layer) {
        this(device, layer, 0L);
    }

    public MetalSurfaceBackend(MetalDevice device, MemorySegment layer, long window) {
        this.device = device;
        this.layer = layer;
        this.window = window;
    }

    @Override
    public void configure(GpuSurface.Configuration configuration) throws SurfaceException {
        this.width = configuration.width();
        this.height = configuration.height();
        boolean vsync = configuration.presentMode() == GpuSurface.PresentMode.FIFO
                || configuration.presentMode() == GpuSurface.PresentMode.FIFO_RELAXED;
        int rc = MetalNative.layerConfigure(this.layer, this.width, this.height, vsync);
        if (rc != 0) {
            throw new SurfaceException("CAMetalLayer.configure failed with status " + rc);
        }
        this.configured = true;
        // Which mode the engine asked for, and what it becomes here. A vsync lock is the difference
        // between "the frame rate did not improve" and "the frame rate could not move", and without
        // this line the two are indistinguishable from outside.
        System.out.println("[MetalMod] surface configured " + this.width + "x" + this.height
                + " | requested " + configuration.presentMode()
                + " | CAMetalLayer displaySyncEnabled=" + (vsync ? "true (vsync)" : "false")
                + " | " + (MetalBackend.windowResolutionSynced()
                        ? "logical resolution (Core Animation scales to the panel)"
                        : "Retina backing resolution"));
        logResolutions();
    }

    /**
     * Print the window's sizes at each surface configuration, including fullscreen and window resizes.
     *
     * <p>Keep the physical Retina framebuffer separate from the drawable the engine requested. It is
     * logged here rather than at window creation because GLFW's answers are only settled once the
     * window exists and has been shown.
     */
    private void logResolutions() {
        if (this.window == 0L) {
            return;
        }
        // Separate one-element buffers rather than one buffer sliced by position: GLFW writes both
        // ints, and passing a slice of the same allocation as the second out-param reads back wrong.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer logicalWidthBuf = stack.mallocInt(1);
            IntBuffer logicalHeightBuf = stack.mallocInt(1);
            IntBuffer framebufferWidthBuf = stack.mallocInt(1);
            IntBuffer framebufferHeightBuf = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(this.window, logicalWidthBuf, logicalHeightBuf);
            GLFW.glfwGetFramebufferSize(this.window, framebufferWidthBuf, framebufferHeightBuf);
            int logicalWidth = logicalWidthBuf.get(0);
            int logicalHeight = logicalHeightBuf.get(0);
            int framebufferWidth = framebufferWidthBuf.get(0);
            int framebufferHeight = framebufferHeightBuf.get(0);
            System.out.println("[MetalMod] window resolution: logical " + logicalWidth + "x" + logicalHeight
                    + ", framebuffer " + framebufferWidth + "x" + framebufferHeight
                    + ", Metal drawable " + this.width + "x" + this.height
                    + ", backing scale " + (logicalWidth > 0
                            ? String.format(java.util.Locale.ROOT, "%.2f", (double) framebufferWidth / logicalWidth)
                            : "unknown")
                    + (this.width == logicalWidth && this.height == logicalHeight
                            ? " (drawable follows window resolution)"
                            : this.width == framebufferWidth && this.height == framebufferHeight
                                    ? " (drawable follows Retina backing)"
                                    : " (drawable differs from both window and backing)"));
        }
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        final Object[] result;
        // nextDrawable blocks until the GPU (or the display) frees a drawable, so the time spent
        // here is drawable acquisition time. Other GPU-related waits are captured separately by F8.
        long waitStart = System.nanoTime();
        try {
            result = MetalNative.layerAcquire(this.layer);
        } catch (Throwable t) {
            throw new SurfaceException(t);
        }
        MetalDevice.noteAcquireWait((System.nanoTime() - waitStart) / 1_000_000.0);
        MemorySegment drawable = (MemorySegment) result[0];
        if (drawable == null || drawable.address() == 0) {
            throw new SurfaceException("CAMetalLayer nextDrawable returned nil");
        }
        this.drawable = drawable;
        // The presented handler records display time after this frame reaches the screen. Read its
        // completed counters now, without touching a drawable after the present path consumes it.
        MetalDevice.pollPresentPacing();
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView colorTextureView) {
        // The engine hands us its main render target's colour view; present() blits it into the
        // drawable. Falls back to a clear when there is no source.
        this.sourceTexture = colorTextureView instanceof MetalTextureView metal
                ? metal.handle() : MemorySegment.NULL;
        if (this.sourceTexture.address() == 0) {
            float[] last = this.device.copyLastClearColor();
            System.arraycopy(last, 0, this.clearColor, 0, 4);
        }
    }

    @Override
    public void present() {
        if (this.drawable == null || this.drawable.address() == 0) {
            return;
        }
        // Frame boundary: publishes the frame interval, the GPU time accumulated since the previous
        // present and the draw count for F3, then resets the counters. The light ring is closed
        // here too, so a published light set is fenced once per presented frame.
        // Adopt a lighting setting changed in the settings screen here: the frame's passes are all
        // submitted, so dropping the compiled pipelines cannot strand a pass that still refers to one.
        this.device.applyPendingLightingSettings();
        this.device.endDynamicLightFrame();
        MetalDevice.endFrame();
        float[] color = this.clearColor;
        int rc;
        if (this.sourceTexture.address() != 0) {
            MetalDevice.countCommandBuffer();   // the present blit is a command buffer too
            rc = MetalNative.layerPresentTexture(this.layer, this.drawable, this.sourceTexture);
        } else {
            rc = MetalNative.layerPresentClear(this.layer, this.drawable,
                    color[0], color[1], color[2], color[3]);
        }
        this.sourceTexture = MemorySegment.NULL;
        // Nothing may be read from the drawable after this point: the present path consumes it.
        // Pacing is measured at acquire time instead - see acquireNextTexture.
        if (rc != 0) {
            System.err.println("[MetalMod] CAMetalLayer present failed with status " + rc);
        } else if (!this.firstPresentLogged) {
            this.firstPresentLogged = true;
            System.out.println("[MetalMod] presenting real frames on Metal | surface "
                    + this.width + "x" + this.height);
        }
        // present consumes the retained drawable.
        this.drawable = MemorySegment.NULL;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        MetalNative.layerRelease(this.layer);
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return List.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);
    }
}
