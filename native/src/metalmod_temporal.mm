#import "metalmod_internal.h"

@implementation MetalModState (Temporal)

- (BOOL)setupTemporalScaler {
    if (!self.device) return NO;

    MTLFXTemporalScalerDescriptor *desc = [MTLFXTemporalScalerDescriptor new];
    desc.inputWidth = self.config.inputWidth;
    desc.inputHeight = self.config.inputHeight;
    desc.outputWidth = self.config.outputWidth;
    desc.outputHeight = self.config.outputHeight;
    desc.colorTextureFormat = self.config.enableHDR ? MTLPixelFormatRGBA16Float : MTLPixelFormatBGRA8Unorm;
    desc.depthTextureFormat = MTLPixelFormatDepth32Float;
    desc.motionTextureFormat = MTLPixelFormatRG16Float;
    desc.outputTextureFormat = self.config.enableHDR ? MTLPixelFormatRGBA16Float : MTLPixelFormatBGRA8Unorm;
    desc.autoExposureEnabled = YES;
    desc.inputContentPropertiesEnabled = NO;

    if (![MTLFXTemporalScalerDescriptor supportsDevice:self.device]) {
        NSLog(@"[MetalMod] Device does not support requested temporal scaler configuration.");
        return NO;
    }

    self.temporalScaler = [desc newTemporalScalerWithDevice:self.device];
    return self.temporalScaler != nil;
}

- (void)encodeTemporalUpscaling:(id<MTLCommandBuffer>)commandBuffer
                   colorTexture:(id<MTLTexture>)colorTex
                   depthTexture:(id<MTLTexture>)depthTex
                  motionTexture:(id<MTLTexture>)motionTex
             destinationTexture:(id<MTLTexture>)dstTex
                         params:(const MetalModFrameParams *)params {
    if (!self.temporalScaler || !commandBuffer || !colorTex || !dstTex) return;

    self.temporalScaler.colorTexture = colorTex;
    self.temporalScaler.depthTexture = depthTex;
    self.temporalScaler.motionTexture = motionTex;
    self.temporalScaler.outputTexture = dstTex;
    self.temporalScaler.jitterOffsetX = params->jitterOffsetX;
    self.temporalScaler.jitterOffsetY = params->jitterOffsetY;
    self.temporalScaler.reset = params->resetHistory || !self.hasHistory;
    self.temporalScaler.depthReversed = params->isDepthReversed;
    self.temporalScaler.motionVectorScaleX = 1.0f;
    self.temporalScaler.motionVectorScaleY = 1.0f;

    [self.temporalScaler encodeToCommandBuffer:commandBuffer];
}

@end
