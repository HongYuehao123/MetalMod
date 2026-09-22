#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Apple Silicon unified-memory telemetry.
 *
 * The MetalFX scaling/preset/frame-parameter structs that used to live here belonged to the retired
 * MoltenVK-interop frame pipeline (see ROADMAP.md §4) and were removed with it. What remains is the
 * UMA/memory telemetry the F3 overlay reads.
 */
typedef struct MetalModMemoryTelemetry {
    uint64_t totalPhysicalMemoryBytes;
    uint64_t availableMemoryBytes;
    uint64_t compressedMemoryBytes;
    uint64_t swapUsedBytes;
    uint64_t processResidentBytes;
    uint64_t metalAllocatedBytes;
    uint64_t metalMaxWorkingSetBytes;
    int32_t memoryPressureLevel; // 0 = Normal, 1 = Warning, 2 = Critical
    int32_t reserved;            // 8-byte alignment padding
} MetalModMemoryTelemetry;

#ifdef __cplusplus
}
#endif
