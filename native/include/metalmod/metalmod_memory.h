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
METALMOD_API void* metalmod_uma_calloc(size_t num, size_t size);

/**
 * Resize a UMA buffer.
 *
 * Only pointers returned by metalmod_uma_alloc/calloc/aligned_alloc (i.e. pointers for which
 * metalmod_uma_owns() returns true) may be passed here. Passing any other pointer returns NULL
 * without allocating, so that the caller can fall back to its own allocator instead of silently
 * losing the previous contents.
 */
METALMOD_API void* metalmod_uma_realloc(void* ptr, size_t newSize);
METALMOD_API void metalmod_uma_free(void* ptr);
METALMOD_API void* metalmod_uma_aligned_alloc(size_t alignment, size_t size);
METALMOD_API void metalmod_uma_aligned_free(void* ptr);

/**
 * Query whether a pointer was handed out by this UMA pool and is still live.
 *
 * A custom allocator must route free()/realloc() by ownership: pointers that predate the pool
 * (or were allocated as a fallback) must go back to the allocator that produced them.
 *
 * @return true if ptr is a live UMA allocation, false otherwise.
 */
METALMOD_API bool metalmod_uma_owns(const void* ptr);

/**
 * Report the usable size of a live UMA allocation.
 *
 * Needed to copy exactly the live bytes when a caller has to migrate a block to another
 * allocator; reading a fixed byte count could run past the end of the old block.
 *
 * @return the allocation length in bytes, or 0 if ptr is not a live UMA allocation.
 */
METALMOD_API size_t metalmod_uma_size(const void* ptr);

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
