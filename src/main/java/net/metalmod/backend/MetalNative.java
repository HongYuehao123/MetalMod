package net.metalmod.backend;

import java.io.File;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Panama FFI bindings for the Metal substrate (`metalmod_metal.h`).
 *
 * <p>This is deliberately separate from {@link net.metalmod.ffi.MetalBridge}: that class binds the
 * retired MoltenVK/MetalFX interop API, while this one binds the primitives the Metal renderer
 * backend needs (device, queue, CAMetalLayer, clear+present). Both load the same dylib, which is
 * safe because the JVM does not load a library twice.
 *
 * <p>The class fails soft: if the dylib or a symbol is missing, {@link #isAvailable()} returns
 * false and the backend is simply not offered, so Minecraft falls back to Vulkan/OpenGL.
 */
public final class MetalNative {

    private static boolean available = false;
    private static String loadError = null;

    private static MethodHandle mhDeviceCreate;
    private static MethodHandle mhDeviceRelease;
    private static MethodHandle mhDeviceInfo;
    private static MethodHandle mhDeviceMaxTextureSize;
    private static MethodHandle mhDeviceMaxBufferSize;
    private static MethodHandle mhDeviceRecommendedWorkingSet;
    private static MethodHandle mhQueueCreate;
    private static MethodHandle mhQueueRelease;
    private static MethodHandle mhLayerCreateForNsWindow;
    private static MethodHandle mhLayerRelease;
    private static MethodHandle mhLayerConfigure;
    private static MethodHandle mhLayerAcquire;
    private static MethodHandle mhLayerPresentClear;

    static {
        try {
            load();
            available = true;
            System.out.println("[MetalMod] Metal backend native library ready (mmm_* symbols resolved).");
        } catch (Throwable t) {
            loadError = t.getMessage();
            System.err.println("[MetalMod] Metal backend native library unavailable: " + loadError);
        }
    }

    private MetalNative() {
    }

    private static void load() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) {
            throw new UnsupportedOperationException("MetalMod requires macOS.");
        }

        File devBuild = new File("native/build/libmetalmod.dylib");
        File dylib;
        if (devBuild.exists()) {
            dylib = devBuild;
        } else {
            InputStream in = MetalNative.class.getResourceAsStream("/natives/libmetalmod.dylib");
            if (in == null) {
                in = MetalNative.class.getResourceAsStream("/libmetalmod.dylib");
            }
            if (in == null) {
                throw new IllegalStateException("Embedded libmetalmod.dylib not found in resources or native/build");
            }
            dylib = File.createTempFile("libmetalmod-", ".dylib");
            dylib.deleteOnExit();
            Files.copy(in, dylib.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        System.load(dylib.getAbsolutePath());

        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.loaderLookup();

        mhDeviceCreate = linker.downcallHandle(symbol(lookup, "mmm_device_create"),
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        mhDeviceRelease = linker.downcallHandle(symbol(lookup, "mmm_device_release"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        mhDeviceInfo = linker.downcallHandle(symbol(lookup, "mmm_device_info"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        mhDeviceMaxTextureSize = linker.downcallHandle(symbol(lookup, "mmm_device_max_texture_size"),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        mhDeviceMaxBufferSize = linker.downcallHandle(symbol(lookup, "mmm_device_max_buffer_size"),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        mhDeviceRecommendedWorkingSet = linker.downcallHandle(symbol(lookup, "mmm_device_recommended_working_set"),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        mhQueueCreate = linker.downcallHandle(symbol(lookup, "mmm_queue_create"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        mhQueueRelease = linker.downcallHandle(symbol(lookup, "mmm_queue_release"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        mhLayerCreateForNsWindow = linker.downcallHandle(symbol(lookup, "mmm_layer_create_for_ns_window"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        mhLayerRelease = linker.downcallHandle(symbol(lookup, "mmm_layer_release"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        mhLayerConfigure = linker.downcallHandle(symbol(lookup, "mmm_layer_configure"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_BOOLEAN));
        mhLayerAcquire = linker.downcallHandle(symbol(lookup, "mmm_layer_acquire"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        mhLayerPresentClear = linker.downcallHandle(symbol(lookup, "mmm_layer_present_clear"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
    }

    private static MemorySegment symbol(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() -> new IllegalStateException(
                "libmetalmod.dylib does not export '" + name + "'. Rebuild the native library "
                        + "with scripts/build_mod.sh."));
    }

    public static boolean isAvailable() {
        return available;
    }

    public static String getLoadError() {
        return loadError;
    }

    private static MemorySegment callAddress(MethodHandle handle, Object... args) {
        try {
            return (MemorySegment) handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static MemorySegment deviceCreate() {
        return callAddress(mhDeviceCreate);
    }

    public static void deviceRelease(MemorySegment device) {
        try {
            mhDeviceRelease.invokeExact(device);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** @return {name, vendor, driver} */
    public static String[] deviceInfo(MemorySegment device) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocate(256);
            MemorySegment vendor = arena.allocate(128);
            MemorySegment driver = arena.allocate(128);
            int rc = (int) mhDeviceInfo.invokeExact(device, name, 256L, vendor, 128L, driver, 128L);
            if (rc != 0) {
                return new String[]{"Unknown Metal device", "Apple", "Metal (macOS)"};
            }
            return new String[]{name.getString(0), vendor.getString(0), driver.getString(0)};
        } catch (Throwable t) {
            return new String[]{"Unknown Metal device", "Apple", "Metal (macOS)"};
        }
    }

    public static long deviceMaxTextureSize(MemorySegment device) {
        try {
            return (long) mhDeviceMaxTextureSize.invokeExact(device);
        } catch (Throwable t) {
            return 8192L;
        }
    }

    public static long deviceMaxBufferSize(MemorySegment device) {
        try {
            return (long) mhDeviceMaxBufferSize.invokeExact(device);
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static long deviceRecommendedWorkingSet(MemorySegment device) {
        try {
            return (long) mhDeviceRecommendedWorkingSet.invokeExact(device);
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static MemorySegment queueCreate(MemorySegment device) {
        return callAddress(mhQueueCreate, device);
    }

    public static void queueRelease(MemorySegment queue) {
        try {
            mhQueueRelease.invokeExact(queue);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static MemorySegment layerCreateForNsWindow(long nsWindow) {
        MemorySegment window = (nsWindow == 0) ? MemorySegment.NULL : MemorySegment.ofAddress(nsWindow);
        return callAddress(mhLayerCreateForNsWindow, window);
    }

    public static void layerRelease(MemorySegment layer) {
        try {
            mhLayerRelease.invokeExact(layer);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int layerConfigure(MemorySegment layer, int width, int height, boolean vsync) {
        try {
            return (int) mhLayerConfigure.invokeExact(layer, width, height, vsync);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** @return {drawable, drawableTexture}; both NULL when acquisition failed. */
    public static MemorySegment[] layerAcquire(MemorySegment layer) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outDrawable = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment outTexture = arena.allocate(ValueLayout.ADDRESS);
            int rc = (int) mhLayerAcquire.invokeExact(layer, outDrawable, outTexture);
            if (rc != 0) {
                return new MemorySegment[]{MemorySegment.NULL, MemorySegment.NULL};
            }
            return new MemorySegment[]{
                    outDrawable.get(ValueLayout.ADDRESS, 0),
                    outTexture.get(ValueLayout.ADDRESS, 0)
            };
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int layerPresentClear(MemorySegment layer, MemorySegment drawable,
                                        float r, float g, float b, float a) {
        try {
            return (int) mhLayerPresentClear.invokeExact(layer, drawable, r, g, b, a);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
