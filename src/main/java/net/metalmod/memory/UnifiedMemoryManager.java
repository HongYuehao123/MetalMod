package net.metalmod.memory;

import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-level manager for Apple Silicon Unified Memory Architecture (UMA).
 * Adheres to: Conservative Trimming, Aggressive Zero-Copy Optimization.
 *
 * Provides 16 KB page-aligned unified memory allocation directly accessible by both CPU and GPU
 * without redundant staging copies or PCIe transfers.
 */
public final class UnifiedMemoryManager {

    private static final UnifiedMemoryManager INSTANCE = new UnifiedMemoryManager();

    // 16 KB page size on Apple Silicon ARM64
    public static final long PAGE_SIZE_16K = 16384L;

    private final AtomicLong activeAllocationsCount = new AtomicLong(0);
    private final AtomicLong totalAllocatedBytes = new AtomicLong(0);

    // Persistent off-heap segment for telemetry readouts
    private final Arena persistentArena = Arena.ofAuto();
    private final MemorySegment persistentTelemetrySegment =
            MetalBridge.isAvailable() ? persistentArena.allocate(MetalBridge.MEMORY_TELEMETRY_LAYOUT) : MemorySegment.NULL;

    // Cached telemetry values
    private long cachedTotalPhysicalMemory = 0;
    private long cachedAvailableMemory = 0;
    private long cachedCompressedMemory = 0;
    private long cachedSwapUsed = 0;
    private long cachedProcessResident = 0;
    private long cachedMetalAllocated = 0;
    private long cachedMetalMaxWorkingSet = 0;
    private int cachedMemoryPressureLevel = 0;

    private volatile boolean initialized = false;

    public static UnifiedMemoryManager getInstance() {
        return INSTANCE;
    }

    private UnifiedMemoryManager() {}

    /**
     * Initialize the UMA engine and macOS kernel memory pressure listener.
     */
    public synchronized void initialize() {
        if (initialized) return;

        if (MetalBridge.isAvailable() && MetalConfig.INSTANCE.enableMemoryPressureHandler) {
            try {
                MethodHandle target = MethodHandles.lookup().findStatic(
                        UnifiedMemoryManager.class,
                        "onNativeMemoryPressure",
                        MethodType.methodType(void.class, int.class)
                );
                MemorySegment callbackStub = Linker.nativeLinker().upcallStub(
                        target,
                        FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT),
                        Arena.global()
                );
                int res = MetalBridge.initMemoryPressure(callbackStub);
                if (res == 0) {
                    System.out.println("[MetalMod] Registered macOS kernel memory pressure dispatch listener.");
                }
            } catch (Throwable t) {
                System.err.println("[MetalMod] Failed to register memory pressure callback: " + t.getMessage());
            }
        }

