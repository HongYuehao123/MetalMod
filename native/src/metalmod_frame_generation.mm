#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#include "metalmod/metalmod_frame_generation.h"
#include "metalmod/metalmod_metalfx.h"
#include "metalmod/metalmod_interpolation.h"
#include "metalmod/metalmod_metal.h"
#include <atomic>
#include <cmath>
#include <algorithm>
#include <mutex>
#include <chrono>
#include <thread>
#include <vector>


// Hand depth and the pre-GUI colour boundary provide native foreground coverage.
// World/postprocessing differences are never submitted as inferred composited UI.
static const char* foregroundSource=R"MSL(
#include <metal_stdlib>
using namespace metal;
kernel void handCoverage(depth2d<float> depth [[texture(0)]],
        texture2d<half,access::write> mask [[texture(1)]],uint2 p [[thread_position_in_grid]]) {
    if(p.x>=mask.get_width()||p.y>=mask.get_height())return;
    float d=depth.read(p);mask.write(half4(isfinite(d)&&d>0?1:0),p);
}
struct Quality { atomic_uint moving; atomic_uint repeated; };
kernel void qualityCheck(texture2d<float> generated [[texture(0)]],texture2d<float> current [[texture(1)]],
    texture2d<float> previous [[texture(2)]],texture2d<float> motion [[texture(3)]],
    texture2d<float> coverage [[texture(4)]],texture2d<float> oldCoverage [[texture(5)]],device Quality& quality [[buffer(0)]],uint2 sample [[thread_position_in_grid]]) {
    uint2 p=sample*8+4;
    if(p.x>=current.get_width()||p.y>=current.get_height())return;
    constexpr sampler nearest(coord::normalized,address::clamp_to_edge,filter::nearest);
    float2 uv=(float2(p)+0.5)/float2(current.get_width(),current.get_height());
    if(coverage.sample(nearest,uv).r>0||oldCoverage.sample(nearest,uv).r>0) return;
    float2 flow=motion.sample(nearest,uv).xy*float2(current.get_width(),current.get_height())/float2(motion.get_width(),motion.get_height());
    float3 c=current.read(p).rgb;
    // Flat colours need no visible displacement; only changing textured world pixels count.
    if(length(flow)>0.75&&any(abs(c-previous.read(p).rgb)>float3(0.015))) {
        atomic_fetch_add_explicit(&quality.moving,1,memory_order_relaxed);
        if(all(abs(c-generated.read(p).rgb)<float3(0.002)))atomic_fetch_add_explicit(&quality.repeated,1,memory_order_relaxed);
    }
}
vertex float4 foregroundVertex(uint i [[vertex_id]]) {
    float2 p=float2((i<<1)&2,i&2);return float4(p*2-1,0,1);
}
fragment float4 foregroundSDR(float4 position [[position]],texture2d<float> generated [[texture(0)]],
        texture2d<float> real [[texture(1)]],texture2d<float> mask [[texture(2)]], texture2d<float> beforeGui [[texture(3)]],
        texture2d<float> current [[texture(4)]],texture2d<float> motion [[texture(5)]],
        texture2d<float> previous [[texture(6)]],texture2d<float> coverage [[texture(7)]],
        texture2d<float> oldCoverage [[texture(8)]],texture2d<float> guiCoverage [[texture(9)]],const device uint2& quality [[buffer(0)]]) {
    uint2 p=uint2(position.xy);
    if(quality.x>=12&&quality.y*10>quality.x*7)return real.read(p);
    // Actual GUI fragments are recorded separately from full-screen vignette modulation.
    // Preserve the already composited native pixel, including genuine item translucency,
    // texture alpha, glint, hand lighting and any HUD drawn over it. Do not blend it twice.
    if(mask.read(p).r>0.5 || guiCoverage.read(p).r>0.01)return real.read(p);
    float4 c=generated.read(p);
    constexpr sampler smooth(coord::normalized,address::clamp_to_edge,filter::linear);
    float2 size=float2(current.get_width(),current.get_height()),uv=(float2(p)+0.5)/size;
    // Unsupported deformation/translucency stays native over current AND previous
    // visible coverage. Never borrow object motion from an enclosing world-space box.
    float protectedPixel=0;
    for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
        float2 q=uv+float2(x,y)/float2(coverage.get_width(),coverage.get_height());
        protectedPixel=max(protectedPixel,max(coverage.sample(smooth,q).r,oldCoverage.sample(smooth,q).r));
    }
    if(protectedPixel>0.01)return real.read(p);
    // A post-hand effect is not reconstructible from colour subtraction. Retain its
    // native result rather than adding shifted silhouettes into interpolated world.
    float3 worldSdr=select(1.055*pow(clamp(current.read(p).rgb,float3(0),float3(1)),float3(1.0/2.4))-0.055,
                          current.read(p).rgb*12.92,current.read(p).rgb<=0.0031308);
    if(any(abs(beforeGui.read(p).rgb-worldSdr)>float3(1.5/255.0)))return real.read(p);
    float3 linear=clamp(c.rgb,float3(0),float3(1));
    float3 sdr=select(1.055*pow(linear,float3(1.0/2.4))-0.055,linear*12.92,linear<=0.0031308);
    // The vanilla vignette multiplies world colour after the pre-GUI boundary.
    // Exact HUD coverage excludes this full-screen modulation. Apply its same-frame
    // attenuation to the midpoint instead of replacing the world with the real frame.
    float3 before=beforeGui.read(p).rgb,after=real.read(p).rgb;
    float3 attenuation=select(float3(1),clamp(after/max(before,float3(1.0/255.0)),float3(0),float3(1)),before>float3(1.0/255.0));
    return float4(sdr*attenuation,c.a);
}
)MSL";

