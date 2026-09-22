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
    // MTLResourceCPUCacheModeWriteCombined: Writes bypass CPU L1/L2 data cache directly into DRAM.
    //
    // NOTE: write-combined memory is fast to write but slow to *read* from the CPU. Callers that
    // read these buffers back on the CPU should prefer MetalMemoryAllocator's default (non-UMA)
    // path; see the README section on the UMA pool.
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

    // No madvise(MADV_FREE_REUSABLE) here.
    //
    // The buffer may still be referenced by an in-flight command buffer (Metal retains the
    // resources an encoder used), so marking its pages reclaimable while the GPU is reading them
    // can hand out or discard live data. Releasing the MTLBuffer is what actually returns the
    // memory; local variable `buffer` goes out of scope here and ARC releases it.
    (void)buffer;
}

void* metalmod_uma_calloc(size_t num, size_t size) {
    if (num == 0 || size == 0) return nullptr;
    // Guard against multiplication overflow before it can under-allocate.
    if (num > SIZE_MAX / size) return nullptr;

    size_t total = num * size;
    void *ptr = metalmod_uma_alloc(total);
    if (ptr) {
        memset(ptr, 0, total);
    }
    return ptr;
}

void* metalmod_uma_realloc(void* ptr, size_t newSize) {
    if (!ptr) return metalmod_uma_alloc(newSize);
    if (newSize == 0) {
        metalmod_uma_free(ptr);
        return nullptr;
    }

    id<MTLBuffer> oldBuf = nil;
    {
        std::lock_guard<std::mutex> lock(g_UmaMutex);
        auto it = g_UmaAllocations.find(ptr);
        if (it != g_UmaAllocations.end()) {
            oldBuf = it->second;
        }
    }

    if (!oldBuf) {
        // Not our pointer. Returning a fresh allocation here would silently discard the caller's
        // existing contents, so fail instead and let the caller fall back to the allocator that
        // actually owns the pointer.
        return nullptr;
    }

    size_t newAligned = align_up_16k(newSize);
    size_t oldLen = [oldBuf length];
    if (newAligned <= oldLen) {
        return ptr; // Already fits inside the 16 KB-aligned allocation
    }

    MetalModState *state = [MetalModState sharedState];
    id<MTLDevice> device = state.device ?: MTLCreateSystemDefaultDevice();
    if (!device) return nullptr;

    MTLResourceOptions options = MTLResourceStorageModeShared | MTLResourceCPUCacheModeWriteCombined;
    id<MTLBuffer> newBuf = [device newBufferWithLength:newAligned options:options];
    if (!newBuf) return nullptr;

    void *newPtr = [newBuf contents];
    if (!newPtr) return nullptr;

    memcpy(newPtr, ptr, oldLen);

    {
        std::lock_guard<std::mutex> lock(g_UmaMutex);
        g_UmaAllocations.erase(ptr);
        g_UmaAllocations[newPtr] = newBuf;
    }

    return newPtr;
}

void* metalmod_uma_aligned_alloc(size_t alignment, size_t size) {
    if (size == 0) return nullptr;

    // MTLBuffer backing store is page-aligned (16 KB on Apple Silicon), which is the strongest
    // guarantee this pool can make. A larger alignment cannot be honoured here, so report failure
    // and let the caller use an allocator that can satisfy it - rather than returning a pointer
    // that is misaligned for the caller's expectations.
    if (alignment > kAppleSiliconPageSize) {
        return nullptr;
    }

    size_t effectiveAlign = alignment > kAppleSiliconPageSize ? alignment : kAppleSiliconPageSize;
    if (size > SIZE_MAX - (effectiveAlign - 1)) return nullptr;
    size_t alignedSize = (size + effectiveAlign - 1) & ~(effectiveAlign - 1);
    return metalmod_uma_alloc(alignedSize);
}

void metalmod_uma_aligned_free(void* ptr) {
    metalmod_uma_free(ptr);
}

bool metalmod_uma_owns(const void* ptr) {
    if (!ptr) return false;
    std::lock_guard<std::mutex> lock(g_UmaMutex);
    return g_UmaAllocations.find(const_cast<void*>(ptr)) != g_UmaAllocations.end();
}

size_t metalmod_uma_size(const void* ptr) {
    if (!ptr) return 0;
    std::lock_guard<std::mutex> lock(g_UmaMutex);
    auto it = g_UmaAllocations.find(const_cast<void*>(ptr));
    if (it == g_UmaAllocations.end()) return 0;
    return (size_t)[it->second length];
}

void metalmod_uma_purge_idle(void) {
    // Conservative trimming: only mark dormant scratch textures as volatile, and never while
    // frame generation is actively writing them.
    MetalModState *state = [MetalModState sharedState];
    if (!state.device) return;
    if (state.frameGenerationActive) return;

    if (state.interpolatedTexture) {
        MTLPurgeableState previous = [state.interpolatedTexture setPurgeableState:MTLPurgeableStateVolatile];
        if (previous == MTLPurgeableStateVolatile) {
            // The contents are now undefined; the frame pipeline must restore NonVolatile before
            // writing to (or reading from) it again.
            state.interpolatedTexturePurged = YES;
        }
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
