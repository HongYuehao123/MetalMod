#import "metalmod_internal.h"
#include <mach/mach_time.h>

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
        _hasHistory = NO;
        _lastFrameTimestamp = mach_absolute_time();
        memset(&_telemetry, 0, sizeof(MetalModTelemetry));
        memset(&_config, 0, sizeof(MetalModConfig));
    }
    return self;
}

- (id<MTLTexture>)exportTextureFromVkImage:(VkImage)image
                                    aspect:(VkImageAspectFlagBits)aspect {
    if (!image) return nil;

    if (self.exportMetalObjectsFunc && self.vkDevice) {
        VkExportMetalTextureInfoEXT textureInfo = {};
        textureInfo.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_TEXTURE_INFO_EXT;
        textureInfo.pNext = nullptr;
        textureInfo.image = image;
        textureInfo.imageView = VK_NULL_HANDLE;
        textureInfo.bufferView = VK_NULL_HANDLE;
        textureInfo.plane = aspect;
        textureInfo.mtlTexture = nil;

        VkExportMetalObjectsInfoEXT exportInfo = {};
        exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT;
        exportInfo.pNext = &textureInfo;

        self.exportMetalObjectsFunc(self.vkDevice, &exportInfo);
        return (__bridge id<MTLTexture>)textureInfo.mtlTexture;
    }

    // Direct cast fallback if MoltenVK direct memory handle is mapped
    return (__bridge id<MTLTexture>)image;
}

@end

#pragma mark - C API Implementations

extern "C" {

int metalmod_init(void* nsWindowHandle) {
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

    // Find or attach CAMetalLayer
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

    if (metalLayer) {
        metalLayer.maximumDrawableCount = 4;
        metalLayer.presentsWithTransaction = NO;
        metalLayer.displaySyncEnabled = YES;
    }

    state.metalLayer = metalLayer;
    return 0;
}

void metalmod_shutdown(void) {
    MetalModState *state = [MetalModState sharedState];
    state.spatialScaler = nil;
    state.temporalScaler = nil;
    state.frameInterpolator = nil;
    state.upscaledTexture = nil;
    state.prevColorTexture = nil;
    state.interpolatedTexture = nil;
    state.hasHistory = NO;
}

int metalmod_configure(const MetalModConfig* config) {
    if (!config) return -1;
    MetalModState *state = [MetalModState sharedState];
    state.config = *config;
    BOOL success = [state recreatePipelines];
    return success ? 0 : -2;
}

int metalmod_register_vulkan_device(VkDevice device, PFN_vkExportMetalObjectsEXT exportFunc) {
    MetalModState *state = [MetalModState sharedState];
    state.vkDevice = device;
    state.exportMetalObjectsFunc = exportFunc;
    return 0;
}

int metalmod_process_frame(
    VkImage colorImage,
    VkImage depthImage,
    VkImage motionImage,
    VkImage uiImage,
    const MetalModFrameParams* params
) {
    if (!colorImage || !params) return -1;

    MetalModState *state = [MetalModState sharedState];
    id<MTLTexture> colorTex = [state exportTextureFromVkImage:colorImage aspect:VK_IMAGE_ASPECT_COLOR_BIT];
    id<MTLTexture> depthTex = [state exportTextureFromVkImage:depthImage aspect:VK_IMAGE_ASPECT_DEPTH_BIT];
    id<MTLTexture> motionTex = [state exportTextureFromVkImage:motionImage aspect:VK_IMAGE_ASPECT_COLOR_BIT];
    id<MTLTexture> uiTex = [state exportTextureFromVkImage:uiImage aspect:VK_IMAGE_ASPECT_COLOR_BIT];

    if (!colorTex) return -2;

    [state processFrameWithColor:colorTex depth:depthTex motion:motionTex ui:uiTex params:params];
    return 0;
}

void metalmod_get_telemetry(MetalModTelemetry* outTelemetry) {
    if (!outTelemetry) return;
    MetalModState *state = [MetalModState sharedState];
    *outTelemetry = state.telemetry;
}

bool metalmod_is_spatial_scaler_supported(uint32_t inW, uint32_t inH, uint32_t outW, uint32_t outH) {
    (void)inW; (void)inH; (void)outW; (void)outH;
    MetalModState *state = [MetalModState sharedState];
    if (!state.device) return false;

    return [MTLFXSpatialScalerDescriptor supportsDevice:state.device];
}

bool metalmod_is_temporal_scaler_supported(uint32_t inW, uint32_t inH, uint32_t outW, uint32_t outH) {
    (void)inW; (void)inH; (void)outW; (void)outH;
    MetalModState *state = [MetalModState sharedState];
    if (!state.device) return false;

    return [MTLFXTemporalScalerDescriptor supportsDevice:state.device];
}

bool metalmod_is_frame_gen_supported(uint32_t width, uint32_t height) {
    (void)width; (void)height;
    MetalModState *state = [MetalModState sharedState];
    if (!state.device) return false;

    if (@available(macOS 26.0, *)) {
        return [MTLFXFrameInterpolatorDescriptor supportsDevice:state.device];
    }
    return false;
}

void metalmod_update_window_title(void) {
    dispatch_async(dispatch_get_main_queue(), ^{
        MetalModState *state = [MetalModState sharedState];
        NSArray<NSWindow *> *windows = [NSApp windows];
        NSWindow *w = [NSApp keyWindow] ?: [NSApp mainWindow];
        if (!w && [windows count] > 0) w = [windows firstObject];
        if (!w) return;

        // Query the actual physical backing pixel size of the macOS window
        NSSize backingSize = [w.contentView convertSizeToBacking:w.contentView.bounds.size];
        uint32_t realW = (uint32_t)backingSize.width;
        uint32_t realH = (uint32_t)backingSize.height;

        uint32_t outW = (realW > 0) ? realW : state.config.outputWidth;
        uint32_t outH = (realH > 0) ? realH : state.config.outputHeight;

        uint32_t inW = state.config.inputWidth;
        uint32_t inH = state.config.inputHeight;

        if (state.config.outputWidth > 0 && outW != state.config.outputWidth) {
            float ratioW = (float)state.config.inputWidth / (float)state.config.outputWidth;
            float ratioH = (float)state.config.inputHeight / (float)state.config.outputHeight;
            inW = (uint32_t)(outW * ratioW);
            inH = (uint32_t)(outH * ratioH);
            if (inW & 1) inW++;
            if (inH & 1) inH++;
            MetalModConfig cfg = state.config;
            cfg.inputWidth = inW;
            cfg.inputHeight = inH;
            cfg.outputWidth = outW;
            cfg.outputHeight = outH;
            state.config = cfg;
        } else if (inW == 0 || inH == 0) {
            float scale = 0.50f;
            inW = (uint32_t)(outW * scale);
            inH = (uint32_t)(outH * scale);
            if (inW & 1) inW++;
            if (inH & 1) inH++;
        }

        NSString *modeStr = state.config.scalingMode == METALMOD_SCALING_TEMPORAL ? @"Temporal" : (state.config.scalingMode == METALMOD_SCALING_SPATIAL ? @"Spatial" : @"Off");
        NSString *title = [NSString stringWithFormat:@"Minecraft [MetalMod: %ux%u -> %ux%u (%@) | FG: %@]",
                          inW, inH,
                          outW, outH,
                          modeStr,
                          state.config.frameGenerationEnabled ? @"ON (Metal 4)" : @"OFF"];

        for (NSWindow *win in windows) {
            if ([win isVisible]) {
                [win setTitle:title];
            }
        }
    });
}

}
