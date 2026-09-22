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

MMM_API void*   mmm_buffer_create(void* device, int64_t length);
MMM_API void*   mmm_buffer_contents(void* buffer);
MMM_API int64_t mmm_buffer_length(void* buffer);
MMM_API void    mmm_buffer_release(void* buffer);

// ---------------------------------------------------------------------------------------------
// Samplers
// ---------------------------------------------------------------------------------------------

MMM_API void* mmm_sampler_create(void* device, int32_t addressU, int32_t addressV,
                                 int32_t minFilter, int32_t magFilter,
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
