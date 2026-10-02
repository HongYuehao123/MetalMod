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
/// Latest completed AA+FX+copy submission duration; asynchronous, may lag the current frame.
/// Returns -1 before completion, after errors, for plain recovery or unavailable timestamps.
/// This is not total frame GPU time; callers must not sum it with overlapping buffer spans.
MMM_API int64_t mmm_fx_spatial_gpu_duration_ns(void* scaler);

/// Temporal prototype ABI v1, isolated from the active spatial coordinator.
/// Fixed linear RGBA16Float colour/output, Depth32Float depth, RG16Float motion,
/// R8Unorm reactive mask; all inputs are scene-sized, output is private storage.
MMM_API bool mmm_fx_temporal_supported(void* device);
MMM_API void* mmm_fx_temporal_create(void* device, int32_t inputWidth, int32_t inputHeight,
                                    int32_t outputWidth, int32_t outputHeight);
MMM_API void mmm_fx_temporal_release(void* scaler);
MMM_API bool mmm_fx_temporal_healthy(void* scaler);
/// Minimum texture usage bits, role 0 colour / 1 depth / 2 motion / 3 reactive / 4 output.
/// Returns -1 for NULL/invalid role. Additional usage bits are allowed.
MMM_API int64_t mmm_fx_temporal_texture_usage(void* scaler, int32_t role);
/// Motion = previous minus current unjittered scene pixel coordinates, top-left Y.
/// Jitter is the current sampling offset in scene pixels; exposure is fixed at 1.
/// depthReversed declares the supplied depth convention. reset discards old history;
/// first use always resets. See docs/phase7/temporal-contract.md for ownership.
/// Returns 0 on complete encoding, -1 null, -2 latched failure, -3 texture/queue
/// contract, -4 invalid jitter, -5 exception. No commands are encoded on -1..-4.
/// Commit successful work on the same ordered queue; if discarded, retire the scaler.
MMM_API int32_t mmm_fx_temporal_encode(void* scaler, void* commandBuffer, void* color,
                                      void* depth, void* motion, void* reactive, void* output,
                                      float jitterX, float jitterY, bool depthReversed, bool reset);
#ifdef __cplusplus
}
#endif
