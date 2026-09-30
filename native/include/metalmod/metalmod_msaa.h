#pragma once
#include "metalmod_metal.h"
#ifdef __cplusplus
extern "C" {
#endif
// Render-thread only. Register the Spatial world attachments; null disables. Engine textures
// remain single-sampled and readable. Companions resolve after every render pass.
MMM_API int32_t mmm_msaa_world(void* color, void* depth);
// Preflight/cache the four-sample state; zero succeeds. Configure coverage before preflight/bind.
MMM_API int32_t mmm_msaa_pipeline_ready(void* pipeline);
MMM_API int32_t mmm_msaa_pipeline(void* pipeline, int32_t coverageBufferIndex);
#ifdef __cplusplus
}
#ifdef __OBJC__
#import <Metal/Metal.h>
// Internal hooks: prepare seeds invalidated LOAD attachments from current resolved values.
// Ordinary LOAD passes retain per-sample depth; external clears/copies invalidate companions.
bool mmm_msaa_prepare(id<MTLCommandBuffer> buffer, MTLRenderPassDescriptor* pass);
void mmm_msaa_written(id<MTLTexture> texture);
void mmm_msaa_forget(id<MTLTexture> texture);
void mmm_msaa_tag(id<MTLRenderCommandEncoder> encoder, bool enabled);
bool mmm_msaa_encoder(id<MTLRenderCommandEncoder> encoder);
#endif
#endif
