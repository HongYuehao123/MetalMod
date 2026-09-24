// MetalFX substrate implementation. See metalmod_metalfx.h for the contract.
//
// One rule shapes this file: MetalMod owns presentation, so a scaler is just another pass on the
// frame's own queue. Nothing here creates a queue, commits a command buffer or touches the layer -
// the caller owns those, which is what keeps the upscale ordered behind the passes that produced its
// input and ahead of the blit that presents it.

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#import <QuartzCore/QuartzCore.h>
#import <Metal/MTLDrawable.h>

#include "metalmod/metalmod_metalfx.h"

#include <string.h>

// The Java side keeps the GpuFormat -> MTLPixelFormat table; this layer is handed raw values, so it
// only ever converts back for the MetalFX descriptor.
static MTLPixelFormat mmm_fx_pixel_format(int64_t raw) {
    return (MTLPixelFormat)raw;
}

static MTLFXSpatialScalerColorProcessingMode mmm_fx_color_mode(int32_t raw) {
    switch (raw) {
        case 1: return MTLFXSpatialScalerColorProcessingModeLinear;
        case 2: return MTLFXSpatialScalerColorProcessingModeHDR;
        case 0:
        default: return MTLFXSpatialScalerColorProcessingModePerceptual;
    }
}

// The last failure, reported to Java through mmm_fx_last_error(). Written only on the render thread,
// which is the only thread that creates or encodes a scaler.
static char g_LastFxError[512] = {0};

/// The last temporal scaler step's GPU span, in milliseconds, written from its completion handler.
static volatile double g_TemporalLastGpuMs = 0.0;

static void mmm_fx_set_error(NSString* message) {
    const char* utf8 = message != nil ? message.UTF8String : "unknown MetalFX failure";
    if (utf8 == NULL) utf8 = "unknown MetalFX failure";
    strncpy(g_LastFxError, utf8, sizeof(g_LastFxError) - 1);
    g_LastFxError[sizeof(g_LastFxError) - 1] = '\0';
}

static void mmm_fx_clear_error(void) {
    g_LastFxError[0] = '\0';
}

const char* mmm_fx_last_error(void) {
    return g_LastFxError;
}

// A scaler is one opaque handle carrying the effect and the usage bits it demands of the textures it
// is handed. The usage bits are cached at creation because reading them back per frame would be an
// Objective-C message send on the present path for a value that never changes.
struct MMMSpatialScaler {
    id<MTLFXSpatialScaler> scaler;
    uint32_t colorUsage;
    uint32_t outputUsage;
};

struct MMMTemporalScaler {
    id<MTLFXTemporalScaler> scaler;
    uint32_t colorUsage;
    uint32_t depthUsage;
    uint32_t motionUsage;
    uint32_t outputUsage;
    // What the descriptor actually accepted, for mmm_fx_temporal_describe().
    bool depthReversed;
    bool dynamicResolution;
    bool reactiveMask;
    bool jitteredMotion;
    float minScale;
    float maxScale;
};

// ---------------------------------------------------------------------------------------------
// Spatial (7A)
// ---------------------------------------------------------------------------------------------

bool mmm_fx_spatial_supported(void* device, int64_t colorFormat, int64_t outputFormat) {
    id<MTLDevice> metalDevice = (__bridge id<MTLDevice>)device;
    if (metalDevice == nil) return false;
    if (@available(macOS 13.0, *)) {
        @autoreleasepool {
            MTLFXSpatialScalerDescriptor* descriptor = [[MTLFXSpatialScalerDescriptor alloc] init];
            descriptor.colorTextureFormat = mmm_fx_pixel_format(colorFormat);
            descriptor.outputTextureFormat = mmm_fx_pixel_format(outputFormat);
            return [MTLFXSpatialScalerDescriptor supportsDevice:metalDevice];
        }
    }
    return false;
}

