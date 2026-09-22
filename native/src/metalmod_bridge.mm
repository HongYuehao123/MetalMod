#import "metalmod_internal.h"
#include <mach/mach_time.h>

// Apple GPUs support 16384x16384 textures; anything above that is guaranteed to fail and is
// treated as an invalid capability query rather than being silently reported as "supported".
static const uint32_t kMetalModMaxTextureDimension = 16384;

// A distinct diagnostic message is logged immediately the first time it occurs, then at most
// once per this interval. Prevents per-frame failures from flooding the log.
static const NSTimeInterval kMetalModDiagnosticRepeatIntervalSeconds = 60.0;

@implementation MetalModState

+ (instancetype)sharedState {
    static MetalModState *instance = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[MetalModState alloc] init];
    });
    return instance;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _device = MTLCreateSystemDefaultDevice();
        if (_device) {
            _commandQueue = [_device newCommandQueue];
        }
        _pipelineLock = [[NSLock alloc] init];
        _hasHistory = NO;
        _frameGenerationActive = NO;
        _interpolatedTexturePurged = NO;
        _lastDiagnosticTimestamp = 0.0;
        _diagnosticTimestamps = [NSMutableDictionary dictionary];
        _lastFrameTimestamp = mach_absolute_time();
        _presentedWindowStart = CACurrentMediaTime();
        _presentedWindowCount = 0;
        _lastRenderTimestamp = 0.0;
        memset(&_telemetry, 0, sizeof(MetalModTelemetry));
        memset(&_config, 0, sizeof(MetalModConfig));
    }
    return self;
}

- (void)logDiagnostic:(NSString *)message {
    NSTimeInterval now = CACurrentMediaTime();

    // A recurring per-frame failure must not spam the log. Each distinct message is reported
    // immediately the first time, then at most once per repeat interval.
    NSNumber *previous = self.diagnosticTimestamps[message];
    if (previous != nil && (now - previous.doubleValue) < kMetalModDiagnosticRepeatIntervalSeconds) {
        return;
    }
    self.diagnosticTimestamps[message] = @(now);
    NSLog(@"[MetalMod] %@", message);
}

- (id<MTLTexture>)exportTextureFromVkImage:(VkImage)image
                                    aspect:(VkImageAspectFlagBits)aspect {
    if (!image) return nil;

    // A VkImage is an opaque driver handle. It can only be turned into an id<MTLTexture> through
    // VK_EXT_metal_objects with a registered VkDevice and its vkExportMetalObjectsEXT pointer.
    //
    // This method previously fell back to `(__bridge id<MTLTexture>)image` when interop was not
    // registered. That reinterprets an arbitrary integer handle as an Objective-C object and
    // crashes the process (verified: SIGSEGV in objc_retain). Refuse instead.
    if (!self.vkDevice || !self.exportMetalObjectsFunc) {
        [self logDiagnostic:@"Vulkan interop is not registered; call "
                             "metalmod_register_vulkan_device() with the live VkDevice and "
                             "vkExportMetalObjectsEXT before supplying VkImage handles."];
        return nil;
    }

    // VkExportMetalTextureInfoEXT::plane accepts only COLOR / PLANE_0..2. Depth and stencil
    // aspects are not planes and must not be passed here; the exported MTLTexture carries its
    // own pixel format.
    (void)aspect;

    VkExportMetalTextureInfoEXT textureInfo = {};
    textureInfo.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_TEXTURE_INFO_EXT;
    textureInfo.pNext = nullptr;
    textureInfo.image = image;
    textureInfo.imageView = VK_NULL_HANDLE;
    textureInfo.bufferView = VK_NULL_HANDLE;
    textureInfo.plane = VK_IMAGE_ASPECT_COLOR_BIT;
    textureInfo.mtlTexture = nullptr;

    VkExportMetalObjectsInfoEXT exportInfo = {};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT;
    exportInfo.pNext = &textureInfo;

    self.exportMetalObjectsFunc(self.vkDevice, &exportInfo);

    id<MTLTexture> texture = (__bridge id<MTLTexture>)textureInfo.mtlTexture;
    if (!texture) {
        [self logDiagnostic:@"vkExportMetalObjectsEXT returned no MTLTexture. The VkImage was "
                             "probably not created with VkExportMetalObjectCreateInfoEXT "
                             "(VK_EXPORT_METAL_OBJECT_TYPE_METAL_TEXTURE_BIT_EXT)."];
    }
    return texture;
}