@interface MMMGameplayFrames : NSObject {
@public
    std::atomic<uint64_t> counters[17];
    std::mutex timingMutex;
    uint64_t lastDisplay, windowStart, windowDisplays;
    int lastRole;
    std::atomic<uint64_t> refreshNs;
    uint64_t ids[2];
    int index;
    bool captured, reset, handCaptured;
    float dt, nearPlane, farPlane, fov, jx, jy;
}
@property(nonatomic,strong) id<MTLDevice> device;
@property(nonatomic,strong) id<MTLCommandQueue> queue;
@property(nonatomic,strong) NSArray<id<MTLTexture>>* colors;
@property(nonatomic,strong) id<MTLTexture> ui;
@property(nonatomic,strong) id<MTLTexture> generated;
@property(nonatomic,strong) id<MTLTexture> sdr;
@property(nonatomic,strong) id<MTLTexture> handMask;
@property(nonatomic,strong) id<MTLTexture> beforeGui;
@property(nonatomic,strong) id<MTLTexture> oldCoverage;
@property(nonatomic,strong) id<MTLTexture> guiCoverage;
@property(nonatomic,strong) id<MTLComputePipelineState> handCoverage;
@property(nonatomic,strong) id<MTLComputePipelineState> qualityCheck;
@property(nonatomic,strong) id<MTLRenderPipelineState> foreground;
@property(nonatomic,assign) void* motion;
@property(nonatomic,assign) void* interpolation;
@property(nonatomic,assign) void* converter;
@end
@implementation MMMGameplayFrames
- (void)dealloc {
    mmm_fx_temporal_frame_release(_motion);
    mmm_fx_interpolation_release(_interpolation);
    mmm_fx_temporal_color_release(_converter);
}
@end
static id<MTLTexture> allocate(id<MTLDevice> dev,MTLPixelFormat format,int w,int h,MTLTextureUsage usage,bool priv=false) {
    auto d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
    d.usage=usage;d.storageMode=priv?MTLStorageModePrivate:MTLStorageModeShared;
    return [dev newTextureWithDescriptor:d];
}
void* mmm_fg_create(void* device,void* queue,int32_t iw,int32_t ih,int32_t ow,int32_t oh,int64_t format) {
    if(!device||!queue||!mmm_fx_interpolation_supported(device))return NULL;
    @autoreleasepool { @try {
        MMMGameplayFrames* s=[MMMGameplayFrames new];s.device=(__bridge id<MTLDevice>)device;
        s.queue=(__bridge id<MTLCommandQueue>)queue;if(s.queue.device!=s.device)return NULL;
        s.interpolation=mmm_fx_interpolation_create(device,iw,ih,ow,oh);
        s.motion=mmm_fx_motion_frame_create(device,iw,ih,format);
        s.converter=mmm_fx_temporal_color_create(device,format);
        if(!s.interpolation||!s.motion||!s.converter)return NULL;
        auto usage=[&](int role){return (MTLTextureUsage)mmm_fx_interpolation_texture_usage(s.interpolation,role);};
        auto a=allocate(s.device,MTLPixelFormatRGBA16Float,ow,oh,usage(0)|MTLTextureUsageRenderTarget);
        auto b=allocate(s.device,MTLPixelFormatRGBA16Float,ow,oh,usage(0)|MTLTextureUsageRenderTarget);
        s.ui=allocate(s.device,MTLPixelFormatRGBA16Float,ow,oh,usage(3)|MTLTextureUsageRenderTarget);
        s.generated=allocate(s.device,MTLPixelFormatRGBA16Float,ow,oh,usage(4)|MTLTextureUsageShaderRead,true);
        s.sdr=allocate(s.device,(MTLPixelFormat)format,ow,oh,MTLTextureUsageShaderRead|MTLTextureUsageRenderTarget);
        s.guiCoverage=allocate(s.device,MTLPixelFormatR8Unorm,ow,oh,MTLTextureUsageShaderRead|MTLTextureUsageRenderTarget);
        s.oldCoverage=allocate(s.device,MTLPixelFormatR8Unorm,iw,ih,MTLTextureUsageShaderRead|MTLTextureUsageRenderTarget);
        s.beforeGui=allocate(s.device,(MTLPixelFormat)format,ow,oh,MTLTextureUsageShaderRead);
        s.handMask=allocate(s.device,MTLPixelFormatR8Unorm,ow,oh,MTLTextureUsageShaderRead|MTLTextureUsageShaderWrite);
        NSError* error=nil;
        auto library=[s.device newLibraryWithSource:[NSString stringWithUTF8String:foregroundSource] options:nil error:&error];
        if(!library){NSLog(@"[MetalMod] FG foreground shader: %@",error);return NULL;}
        s.qualityCheck=[s.device newComputePipelineStateWithFunction:[library newFunctionWithName:@"qualityCheck"] error:&error];
        s.handCoverage=[s.device newComputePipelineStateWithFunction:[library newFunctionWithName:@"handCoverage"] error:&error];
        auto pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[library newFunctionWithName:@"foregroundVertex"];
        pd.fragmentFunction=[library newFunctionWithName:@"foregroundSDR"];pd.colorAttachments[0].pixelFormat=(MTLPixelFormat)format;
        s.foreground=[s.device newRenderPipelineStateWithDescriptor:pd error:&error];
        if(!a||!b||!s.ui||!s.generated||!s.sdr||!s.handMask||!s.beforeGui||!s.oldCoverage||!s.guiCoverage||!s.handCoverage||!s.qualityCheck||!s.foreground)return NULL;
        std::vector<uint8_t> blank((size_t)iw*ih,0);
        [s.oldCoverage replaceRegion:MTLRegionMake2D(0,0,iw,ih) mipmapLevel:0 withBytes:blank.data() bytesPerRow:iw];
        [(__bridge id<MTLTexture>)mmm_fx_temporal_frame_texture(s.motion,5) replaceRegion:MTLRegionMake2D(0,0,iw,ih) mipmapLevel:0 withBytes:blank.data() bytesPerRow:iw];
        s.colors=@[a,b];s->index=1;s->ids[0]=s->ids[1]=0;s->captured=false;
        for(auto& value:s->counters)value.store(0);s->refreshNs.store(0);
        return (__bridge_retained void*)s;
    } @catch(NSException* e) {NSLog(@"[MetalMod] Gameplay FG create: %@",e.reason);return NULL;} }
}
void mmm_fg_release(void* h) {if(h){MMMGameplayFrames* s=(__bridge_transfer MMMGameplayFrames*)h;(void)s;}}
static bool healthy(MMMGameplayFrames* s) {
    return !(s->counters[6].load()&1)&&mmm_fx_interpolation_healthy(s.interpolation)&&mmm_fx_temporal_frame_healthy(s.motion)&&mmm_fx_temporal_color_healthy(s.converter);
}
void* mmm_fg_gui_coverage(void* h){return h?(__bridge void*)((__bridge MMMGameplayFrames*)h).guiCoverage:NULL;}
void* mmm_fg_coverage(void* h){return h?mmm_fx_temporal_frame_texture(((__bridge MMMGameplayFrames*)h).motion,5):NULL;}
int32_t mmm_fg_capture(void* h,void* command,void* world,void* scene,void* depth,
 const float* matrices,const float* objects,int32_t count,uint64_t renderId,float dt,float near,float far,float fov,float jx,float jy,bool reset) {
    if(!h||!command||!world||!scene||!depth)return -1;
    MMMGameplayFrames* s=(__bridge MMMGameplayFrames*)h;
    if(!healthy(s))return -2;
    id<MTLCommandBuffer> cb=(__bridge id<MTLCommandBuffer>)command;
    if(cb.commandQueue!=s.queue||cb.status!=MTLCommandBufferStatusNotEnqueued||!renderId||renderId<=s->ids[s->index])return -3;
    if(!std::isfinite(dt)||dt<=0||dt>0.25f||!std::isfinite(near)||near<=0||!std::isfinite(far)||far<=near
       ||!std::isfinite(fov)||fov<=0||fov>=180)return -4;
    @autoreleasepool { @try {
        s->captured=false;s->handCaptured=false;
        int rc=mmm_fx_motion_frame_prepare(s.motion,command,scene,depth,matrices,objects,count,jx,jy,reset);
        if(rc<0){if(rc!=-4)s->counters[6].store(1);return rc;}
        int next=1-s->index;
        rc=mmm_fx_temporal_color_encode(s.converter,command,world,(__bridge void*)s.colors[next],true);
        if(rc<0){s->counters[6].store(1);return rc;}
        s->reset=reset||s->ids[s->index]==0;s->index=next;s->ids[next]=renderId;
        s->dt=dt;s->nearPlane=near;s->farPlane=far;s->fov=fov;s->jx=jx;s->jy=jy;s->captured=true;
        s->counters[0]++;
        [cb addCompletedHandler:^(id<MTLCommandBuffer> done){if(done.status==MTLCommandBufferStatusError)s->counters[6].store(1);}];
        return 0;
    } @catch(NSException* e){s->counters[6].store(1);NSLog(@"[MetalMod] Gameplay FG capture: %@",e.reason);return -5;} }
}
/* Snapshot after hand rendering and before the native GUI depth clear. */
int32_t mmm_fg_capture_hand(void* h,void* command,void* depth,void* color) {
    if(!h||!command||!depth||!color)return -1;
    MMMGameplayFrames* s=(__bridge MMMGameplayFrames*)h;
    if(!healthy(s)||!s->captured)return -2;
    id<MTLCommandBuffer> cb=(__bridge id<MTLCommandBuffer>)command;
    id<MTLTexture> d=(__bridge id<MTLTexture>)depth;
    id<MTLTexture> c=(__bridge id<MTLTexture>)color;
    if(cb.commandQueue!=s.queue||cb.status!=MTLCommandBufferStatusNotEnqueued||d.device!=s.device
       ||d.pixelFormat!=MTLPixelFormatDepth32Float||d.width!=s.handMask.width||d.height!=s.handMask.height
       ||c.device!=s.device||c.width!=s.beforeGui.width||c.height!=s.beforeGui.height||c.pixelFormat!=s.beforeGui.pixelFormat
       ||d.textureType!=MTLTextureType2D||d.sampleCount!=1||!(d.usage&MTLTextureUsageShaderRead))return -3;
    @autoreleasepool {
        auto blit=[cb blitCommandEncoder];if(!blit)return -5;
        [blit copyFromTexture:c toTexture:s.beforeGui];[blit endEncoding];
        auto enc=[cb computeCommandEncoder];if(!enc)return -5;
        [enc setComputePipelineState:s.handCoverage];[enc setTexture:d atIndex:0];[enc setTexture:s.handMask atIndex:1];
        [enc dispatchThreads:MTLSizeMake(d.width,d.height,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];[enc endEncoding];
        auto clear=[MTLRenderPassDescriptor renderPassDescriptor];clear.colorAttachments[0].texture=s.guiCoverage;
        clear.colorAttachments[0].loadAction=MTLLoadActionClear;clear.colorAttachments[0].storeAction=MTLStoreActionStore;
        clear.colorAttachments[0].clearColor=MTLClearColorMake(0,0,0,0);
        auto clearEncoder=[cb renderCommandEncoderWithDescriptor:clear];[clearEncoder endEncoding];
        s->handCaptured=true;
        [cb addCompletedHandler:^(id<MTLCommandBuffer> done){if(done.status==MTLCommandBufferStatusError)s->counters[6].store(1);}];
        return 0;
    }
}
void* mmm_fg_ui_texture(void* h){return h?(__bridge void*)((__bridge MMMGameplayFrames*)h).ui:NULL;}
void* mmm_fg_hand_mask(void* h){return h?(__bridge void*)((__bridge MMMGameplayFrames*)h).handMask:NULL;}
int32_t mmm_fg_encode(void* h,void* command,void* real) {
    if(!h||!command||!real)return -1;
    MMMGameplayFrames* s=(__bridge MMMGameplayFrames*)h;
    if(!healthy(s)||!s->captured)return -2;
    if(!s->handCaptured)return -7; // missing boundary: ordinary real frame, never unprotected hand
    id<MTLCommandBuffer> cb=(__bridge id<MTLCommandBuffer>)command;
    if(cb.commandQueue!=s.queue||cb.status!=MTLCommandBufferStatusNotEnqueued)return -3;
    int rc=mmm_fx_temporal_color_encode(s.converter,command,real,(__bridge void*)s.ui,true);
    if(rc<0){s->counters[6].store(1);return rc;}
    uint64_t previous=s->ids[1-s->index];
    rc=mmm_fx_interpolation_encode(s.interpolation,command,(__bridge void*)s.colors[s->index],
       (__bridge void*)s.colors[1-s->index],mmm_fx_temporal_frame_texture(s.motion,2),
       mmm_fx_temporal_frame_texture(s.motion,0),NULL,(__bridge void*)s.generated,
       previous,s->ids[s->index],s->dt,s->nearPlane,s->farPlane,s->fov,-s->jx,-s->jy,true,s->reset);
    if(rc<0){s->counters[6].store(1);return rc;}
    auto quality=[s.device newBufferWithLength:8 options:MTLResourceStorageModeShared];
    if(!quality)return -5;memset(quality.contents,0,8);
    auto check=[cb computeCommandEncoder];[check setComputePipelineState:s.qualityCheck];
    [check setTexture:s.generated atIndex:0];[check setTexture:s.colors[s->index] atIndex:1];
    [check setTexture:s.colors[1-s->index] atIndex:2];
    [check setTexture:(__bridge id<MTLTexture>)mmm_fx_temporal_frame_texture(s.motion,0) atIndex:3];
    [check setTexture:(__bridge id<MTLTexture>)mmm_fg_coverage(h) atIndex:4];
    [check setTexture:s.oldCoverage atIndex:5];
    [check setBuffer:quality offset:0 atIndex:0];
    [check dispatchThreads:MTLSizeMake((s.generated.width+7)/8,(s.generated.height+7)/8,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];[check endEncoding];
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done){
        const uint32_t* counts=(const uint32_t*)quality.contents;
        if(rc==0&&done.status==MTLCommandBufferStatusCompleted&&counts[0]>=12&&counts[1]*10>counts[0]*7)s->counters[6].fetch_or(2);
    }];
    if(s->reset) {
        auto clear=[MTLRenderPassDescriptor renderPassDescriptor];clear.colorAttachments[0].texture=s.oldCoverage;
        clear.colorAttachments[0].loadAction=MTLLoadActionClear;clear.colorAttachments[0].storeAction=MTLStoreActionStore;
        clear.colorAttachments[0].clearColor=MTLClearColorMake(0,0,0,0);
        auto enc=[cb renderCommandEncoderWithDescriptor:clear];[enc endEncoding];
    }
    auto pd=[MTLRenderPassDescriptor renderPassDescriptor];pd.colorAttachments[0].texture=s.sdr;
    pd.colorAttachments[0].loadAction=MTLLoadActionDontCare;pd.colorAttachments[0].storeAction=MTLStoreActionStore;
    auto enc=[cb renderCommandEncoderWithDescriptor:pd];if(!enc)return -5;
    [enc setRenderPipelineState:s.foreground];[enc setFragmentBuffer:quality offset:0 atIndex:0];[enc setFragmentTexture:s.generated atIndex:0];
    [enc setFragmentTexture:(__bridge id<MTLTexture>)real atIndex:1];[enc setFragmentTexture:s.handMask atIndex:2];[enc setFragmentTexture:s.beforeGui atIndex:3];
    [enc setFragmentTexture:s.colors[s->index] atIndex:4];
    [enc setFragmentTexture:(__bridge id<MTLTexture>)mmm_fx_temporal_frame_texture(s.motion,0) atIndex:5];
    [enc setFragmentTexture:s.colors[1-s->index] atIndex:6];
    [enc setFragmentTexture:(__bridge id<MTLTexture>)mmm_fg_coverage(h) atIndex:7];
    [enc setFragmentTexture:s.oldCoverage atIndex:8];[enc setFragmentTexture:s.guiCoverage atIndex:9];
    [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[enc endEncoding];
    auto blit=[cb blitCommandEncoder];
    [blit copyFromTexture:(__bridge id<MTLTexture>)mmm_fg_coverage(h) toTexture:s.oldCoverage];[blit endEncoding];
    s->captured=false;s->counters[1]++;if(rc==0)s->counters[2]++;
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done){if(done.status==MTLCommandBufferStatusError)s->counters[6].store(1);}];
    return rc;
}
void* mmm_fg_debug_texture(void* h,int32_t role) {
    if(!h)return NULL;MMMGameplayFrames* s=(__bridge MMMGameplayFrames*)h;
    switch(role){case 0:return (__bridge void*)s.generated;case 1:return (__bridge void*)s.colors[s->index];
        case 2:return (__bridge void*)s.colors[1-s->index];case 3:return (__bridge void*)s.beforeGui;
        case 4:return (__bridge void*)s.handMask;case 5:return mmm_fx_temporal_frame_texture(s.motion,0);
        case 6:return mmm_fg_coverage(h);case 7:return (__bridge void*)s.guiCoverage;default:return NULL;}
}
void* mmm_fg_texture(void* h){return h?(__bridge void*)((__bridge MMMGameplayFrames*)h).sdr:NULL;}
static void observe(MMMGameplayFrames* s,id<CAMetalDrawable> drawable,int role) {
    [drawable addPresentedHandler:^(id<MTLDrawable> d){
        if(d.presentedTime>0){
            const uint64_t time=(uint64_t)(d.presentedTime*1e9);
            // Only OS presentation timestamps determine display FPS; encodes are not displays.
            // Callback bookkeeping never participates in drawable acquisition or GPU submission.
            std::lock_guard<std::mutex> lock(s->timingMutex);
            s->counters[role]++;s->counters[7].store(time);
            if(!s->lastDisplay){s->counters[8].store(time);s->windowStart=time;}
            else if(time>s->lastDisplay){
                uint64_t gap=time-s->lastDisplay, period=s->refreshNs.load();
                s->counters[9]++;s->counters[10]+=gap;
                if(!s->counters[11].load()||gap<s->counters[11].load())s->counters[11].store(gap);
                s->counters[12].store(std::max(gap,s->counters[12].load()));
                if(period&&gap<period/2)s->counters[13]++;
                if(period&&gap>period*3/2)s->counters[14]++;
                if(role==s->lastRole)s->counters[15]++;
            }else s->counters[15]++;
            ++s->windowDisplays;
            if(time>s->windowStart&&time-s->windowStart>=1000000000){
                s->counters[16].store((s->windowDisplays-1)*1000000000000ULL/(time-s->windowStart));
                s->windowStart=time;s->windowDisplays=1;
            }
            s->lastDisplay=time;s->lastRole=role;
        }
        else s->counters[5]++;
    }];
}
int32_t mmm_fg_present_paced(void* h,void* layer,void* drawable,void* real,float displayInterval) {
    if(!h||!layer||!real||!std::isfinite(displayInterval)
        ||displayInterval<1.f/300||displayInterval>1.f/20)return 1;
    MMMGameplayFrames* s=(__bridge MMMGameplayFrames*)h;
    if(!healthy(s)||!s->captured||(s->counters[6].load()&2))return 1;
    @autoreleasepool {
        void* retained=mmm_command_buffer_create((__bridge void*)s.queue);
        id<MTLCommandBuffer> cb=(__bridge_transfer id<MTLCommandBuffer>)retained;if(!cb)return 1;
        cb.label=@"MetalMod gameplay frame interpolation";
        int rc=mmm_fg_encode(h,(__bridge void*)cb,real);
        if(rc<0)return 1;
        [cb commit];
        if(rc==1)return 1; // warmup still commits history, ordinary real presentation follows
        // A bounded drawable pool supplies backpressure. Completed copies are presented
        // asynchronously in FIFO order, one per refresh, without stalling world rendering.
        // The render owner may defer its first drawable until world and interpolation
        // have entered the GPU queue. This acquisition does not rotate upload/light rings.
        if(!drawable)drawable=mmm_layer_acquire_display(layer);
        if(!drawable){s->counters[5]++;return 1;}
        s->refreshNs.store((uint64_t)(displayInterval*1e9));
        observe(s,(__bridge id<CAMetalDrawable>)drawable,3);
        rc=mmm_layer_present_texture_paced(layer,drawable,(__bridge void*)s.sdr,displayInterval);
        if(rc!=0)return 1;
        void* second=mmm_layer_acquire_display(layer);
        if(!second){s->counters[5]++;return 0;}
        observe(s,(__bridge id<CAMetalDrawable>)second,4);
        rc=mmm_layer_present_texture_paced(layer,second,real,displayInterval);
        if(rc!=0){id<CAMetalDrawable> released=(__bridge_transfer id<CAMetalDrawable>)second;(void)released;s->counters[6].store(1);}
        return 0;
    }
}
int32_t mmm_fg_present(void* h,void* layer,void* drawable,void* real) {
    return mmm_fg_present_paced(h,layer,drawable,real,1.f/60);
}
int32_t mmm_fg_stats(void* h,uint64_t* values,int32_t count) {
    if(!h||!values||count<8)return -1;
    MMMGameplayFrames* s=(__bridge MMMGameplayFrames*)h;
    for(int i=0;i<std::min(count,17);i++)values[i]=s->counters[i].load();return 0;
}
