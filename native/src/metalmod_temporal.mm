#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#include "metalmod/metalmod_metalfx.h"
#include <atomic>
#include <cmath>

// Prototype only. Render-thread ownership; completion handlers only set the error latch.
@interface MMMTemporalState : NSObject {
@public
    std::atomic<bool> failed;
    bool firstFrame;
}
@property(nonatomic, strong) id<MTLDevice> device;
@property(nonatomic, strong) id<MTLCommandQueue> queue;
@property(nonatomic, strong) id<MTLFXTemporalScaler> scaler;
@property(nonatomic, strong) id<MTLTexture> exposure;
@end
@implementation MMMTemporalState
@end

bool mmm_fx_temporal_supported(void* device) {
    if (!device) return false;
    // ABI v1 requires the reactive mask (macOS 14.4), not just the original scaler.
    if (@available(macOS 14.4, *))
        return [MTLFXTemporalScalerDescriptor supportsDevice:(__bridge id<MTLDevice>)device];
    return false;
}

void* mmm_fx_temporal_create(void* device, int32_t iw, int32_t ih, int32_t ow, int32_t oh) {
    if (!mmm_fx_temporal_supported(device) || iw <= 0 || ih <= 0 || ow <= 0 || oh <= 0
            || iw > ow || ih > oh || ow > 16384 || oh > 16384) return NULL;
    // A fixed scene scale preserves aspect ratio, allowing one input pixel of rounding.
    if (std::abs((double)iw * oh - (double)ih * ow) > (ow > oh ? ow : oh)) return NULL;
    @autoreleasepool {
        @try {
            id<MTLDevice> dev = (__bridge id<MTLDevice>)device;
            const float minScale = [MTLFXTemporalScalerDescriptor supportedInputContentMinScaleForDevice:dev];
            const float maxScale = [MTLFXTemporalScalerDescriptor supportedInputContentMaxScaleForDevice:dev];
            // Apple expresses scale as output/input. Validate both axes, including rounded sizes.
            const double sx = (double)ow / iw, sy = (double)oh / ih;
            if (!std::isfinite(minScale) || !std::isfinite(maxScale)
                    || sx < minScale || sy < minScale || sx > maxScale || sy > maxScale) return NULL;
            MTLFXTemporalScalerDescriptor* desc = [MTLFXTemporalScalerDescriptor new];
            desc.inputWidth = iw; desc.inputHeight = ih;
            desc.outputWidth = ow; desc.outputHeight = oh;
            desc.colorTextureFormat = MTLPixelFormatRGBA16Float;
            desc.outputTextureFormat = MTLPixelFormatRGBA16Float;
            desc.depthTextureFormat = MTLPixelFormatDepth32Float;
            desc.motionTextureFormat = MTLPixelFormatRG16Float;
            desc.autoExposureEnabled = NO;
            desc.reactiveMaskTextureEnabled = YES;
            desc.reactiveMaskTextureFormat = MTLPixelFormatR8Unorm;
            desc.requiresSynchronousInitialization = YES;
            MMMTemporalState* state = [MMMTemporalState new];
            state->failed.store(false);
            state->firstFrame = true;
            state.device = dev;
            state.scaler = [desc newTemporalScalerWithDevice:dev];
            if (!state.scaler) return NULL;
            MTLTextureDescriptor* td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:
                    MTLPixelFormatR16Float width:1 height:1 mipmapped:NO];
            td.storageMode = MTLStorageModeShared;
            td.usage = MTLTextureUsageShaderRead;
            state.exposure = [dev newTextureWithDescriptor:td];
            if (!state.exposure) return NULL;
            const uint16_t one = 0x3c00; // IEEE binary16 1.0, no auto/pre-exposure adjustment.
            [state.exposure replaceRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0
                    withBytes:&one bytesPerRow:sizeof(one)];
            return (__bridge_retained void*)state;
        } @catch (NSException* error) {
            NSLog(@"[MetalMod] Temporal prototype creation rejected: %@", error.reason);
            return NULL;
        }
    }
}

void mmm_fx_temporal_release(void* handle) {
    if (handle) { MMMTemporalState* released = (__bridge_transfer MMMTemporalState*)handle; (void)released; }
}
bool mmm_fx_temporal_healthy(void* handle) {
    return handle && !((__bridge MMMTemporalState*)handle)->failed.load();
}