        updateTelemetry();
        initialized = true;
    }

    /**
     * Native callback invoked by libmetalmod.dylib via Panama FFI upcall stub when macOS signals memory pressure.
     * Adheres to conservative trimming: never triggers forced GC or drops active data.
     */
    public static void onNativeMemoryPressure(int level) {
        String levelStr = switch (level) {
            case 1 -> "WARNING";
            case 2 -> "CRITICAL";
            default -> "NORMAL";
        };
        System.out.println("[MetalMod UMA] macOS Memory Pressure Event: " + levelStr + " (Level " + level + ")");
        INSTANCE.cachedMemoryPressureLevel = level;

        if (level > 0) {
            // Conservative: Only inform Mach VM and purge dormant scratch textures
            MetalBridge.purgeIdleMemory();
        }
    }

    /**
     * Allocate a 16 KB page-aligned unified memory buffer directly accessible by both CPU and GPU.
     * Aggressive Zero-Copy: Direct MTLResourceStorageModeShared + WriteCombined pointer.
     * Conservative Trimming: Allocations proceed freely without artificial caps.
     */
    public MemorySegment allocate(long size) {
        if (size <= 0) return MemorySegment.NULL;

        long alignedSize = (size + PAGE_SIZE_16K - 1) & ~(PAGE_SIZE_16K - 1);

        if (MetalConfig.INSTANCE.enableUnifiedMemoryPool && MetalBridge.isAvailable()) {
            MemorySegment segment = MetalBridge.allocateUnifiedBuffer(alignedSize);
            if (!segment.equals(MemorySegment.NULL) && segment.address() != 0) {
                activeAllocationsCount.incrementAndGet();
                totalAllocatedBytes.addAndGet(alignedSize);
                return segment;
            }
        }

        // Fallback to 16 KB page-aligned Java off-heap segment if native UMA unavailable
        activeAllocationsCount.incrementAndGet();
        totalAllocatedBytes.addAndGet(alignedSize);
        return Arena.ofAuto().allocate(alignedSize, PAGE_SIZE_16K);
    }

    /**
     * Free a unified memory buffer previously allocated with allocate().
     */
    public void free(MemorySegment segment) {
        if (segment == null || segment.equals(MemorySegment.NULL)) return;

        long size = segment.byteSize();
        activeAllocationsCount.decrementAndGet();
        totalAllocatedBytes.addAndGet(-size);

        if (MetalConfig.INSTANCE.enableUnifiedMemoryPool && MetalBridge.isAvailable()) {
            MetalBridge.freeUnifiedBuffer(segment);
        }
    }

    public void free(long address) {
        if (address == 0) return;
        activeAllocationsCount.decrementAndGet();
        if (MetalConfig.INSTANCE.enableUnifiedMemoryPool && MetalBridge.isAvailable()) {
            MetalBridge.freeUnifiedBufferAddress(address);
        }
    }

    /**
     * Query macOS Mach VM and Metal runtime for live memory metrics.
     */
    public void updateTelemetry() {
        if (!MetalBridge.isAvailable() || persistentTelemetrySegment.equals(MemorySegment.NULL)) return;

        try {
            MetalBridge.getMemoryTelemetry(persistentTelemetrySegment);
            cachedTotalPhysicalMemory = persistentTelemetrySegment.get(ValueLayout.JAVA_LONG, 0);
            cachedAvailableMemory = persistentTelemetrySegment.get(ValueLayout.JAVA_LONG, 8);
            cachedCompressedMemory = persistentTelemetrySegment.get(ValueLayout.JAVA_LONG, 16);
            cachedSwapUsed = persistentTelemetrySegment.get(ValueLayout.JAVA_LONG, 24);
            cachedProcessResident = persistentTelemetrySegment.get(ValueLayout.JAVA_LONG, 32);
            cachedMetalAllocated = persistentTelemetrySegment.get(ValueLayout.JAVA_LONG, 40);
            cachedMetalMaxWorkingSet = persistentTelemetrySegment.get(ValueLayout.JAVA_LONG, 48);
            cachedMemoryPressureLevel = persistentTelemetrySegment.get(ValueLayout.JAVA_INT, 56);
        } catch (Throwable ignored) {
        }
    }

    public long getTotalPhysicalMemory() {
        return cachedTotalPhysicalMemory;
    }

    public long getAvailableMemory() {
        return cachedAvailableMemory;
    }

    public long getCompressedMemory() {
        return cachedCompressedMemory;
    }

    public long getSwapUsed() {
        return cachedSwapUsed;
    }

    public long getProcessResident() {
        return cachedProcessResident;
    }

    public long getMetalAllocated() {
        return cachedMetalAllocated;
    }

    public long getMetalMaxWorkingSet() {
        return cachedMetalMaxWorkingSet;
    }

    public int getMemoryPressureLevel() {
        return cachedMemoryPressureLevel;
    }

    public String getPressureString() {
        return switch (cachedMemoryPressureLevel) {
            case 1 -> "§eWarning§r";
            case 2 -> "§cCritical§r";
            default -> "§aNormal§r";
        };
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0) return "0 B";
        double gib = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gib >= 1.0) {
            return String.format("%.1f GB", gib);
        }
        double mib = bytes / (1024.0 * 1024.0);
        return String.format("%.0f MB", mib);
    }
}
