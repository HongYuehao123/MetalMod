#pragma once
#include "metalmod_metal.h"
#ifdef __cplusplus
extern "C" {
#endif
/// Backend-owned spatial reference, SDR perceptual colour. All handles are caller-retained.
MMM_API bool mmm_fx_spatial_supported(void* device);
/// Returns NULL on unsupported device, invalid dimensions/format or failed allocation.
MMM_API void* mmm_fx_spatial_create(void* device, int32_t inputWidth, int32_t inputHeight,
                                   int32_t outputWidth, int32_t outputHeight, int64_t format);
MMM_API void mmm_fx_spatial_release(void* scaler);
/// Render-thread-only diagnostic A/B control. Edge-aware input AA is enabled by default.
/// Does not affect plain recovery, and never modifies the source texture.
MMM_API void mmm_fx_spatial_set_antialias(void* scaler, bool enabled);
/// Encode into the renderer's ordered command buffer. Returns 0 only after complete encoding.
/// The output must have the descriptor's format/size; input must have shader-read usage.
/// plainScale is the prevalidated same-frame recovery path, never a second release upscaler.
/// GPU errors are latched and reported by subsequent calls; no same-frame GPU recovery is claimed.
MMM_API int32_t mmm_fx_spatial_encode(void* scaler, void* commandBuffer,
                                     void* source, void* destination, bool plainScale);
MMM_API bool mmm_fx_spatial_healthy(void* scaler);
#ifdef __cplusplus
}
#endif
