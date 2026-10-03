#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include "metalmod/metalmod_metalfx.h"
#include <atomic>

static constexpr const char* temporalColorSource = R"MSL(
#include <metal_stdlib>
using namespace metal;
vertex float4 colorVertex(uint i [[vertex_id]]) {
    float2 p = float2((i << 1) & 2, i & 2);
    return float4(p * 2 - 1, 0, 1);
}
fragment float4 decodeSDR(float4 position [[position]], texture2d<float> source [[texture(0)]]) {
    float4 c = source.read(uint2(position.xy));
    float3 linear = select(pow((c.rgb + 0.055) / 1.055, float3(2.4)),
                           c.rgb / 12.92, c.rgb <= 0.04045);
    return float4(linear, c.a);
}
fragment float4 encodeSDR(float4 position [[position]], texture2d<float> source [[texture(0)]]) {
    float4 c = source.read(uint2(position.xy));
    // SDR output: bounded reconstruction overshoot, with no HDR/tone-mapping side effects.
    float3 linear = clamp(c.rgb, float3(0), float3(1));
    float3 sdr = select(1.055 * pow(linear, float3(1.0 / 2.4)) - 0.055,
                       linear * 12.92, linear <= 0.0031308);
    return float4(sdr, c.a);
}
)MSL";

@interface MMMTemporalColorState : NSObject {
@public
    std::atomic<bool> failed;
}
@property(nonatomic, strong) id<MTLDevice> device;
@property(nonatomic, strong) id<MTLCommandQueue> queue;
@property(nonatomic) MTLPixelFormat sdrFormat;
@property(nonatomic, strong) id<MTLRenderPipelineState> decodePipeline;
@property(nonatomic, strong) id<MTLRenderPipelineState> encodePipeline;
@end
@implementation MMMTemporalColorState
@end

void* mmm_fx_temporal_color_create(void* device, int64_t format) {
    if (!device || (format != MTLPixelFormatRGBA8Unorm && format != MTLPixelFormatBGRA8Unorm)) return NULL;
    @autoreleasepool {
        @try {
            id<MTLDevice> dev = (__bridge id<MTLDevice>)device;
            NSError* error = nil;
            id<MTLLibrary> library = [dev newLibraryWithSource:[NSString stringWithUTF8String:temporalColorSource]
                    options:nil error:&error];
            if (!library) { NSLog(@"[MetalMod] Temporal colour shaders: %@",error); return NULL; }
            MMMTemporalColorState* state = [MMMTemporalColorState new];
            state->failed.store(false);
            state.device = dev; state.sdrFormat = (MTLPixelFormat)format;
            MTLRenderPipelineDescriptor* pd = [MTLRenderPipelineDescriptor new];
            pd.vertexFunction = [library newFunctionWithName:@"colorVertex"];
            pd.fragmentFunction = [library newFunctionWithName:@"decodeSDR"];
            pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
            state.decodePipeline = [dev newRenderPipelineStateWithDescriptor:pd error:&error];
            pd.fragmentFunction = [library newFunctionWithName:@"encodeSDR"];
            pd.colorAttachments[0].pixelFormat = (MTLPixelFormat)format;
            state.encodePipeline = [dev newRenderPipelineStateWithDescriptor:pd error:&error];
            if (!state.decodePipeline || !state.encodePipeline) {
                NSLog(@"[MetalMod] Temporal colour pipelines: %@",error); return NULL;
            }
            return (__bridge_retained void*)state;
        } @catch (NSException* error) {
            NSLog(@"[MetalMod] Temporal colour creation rejected: %@",error.reason); return NULL;
        }
    }
}
void mmm_fx_temporal_color_release(void* handle) {
    if (handle) { MMMTemporalColorState* released = (__bridge_transfer MMMTemporalColorState*)handle; (void)released; }
}
bool mmm_fx_temporal_color_healthy(void* handle) {
    return handle && !((__bridge MMMTemporalColorState*)handle)->failed.load();
}
int32_t mmm_fx_temporal_color_encode(void* handle, void* commandBuffer, void* source, void* destination,
                                    bool toLinear) {
    if (!handle || !commandBuffer || !source || !destination) return -1;
    MMMTemporalColorState* state = (__bridge MMMTemporalColorState*)handle;
    if (state->failed.load()) return -2;
    id<MTLCommandBuffer> cb = (__bridge id<MTLCommandBuffer>)commandBuffer;
    id<MTLTexture> input = (__bridge id<MTLTexture>)source, output = (__bridge id<MTLTexture>)destination;
    if (cb.status != MTLCommandBufferStatusNotEnqueued || cb.device != state.device
            || (state.queue && cb.commandQueue != state.queue) || input == output
            || input.device != state.device || output.device != state.device
            || input.textureType != MTLTextureType2D || output.textureType != MTLTextureType2D
            || input.sampleCount != 1 || output.sampleCount != 1
            || input.width != output.width || input.height != output.height
            || input.storageMode == MTLStorageModeMemoryless || output.storageMode == MTLStorageModeMemoryless
            || input.pixelFormat != (toLinear ? state.sdrFormat : MTLPixelFormatRGBA16Float)
            || output.pixelFormat != (toLinear ? MTLPixelFormatRGBA16Float : state.sdrFormat)
            || !(input.usage & MTLTextureUsageShaderRead) || !(output.usage & MTLTextureUsageRenderTarget)) return -3;
    @autoreleasepool {
        @try {
            MTLRenderPassDescriptor* pd = [MTLRenderPassDescriptor renderPassDescriptor];
            pd.colorAttachments[0].texture = output;
            pd.colorAttachments[0].loadAction = MTLLoadActionDontCare;
            pd.colorAttachments[0].storeAction = MTLStoreActionStore;
            id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:pd];
            if (!enc) return -4;
            [enc setRenderPipelineState:toLinear ? state.decodePipeline : state.encodePipeline];
            [enc setFragmentTexture:input atIndex:0];
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [enc endEncoding];
            [cb addCompletedHandler:^(id<MTLCommandBuffer> completed) {
                if (completed.status == MTLCommandBufferStatusError) {
                    state->failed.store(true);
                    NSLog(@"[MetalMod] Temporal colour GPU failure: %@",completed.error);
                }
            }];
            state.queue = cb.commandQueue;
            return 0;
        } @catch (NSException* error) {
            state->failed.store(true);
            NSLog(@"[MetalMod] Temporal colour encode rejected: %@",error.reason); return -5;
        }
    }
}