int64_t mmm_fx_temporal_texture_usage(void* handle, int32_t role) {
    if (!handle) return -1;
    id<MTLFXTemporalScaler> fx = ((__bridge MMMTemporalState*)handle).scaler;
    switch (role) {
        case 0: return (int64_t)fx.colorTextureUsage;
        case 1: return (int64_t)fx.depthTextureUsage;
        case 2: return (int64_t)fx.motionTextureUsage;
        case 3:
            if (@available(macOS 27.0, *)) return (int64_t)fx.reactiveMaskTextureUsage;
            return (int64_t)fx.reactiveTextureUsage;
        case 4: return (int64_t)fx.outputTextureUsage;
        default: return -1;
    }
}

static bool temporalTexture(id<MTLTexture> texture, id<MTLDevice> device, MTLPixelFormat format,
                            NSUInteger width, NSUInteger height, MTLTextureUsage usage) {
    return texture.device == device && texture.textureType == MTLTextureType2D
            && texture.sampleCount == 1 && texture.arrayLength == 1
            && texture.width == width && texture.height == height && texture.pixelFormat == format
            && (texture.usage & usage) == usage && texture.storageMode != MTLStorageModeMemoryless;
}

int32_t mmm_fx_temporal_encode(void* handle, void* commandBuffer, void* color, void* depth,
                               void* motion, void* reactive, void* output,
                               float jitterX, float jitterY, bool depthReversed, bool reset) {
    if (!handle || !commandBuffer || !color || !depth || !motion || !reactive || !output) return -1;
    MMMTemporalState* state = (__bridge MMMTemporalState*)handle;
    if (state->failed.load()) return -2;
    if (!std::isfinite(jitterX) || !std::isfinite(jitterY)
            || std::abs(jitterX) > 0.5f || std::abs(jitterY) > 0.5f) return -4;
    id<MTLCommandBuffer> cb = (__bridge id<MTLCommandBuffer>)commandBuffer;
    id<MTLTexture> c = (__bridge id<MTLTexture>)color, d = (__bridge id<MTLTexture>)depth;
    id<MTLTexture> m = (__bridge id<MTLTexture>)motion, r = (__bridge id<MTLTexture>)reactive;
    id<MTLTexture> o = (__bridge id<MTLTexture>)output;
    id<MTLFXTemporalScaler> fx = state.scaler;
    if (cb.status != MTLCommandBufferStatusNotEnqueued || cb.device != state.device
            || (state.queue && cb.commandQueue != state.queue)
            || !temporalTexture(c, state.device, fx.colorTextureFormat, fx.inputWidth, fx.inputHeight, fx.colorTextureUsage)
            || !temporalTexture(d, state.device, fx.depthTextureFormat, fx.inputWidth, fx.inputHeight, fx.depthTextureUsage)
            || !temporalTexture(m, state.device, fx.motionTextureFormat, fx.inputWidth, fx.inputHeight, fx.motionTextureUsage)
            || !temporalTexture(r, state.device, fx.reactiveMaskTextureFormat, fx.inputWidth, fx.inputHeight,
                    (MTLTextureUsage)mmm_fx_temporal_texture_usage(handle, 3))
            || !temporalTexture(o, state.device, fx.outputTextureFormat, fx.outputWidth, fx.outputHeight, fx.outputTextureUsage)
            || o.storageMode != MTLStorageModePrivate || c == o) return -3;
    @autoreleasepool {
        @try {
            fx.colorTexture = c; fx.depthTexture = d; fx.motionTexture = m;
            fx.reactiveMaskTexture = r; fx.outputTexture = o;
            fx.exposureTexture = state.exposure; fx.preExposure = 1.0f;
            fx.inputContentWidth = fx.inputWidth; fx.inputContentHeight = fx.inputHeight;
            fx.motionVectorScaleX = 1.0f; fx.motionVectorScaleY = 1.0f;
            fx.jitterOffsetX = jitterX; fx.jitterOffsetY = jitterY;
            fx.depthReversed = depthReversed;
            fx.reset = reset || state->firstFrame;
            [fx encodeToCommandBuffer:cb];
            [cb addCompletedHandler:^(id<MTLCommandBuffer> completed) {
                // Capturing state retains the scaler/exposure until GPU work finishes.
                if (completed.status == MTLCommandBufferStatusError) {
                    state->failed.store(true);
                    NSLog(@"[MetalMod] Temporal prototype GPU failure: %@", completed.error);
                }
            }];
            state.queue = cb.commandQueue;
            state->firstFrame = false;
            return 0;
        } @catch (NSException* error) {
            // Partial encoding/history is unusable: discard the CB and retire this generation.
            state->failed.store(true);
            NSLog(@"[MetalMod] Temporal prototype encode rejected: %@", error.reason);
            return -5;
        }
    }
}
