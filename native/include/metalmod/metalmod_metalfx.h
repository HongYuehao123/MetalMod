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

/// Temporal scaler ABI v1, used by the complete gameplay frame coordinator.
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
/// Reusable SDR transfer passes; sdrFormat must be RGBA8Unorm or BGRA8Unorm (not sRGB).
/// Colour conversion is texel-exact, never resizes, filters or changes alpha.
MMM_API void* mmm_fx_temporal_color_create(void* device, int64_t sdrFormat);
MMM_API void mmm_fx_temporal_color_release(void* converter);
MMM_API bool mmm_fx_temporal_color_healthy(void* converter);
/// toLinear: SDR -> RGBA16Float; otherwise RGBA16Float -> SDR with [0,1] clamping.
/// Caller owns textures/command buffer; source ShaderRead and destination RenderTarget usages.
/// 0 success; -1 NULL, -2 failed generation, -3 contract, -4 encoder unavailable, -5 exception.
MMM_API int32_t mmm_fx_temporal_color_encode(void* converter, void* commandBuffer,
                                            void* source, void* destination, bool toLinear);
/// Motion = previous minus current unjittered scene pixel coordinates, top-left Y.
/// Jitter is the MetalFX texture lookup offset in scene pixels; exposure is fixed at 1.
/// depthReversed declares the supplied depth convention. reset discards old history;
/// first use always resets. See docs/phase7/temporal-contract.md for ownership.
/// Returns 0 on complete encoding, -1 null, -2 latched failure, -3 texture/queue
/// contract, -4 invalid jitter, -5 exception. No commands are encoded on -1..-4.
/// Commit successful work on the same ordered queue; if discarded, retire the scaler.
MMM_API int32_t mmm_fx_temporal_encode(void* scaler, void* commandBuffer, void* color,
                                      void* depth, void* motion, void* reactive, void* output,
                                      float jitterX, float jitterY, bool depthReversed, bool reset);
/// Complete ordered scene submission with GPU-owned motion/reactive/history resources.
/// matrices: three column-major float4x4 (previousFromCurrent, inverseCurrentPV, previousPV).
/// objects: count triples of float4 (lower.xyz/prior-valid.w, upper.xyz, previous-current delta.xyz),
/// all positions camera-relative to this frame. Unknown/ambiguous/deformed samples reject history.
MMM_API void* mmm_fx_temporal_frame_create(void* device, int32_t iw, int32_t ih, int32_t ow, int32_t oh, int64_t sdrFormat);
MMM_API void mmm_fx_temporal_frame_release(void* frame);
MMM_API bool mmm_fx_temporal_frame_healthy(void* frame);
/// Borrowed diagnostic textures: 0 motion, 1 reactive, 2 saved depth, 3 linear input, 4 output, 5 raster coverage.
MMM_API void* mmm_fx_temporal_frame_texture(void* frame, int32_t role);
MMM_API int64_t mmm_fx_temporal_frame_gpu_duration_ns(void* frame);
/// jitterX/Y are projection sampling offsets; this helper converts them to MetalFX lookup offsets.
MMM_API int32_t mmm_fx_temporal_frame_encode(void* frame, void* commandBuffer, void* color, void* depth, void* output,
        const float* matrices, const float* objects, int32_t count, float jitterX, float jitterY, bool reset);
#ifdef __cplusplus
}
#endif
