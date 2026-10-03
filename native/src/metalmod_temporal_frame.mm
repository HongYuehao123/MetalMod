#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include "metalmod/metalmod_metalfx.h"
#include <atomic>
#include <cmath>
#include <cstring>

// A complete ordered scene submission. History textures are never written by the CPU.
// Independent rigid motion comes from stable render-state identities; a bounded patch refinement
// handles pose deformation. Unmatched/ambiguous/depth-incompatible samples reject history.
static const char* frameSource = R"MSL(
#include <metal_stdlib>
using namespace metal;
struct Params { float4x4 previousFromCurrent; float4 jitter; uint4 meta; };
struct Object { float4 lower; float4 upper; float4 previousDelta; };
constexpr sampler historySampler(coord::normalized, address::clamp_to_edge, filter::linear);
float patchError(texture2d<float> current, texture2d<float> previous, float2 p, float2 q, float2 size) {
    float error=0;
    const float2 offsets[5]={float2(0),float2(-1,0),float2(1,0),float2(0,-1),float2(0,1)};
    for(uint i=0;i<5;i++) {
        float3 a=current.sample(historySampler,(p+offsets[i])/size).rgb;
        float3 b=previous.sample(historySampler,(q+offsets[i])/size).rgb;
        float3 delta=a-b;
        error+=dot(delta,delta)*(i==0?4.0f:1.0f);
    }
    return error/24.0f;
}
kernel void motionInputs(texture2d<float> current [[texture(0)]], depth2d<float> depth [[texture(1)]],
        texture2d<float> previous [[texture(2)]], depth2d<float> previousDepth [[texture(3)]],
        texture2d<half,access::write> motion [[texture(4)]], texture2d<half,access::write> reactive [[texture(5)]], texture2d<float> coverage [[texture(6)]],
        constant Params& params [[buffer(0)]], const device Object* objects [[buffer(1)]], uint2 pixel [[thread_position_in_grid]]) {
    uint2 dimensions=uint2(current.get_width(),current.get_height());
    if(any(pixel>=dimensions))return;
    float2 size=float2(dimensions), p=float2(pixel)+0.5f;
    float z=depth.read(pixel);
    float rejection=1; float2 vector=0;
    if(params.meta.y!=0 && z>0 && isfinite(z)) {
        // Undo the current sample offset before reconstructing the camera-relative world point.
        float2 unjittered=p+params.jitter.xy;
        float4 clip=float4(unjittered.x*2/size.x-1,1-unjittered.y*2/size.y,z,1);
        // World reconstruction is needed only for independent-object membership below.
        // CPU supplies both matrices in the storage buffer following the object records.
        const device float4x4* inverseCurrent=reinterpret_cast<const device float4x4*>(objects+params.meta.x);
        float4 world4=(*inverseCurrent)*clip;
        float3 world=world4.xyz/world4.w;
        float4 oldClip=params.previousFromCurrent*clip;
        bool objectNew=false;
        float3 rigidDelta=0;
        uint membership=0;
        for(uint i=0;i<params.meta.x;i++) {
            Object item=objects[i];
            if(all(world>=item.lower.xyz)&&all(world<=item.upper.xyz)) {
                membership++;
                objectNew=objectNew||item.lower.w<0.5f;
                rigidDelta=item.previousDelta.xyz;
            }
        }
        // Overlapping objects have no reliable identity here. Never manufacture stationary motion.
        if(!objectNew && membership<=1 && all(isfinite(world))) {
            if(membership==1) {
                const device float4x4* previousProjection=inverseCurrent+1;
                oldClip+=(*previousProjection)*float4(rigidDelta,0);
            }
            if(oldClip.w>0 && all(isfinite(oldClip))) {
                float3 ndc=oldClip.xyz/oldClip.w;
                float2 oldUnjittered=float2((ndc.x+1)*size.x/2,(1-ndc.y)*size.y/2);
                float2 q=oldUnjittered-params.jitter.zw;
                if(all(q>=1.5f)&&all(q<size-1.5f)&&ndc.z>0&&ndc.z<=1) {
                    float observed=previousDepth.sample(historySampler,q/size);
                    // Reversed-Z agreement rejects newly uncovered/background and missing geometry.
                    float depthTolerance=max(0.000001f,abs(ndc.z)*0.04f);
                    bool depthValid=observed>0 && abs(observed-ndc.z)<=depthTolerance;
                    if(depthValid) {
                        float best=patchError(current,previous,p,q,size), second=INFINITY;
                        float2 bestOffset=0;
                        // Refine only object pixels; static terrain uses exact camera reprojection.
                        // Missing/deformed geometry falls back to reactive rejection, never zero flow.
                        if(membership==1 && best>0.0005f) {
                            for(int y=-4;y<=4;y++)for(int x=-4;x<=4;x++) {
                                if(x==0&&y==0)continue;
                                float2 candidate=q+float2(x,y);
                                if(any(candidate<1.5f)||any(candidate>=size-1.5f))continue;
                                float oldDepth=previousDepth.sample(historySampler,candidate/size);
                                if(oldDepth<=0 || abs(oldDepth-ndc.z)>depthTolerance)continue;
                                float error=patchError(current,previous,p,candidate,size);
                                if(error<best){second=best;best=error;bestOffset=float2(x,y);}
                                else second=min(second,error);
                            }
                        }
                        bool ambiguous=any(bestOffset!=0)&&second-best<0.00005f;
                        if(!ambiguous && best<0.015f) {
                            vector=oldUnjittered+bestOffset-unjittered;
                            rejection=clamp(best*60.0f,0.0f,1.0f);
                        }
                    }
                }
            }
        }
    }
    rejection=max(rejection,coverage.read(pixel).r);
    motion.write(half4(half2(vector),0,0),pixel);
    reactive.write(half4(half(rejection),0,0,0),pixel);
}
)MSL";