void* mmm_fx_spatial_create(void* device,
                            int64_t colorFormat, int64_t outputFormat,
                            int32_t inputWidth, int32_t inputHeight,
                            int32_t outputWidth, int32_t outputHeight,
                            int32_t colorProcessingMode) {
    mmm_fx_clear_error();
    id<MTLDevice> metalDevice = (__bridge id<MTLDevice>)device;
    if (metalDevice == nil) {
        mmm_fx_set_error(@"no Metal device");
        return NULL;
    }
    if (inputWidth <= 0 || inputHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) {
        mmm_fx_set_error([NSString stringWithFormat:
                @"invalid scaler sizes %dx%d -> %dx%d",
                inputWidth, inputHeight, outputWidth, outputHeight]);
        return NULL;
    }
    if (@available(macOS 13.0, *)) {
        @autoreleasepool {
            MTLFXSpatialScalerDescriptor* descriptor = [[MTLFXSpatialScalerDescriptor alloc] init];
            descriptor.colorTextureFormat = mmm_fx_pixel_format(colorFormat);
            descriptor.outputTextureFormat = mmm_fx_pixel_format(outputFormat);
            descriptor.inputWidth = (NSUInteger)inputWidth;
            descriptor.inputHeight = (NSUInteger)inputHeight;
            descriptor.outputWidth = (NSUInteger)outputWidth;
            descriptor.outputHeight = (NSUInteger)outputHeight;
            descriptor.colorProcessingMode = mmm_fx_color_mode(colorProcessingMode);

            id<MTLFXSpatialScaler> scaler = [descriptor newSpatialScalerWithDevice:metalDevice];
            if (scaler == nil) {
                mmm_fx_set_error([NSString stringWithFormat:
                        @"newSpatialScalerWithDevice returned nil for %dx%d -> %dx%d",
                        inputWidth, inputHeight, outputWidth, outputHeight]);
                return NULL;
            }

            MMMSpatialScaler* handle = new MMMSpatialScaler();
            handle->scaler = scaler;
            handle->colorUsage = (uint32_t)scaler.colorTextureUsage;
            handle->outputUsage = (uint32_t)scaler.outputTextureUsage;
            return handle;
        }
    }
    mmm_fx_set_error(@"MetalFX spatial scaling needs macOS 13 or newer");
    return NULL;
}

void mmm_fx_spatial_release(void* scaler) {
    if (scaler == NULL) return;
    @autoreleasepool {
        MMMSpatialScaler* handle = (MMMSpatialScaler*)scaler;
        // ARC releases the strong protocol reference with the struct.
        delete handle;
    }
}

bool mmm_fx_spatial_texture_usage(void* scaler,
                                  uint32_t* outColorUsage, uint32_t* outOutputUsage) {
    MMMSpatialScaler* handle = (MMMSpatialScaler*)scaler;
    if (handle == NULL) return false;
    if (outColorUsage != NULL) *outColorUsage = handle->colorUsage;
    if (outOutputUsage != NULL) *outOutputUsage = handle->outputUsage;
    return true;
}

int mmm_fx_spatial_encode(void* scaler, void* commandBuffer,
                          void* colorTexture, void* outputTexture,
                          int32_t colorContentWidth, int32_t colorContentHeight) {
    MMMSpatialScaler* handle = (MMMSpatialScaler*)scaler;
    id<MTLCommandBuffer> buffer = (__bridge id<MTLCommandBuffer>)commandBuffer;
    id<MTLTexture> color = (__bridge id<MTLTexture>)colorTexture;
    id<MTLTexture> output = (__bridge id<MTLTexture>)outputTexture;
    if (handle == NULL || handle->scaler == nil) return -1;
    if (buffer == nil) return -2;
    if (color == nil || output == nil) return -3;

    @autoreleasepool {
        id<MTLFXSpatialScaler> effect = handle->scaler;
        effect.colorTexture = color;
        effect.outputTexture = output;
        // 0 means "the whole input", which is what a steady-resolution frame wants; a resize can
        // name a smaller region so an in-flight frame letterboxes instead of stretching.
        effect.inputContentWidth = colorContentWidth > 0 ? (NSUInteger)colorContentWidth
                                                         : color.width;
        effect.inputContentHeight = colorContentHeight > 0 ? (NSUInteger)colorContentHeight
                                                           : color.height;
        [effect encodeToCommandBuffer:buffer];
    }
    return 0;
}

int mmm_fx_spatial_run(void* scaler, void* queue,
                       void* sourceTexture, void* targetTexture) {
    MMMSpatialScaler* handle = (MMMSpatialScaler*)scaler;
    id<MTLCommandQueue> metalQueue = (__bridge id<MTLCommandQueue>)queue;
    id<MTLTexture> source = (__bridge id<MTLTexture>)sourceTexture;
    id<MTLTexture> target = (__bridge id<MTLTexture>)targetTexture;
    if (handle == NULL || handle->scaler == nil) return -1;
    if (metalQueue == nil) return -2;
    if (source == nil || target == nil) return -3;

    @autoreleasepool {
        id<MTLCommandBuffer> commandBuffer = [metalQueue commandBuffer];
        commandBuffer.label = @"MetalMod MetalFX upscale";
        int rc = mmm_fx_spatial_encode(scaler, (__bridge void*)commandBuffer,
                                       sourceTexture, targetTexture, 0, 0);
        // Committed either way: the buffer is ours and nothing else will release it, and an empty
        // commit is cheaper than a leaked command buffer.
        [commandBuffer commit];
        return rc;
    }
}

