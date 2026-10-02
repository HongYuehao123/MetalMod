#import <Metal/Metal.h>
#import <AppKit/AppKit.h>
#include <mutex>
#include <vector>
#include <cmath>
#include <cstdint>
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

extern "C" void benchmark_display_activate(){[NSApp activateIgnoringOtherApps:YES];}
