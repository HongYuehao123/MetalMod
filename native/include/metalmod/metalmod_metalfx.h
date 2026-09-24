#pragma once

// MetalFX substrate for the MetalMod renderer backend (ROADMAP Phase 7).
//
// MetalMod owns the device, the render targets and the presentation queue, so a MetalFX effect is an
// ordinary pass over our own textures: there is no interop, no foreign swapchain and no second queue
// to synchronise. This surface exposes exactly the effects the backend encodes, one opaque handle
// each, and it never allocates a Minecraft resource.
//
// The Java side owns the render targets and the sizes; this layer owns the MTLFX objects and their
// encoder. Every entry point is safe with NULL and reports failure through its return value rather
// than by crashing - a fault here takes the game down with it.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MMM_FX_API __attribute__((visibility("default")))

// ---------------------------------------------------------------------------------------------
// MetalFX spatial upscaling (Phase 7A)
// ---------------------------------------------------------------------------------------------

/// Whether the device can run a spatial scaler with the given pixel formats.
///
/// Kept as a query rather than an assumption because MetalFX capabilities differ per GPU and per OS,
/// and the backend has to fall back to its own blit rather than fail when the answer is no.
/// `colorFormat`/`outputFormat` are MTLPixelFormat raw values supplied by the Java side, so the
/// format mapping table stays in one place (MetalFormat).
MMM_FX_API bool mmm_fx_spatial_supported(void* device, int64_t colorFormat, int64_t outputFormat);

/// Create a spatial scaler. Returns NULL when MetalFX rejects the configuration.
///
/// `inputWidth`/`inputHeight` are the rendered (low) resolution, `outputWidth`/`outputHeight` the
/// native resolution it is presented at. `colorProcessingMode` is an
/// MTLFXSpatialScalerColorProcessingMode raw value: 0 perceptual (sRGB-encoded inputs), 1 linear.
MMM_FX_API void* mmm_fx_spatial_create(void* device,
                                       int64_t colorFormat, int64_t outputFormat,
                                       int32_t inputWidth, int32_t inputHeight,
                                       int32_t outputWidth, int32_t outputHeight,
                                       int32_t colorProcessingMode);
MMM_FX_API void mmm_fx_spatial_release(void* scaler);

/// Human-readable description of the last MetalFX failure. Never NULL; empty after a success.
MMM_FX_API const char* mmm_fx_last_error(void);

/// Encode one spatial upscale into `commandBuffer`, reading `colorTexture` and writing
/// `outputTexture`.
///
/// `colorContentWidth`/`colorContentHeight` are the region of the input actually filled this frame;
/// they exist so a window resize can letterbox an in-flight frame instead of stretching it. Pass 0
/// for either to mean "the whole input texture".
///
/// Returns 0 on success. This does not commit the command buffer: the caller owns the buffer and its
/// ordering, which is what keeps the upscale behind the passes that wrote the input.
MMM_FX_API int mmm_fx_spatial_encode(void* scaler, void* commandBuffer,
                                     void* colorTexture, void* outputTexture,
                                     int32_t colorContentWidth, int32_t colorContentHeight);

/// Run one spatial upscale as a self-contained step on `queue`.
///
/// This is the entry point the renderer uses. It creates its own command buffer, encodes the
/// upscale, commits it and releases it - so the upscale is one call from the frame's point of view
/// and lands on the same queue as the passes that wrote its input, which is what orders it behind
/// them. Creating the buffer here rather than in Java is deliberate: the engine's command encoder
/// owns whatever it has already recorded, and reaching into it to add a foreign pass would lose the
/// engine's own submission.
///
/// Returns 0 on success. `sourceTexture` and `targetTexture` are MTLTexture handles.
MMM_FX_API int mmm_fx_spatial_run(void* scaler, void* queue,
                                  void* sourceTexture, void* targetTexture);

/// The input/output usage bits MetalFX requires of the textures it is handed.
///
/// Exposed so the Java side can create a render target that satisfies the scaler instead of
/// discovering the mismatch as a validation failure at encode time. Returns false with `*outUnused`
/// untouched when the scaler is NULL.
MMM_FX_API bool mmm_fx_spatial_texture_usage(void* scaler,
                                             uint32_t* outColorUsage, uint32_t* outOutputUsage);

// ---------------------------------------------------------------------------------------------
// MetalFX temporal upscaling (Phase 7B)
// ---------------------------------------------------------------------------------------------

/// Whether the device can run a temporal scaler with the given pixel formats.
MMM_FX_API bool mmm_fx_temporal_supported(void* device,
                                          int64_t colorFormat, int64_t depthFormat,
                                          int64_t motionFormat, int64_t outputFormat);

