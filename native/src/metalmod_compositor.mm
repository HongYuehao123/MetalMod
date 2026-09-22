#import "metalmod_internal.h"

@interface MetalModState (SpatialInternal)
- (BOOL)setupSpatialScaler;
@end

@interface MetalModState (TemporalInternal)
- (BOOL)setupTemporalScaler;
@end

@interface MetalModState (InterpolatorInternal)
- (BOOL)setupFrameInterpolator;
@end

@implementation MetalModState (Compositor)

- (BOOL)recreatePipelines {
    if (!self.device) return NO;

    // With upscaling off and frame generation off there is nothing to render into these textures,
    // so do not allocate them: at 2560x1440 BGRA8 they are ~14 MB each, and three of them would be
    // ~42 MB of GPU memory held for no reason in the default configuration.
    BOOL pipelineInUse = (self.config.scalingMode != METALMOD_SCALING_OFF)
                         || self.config.frameGenerationEnabled;
    if (!pipelineInUse) {
        for (int i = 0; i < 2; i++) {
            self->_historyTextures[i] = nil;
        }
        self.upscaledTexture = nil;
        self.prevColorTexture = nil;
        self.interpolatedTexture = nil;
        self.spatialScaler = nil;
        self.temporalScaler = nil;
        self.frameInterpolator = nil;
        self.hasHistory = NO;
        return YES;
    }

    // 1. Allocate textures for output dimensions
    MTLTextureDescriptor *texDesc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:self.config.enableHDR ? MTLPixelFormatRGBA16Float : MTLPixelFormatBGRA8Unorm
                                                                                       width:self.config.outputWidth
                                                                                      height:self.config.outputHeight
                                                                                   mipmapped:NO];
    texDesc.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsageRenderTarget;
    texDesc.storageMode = MTLStorageModePrivate;

    _historyTextures[0] = [self.device newTextureWithDescriptor:texDesc];
    _historyTextures[1] = [self.device newTextureWithDescriptor:texDesc];
    self.currentHistoryIndex = 0;
    self.upscaledTexture = _historyTextures[0];
    self.prevColorTexture = _historyTextures[1];
    self.interpolatedTexture = [self.device newTextureWithDescriptor:texDesc];

    // 2. Setup Scalers based on config
    if (self.config.scalingMode == METALMOD_SCALING_SPATIAL) {
        if (![self setupSpatialScaler]) {
            NSLog(@"[MetalMod] Failed to configure Spatial Scaler.");
            return NO;
        }
    } else if (self.config.scalingMode == METALMOD_SCALING_TEMPORAL) {
        if (![self setupTemporalScaler]) {
            NSLog(@"[MetalMod] Failed to configure Temporal Scaler.");
            return NO;
        }
    }

    // 3. Setup Frame Interpolator if enabled
    if (self.config.frameGenerationEnabled) {
        if (![self setupFrameInterpolator]) {
            NSLog(@"[MetalMod] Failed to configure Frame Interpolator.");
            return NO;
        }
    }

    // 4. Setup Compositor Render Pipeline using in-memory Metal Shading Language
    static NSString *const kCompositorMSL = @R"(
#include <metal_stdlib>
using namespace metal;

struct VertexOut {
    float4 position [[position]];
    float2 uv;
};

vertex VertexOut fullScreenVertexShader(uint vertexID [[vertex_id]]) {
    VertexOut out;
    out.uv = float2((vertexID << 1) & 2, vertexID & 2);
    out.position = float4(out.uv * float2(2.0f, -2.0f) + float2(-1.0f, 1.0f), 0.0f, 1.0f);
    return out;
}

fragment float4 compositeUIFragmentShader(
    VertexOut in [[stage_in]],
    texture2d<float, access::sample> worldTexture [[texture(0)]],
    texture2d<float, access::sample> uiTexture [[texture(1)]],
    sampler textureSampler [[sampler(0)]]
) {
    float4 worldColor = worldTexture.sample(textureSampler, in.uv);
    float4 uiColor = uiTexture.sample(textureSampler, in.uv);
    float3 finalRGB = mix(worldColor.rgb, uiColor.rgb, uiColor.a);
    return float4(finalRGB, 1.0f);
}

fragment float4 passthroughFragmentShader(
    VertexOut in [[stage_in]],
    texture2d<float, access::sample> sourceTexture [[texture(0)]],
    sampler textureSampler [[sampler(0)]]
) {
    return sourceTexture.sample(textureSampler, in.uv);
}
)";

    NSError *error = nil;
    MTLCompileOptions *options = [MTLCompileOptions new];
    id<MTLLibrary> library = [self.device newLibraryWithSource:kCompositorMSL options:options error:&error];
    if (!library) {
        NSLog(@"[MetalMod] Failed to compile runtime Metal shader: %@", error);
    }

    if (library) {
        id<MTLFunction> vertexFunc = [library newFunctionWithName:@"fullScreenVertexShader"];
        id<MTLFunction> compFragFunc = [library newFunctionWithName:@"compositeUIFragmentShader"];
        id<MTLFunction> passFragFunc = [library newFunctionWithName:@"passthroughFragmentShader"];

        MTLRenderPipelineDescriptor *pDesc = [MTLRenderPipelineDescriptor new];
        pDesc.vertexFunction = vertexFunc;
        pDesc.fragmentFunction = compFragFunc;
        pDesc.colorAttachments[0].pixelFormat = self.config.enableHDR ? MTLPixelFormatRGBA16Float : MTLPixelFormatBGRA8Unorm;
        pDesc.colorAttachments[0].blendingEnabled = YES;
        pDesc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
        pDesc.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;

        self.compositePipelineState = [self.device newRenderPipelineStateWithDescriptor:pDesc error:&error];

        pDesc.fragmentFunction = passFragFunc;
        pDesc.colorAttachments[0].blendingEnabled = NO;
        self.passthroughPipelineState = [self.device newRenderPipelineStateWithDescriptor:pDesc error:&error];

        MTLSamplerDescriptor *sDesc = [MTLSamplerDescriptor new];
        sDesc.minFilter = MTLSamplerMinMagFilterLinear;
        sDesc.magFilter = MTLSamplerMinMagFilterLinear;
        sDesc.sAddressMode = MTLSamplerAddressModeClampToEdge;
        sDesc.tAddressMode = MTLSamplerAddressModeClampToEdge;
        self.samplerState = [self.device newSamplerStateWithDescriptor:sDesc];
    }

    self.hasHistory = NO;
    return YES;
}

- (void)compositeUIOnTarget:(id<MTLRenderCommandEncoder>)encoder
               worldTexture:(id<MTLTexture>)worldTex
                  uiTexture:(id<MTLTexture>)uiTex {
    if (!encoder || !worldTex) return;

    if (uiTex && self.compositePipelineState) {
        [encoder setRenderPipelineState:self.compositePipelineState];
        [encoder setFragmentTexture:worldTex atIndex:0];
        [encoder setFragmentTexture:uiTex atIndex:1];
        [encoder setFragmentSamplerState:self.samplerState atIndex:0];
        [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    } else if (self.passthroughPipelineState) {
        [encoder setRenderPipelineState:self.passthroughPipelineState];
        [encoder setFragmentTexture:worldTex atIndex:0];
        [encoder setFragmentSamplerState:self.samplerState atIndex:0];
        [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    }
}

@end
