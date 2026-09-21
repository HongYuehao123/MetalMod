#pragma once

#include "metalmod_types.h"
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define METALMOD_API __attribute__((visibility("default")))

/**
 * Allocate a buffer backed by Apple Silicon Unified Memory (MTLBuffer with MTLResourceStorageModeShared
 * and MTLResourceCPUCacheModeWriteCombined), aligned to Apple Silicon hardware page boundary (16 KB).
 *
 * @param size Requested allocation size in bytes.
 * @return Raw pointer to shared memory buffer, or NULL on failure.
 */
METALMOD_API void* metalmod_uma_alloc(size_t size);

/**
 * Free a buffer previously allocated with metalmod_uma_alloc.
 *
 * @param ptr Pointer returned by metalmod_uma_alloc.
 */
METALMOD_API void metalmod_uma_free(void* ptr);

/**
 * Perform conservative memory reclamation.
 * Tells the macOS Mach VM that idle freed memory pages can be reclaimed via madvise(MADV_FREE_REUSABLE),
 * and marks unused transient scratch textures MTLPurgeableStateVolatile.
 * Never evicts active or in-use gameplay buffers.
 */
METALMOD_API void metalmod_uma_purge_idle(void);

/**
 * Callback function type for memory pressure notifications.
 * @param level 0 = Normal, 1 = Warning, 2 = Critical
 */
typedef void (*MetalModMemoryPressureCallback)(int32_t level);

/**
 * Initialize the Grand Central Dispatch kernel memory pressure listener.
 * Listens to DISPATCH_SOURCE_TYPE_MEMORYPRESSURE.
 *
 * @param callback Function pointer invoked when macOS kernel signals a memory state change.
 * @return 0 on success, non-zero on failure.
 */
METALMOD_API int metalmod_memory_pressure_init(MetalModMemoryPressureCallback callback);

/**
 * Retrieve real-time Apple Silicon Unified Memory and Mach VM telemetry.
 *
 * @param outTelemetry Pointer to MetalModMemoryTelemetry struct to populate.
 */
METALMOD_API void metalmod_get_memory_telemetry(MetalModMemoryTelemetry* outTelemetry);

#ifdef __cplusplus
}
#endif
