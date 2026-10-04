#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#include "metalmod/metalmod_interpolation.h"
#include <atomic>
#include <algorithm>
#include <cmath>

@interface MMMInterpolationState : NSObject {
@public
    std::atomic<bool> failed;
    uint64_t lastId;
    bool primed;
    uint32_t warmupPairs;
}
@property(nonatomic, strong) id<MTLDevice> device;
@property(nonatomic, strong) id<MTLCommandQueue> queue;
@property(nonatomic, strong) id<MTLFXFrameInterpolator> interpolator;
@property(nonatomic, strong) id<MTLTexture> lastColor;
@end
@implementation MMMInterpolationState
@end

bool mmm_fx_interpolation_supported(void* device) {
    if (!device) return false;
    if (@available(macOS 26.0, *))
        return [MTLFXFrameInterpolatorDescriptor supportsDevice:(__bridge id<MTLDevice>)device];
    return false;
}
void* mmm_fx_interpolation_create(void* device, int32_t iw, int32_t ih, int32_t ow, int32_t oh) {
    if (!mmm_fx_interpolation_supported(device) || iw <= 0 || ih <= 0 || iw > ow || ih > oh
        || ow > 16384 || oh > 16384
        || std::abs((double)iw * oh - (double)ih * ow) > std::max(ow, oh)) return NULL;
    @autoreleasepool {
        @try {
            MTLFXFrameInterpolatorDescriptor* desc = [MTLFXFrameInterpolatorDescriptor new];
            desc.inputWidth = iw; desc.inputHeight = ih;
            desc.outputWidth = ow; desc.outputHeight = oh;
            desc.colorTextureFormat = desc.outputTextureFormat = MTLPixelFormatRGBA16Float;
            desc.depthTextureFormat = MTLPixelFormatDepth32Float;
            desc.motionTextureFormat = MTLPixelFormatRG16Float;
            desc.uiTextureFormat = MTLPixelFormatRGBA16Float;
            // Explicit previous colour works on macOS 26 and 27; no scaler dependency.
            MMMInterpolationState* state = [MMMInterpolationState new];
            state.device = (__bridge id<MTLDevice>)device;
            state.interpolator = [desc newFrameInterpolatorWithDevice:state.device];
            state->failed.store(false); state->primed = false; state->warmupPairs = 0; state->lastId = 0;
            if (!state.interpolator) return NULL;
            return (__bridge_retained void*)state;
        } @catch (NSException* error) {
            NSLog(@"[MetalMod] Frame interpolation creation rejected: %@", error.reason);
            return NULL;
        }
    }
}
void mmm_fx_interpolation_release(void* handle) {
    if (handle) { MMMInterpolationState* released = (__bridge_transfer MMMInterpolationState*)handle; (void)released; }
}
bool mmm_fx_interpolation_healthy(void* handle) {
    return handle && !((__bridge MMMInterpolationState*)handle)->failed.load();
}
int64_t mmm_fx_interpolation_texture_usage(void* handle, int32_t role) {
    if (!handle) return -1;
    id<MTLFXFrameInterpolator> fx = ((__bridge MMMInterpolationState*)handle).interpolator;
    switch (role) {
        case 0: return fx.colorTextureUsage;
        case 1: return fx.depthTextureUsage;
        case 2: return fx.motionTextureUsage;
        case 3: return fx.uiTextureUsage;
        case 4: return fx.outputTextureUsage;
        default: return -1;
    }
}
static bool validTexture(id<MTLTexture> t, id<MTLDevice> dev, MTLPixelFormat format,
                         NSUInteger w, NSUInteger h, MTLTextureUsage usage) {
    return t && t.device == dev && t.textureType == MTLTextureType2D && t.sampleCount == 1
        && t.arrayLength == 1 && t.width == w && t.height == h && t.pixelFormat == format
        && (t.usage & usage) == usage && t.storageMode != MTLStorageModeMemoryless
        && t.hazardTrackingMode != MTLHazardTrackingModeUntracked;
}
static int32_t encodeInterpolation(void* handle, void* commandBuffer, void* current, void* previous,
    void* depth, void* motion, void* ui, void* output, uint64_t previousId, uint64_t currentId,
    float dt, float nearPlane, float farPlane, float fovDegrees, float jitterX, float jitterY,
    bool depthReversed, bool reset, bool composited) {
    if (!handle || !commandBuffer || !current || !previous || !depth || !motion || !output) return -1;
    MMMInterpolationState* state = (__bridge MMMInterpolationState*)handle;
    if (state->failed.load()) return -2;
    if (!std::isfinite(dt) || dt <= 0 || dt > 0.25f || !std::isfinite(nearPlane)
        || !std::isfinite(farPlane) || nearPlane <= 0 || farPlane <= nearPlane
        || !std::isfinite(fovDegrees) || fovDegrees <= 0 || fovDegrees >= 180
        || !std::isfinite(jitterX) || !std::isfinite(jitterY)
        || std::abs(jitterX) > 0.5f || std::abs(jitterY) > 0.5f) return -4;
    id<MTLCommandBuffer> cb = (__bridge id<MTLCommandBuffer>)commandBuffer;
    id<MTLTexture> c = (__bridge id<MTLTexture>)current, p = (__bridge id<MTLTexture>)previous;
    id<MTLTexture> d = (__bridge id<MTLTexture>)depth, m = (__bridge id<MTLTexture>)motion;
    id<MTLTexture> u = (__bridge id<MTLTexture>)ui, o = (__bridge id<MTLTexture>)output;
    id<MTLFXFrameInterpolator> fx = state.interpolator;
    if (cb.status != MTLCommandBufferStatusNotEnqueued || cb.device != state.device
        || (state.queue && cb.commandQueue != state.queue)
        || !validTexture(c,state.device,fx.colorTextureFormat,fx.outputWidth,fx.outputHeight,fx.colorTextureUsage)
        || !validTexture(p,state.device,fx.colorTextureFormat,fx.outputWidth,fx.outputHeight,fx.colorTextureUsage)
        || !validTexture(d,state.device,fx.depthTextureFormat,fx.inputWidth,fx.inputHeight,fx.depthTextureUsage)
        || !validTexture(m,state.device,fx.motionTextureFormat,fx.inputWidth,fx.inputHeight,fx.motionTextureUsage)
        || (u && !validTexture(u,state.device,fx.uiTextureFormat,fx.outputWidth,fx.outputHeight,fx.uiTextureUsage))
        || !validTexture(o,state.device,fx.outputTextureFormat,fx.outputWidth,fx.outputHeight,fx.outputTextureUsage)
        || o.storageMode != MTLStorageModePrivate || c == p || o == c || o == p || o == u) return -3;
    if (currentId == 0 || previousId >= currentId || (state->primed && currentId <= state->lastId)
        || (state->primed && !reset && (previousId != state->lastId || p != state.lastColor))) return -6;
    const bool prime = reset || !state->primed;
    // Measured reset sequences can reproduce current colour for two pairs.
    // Conservatively suppress both before declaring an output display eligible.
    const bool displayEligible = !prime && state->warmupPairs == 0;
    @autoreleasepool {
        @try {
            fx.colorTexture = c; fx.prevColorTexture = p; fx.depthTexture = d;
            fx.motionTexture = m; fx.uiTexture = u; fx.uiTextureComposited = composited; fx.outputTexture = o;
            fx.motionVectorScaleX = (float)fx.outputWidth / fx.inputWidth;
            fx.motionVectorScaleY = (float)fx.outputHeight / fx.inputHeight;
            fx.deltaTime = dt; fx.nearPlane = nearPlane; fx.farPlane = farPlane;
            fx.fieldOfView = fovDegrees; fx.aspectRatio = (float)fx.outputWidth / fx.outputHeight;
            fx.jitterOffsetX = jitterX; fx.jitterOffsetY = jitterY;
            fx.depthReversed = depthReversed; fx.shouldResetHistory = prime;
            [fx encodeToCommandBuffer:cb];
            // Retain both the generation and this encode's exact inputs through completion.
            NSArray* textures = u ? @[c,p,d,m,u,o] : @[c,p,d,m,o];
            [cb addCompletedHandler:^(id<MTLCommandBuffer> completed) {
                (void)textures;
                if (completed.status == MTLCommandBufferStatusError) {
                    state->failed.store(true);
                    NSLog(@"[MetalMod] Frame interpolation GPU failure: %@", completed.error);
                }
            }];
            state.queue = cb.commandQueue; state.lastColor = c;
            state->lastId = currentId; state->primed = true;
            state->warmupPairs = prime ? 2 : (state->warmupPairs ? state->warmupPairs - 1 : 0);
            return displayEligible ? 0 : 1;
        } @catch (NSException* error) {
            state->failed.store(true);
            NSLog(@"[MetalMod] Frame interpolation encode rejected: %@", error.reason);
            return -5;
        }
    }
}

int32_t mmm_fx_interpolation_encode(void* h,void* cb,void* c,void* p,void* d,void* m,void* ui,void* o,
 uint64_t pi,uint64_t ci,float dt,float near,float far,float fov,float jx,float jy,bool reversed,bool reset) {
 return encodeInterpolation(h,cb,c,p,d,m,ui,o,pi,ci,dt,near,far,fov,jx,jy,reversed,reset,false);
}
int32_t mmm_fx_interpolation_encode_composited(void* h,void* cb,void* c,void* p,void* d,void* m,void* ui,void* o,
 uint64_t pi,uint64_t ci,float dt,float near,float far,float fov,float jx,float jy,bool reversed,bool reset) {
 return encodeInterpolation(h,cb,c,p,d,m,ui,o,pi,ci,dt,near,far,fov,jx,jy,reversed,reset,true);
}
