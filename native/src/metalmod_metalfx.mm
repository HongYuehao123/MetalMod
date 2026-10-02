#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#include "metalmod/metalmod_metalfx.h"
#include <atomic>
#include <mutex>
#include <cmath>
#include "metalmod_spatial_aa.h"

// Resources and submission sequence are render-thread owned. Completion threads publish
// the atomic error latch and timing protected by timingMutex.
@interface MMMSpatialState : NSObject {
@public
    std::atomic<bool> failed;
    std::mutex timingMutex;
    uint64_t submissionSequence;
    uint64_t completedSequence;
    int64_t gpuDurationNs;
}
@property(nonatomic, strong) id<MTLFXSpatialScaler> scaler;
@property(nonatomic, strong) id<MTLTexture> output;
@property(nonatomic, strong) id<MTLTexture> antialiasedInput;
@property(nonatomic, strong) id<MTLRenderPipelineState> antialiasPipeline;
@property(nonatomic) BOOL antialiasEnabled;
@property(nonatomic, strong) id<MTLRenderPipelineState> recoveryPipeline;
@property(nonatomic, strong) id<MTLSamplerState> sampler;
@end
@implementation MMMSpatialState
@end

bool mmm_fx_spatial_supported(void* device) {
    if (!device) return false;
    if (@available(macOS 13.0, *))
        return [MTLFXSpatialScalerDescriptor supportsDevice:(__bridge id<MTLDevice>)device];
    return false;
}

void* mmm_fx_spatial_create(void* device, int32_t iw, int32_t ih, int32_t ow, int32_t oh,
                            int64_t format) {
    if (!mmm_fx_spatial_supported(device) || iw <= 0 || ih <= 0 || ow < iw || oh < ih
            || ow > 16384 || oh > 16384 || iw * 2 + 1 < ow || ih * 2 + 1 < oh
            || (format != MTLPixelFormatRGBA8Unorm && format != MTLPixelFormatBGRA8Unorm)) return NULL;
    @autoreleasepool {
        @try {
            id<MTLDevice> dev = (__bridge id<MTLDevice>)device;
            MTLFXSpatialScalerDescriptor* desc = [MTLFXSpatialScalerDescriptor new];
            desc.inputWidth = iw; desc.inputHeight = ih;
            desc.outputWidth = ow; desc.outputHeight = oh;
            desc.colorTextureFormat = (MTLPixelFormat)format;
            desc.outputTextureFormat = (MTLPixelFormat)format;
            // Vanilla SDR RGBA8 contains perceptual colour; no sRGB view or additional gamma pass.
            desc.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;
            MMMSpatialState* state = [MMMSpatialState new];
            state->failed.store(false);
            state->submissionSequence = state->completedSequence = 0;
            state->gpuDurationNs = -1;
            state.antialiasEnabled = YES;
            state.scaler = [desc newSpatialScalerWithDevice:dev];
            if (!state.scaler) return NULL;
            MTLTextureDescriptor* td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:
                    (MTLPixelFormat)format width:ow height:oh mipmapped:NO];
            // Required by MTLFXSpatialScalerBase.outputTexture in the installed SDK.
            td.storageMode = MTLStorageModePrivate;
            td.usage = state.scaler.outputTextureUsage | MTLTextureUsageShaderRead;
            state.output = [dev newTextureWithDescriptor:td];
            state.output.label = @"MetalMod MetalFX reconstruction";
            if (!state.output) return NULL;
            td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:
                    (MTLPixelFormat)format width:iw height:ih mipmapped:NO];
            td.storageMode = MTLStorageModePrivate;
            td.usage = state.scaler.colorTextureUsage | MTLTextureUsageRenderTarget;
            state.antialiasedInput = [dev newTextureWithDescriptor:td];
            state.antialiasedInput.label = @"MetalMod spatial anti-aliased scene";
            if (!state.antialiasedInput) return NULL;
            NSString* msl = [NSString stringWithUTF8String:MMMSpatialShaderSource];
            NSError* error = nil;
            id<MTLLibrary> lib = [dev newLibraryWithSource:msl options:nil error:&error];
            if (!lib) return NULL;
            MTLRenderPipelineDescriptor* pd = [MTLRenderPipelineDescriptor new];
            pd.vertexFunction = [lib newFunctionWithName:@"vs"];
            pd.fragmentFunction = [lib newFunctionWithName:@"fs"];
            pd.colorAttachments[0].pixelFormat = (MTLPixelFormat)format;
            state.recoveryPipeline = [dev newRenderPipelineStateWithDescriptor:pd error:&error];
            pd.fragmentFunction = [lib newFunctionWithName:@"antialias"];
            state.antialiasPipeline = [dev newRenderPipelineStateWithDescriptor:pd error:&error];
            MTLSamplerDescriptor* sd = [MTLSamplerDescriptor new];
            sd.minFilter = MTLSamplerMinMagFilterLinear; sd.magFilter = MTLSamplerMinMagFilterLinear;
            sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
            sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
            state.sampler = [dev newSamplerStateWithDescriptor:sd];
            if (!state.recoveryPipeline || !state.antialiasPipeline || !state.sampler) return NULL;
            return (__bridge_retained void*)state;
        } @catch (NSException* error) {
            NSLog(@"[MetalMod] MetalFX creation rejected: %@", error.reason);
            return NULL;
        }
    }
}

