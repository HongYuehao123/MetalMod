package net.metalmod.backend;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuTexture;

import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.List;

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

    private boolean configured;
    private int width;
    private int height;
    private MemorySegment drawable = MemorySegment.NULL;
    private MemorySegment sourceTexture = MemorySegment.NULL;
    private final float[] clearColor = FALLBACK_CLEAR.clone();
    private boolean closed;
    private boolean firstPresentLogged;

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
        // nextDrawable blocks until the GPU (or the display) frees a drawable, so the time spent
        // here is the frame's wait on the GPU. MetalDevice turns it into the CPU/GPU split F3 shows.
        long waitStart = System.nanoTime();
        try {
            result = MetalNative.layerAcquire(this.layer);
        } catch (Throwable t) {
            throw new SurfaceException(t);
        }
        MetalDevice.noteAcquireWait((System.nanoTime() - waitStart) / 1_000_000.0);
        if (result[0] == null || result[0].address() == 0) {
            throw new SurfaceException("CAMetalLayer nextDrawable returned nil");
        }
        this.drawable = result[0];
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
        // present and the draw count for F3, then resets the counters.
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