@interface MMMTemporalFrame : NSObject {
@public
    std::atomic<bool> failed;
    std::atomic<int64_t> duration;
    bool history;
    float lastX,lastY;
}
@property(nonatomic,strong) id<MTLDevice> device;
@property(nonatomic,assign) MTLPixelFormat sdrFormat;
@property(nonatomic,strong) id<MTLCommandQueue> queue;
@property(nonatomic,strong) id<MTLComputePipelineState> inputs;
@property(nonatomic,strong) id<MTLTexture> color;
@property(nonatomic,strong) id<MTLTexture> depthHistory;
@property(nonatomic,strong) id<MTLTexture> colorHistory;
@property(nonatomic,strong) id<MTLTexture> motion;
@property(nonatomic,strong) id<MTLTexture> reactive;
@property(nonatomic,strong) id<MTLTexture> output;
@property(nonatomic,strong) id<MTLTexture> coverage;
@property(nonatomic,assign) void* scaler;
@property(nonatomic,assign) void* converter;
@end
@implementation MMMTemporalFrame
- (void)dealloc { mmm_fx_temporal_release(_scaler); mmm_fx_temporal_color_release(_converter); }
@end

static id<MTLTexture> frameTexture(id<MTLDevice> device,MTLPixelFormat format,int w,int h,MTLTextureUsage usage,bool privateStorage=false) {
    MTLTextureDescriptor* descriptor=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
    descriptor.storageMode=privateStorage?MTLStorageModePrivate:MTLStorageModeShared;
    descriptor.usage=usage;
    return [device newTextureWithDescriptor:descriptor];
}
void* mmm_fx_temporal_frame_create(void* device,int32_t iw,int32_t ih,int32_t ow,int32_t oh,int64_t sdrFormat) {
    if(!device)return NULL;
    @autoreleasepool { @try {
        MMMTemporalFrame* state=[MMMTemporalFrame new];state.device=(__bridge id<MTLDevice>)device;state.sdrFormat=(MTLPixelFormat)sdrFormat;
        state->failed.store(false);state->duration.store(-1);state->history=false;
        state.scaler=mmm_fx_temporal_create(device,iw,ih,ow,oh);
        state.converter=mmm_fx_temporal_color_create(device,sdrFormat);
        if(!state.scaler||!state.converter)return NULL;
        NSError* error=nil;
        id<MTLLibrary> library=[state.device newLibraryWithSource:[NSString stringWithUTF8String:frameSource] options:nil error:&error];
        if(!library){NSLog(@"[MetalMod] Temporal inputs: %@",error);return NULL;}
        state.inputs=[state.device newComputePipelineStateWithFunction:[library newFunctionWithName:@"motionInputs"] error:&error];
        if(!state.inputs){NSLog(@"[MetalMod] Temporal inputs pipeline: %@",error);return NULL;}
        auto usage=[&](int role){return (MTLTextureUsage)mmm_fx_temporal_texture_usage(state.scaler,role);};
        state.color=frameTexture(state.device,MTLPixelFormatRGBA16Float,iw,ih,usage(0)|MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead);
        state.colorHistory=frameTexture(state.device,MTLPixelFormatRGBA16Float,iw,ih,MTLTextureUsageShaderRead);
        state.depthHistory=frameTexture(state.device,MTLPixelFormatDepth32Float,iw,ih,MTLTextureUsageShaderRead);
        state.coverage=frameTexture(state.device,MTLPixelFormatR8Unorm,iw,ih,MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead);
        state.motion=frameTexture(state.device,MTLPixelFormatRG16Float,iw,ih,usage(2)|MTLTextureUsageShaderWrite);
        state.reactive=frameTexture(state.device,MTLPixelFormatR8Unorm,iw,ih,usage(3)|MTLTextureUsageShaderWrite);
        state.output=frameTexture(state.device,MTLPixelFormatRGBA16Float,ow,oh,usage(4)|MTLTextureUsageShaderRead,true);
        if(!state.color||!state.colorHistory||!state.depthHistory||!state.motion||!state.reactive||!state.coverage||!state.output)return NULL;
        return (__bridge_retained void*)state;
    } @catch(NSException* error){NSLog(@"[MetalMod] Temporal frame creation: %@",error.reason);return NULL;} }
}
void mmm_fx_temporal_frame_release(void* frame){if(frame){MMMTemporalFrame* released=(__bridge_transfer MMMTemporalFrame*)frame;(void)released;}}
bool mmm_fx_temporal_frame_healthy(void* frame){
    if(!frame)return false;
    MMMTemporalFrame* state=(__bridge MMMTemporalFrame*)frame;
    return !state->failed.load()&&mmm_fx_temporal_healthy(state.scaler)&&mmm_fx_temporal_color_healthy(state.converter);
}
void* mmm_fx_temporal_frame_texture(void* frame,int32_t role) {
    if(!frame)return NULL;
    MMMTemporalFrame* state=(__bridge MMMTemporalFrame*)frame;
    switch(role){case 0:return (__bridge void*)state.motion;case 1:return (__bridge void*)state.reactive;
        case 2:return (__bridge void*)state.depthHistory;case 3:return (__bridge void*)state.color;
        case 4:return (__bridge void*)state.output;case 5:return (__bridge void*)state.coverage;default:return NULL;}
}
int64_t mmm_fx_temporal_frame_gpu_duration_ns(void* frame){return frame?((__bridge MMMTemporalFrame*)frame)->duration.load():-1;}

