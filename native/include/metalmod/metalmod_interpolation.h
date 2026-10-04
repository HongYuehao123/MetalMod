#ifndef METALMOD_INTERPOLATION_H
#define METALMOD_INTERPOLATION_H
#include <stdbool.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Offline Phase 7C ABI v1. Render-thread owned, ordinary MTLCommandBuffer API.
 * Linear RGBA16F world colour (current/previous/output at output size), Depth32F
 * and RG16F motion at input size. Motion points current -> previous, top-left
 * pixels, without jitter; scale converts input pixels to output pixels.
 * Optional native RGBA16F premultiplied UI overlay; NULL means world-only.
 * Output must be private; query required usage bits before allocation.
 * Caller keeps input snapshots immutable until GPU completion, commits successful
 * encodes in call order on one queue, and retires the handle if a CB is discarded.
 * No presentation, simulation, light publication or scene-history advancement.
 */
bool mmm_fx_interpolation_supported(void* device);
void* mmm_fx_interpolation_create(void* device, int32_t iw, int32_t ih, int32_t ow, int32_t oh);
void mmm_fx_interpolation_release(void* handle);
bool mmm_fx_interpolation_healthy(void* handle);
/* Roles: 0 colour (also previous), 1 depth, 2 motion, 3 UI, 4 output. */
int64_t mmm_fx_interpolation_texture_usage(void* handle, int32_t role);
/* Result: 0 encoded/display eligible; 1 encoded reset/prime/warmup (do not display);
 * -1 NULL, -2 failed generation, -3 resource/queue, -4 scalar metadata,
 * -5 encode exception (discard CB/retire), -6 stale or mismatched frame pair.
 * IDs strictly increase, including resets; previousId matches last currentId
 * unless reset. First use resets automatically. The reset encode and the first two
 * consecutive pairs after it are not display eligible. Jitter is in input pixels;
 * dt in seconds, near/far positive physical distances, vertical FOV in degrees.
 */
int32_t mmm_fx_interpolation_encode(void* handle, void* cb, void* current, void* previous,
    void* depth, void* motion, void* ui, void* output, uint64_t previousId, uint64_t currentId,
    float dt, float nearPlane, float farPlane, float fovDegrees, float jitterX, float jitterY,
    bool depthReversed, bool reset);
/* ui is the current world+native hand/HUD composite; MetalFX removes/recomposites UI. */
int32_t mmm_fx_interpolation_encode_composited(void* handle, void* cb, void* current, void* previous,
    void* depth, void* motion, void* ui, void* output, uint64_t previousId, uint64_t currentId,
    float dt, float nearPlane, float farPlane, float fovDegrees, float jitterX, float jitterY,
    bool depthReversed, bool reset);
#ifdef __cplusplus
}
#endif
#endif
