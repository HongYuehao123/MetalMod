// A real window/display-link test. Requires a logged-in macOS display and host GPU access.
#import <Cocoa/Cocoa.h>
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#include "metalmod/metalmod_metal.h"
#include "metalmod/metalmod_display_link.h"
#include <functional>
#include <cstdio>
#include <vector>
#include <memory>
#include <atomic>

static int failures=0;
static void check(const char* label,bool ok) {
    printf("[%s] %s\n",ok?"PASS":"FAIL",label);fflush(stdout);if(!ok)failures++;
}
static bool pumpUntil(std::function<bool()> condition,double seconds=3) {
    double end=CACurrentMediaTime()+seconds;
    while(CACurrentMediaTime()<end) {
        if(condition())return true;
        @autoreleasepool {
            NSEvent* event=[NSApp nextEventMatchingMask:NSEventMaskAny
                untilDate:[NSDate dateWithTimeIntervalSinceNow:0.01] inMode:NSDefaultRunLoopMode dequeue:YES];
            if(event)[NSApp sendEvent:event];
            [NSApp updateWindows];
            [CATransaction flush];
        }
    }
    return condition();
}
int main() {
    @autoreleasepool {
        [NSApplication sharedApplication];[NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
        [NSApp finishLaunching];
        void* device=mmm_device_create();check("host Metal device",device!=NULL);if(!device)return 1;
        id<MTLDevice> dev=(__bridge id<MTLDevice>)device;
        id<MTLCommandQueue> queue=[dev newCommandQueue];
        NSWindow* window=[[NSWindow alloc] initWithContentRect:NSMakeRect(120,120,256,192)
            styleMask:NSWindowStyleMaskTitled backing:NSBackingStoreBuffered defer:NO];
        window.title=@"MetalMod display-link verification";
        window.releasedWhenClosed=NO;
        void* layer=mmm_layer_create((__bridge void*)window.contentView);
        check("configure FIFO",mmm_layer_configure(layer,64,48,true)==0);
        window.collectionBehavior=NSWindowCollectionBehaviorCanJoinAllSpaces|NSWindowCollectionBehaviorFullScreenAuxiliary;
        window.level=NSStatusWindowLevel;[window orderFrontRegardless];[NSApp activateIgnoringOtherApps:YES];
        pumpUntil([]{return false;},0.1);
        printf("window visible=%d occlusion=%lu screen=%s layerFrame=%.0fx%.0f\n",window.visible,(unsigned long)window.occlusionState,window.screen?"yes":"no",((__bridge CAMetalLayer*)layer).frame.size.width,((__bridge CAMetalLayer*)layer).frame.size.height);

        auto baselineTime=std::make_shared<std::atomic<double>>(-1);
        mmm_layer_set_present_queue((__bridge void*)queue);
        for(int shot=0;shot<8&&baselineTime->load()<=0;shot++) {
            id<CAMetalDrawable> baseline=[(__bridge CAMetalLayer*)layer nextDrawable];
            [baseline addPresentedHandler:^(id<MTLDrawable> d){baselineTime->store(d.presentedTime);}];
            mmm_layer_present_clear(layer,(__bridge_retained void*)baseline,0.2,0.2,0.2,1);
            pumpUntil([&]{return baselineTime->load()>0;},0.1);
        }
        printf("ordinary baseline presentedTime=%.6f\n",baselineTime->load());
        if(baselineTime->load()<=0) {
            printf("DISPLAY LINK CHECK BLOCKED: no ordinary onscreen baseline; unlock the Mac and keep this test window visible.\n");
            [window close];mmm_layer_release(layer);mmm_device_release(device);return 77;
        }
        check("ordinary window baseline actually displays",true);
        void* link=mmm_display_link_create(layer,(__bridge void*)queue,64,48);
        check("real display link created",link!=NULL);
        if(!link){[window close];mmm_layer_release(layer);mmm_device_release(device);return 1;}
        check("single presenter ownership",mmm_display_link_owns_layer(layer));
        check("duplicate presenter denied",mmm_display_link_create(layer,(__bridge void*)queue,64,48)==NULL);
        void* drawable=NULL;void* texture=NULL;
        check("ordinary drawable acquisition denied",mmm_layer_acquire(layer,&drawable,&texture)==-3&&drawable==NULL);
        check("resize denied while callback owns layer",mmm_layer_configure(layer,65,49,true)==-3);
        uint64_t stats[14]={};
        auto read=[&]{mmm_display_link_stats(link,stats,14);};
        auto td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:64 height:48 mipmapped:NO];
        td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;
        id<MTLTexture> real=[dev newTextureWithDescriptor:td],generated=[dev newTextureWithDescriptor:td];
        std::vector<uint8_t> pixels(64*48*4);
        for(int i=0;i<64*48;i++){pixels[i*4]=255;pixels[i*4+3]=255;}
        [real replaceRegion:MTLRegionMake2D(0,0,64,48) mipmapLevel:0 withBytes:pixels.data() bytesPerRow:64*4];
        for(int i=0;i<64*48;i++){pixels[i*4]=0;pixels[i*4+1]=255;}
        [generated replaceRegion:MTLRegionMake2D(0,0,64,48) mipmapLevel:0 withBytes:pixels.data() bytesPerRow:64*4];
        auto submit=[&](id<MTLCommandBuffer> cb,uint64_t id,bool pair) {
            return mmm_display_link_submit(link,(__bridge void*)cb,(__bridge void*)real,
                pair?(__bridge void*)generated:NULL,id,0,0,0,1);
        };
        auto wrong=[[dev newCommandQueue] commandBuffer];
        check("unordered snapshot queue denied",submit(wrong,1,false)==-3);
        uint64_t nextId=1;
        // The compositor can drop startup drawables. Verify sustained pair delivery,
        // recording only actual presented callbacks, never treating a zero time as a pass.
        for(int attempt=0;attempt<8;attempt++) {
            auto cb=[queue commandBuffer];
            check("real/generated snapshots accepted",submit(cb,nextId++,true)==0);
            [cb commit];
            if(pumpUntil([&]{read();return stats[3]>=1&&stats[4]>=1&&stats[10]==2*(nextId-1);},0.1))break;
        }
        check("actual OS presentations deliver generated and matching real",stats[3]>=1&&stats[4]>=1);
        read();
        printf("callbacks=%llu displayed=%llu real=%llu generated=%llu actualTimeNs=%llu\n",
            stats[0],stats[2],stats[3],stats[4],stats[11]);
        printf("late=%llu dropped=%llu occupied=%llu failed=%llu\n",stats[6],stats[5],stats[12],stats[13]);
        check("actual timestamp and separate display IDs",stats[11]>0&&stats[9]==nextId-1&&stats[10]>=2);
        check("completed pair releases snapshot slot",pumpUntil([&]{read();return stats[12]==0;}));
        const uint64_t displayedBeforeBacklog=stats[2],droppedBeforeBacklog=stats[5];
        auto staleId=[queue commandBuffer];
        check("repeated rendered ID denied",submit(staleId,nextId-1,false)==-4);
        // Hold copies uncommitted: deterministic full mailbox with no callback consuming them.
        id<MTLCommandBuffer> pending[3];
        for(int i=0;i<3;i++) {
            pending[i]=[queue commandBuffer];
            check("bounded snapshot slot accepted",submit(pending[i],nextId++,false)==0);
        }
        auto full=[queue commandBuffer];
        check("fourth pending copy drops without blocking",submit(full,nextId,false)==1);
        read();check("mailbox remains bounded at three",stats[12]==3&&stats[7]==1);
        // Expired ready frames must not be sent to the display.
        pumpUntil([]{return false;},0.3);
        for(auto p:pending)[p commit];
        check("stale backlog discarded",pumpUntil([&]{read();return stats[12]==0&&stats[5]>=droppedBeforeBacklog+4;}));
        read();check("stale backlog adds no displayed frames",stats[2]==displayedBeforeBacklog);
        // Stop with a copy still pending, then let completion retire its resources.
        auto last=[queue commandBuffer];
        check("snapshot before shutdown accepted",submit(last,nextId++,false)==0);
        check("stop relinquishes sole drawable owner",mmm_display_link_stop(link)==0&&!mmm_display_link_owns_layer(layer));
        check("stopped generation rejects submit",submit([queue commandBuffer],nextId,false)==-2);
        mmm_display_link_release(link);link=NULL;
        [last commit];[last waitUntilCompleted];
        check("release with copy in flight survives",last.status==MTLCommandBufferStatusCompleted);
        check("odd resize after stop",mmm_layer_configure(layer,65,49,true)==0);
        // Repeated ownership transfer back to ordinary and to a fresh display link.
        for(int i=0;i<4;i++) {
            link=mmm_display_link_create(layer,(__bridge void*)queue,65,49);
            check("recreate display-link ownership",link!=NULL);
            if(!link)break;
            bool actuallyPresented=false;
            for(int shot=1;shot<=8;shot++) {
                auto clear=[queue commandBuffer];
                check("source-less real frame clears",mmm_display_link_submit(link,(__bridge void*)clear,NULL,NULL,shot,0.2,0.1,0.3,1)==0);
                [clear commit];
                if(pumpUntil([&]{mmm_display_link_stats(link,stats,14);return stats[3]>=1;},0.1)){actuallyPresented=true;break;}
            }
            check("fresh generation actually presents",actuallyPresented);
            check("repeat stop completes",mmm_display_link_stop(link)==0);
            mmm_display_link_release(link);link=NULL;
        }
        check("IMMEDIATE uses ordinary path",mmm_layer_configure(layer,65,49,false)==0
            &&mmm_display_link_create(layer,(__bridge void*)queue,65,49)==NULL);
        mmm_layer_set_present_queue((__bridge void*)queue);
        int rc=mmm_layer_acquire(layer,&drawable,&texture);
        check("ordinary drawable acquisition restored",rc==0&&drawable!=NULL);
        if(rc==0)check("ordinary presentation restored",mmm_layer_present_clear(layer,drawable,0,0,0,1)==0);
        [window close];mmm_layer_release(layer);mmm_device_release(device);
    }
    printf(failures?"DISPLAY LINK CHECK FAILED (%d)\n":"DISPLAY LINK CHECK PASSED\n",failures);
    return failures?1:0;
}
