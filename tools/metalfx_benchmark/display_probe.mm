#import <Metal/Metal.h>
#import <AppKit/AppKit.h>
#import <QuartzCore/CAMetalLayer.h>
#include <mutex>
#include <vector>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
// Diagnostic only: bounded samples, no scheduling or presentation ownership changes.
static std::mutex lock;
static uint64_t generation=0,submitted=0;
static std::vector<int64_t> times;
extern "C" void benchmark_display_reset(){std::lock_guard<std::mutex> guard(lock);++generation;submitted=0;times.clear();}
extern "C" void benchmark_display_observe(void* handle){
 if(!handle)return;id<MTLDrawable> drawable=(__bridge id<MTLDrawable>)handle;uint64_t epoch;
 {std::lock_guard<std::mutex> guard(lock);epoch=generation;++submitted;}
 [drawable addPresentedHandler:^(id<MTLDrawable> shown){
  const double t=shown.presentedTime;
  std::lock_guard<std::mutex> guard(lock);
  if(epoch==generation && times.size()<12000)times.push_back(std::isfinite(t)&&t>0?(int64_t)(t*1e9):0);
 }];
}
extern "C" int32_t benchmark_display_snapshot(int64_t* out,int32_t capacity){
 if(!out||capacity<2)return -1;std::lock_guard<std::mutex> guard(lock);
 out[0]=submitted;int32_t n=(int32_t)times.size();if(n>capacity-2)n=capacity-2;out[1]=n;
 for(int32_t i=0;i<n;++i)out[i+2]=times[i];return n+2;
}

extern "C" int32_t benchmark_display_activate(){
 [NSApp activateIgnoringOtherApps:YES];
 // Called on the render/main thread outside timed captures. Record the window's
 // actual hosting screen rather than assuming a display from the machine inventory.
 static __weak NSWindow* benchmarkWindow=nil;
 // Moving the GLFW window can temporarily clear AppKit's key/main window.
 // Keep a weak reference to the already identified benchmark window.
 NSWindow* window=benchmarkWindow ?: (NSApp.keyWindow ?: NSApp.mainWindow);
 if(!window) for(NSWindow* candidate in NSApp.windows)
   if([candidate.contentView.layer isKindOfClass:CAMetalLayer.class]) {window=candidate;break;}
 if(window)benchmarkWindow=window;
 static bool placed=false;
 const char* target=getenv("METALMOD_BENCHMARK_DISPLAY");
 if(!placed && target && (strcmp(target,"internal")==0 || strcmp(target,"external")==0)) {
   const bool wantInternal=strcmp(target,"internal")==0;
   NSScreen* destination=nil;
   for(NSScreen* candidate in NSScreen.screens)
     if((bool)CGDisplayIsBuiltin([candidate.deviceDescription[@"NSScreenNumber"] unsignedIntValue])==wantInternal) {destination=candidate;break;}
   if(!window || !destination) {NSLog(@"[DisplayProbe] requested %s screen unavailable (window=%@ screen=%@)",target,window,destination);return -1;}
   NSRect frame=destination.visibleFrame;
   if(!wantInternal) {
     frame.size=window.frame.size;
     frame.origin.y=NSMaxY(destination.visibleFrame)-frame.size.height;
   }
   [window setFrame:frame display:YES];placed=true;
 }
 NSScreen* screen=window.screen;
 if(screen) NSLog(@"[DisplayProbe] hosting screen=%@ displayID=%@ maximumHz=%ld window=%@ screen=%@ scale=%.1f builtIn=%d",
   screen.localizedName,screen.deviceDescription[@"NSScreenNumber"],
   (long)screen.maximumFramesPerSecond,NSStringFromRect(window.frame),
   NSStringFromRect(screen.frame),screen.backingScaleFactor,
   CGDisplayIsBuiltin([screen.deviceDescription[@"NSScreenNumber"] unsignedIntValue]));
 return screen ? 0 : -1;
}
