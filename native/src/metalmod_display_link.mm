#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#include "metalmod/metalmod_display_link.h"
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <cmath>
#include <unordered_set>

static std::mutex g_OwnerMutex;
static std::unordered_set<void*> g_OwnedLayers;
struct LayerLease {
    void* layer;
    bool acquired, handedOff=false;
    explicit LayerLease(void* l):layer(l) {
        std::lock_guard<std::mutex> lock(g_OwnerMutex);
        acquired=g_OwnedLayers.insert(l).second;
    }
    ~LayerLease() {
        if(acquired&&!handedOff){std::lock_guard<std::mutex> lock(g_OwnerMutex);g_OwnedLayers.erase(layer);}
    }
};
bool mmm_display_link_owns_layer(void* layer) {
    std::lock_guard<std::mutex> lock(g_OwnerMutex);
    return g_OwnedLayers.count(layer)!=0;
}

static constexpr int kSlots=3;
static constexpr double kMaxAge=0.25;
struct MMMPresentSlot {
    __strong id<MTLTexture> real;
    __strong id<MTLTexture> generated;
    int status=0; // free, copying, ready, active
    uint64_t renderedId=0;
    double submittedAt=0;
    bool hasGenerated=false;
    bool realPending=false;
    bool allSubmitted=false;
    int inFlight=0;
};
@interface MMMDisplayLinkState : NSObject <CAMetalDisplayLinkDelegate> {
@public
    std::mutex mutex;
    std::condition_variable changed;
    MMMPresentSlot slots[kSlots];
    uint64_t counters[12];
    uint64_t displaySequence;
    bool stopped, failed, started, exited;
    CFRunLoopRef loop;
    int width,height;
    int active;
}
@property(nonatomic,strong) CAMetalLayer* layer;
@property(nonatomic,strong) id<MTLCommandQueue> queue;
@property(nonatomic,strong) id<MTLRenderPipelineState> pipeline;
@property(nonatomic,strong) id<MTLSamplerState> sampler;
@property(nonatomic,strong) NSThread* thread;
- (void)run;
@end

