#pragma once
#include "metalmod_metal.h"
#ifdef __cplusplus
extern "C" {
#endif
// Render-thread only. Register the Spatial world attachments; null disables. Engine textures
// remain single-sampled and readable. Companions resolve after every render pass.
MMM_API int32_t mmm_msaa_world(void* color, void* depth);
MMM_API int32_t mmm_msaa_pipeline(void* pipeline, int32_t coverageBufferIndex);
#ifdef __cplusplus
}
#ifdef __OBJC__
#import <Metal/Metal.h>
// Internal hooks: prepare seeds LOAD attachments from their current resolved values, so utility
// clears, depth copies and post-processing writes are visible to the next raster pass.
bool mmm_msaa_prepare(id<MTLCommandBuffer> buffer, MTLRenderPassDescriptor* pass);
void mmm_msaa_written(id<MTLTexture> texture);
void mmm_msaa_forget(id<MTLTexture> texture);
void mmm_msaa_tag(id<MTLRenderCommandEncoder> encoder, bool enabled);
bool mmm_msaa_encoder(id<MTLRenderCommandEncoder> encoder);
#endif
#endif