// ---------------------------------------------------------------------------------------------
// Presentation pacing (7C groundwork)
// ---------------------------------------------------------------------------------------------

// Only ever touched from the thread that presents.
static double g_LastPresentedTime = 0.0;
static double g_PresentMetrics[MMM_PRESENT_METRIC_COUNT] = {0};

// Where a drawable was last seen on screen, keyed by its texture. A layer rotates a handful of
// drawables, so a small table is enough; the entry is refreshed every time the drawable comes back.
static const int kPresentSlots = 8;
static void* g_PresentTextures[kPresentSlots] = {nullptr};
static double g_PresentTimes[kPresentSlots] = {0.0};

double mmm_present_time(void* drawableTexture, double* outIntervalSeconds) {
    if (outIntervalSeconds != NULL) *outIntervalSeconds = 0.0;
    id<MTLTexture> texture = (__bridge id<MTLTexture>)drawableTexture;
    if (texture == nil) return 0.0;

    void* key = (__bridge void*)texture;
    int slot = -1;
    for (int i = 0; i < kPresentSlots; i++) {
        if (g_PresentTextures[i] == key) { slot = i; break; }
    }
    if (slot < 0) {
        for (int i = 0; i < kPresentSlots; i++) {
            if (g_PresentTextures[i] == nullptr) { slot = i; break; }
        }
        // A full table means the layer has more drawables in rotation than this; reuse the oldest.
        if (slot < 0) {
            slot = 0;
            for (int i = 1; i < kPresentSlots; i++) {
                if (g_PresentTimes[i] < g_PresentTimes[slot]) slot = i;
            }
        }
        g_PresentTextures[slot] = key;
        g_PresentTimes[slot] = 0.0;
    }

    // The drawable's own report, asked of the object that actually implements it: CAMetalDrawable
    // presents `presentedTime`, and a CAMetalLayer hands out a drawable whose `texture` is that same
    // drawable. Asking by selector rather than by casting keeps this honest if a future layer hands
    // out a plain texture - the count of "unreported" then says so instead of reading garbage.
    CFTimeInterval presented = 0.0;
    if ([texture respondsToSelector:@selector(presentedTime)]) {
        presented = ((id<CAMetalDrawable>)texture).presentedTime;
    }
    if (presented <= 0.0) {
        g_PresentMetrics[MMM_PRESENT_UNREPORTED] += 1.0;
        return 0.0;
    }

    double previous = g_PresentTimes[slot];
    g_PresentTimes[slot] = presented;
    if (previous <= 0.0 || presented <= previous) {
        // First time this drawable has been seen on screen: there is no interval yet.
        g_PresentMetrics[MMM_PRESENT_UNREPORTED] += 1.0;
        return 0.0;
    }

    double interval = presented - previous;
    g_PresentMetrics[MMM_PRESENT_FRAMES] += 1.0;
    // The classification is relative to the previous interval rather than to a nominal refresh rate,
    // because the refresh rate is not known here and the question the counters answer is "did this
    // frame wait for an extra refresh", not "what is the display doing".
    static double previousInterval = 0.0;
    if (previousInterval > 0.0) {
        double ratio = interval / previousInterval;
        if (ratio < 1.2) {
            g_PresentMetrics[MMM_PRESENT_STEADY] += 1.0;
        } else if (ratio >= 1.5) {
            g_PresentMetrics[MMM_PRESENT_DROPPED] += 1.0;
        }
    }
    previousInterval = interval;
    if (outIntervalSeconds != NULL) *outIntervalSeconds = interval;
    return presented;
}

int32_t mmm_present_read_reset(double* out, int32_t count) {
    if (out == NULL || count < MMM_PRESENT_METRIC_COUNT) return -1;
    memcpy(out, g_PresentMetrics, sizeof(g_PresentMetrics));
    memset(g_PresentMetrics, 0, sizeof(g_PresentMetrics));
    return MMM_PRESENT_METRIC_COUNT;
}