/// Create a temporal scaler. `depthFormat` and `motionFormat` are MTLPixelFormat raw values.
///
/// `depthReversed` describes the depth buffer the caller will hand the scaler: true when 0 is the
/// far plane. Minecraft's projection maps near to 0 and far to 1, so the backend passes false.
///
/// `dynamicResolution`, `reactiveMask` and `jitteredMotion` are decisions MetalFX only accepts on
/// the descriptor, so they are creation arguments rather than setters: a scaler built without them
/// cannot be given them later. `maskFormat` is the reactive mask's MTLPixelFormat and is ignored
/// unless `reactiveMask` is true.
MMM_FX_API void* mmm_fx_temporal_create(void* device,
                                        int64_t colorFormat, int64_t depthFormat,
                                        int64_t motionFormat, int64_t outputFormat,
                                        int32_t inputWidth, int32_t inputHeight,
                                        int32_t outputWidth, int32_t outputHeight,
                                        bool depthReversed,
                                        bool dynamicResolution, float minScale, float maxScale,
                                        bool reactiveMask, int64_t maskFormat,
                                        bool jitteredMotion);

/// What the scaler's descriptor actually accepted, so the caller can report the real configuration
/// instead of the one it asked for. Any out-pointer may be NULL.
MMM_FX_API bool mmm_fx_temporal_describe(void* scaler,
                                         bool* outDepthReversed,
                                         bool* outDynamicResolution,
                                         float* outMinScale, float* outMaxScale,
                                         bool* outReactiveMask, bool* outJitteredMotion);

MMM_FX_API void mmm_fx_temporal_release(void* scaler);

/// Encode one temporal upscale.
///
/// `jitterX`/`jitterY` are the projection jitter the frame was rendered with, in input-texture
/// pixels, and they must be the values the colour and motion textures were produced with - a
/// mismatch shows up as a permanently soft or vibrating image rather than as an error.
///
/// `reset` discards the scaler's history and starts a fresh accumulation, which is what a camera
/// cut, a world change or a resolution change needs. It costs one frame of convergence.
MMM_FX_API int mmm_fx_temporal_encode(void* scaler, void* commandBuffer,
                                      void* colorTexture, void* depthTexture, void* motionTexture,
                                      void* outputTexture,
                                      float jitterX, float jitterY, bool reset);

// ---------------------------------------------------------------------------------------------
// Presentation pacing (Phase 7C groundwork)
// ---------------------------------------------------------------------------------------------

/// Record where the frame the layer last handed out actually landed, and how long after the one
/// before it.
///
/// Call *at acquire time*, passing the texture of the drawable the layer just handed out. Returns the
/// presentation time in seconds on the display's own clock, or 0 when the display has not reported it
/// yet. `outIntervalSeconds`, when non-NULL, receives the gap since the previously reported
/// presentation - the number a pacer is judged by.
///
/// Why not at present time: a `CAMetalDrawable` reports its presentation time only once the display
/// has shown it, so reading it immediately after `presentDrawable:` returns 0 - and the present path
/// transfers ownership of the drawable, so touching it afterwards is a use-after-free. The drawable
/// comes back around the rotation a frame or two later, which is when its time is both set and safe to
/// read.
///
/// This is the measurement a frame-generation pacer is built on and validated against: interpolation
/// and a `CAMetalDisplayLink` only help if the delivered frames land on distinct refreshes, and this
/// is the only place that is stated rather than inferred from a CPU timer.
MMM_FX_API double mmm_present_time(void* drawableTexture, double* outIntervalSeconds);

/// Pacing counters since the last reset, in a fixed order:
///   [0] frames noted, [1] frames with no reported presentation time,
///   [2] intervals within 20% of the previous one (steady),
///   [3] intervals at least 1.5x the previous one (a dropped refresh).
/// Returns the count, or -1 when the destination is too small.
enum MMMPresentMetric {
    MMM_PRESENT_FRAMES, MMM_PRESENT_UNREPORTED, MMM_PRESENT_STEADY, MMM_PRESENT_DROPPED,
    MMM_PRESENT_METRIC_COUNT
};
MMM_FX_API int32_t mmm_present_read_reset(double* out, int32_t count);

// ---------------------------------------------------------------------------------------------
// GPU execution timing
// ---------------------------------------------------------------------------------------------

/// Measure how long a fill of this texture actually takes on the GPU, fenced.
///
/// Returns milliseconds, or a negative value on failure. `passes` repeats the fill inside one command
/// buffer so the measurement is long enough to be above timer noise; the result is per pass.
///
/// This exists because a frame rate on a 60 Hz display cannot say whether the GPU has headroom. The
/// display paces the loop, so "the frame rate did not move" is consistent with both "the GPU is idle"
/// and "the GPU is saturated" - and those two demand opposite decisions about render scaling. Timing
/// the work itself, with the GPU synchronised, separates them.
MMM_FX_API double mmm_gpu_time_fill(void* queue, void* texture, int32_t passes);

/// The same, for a MetalFX spatial upscale into `targetTexture` from `sourceTexture`.
MMM_FX_API double mmm_gpu_time_upscale(void* scaler, void* queue,
                                       void* sourceTexture, void* targetTexture, int32_t passes);

#ifdef __cplusplus
}
#endif
