#import "metalmod_internal.h"
#include <mach/mach_time.h>

@interface MetalModState (SpatialMethods)
- (void)encodeSpatialUpscaling:(id<MTLCommandBuffer>)commandBuffer
                 sourceTexture:(id<MTLTexture>)srcTex
            destinationTexture:(id<MTLTexture>)dstTex;
@end

@interface MetalModState (TemporalMethods)
- (void)encodeTemporalUpscaling:(id<MTLCommandBuffer>)commandBuffer
                   colorTexture:(id<MTLTexture>)colorTex
                   depthTexture:(id<MTLTexture>)depthTex
                  motionTexture:(id<MTLTexture>)motionTex
             destinationTexture:(id<MTLTexture>)dstTex
                         params:(const MetalModFrameParams *)params;
@end

@interface MetalModState (InterpolatorMethods)
- (void)encodeFrameInterpolation:(id<MTLCommandBuffer>)commandBuffer
                    currentFrame:(id<MTLTexture>)currFrame
                   previousFrame:(id<MTLTexture>)prevFrame
                    depthTexture:(id<MTLTexture>)depthTex
                   motionTexture:(id<MTLTexture>)motionTex
                       uiTexture:(id<MTLTexture>)uiTex
             interpolatedTexture:(id<MTLTexture>)interpolatedDst
                          params:(const MetalModFrameParams *)params;
@end

@interface MetalModState (CompositorMethods)
- (void)compositeUIOnTarget:(id<MTLRenderCommandEncoder>)encoder
               worldTexture:(id<MTLTexture>)worldTex
                  uiTexture:(id<MTLTexture>)uiTex;
@end

@implementation MetalModState (Pacer)