// ---------------------------------------------------------------------------------------------
// GPU execution timing
// ---------------------------------------------------------------------------------------------

double mmm_gpu_time_fill(void* queue, void* texture, int32_t passes) {
    id<MTLCommandQueue> metalQueue = (__bridge id<MTLCommandQueue>)queue;
    id<MTLTexture> target = (__bridge id<MTLTexture>)texture;
    if (metalQueue == nil || target == nil) return -1.0;
    if (passes <= 0) passes = 1;

    @autoreleasepool {
        id<MTLCommandBuffer> commandBuffer = [metalQueue commandBuffer];
        commandBuffer.label = @"MetalMod gpu time (fill)";
        // A full-target clear is the cheapest write there is, so the number is a floor on what a pass
        // over this many pixels costs rather than a prediction of a real one. That is what makes it
        // useful: if even the floor is a large fraction of the frame, scaling cannot save the frame.
        MTLRenderPassDescriptor* descriptor = [MTLRenderPassDescriptor renderPassDescriptor];
        descriptor.colorAttachments[0].texture = target;
        descriptor.colorAttachments[0].loadAction = MTLLoadActionClear;
        descriptor.colorAttachments[0].storeAction = MTLStoreActionStore;
        descriptor.colorAttachments[0].clearColor = MTLClearColorMake(0.1, 0.2, 0.3, 1.0);
        for (int i = 0; i < passes; i++) {
            id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:descriptor];
            [encoder endEncoding];
        }
        [commandBuffer commit];
        [commandBuffer waitUntilCompleted];
        double seconds = commandBuffer.GPUEndTime - commandBuffer.GPUStartTime;
        return seconds > 0.0 ? (seconds * 1000.0) / passes : -2.0;
    }
}

double mmm_fx_temporal_gpu_time(void* scaler, void* queue,
                                  void* colorTexture, void* depthTexture, void* motionTexture,
                                  void* outputTexture,
                                  float jitterX, float jitterY, int32_t passes) {
    MMMTemporalScaler* handle = (MMMTemporalScaler*)scaler;
    id<MTLCommandQueue> metalQueue = (__bridge id<MTLCommandQueue>)queue;
    if (handle == NULL || handle->scaler == nil) return -1.0;
    if (metalQueue == nil) return -1.0;
    if (colorTexture == NULL || depthTexture == NULL || motionTexture == NULL
            || outputTexture == NULL) {
        return -1.0;
    }
    if (passes <= 0) passes = 1;

    // One command buffer per pass, each waited on, reading the buffer's own GPU timestamps. A temporal
    // scaler carries history across calls, so encoding several passes into one buffer would have them
    // racing over that history; separate buffers give a number that means what it says.
    double totalSeconds = 0.0;
    int counted = 0;
    @autoreleasepool {
        for (int i = 0; i < passes; i++) {
            id<MTLCommandBuffer> commandBuffer = [metalQueue commandBuffer];
            commandBuffer.label = @"MetalMod gpu time (temporal)";
            mmm_fx_temporal_encode(scaler, (__bridge void*)commandBuffer, colorTexture, depthTexture,
                                   motionTexture, outputTexture, jitterX, jitterY, false);
            [commandBuffer commit];
            [commandBuffer waitUntilCompleted];
            double seconds = commandBuffer.GPUEndTime - commandBuffer.GPUStartTime;
            if (seconds > 0.0) {
                totalSeconds += seconds;
                counted++;
            }
        }
    }
    return counted > 0 ? (totalSeconds * 1000.0) / counted : -2.0;
}

double mmm_gpu_time_upscale(void* scaler, void* queue,
                            void* sourceTexture, void* targetTexture, int32_t passes) {
    MMMSpatialScaler* handle = (MMMSpatialScaler*)scaler;
    id<MTLCommandQueue> metalQueue = (__bridge id<MTLCommandQueue>)queue;
    id<MTLTexture> source = (__bridge id<MTLTexture>)sourceTexture;
    id<MTLTexture> target = (__bridge id<MTLTexture>)targetTexture;
    if (handle == NULL || handle->scaler == nil) return -1.0;
    if (metalQueue == nil || source == nil || target == nil) return -1.0;
    if (passes <= 0) passes = 1;

    @autoreleasepool {
        id<MTLCommandBuffer> commandBuffer = [metalQueue commandBuffer];
        commandBuffer.label = @"MetalMod gpu time (upscale)";
        for (int i = 0; i < passes; i++) {
            mmm_fx_spatial_encode(scaler, (__bridge void*)commandBuffer, sourceTexture, targetTexture, 0, 0);
        }
        [commandBuffer commit];
        [commandBuffer waitUntilCompleted];
        double seconds = commandBuffer.GPUEndTime - commandBuffer.GPUStartTime;
        return seconds > 0.0 ? (seconds * 1000.0) / passes : -2.0;
    }
}

