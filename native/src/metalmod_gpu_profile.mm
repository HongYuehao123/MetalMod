// Independently implemented using Apple's Metal counter API; mcopt inspired the stage-level
// measurement approach. See docs/performance/mcopt-techniques.md for references and semantics.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <objc/runtime.h>
#include "metalmod/metalmod_gpu_profile.h"
#include <atomic>
#include <mutex>
#include <cmath>

static std::atomic<bool> enabled{false};
static std::atomic<uint64_t> epoch{0};
static std::atomic<unsigned> outstanding{0};
static std::mutex resultsMutex;
static int64_t results[MMM_GPU_PROFILE_METRIC_COUNT] = {};
static constexpr unsigned MAX_OUTSTANDING = 16, MAX_PASSES = 64;
static char profileKey;

@interface MetalModGpuStageProfile : NSObject {
@public
    id<MTLCounterSampleBuffer> samples;
    uint64_t generation;
    MTLTimestamp cpuStart, gpuStart;
    unsigned passes;
    bool committed;
}
@end
@implementation MetalModGpuStageProfile
- (void)dealloc { outstanding.fetch_sub(1, std::memory_order_relaxed); }
@end

bool mmm_gpu_profile_supported(void* device) {
    id<MTLDevice> d = (__bridge id<MTLDevice>)device;
    if (!d || ![d supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) return false;
    for (id<MTLCounterSet> set in d.counterSets)
        if ([set.name isEqualToString:MTLCommonCounterSetTimestamp]) return true;
    return false;
}

void mmm_gpu_profile_set_enabled(bool value) {
    std::lock_guard<std::mutex> lock(resultsMutex);
    enabled.store(false, std::memory_order_relaxed);
    epoch.fetch_add(1, std::memory_order_relaxed);
    for (auto& result : results) result = 0;
    enabled.store(value, std::memory_order_release);
}

int32_t mmm_gpu_profile_read_reset(int64_t* out, int32_t count) {
    if (!out || count < MMM_GPU_PROFILE_METRIC_COUNT) return -1;
    std::lock_guard<std::mutex> lock(resultsMutex);
    for (int i = 0; i < MMM_GPU_PROFILE_METRIC_COUNT; i++) {
        out[i] = results[i];
        results[i] = 0;
    }
    if (!out[0]) out[1] = out[2] = -1;
    return MMM_GPU_PROFILE_METRIC_COUNT;
}

static void dropped(uint64_t generation) {
    std::lock_guard<std::mutex> lock(resultsMutex);
    if (enabled.load(std::memory_order_relaxed) && generation == epoch.load(std::memory_order_relaxed)) ++results[3];
}

void mmm_gpu_profile_attach(id<MTLCommandBuffer> buffer, MTLRenderPassDescriptor* descriptor) {
    if (!enabled.load(std::memory_order_acquire)) return;
    uint64_t generation = epoch.load(std::memory_order_relaxed);
    MetalModGpuStageProfile* profile = objc_getAssociatedObject(buffer, &profileKey);
    if (!profile) {
        id<MTLDevice> device = buffer.device;
        if (!mmm_gpu_profile_supported((__bridge void*)device)) { dropped(generation); return; }
        if (outstanding.fetch_add(1, std::memory_order_relaxed) >= MAX_OUTSTANDING) {
            outstanding.fetch_sub(1, std::memory_order_relaxed);
            dropped(generation);
            return;
        }
        profile = [MetalModGpuStageProfile new];
        profile->generation = generation;
        for (id<MTLCounterSet> set in device.counterSets) {
            if (![set.name isEqualToString:MTLCommonCounterSetTimestamp]) continue;
            MTLCounterSampleBufferDescriptor* desc = [MTLCounterSampleBufferDescriptor new];
            desc.counterSet = set;
            desc.storageMode = MTLStorageModeShared;
            desc.sampleCount = MAX_PASSES * 4;
            profile->samples = [device newCounterSampleBufferWithDescriptor:desc error:nil];
            break;
        }
        if (!profile->samples) { dropped(generation); return; }
        [device sampleTimestamps:&profile->cpuStart gpuTimestamp:&profile->gpuStart];
        objc_setAssociatedObject(buffer, &profileKey, profile, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }
    if (profile->generation != generation || profile->passes == MAX_PASSES) { dropped(generation); return; }
    auto attachment = descriptor.sampleBufferAttachments[0];
    unsigned first = profile->passes++ * 4;
    attachment.sampleBuffer = profile->samples;
    attachment.startOfVertexSampleIndex = first;
    attachment.endOfVertexSampleIndex = first + 1;
    attachment.startOfFragmentSampleIndex = first + 2;
    attachment.endOfFragmentSampleIndex = first + 3;
}

void mmm_gpu_profile_commit(id<MTLCommandBuffer> buffer) {
    // Normal rendering pays only a relaxed atomic load, with no associated-object lookup.
    // Pending samples still need their completion hook after capture has been disabled.
    if (!buffer || !outstanding.load(std::memory_order_relaxed)) return;
    MetalModGpuStageProfile* profile = objc_getAssociatedObject(buffer, &profileKey);
    if (!profile || profile->committed) return;
    profile->committed = true;
    [buffer addCompletedHandler:^(id<MTLCommandBuffer> completed) {
        @autoreleasepool {
            int64_t valid = 0, vertex = 0, fragment = 0;
            MTLTimestamp cpuEnd = 0, gpuEnd = 0;
            [completed.device sampleTimestamps:&cpuEnd gpuTimestamp:&gpuEnd];
            double scale = gpuEnd > profile->gpuStart && cpuEnd > profile->cpuStart
                    ? double(cpuEnd - profile->cpuStart) / double(gpuEnd - profile->gpuStart) : 0;
            NSData* data = completed.status == MTLCommandBufferStatusCompleted
                    ? [profile->samples resolveCounterRange:NSMakeRange(0, profile->passes * 4)] : nil;
            if (scale > 0 && data.length >= profile->passes * 4 * sizeof(MTLCounterResultTimestamp)) {
                const auto* timestamps = (const MTLCounterResultTimestamp*)data.bytes;
                for (unsigned p = 0; p < profile->passes; p++) {
                    uint64_t v0 = timestamps[4*p].timestamp, v1 = timestamps[4*p+1].timestamp;
                    uint64_t f0 = timestamps[4*p+2].timestamp, f1 = timestamps[4*p+3].timestamp;
                    if (!v0 || !f0 || v0 == MTLCounterErrorValue || v1 == MTLCounterErrorValue
                            || f0 == MTLCounterErrorValue || f1 == MTLCounterErrorValue || v1 < v0 || f1 < f0) continue;
                    ++valid;
                    vertex += (int64_t)std::llround(double(v1 - v0) * scale);
                    fragment += (int64_t)std::llround(double(f1 - f0) * scale);
                }
            }
            std::lock_guard<std::mutex> lock(resultsMutex);
            if (enabled.load(std::memory_order_relaxed) && profile->generation == epoch.load(std::memory_order_relaxed)) {
                results[0] += valid; results[1] += vertex; results[2] += fragment;
                results[4] += profile->passes - valid;
            }
        }
    }];
    // The completion block owns the sample buffers until it has resolved them.
    objc_setAssociatedObject(buffer, &profileKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}
