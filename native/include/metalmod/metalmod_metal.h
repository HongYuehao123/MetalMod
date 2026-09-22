#pragma once

// Native Metal substrate for the MetalMod renderer backend.
//
// Minecraft 26.2 abstracts its graphics API behind com.mojang.blaze3d.systems.GpuBackend /
// GpuDeviceBackend. Metal is Objective-C, so Java cannot call it through Panama FFI directly;
// this C surface wraps the Objective-C objects behind opaque handles that Java can hold.
//
// Handles are +1 retained for the caller unless stated otherwise and must be released through the
// matching *_release function. Every function is safe to call with NULL and will report failure
// rather than crash - a backend that faults takes the whole game down with it.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MMM_API __attribute__((visibility("default")))

// ---------------------------------------------------------------------------------------------
// Device
// ---------------------------------------------------------------------------------------------

/// Create the system default Metal device. Returns NULL when no Metal device is available.
MMM_API void* mmm_device_create(void);
MMM_API void  mmm_device_release(void* device);

/// Fill caller-provided buffers with device strings. Returns 0 on success.
MMM_API int mmm_device_info(void* device,
                            char* outName, size_t nameCapacity,
                            char* outVendor, size_t vendorCapacity,
                            char* outDriver, size_t driverCapacity);

/// Device limits. Return 0 when unavailable.
MMM_API int64_t mmm_device_max_texture_size(void* device);
MMM_API int64_t mmm_device_max_buffer_size(void* device);
MMM_API int64_t mmm_device_recommended_working_set(void* device);

/// Whether the device supports the given features. Metal has all of these on Apple silicon, but
/// feature reporting must be honest: the game negotiates against it.
MMM_API bool mmm_device_supports_family(void* device, int32_t family);

// ---------------------------------------------------------------------------------------------
// Command queue
// ---------------------------------------------------------------------------------------------

MMM_API void* mmm_queue_create(void* device);
MMM_API void  mmm_queue_release(void* queue);

// ---------------------------------------------------------------------------------------------
// Textures (standalone, for render targets and tests)
// ---------------------------------------------------------------------------------------------

/// Create a 2D texture. `pixelFormat` is a MTLPixelFormat raw value supplied by the Java side so
/// the format mapping table lives in one place. `storageShared` selects
/// MTLStorageModeShared (CPU-visible, used for readback and staging).
MMM_API void* mmm_texture_create(void* device, int64_t pixelFormat, int32_t width, int32_t height,
                                 bool storageShared, uint32_t usageFlags);
MMM_API void  mmm_texture_release(void* texture);
MMM_API int32_t mmm_texture_width(void* texture);
MMM_API int32_t mmm_texture_height(void* texture);

/// Read a 2D texture back into `out` (must hold rowBytes * height bytes). Blocks.
MMM_API int mmm_texture_read(void* texture, void* out, size_t capacity, size_t rowBytes);

/// General texture creation with explicit mips, layers and texture type.
///
/// `textureType` is an MTLTextureType raw value supplied by the Java side, so the Metal enum
/// mapping lives in one place (`MetalFormat`). For a cube texture pass depthOrLayers = 6; for a
/// 2D array pass the layer count. `storageShared` selects MTLStorageModeShared (CPU-visible).
MMM_API void* mmm_texture_create_full(void* device, int64_t pixelFormat,
                                      int32_t width, int32_t height,
                                      int32_t depthOrLayers, int32_t mipLevels,
                                      int32_t textureType, bool storageShared,
                                      uint32_t usageFlags);

/// Create a mip-level / layer-range view of a texture. `textureType` is an MTLTextureType value.
MMM_API void* mmm_texture_create_view(void* texture, int64_t pixelFormat, int32_t textureType,
                                      int32_t baseMipLevel, int32_t mipLevels,
                                      int32_t baseLayer, int32_t layerCount);

/// Upload `height` rows into a shared-storage texture. Returns 0 on success.
MMM_API int mmm_texture_replace_region(void* texture, int32_t mipLevel, int32_t slice,
                                       int32_t x, int32_t y, int32_t width, int32_t height,
                                       const void* data, size_t bytesPerRow);

/// Read a region back from a shared-storage texture. Returns 0 on success.
MMM_API int mmm_texture_read_region(void* texture, int32_t mipLevel, int32_t slice,
                                    int32_t x, int32_t y, int32_t width, int32_t height,
                                    void* out, size_t capacity, size_t bytesPerRow);

// ---------------------------------------------------------------------------------------------
// Buffers (CPU-visible shared storage)
// ---------------------------------------------------------------------------------------------

/// Block until all previously committed work on the queue has completed. Used before a CPU-side
/// texture readback, which otherwise races the GPU that is still writing that texture.
MMM_API void mmm_queue_synchronize(void* queue);

// ---------------------------------------------------------------------------------------------
// Fences
// ---------------------------------------------------------------------------------------------

/// Create a fence on the queue. Work committed before this call is complete once the fence signals.
MMM_API void* mmm_fence_create(void* queue);
/// Wait for the fence. timeoutNanos <= 0 polls once; a very large value waits indefinitely.
MMM_API bool mmm_fence_wait(void* fence, int64_t timeoutNanos);
MMM_API void mmm_fence_release(void* fence);