void mmm_fx_spatial_release(void* handle) {
    if (handle) { MMMSpatialState* released = (__bridge_transfer MMMSpatialState*)handle; (void)released; }
}
void mmm_fx_spatial_set_antialias(void* handle, bool enabled) {
    if (handle) ((__bridge MMMSpatialState*)handle).antialiasEnabled = enabled;
}
bool mmm_fx_spatial_healthy(void* handle) {
    return handle && !((__bridge MMMSpatialState*)handle)->failed.load();
}

int64_t mmm_fx_spatial_gpu_duration_ns(void* handle) {
    if (!handle) return -1;
    MMMSpatialState* state = (__bridge MMMSpatialState*)handle;
    std::lock_guard<std::mutex> guard(state->timingMutex);
    return state->failed.load() ? -1 : state->gpuDurationNs;
}

int32_t mmm_fx_spatial_encode(void* handle, void* commandBuffer, void* source, void* destination,
                             bool plainScale) {
    if (!handle || !commandBuffer || !source || !destination) return -1;
    MMMSpatialState* state = (__bridge MMMSpatialState*)handle;
    id<MTLCommandBuffer> cb = (__bridge id<MTLCommandBuffer>)commandBuffer;
    id<MTLTexture> input = (__bridge id<MTLTexture>)source;
    id<MTLTexture> dest = (__bridge id<MTLTexture>)destination;
    id<MTLFXSpatialScaler> fx = state.scaler;
    if (state->failed.load()) return -2;
    if (cb.status != MTLCommandBufferStatusNotEnqueued || input == dest
            || cb.device != input.device || input.device != dest.device
            || input.device != state.output.device
            || input.textureType != MTLTextureType2D || dest.textureType != MTLTextureType2D
            || input.sampleCount != 1 || dest.sampleCount != 1
            || input.pixelFormat != fx.colorTextureFormat || dest.pixelFormat != fx.outputTextureFormat
            || input.width != fx.inputWidth || input.height != fx.inputHeight
            || dest.width != fx.outputWidth || dest.height != fx.outputHeight
            || (input.usage & fx.colorTextureUsage) != fx.colorTextureUsage
            || (dest.usage & MTLTextureUsageRenderTarget) == 0) return -3;
    @autoreleasepool {
        @try {
            if (plainScale) {
                MTLRenderPassDescriptor* pd = [MTLRenderPassDescriptor renderPassDescriptor];
                pd.colorAttachments[0].texture = dest;
                pd.colorAttachments[0].loadAction = MTLLoadActionDontCare;
                pd.colorAttachments[0].storeAction = MTLStoreActionStore;
                id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:pd];
                if (!enc) return -4;
                [enc setRenderPipelineState:state.recoveryPipeline];
                [enc setFragmentTexture:input atIndex:0];
                [enc setFragmentSamplerState:state.sampler atIndex:0];
                [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
                [enc endEncoding];
            } else {
                if (state.antialiasEnabled) {
                    MTLRenderPassDescriptor* pd = [MTLRenderPassDescriptor renderPassDescriptor];
                    pd.colorAttachments[0].texture = state.antialiasedInput;
                    pd.colorAttachments[0].loadAction = MTLLoadActionDontCare;
                    pd.colorAttachments[0].storeAction = MTLStoreActionStore;
                    id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:pd];
                    if (!enc) return -4;
                    [enc setRenderPipelineState:state.antialiasPipeline];
                    [enc setFragmentTexture:input atIndex:0];
                    [enc setFragmentSamplerState:state.sampler atIndex:0];
                    [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
                    [enc endEncoding];
                }
                fx.colorTexture = state.antialiasEnabled ? state.antialiasedInput : input;
                fx.outputTexture = state.output;
                fx.inputContentWidth = input.width; fx.inputContentHeight = input.height;
                [fx encodeToCommandBuffer:cb];
                id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
                if (!blit) return -4;
                [blit copyFromTexture:state.output sourceSlice:0 sourceLevel:0
                        sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(dest.width,dest.height,1)
                        toTexture:dest destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
                [blit endEncoding];
            }
            // This command buffer contains only AA + FX + output copy, or plain recovery.
            // Measure one submission, never sum overlapping command-buffer spans as frame GPU time.
            const uint64_t sequence = ++state->submissionSequence;
            // Retains this generation until its final submitted use completes.
            [cb addCompletedHandler:^(id<MTLCommandBuffer> completed) {
                const double start = completed.GPUStartTime, end = completed.GPUEndTime;
                int64_t duration = !plainScale && completed.status == MTLCommandBufferStatusCompleted
                        && std::isfinite(start) && std::isfinite(end) && start > 0 && end > start
                        ? (int64_t)((end - start) * 1e9) : -1;
                {
                    // Completion handlers can arrive on different threads; an older sample
                    // must not replace the timing of a newer submission from this generation.
                    std::lock_guard<std::mutex> guard(state->timingMutex);
                    if (sequence > state->completedSequence) {
                        state->completedSequence = sequence;
                        state->gpuDurationNs = duration;
                    }
                }
                if (completed.status == MTLCommandBufferStatusError) {
                    state->failed.store(true);
                    NSLog(@"[MetalMod] MetalFX GPU failure: %@", completed.error);
                }
            }];
            return 0;
        } @catch (NSException* error) {
            // Caller must discard this uncommitted command buffer, then encode plain recovery anew.
            NSLog(@"[MetalMod] MetalFX encode rejected: %@", error.reason);
            return -5;
        }
    }
}
