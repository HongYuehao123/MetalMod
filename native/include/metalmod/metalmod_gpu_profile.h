#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Optional completed render-stage workload diagnostics. These are NOT whole-frame GPU times:
// stages/passes can overlap and completions can arrive several CPU frames after submission.
// Layout: valid passes, vertex ns, fragment ns, dropped passes, invalid passes.
// Durations are -1 until a valid pass completes. Reads never wait for GPU work.
enum { MMM_GPU_PROFILE_METRIC_COUNT = 5 };
void mmm_gpu_profile_set_enabled(bool enabled);
int32_t mmm_gpu_profile_read_reset(int64_t* out, int32_t count);
bool mmm_gpu_profile_supported(void* device);

#ifdef __cplusplus
}

#ifdef __OBJC__
void mmm_gpu_profile_attach(id<MTLCommandBuffer> buffer, MTLRenderPassDescriptor* descriptor);
void mmm_gpu_profile_commit(id<MTLCommandBuffer> buffer);
#endif
#endif
