#pragma once

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#import <QuartzCore/QuartzCore.h>
#import <Cocoa/Cocoa.h>

#include "metalmod/metalmod.h"

// ---------------------------------------------------------------------------------------------
// Presentation ownership
// ---------------------------------------------------------------------------------------------
// On macOS the CAMetalLayer is created by GLFW and belongs to MoltenVK's swapchain. Minecraft
// presents into that layer at the end of every frame - i.e. *after* RenderTarget.blitToScreen,
// which is where MetalMod's frame pipeline is invoked. A drawable presented by MetalMod therefore
// cannot win: the swapchain present always happens afterwards and overwrites it. Presenting into
// the layer additionally races MoltenVK for drawable ownership.
//
// Consequently MetalMod cannot make an upscaled or interpolated frame reach the display until it
// owns presentation end-to-end (Minecraft renders offscreen; MetalMod drives the display). While
// that is false, the frame pipeline stays inert and metalmod_process_frame() returns
// METALMOD_ERR_NO_PRESENTATION, so no GPU time is spent producing an image nobody can see.
//
// Flip to 1 only together with a real pacer that owns the layer.
#define METALMOD_OWNS_PRESENTATION 0

// Frame interpolation must land on a *different* display refresh than the real frame. That
// requires a CAMetalDisplayLink (or equivalent) pacer; without one, encoding an intermediate
// frame only adds cost. Interpolation is skipped unless this is 1.
#define METALMOD_PACER_AVAILABLE 0

// metalmod_process_frame() / metalmod_configure() return codes.
#define METALMOD_OK 0
#define METALMOD_ERR_INVALID_PARAMS (-1)
#define METALMOD_ERR_NO_RUNTIME (-2)
#define METALMOD_ERR_NO_VULKAN_INTEROP (-3)
#define METALMOD_ERR_NO_COLOR_TEXTURE (-4)
#define METALMOD_ERR_MISSING_DEPTH_OR_MOTION (-5)
#define METALMOD_ERR_NO_PRESENTATION (-6)

@interface MetalModState : NSObject {
@public
    MetalModTelemetry _telemetry;
    id<MTLTexture> _historyTextures[2];
    // Real (measured) presentation bookkeeping. Never synthesised from the render rate.
    NSTimeInterval _presentedWindowStart;
    uint64_t _presentedWindowCount;
    NSTimeInterval _lastRenderTimestamp;
}

@property (nonatomic, strong) id<MTLDevice> device;
@property (nonatomic, strong) id<MTLCommandQueue> commandQueue;
@property (nonatomic, weak) CAMetalLayer *metalLayer;
@property (nonatomic, weak) NSView *targetView;

// MoltenVK device registration
@property (nonatomic, assign) VkDevice vkDevice;
@property (nonatomic, assign) PFN_vkExportMetalObjectsEXT exportMetalObjectsFunc;

// Active configuration
@property (nonatomic, assign) MetalModConfig config;

// Serialises pipeline (re)configuration against per-frame encoding. Configuration rebuilds
// MTLTextures and MetalFX scalers, so it must never overlap a frame that is using them.
@property (nonatomic, strong) NSLock *pipelineLock;

// MetalFX objects
@property (nonatomic, strong) id<MTLFXSpatialScaler> spatialScaler;
@property (nonatomic, strong) id<MTLFXTemporalScaler> temporalScaler;
@property (nonatomic, strong) id<MTLFXFrameInterpolator> frameInterpolator;

// Render targets & history textures (Double-buffered ping-pong for zero-copy history)
@property (nonatomic, assign) uint32_t currentHistoryIndex;
@property (nonatomic, strong) id<MTLTexture> upscaledTexture;
@property (nonatomic, strong) id<MTLTexture> prevColorTexture;
@property (nonatomic, strong) id<MTLTexture> interpolatedTexture;

// True when interpolatedTexture has been marked MTLPurgeableStateVolatile and must be
// restored to NonVolatile before it is written again.
@property (nonatomic, assign) BOOL interpolatedTexturePurged;

// UI Compositor pipeline
@property (nonatomic, strong) id<MTLRenderPipelineState> compositePipelineState;
@property (nonatomic, strong) id<MTLRenderPipelineState> passthroughPipelineState;
@property (nonatomic, strong) id<MTLSamplerState> samplerState;

// Telemetry & pacing
@property (nonatomic, assign) MetalModTelemetry telemetry;
@property (nonatomic, assign) uint64_t lastFrameTimestamp;
@property (nonatomic, assign) BOOL hasHistory;

// Frame generation can only reach the display through a pacer that owns presentation.
// The mod does not own the CAMetalLayer (MoltenVK does), so interpolation is currently
// encoded but never presented; see metalmod_pacer.mm.
@property (nonatomic, assign) BOOL frameGenerationActive;

// Diagnostics
@property (nonatomic, assign) NSTimeInterval lastDiagnosticTimestamp;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *diagnosticTimestamps;

// Human-readable pipeline status, published by the Java side and shown in the window title.
// Atomic (default attribute) because it is written on the render thread and read on the main queue.
@property (copy) NSString *pipelineStatusText;

+ (instancetype)sharedState;

- (id<MTLTexture>)exportTextureFromVkImage:(VkImage)image
                                    aspect:(VkImageAspectFlagBits)aspect;

/**
 * Rate-limited diagnostic logging (at most one message every few seconds), so a per-frame
 * failure cannot spam the log or stall the render thread.
 */
- (void)logDiagnostic:(NSString *)message;

/**
 * Verify that a texture exists and uses the pixel format a MetalFX scaler was configured with.
 */
- (BOOL)validateTexture:(id<MTLTexture>)texture
               expected:(MTLPixelFormat)expected
                   role:(NSString *)role
                 scaler:(NSString *)scalerName;

@end

@interface MetalModState (Compositor)
- (BOOL)recreatePipelines;
@end

@interface MetalModState (Pacer)
- (void)processFrameWithColor:(id<MTLTexture>)colorTex
                        depth:(id<MTLTexture>)depthTex
                       motion:(id<MTLTexture>)motionTex
                           ui:(id<MTLTexture>)uiTex
                       params:(const MetalModFrameParams *)params;
@end