/**
 * A MetalFX scaler is only usable if the textures handed to it use the exact pixel formats the
 * descriptor was configured with. MoltenVK textures are created by the Vulkan driver, so the
 * format must be reconciled at runtime instead of assumed.
 */
- (BOOL)validateTexture:(id<MTLTexture>)texture
             expected:(MTLPixelFormat)expected
                 role:(NSString *)role
               scaler:(NSString *)scalerName {
    if (!texture) {
        [self logDiagnostic:[NSString stringWithFormat:@"%@ requires a %@ texture but none was "
                             @"supplied; skipping this frame.", scalerName, role]];
        return NO;
    }
    if (texture.pixelFormat != expected) {
        [self logDiagnostic:[NSString stringWithFormat:@"%@ %@ texture format is %lu but the "
                             @"scaler was configured for %lu; skipping this frame.",
                             scalerName, role, (unsigned long)texture.pixelFormat,
                             (unsigned long)expected]];
        return NO;
    }
    return YES;
}

@end

#pragma mark - C API Implementations

extern "C" {

int metalmod_init(void* nsWindowHandle) {
    // MoltenVK reads MVK_CONFIG_* once, at first use. Setting them here only has an effect if
    // MoltenVK has not initialised yet; to guarantee they apply, pass them as launch environment
    // variables instead (e.g. -DMVK_CONFIG_PREFILL_METAL_COMMAND_BUFFERS=1).
    //
    // MVK_CONFIG_HOST_COHERENT_MEMORY_FLUSH_MODE is deliberately NOT overridden: disabling
    // automatic flushes can drop writes to host-coherent memory and corrupt data.
    setenv("MVK_CONFIG_PREFILL_METAL_COMMAND_BUFFERS", "1", 1);
    setenv("MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS", "1", 1);
    setenv("MVK_CONFIG_RESUME_LOST_DEVICE", "1", 1);

    MetalModState *state = [MetalModState sharedState];
    if (!state.device || !state.commandQueue) {
        return -2;
    }

    NSWindow *window = nil;
    NSView *view = nil;

    if (nsWindowHandle) {
        id targetObj = (__bridge id)nsWindowHandle;
        if ([targetObj isKindOfClass:[NSWindow class]]) {
            window = (NSWindow *)targetObj;
            view = [window contentView];
        } else if ([targetObj isKindOfClass:[NSView class]]) {
            view = (NSView *)targetObj;
        }
    }

    // Auto-discover window via NSApp if not explicitly passed
    if (!view) {
        window = [NSApp keyWindow] ?: [NSApp mainWindow];
        if (!window && [[NSApp windows] count] > 0) {
            window = [[NSApp windows] firstObject];
        }
        if (window) {
            view = [window contentView];
        }
    }

    if (view) {
        state.targetView = view;
    }

    // Find an existing CAMetalLayer. NOTE: on a MoltenVK-backed client this layer belongs to
    // MoltenVK's swapchain; presenting our own drawables into it conflicts with the swapchain.
    // It is only used here for inspection/telemetry until presentation ownership is resolved.
    CAMetalLayer *metalLayer = nil;
    if ([view.layer isKindOfClass:[CAMetalLayer class]]) {
        metalLayer = (CAMetalLayer *)view.layer;
    } else {
        for (CALayer *sublayer in view.layer.sublayers) {
            if ([sublayer isKindOfClass:[CAMetalLayer class]]) {
                metalLayer = (CAMetalLayer *)sublayer;
                break;
            }
        }
    }

    state.metalLayer = metalLayer;
    return 0;
}

void metalmod_shutdown(void) {
    MetalModState *state = [MetalModState sharedState];
    [state.pipelineLock lock];
    state.spatialScaler = nil;
    state.temporalScaler = nil;
    state.frameInterpolator = nil;
    state.upscaledTexture = nil;
    state.prevColorTexture = nil;
    state.interpolatedTexture = nil;
    state.compositePipelineState = nil;
    state.passthroughPipelineState = nil;
    state.samplerState = nil;
    state.hasHistory = NO;
    state.frameGenerationActive = NO;
    state.interpolatedTexturePurged = NO;
    state.vkDevice = NULL;
    state.exportMetalObjectsFunc = NULL;
    [state.pipelineLock unlock];
}

int metalmod_configure(const MetalModConfig* config) {
    if (!config) return -1;
    MetalModState *state = [MetalModState sharedState];
    if (!state.device) return -2;

    // Rebuilding scalers and textures must not overlap a frame that is still encoding with them.
    [state.pipelineLock lock];
    state.config = *config;
    BOOL success = [state recreatePipelines];
    [state.pipelineLock unlock];
    return success ? 0 : -3;
}

int metalmod_register_vulkan_device(VkDevice device, PFN_vkExportMetalObjectsEXT exportFunc) {
    if (!device || !exportFunc) return -1;
    MetalModState *state = [MetalModState sharedState];
    state.vkDevice = device;
    state.exportMetalObjectsFunc = exportFunc;
    return 0;
}

bool metalmod_has_vulkan_interop(void) {
    MetalModState *state = [MetalModState sharedState];
    return (state.vkDevice != NULL && state.exportMetalObjectsFunc != NULL);
}

int metalmod_process_frame(
    VkImage colorImage,
    VkImage depthImage,
    VkImage motionImage,
    VkImage uiImage,
    const MetalModFrameParams* params
) {
    if (!params) return METALMOD_ERR_INVALID_PARAMS;

    MetalModState *state = [MetalModState sharedState];
    if (!state.device || !state.commandQueue) return METALMOD_ERR_NO_RUNTIME;

    // Nothing to do. Report success so callers do not treat "upscaling disabled" as an error.
    BOOL needsUpscale = (state.config.scalingMode != METALMOD_SCALING_OFF);
    BOOL needsInterpolation = state.config.frameGenerationEnabled;
    if (!needsUpscale && !needsInterpolation) return METALMOD_OK;

    if (!METALMOD_OWNS_PRESENTATION) {
        // MoltenVK owns the CAMetalLayer and presents *after* this hook runs, so any drawable we
        // present is overwritten. Producing an image nobody can see would only burn CPU and GPU
        // time - the exact opposite of the goal. Report a distinct, non-fatal status instead.
        [state logDiagnostic:@"MetalMod frame pipeline is inactive: MoltenVK owns presentation. "
                             "Upscaling and frame generation cannot reach the display until "
                             "MetalMod owns the CAMetalLayer (see METALMOD_OWNS_PRESENTATION)."];
        return METALMOD_ERR_NO_PRESENTATION;
    }

    if (!metalmod_has_vulkan_interop()) {
        // The previous implementation allocated a fallback texture here that was never written
        // to, and presented it: the result was an uninitialised full-screen frame. Refuse
        // instead of presenting garbage.
        [state logDiagnostic:@"Frame pipeline unavailable: no Vulkan interop registered. "
                             "Upscaling and frame generation are inert until "
                             "metalmod_register_vulkan_device() is called."];
        return METALMOD_ERR_NO_VULKAN_INTEROP;
    }

    id<MTLTexture> colorTex = [state exportTextureFromVkImage:colorImage aspect:VK_IMAGE_ASPECT_COLOR_BIT];
    if (!colorTex) return METALMOD_ERR_NO_COLOR_TEXTURE;

    id<MTLTexture> depthTex = [state exportTextureFromVkImage:depthImage aspect:VK_IMAGE_ASPECT_DEPTH_BIT];
    id<MTLTexture> motionTex = [state exportTextureFromVkImage:motionImage aspect:VK_IMAGE_ASPECT_COLOR_BIT];
    id<MTLTexture> uiTex = [state exportTextureFromVkImage:uiImage aspect:VK_IMAGE_ASPECT_COLOR_BIT];

    // Temporal upscaling and frame interpolation both need depth and motion vectors; without
    // them MetalFX would read whatever the texture happens to contain.
    if (state.config.scalingMode == METALMOD_SCALING_TEMPORAL || needsInterpolation) {
        if (!depthTex || !motionTex) {
            [state logDiagnostic:@"Temporal upscaling / frame generation require depth and motion "
                                 "vector textures; skipping this frame."];
            return METALMOD_ERR_MISSING_DEPTH_OR_MOTION;
        }
    }

    [state processFrameWithColor:colorTex depth:depthTex motion:motionTex ui:uiTex params:params];
    return METALMOD_OK;
}

void metalmod_get_telemetry(MetalModTelemetry* outTelemetry) {
    if (!outTelemetry) return;
    MetalModState *state = [MetalModState sharedState];
    *outTelemetry = state.telemetry;
}

bool metalmod_is_spatial_scaler_supported(uint32_t inW, uint32_t inH, uint32_t outW, uint32_t outH) {
    MetalModState *state = [MetalModState sharedState];
    if (!state.device) return false;
    if (inW == 0 || inH == 0 || outW == 0 || outH == 0) return false;
    // Spatial scaling means upscaling: input must be smaller than output.
    if (inW > outW || inH > outH) return false;
    if (outW > kMetalModMaxTextureDimension || outH > kMetalModMaxTextureDimension) return false;
    if (![MTLFXSpatialScalerDescriptor supportsDevice:state.device]) return false;

    MTLFXSpatialScalerDescriptor *desc = [MTLFXSpatialScalerDescriptor new];
    desc.inputWidth = inW;
    desc.inputHeight = inH;
    desc.outputWidth = outW;
    desc.outputHeight = outH;
    desc.colorTextureFormat = MTLPixelFormatBGRA8Unorm;
    desc.outputTextureFormat = MTLPixelFormatBGRA8Unorm;
    desc.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;

    // Creating the scaler is the only query that actually validates the requested configuration.
    id<MTLFXSpatialScaler> scaler = [desc newSpatialScalerWithDevice:state.device];
    return scaler != nil;
}

bool metalmod_is_temporal_scaler_supported(uint32_t inW, uint32_t inH, uint32_t outW, uint32_t outH) {
    MetalModState *state = [MetalModState sharedState];
    if (!state.device) return false;
    if (inW == 0 || inH == 0 || outW == 0 || outH == 0) return false;
    if (inW > outW || inH > outH) return false;
    if (outW > kMetalModMaxTextureDimension || outH > kMetalModMaxTextureDimension) return false;
    if (![MTLFXTemporalScalerDescriptor supportsDevice:state.device]) return false;

    MTLFXTemporalScalerDescriptor *desc = [MTLFXTemporalScalerDescriptor new];
    desc.inputWidth = inW;
    desc.inputHeight = inH;
    desc.outputWidth = outW;
    desc.outputHeight = outH;
    desc.colorTextureFormat = MTLPixelFormatBGRA8Unorm;
    desc.depthTextureFormat = MTLPixelFormatDepth32Float;
    desc.motionTextureFormat = MTLPixelFormatRG16Float;
    desc.outputTextureFormat = MTLPixelFormatBGRA8Unorm;
    desc.autoExposureEnabled = YES;
    desc.inputContentPropertiesEnabled = NO;

    id<MTLFXTemporalScaler> scaler = [desc newTemporalScalerWithDevice:state.device];
    return scaler != nil;
}

bool metalmod_is_frame_gen_supported(uint32_t width, uint32_t height) {
    MetalModState *state = [MetalModState sharedState];
    if (!state.device) return false;
    if (width == 0 || height == 0) return false;
    if (width > kMetalModMaxTextureDimension || height > kMetalModMaxTextureDimension) return false;

    if (@available(macOS 26.0, *)) {
        if (![MTLFXFrameInterpolatorDescriptor supportsDevice:state.device]) return false;

        MTLFXFrameInterpolatorDescriptor *desc = [MTLFXFrameInterpolatorDescriptor new];
        // Frame interpolation is 1:1: inputWidth/Height describe the depth and motion texture
        // resolution, which equals the output resolution.
        desc.inputWidth = width;
        desc.inputHeight = height;
        desc.outputWidth = width;
        desc.outputHeight = height;
        desc.colorTextureFormat = MTLPixelFormatBGRA8Unorm;
        desc.depthTextureFormat = MTLPixelFormatDepth32Float;
        desc.motionTextureFormat = MTLPixelFormatRG16Float;
        desc.outputTextureFormat = MTLPixelFormatBGRA8Unorm;
        desc.uiTextureFormat = MTLPixelFormatBGRA8Unorm;

        id<MTLFXFrameInterpolator> interpolator = [desc newFrameInterpolatorWithDevice:state.device];
        return interpolator != nil;
    }
    return false;
}

void metalmod_report_pipeline_status(const char* status) {
    MetalModState *state = [MetalModState sharedState];
    state.pipelineStatusText = status ? @(status) : nil;
}

void metalmod_update_window_title(void) {
    dispatch_async(dispatch_get_main_queue(), ^{
        MetalModState *state = [MetalModState sharedState];
        // Nothing meaningful to report before the runtime is up.
        if (!state.device) return;

        NSArray<NSWindow *> *windows = [NSApp windows];
        NSWindow *w = [NSApp keyWindow] ?: [NSApp mainWindow];
        if (!w && [windows count] > 0) w = [windows firstObject];
        if (!w) return;

        // Use the size the renderer actually works with (GLFW's framebuffer size, reported through
        // the config) rather than converting the view's bounds: convertSizeToBacking on the content
        // view includes the macOS title bar, so it reported 5120x2880 for a window whose framebuffer
        // is 5120x2664 - disagreeing with the resolution shown on F3.
        uint32_t outW = state.config.outputWidth;
        uint32_t outH = state.config.outputHeight;
        if (outW == 0 || outH == 0) {
            NSSize backingSize = [w.contentView convertSizeToBacking:w.contentView.bounds.size];
            outW = (uint32_t)backingSize.width;
            outH = (uint32_t)backingSize.height;
        }

        uint32_t inW = state.config.inputWidth;
        uint32_t inH = state.config.inputHeight;

        NSString *modeStr = @"Off";
        if (state.config.scalingMode == METALMOD_SCALING_TEMPORAL) {
            modeStr = @"Temporal";
        } else if (state.config.scalingMode == METALMOD_SCALING_SPATIAL) {
            modeStr = @"Spatial";
        }

        // Report frame generation as inactive when it cannot actually be presented, instead of
        // echoing the requested setting back as if it were in effect.
        NSString *fgStr = @"OFF";
        if (state.config.frameGenerationEnabled) {
            fgStr = state.frameGenerationActive ? @"ON" : @"requested/inactive";
        }

        // Status published by the Java side. This is the only diagnostic channel that does not
        // depend on a mixin applying, so it is what tells "no hook ran" apart from "idle".
        NSString *statusStr = state.pipelineStatusText ?: @"no frame submitted";

        NSString *title = [NSString stringWithFormat:
            @"Minecraft [MetalMod: %ux%u -> %ux%u (%@) | FG: %@ | %@]",
            inW, inH, outW, outH, modeStr, fgStr, statusStr];

        for (NSWindow *win in windows) {
            if ([win isVisible]) {
                [win setTitle:title];
            }
        }
    });
}

}
