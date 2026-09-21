#import "metalmod_internal.h"
#import "metalmod/metalmod_memory.h"
#import <Metal/Metal.h>

#include <sys/mman.h>
#include <sys/sysctl.h>
#include <mach/mach.h>
#include <mach/mach_host.h>
#include <mach/host_info.h>
#include <mach/task_info.h>
#include <mach/vm_statistics.h>
#include <dispatch/dispatch.h>

#include <unordered_map>
#include <mutex>

// Apple Silicon ARM64 hardware page size (16 KB)
static const size_t kAppleSiliconPageSize = 16384;

static inline size_t align_up_16k(size_t val) {
    return (val + kAppleSiliconPageSize - 1) & ~(kAppleSiliconPageSize - 1);
}

// Global buffer tracking for zero-copy UMA buffers
static std::mutex g_UmaMutex;
static std::unordered_map<void*, id<MTLBuffer>> g_UmaAllocations;

// Dispatch source for macOS kernel memory pressure
static dispatch_source_t g_MemoryPressureSource = nil;
static MetalModMemoryPressureCallback g_PressureCallback = nullptr;
static int32_t g_CurrentPressureLevel = 0; // 0 = Normal, 1 = Warning, 2 = Critical

extern "C" {

void* metalmod_uma_alloc(size_t size) {
    if (size == 0) return nullptr;

    MetalModState *state = [MetalModState sharedState];
    id<MTLDevice> device = state.device ?: MTLCreateSystemDefaultDevice();
    if (!device) return nullptr;

    // Enforce 16 KB page alignment for zero-copy Apple Silicon UMA
    size_t alignedSize = align_up_16k(size);

    // MTLResourceStorageModeShared: CPU and GPU share the same physical DRAM on Apple Silicon.
    // MTLResourceCPUCacheModeWriteCombined: Writes bypass CPU L1/L2 data cache directly into DRAM,
    // avoiding cache pollution of active game loop instructions.
    MTLResourceOptions options = MTLResourceStorageModeShared | MTLResourceCPUCacheModeWriteCombined;

    id<MTLBuffer> buffer = [device newBufferWithLength:alignedSize options:options];
    if (!buffer) return nullptr;

    void *ptr = [buffer contents];
    if (!ptr) return nullptr;

    std::lock_guard<std::mutex> lock(g_UmaMutex);
    g_UmaAllocations[ptr] = buffer;

    return ptr;
}

void metalmod_uma_free(void* ptr) {
    if (!ptr) return;

    id<MTLBuffer> buffer = nil;
    {
        std::lock_guard<std::mutex> lock(g_UmaMutex);
        auto it = g_UmaAllocations.find(ptr);
        if (it != g_UmaAllocations.end()) {
            buffer = it->second;
            g_UmaAllocations.erase(it);
        }
    }

    if (buffer) {
        // Advise Mach VM that this region is freed and reusable without swap writes
        size_t len = [buffer length];
        madvise(ptr, len, MADV_FREE_REUSABLE);
        // ARC will release buffer when it goes out of scope
    }
}

void* metalmod_uma_calloc(size_t num, size_t size) {
    size_t total = num * size;
    void *ptr = metalmod_uma_alloc(total);
    if (ptr) {
        memset(ptr, 0, align_up_16k(total));
    }
    return ptr;
}

void* metalmod_uma_realloc(void* ptr, size_t newSize) {
    if (!ptr) return metalmod_uma_alloc(newSize);
    if (newSize == 0) {
        metalmod_uma_free(ptr);
        return nullptr;
    }

    size_t newAligned = align_up_16k(newSize);

    id<MTLBuffer> oldBuf = nil;
    {
        std::lock_guard<std::mutex> lock(g_UmaMutex);
        auto it = g_UmaAllocations.find(ptr);
        if (it != g_UmaAllocations.end()) {
            oldBuf = it->second;
        }
    }

    if (!oldBuf) {
        return metalmod_uma_alloc(newSize);
    }

    size_t oldLen = [oldBuf length];
    if (newAligned <= oldLen) {
        return ptr; // Already fits inside the 16KB-aligned memory page
    }

    MetalModState *state = [MetalModState sharedState];
    id<MTLDevice> device = state.device ?: MTLCreateSystemDefaultDevice();
    if (!device) return nullptr;

    MTLResourceOptions options = MTLResourceStorageModeShared | MTLResourceCPUCacheModeWriteCombined;
    id<MTLBuffer> newBuf = [device newBufferWithLength:newAligned options:options];
    if (!newBuf) return nullptr;

    void *newPtr = [newBuf contents];
    memcpy(newPtr, ptr, oldLen);

    {
        std::lock_guard<std::mutex> lock(g_UmaMutex);
        g_UmaAllocations.erase(ptr);
        g_UmaAllocations[newPtr] = newBuf;
    }
    madvise(ptr, oldLen, MADV_FREE_REUSABLE);

    return newPtr;
}

void* metalmod_uma_aligned_alloc(size_t alignment, size_t size) {
    size_t effectiveAlign = alignment > kAppleSiliconPageSize ? alignment : kAppleSiliconPageSize;
    size_t alignedSize = (size + effectiveAlign - 1) & ~(effectiveAlign - 1);
    return metalmod_uma_alloc(alignedSize);
}

void metalmod_uma_aligned_free(void* ptr) {
    metalmod_uma_free(ptr);
}

void metalmod_uma_purge_idle(void) {
    // Conservative trimming:
    // Only inform Mach VM about uncommitted or idle reusable pages.
    // Do NOT evict in-use gameplay buffers or cause stutter.
    MetalModState *state = [MetalModState sharedState];
    if (!state.device) return;

    // Set intermediate scratch textures to volatile if currently dormant
    if (state.interpolatedTexture) {
        [state.interpolatedTexture setPurgeableState:MTLPurgeableStateVolatile];
    }
}

int metalmod_memory_pressure_init(MetalModMemoryPressureCallback callback) {
    g_PressureCallback = callback;

    if (g_MemoryPressureSource) {
        return 0; // Already running
    }

    dispatch_queue_t queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0);
    g_MemoryPressureSource = dispatch_source_create(
        DISPATCH_SOURCE_TYPE_MEMORYPRESSURE,
        0,
        DISPATCH_MEMORYPRESSURE_NORMAL | DISPATCH_MEMORYPRESSURE_WARN | DISPATCH_MEMORYPRESSURE_CRITICAL,
        queue
    );

    if (!g_MemoryPressureSource) {
        return -1;
    }

    dispatch_source_set_event_handler(g_MemoryPressureSource, ^{
        unsigned long status = dispatch_source_get_data(g_MemoryPressureSource);
        int32_t level = 0; // Normal

        if (status & DISPATCH_MEMORYPRESSURE_CRITICAL) {
            level = 2;
        } else if (status & DISPATCH_MEMORYPRESSURE_WARN) {
            level = 1;
        }

        g_CurrentPressureLevel = level;

        // On warning/critical, execute conservative scratch reclaim
        if (level > 0) {
            metalmod_uma_purge_idle();
        }

        if (g_PressureCallback) {
            g_PressureCallback(level);
        }
    });

    dispatch_resume(g_MemoryPressureSource);
    return 0;
}