// ---------------------------------------------------------------------------------------------
// Temporal (7B)
// ---------------------------------------------------------------------------------------------

bool mmm_fx_temporal_supported(void* device,
                               int64_t colorFormat, int64_t depthFormat,
                               int64_t motionFormat, int64_t outputFormat) {
    id<MTLDevice> metalDevice = (__bridge id<MTLDevice>)device;
    if (metalDevice == nil) return false;
    if (@available(macOS 13.0, *)) {
        @autoreleasepool {
            MTLFXTemporalScalerDescriptor* descriptor = [[MTLFXTemporalScalerDescriptor alloc] init];
            descriptor.colorTextureFormat = mmm_fx_pixel_format(colorFormat);
            descriptor.depthTextureFormat = mmm_fx_pixel_format(depthFormat);
            descriptor.motionTextureFormat = mmm_fx_pixel_format(motionFormat);
            descriptor.outputTextureFormat = mmm_fx_pixel_format(outputFormat);
            return [MTLFXTemporalScalerDescriptor supportsDevice:metalDevice];
        }
    }
    return false;
}

void* mmm_fx_temporal_create(void* device,
                             int64_t colorFormat, int64_t depthFormat,
                             int64_t motionFormat, int64_t outputFormat,
                             int32_t inputWidth, int32_t inputHeight,
                             int32_t outputWidth, int32_t outputHeight,
                             bool depthReversed,
                             bool dynamicResolution, float minScale, float maxScale,
                             bool reactiveMask, int64_t maskFormat,
                             bool jitteredMotion) {
    mmm_fx_clear_error();
    id<MTLDevice> metalDevice = (__bridge id<MTLDevice>)device;
    if (metalDevice == nil) {
        mmm_fx_set_error(@"no Metal device");
        return NULL;
    }
    if (inputWidth <= 0 || inputHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) {
        mmm_fx_set_error([NSString stringWithFormat:
                @"invalid temporal sizes %dx%d -> %dx%d",
                inputWidth, inputHeight, outputWidth, outputHeight]);
        return NULL;
    }
    if (@available(macOS 13.0, *)) {
        @autoreleasepool {
            MTLFXTemporalScalerDescriptor* descriptor = [[MTLFXTemporalScalerDescriptor alloc] init];
            descriptor.colorTextureFormat = mmm_fx_pixel_format(colorFormat);
            descriptor.depthTextureFormat = mmm_fx_pixel_format(depthFormat);
            descriptor.motionTextureFormat = mmm_fx_pixel_format(motionFormat);
            descriptor.outputTextureFormat = mmm_fx_pixel_format(outputFormat);
            descriptor.inputWidth = (NSUInteger)inputWidth;
            descriptor.inputHeight = (NSUInteger)inputHeight;
            descriptor.outputWidth = (NSUInteger)outputWidth;
            descriptor.outputHeight = (NSUInteger)outputHeight;
            if (dynamicResolution) {
                descriptor.inputContentPropertiesEnabled = YES;
                descriptor.inputContentMinScale = minScale;
                descriptor.inputContentMaxScale = maxScale;
            }
            if (@available(macOS 14.4, *)) {
                if (reactiveMask) {
                    descriptor.reactiveMaskTextureEnabled = YES;
                    descriptor.reactiveMaskTextureFormat = mmm_fx_pixel_format(maskFormat);
                }
            }
            if (@available(macOS 27.0, *)) {
                if (jitteredMotion) descriptor.jitteredMotionVectorsEnabled = YES;
            }

            id<MTLFXTemporalScaler> scaler = [descriptor newTemporalScalerWithDevice:metalDevice];
            if (scaler == nil) {
                mmm_fx_set_error([NSString stringWithFormat:
                        @"newTemporalScalerWithDevice returned nil for %dx%d -> %dx%d",
                        inputWidth, inputHeight, outputWidth, outputHeight]);
                return NULL;
            }

            // depthReversed is a property of the scaler instance, not of the descriptor.
            scaler.depthReversed = depthReversed ? YES : NO;

            MMMTemporalScaler* handle = new MMMTemporalScaler();
            handle->scaler = scaler;
            handle->colorUsage = (uint32_t)scaler.colorTextureUsage;
            handle->depthUsage = (uint32_t)scaler.depthTextureUsage;
            handle->motionUsage = (uint32_t)scaler.motionTextureUsage;
            handle->outputUsage = (uint32_t)scaler.outputTextureUsage;
            handle->depthReversed = depthReversed;
            handle->dynamicResolution = dynamicResolution;
            handle->reactiveMask = reactiveMask;
            handle->jitteredMotion = jitteredMotion;
            handle->minScale = dynamicResolution ? minScale : 1.0f;
            handle->maxScale = dynamicResolution ? maxScale : 1.0f;
            return handle;
        }
    }
    mmm_fx_set_error(@"MetalFX temporal scaling needs macOS 13 or newer");
    return NULL;
}

