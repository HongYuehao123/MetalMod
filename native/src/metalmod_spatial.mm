#import "metalmod_internal.h"

@implementation MetalModState (Spatial)

- (BOOL)setupSpatialScaler {
    if (!self.device) return NO;

    MTLFXSpatialScalerDescriptor *desc = [MTLFXSpatialScalerDescriptor new];
    desc.inputWidth = self.config.inputWidth;
    desc.inputHeight = self.config.inputHeight;
    desc.outputWidth = self.config.outputWidth;
    desc.outputHeight = self.config.outputHeight;
    desc.colorTextureFormat = self.config.enableHDR ? MTLPixelFormatRGBA16Float : MTLPixelFormatBGRA8Unorm;
    desc.outputTextureFormat = self.config.enableHDR ? MTLPixelFormatRGBA16Float : MTLPixelFormatBGRA8Unorm;
    desc.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;

    if (![MTLFXSpatialScalerDescriptor supportsDevice:self.device]) {
        NSLog(@"[MetalMod] Device does not support requested spatial scaler configuration.");
        return NO;
    }

    self.spatialScaler = [desc newSpatialScalerWithDevice:self.device];
    return self.spatialScaler != nil;
}

- (void)encodeSpatialUpscaling:(id<MTLCommandBuffer>)commandBuffer
                 sourceTexture:(id<MTLTexture>)srcTex
            destinationTexture:(id<MTLTexture>)dstTex {
    if (!self.spatialScaler || !commandBuffer || !srcTex || !dstTex) return;

    self.spatialScaler.colorTexture = srcTex;
    self.spatialScaler.outputTexture = dstTex;
    [self.spatialScaler encodeToCommandBuffer:commandBuffer];
}

@end
