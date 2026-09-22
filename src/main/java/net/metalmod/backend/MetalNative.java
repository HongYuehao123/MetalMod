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
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Panama FFI bindings for the Metal substrate (`metalmod_metal.h`).
 *
 * <p>Separate from {@link net.metalmod.ffi.MetalBridge}, which binds the retired MoltenVK/MetalFX
 * interop API. This class covers device, queue, layer, texture, buffer, sampler and clear
 * primitives. It fails soft: if the dylib or a symbol is missing, {@link #isAvailable()} is false
 * and the backend is simply not offered.
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

    private static MethodHandle mhTextureCreateFull;
    private static MethodHandle mhTextureCreateView;
    private static MethodHandle mhTextureReplaceRegion;
    private static MethodHandle mhTextureReadRegion;
    private static MethodHandle mhTextureRelease;
    private static MethodHandle mhBufferCreate;
    private static MethodHandle mhBufferContents;
    private static MethodHandle mhBufferLength;
    private static MethodHandle mhBufferRelease;
    private static MethodHandle mhSamplerCreate;
    private static MethodHandle mhSamplerRelease;
    private static MethodHandle mhClearTextures;

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

        mhTextureCreateFull = linker.downcallHandle(symbol(lookup, "mmm_texture_create_full"),
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_BOOLEAN,
                        ValueLayout.JAVA_INT));
        mhTextureCreateView = linker.downcallHandle(symbol(lookup, "mmm_texture_create_view"),
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        mhTextureReplaceRegion = linker.downcallHandle(symbol(lookup, "mmm_texture_replace_region"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        mhTextureReadRegion = linker.downcallHandle(symbol(lookup, "mmm_texture_read_region"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        mhTextureRelease = linker.downcallHandle(symbol(lookup, "mmm_texture_release"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        mhBufferCreate = linker.downcallHandle(symbol(lookup, "mmm_buffer_create"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        mhBufferContents = linker.downcallHandle(symbol(lookup, "mmm_buffer_contents"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        mhBufferLength = linker.downcallHandle(symbol(lookup, "mmm_buffer_length"),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        mhBufferRelease = linker.downcallHandle(symbol(lookup, "mmm_buffer_release"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        mhSamplerCreate = linker.downcallHandle(symbol(lookup, "mmm_sampler_create"),
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_DOUBLE));
        mhSamplerRelease = linker.downcallHandle(symbol(lookup, "mmm_sampler_release"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        mhClearTextures = linker.downcallHandle(symbol(lookup, "mmm_clear_textures"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_DOUBLE));
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

    // -----------------------------------------------------------------------------------------
    // Call helpers
    // -----------------------------------------------------------------------------------------

    private static MemorySegment callAddress(MethodHandle handle, Object... args) {
        try {
            return (MemorySegment) handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static int callInt(MethodHandle handle, Object... args) {
        try {
            return (int) handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static long callLong(MethodHandle handle, Object... args) {
        try {
            return (long) handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static void callVoid(MethodHandle handle, Object... args) {
        try {
            handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.address() == 0;
    }

    // -----------------------------------------------------------------------------------------
    // Device / queue
    // -----------------------------------------------------------------------------------------

    public static MemorySegment deviceCreate() {
        return callAddress(mhDeviceCreate);
    }

    public static void deviceRelease(MemorySegment device) {
        callVoid(mhDeviceRelease, device);
    }

    /** @return {name, vendor, driver} */
    public static String[] deviceInfo(MemorySegment device) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocate(256);
            MemorySegment vendor = arena.allocate(128);
            MemorySegment driver = arena.allocate(128);
            int rc = callInt(mhDeviceInfo, device, name, 256L, vendor, 128L, driver, 128L);
            if (rc != 0) {
                return new String[]{"Unknown Metal device", "Apple", "Metal (macOS)"};
            }
            return new String[]{name.getString(0), vendor.getString(0), driver.getString(0)};
        }
    }

    public static long deviceMaxTextureSize(MemorySegment device) {
        return callLong(mhDeviceMaxTextureSize, device);
    }

    public static long deviceMaxBufferSize(MemorySegment device) {
        return callLong(mhDeviceMaxBufferSize, device);
    }

    public static long deviceRecommendedWorkingSet(MemorySegment device) {
        return callLong(mhDeviceRecommendedWorkingSet, device);
    }

    public static MemorySegment queueCreate(MemorySegment device) {
        return callAddress(mhQueueCreate, device);
    }

    public static void queueRelease(MemorySegment queue) {
        callVoid(mhQueueRelease, queue);
    }

    // -----------------------------------------------------------------------------------------
    // Surface
    // -----------------------------------------------------------------------------------------

    public static MemorySegment layerCreateForNsWindow(long nsWindow) {
        MemorySegment window = (nsWindow == 0) ? MemorySegment.NULL : MemorySegment.ofAddress(nsWindow);
        return callAddress(mhLayerCreateForNsWindow, window);
    }

    public static void layerRelease(MemorySegment layer) {
        callVoid(mhLayerRelease, layer);
    }

    public static int layerConfigure(MemorySegment layer, int width, int height, boolean vsync) {
        return callInt(mhLayerConfigure, layer, width, height, vsync);
    }

    /** @return {drawable, drawableTexture}; both NULL when acquisition failed. */
    public static MemorySegment[] layerAcquire(MemorySegment layer) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outDrawable = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment outTexture = arena.allocate(ValueLayout.ADDRESS);
            int rc = callInt(mhLayerAcquire, layer, outDrawable, outTexture);
            if (rc != 0) {
                return new MemorySegment[]{MemorySegment.NULL, MemorySegment.NULL};
            }
            return new MemorySegment[]{
                    outDrawable.get(ValueLayout.ADDRESS, 0),
                    outTexture.get(ValueLayout.ADDRESS, 0)
            };
        }
    }

    public static int layerPresentClear(MemorySegment layer, MemorySegment drawable,
                                        float r, float g, float b, float a) {
        return callInt(mhLayerPresentClear, layer, drawable, r, g, b, a);
    }

    // -----------------------------------------------------------------------------------------
    // Textures
    // -----------------------------------------------------------------------------------------

    public static MemorySegment textureCreateFull(MemorySegment device, long pixelFormat,
                                                  int width, int height, int depthOrLayers,
                                                  int mipLevels, int textureType,
                                                  boolean storageShared, int usageFlags) {
        return callAddress(mhTextureCreateFull, device, pixelFormat, width, height,
                depthOrLayers, mipLevels, textureType, storageShared, usageFlags);
    }

    public static MemorySegment textureCreateView(MemorySegment texture, long pixelFormat,
                                                  int textureType, int baseMipLevel, int mipLevels,
                                                  int baseLayer, int layerCount) {
        return callAddress(mhTextureCreateView, texture, pixelFormat, textureType,
                baseMipLevel, mipLevels, baseLayer, layerCount);
    }

    public static void textureRelease(MemorySegment texture) {
        callVoid(mhTextureRelease, texture);
    }

    /**
     * Upload a region from a (possibly heap) {@link ByteBuffer}. Heap buffers cannot be passed to
     * native code, so they are copied into a short-lived confined arena first.
     */
    public static int textureReplaceRegion(MemorySegment texture, int mipLevel, int slice,
                                           int x, int y, int width, int height,
                                           ByteBuffer data, long bytesPerRow) {
        MemorySegment segment = MemorySegment.ofBuffer(data.duplicate());
        if (segment.isNative()) {
            return callInt(mhTextureReplaceRegion, texture, mipLevel, slice, x, y, width, height,
                    segment, bytesPerRow);
        }
        try (Arena arena = Arena.ofConfined()) {
            long size = segment.byteSize();
            MemorySegment copy = arena.allocate(Math.max(1L, size));
            MemorySegment.copy(segment, 0L, copy, 0L, size);
            return callInt(mhTextureReplaceRegion, texture, mipLevel, slice, x, y, width, height,
                    copy, bytesPerRow);
        }
    }

    /** Upload from an already-native segment (used by the buffer-to-texture path). */
    public static int textureReplaceRegionRaw(MemorySegment texture, int mipLevel, int slice,
                                              int x, int y, int width, int height,
                                              MemorySegment nativeData, long bytesPerRow) {
        return callInt(mhTextureReplaceRegion, texture, mipLevel, slice, x, y, width, height,
                nativeData, bytesPerRow);
    }

    public static int textureReadRegion(MemorySegment texture, int mipLevel, int slice,
                                        int x, int y, int width, int height,
                                        MemorySegment nativeOut, long capacity, long bytesPerRow) {
        return callInt(mhTextureReadRegion, texture, mipLevel, slice, x, y, width, height,
                nativeOut, capacity, bytesPerRow);
    }

    // -----------------------------------------------------------------------------------------
    // Buffers
    // -----------------------------------------------------------------------------------------

    public static MemorySegment bufferCreate(MemorySegment device, long length) {
        return callAddress(mhBufferCreate, device, Math.max(1L, length));
    }

    public static MemorySegment bufferContents(MemorySegment buffer, long length) {
        MemorySegment ptr = callAddress(mhBufferContents, buffer);
        if (isNull(ptr)) {
            return MemorySegment.NULL;
        }
        return ptr.reinterpret(Math.max(1L, length));
    }

    public static long bufferLength(MemorySegment buffer) {
        return callLong(mhBufferLength, buffer);
    }

    public static void bufferRelease(MemorySegment buffer) {
        callVoid(mhBufferRelease, buffer);
    }

    // -----------------------------------------------------------------------------------------
    // Samplers
    // -----------------------------------------------------------------------------------------

    public static MemorySegment samplerCreate(MemorySegment device, int addressU, int addressV,
                                              int minFilter, int magFilter,
                                              int maxAnisotropy, boolean hasMaxLod, double maxLod) {
        return callAddress(mhSamplerCreate, device, addressU, addressV, minFilter, magFilter,
                maxAnisotropy, hasMaxLod, maxLod);
    }

    public static void samplerRelease(MemorySegment sampler) {
        callVoid(mhSamplerRelease, sampler);
    }

    // -----------------------------------------------------------------------------------------
    // Clear
    // -----------------------------------------------------------------------------------------

    public static int clearTextures(MemorySegment queue,
                                    MemorySegment colorTexture, boolean hasColor,
                                    float r, float g, float b, float a,
                                    MemorySegment depthTexture, boolean hasDepth, double depthValue) {
        MemorySegment color = hasColor && colorTexture != null ? colorTexture : MemorySegment.NULL;
        MemorySegment depth = hasDepth && depthTexture != null ? depthTexture : MemorySegment.NULL;
        return callInt(mhClearTextures, queue, color, hasColor, r, g, b, a, depth, hasDepth, depthValue);
    }
}