MMM_API void*   mmm_buffer_create(void* device, int64_t length);
MMM_API void*   mmm_buffer_contents(void* buffer);
MMM_API int64_t mmm_buffer_length(void* buffer);
MMM_API void    mmm_buffer_release(void* buffer);

// ---------------------------------------------------------------------------------------------
// Samplers
// ---------------------------------------------------------------------------------------------

MMM_API void* mmm_sampler_create(void* device, int32_t addressU, int32_t addressV,
                                 int32_t minFilter, int32_t magFilter, int32_t mipFilter,
                                 int32_t maxAnisotropy, bool hasMaxLod, double maxLod);
MMM_API void  mmm_sampler_release(void* sampler);

// ---------------------------------------------------------------------------------------------
// Clear (render-pass load/store)
// ---------------------------------------------------------------------------------------------

/// Clear a colour and/or depth texture with one render pass. Pass NULL/false to skip an attachment.
/// The texture must have been created with MTLTextureUsageRenderTarget.
MMM_API int mmm_clear_textures(void* queue,
                               void* colorTexture, bool hasColor, float r, float g, float b, float a,
                               void* depthTexture, bool hasDepth, double depthValue);

/// Clear only the given rectangle, leaving everything outside it untouched.
///
/// A Metal render pass clears a whole attachment - the load action ignores the scissor - so a
/// sub-rectangle clear is done with a scissored full-screen triangle. Vulkan's VkClearRect does this
/// natively, so without it the backends disagree. Same attachment rules as mmm_clear_textures.
MMM_API int mmm_clear_textures_region(void* queue,
                                      void* colorTexture, bool hasColor,
                                      float r, float g, float b, float a,
                                      void* depthTexture, bool hasDepth, double depthValue,
                                      int32_t x, int32_t y, int32_t width, int32_t height);

// ---------------------------------------------------------------------------------------------
// Shader libraries, pipelines and drawing (Phase 3)
// ---------------------------------------------------------------------------------------------

/// Vertex buffer layout and attribute records mirror MTLVertexDescriptor. The Java side fills the
/// Metal enum values, because MetalFormat owns that mapping.
typedef struct {
    int32_t bufferIndex;
    int32_t stride;
    int32_t stepFunction;   // MTLVertexStepFunction: 1 = per vertex, 2 = per instance
    int32_t stepRate;
} MMMVertexBufferLayout;

typedef struct {
    int32_t location;
    int32_t bufferIndex;
    int32_t format;         // MTLVertexFormat raw value
    int32_t offset;
} MMMVertexAttribute;

/// Compile an MSL source string into an MTLLibrary. Returns NULL and logs on failure.
MMM_API void* mmm_library_create(void* device, const char* source, size_t length);
MMM_API void  mmm_library_release(void* library);

/// Build a render pipeline state (and its depth-stencil state) from two MSL libraries.
/// Returns an opaque MMMPipeline* or NULL. Colour/depth formats are MTLPixelFormat values; the
/// blend/compare/topology/winding/cull/fill values are Metal enum raw values.
MMM_API void* mmm_render_pipeline_create(
    void* device,
    void* vertexLibrary, const char* vertexFunction,
    void* fragmentLibrary, const char* fragmentFunction,
    int64_t colorFormat, int32_t colorWriteMask, int32_t blendEnabled,
    int32_t blendSrcColor, int32_t blendDstColor, int32_t blendOpColor,
    int32_t blendSrcAlpha, int32_t blendDstAlpha, int32_t blendOpAlpha,
    int64_t depthFormat, int32_t depthCompare, int32_t depthWrite,
    int32_t topology, int32_t winding, int32_t cullMode, int32_t triangleFill,
    float depthBiasScale, float depthBiasConstant,
    const MMMVertexBufferLayout* buffers, int32_t bufferCount,
    const MMMVertexAttribute* attributes, int32_t attributeCount);
MMM_API void mmm_render_pipeline_release(void* pipeline);

/// Human-readable message for the most recent failure from mmm_library_create or
/// mmm_render_pipeline_create. Never NULL; empty when the last call succeeded. The pointer is owned
/// by the library and stays valid until the next call that can fail.
MMM_API const char* mmm_last_error(void);

/// Begin a render pass. colorTextures/colorLoadClear/clearColors are parallel arrays of length
/// colorCount; clearColors holds 4 floats per attachment. Returns an encoder or NULL.
MMM_API void* mmm_render_pass_begin(
    void* commandBuffer,
    int32_t colorCount, void* const* colorTextures,
    const int32_t* colorLoadClear, const float* clearColors,
    void* depthTexture, int32_t depthLoadClear, double depthValue,
    int32_t width, int32_t height);