// matrices = previousFromCurrent, inverseCurrentPV, previousPV; column-major float4x4.
// objects = triples of float4: camera-relative lower.xyz / prior-valid.w, upper.xyz, previous-current delta.xyz.
int32_t mmm_fx_temporal_frame_encode(void* frame,void* commandBuffer,void* sceneColor,void* sceneDepth,void* destination,
        const float* matrices,const float* objects,int32_t count,float jitterX,float jitterY,bool reset) {
    if(!frame||!commandBuffer||!sceneColor||!sceneDepth||!destination||!matrices||count<0||count>1024||(count&&!objects))return -1;
    if(!mmm_fx_temporal_frame_healthy(frame))return -2;
    for(int i=0;i<48;i++)if(!std::isfinite(matrices[i]))return -4;
    for(int i=0;i<count*12;i++)if(!std::isfinite(objects[i]))return -4;
    if(!std::isfinite(jitterX)||!std::isfinite(jitterY)||std::abs(jitterX)>0.5f||std::abs(jitterY)>0.5f)return -4;
    MMMTemporalFrame* state=(__bridge MMMTemporalFrame*)frame;
    id<MTLCommandBuffer> cb=(__bridge id<MTLCommandBuffer>)commandBuffer;
    id<MTLTexture> depth=(__bridge id<MTLTexture>)sceneDepth;
    id<MTLTexture> target=(__bridge id<MTLTexture>)destination;
    if(target.device!=state.device||target.pixelFormat!=state.sdrFormat||target.width!=state.output.width
            ||target.height!=state.output.height||target.textureType!=MTLTextureType2D||target.sampleCount!=1
            ||target.arrayLength!=1||!(target.usage&MTLTextureUsageRenderTarget))return -3;
    if(cb.status!=MTLCommandBufferStatusNotEnqueued||cb.device!=state.device||(state.queue&&state.queue!=cb.commandQueue)
            ||depth.device!=state.device||depth.pixelFormat!=MTLPixelFormatDepth32Float
            ||depth.width!=state.color.width||depth.height!=state.color.height||depth.sampleCount!=1||depth.textureType!=MTLTextureType2D||depth.arrayLength!=1
            ||!(depth.usage&MTLTextureUsageShaderRead))return -3;
    @autoreleasepool { @try {
        int rc=mmm_fx_temporal_color_encode(state.converter,commandBuffer,sceneColor,(__bridge void*)state.color,true);
        if(rc)return rc;
        // Storage-buffer tables avoid Apple's dynamic std140 uniform-array indexing hazard.
        NSUInteger bytes=(NSUInteger)count*48+128;
        id<MTLBuffer> table=[state.device newBufferWithLength:bytes options:MTLResourceStorageModeShared];
        if(!table)return -6;
        if(count)std::memcpy(table.contents,objects,count*48);
        std::memcpy((char*)table.contents+count*48,matrices+16,128);
        struct alignas(16) Params {float matrix[16];float jitter[4];uint32_t meta[4];} params{};
        std::memcpy(params.matrix,matrices,64);
        params.jitter[0]=jitterX;params.jitter[1]=jitterY;params.jitter[2]=state->lastX;params.jitter[3]=state->lastY;
        params.meta[0]=count;params.meta[1]=state->history&&!reset;
        id<MTLComputeCommandEncoder> encoder=[cb computeCommandEncoder];
        if(!encoder)return -6;
        [encoder setComputePipelineState:state.inputs];[encoder setBytes:&params length:sizeof(params) atIndex:0];
        [encoder setBuffer:table offset:0 atIndex:1];
        [encoder setTexture:state.color atIndex:0];[encoder setTexture:depth atIndex:1];
        [encoder setTexture:state.colorHistory atIndex:2];[encoder setTexture:state.depthHistory atIndex:3];
        [encoder setTexture:state.motion atIndex:4];[encoder setTexture:state.reactive atIndex:5];[encoder setTexture:state.coverage atIndex:6];
        [encoder dispatchThreads:MTLSizeMake(state.color.width,state.color.height,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];
        [encoder endEncoding];
        // Our projection renders f(pixel+jitter); MetalFX samples the resulting texture at
        // pixel-jitter to return to the unjittered reference. Motion inputs remain unjittered.
        rc=mmm_fx_temporal_encode(state.scaler,commandBuffer,(__bridge void*)state.color,sceneDepth,
                (__bridge void*)state.motion,(__bridge void*)state.reactive,(__bridge void*)state.output,-jitterX,-jitterY,true,reset);
        if(rc){state->failed.store(true);return rc;}
        rc=mmm_fx_temporal_color_encode(state.converter,commandBuffer,(__bridge void*)state.output,destination,false);
        if(rc){state->failed.store(true);return rc;}
        id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder];if(!blit){state->failed.store(true);return -6;}
        [blit copyFromTexture:state.color toTexture:state.colorHistory];[blit copyFromTexture:depth toTexture:state.depthHistory];[blit endEncoding];
        state.queue=cb.commandQueue;state->history=true;state->lastX=jitterX;state->lastY=jitterY;
        [cb addCompletedHandler:^(id<MTLCommandBuffer> completed){
            if(completed.status==MTLCommandBufferStatusError){state->failed.store(true);state->duration.store(-1);}
            else if(completed.GPUEndTime>=completed.GPUStartTime&&completed.GPUStartTime>0)
                state->duration.store((int64_t)((completed.GPUEndTime-completed.GPUStartTime)*1e9));
        }];
        return 0;
    } @catch(NSException* error){state->failed.store(true);NSLog(@"[MetalMod] Temporal frame encode: %@",error.reason);return -5;} }
}
