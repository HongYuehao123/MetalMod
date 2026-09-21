#import "metalmod_internal.h"

@implementation MetalModState (Interpolator)

- (BOOL)setupFrameInterpolator {
    if (!self.device) return NO;

    if (@available(macOS 26.0, *)) {
        MTLFXFrameInterpolatorDescriptor *desc = [MTLFXFrameInterpolatorDescriptor new];
        desc.inputWidth = self.config.outputWidth;
        desc.inputHeight = self.config.outputHeight;
        desc.outputWidth = self.config.outputWidth;
        desc.outputHeight = self.config.outputHeight;
        desc.colorTextureFormat = self.config.enableHDR ? MTLPixelFormatRGBA16Float : MTLPixelFormatBGRA8Unorm;
        desc.depthTextureFormat = MTLPixelFormatDepth32Float;
        desc.motionTextureFormat = MTLPixelFormatRG16Float;
        desc.outputTextureFormat = self.config.enableHDR ? MTLPixelFormatRGBA16Float : MTLPixelFormatBGRA8Unorm;

        if (![MTLFXFrameInterpolatorDescriptor supportsDevice:self.device]) {
            NSLog(@"[MetalMod] Device does not support MTLFXFrameInterpolator with specified settings.");
            return NO;
        }

        self.frameInterpolator = [desc newFrameInterpolatorWithDevice:self.device];
        return self.frameInterpolator != nil;
    }
    return NO;
}

- (void)encodeFrameInterpolation:(id<MTLCommandBuffer>)commandBuffer
                    currentFrame:(id<MTLTexture>)currFrame
                   previousFrame:(id<MTLTexture>)prevFrame
                    depthTexture:(id<MTLTexture>)depthTex
                   motionTexture:(id<MTLTexture>)motionTex
                       uiTexture:(id<MTLTexture>)uiTex
             interpolatedTexture:(id<MTLTexture>)interpolatedDst
                          params:(const MetalModFrameParams *)params {
    if (@available(macOS 26.0, *)) {
        if (!self.frameInterpolator || !commandBuffer || !currFrame || !prevFrame || !interpolatedDst) {
            return;
        }

        self.frameInterpolator.colorTexture = currFrame;
        self.frameInterpolator.prevColorTexture = prevFrame;
        self.frameInterpolator.depthTexture = depthTex;
        self.frameInterpolator.motionTexture = motionTex;
        self.frameInterpolator.outputTexture = interpolatedDst;

        // Decoupled UI texture injection
        if (uiTex) {
            self.frameInterpolator.uiTexture = uiTex;
            self.frameInterpolator.uiTextureComposited = NO;
        } else {
            self.frameInterpolator.uiTexture = nil;
            self.frameInterpolator.uiTextureComposited = NO;
        }

        self.frameInterpolator.deltaTime = params->deltaTime > 0.001f ? params->deltaTime : 0.016f;
        self.frameInterpolator.motionVectorScaleX = 1.0f;
        self.frameInterpolator.motionVectorScaleY = 1.0f;
        self.frameInterpolator.jitterOffsetX = params->jitterOffsetX;
        self.frameInterpolator.jitterOffsetY = params->jitterOffsetY;
        self.frameInterpolator.nearPlane = params->nearPlane;
        self.frameInterpolator.farPlane = params->farPlane;
        self.frameInterpolator.fieldOfView = params->fieldOfView;
        self.frameInterpolator.aspectRatio = params->aspectRatio;
        self.frameInterpolator.depthReversed = params->isDepthReversed;
        self.frameInterpolator.shouldResetHistory = params->resetHistory || !self.hasHistory;

        [self.frameInterpolator encodeToCommandBuffer:commandBuffer];
    }
}

@end