@implementation MMMDisplayLinkState
- (void)run {
    @autoreleasepool {
        CAMetalDisplayLink* link=nil;
        @try {
            link=[[CAMetalDisplayLink alloc] initWithMetalLayer:self.layer];
            link.delegate=self;
            link.preferredFrameLatency=1;
            [link addToRunLoop:NSRunLoop.currentRunLoop forMode:NSDefaultRunLoopMode];
            // Keep the run loop alive even when the layer is occluded/minimized.
            NSPort* keepAlive=[NSMachPort port];
            [NSRunLoop.currentRunLoop addPort:keepAlive forMode:NSDefaultRunLoopMode];
            {
                std::lock_guard<std::mutex> lock(mutex);
                loop=CFRunLoopGetCurrent(); CFRetain(loop);
                started=true; changed.notify_all();
            }
            for (;;) {
                { std::lock_guard<std::mutex> lock(mutex); if(stopped)break; }
                @autoreleasepool { CFRunLoopRunInMode(kCFRunLoopDefaultMode,0.1,false); }
            }
            [NSRunLoop.currentRunLoop removePort:keepAlive forMode:NSDefaultRunLoopMode];
        } @catch(NSException* error) {
            std::lock_guard<std::mutex> lock(mutex);
            failed=true;
            NSLog(@"[MetalMod] Display link failed: %@",error.reason);
        }
        [link invalidate]; link.delegate=nil;
        std::lock_guard<std::mutex> lock(mutex);
        if(loop){CFRelease(loop);loop=NULL;}
        {
            std::lock_guard<std::mutex> ownerLock(g_OwnerMutex);
            g_OwnedLayers.erase((__bridge void*)self.layer);
        }
        stopped=true;exited=true;self.thread=nil;changed.notify_all();
    }
}
- (void)metalDisplayLink:(CAMetalDisplayLink*)link needsUpdate:(CAMetalDisplayLinkUpdate*)update {
    (void)link;
    @autoreleasepool {
        std::unique_lock<std::mutex> lock(mutex);
        if(stopped||failed)return;
        counters[0]++;
        const double now=CACurrentMediaTime();
        if(!std::isfinite(update.targetTimestamp)||!std::isfinite(update.targetPresentationTimestamp)
            ||update.targetTimestamp<=now){counters[6]++;return;}
        if(active<0) {
            int newest=-1;
            for(int i=0;i<kSlots;i++)if(slots[i].status==2) {
                if(now-slots[i].submittedAt>kMaxAge){
                    counters[5]+=slots[i].hasGenerated?2:1;slots[i].status=0;
                }else if(newest<0||slots[i].renderedId>slots[newest].renderedId)newest=i;
            }
            if(newest<0)return;
            active=newest;slots[active].status=3;
            slots[active].realPending=slots[active].hasGenerated;
        }
        const int index=active;
        MMMPresentSlot& slot=slots[index];
        if(now-slot.submittedAt>kMaxAge) {
            counters[5]+=slot.realPending?2:1;
            slot.allSubmitted=true;active=-1;
            if(slot.inFlight==0)slot.status=0;
            return;
        }
        // A generated image is always followed by its matching real image.
        const bool generated=slot.hasGenerated&&slot.realPending;
        id<MTLTexture> source=generated?slot.generated:slot.real;
        id<CAMetalDrawable> drawable=update.drawable;
        if(!drawable||drawable.texture.width!=(NSUInteger)width||drawable.texture.height!=(NSUInteger)height) {
            failed=true;return;
        }
        @try {
            // No global utility/staging helpers are called from this thread.
            id<MTLCommandBuffer> cb=[self.queue commandBuffer];
            id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder];
            if(!cb||!blit){failed=true;return;}
            [blit copyFromTexture:source sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
                sourceSize:MTLSizeMake(width,height,1) toTexture:drawable.texture destinationSlice:0
                destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
            [blit endEncoding];
            if(CACurrentMediaTime()>=update.targetTimestamp){counters[6]++;return;}
            const uint64_t renderedId=slot.renderedId, sequence=++displaySequence;
            slot.inFlight++;
            if(generated)slot.realPending=false;
            else {slot.allSubmitted=true;active=-1;}
            [drawable addPresentedHandler:^(id<MTLDrawable> presented) {
                std::lock_guard<std::mutex> guard(self->mutex);
                if(presented.presentedTime<=0){self->counters[5]++;return;}
                self->counters[2]++;self->counters[generated?4:3]++;
                if(sequence>self->counters[10]) {
                    self->counters[9]=renderedId;self->counters[10]=sequence;
                    self->counters[11]=(uint64_t)(presented.presentedTime*1e9);
                }
            }];
            [cb addCompletedHandler:^(id<MTLCommandBuffer> completed) {
                std::lock_guard<std::mutex> guard(self->mutex);
                MMMPresentSlot& finished=self->slots[index];
                finished.inFlight--;
                if(finished.allSubmitted&&finished.inFlight==0)finished.status=0;
                if(completed.status==MTLCommandBufferStatusError)self->failed=true;
            }];
            cb.label=generated?@"MetalMod display-link generated":@"MetalMod display-link real";
            // Display-link drawables already carry scheduling. Commit the copy first,
            // then present before the callback deadline without explicit timing options.
            // Occluded drawables may invoke presented handlers synchronously. Never
            // hold the mailbox mutex across commit/present (handlers take that mutex).
            lock.unlock();
            [cb commit];
            [drawable present];
        } @catch(NSException* error) {
            if(!lock.owns_lock())lock.lock();
            failed=true;
            NSLog(@"[MetalMod] Display-link present rejected: %@",error.reason);
        }
    }
}
@end

