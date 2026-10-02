#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#include <cstdio>
int main(){@autoreleasepool{
 id<MTLDevice> device=MTLCreateSystemDefaultDevice();
 if(!device){printf("no Metal device\n");return 1;}
 printf("Device: %s\nSpatial supported: %d\nTemporal supported: %d\n",device.name.UTF8String,
 [MTLFXSpatialScalerDescriptor supportsDevice:device],[MTLFXTemporalScalerDescriptor supportsDevice:device]);
 if(![MTLFXTemporalScalerDescriptor supportsDevice:device])return 2;
 MTLFXTemporalScalerDescriptor* d=[MTLFXTemporalScalerDescriptor new];
 d.inputWidth=192;d.inputHeight=108;d.outputWidth=256;d.outputHeight=144;
 d.colorTextureFormat=MTLPixelFormatRGBA8Unorm;d.depthTextureFormat=MTLPixelFormatDepth32Float;
 d.motionTextureFormat=MTLPixelFormatRG16Float;d.outputTextureFormat=MTLPixelFormatRGBA8Unorm;
 d.autoExposureEnabled=NO;
 id<MTLFXTemporalScaler> fx=[d newTemporalScalerWithDevice:device];
 printf("SDR+Depth32+RG16 creation: %d\n",fx!=nil);
 if(fx)printf("texture usages: colour=%lu depth=%lu motion=%lu output=%lu\n",(unsigned long)fx.colorTextureUsage,(unsigned long)fx.depthTextureUsage,(unsigned long)fx.motionTextureUsage,(unsigned long)fx.outputTextureUsage);
 return fx?0:3;
}}