void metalmod_get_memory_telemetry(MetalModMemoryTelemetry* outTelemetry) {
    if (!outTelemetry) return;
    memset(outTelemetry, 0, sizeof(MetalModMemoryTelemetry));

    // 1. Total Physical RAM via sysctl
    uint64_t totalMem = 0;
    size_t size = sizeof(totalMem);
    int mib[2] = {CTL_HW, HW_MEMSIZE};
    if (sysctl(mib, 2, &totalMem, &size, NULL, 0) == 0) {
        outTelemetry->totalPhysicalMemoryBytes = totalMem;
    }

    // 2. Mach VM Page Statistics (Available & Compressed Memory)
    mach_msg_type_number_t count = HOST_VM_INFO64_COUNT;
    vm_statistics64_data_t vmStat;
    if (host_statistics64(mach_host_self(), HOST_VM_INFO64, (host_info64_t)&vmStat, &count) == KERN_SUCCESS) {
        vm_size_t pageSize = vm_kernel_page_size;
        uint64_t freeBytes = (uint64_t)vmStat.free_count * pageSize;
        uint64_t inactiveBytes = (uint64_t)vmStat.inactive_count * pageSize;
        outTelemetry->availableMemoryBytes = freeBytes + inactiveBytes;
        outTelemetry->compressedMemoryBytes = (uint64_t)vmStat.compressor_page_count * pageSize;
    }

    // 3. Swap Memory Usage via sysctl
    struct xsw_usage swapUsage;
    size_t swapSize = sizeof(swapUsage);
    int swapMib[2] = {CTL_VM, VM_SWAPUSAGE};
    if (sysctl(swapMib, 2, &swapUsage, &swapSize, NULL, 0) == 0) {
        outTelemetry->swapUsedBytes = swapUsage.xsu_used;
    }

    // 4. Process Resident Set Size (Actual Apple physical footprint)
    task_vm_info_data_t vmInfo;
    mach_msg_type_number_t taskCount = TASK_VM_INFO_COUNT;
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&vmInfo, &taskCount) == KERN_SUCCESS) {
        outTelemetry->processResidentBytes = (uint64_t)vmInfo.phys_footprint;
    }

    // 5. Metal Unified Working Set & Allocations
    MetalModState *state = [MetalModState sharedState];
    id<MTLDevice> device = state.device ?: MTLCreateSystemDefaultDevice();
    if (device) {
        outTelemetry->metalAllocatedBytes = (uint64_t)device.currentAllocatedSize;
        outTelemetry->metalMaxWorkingSetBytes = (uint64_t)device.recommendedMaxWorkingSetSize;
    }

    // 6. Memory Pressure Level
    outTelemetry->memoryPressureLevel = g_CurrentPressureLevel;
}

}