static bool sourceValid(id<MTLTexture> t, MMMDisplayLinkState* s) {
    return t.device==s.queue.device&&t.textureType==MTLTextureType2D&&t.sampleCount==1
        &&t.arrayLength==1&&t.width==(NSUInteger)s->width&&t.height==(NSUInteger)s->height
        &&(t.pixelFormat==MTLPixelFormatRGBA8Unorm||t.pixelFormat==MTLPixelFormatBGRA8Unorm)
        &&(t.usage&MTLTextureUsageShaderRead)&&t.storageMode!=MTLStorageModeMemoryless
        &&t.hazardTrackingMode!=MTLHazardTrackingModeUntracked;
}
void* mmm_display_link_create(void* layer,void* queue,int32_t w,int32_t h) {
    if(!layer||!queue||w<=0||h<=0||w>16384||h>16384)return NULL;
    CAMetalLayer* l=(__bridge CAMetalLayer*)layer;
    id<MTLCommandQueue> q=(__bridge id<MTLCommandQueue>)queue;
    if(l.device!=q.device||l.pixelFormat!=MTLPixelFormatBGRA8Unorm||l.framebufferOnly
        ||l.drawableSize.width!=w||l.drawableSize.height!=h||!l.displaySyncEnabled)return NULL;
    LayerLease lease(layer);
    if(!lease.acquired)return NULL;
    @autoreleasepool {
        @try {
            MMMDisplayLinkState* s=[MMMDisplayLinkState new];
            s.layer=l;s.queue=q;s->width=w;s->height=h;s->active=-1;
            s->displaySequence=0;s->stopped=s->failed=s->started=s->exited=false;s->loop=NULL;
            for(auto& c:s->counters)c=0;
            NSString* msl=@"#include <metal_stdlib>\nusing namespace metal;\n"
                "struct V{float4 p[[position]];float2 uv;};\n"
                "vertex V v(uint id[[vertex_id]]){float2 p=float2((id<<1)&2,id&2);"
                "return {float4(p*2-1,0,1),float2(p.x,1-p.y)};}\n"
                "fragment float4 f(V in[[stage_in]],texture2d<float> t[[texture(0)]],"
                "sampler s[[sampler(0)]]){return t.sample(s,in.uv);}";
            NSError* error=nil;
            id<MTLLibrary> lib=[q.device newLibraryWithSource:msl options:nil error:&error];
            if(!lib)return NULL;
            auto pd=[MTLRenderPipelineDescriptor new];
            pd.vertexFunction=[lib newFunctionWithName:@"v"];pd.fragmentFunction=[lib newFunctionWithName:@"f"];
            pd.colorAttachments[0].pixelFormat=MTLPixelFormatBGRA8Unorm;
            s.pipeline=[q.device newRenderPipelineStateWithDescriptor:pd error:&error];
            auto sd=[MTLSamplerDescriptor new];sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;
            s.sampler=[q.device newSamplerStateWithDescriptor:sd];
            if(!s.pipeline||!s.sampler)return NULL;
            auto td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                width:w height:h mipmapped:NO];
            td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead|MTLTextureUsageRenderTarget;
            for(auto& slot:s->slots) {
                slot.real=[q.device newTextureWithDescriptor:td];
                if(!slot.real)return NULL;
            }
            s.thread=[[NSThread alloc] initWithBlock:^{[s run];}];s.thread.name=@"MetalMod display link";
            [s.thread start];lease.handedOff=true;
            {
                std::unique_lock<std::mutex> lock(s->mutex);
                if(!s->changed.wait_for(lock,std::chrono::seconds(2),[&]{return s->started||s->exited;})
                    ||s->failed||s->exited) {
                    lock.unlock();mmm_display_link_stop((__bridge void*)s);return NULL;
                }
            }
            return (__bridge_retained void*)s;
        } @catch(NSException* error) { NSLog(@"[MetalMod] Display-link creation rejected: %@",error.reason);return NULL; }
    }
}
bool mmm_display_link_healthy(void* handle) {
    if(!handle)return false;
    MMMDisplayLinkState* s=(__bridge MMMDisplayLinkState*)handle;
    std::lock_guard<std::mutex> lock(s->mutex);
    return !s->stopped&&!s->failed;
}
int32_t mmm_display_link_submit(void* handle,void* commandBuffer,void* real,void* generated,
    uint64_t renderedId,float r,float g,float b,float a) {
    if(!handle||!commandBuffer)return -1;
    MMMDisplayLinkState* s=(__bridge MMMDisplayLinkState*)handle;
    id<MTLCommandBuffer> cb=(__bridge id<MTLCommandBuffer>)commandBuffer;
    id<MTLTexture> source=(__bridge id<MTLTexture>)real, gen=(__bridge id<MTLTexture>)generated;
    std::lock_guard<std::mutex> lock(s->mutex);
    if(s->stopped||s->failed)return -2;
    if(cb.commandQueue!=s.queue||cb.status!=MTLCommandBufferStatusNotEnqueued
        ||(source&&!sourceValid(source,s))||(gen&&(!source||!sourceValid(gen,s))))return -3;
    if(!renderedId||renderedId<=s->counters[8]||!std::isfinite(r)||!std::isfinite(g)
        ||!std::isfinite(b)||!std::isfinite(a))return -4;
    int index=-1;
    for(int i=0;i<kSlots;i++)if(s->slots[i].status==0){index=i;break;}
    if(index<0){s->counters[7]++;s->counters[5]+=gen?2:1;return 1;}
    MMMPresentSlot& slot=s->slots[index];
    @try {
        if(gen&&!slot.generated) {
            auto td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                width:s->width height:s->height mipmapped:NO];
            td.storageMode=MTLStorageModeShared;
            td.usage=MTLTextureUsageShaderRead|MTLTextureUsageRenderTarget;
            slot.generated=[s.queue.device newTextureWithDescriptor:td];
            if(!slot.generated){s->failed=true;return -5;}
        }
        auto snapshot=[&](id<MTLTexture> src,id<MTLTexture> target) {
            auto pd=[MTLRenderPassDescriptor renderPassDescriptor];
            pd.colorAttachments[0].texture=target;
            pd.colorAttachments[0].loadAction=src?MTLLoadActionDontCare:MTLLoadActionClear;
            pd.colorAttachments[0].storeAction=MTLStoreActionStore;
            pd.colorAttachments[0].clearColor=MTLClearColorMake(r,g,b,a);
            id<MTLRenderCommandEncoder> enc=[cb renderCommandEncoderWithDescriptor:pd];
            if(!enc)return false;
            if(src){[enc setRenderPipelineState:s.pipeline];[enc setFragmentTexture:src atIndex:0];
                [enc setFragmentSamplerState:s.sampler atIndex:0];
                [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];}
            [enc endEncoding];return true;
        };
        if(!snapshot(source,slot.real)||(gen&&!snapshot(gen,slot.generated))){s->failed=true;return -5;}
        slot.status=1;slot.renderedId=renderedId;slot.submittedAt=CACurrentMediaTime();
        slot.hasGenerated=gen!=nil;slot.realPending=false;slot.allSubmitted=false;slot.inFlight=0;
        s->counters[1]++;s->counters[8]=renderedId;
        // Holding source objects does not permit the caller to change their pixels before completion.
        NSArray* inputs=gen?@[source,gen]:(source?@[source]:@[]);
        [cb addCompletedHandler:^(id<MTLCommandBuffer> completed) {
            (void)inputs;
            std::lock_guard<std::mutex> guard(s->mutex);
            auto& ready=s->slots[index];
            if(completed.status==MTLCommandBufferStatusError){s->failed=true;ready.status=0;return;}
            if(s->stopped){ready.status=0;return;}
            bool newer=false;
            for(int i=0;i<kSlots;i++)if(i!=index&&s->slots[i].status>=2) {
                auto& other=s->slots[i];
                if(other.renderedId>ready.renderedId)newer=true;
                else if(other.status==2){s->counters[5]+=other.hasGenerated?2:1;other.status=0;}
            }
            if(newer){s->counters[5]+=ready.hasGenerated?2:1;ready.status=0;}
            else ready.status=2;
        }];
        return 0;
    } @catch(NSException* error) {s->failed=true;NSLog(@"[MetalMod] Display-link snapshot failed: %@",error.reason);return -5;}
}
int32_t mmm_display_link_stop(void* handle) {
    if(!handle)return 0;
    MMMDisplayLinkState* s=(__bridge MMMDisplayLinkState*)handle;
    std::unique_lock<std::mutex> lock(s->mutex);
    s->stopped=true;
    if(s->loop){CFRunLoopStop(s->loop);CFRunLoopWakeUp(s->loop);}
    return s->changed.wait_for(lock,std::chrono::seconds(2),[&]{return s->exited;})?0:-1;
}
void mmm_display_link_release(void* handle) {
    if(!handle)return;
    mmm_display_link_stop(handle);
    MMMDisplayLinkState* s=(__bridge_transfer MMMDisplayLinkState*)handle;(void)s;
}
int32_t mmm_display_link_stats(void* handle,uint64_t* values,int32_t capacity) {
    if(!handle||!values||capacity<14)return -1;
    MMMDisplayLinkState* s=(__bridge MMMDisplayLinkState*)handle;
    std::lock_guard<std::mutex> lock(s->mutex);
    for(int i=0;i<12;i++)values[i]=s->counters[i];
    values[12]=0;for(auto& slot:s->slots)if(slot.status)values[12]++;
    values[13]=s->failed?1:0;return 0;
}
