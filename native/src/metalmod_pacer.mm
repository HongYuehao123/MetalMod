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

    [self.pipelineLock lock];

    uint64_t frameIdx = params->frameIndex;

    MTLPixelFormat colorFormat = self.config.enableHDR ? MTLPixelFormatRGBA16Float
                                                       : MTLPixelFormatBGRA8Unorm;
    MTLPixelFormat uiFormat = colorFormat;

    // Validate every input against the format the scaler was configured with. MetalFX does not
    // validate this for us; feeding it a mismatched format produces undefined results.
    if (self.config.scalingMode == METALMOD_SCALING_SPATIAL) {
        if (![self validateTexture:colorTex expected:colorFormat role:@"color" scaler:@"Spatial"]) {
            [self.pipelineLock unlock];
            return;
        }
    } else if (self.config.scalingMode == METALMOD_SCALING_TEMPORAL) {
        if (![self validateTexture:colorTex expected:colorFormat role:@"color" scaler:@"Temporal"] ||
            ![self validateTexture:depthTex expected:MTLPixelFormatDepth32Float role:@"depth" scaler:@"Temporal"] ||
            ![self validateTexture:motionTex expected:MTLPixelFormatRG16Float role:@"motion" scaler:@"Temporal"]) {
            [self.pipelineLock unlock];
            return;
        }
    }

    if (self.interpolatedTexturePurged && self.interpolatedTexture) {
        // A purged texture holds undefined contents until it is made non-volatile again.
        [self.interpolatedTexture setPurgeableState:MTLPurgeableStateNonVolatile];
        self.interpolatedTexturePurged = NO;
    }

    id<MTLCommandBuffer> cmdBuffer = [self.commandQueue commandBuffer];
    cmdBuffer.label = @"MetalMod Pipeline Command Buffer";

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

    // 2. Metal 4 Frame Interpolation Pass.
    //
    // This is only encoded when a pacer exists: interpolation is pointless without the ability to
    // present the intermediate frame on its own display refresh, and it roughly triples the CPU
    // cost of the frame (measured ~0.9 ms -> ~3.1 ms per frame for temporal + interpolation).
    BOOL wantsInterpolation = self.config.frameGenerationEnabled &&
                              METALMOD_PACER_AVAILABLE &&
                              self.frameInterpolator != nil &&
                              self.hasHistory && !params->resetHistory;

    self.frameGenerationActive = wantsInterpolation;

    if (wantsInterpolation &&
        [self validateTexture:depthTex expected:MTLPixelFormatDepth32Float role:@"depth" scaler:@"FrameInterpolator"] &&
        [self validateTexture:motionTex expected:MTLPixelFormatRG16Float role:@"motion" scaler:@"FrameInterpolator"]) {

        [self encodeFrameInterpolation:cmdBuffer
                          currentFrame:currentFullResWorld
                         previousFrame:prevFrameTexture
                          depthTexture:depthTex
                         motionTexture:motionTex
                             uiTexture:uiTex
                   interpolatedTexture:self.interpolatedTexture
                                params:params];

        // The interpolated frame is composited and presented in the same command buffer as the
        // real frame only as a placeholder; a correct pacer must present it separately.
        if (self.metalLayer && METALMOD_PACER_AVAILABLE) {
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
                self->_telemetry.totalFramesPresented++;
            }
        }
    }

    // 3. Present the real frame (t)
    if (self.metalLayer && METALMOD_OWNS_PRESENTATION) {
        id<CAMetalDrawable> realDrawable = [self.metalLayer nextDrawable];
        if (realDrawable) {
            MTLRenderPassDescriptor *passDesc = [MTLRenderPassDescriptor renderPassDescriptor];
            passDesc.colorAttachments[0].texture = realDrawable.texture;
            passDesc.colorAttachments[0].loadAction = MTLLoadActionDontCare;
            passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;

            id<MTLRenderCommandEncoder> enc = [cmdBuffer renderCommandEncoderWithDescriptor:passDesc];
            enc.label = @"Real Frame UI Compositor Encoder";
            [self compositeUIOnTarget:enc worldTexture:currentFullResWorld
                            uiTexture:(uiTex.pixelFormat == uiFormat ? uiTex : nil)];
            [enc endEncoding];

            [cmdBuffer presentDrawable:realDrawable];
            self->_telemetry.totalFramesPresented++;
        }
    }

    // 4. Zero-Copy History Advance: flip the ping-pong index
    if (self.config.frameGenerationEnabled) {
        self.currentHistoryIndex ^= 1;
        self.hasHistory = YES;
    }

    self->_telemetry.totalFramesRendered++;

    // GPU time must come from the command buffer's own GPU timestamps, not from wall-clock around
    // encoding. Wall-clock spans queue latency (including waiting on in-flight buffers) and is not
    // a GPU measurement; the previous implementation used it and derived a fake FPS from it.
    [cmdBuffer addCompletedHandler:^(id<MTLCommandBuffer> buffer) {
        CFTimeInterval gpuStart = buffer.GPUStartTime;
        CFTimeInterval gpuEnd = buffer.GPUEndTime;
        if (gpuEnd > gpuStart && gpuStart > 0.0) {
            self->_telemetry.gpuFrameTimeMs = (float)((gpuEnd - gpuStart) * 1000.0);
        }

        // Presented FPS is derived only from frames that were actually handed to the display.
        NSTimeInterval now = CACurrentMediaTime();
        NSTimeInterval window = now - self->_presentedWindowStart;
        if (window >= 0.5) {
            self->_telemetry.presentedFPS = (float)((double)self->_presentedWindowCount / window);
            self->_presentedWindowCount = 0;
            self->_presentedWindowStart = now;
        }

        if ((frameIdx & 63) == 0) {
            dispatch_async(dispatch_get_main_queue(), ^{
                NSWindow *w = [NSApp keyWindow] ?: [NSApp mainWindow];
                if (!w && [[NSApp windows] count] > 0) w = [[NSApp windows] firstObject];
                if (!w) return;

                NSString *modeStr = @"Off";
                if (self.config.scalingMode == METALMOD_SCALING_TEMPORAL) {
                    modeStr = @"Temporal";
                } else if (self.config.scalingMode == METALMOD_SCALING_SPATIAL) {
                    modeStr = @"Spatial";
                }

                NSString *fgStr = self.config.frameGenerationEnabled
                    ? (self.frameGenerationActive ? @"ON" : @"requested/inactive")
                    : @"OFF";

                NSString *title = [NSString stringWithFormat:
                    @"Minecraft [MetalMod: %ux%u -> %ux%u (%@) | FG: %@ | GPU: %.2f ms]",
                    self.config.inputWidth, self.config.inputHeight,
                    self.config.outputWidth, self.config.outputHeight,
                    modeStr, fgStr, self->_telemetry.gpuFrameTimeMs];
                [w setTitle:title];
            });
        }
    }];

    [cmdBuffer commit];

    [self.pipelineLock unlock];
}

@end
