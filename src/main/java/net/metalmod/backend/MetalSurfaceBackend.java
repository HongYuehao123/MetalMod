package net.metalmod.backend;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.List;

/**
 * Phase 1 surface: owns the CAMetalLayer attached to the game window.
 *
 * <p>acquireNextTexture() takes a drawable; blitFromTexture() records the colour to show (the
 * engine's last render-target clear, since draws are inert); present() clears the drawable and
 * presents it in one command buffer. Encoding the clear and the present together is required for
 * ordering: two command queues have no implicit order, so a separate clear would be free to land
 * after the present.
 */
public final class MetalSurfaceBackend implements GpuSurfaceBackend {

    // Phase 1 diagnostic colour. The engine's own menu clear is transparent black, which is
    // indistinguishable from a window that never presented. A pulsing teal makes "Metal is
    // presenting" visible until the draw path (Phase 3) produces the real image. Remove this and
    // use device.copyLastClearColor() once draws are encoded.
    private static final float[] FIRST_LIGHT_CLEAR = {0.05f, 0.42f, 0.60f, 1.0f};

    private final MetalDevice device;
    private final MemorySegment layer;

    private boolean configured;
    private int width;
    private int height;
    private MemorySegment drawable = MemorySegment.NULL;
    private final float[] clearColor = FIRST_LIGHT_CLEAR.clone();
    private boolean closed;

    // First-light telemetry: proves frames actually reach the display, independently of what the
    // inert draw path would otherwise make impossible to see.
    private long acquiredFrames;
    private long presentedFrames;
    private boolean firstAcquireLogged;

    public MetalSurfaceBackend(MetalDevice device, MemorySegment layer) {
        this.device = device;
        this.layer = layer;
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
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        final MemorySegment[] result;
        try {
            result = MetalNative.layerAcquire(this.layer);
        } catch (Throwable t) {
            throw new SurfaceException(t);
        }
        if (result[0] == null || result[0].address() == 0) {
            throw new SurfaceException("CAMetalLayer nextDrawable returned nil");
        }
        this.drawable = result[0];
        this.acquiredFrames++;
        if (!this.firstAcquireLogged) {
            this.firstAcquireLogged = true;
            System.out.println("[MetalMod] Metal surface first drawable acquired ("
                    + this.width + "x" + this.height + ").");
        }
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView colorTextureView) {
        // Draws are inert, so the blit source is empty. Pulse the first-light colour so a frozen
        // window is visibly distinguishable from a live present loop.
        float pulse = 0.80f + 0.20f * (float) Math.sin(this.acquiredFrames * 0.06);
        this.clearColor[0] = FIRST_LIGHT_CLEAR[0] * pulse;
        this.clearColor[1] = FIRST_LIGHT_CLEAR[1] * pulse;
        this.clearColor[2] = FIRST_LIGHT_CLEAR[2] * pulse;
        this.clearColor[3] = 1.0f;
    }

    @Override
    public void present() {
        if (this.drawable == null || this.drawable.address() == 0) {
            return;
        }
        float[] color = this.clearColor;
        int rc = MetalNative.layerPresentClear(this.layer, this.drawable,
                color[0], color[1], color[2], color[3]);
        if (rc != 0) {
            System.err.println("[MetalMod] CAMetalLayer present failed with status " + rc);
        } else {
            this.presentedFrames++;
            if (this.presentedFrames == 1 || this.presentedFrames % 600 == 0) {
                System.out.println("[MetalMod] presented " + this.presentedFrames
                        + " frame(s) on Metal | surface " + this.width + "x" + this.height
                        + " | clear RGBA(" + color[0] + ", " + color[1] + ", " + color[2]
                        + ", " + color[3] + ")");
            }
        }
        // present_clear consumes the retained drawable.
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
