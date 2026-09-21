package net.metalmod.ffi;

import java.io.File;
import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Java Foreign Function & Memory API (Panama FFI) bindings to libmetalmod.dylib.
 * Provides high-speed direct native calls without JNI overhead on Apple Silicon macOS 26+.
 */
public final class MetalBridge {

    private static boolean available = false;
    private static String loadError = null;

    // Method handles to native functions
    private static MethodHandle mh_init;
    private static MethodHandle mh_shutdown;
    private static MethodHandle mh_configure;
    private static MethodHandle mh_register_vulkan_device;
    private static MethodHandle mh_process_frame;
    private static MethodHandle mh_get_telemetry;
    private static MethodHandle mh_is_spatial_supported;
    private static MethodHandle mh_is_temporal_supported;
    private static MethodHandle mh_is_frame_gen_supported;
    private static MethodHandle mh_update_window_title;
    private static MethodHandle mh_uma_alloc;
    private static MethodHandle mh_uma_calloc;
    private static MethodHandle mh_uma_realloc;
    private static MethodHandle mh_uma_free;
    private static MethodHandle mh_uma_aligned_alloc;
    private static MethodHandle mh_uma_aligned_free;
    private static MethodHandle mh_uma_purge_idle;
    private static MethodHandle mh_get_memory_telemetry;
    private static MethodHandle mh_memory_pressure_init;

