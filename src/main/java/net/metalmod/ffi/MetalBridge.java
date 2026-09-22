package net.metalmod.ffi;

import java.io.File;
import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Java Foreign Function &amp; Memory API (Panama FFI) bindings to libmetalmod.dylib.
 *
 * <p>Only two native surfaces remain: the Metal renderer backend (bound separately by
 * {@code net.metalmod.backend.MetalNative}) and the Apple Silicon UMA / memory-telemetry pool bound
 * here. The MoltenVK-interop frame pipeline that used to be bound here - {@code metalmod_init},
 * {@code metalmod_configure}, {@code metalmod_register_vulkan_device}, {@code metalmod_process_frame}
 * and the MetalFX capability queries - was retired with the rest of that architecture
 * (ROADMAP.md §4) and deleted from the native library, so its bindings are gone too.
 * {@link #symbol} throws for a missing export, so a stale binding would have failed library load.
 */
public final class MetalBridge {

    private static boolean available = false;
    private static String loadError = null;

    // Method handles to native functions
    private static MethodHandle mh_uma_alloc;
    private static MethodHandle mh_uma_calloc;
    private static MethodHandle mh_uma_realloc;
    private static MethodHandle mh_uma_free;
    private static MethodHandle mh_uma_aligned_alloc;
    private static MethodHandle mh_uma_aligned_free;
    private static MethodHandle mh_uma_purge_idle;
    private static MethodHandle mh_uma_owns;
    private static MethodHandle mh_uma_size;
    private static MethodHandle mh_get_memory_telemetry;
    private static MethodHandle mh_memory_pressure_init;

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

        // Resolve by name with a message that names the missing symbol: a bare orElseThrow()
        // reports only "No value present", which hides which export is missing.

        mh_uma_alloc = linker.downcallHandle(
                symbol(lookup, "metalmod_uma_alloc"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
        );
        mh_uma_calloc = linker.downcallHandle(
                symbol(lookup, "metalmod_uma_calloc"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
        );

        mh_uma_realloc = linker.downcallHandle(
                symbol(lookup, "metalmod_uma_realloc"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
        );

        mh_uma_free = linker.downcallHandle(
                symbol(lookup, "metalmod_uma_free"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        mh_uma_aligned_alloc = linker.downcallHandle(
                symbol(lookup, "metalmod_uma_aligned_alloc"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
        );

        mh_uma_aligned_free = linker.downcallHandle(
                symbol(lookup, "metalmod_uma_aligned_free"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        mh_uma_purge_idle = linker.downcallHandle(
                symbol(lookup, "metalmod_uma_purge_idle"),
                FunctionDescriptor.ofVoid()
        );

        mh_get_memory_telemetry = linker.downcallHandle(
                symbol(lookup, "metalmod_get_memory_telemetry"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        mh_memory_pressure_init = linker.downcallHandle(
                symbol(lookup, "metalmod_memory_pressure_init"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );

        mh_uma_owns = linker.downcallHandle(
                symbol(lookup, "metalmod_uma_owns"),
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS)
        );

        mh_uma_size = linker.downcallHandle(
                symbol(lookup, "metalmod_uma_size"),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
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

    /**
     * Whether a pointer is a live allocation from the native UMA pool.
     *
     * A custom LWJGL allocator must route free()/realloc() by ownership: pointers that were
     * allocated before the pool was installed must go back to the allocator that produced them.
     */
    public static boolean ownsUnifiedBuffer(long address) {
        if (!available || address == 0 || mh_uma_owns == null) return false;
        try {
            return (boolean) mh_uma_owns.invokeExact(MemorySegment.ofAddress(address));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Usable size of a live UMA allocation, or 0 if the address is not a live UMA allocation.
     */
    public static long unifiedBufferSize(long address) {
        if (!available || address == 0 || mh_uma_size == null) return 0L;
        try {
            return (long) mh_uma_size.invokeExact(MemorySegment.ofAddress(address));
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * Resolve an exported native symbol, failing with the symbol name in the message.
     */
    private static MemorySegment symbol(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() -> new IllegalStateException(
                "libmetalmod.dylib does not export '" + name + "'. The bundled native library is "
                        + "out of date with the Java bindings; rebuild it with scripts/build_mod.sh."));
    }
}