bool mmm_fx_temporal_describe(void* scaler,
                              bool* outDepthReversed,
                              bool* outDynamicResolution,
                              float* outMinScale, float* outMaxScale,
                              bool* outReactiveMask, bool* outJitteredMotion) {
    MMMTemporalScaler* handle = (MMMTemporalScaler*)scaler;
    if (handle == NULL) return false;
    if (outDepthReversed != NULL) *outDepthReversed = handle->depthReversed;
    if (outDynamicResolution != NULL) *outDynamicResolution = handle->dynamicResolution;
    if (outMinScale != NULL) *outMinScale = handle->minScale;
    if (outMaxScale != NULL) *outMaxScale = handle->maxScale;
    if (outReactiveMask != NULL) *outReactiveMask = handle->reactiveMask;
    if (outJitteredMotion != NULL) *outJitteredMotion = handle->jitteredMotion;
    return true;
}

void mmm_fx_temporal_release(void* scaler) {
    if (scaler == NULL) return;
    @autoreleasepool {
        MMMTemporalScaler* handle = (MMMTemporalScaler*)scaler;
        delete handle;
    }
}

int mmm_fx_temporal_encode(void* scaler, void* commandBuffer,
                           void* colorTexture, void* depthTexture, void* motionTexture,
                           void* outputTexture,
                           float jitterX, float jitterY, bool reset) {
    MMMTemporalScaler* handle = (MMMTemporalScaler*)scaler;
    id<MTLCommandBuffer> buffer = (__bridge id<MTLCommandBuffer>)commandBuffer;
    id<MTLTexture> color = (__bridge id<MTLTexture>)colorTexture;
    id<MTLTexture> depth = (__bridge id<MTLTexture>)depthTexture;
    id<MTLTexture> motion = (__bridge id<MTLTexture>)motionTexture;
    id<MTLTexture> output = (__bridge id<MTLTexture>)outputTexture;
    if (handle == NULL || handle->scaler == nil) return -1;
    if (buffer == nil) return -2;
    if (color == nil || depth == nil || motion == nil || output == nil) return -3;

    @autoreleasepool {
        id<MTLFXTemporalScaler> effect = handle->scaler;
        effect.colorTexture = color;
        effect.depthTexture = depth;
        effect.motionTexture = motion;
        effect.outputTexture = output;
        // Motion and depth are already in input-texture pixels, so no extra scaling is applied.
        effect.motionVectorScaleX = 1.0f;
        effect.motionVectorScaleY = 1.0f;
        effect.jitterOffsetX = jitterX;
        effect.jitterOffsetY = jitterY;
        effect.reset = reset ? YES : NO;
        [effect encodeToCommandBuffer:buffer];
        // Only the frame's own path is timed here: the harness calls this with its own buffer and its
        // own timing, and a stale value from that would be worse than no value at all.
        if (buffer.label == nil || ![buffer.label hasPrefix:@"MetalMod gpu time"]) {
            [buffer addCompletedHandler:^(id<MTLCommandBuffer> completed) {
                double seconds = completed.GPUEndTime - completed.GPUStartTime;
                if (seconds > 0.0) g_TemporalLastGpuMs = seconds * 1000.0;
            }];
        }
    }
    return 0;
}

double mmm_fx_temporal_last_gpu_ms(void) {
    return g_TemporalLastGpuMs;
}