- (void)processFrameWithColor:(id<MTLTexture>)colorTex
                        depth:(id<MTLTexture>)depthTex
                       motion:(id<MTLTexture>)motionTex
                           ui:(id<MTLTexture>)uiTex
                       params:(const MetalModFrameParams *)params {
    if (!self.commandQueue || !colorTex) return;

    uint64_t startTime = mach_absolute_time();

    id<MTLCommandBuffer> cmdBuffer = [self.commandQueue commandBuffer];
    cmdBuffer.label = @"MetalMod Pipeline Command Buffer";

    if (!self.metalLayer) {
        NSWindow *w = [NSApp keyWindow] ?: [NSApp mainWindow];
        if (!w && [[NSApp windows] count] > 0) w = [[NSApp windows] firstObject];
        if (w) {
            NSView *v = [w contentView];
            if ([v.layer isKindOfClass:[CAMetalLayer class]]) {
                self.metalLayer = (CAMetalLayer *)v.layer;
            } else {
                for (CALayer *sub in v.layer.sublayers) {
                    if ([sub isKindOfClass:[CAMetalLayer class]]) {
                        self.metalLayer = (CAMetalLayer *)sub;
                        break;
                    }
                }
            }
            if (self.metalLayer) {
                self.metalLayer.maximumDrawableCount = 4;
                self.metalLayer.presentsWithTransaction = NO;
                self.metalLayer.displaySyncEnabled = YES;
            }
        }
    }

    id<MTLTexture> currentUpscaleDst = self->_historyTextures[self.currentHistoryIndex] ?: self.upscaledTexture;
    id<MTLTexture> prevFrameTexture = self->_historyTextures[1 - self.currentHistoryIndex] ?: self.prevColorTexture;
    id<MTLTexture> currentFullResWorld = colorTex;

    // 1. MetalFX Upscaling Pass (direct encode into active ping-pong target)
    if (self.config.scalingMode == METALMOD_SCALING_SPATIAL && self.spatialScaler) {
        currentFullResWorld = currentUpscaleDst;
        [self encodeSpatialUpscaling:cmdBuffer sourceTexture:colorTex destinationTexture:currentUpscaleDst];
    } else if (self.config.scalingMode == METALMOD_SCALING_TEMPORAL && self.temporalScaler) {
        currentFullResWorld = currentUpscaleDst;
        [self encodeTemporalUpscaling:cmdBuffer
                         colorTexture:colorTex
                         depthTexture:depthTex
                        motionTexture:motionTex
                   destinationTexture:currentUpscaleDst
                               params:params];
    }

    // 2. Metal 4 Frame Interpolation Pass (if enabled and history exists)
    if (self.config.frameGenerationEnabled && self.frameInterpolator && self.hasHistory && !params->resetHistory) {
        // Generate intermediate frame (t - 0.5)
        [self encodeFrameInterpolation:cmdBuffer
                          currentFrame:currentFullResWorld
                         previousFrame:prevFrameTexture
                          depthTexture:depthTex
                         motionTexture:motionTex
                             uiTexture:uiTex
                   interpolatedTexture:self.interpolatedTexture
                                params:params];

        // Present intermediate frame to CAMetalLayer if available
        if (self.metalLayer) {
            id<CAMetalDrawable> interpDrawable = [self.metalLayer nextDrawable];
            if (interpDrawable) {
                MTLRenderPassDescriptor *passDesc = [MTLRenderPassDescriptor renderPassDescriptor];
                passDesc.colorAttachments[0].texture = interpDrawable.texture;
                passDesc.colorAttachments[0].loadAction = MTLLoadActionDontCare;
                passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;

                id<MTLRenderCommandEncoder> enc = [cmdBuffer renderCommandEncoderWithDescriptor:passDesc];
                enc.label = @"Interpolated Frame UI Compositor Encoder";
                [self compositeUIOnTarget:enc worldTexture:self.interpolatedTexture uiTexture:uiTex];
                [enc endEncoding];

                [cmdBuffer presentDrawable:interpDrawable];
                _telemetry.totalFramesPresented++;
            }
        }
    }

    // 3. Present Real Frame (t)
    if (self.metalLayer) {
        id<CAMetalDrawable> realDrawable = [self.metalLayer nextDrawable];
        if (realDrawable) {
            MTLRenderPassDescriptor *passDesc = [MTLRenderPassDescriptor renderPassDescriptor];
            passDesc.colorAttachments[0].texture = realDrawable.texture;
            passDesc.colorAttachments[0].loadAction = MTLLoadActionDontCare;
            passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;

            id<MTLRenderCommandEncoder> enc = [cmdBuffer renderCommandEncoderWithDescriptor:passDesc];
            enc.label = @"Real Frame UI Compositor Encoder";
            [self compositeUIOnTarget:enc worldTexture:currentFullResWorld uiTexture:uiTex];
            [enc endEncoding];

            [cmdBuffer presentDrawable:realDrawable];
            _telemetry.totalFramesPresented++;
        }
    }

    // 4. Zero-Copy History Advance: Simply flip the ping-pong index!
    if (self.config.frameGenerationEnabled) {
        self.currentHistoryIndex ^= 1;
        self.hasHistory = YES;
    }

    // Telemetry updates
    _telemetry.totalFramesRendered++;
    mach_timebase_info_data_t timebase;
    mach_timebase_info(&timebase);
    uint64_t frameIdx = params->frameIndex;

    [cmdBuffer addCompletedHandler:^(id<MTLCommandBuffer> buffer) {
        (void)buffer;
        uint64_t endTime = mach_absolute_time();
        uint64_t elapsedNano = (endTime - startTime) * timebase.numer / timebase.denom;
        float frameTimeMs = (float)elapsedNano / 1000000.0f;
        self->_telemetry.gpuFrameTimeMs = frameTimeMs;
        if (frameTimeMs > 0.0001f) {
            self->_telemetry.renderFPS = 1000.0f / frameTimeMs;
            self->_telemetry.presentedFPS = self.config.frameGenerationEnabled ? (2.0f * self->_telemetry.renderFPS) : self->_telemetry.renderFPS;
        }
        if ((frameIdx & 63) == 0) {
            dispatch_async(dispatch_get_main_queue(), ^{
                NSWindow *w = [NSApp keyWindow] ?: [NSApp mainWindow];
                if (!w && [[NSApp windows] count] > 0) w = [[NSApp windows] firstObject];
                if (w) {
                    NSString *modeStr = self.config.scalingMode == METALMOD_SCALING_TEMPORAL ? @"Temporal" : (self.config.scalingMode == METALMOD_SCALING_SPATIAL ? @"Spatial" : @"Off");
                    NSString *title = [NSString stringWithFormat:@"Minecraft [MetalMod: %ux%u -> %ux%u (%@) | FG: %@ | FPS: %.0f]",
                                      self.config.inputWidth, self.config.inputHeight,
                                      self.config.outputWidth, self.config.outputHeight,
                                      modeStr,
                                      self.config.frameGenerationEnabled ? @"ON" : @"OFF",
                                      self->_telemetry.presentedFPS];
                    [w setTitle:title];
                }
            });
        }
    }];

    [cmdBuffer commit];
}

@end