MMM_API void mmm_render_pass_end(void* encoder);
MMM_API void mmm_render_pass_set_pipeline(void* encoder, void* pipeline);
MMM_API void mmm_render_pass_set_vertex_buffer(void* encoder, void* buffer, int64_t offset, int32_t index);
MMM_API void mmm_render_pass_set_fragment_buffer(void* encoder, void* buffer, int64_t offset, int32_t index);
MMM_API void mmm_render_pass_set_vertex_texture(void* encoder, void* texture, int32_t index);
MMM_API void mmm_render_pass_set_fragment_texture(void* encoder, void* texture, int32_t index);
MMM_API void mmm_render_pass_set_vertex_sampler(void* encoder, void* sampler, int32_t index);
MMM_API void mmm_render_pass_set_fragment_sampler(void* encoder, void* sampler, int32_t index);
MMM_API void mmm_render_pass_set_scissor(void* encoder, int32_t x, int32_t y, int32_t width, int32_t height);
MMM_API void mmm_render_pass_set_viewport(void* encoder, double x, double y, double width, double height);
MMM_API void mmm_render_pass_push_debug_group(void* encoder, const char* label);
MMM_API void mmm_render_pass_pop_debug_group(void* encoder);
MMM_API void mmm_render_pass_draw(void* encoder, int32_t topology, int32_t vertexStart,
                                  int32_t vertexCount, int32_t instanceCount, int32_t firstInstance);
/// Draw a triangle fan. Metal has no fan primitive, so this expands one into an indexed triangle
/// list from a cached, prefix-stable index buffer; `vertexStart` becomes Metal's baseVertex.
MMM_API void mmm_render_pass_draw_fan(void* encoder, int32_t vertexStart, int32_t vertexCount,
                                      int32_t instanceCount, int32_t firstInstance);
MMM_API void mmm_render_pass_draw_indexed(void* encoder, int32_t topology, void* indexBuffer,
                                          int64_t indexBufferOffset, int32_t indexType,
                                          int32_t indexCount, int32_t instanceCount,
                                          int32_t firstIndex, int32_t baseVertex, int32_t firstInstance);

// ---------------------------------------------------------------------------------------------
// Surface (CAMetalLayer)
// ---------------------------------------------------------------------------------------------

/// Adopt the CAMetalLayer already attached to `nsView` (creating one if the view has none), or
/// create a detached layer when `nsView` is NULL. Used by the smoke test with NULL.
MMM_API void* mmm_layer_create(void* nsView);
MMM_API void  mmm_layer_release(void* layer);

/// Set drawable size and vsync. `maximumDrawableCount` of 3 gives the CPU room to run ahead
/// without adding a full frame of latency.
MMM_API int mmm_layer_configure(void* layer, int32_t width, int32_t height, bool vsync);

/// Acquire the next drawable. On success both out-params are set; the drawable is +1 retained and
/// the texture is borrowed from it (do NOT release the texture separately).
/// Returns 0 on success, non-zero on failure.
MMM_API int mmm_layer_acquire(void* layer, void** outDrawable, void** outTexture);

/// Schedule presentation of a previously acquired drawable and release it.
MMM_API void mmm_layer_present(void* layer, void* drawable);

/// Build a CAMetalLayer on the content view of an NSWindow (the pointer GLFWNativeCocoa returns).
/// Used by the renderer backend, which receives a GLFWwindow* and must reach its NSWindow.
MMM_API void* mmm_layer_create_for_ns_window(void* nsWindow);

/// Clear an acquired drawable and present it from a single command buffer. Encoding the clear and
/// the present on one queue in one commit is what makes the ordering correct; two queues there is
/// no ordering and the clear can land after the present. Consumes the retained drawable.
/// Returns 0 on success, non-zero on failure.
MMM_API int mmm_layer_present_clear(void* layer, void* drawable,
                                    float r, float g, float b, float a);

/// Render sourceTexture into the drawable with a built-in full-screen blit pipeline, then present
/// it. Encoding the blit and the present in one command buffer on the present queue is what keeps
/// them ordered. Pass a NULL source to clear instead.
MMM_API int mmm_layer_present_texture(void* layer, void* drawable, void* sourceTexture);

/// Use the renderer's own command queue for the present blit. Command buffers on one queue execute
/// in commit order, so this is what prevents the blit from sampling the render target while the
/// render pass that writes it is still in flight (two queues have no ordering and can deadlock the
/// GPU). Call once with the device queue.
MMM_API void mmm_layer_set_present_queue(void* queue);

// ---------------------------------------------------------------------------------------------
// Command buffers and render passes
// ---------------------------------------------------------------------------------------------

MMM_API void* mmm_command_buffer_create(void* queue);
MMM_API void  mmm_command_buffer_commit(void* commandBuffer);
MMM_API void  mmm_command_buffer_wait(void* commandBuffer);
MMM_API void  mmm_command_buffer_release(void* commandBuffer);

/// Begin a render pass whose single colour attachment is `texture`, cleared to the given colour.
/// Returns an encoder handle, or NULL on failure.
MMM_API void* mmm_begin_clear_pass(void* commandBuffer, void* texture,
                                   float r, float g, float b, float a);

/// End the current encoder. Safe with NULL.
MMM_API void mmm_end_encoding(void* encoder);

#ifdef __cplusplus
}
#endif
