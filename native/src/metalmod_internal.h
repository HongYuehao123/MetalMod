#pragma once

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#import <QuartzCore/QuartzCore.h>
#import <Cocoa/Cocoa.h>

#include "metalmod/metalmod.h"

@interface MetalModState : NSObject {
@public
    MetalModTelemetry _telemetry;
    id<MTLTexture> _historyTextures[2];
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

// MetalFX objects
@property (nonatomic, strong) id<MTLFXSpatialScaler> spatialScaler;
@property (nonatomic, strong) id<MTLFXTemporalScaler> temporalScaler;
@property (nonatomic, strong) id<MTLFXFrameInterpolator> frameInterpolator;

// Render targets & history textures (Double-buffered ping-pong for zero-copy history)
@property (nonatomic, assign) uint32_t currentHistoryIndex;
@property (nonatomic, strong) id<MTLTexture> upscaledTexture;
@property (nonatomic, strong) id<MTLTexture> prevColorTexture;
@property (nonatomic, strong) id<MTLTexture> interpolatedTexture;

// UI Compositor pipeline
@property (nonatomic, strong) id<MTLRenderPipelineState> compositePipelineState;
@property (nonatomic, strong) id<MTLRenderPipelineState> passthroughPipelineState;
@property (nonatomic, strong) id<MTLSamplerState> samplerState;

// Telemetry & pacing
@property (nonatomic, assign) MetalModTelemetry telemetry;
@property (nonatomic, assign) uint64_t lastFrameTimestamp;
@property (nonatomic, assign) BOOL hasHistory;

+ (instancetype)sharedState;

- (id<MTLTexture>)exportTextureFromVkImage:(VkImage)image
                                    aspect:(VkImageAspectFlagBits)aspect;

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
