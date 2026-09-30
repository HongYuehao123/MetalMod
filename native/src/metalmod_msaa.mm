// Spatial input coverage AA. Public engine attachments stay single-sampled; private 4x companions
// are used only by raster passes belonging to the registered world color/depth pair.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <objc/runtime.h>
#include "metalmod/metalmod_msaa.h"

static id<MTLTexture> gColor, gDepth;
static NSMutableDictionary<NSValue*, id<MTLTexture>>* gCompanions;
static NSMutableDictionary<NSString*, id<MTLRenderPipelineState>>* gSeedStates;
static NSMutableSet<NSValue*>* gInvalid;
static id<MTLLibrary> gSeedLibrary;
static id<MTLDepthStencilState> gSeedDepth;
static char gEncoderKey;

static id<MTLTexture> root(id<MTLTexture> texture) {
    while (texture.parentTexture) texture = texture.parentTexture;
    return texture;
}
static NSValue* key(id<MTLTexture> texture) {
    return [NSValue valueWithPointer:(__bridge const void*)root(texture)];
}
static id<MTLTexture> companion(id<MTLTexture> texture) {
    if (!texture) return nil;
    NSValue* k = key(texture);
    id<MTLTexture> result = gCompanions[k];
    if (result) return result;
    if (texture.textureType != MTLTextureType2D || texture.sampleCount != 1) return nil;
    MTLTextureDescriptor* d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:texture.pixelFormat
        width:texture.width height:texture.height mipmapped:NO];
    d.textureType = MTLTextureType2DMultisample;
    d.sampleCount = 4;
    d.storageMode = MTLStorageModePrivate;
    d.usage = MTLTextureUsageRenderTarget;
    result = [texture.device newTextureWithDescriptor:d];
    if (result) { result.label = @"MetalMod Spatial 4x coverage"; gCompanions[k] = result; [gInvalid addObject:k]; }
    return result;
}
int32_t mmm_msaa_world(void* color, void* depth) {
    @autoreleasepool {
        id<MTLTexture> c = root((__bridge id<MTLTexture>)color);
        id<MTLTexture> d = root((__bridge id<MTLTexture>)depth);
        if (c == gColor && d == gDepth) return c ? 4 : 0;
        // Caller changes registrations only at a drained frame boundary.
        gColor = nil; gDepth = nil; gCompanions = nil; gInvalid = nil;
        if (!c && !d) { gSeedLibrary = nil; gSeedStates = nil; gSeedDepth = nil; return 0; }
        if (!c || !d || c.device != d.device || c.width != d.width || c.height != d.height
            || ![c.device supportsTextureSampleCount:4]) return -1;
        gCompanions = [NSMutableDictionary dictionary];
        gInvalid = [NSMutableSet set];
        if (!companion(c) || !companion(d)) { gCompanions = nil; return -1; }
        gColor = c; gDepth = d;
        return 4;
    }
}
void mmm_msaa_written(id<MTLTexture> texture) {
    if (texture && gCompanions[key(texture)]) [gInvalid addObject:key(texture)];
}
void mmm_msaa_forget(id<MTLTexture> texture) {
    if (!texture) return;
    // Releasing a view must not unregister its live parent.
    if (texture == gColor || texture == gDepth) {
        gColor = nil; gDepth = nil; gCompanions = nil; gInvalid = nil;
    } else if (!texture.parentTexture) {
        [gCompanions removeObjectForKey:key(texture)];
        [gInvalid removeObject:key(texture)];
    }
}
void mmm_msaa_tag(id<MTLRenderCommandEncoder> encoder, bool enabled) {
    objc_setAssociatedObject(encoder, &gEncoderKey, enabled ? @YES : nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}
bool mmm_msaa_encoder(id<MTLRenderCommandEncoder> encoder) {
    return [objc_getAssociatedObject(encoder, &gEncoderKey) boolValue];
}

// Seeding retains exact resolved color and depth through arbitrary engine writes between passes.
// Per-sample coverage/depth survives ordinary LOAD passes. Only an external write invalidates
// a companion, in which case resolved data must replace it (e.g. an engine depth copy).
static id<MTLRenderPipelineState> seedState(id<MTLDevice> device, MTLPixelFormat color, MTLPixelFormat depth) {
    if (!gSeedLibrary || gSeedLibrary.device != device) {
        NSString* source = @R"MSL(
#include <metal_stdlib>
using namespace metal;
struct V { float4 position [[position]]; };
vertex V seedVertex(uint id [[vertex_id]]) {
    float2 p = float2((id << 1) & 2, id & 2);
    return {float4(p * 2.0 - 1.0, 0.0, 1.0)};
}
struct Flags { uint colorLoad; uint depthLoad; float depthClear; float pad; float4 colorClear; };
struct CD { float4 color [[color(0)]]; float depth [[depth(any)]]; };
fragment CD seedBoth(V v [[stage_in]], texture2d<float, access::read> c [[texture(0)]],
    depth2d<float, access::read> d [[texture(1)]], constant Flags& f [[buffer(0)]]) {
    uint2 p = uint2(v.position.xy);
    return {f.colorLoad ? c.read(p) : f.colorClear, f.depthLoad ? d.read(p) : f.depthClear};
}
fragment float4 seedColor(V v [[stage_in]], texture2d<float, access::read> c [[texture(0)]],
    constant Flags& f [[buffer(0)]]) { return f.colorLoad ? c.read(uint2(v.position.xy)) : f.colorClear; }
fragment float seedDepth(V v [[stage_in]], depth2d<float, access::read> d [[texture(1)]],
    constant Flags& f [[buffer(0)]]) [[depth(any)]] {
    return f.depthLoad ? d.read(uint2(v.position.xy)) : f.depthClear;
}
)MSL";
        NSError* error = nil;
        gSeedLibrary = [device newLibraryWithSource:source options:nil error:&error];
        if (!gSeedLibrary) { NSLog(@"[MetalMod] MSAA seed library: %@", error); return nil; }
        gSeedStates = [NSMutableDictionary dictionary];
        MTLDepthStencilDescriptor* ds = [MTLDepthStencilDescriptor new];
        ds.depthCompareFunction = MTLCompareFunctionAlways;
        ds.depthWriteEnabled = YES;
        gSeedDepth = [device newDepthStencilStateWithDescriptor:ds];
    }
    NSString* k = [NSString stringWithFormat:@"%lu:%lu", (unsigned long)color, (unsigned long)depth];
    id<MTLRenderPipelineState> state = gSeedStates[k];
    if (state) return state;
    MTLRenderPipelineDescriptor* p = [MTLRenderPipelineDescriptor new];
    p.vertexFunction = [gSeedLibrary newFunctionWithName:@"seedVertex"];
    p.fragmentFunction = [gSeedLibrary newFunctionWithName:color && depth ? @"seedBoth" : color ? @"seedColor" : @"seedDepth"];
    p.rasterSampleCount = 4;
    p.colorAttachments[0].pixelFormat = color;
    p.depthAttachmentPixelFormat = depth;
    NSError* error = nil;
    state = [device newRenderPipelineStateWithDescriptor:p error:&error];
    if (!state) NSLog(@"[MetalMod] MSAA seed pipeline: %@", error);
    else gSeedStates[k] = state;
    return state;
}
bool mmm_msaa_prepare(id<MTLCommandBuffer> buffer, MTLRenderPassDescriptor* pass) {
    id<MTLTexture> c = pass.colorAttachments[0].texture;
    id<MTLTexture> d = pass.depthAttachment.texture;
    if (!gColor || (root(c) != gColor && root(d) != gDepth)) return false;
    // Backend advertises one color attachment. Reject unfamiliar layouts rather than partially
    // multisampling a pass with mismatched attachments.
    if (pass.colorAttachments[1].texture || (c && d && (c.width != d.width || c.height != d.height))) return false;
    id<MTLTexture> mc = companion(c), md = companion(d);
    if ((c && !mc) || (d && !md)) return false;
    bool loadC = c && pass.colorAttachments[0].loadAction == MTLLoadActionLoad && [gInvalid containsObject:key(c)];
    bool loadD = d && pass.depthAttachment.loadAction == MTLLoadActionLoad && [gInvalid containsObject:key(d)];
    if (loadC || loadD) {
        id<MTLRenderPipelineState> state = seedState(buffer.device, loadC ? c.pixelFormat : MTLPixelFormatInvalid,
                                                    loadD ? d.pixelFormat : MTLPixelFormatInvalid);
        if (!state) return false;
        MTLRenderPassDescriptor* seed = [MTLRenderPassDescriptor renderPassDescriptor];
        if (loadC) { seed.colorAttachments[0].texture = mc; seed.colorAttachments[0].loadAction = MTLLoadActionDontCare;
            seed.colorAttachments[0].storeAction = MTLStoreActionStore; }
        if (loadD) { seed.depthAttachment.texture = md; seed.depthAttachment.loadAction = MTLLoadActionDontCare;
            seed.depthAttachment.storeAction = MTLStoreActionStore; }
        id<MTLRenderCommandEncoder> e = [buffer renderCommandEncoderWithDescriptor:seed];
        if (!e) return false;
        struct { uint32_t colorLoad, depthLoad; float depthClear, pad; float colorClear[4]; } flags = {};
        flags.colorLoad = loadC; flags.depthLoad = loadD;
        flags.depthClear = pass.depthAttachment.clearDepth;
        MTLClearColor clear = pass.colorAttachments[0].clearColor;
        flags.colorClear[0] = clear.red; flags.colorClear[1] = clear.green;
        flags.colorClear[2] = clear.blue; flags.colorClear[3] = clear.alpha;
        [e setRenderPipelineState:state];
        if (loadD) [e setDepthStencilState:gSeedDepth];
        if (c) [e setFragmentTexture:c atIndex:0];
        if (d) [e setFragmentTexture:d atIndex:1];
        [e setFragmentBytes:&flags length:sizeof(flags) atIndex:0];
        id<MTLTexture> size = c ?: d;
        [e setViewport:(MTLViewport){0,0,(double)size.width,(double)size.height,0,1}];
        [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [e endEncoding];
    }
    if (c) { pass.colorAttachments[0].texture = mc; pass.colorAttachments[0].resolveTexture = c;
        pass.colorAttachments[0].storeAction = MTLStoreActionStoreAndMultisampleResolve; }
    if (d) { pass.depthAttachment.texture = md; pass.depthAttachment.resolveTexture = d;
        pass.depthAttachment.storeAction = MTLStoreActionStoreAndMultisampleResolve;
        pass.depthAttachment.depthResolveFilter = MTLMultisampleDepthResolveFilterMax; }
    if (c) [gInvalid removeObject:key(c)];
    if (d) [gInvalid removeObject:key(d)];
    return true;
}
