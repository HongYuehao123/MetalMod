#pragma once

#include "metalmod_types.h"
#include "vulkan/vulkan_metal.h"

#ifdef __cplusplus
extern "C" {
#endif

#define METALMOD_API __attribute__((visibility("default")))

/**
 * Initialize MetalMod runtime.
 * @param nsWindowHandle Pointer to Cocoa NSWindow or NSView obtained via GLFW glfwGetCocoaWindow.
 * @return 0 on success, non-zero error code otherwise.
 */
METALMOD_API int metalmod_init(void* nsWindowHandle);

/**
 * Shut down MetalMod runtime, releasing Metal resources and display link.
 */
METALMOD_API void metalmod_shutdown(void);

/**
 * Apply or update pipeline configuration (resolutions, scaler mode, frame gen toggle).
 */
METALMOD_API int metalmod_configure(const MetalModConfig* config);

/**
 * Register active Vulkan Device & MoltenVK export function pointer.
 */
METALMOD_API int metalmod_register_vulkan_device(VkDevice device, PFN_vkExportMetalObjectsEXT exportFunc);

/**
 * Core per-frame processing function.
 * Called at the end of the frame (interception of vkQueuePresentKHR).
 * 
 * @param colorImage Vulkan VkImage containing low-res rendered 3D world.
 * @param depthImage Vulkan VkImage containing depth buffer (optional for spatial, required for temporal/frame gen).
 * @param motionImage Vulkan VkImage containing 2D motion vectors (optional for spatial, required for temporal/frame gen).
 * @param uiImage Vulkan VkImage containing 100% native-res UI/HUD overlay (optional, nil if rendered directly).
 * @param params Frame parameters including delta time, jitter, and camera planes.
 * @return 0 on success.
 */
METALMOD_API int metalmod_process_frame(
    VkImage colorImage,
    VkImage depthImage,
    VkImage motionImage,
    VkImage uiImage,
    const MetalModFrameParams* params
);

/**
 * Retrieve real-time performance telemetry.
 */
METALMOD_API void metalmod_get_telemetry(MetalModTelemetry* outTelemetry);

/**
 * Update macOS window title directly with active MetalMod resolution and mode.
 */
METALMOD_API void metalmod_update_window_title(void);

/**
 * Capabilities queries.
 */
METALMOD_API bool metalmod_is_spatial_scaler_supported(uint32_t inW, uint32_t inH, uint32_t outW, uint32_t outH);
METALMOD_API bool metalmod_is_temporal_scaler_supported(uint32_t inW, uint32_t inH, uint32_t outW, uint32_t outH);
METALMOD_API bool metalmod_is_frame_gen_supported(uint32_t width, uint32_t height);

#ifdef __cplusplus
}
#endif
