#pragma once

// Render-resolution motion vectors for the MetalFX temporal scaler (ROADMAP Phase 7B).
//
// A temporal upscaler reconstructs each output pixel from where the corresponding input pixels were
// in previous frames, and it cannot derive that itself: MetalFX documents that its depth reprojection
// describes camera motion but not independently moving objects, and it requires a motion texture on
// the descriptor. This surface produces that texture.
//
// What it produces is the *camera* half of the motion field: for every pixel, the level's depth is
// turned back into a camera-relative world position and reprojected through the previous frame's
// view-projection. That is exact for static geometry and for a moving camera, which is the majority
// of the frame. It is not exact for geometry that moves independently of the camera - a mob, a
// particle, an animated block - and those pixels are the named gap that Phase 8C's previous-transform
// scene contract fills. Nothing here guesses at them; a wrong vector is worse than none, and a
// camera-only producer is honest about which half it covers.
//
// This layer owns only the motion texture and its kernel. The matrices come from the Java side, which
// is where the engine's camera and projection state lives; the queue belongs to the caller, which is
// what keeps the dispatch ordered behind the passes that wrote the depth buffer.
//
// Every entry point is safe with NULL and reports failure through its return value rather than by
// crashing - a fault here takes the game down with it.

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MMM_MOTION_API __attribute__((visibility("default")))

/// Create the motion resource: one RG16Float texture at the render resolution.
///
/// The texture is the format MetalFX expects for motion vectors and is created with the usage bits
/// its descriptor asks for (shader read) plus the shader-write bit this layer's kernel needs. It is
/// sized to the *input* (render) resolution, which is what `motionVectorScaleX/Y = 1.0` means.
///
/// Returns NULL when the device is nil or the size is not positive; the reason is available from
/// mmm_motion_last_error().
MMM_MOTION_API void* mmm_motion_create(void* device, int32_t width, int32_t height);

MMM_MOTION_API void mmm_motion_release(void* motion);

/// The texture a temporal scaler is handed as its motion texture.
MMM_MOTION_API void* mmm_motion_texture(void* motion);

MMM_MOTION_API int32_t mmm_motion_width(void* motion);
MMM_MOTION_API int32_t mmm_motion_height(void* motion);

/// Human-readable description of the last failure. Never NULL; empty after a success.
MMM_MOTION_API const char* mmm_motion_last_error(void);

/// Reconstruct one frame of motion vectors on `queue`, reading `depthTexture`.
///
/// `currentInverseViewProjection` and `previousViewProjection` are 16 floats each in column-major
/// order, matching `org.joml.Matrix4f.get(float[])` and MSL's own `float4x4` layout, so no transpose
/// happens on either side of the boundary.
///
/// Both matrices act on *camera-relative world space*: the current one is the inverse of
/// `projection * view` and the previous one already carries the camera translation between the two
/// frames, so the kernel needs no camera positions of its own. They are unjittered - MetalFX's
/// `jitterOffsetX/Y` carries the sub-pixel offset separately - so a caller that jitters its
/// projection must remove the jitter before handing the matrices over.
///
/// The motion convention is the one MetalFX documents for `motionVectorScaleX/Y = 1.0`: a value in
/// input-texture pixels, pointing from this pixel to where its surface was in the previous frame, in
/// a top-left fragment coordinate system. An object that moved down and right in the image therefore
/// carries a negative vector, which is what the scaler expects.
///
/// Returns 0 on success. The dispatch is committed on its own command buffer, so it lands behind the
/// passes that wrote the depth in commit order without this side reaching into the engine's encoder.
MMM_MOTION_API int mmm_motion_run(void* motion, void* queue, void* depthTexture,
                                  const float* currentInverseViewProjection,
                                  const float* previousViewProjection);

/// Whether the depth texture this run was handed was a depth format the kernel can read.
///
/// Kept as a query because a mistaken format is a bind-time validation failure, and the backend
/// would rather report it once than discover it as a lost frame.
MMM_MOTION_API bool mmm_motion_depth_format_supported(void* device, int64_t depthFormat);

/// One-line summary of the last run: pixels dispatched, and why nothing ran. For the F3 section.
MMM_MOTION_API const char* mmm_motion_describe(void* motion);

#ifdef __cplusplus
}
#endif
