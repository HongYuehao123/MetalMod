#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Edge-aware AA of the completed world image. The source is restored in place before the HUD draws.
// scratch must be a distinct RGBA8 texture of the same size with render-target usage.
// Returns 0 when the pass was submitted; errors leave source untouched.
__attribute__((visibility("default"))) int mmm_aa_run(void* device, void* queue,
        void* source, void* scratch);

#ifdef __cplusplus
}
#endif
