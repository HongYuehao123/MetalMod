#import <Metal/Metal.h>

#include "metalmod/metalmod_aa.h"

// A compact FXAA edge search. The alpha channel comes from the centre pixel so translucent world
// passes keep their coverage. The full-screen triangle follows the presentation pass's Y convention.
static const char* kAaSource = R"MSL(
#include <metal_stdlib>
using namespace metal;

struct VertexOut { float4 position [[position]]; float2 uv; };

vertex VertexOut aa_vertex(uint id [[vertex_id]]) {
    float2 p = float2((float)((id << 1) & 2), (float)(id & 2));
    VertexOut out;
    out.position = float4(p * 2.0 - 1.0, 0.0, 1.0);
    out.uv = float2(p.x, 1.0 - p.y);
    return out;
}

static float luma(float3 rgb) {
    return dot(rgb, float3(0.299, 0.587, 0.114));
}

fragment float4 aa_fragment(VertexOut in [[stage_in]],
        texture2d<float> source [[texture(0)]], sampler sampleState [[sampler(0)]]) {
    float2 step = 1.0 / float2(source.get_width(), source.get_height());
    float4 centre = source.sample(sampleState, in.uv);
    float3 nw = source.sample(sampleState, in.uv + step * float2(-1.0, -1.0)).rgb;
    float3 ne = source.sample(sampleState, in.uv + step * float2( 1.0, -1.0)).rgb;
    float3 sw = source.sample(sampleState, in.uv + step * float2(-1.0,  1.0)).rgb;
    float3 se = source.sample(sampleState, in.uv + step * float2( 1.0,  1.0)).rgb;
    float lNW = luma(nw), lNE = luma(ne), lSW = luma(sw), lSE = luma(se);
    float lCentre = luma(centre.rgb);
    float lMin = min(lCentre, min(min(lNW, lNE), min(lSW, lSE)));
    float lMax = max(lCentre, max(max(lNW, lNE), max(lSW, lSE)));
    // The earlier 1/8 relative threshold treated distant foliage and fine textures as edges.
    // Leave those pixels alone; use the filter for the stronger silhouette gradients it can help.
    if (lMax - lMin < max(0.0625, lMax * 0.20)) return centre;

    float2 direction = float2(-((lNW + lNE) - (lSW + lSE)),
                               (lNW + lSW) - (lNE + lSE));
    float reduce = max((lNW + lNE + lSW + lSE) * (0.25 * 0.0312), 1.0 / 128.0);
    direction = clamp(direction / (min(abs(direction.x), abs(direction.y)) + reduce),
                      float2(-4.0), float2(4.0)) * step;
    float3 a = 0.5 * (source.sample(sampleState, in.uv + direction * (-1.0 / 6.0)).rgb
                    + source.sample(sampleState, in.uv + direction * ( 1.0 / 6.0)).rgb);
    float3 b = 0.5 * a + 0.25 * (
            source.sample(sampleState, in.uv + direction * -0.5).rgb
          + source.sample(sampleState, in.uv + direction *  0.5).rgb);
    float lB = luma(b);
    float3 filtered = lB < lMin || lB > lMax ? a : b;
    return float4(mix(centre.rgb, filtered, 0.65), centre.a);
}
)MSL";

static id<MTLDevice> gAaDevice = nil;
static id<MTLRenderPipelineState> gAaPipeline = nil;
static id<MTLSamplerState> gAaSampler = nil;

static bool ensure_aa_pipeline(id<MTLDevice> device) {
    if (gAaDevice == device && gAaPipeline != nil && gAaSampler != nil) return true;
    @autoreleasepool {
        NSError* error = nil;
        id<MTLLibrary> library = [device newLibraryWithSource:
                [NSString stringWithUTF8String:kAaSource] options:nil error:&error];
        if (library == nil) {
            NSLog(@"[MetalMod] AA shader failed: %@", error.localizedDescription);
            return false;
        }
        MTLRenderPipelineDescriptor* descriptor = [[MTLRenderPipelineDescriptor alloc] init];
        descriptor.vertexFunction = [library newFunctionWithName:@"aa_vertex"];
        descriptor.fragmentFunction = [library newFunctionWithName:@"aa_fragment"];
        descriptor.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
        descriptor.rasterSampleCount = 1;
        id<MTLRenderPipelineState> pipeline =
                [device newRenderPipelineStateWithDescriptor:descriptor error:&error];
        if (pipeline == nil) {
            NSLog(@"[MetalMod] AA pipeline failed: %@", error.localizedDescription);
            return false;
        }
        MTLSamplerDescriptor* samplerDescriptor = [[MTLSamplerDescriptor alloc] init];
        samplerDescriptor.minFilter = MTLSamplerMinMagFilterLinear;
        samplerDescriptor.magFilter = MTLSamplerMinMagFilterLinear;
        samplerDescriptor.sAddressMode = MTLSamplerAddressModeClampToEdge;
        samplerDescriptor.tAddressMode = MTLSamplerAddressModeClampToEdge;
        id<MTLSamplerState> sampler = [device newSamplerStateWithDescriptor:samplerDescriptor];
        if (sampler == nil) return false;
        gAaDevice = device;
        gAaPipeline = pipeline;
        gAaSampler = sampler;
        return true;
    }
}

int mmm_aa_run(void* deviceHandle, void* queueHandle, void* sourceHandle, void* scratchHandle) {
    id<MTLDevice> device = (__bridge id<MTLDevice>)deviceHandle;
    id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)queueHandle;
    id<MTLTexture> source = (__bridge id<MTLTexture>)sourceHandle;
    id<MTLTexture> scratch = (__bridge id<MTLTexture>)scratchHandle;
    if (device == nil || queue == nil || source == nil || scratch == nil || source == scratch)
        return -1;
    if (source.width != scratch.width || source.height != scratch.height
            || source.pixelFormat != MTLPixelFormatRGBA8Unorm
            || scratch.pixelFormat != MTLPixelFormatRGBA8Unorm)
        return -2;
    if (!ensure_aa_pipeline(device)) return -3;

    @autoreleasepool {
        id<MTLCommandBuffer> buffer = [queue commandBuffer];
        if (buffer == nil) return -4;
        buffer.label = @"MetalMod world FXAA";
        MTLRenderPassDescriptor* pass = [MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].texture = scratch;
        pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> encoder = [buffer renderCommandEncoderWithDescriptor:pass];
        if (encoder == nil) return -5;
        [encoder setRenderPipelineState:gAaPipeline];
        [encoder setFragmentTexture:source atIndex:0];
        [encoder setFragmentSamplerState:gAaSampler atIndex:0];
        [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [encoder endEncoding];
        id<MTLBlitCommandEncoder> blit = [buffer blitCommandEncoder];
        if (blit == nil) return -6;
        [blit copyFromTexture:scratch sourceSlice:0 sourceLevel:0
                sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(source.width, source.height, 1)
                toTexture:source destinationSlice:0 destinationLevel:0
                destinationOrigin:MTLOriginMake(0, 0, 0)];
        [blit endEncoding];
        [buffer commit];
    }
    return 0;
}
