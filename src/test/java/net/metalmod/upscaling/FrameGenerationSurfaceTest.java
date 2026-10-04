package net.metalmod.upscaling;

import com.mojang.blaze3d.systems.GpuSurface;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalNative;
import net.metalmod.backend.MetalSurfaceBackend;

/** Regression: interpolation retains the ordinary owner, including menus without a world producer. */
public final class FrameGenerationSurfaceTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static int runTests() {
        String oldFlag = System.getProperty("metalmod.frameGeneration");
        MetalDevice device = null;
        MetalSurfaceBackend surface = null;
        MemorySegment layer = MemorySegment.NULL;
        try {
            System.setProperty("metalmod.frameGeneration", "false");
            device = MetalDevice.create();
            require(device != null, "real Metal device");
            var create = Linker.nativeLinker().downcallHandle(SymbolLookup.loaderLookup()
                    .find("mmm_layer_create").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            layer = (MemorySegment)create.invokeExact(MemorySegment.NULL);
            require(layer.address()!=0, "detached layer");
            surface = new MetalSurfaceBackend(device, layer);
            surface.configure(new GpuSurface.Configuration(64,48,GpuSurface.PresentMode.FIFO));
            require(!surface.displayLinkActive(), "Off uses ordinary owner");
            System.setProperty("metalmod.frameGeneration", "true");
            surface.acquireNextTexture();
            require(!surface.displayLinkActive(), "requested On retains ordinary acquisition");
            require(!FrameGenerationSettings.status().pacingActive(), "UI reports inactive pacing");
            surface.present();
            require(surface.displayLinkStats()[1]==0, "no gameplay snapshot submitted to blocked presenter");
            System.setProperty("metalmod.frameGeneration", "false");
            System.setProperty("metalmod.frameGeneration", "true");
            surface.acquireNextTexture();surface.present();
            require(!surface.displayLinkActive() && surface.displayLinkStats()[1]==0,
                    "repeated flag On cannot bypass availability");
            System.setProperty("metalmod.frameGeneration", "false");
            surface.acquireNextTexture();
            require(!surface.displayLinkActive(), "live Off relinquishes owner before ordinary acquire");
            surface.present();
            System.setProperty("metalmod.frameGeneration", "true");
            surface.configure(new GpuSurface.Configuration(65,49,GpuSurface.PresentMode.IMMEDIATE));
            require(!surface.displayLinkActive(), "IMMEDIATE retains ordinary owner despite requested On");
            surface.configure(new GpuSurface.Configuration(65,49,GpuSurface.PresentMode.FIFO));
            require(!surface.displayLinkActive(), "FIFO reconfigure retains ordinary owner at odd size");
            surface.acquireNextTexture();surface.present();
            surface.close();
            require(!surface.displayLinkActive(), "close stops native owner");
            System.out.println("PASS Frame Generation retains ordinary drawable ownership: flags, repeated choices, FIFO/IMMEDIATE and odd resize");
            return 0;
        } catch (Throwable error) { error.printStackTrace(); return 1; }
        finally {
            if (surface != null) surface.close();
            else if (layer.address()!=0) MetalNative.layerRelease(layer);
            if (device != null) device.close();
            if (oldFlag == null) System.clearProperty("metalmod.frameGeneration");
            else System.setProperty("metalmod.frameGeneration",oldFlag);
        }
    }
}