    // Struct Layout: MetalModConfig
    // uint32_t inputWidth, inputHeight, outputWidth, outputHeight (4 x 4 = 16 bytes)
    // int32_t scalingMode (4 bytes)
    // bool frameGenerationEnabled (1 byte)
    // 3 bytes padding
    // float sharpness (4 bytes)
    // bool enableHDR (1 byte)
    // bool enableUIOverlay (1 byte)
    // 2 bytes padding
    // uint32_t targetDisplayFPS (4 bytes)
    // bool enableUnifiedMemoryPool (1 byte)
    // bool enableMemoryPressureHandler (1 byte)
    // 2 bytes padding
    public static final GroupLayout CONFIG_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("inputWidth"),
            ValueLayout.JAVA_INT.withName("inputHeight"),
            ValueLayout.JAVA_INT.withName("outputWidth"),
            ValueLayout.JAVA_INT.withName("outputHeight"),
            ValueLayout.JAVA_INT.withName("scalingMode"),
            ValueLayout.JAVA_BOOLEAN.withName("frameGenerationEnabled"),
            MemoryLayout.paddingLayout(3),
            ValueLayout.JAVA_FLOAT.withName("sharpness"),
            ValueLayout.JAVA_BOOLEAN.withName("enableHDR"),
            ValueLayout.JAVA_BOOLEAN.withName("enableUIOverlay"),
            MemoryLayout.paddingLayout(2),
            ValueLayout.JAVA_INT.withName("targetDisplayFPS"),
            ValueLayout.JAVA_BOOLEAN.withName("enableUnifiedMemoryPool"),
            ValueLayout.JAVA_BOOLEAN.withName("enableMemoryPressureHandler"),
            MemoryLayout.paddingLayout(2)
    );

    // Struct Layout: MetalModMemoryTelemetry (64 bytes total)
    // uint64_t totalPhysicalMemoryBytes (8 bytes)
    // uint64_t availableMemoryBytes (8 bytes)
    // uint64_t compressedMemoryBytes (8 bytes)
    // uint64_t swapUsedBytes (8 bytes)
    // uint64_t processResidentBytes (8 bytes)
    // uint64_t metalAllocatedBytes (8 bytes)
    // uint64_t metalMaxWorkingSetBytes (8 bytes)
    // int32_t memoryPressureLevel (4 bytes)
    // int32_t reserved (4 bytes)
    public static final GroupLayout MEMORY_TELEMETRY_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("totalPhysicalMemoryBytes"),
            ValueLayout.JAVA_LONG.withName("availableMemoryBytes"),
            ValueLayout.JAVA_LONG.withName("compressedMemoryBytes"),
            ValueLayout.JAVA_LONG.withName("swapUsedBytes"),
            ValueLayout.JAVA_LONG.withName("processResidentBytes"),
            ValueLayout.JAVA_LONG.withName("metalAllocatedBytes"),
            ValueLayout.JAVA_LONG.withName("metalMaxWorkingSetBytes"),
            ValueLayout.JAVA_INT.withName("memoryPressureLevel"),
            ValueLayout.JAVA_INT.withName("reserved")
    );

    // Struct Layout: MetalModFrameParams
    // uint64_t frameIndex (8 bytes)
    // float deltaTime, jitterOffsetX, jitterOffsetY, nearPlane, farPlane, fieldOfView, aspectRatio (7 x 4 = 28 bytes)
    // bool resetHistory, isDepthReversed (2 bytes)
    // 2 bytes padding
    public static final GroupLayout FRAME_PARAMS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("frameIndex"),
            ValueLayout.JAVA_FLOAT.withName("deltaTime"),
            ValueLayout.JAVA_FLOAT.withName("jitterOffsetX"),
            ValueLayout.JAVA_FLOAT.withName("jitterOffsetY"),
            ValueLayout.JAVA_FLOAT.withName("nearPlane"),
            ValueLayout.JAVA_FLOAT.withName("farPlane"),
            ValueLayout.JAVA_FLOAT.withName("fieldOfView"),
            ValueLayout.JAVA_FLOAT.withName("aspectRatio"),
            ValueLayout.JAVA_BOOLEAN.withName("resetHistory"),
            ValueLayout.JAVA_BOOLEAN.withName("isDepthReversed"),
            MemoryLayout.paddingLayout(2)
    );

    // Struct Layout: MetalModTelemetry
    // float renderFPS, presentedFPS, gpuFrameTimeMs, upscalerTimeMs, frameGenTimeMs (5 x 4 = 20 bytes)
    // uint64_t totalFramesRendered, totalFramesPresented (2 x 8 = 16 bytes)
    public static final GroupLayout TELEMETRY_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_FLOAT.withName("renderFPS"),
            ValueLayout.JAVA_FLOAT.withName("presentedFPS"),
            ValueLayout.JAVA_FLOAT.withName("gpuFrameTimeMs"),
            ValueLayout.JAVA_FLOAT.withName("upscalerTimeMs"),
            ValueLayout.JAVA_FLOAT.withName("frameGenTimeMs"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.JAVA_LONG.withName("totalFramesRendered"),
            ValueLayout.JAVA_LONG.withName("totalFramesPresented")
    );

    static {
        try {
            loadNativeLibrary();
        } catch (Throwable t) {
            loadError = t.getMessage();
            System.err.println("[MetalMod] Failed to load libmetalmod.dylib: " + t.getMessage());
        }
    }

    private static void loadNativeLibrary() throws Exception {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("mac")) {
            throw new UnsupportedOperationException("MetalMod requires macOS.");
        }

        // Try to load from built path first, then fallback to extract from JAR
        File devBuild = new File("native/build/libmetalmod.dylib");
        File dylibFile;
        if (devBuild.exists()) {
            dylibFile = devBuild;
        } else {
            InputStream in = MetalBridge.class.getResourceAsStream("/natives/libmetalmod.dylib");
            if (in == null) {
                in = MetalBridge.class.getResourceAsStream("/libmetalmod.dylib");
            }
            if (in == null) {
                throw new IllegalStateException("Embedded libmetalmod.dylib not found in resources or native/build");
            }
            dylibFile = File.createTempFile("libmetalmod-", ".dylib");
            dylibFile.deleteOnExit();
            Files.copy(in, dylibFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        System.load(dylibFile.getAbsolutePath());

        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.loaderLookup();

        mh_init = linker.downcallHandle(
                lookup.find("metalmod_init").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );

        mh_shutdown = linker.downcallHandle(
                lookup.find("metalmod_shutdown").orElseThrow(),
                FunctionDescriptor.ofVoid()
        );

        mh_configure = linker.downcallHandle(
                lookup.find("metalmod_configure").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );

        mh_register_vulkan_device = linker.downcallHandle(
                lookup.find("metalmod_register_vulkan_device").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        mh_process_frame = linker.downcallHandle(
                lookup.find("metalmod_process_frame").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS
                )
        );

        mh_get_telemetry = linker.downcallHandle(
                lookup.find("metalmod_get_telemetry").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        mh_is_spatial_supported = linker.downcallHandle(
                lookup.find("metalmod_is_spatial_scaler_supported").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_BOOLEAN,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT
                )
        );

        mh_is_temporal_supported = linker.downcallHandle(
                lookup.find("metalmod_is_temporal_scaler_supported").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_BOOLEAN,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT
                )
        );

        mh_is_frame_gen_supported = linker.downcallHandle(
                lookup.find("metalmod_is_frame_gen_supported").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_BOOLEAN,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT
                )
        );

        mh_update_window_title = linker.downcallHandle(
                lookup.find("metalmod_update_window_title").orElseThrow(),
                FunctionDescriptor.ofVoid()
        );

        mh_uma_alloc = linker.downcallHandle(
                lookup.find("metalmod_uma_alloc").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
        );
        mh_uma_calloc = linker.downcallHandle(
                lookup.find("metalmod_uma_calloc").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
        );

        mh_uma_realloc = linker.downcallHandle(
                lookup.find("metalmod_uma_realloc").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
        );

        mh_uma_free = linker.downcallHandle(
                lookup.find("metalmod_uma_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        mh_uma_aligned_alloc = linker.downcallHandle(
                lookup.find("metalmod_uma_aligned_alloc").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
        );

        mh_uma_aligned_free = linker.downcallHandle(
                lookup.find("metalmod_uma_aligned_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        mh_uma_purge_idle = linker.downcallHandle(
                lookup.find("metalmod_uma_purge_idle").orElseThrow(),
                FunctionDescriptor.ofVoid()
        );

        mh_get_memory_telemetry = linker.downcallHandle(
                lookup.find("metalmod_get_memory_telemetry").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        mh_memory_pressure_init = linker.downcallHandle(
                lookup.find("metalmod_memory_pressure_init").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );

        available = true;
        System.out.println("[MetalMod] Successfully loaded libmetalmod.dylib via Panama FFI!");
    }

    public static boolean isAvailable() {
        return available;
    }

    public static String getLoadError() {
        return loadError;
    }

    public static int init(MemorySegment nsWindowHandle) {
        if (!available) return -1;
        try {
            return (int) mh_init.invokeExact(nsWindowHandle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void shutdown() {
        if (!available) return;
        try {
            mh_shutdown.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int configure(MemorySegment configSegment) {
        if (!available) return -1;
        try {
            return (int) mh_configure.invokeExact(configSegment);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int registerVulkanDevice(MemorySegment vkDevice, MemorySegment exportFunc) {
        if (!available) return -1;
        try {
            return (int) mh_register_vulkan_device.invokeExact(vkDevice, exportFunc);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int processFrame(
            MemorySegment colorImage,
            MemorySegment depthImage,
            MemorySegment motionImage,
            MemorySegment uiImage,
            MemorySegment paramsSegment
    ) {
        if (!available) return -1;
        try {
            return (int) mh_process_frame.invokeExact(
                    colorImage != null ? colorImage : MemorySegment.NULL,
                    depthImage != null ? depthImage : MemorySegment.NULL,
                    motionImage != null ? motionImage : MemorySegment.NULL,
                    uiImage != null ? uiImage : MemorySegment.NULL,
                    paramsSegment
            );
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void getTelemetry(MemorySegment telemetrySegment) {
        if (!available) return;
        try {
            mh_get_telemetry.invokeExact(telemetrySegment);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static boolean isSpatialSupported(int inW, int inH, int outW, int outH) {
        if (!available) return false;
        try {
            return (boolean) mh_is_spatial_supported.invokeExact(inW, inH, outW, outH);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isTemporalSupported(int inW, int inH, int outW, int outH) {
        if (!available) return false;
        try {
            return (boolean) mh_is_temporal_supported.invokeExact(inW, inH, outW, outH);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isFrameGenSupported(int width, int height) {
        if (!available) return false;
        try {
            return (boolean) mh_is_frame_gen_supported.invokeExact(width, height);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void updateWindowTitle() {
        if (!available || mh_update_window_title == null) return;
        try {
            mh_update_window_title.invokeExact();
        } catch (Throwable t) {
            // non-fatal
        }
    }

    public static MemorySegment allocateUnifiedBuffer(long size) {
        if (!available || size <= 0 || mh_uma_alloc == null) return MemorySegment.NULL;
        try {
            MemorySegment rawPtr = (MemorySegment) mh_uma_alloc.invokeExact(size);
            if (rawPtr.equals(MemorySegment.NULL) || rawPtr.address() == 0) {
                return MemorySegment.NULL;
            }
            return MemorySegment.ofAddress(rawPtr.address()).reinterpret(size);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to allocate UMA buffer of size " + size, t);
        }
    }

    public static void freeUnifiedBuffer(MemorySegment segment) {
        if (!available || segment == null || segment.equals(MemorySegment.NULL) || mh_uma_free == null) return;
        try {
            mh_uma_free.invokeExact(segment);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to free UMA buffer", t);
        }
    }

    public static MemorySegment allocateUnifiedCalloc(long num, long size) {
        if (!available || num <= 0 || size <= 0 || mh_uma_calloc == null) return MemorySegment.NULL;
        try {
            MemorySegment rawPtr = (MemorySegment) mh_uma_calloc.invokeExact(num, size);
            if (rawPtr.equals(MemorySegment.NULL) || rawPtr.address() == 0) return MemorySegment.NULL;
            return MemorySegment.ofAddress(rawPtr.address()).reinterpret(num * size);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to calloc UMA buffer", t);
        }
    }

    public static MemorySegment reallocateUnified(long address, long newSize) {
        if (!available || mh_uma_realloc == null) return MemorySegment.NULL;
        try {
            MemorySegment oldPtr = (address == 0) ? MemorySegment.NULL : MemorySegment.ofAddress(address);
            MemorySegment rawPtr = (MemorySegment) mh_uma_realloc.invokeExact(oldPtr, newSize);
            if (rawPtr.equals(MemorySegment.NULL) || rawPtr.address() == 0) return MemorySegment.NULL;
            return MemorySegment.ofAddress(rawPtr.address()).reinterpret(newSize);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to realloc UMA buffer", t);
        }
    }

    public static MemorySegment allocateUnifiedAligned(long alignment, long size) {
        if (!available || size <= 0 || mh_uma_aligned_alloc == null) return MemorySegment.NULL;
        try {
            MemorySegment rawPtr = (MemorySegment) mh_uma_aligned_alloc.invokeExact(alignment, size);
            if (rawPtr.equals(MemorySegment.NULL) || rawPtr.address() == 0) return MemorySegment.NULL;
            return MemorySegment.ofAddress(rawPtr.address()).reinterpret(size);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to allocate aligned UMA buffer", t);
        }
    }

    public static void freeUnifiedBufferAddress(long address) {
        if (!available || address == 0 || mh_uma_free == null) return;
        try {
            mh_uma_free.invokeExact(MemorySegment.ofAddress(address));
        } catch (Throwable t) {
            throw new RuntimeException("Failed to free UMA buffer address 0x" + Long.toHexString(address), t);
        }
    }

    public static void purgeIdleMemory() {
        if (!available || mh_uma_purge_idle == null) return;
        try {
            mh_uma_purge_idle.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to purge idle memory", t);
        }
    }

    public static void getMemoryTelemetry(MemorySegment outTelemetry) {
        if (!available || outTelemetry == null || outTelemetry.equals(MemorySegment.NULL) || mh_get_memory_telemetry == null) return;
        try {
            mh_get_memory_telemetry.invokeExact(outTelemetry);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to get memory telemetry", t);
        }
    }

    public static int initMemoryPressure(MemorySegment callbackStub) {
        if (!available || mh_memory_pressure_init == null) return -1;
        try {
            return (int) mh_memory_pressure_init.invokeExact(callbackStub);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to init memory pressure listener", t);
        }
    }
}
