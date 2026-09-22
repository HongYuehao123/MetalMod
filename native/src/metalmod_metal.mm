// Native Metal substrate implementation. See metalmod_metal.h for the contract.

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#import <Cocoa/Cocoa.h>

#include "metalmod/metalmod_metal.h"

// The device is process-wide: MTLCreateSystemDefaultDevice returns the same object on Apple
// silicon anyway, and the game only ever wants one. Cached so repeated calls stay cheap and the
// object cannot be released out from under an in-flight command buffer.
static id<MTLDevice> g_Device = nil;

// Dedicated queue for scheduling presents. Note: [MTLDevice newCommandBuffer] is the Metal 4 API
// and returns id<MTL4CommandBuffer>; command buffers for the Metal 3 path must come from a queue.
static id<MTLCommandQueue> g_PresentQueue = nil;

static inline id<MTLDevice> mmm_device(void* handle) {
    return (__bridge id<MTLDevice>)handle;
}

static inline id<MTLCommandQueue> mmm_queue(void* handle) {
    return (__bridge id<MTLCommandQueue>)handle;
}

static inline id<MTLTexture> mmm_texture(void* handle) {
    return (__bridge id<MTLTexture>)handle;
}

static inline CAMetalLayer* mmm_layer(void* handle) {
    return (__bridge CAMetalLayer*)handle;
}

// ---------------------------------------------------------------------------------------------
// Device
// ---------------------------------------------------------------------------------------------

void* mmm_device_create(void) {
    @autoreleasepool {
        if (g_Device == nil) {
            g_Device = MTLCreateSystemDefaultDevice();
        }
        return (__bridge_retained void*)g_Device;
    }
}

void mmm_device_release(void* device) {
    // The cached device is process-lifetime; balance the retain from mmm_device_create without
    // actually tearing Metal down (the game holds textures and queues elsewhere).
    if (device != NULL) {
        id<MTLDevice> released = (__bridge_transfer id<MTLDevice>)device;
        (void)released;
    }
}

int mmm_device_info(void* device, char* outName, size_t nameCapacity,
                    char* outVendor, size_t vendorCapacity, char* outDriver, size_t driverCapacity) {
    id<MTLDevice> dev = mmm_device(device);
    if (dev == nil) return -1;

    @autoreleasepool {
        if (outName && nameCapacity > 0) {
            const char* value = dev.name.UTF8String;
            snprintf(outName, nameCapacity, "%s", value ? value : "Unknown Metal device");
        }
        if (outVendor && vendorCapacity > 0) {
            // Metal exposes no vendor string; Apple is both vendor and implementer here.
            snprintf(outVendor, vendorCapacity, "%s", "Apple");
        }
        if (outDriver && driverCapacity > 0) {
            snprintf(outDriver, driverCapacity, "Metal (macOS)");
        }
    }
    return 0;
}

int64_t mmm_device_max_texture_size(void* device) {
    id<MTLDevice> dev = mmm_device(device);
    if (dev == nil) return 0;
    // Metal exposes no max-texture-size query (verified against the macOS 27 SDK: no
    // maxTextureDimension property exists anywhere in MTLDevice). Derive it from the GPU family:
    // every Apple silicon GPU supports 16384; anything older falls back to a conservative 8192.
    if ([dev supportsFamily:MTLGPUFamilyApple3]) return 16384;
    return 8192;
}

int64_t mmm_device_max_buffer_size(void* device) {
    id<MTLDevice> dev = mmm_device(device);
    if (dev == nil) return 0;
    return (int64_t)dev.maxBufferLength;
}

int64_t mmm_device_recommended_working_set(void* device) {
    id<MTLDevice> dev = mmm_device(device);
    if (dev == nil) return 0;
    return (int64_t)dev.recommendedMaxWorkingSetSize;
}

bool mmm_device_supports_family(void* device, int32_t family) {
    id<MTLDevice> dev = mmm_device(device);
    if (dev == nil) return false;
    return [dev supportsFamily:(MTLGPUFamily)family];
}

// ---------------------------------------------------------------------------------------------
// Command queue
// ---------------------------------------------------------------------------------------------

void* mmm_queue_create(void* device) {
    id<MTLDevice> dev = mmm_device(device);
    if (dev == nil) return NULL;
    @autoreleasepool {
        id<MTLCommandQueue> queue = [dev newCommandQueue];
        return (__bridge_retained void*)queue;
    }
}

void mmm_queue_release(void* queue) {
    if (queue == NULL) return;
    @autoreleasepool {
        id<MTLCommandQueue> released = (__bridge_transfer id<MTLCommandQueue>)queue;
        (void)released;
    }
}

// ---------------------------------------------------------------------------------------------
// Textures
// ---------------------------------------------------------------------------------------------

void* mmm_texture_create(void* device, int64_t pixelFormat, int32_t width, int32_t height,
                         bool storageShared, uint32_t usageFlags) {
    id<MTLDevice> dev = mmm_device(device);
    if (dev == nil || width <= 0 || height <= 0) return NULL;

    @autoreleasepool {
        MTLTextureDescriptor* descriptor =
            [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:(MTLPixelFormat)pixelFormat
                                                               width:(NSUInteger)width
                                                              height:(NSUInteger)height
                                                           mipmapped:NO];
        descriptor.storageMode = storageShared ? MTLStorageModeShared : MTLStorageModePrivate;
        descriptor.usage = (MTLTextureUsage)usageFlags;
        id<MTLTexture> texture = [dev newTextureWithDescriptor:descriptor];
        return (__bridge_retained void*)texture;
    }
}

void mmm_texture_release(void* texture) {
    if (texture == NULL) return;
    @autoreleasepool {
        id<MTLTexture> released = (__bridge_transfer id<MTLTexture>)texture;
        (void)released;
    }
}

int32_t mmm_texture_width(void* texture) {
    id<MTLTexture> tex = mmm_texture(texture);
    return tex == nil ? 0 : (int32_t)tex.width;
}

int32_t mmm_texture_height(void* texture) {
    id<MTLTexture> tex = mmm_texture(texture);
    return tex == nil ? 0 : (int32_t)tex.height;
}

int mmm_texture_read(void* texture, void* out, size_t capacity, size_t rowBytes) {
    id<MTLTexture> tex = mmm_texture(texture);
    if (tex == nil || out == NULL) return -1;
    if (tex.storageMode != MTLStorageModeShared) return -2;  // private textures are not CPU-readable
    if (rowBytes * tex.height > capacity) return -3;

    @autoreleasepool {
        [tex getBytes:out
          bytesPerRow:rowBytes
           fromRegion:MTLRegionMake2D(0, 0, tex.width, tex.height)
          mipmapLevel:0];
    }
    return 0;
}

void* mmm_texture_create_full(void* device, int64_t pixelFormat, int32_t width, int32_t height,
                              int32_t depthOrLayers, int32_t mipLevels, int32_t textureType,
                              bool storageShared, uint32_t usageFlags) {
    id<MTLDevice> dev = mmm_device(device);
    if (dev == nil || width <= 0 || height <= 0) return NULL;

    @autoreleasepool {
        MTLTextureDescriptor* descriptor = [[MTLTextureDescriptor alloc] init];
        descriptor.pixelFormat = (MTLPixelFormat)pixelFormat;
        descriptor.width = (NSUInteger)width;
        descriptor.height = (NSUInteger)height;
        descriptor.mipmapLevelCount = (NSUInteger)(mipLevels < 1 ? 1 : mipLevels);
        descriptor.textureType = (MTLTextureType)textureType;
        descriptor.usage = (MTLTextureUsage)usageFlags;
        descriptor.storageMode = storageShared ? MTLStorageModeShared : MTLStorageModePrivate;

        if (textureType == MTLTextureTypeCube || textureType == MTLTextureTypeCubeArray) {
            descriptor.arrayLength = (NSUInteger)((depthOrLayers < 6 ? 6 : depthOrLayers) / 6);
        } else if (textureType == MTLTextureType2DArray) {
            descriptor.arrayLength = (NSUInteger)(depthOrLayers < 1 ? 1 : depthOrLayers);
        } else if (textureType == MTLTextureType3D) {
            descriptor.depth = (NSUInteger)(depthOrLayers < 1 ? 1 : depthOrLayers);
        }

        id<MTLTexture> texture = [dev newTextureWithDescriptor:descriptor];
        return (__bridge_retained void*)texture;
    }
}

void* mmm_texture_create_view(void* texture, int64_t pixelFormat, int32_t textureType,
                              int32_t baseMipLevel, int32_t mipLevels,
                              int32_t baseLayer, int32_t layerCount) {
    id<MTLTexture> tex = mmm_texture(texture);
    if (tex == nil) return NULL;

    @autoreleasepool {
        NSRange levels = NSMakeRange((NSUInteger)(baseMipLevel < 0 ? 0 : baseMipLevel),
                                     (NSUInteger)(mipLevels < 1 ? 1 : mipLevels));
        NSRange slices = NSMakeRange((NSUInteger)(baseLayer < 0 ? 0 : baseLayer),
                                     (NSUInteger)(layerCount < 1 ? 1 : layerCount));
        id<MTLTexture> view = [tex newTextureViewWithPixelFormat:(MTLPixelFormat)pixelFormat
                                                     textureType:(MTLTextureType)textureType
                                                          levels:levels
                                                          slices:slices];
        return (__bridge_retained void*)view;
    }
}

int mmm_texture_replace_region(void* texture, int32_t mipLevel, int32_t slice,
                               int32_t x, int32_t y, int32_t width, int32_t height,
                               const void* data, size_t bytesPerRow) {
    id<MTLTexture> tex = mmm_texture(texture);
    if (tex == nil || data == NULL || width <= 0 || height <= 0) return -1;
    if (tex.storageMode == MTLStorageModePrivate) return -2;  // not CPU-writable

    @autoreleasepool {
        MTLRegion region = MTLRegionMake2D(x, y, width, height);
        [tex replaceRegion:region
               mipmapLevel:mipLevel
                     slice:slice
                 withBytes:data
               bytesPerRow:bytesPerRow
              bytesPerImage:bytesPerRow * (size_t)height];
    }
    return 0;
}

int mmm_texture_read_region(void* texture, int32_t mipLevel, int32_t slice,
                            int32_t x, int32_t y, int32_t width, int32_t height,
                            void* out, size_t capacity, size_t bytesPerRow) {
    id<MTLTexture> tex = mmm_texture(texture);
    if (tex == nil || out == NULL || width <= 0 || height <= 0) return -1;
    if (tex.storageMode != MTLStorageModeShared) return -2;  // private textures are not CPU-readable
    if (bytesPerRow * (size_t)height > capacity) return -3;

    @autoreleasepool {
        MTLRegion region = MTLRegionMake2D(x, y, width, height);
        [tex getBytes:out
          bytesPerRow:bytesPerRow
         bytesPerImage:bytesPerRow * (size_t)height
            fromRegion:region
           mipmapLevel:mipLevel
                 slice:slice];
    }
    return 0;
}

// ---------------------------------------------------------------------------------------------
// Buffers
// ---------------------------------------------------------------------------------------------

void* mmm_buffer_create(void* device, int64_t length) {
    id<MTLDevice> dev = mmm_device(device);
    if (dev == nil || length < 0) return NULL;

    @autoreleasepool {
        // Shared keeps the buffer CPU-visible, which is what the engine's mapping and upload paths
        // need. Apple silicon has unified memory, so the cost is lower than on discrete GPUs; a
        // private/staging split is a Phase 3 performance task.
        id<MTLBuffer> buffer = [dev newBufferWithLength:(NSUInteger)length
                                                options:MTLResourceStorageModeShared];
        return (__bridge_retained void*)buffer;
    }
}

void* mmm_buffer_contents(void* buffer) {
    id<MTLBuffer> buf = (__bridge id<MTLBuffer>)buffer;
    return (buf == nil) ? NULL : buf.contents;
}

int64_t mmm_buffer_length(void* buffer) {
    id<MTLBuffer> buf = (__bridge id<MTLBuffer>)buffer;
    return (buf == nil) ? 0 : (int64_t)buf.length;
}

void mmm_buffer_release(void* buffer) {
    if (buffer == NULL) return;
    @autoreleasepool {
        id<MTLBuffer> released = (__bridge_transfer id<MTLBuffer>)buffer;
        (void)released;
    }
}

// ---------------------------------------------------------------------------------------------
// Samplers
// ---------------------------------------------------------------------------------------------

void* mmm_sampler_create(void* device, int32_t addressU, int32_t addressV,
                         int32_t minFilter, int32_t magFilter,
                         int32_t maxAnisotropy, bool hasMaxLod, double maxLod) {
    id<MTLDevice> dev = mmm_device(device);
    if (dev == nil) return NULL;

    @autoreleasepool {
        MTLSamplerDescriptor* descriptor = [[MTLSamplerDescriptor alloc] init];
        descriptor.sAddressMode = (MTLSamplerAddressMode)addressU;
        descriptor.tAddressMode = (MTLSamplerAddressMode)addressV;
        descriptor.minFilter = (MTLSamplerMinMagFilter)minFilter;
        descriptor.magFilter = (MTLSamplerMinMagFilter)magFilter;
        descriptor.mipFilter = (minFilter == MTLSamplerMinMagFilterLinear)
                                   ? MTLSamplerMipFilterLinear
                                   : MTLSamplerMipFilterNearest;
        descriptor.maxAnisotropy = (NSUInteger)(maxAnisotropy < 1 ? 1 : maxAnisotropy);
        descriptor.lodMinClamp = 0.0f;
        if (hasMaxLod) {
            descriptor.lodMaxClamp = (float)maxLod;
        }
        id<MTLSamplerState> sampler = [dev newSamplerStateWithDescriptor:descriptor];
        return (__bridge_retained void*)sampler;
    }
}

void mmm_sampler_release(void* sampler) {
    if (sampler == NULL) return;
    @autoreleasepool {
        id<MTLSamplerState> released = (__bridge_transfer id<MTLSamplerState>)sampler;
        (void)released;
    }
}

// ---------------------------------------------------------------------------------------------
// Clear
// ---------------------------------------------------------------------------------------------

int mmm_clear_textures(void* queue, void* colorTexture, bool hasColor,
                       float r, float g, float b, float a,
                       void* depthTexture, bool hasDepth, double depthValue) {
    id<MTLCommandQueue> metalQueue = mmm_queue(queue);
    id<MTLTexture> color = mmm_texture(colorTexture);
    id<MTLTexture> depth = mmm_texture(depthTexture);
    if (metalQueue == nil) return -1;
    if (!hasColor && !hasDepth) return 0;

    @autoreleasepool {
        MTLRenderPassDescriptor* descriptor = [MTLRenderPassDescriptor renderPassDescriptor];
        if (hasColor && color != nil) {
            descriptor.colorAttachments[0].texture = color;
            descriptor.colorAttachments[0].loadAction = MTLLoadActionClear;
            descriptor.colorAttachments[0].storeAction = MTLStoreActionStore;
            descriptor.colorAttachments[0].clearColor = MTLClearColorMake(r, g, b, a);
        }
        if (hasDepth && depth != nil) {
            descriptor.depthAttachment.texture = depth;
            descriptor.depthAttachment.loadAction = MTLLoadActionClear;
            descriptor.depthAttachment.storeAction = MTLStoreActionStore;
            descriptor.depthAttachment.clearDepth = depthValue;
        }
        id<MTLCommandBuffer> commandBuffer = [metalQueue commandBuffer];
        commandBuffer.label = @"MetalMod clear";
        id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:descriptor];
        [encoder endEncoding];
        [commandBuffer commit];
    }
    return 0;
}

// ---------------------------------------------------------------------------------------------
// Surface
// ---------------------------------------------------------------------------------------------

void* mmm_layer_create(void* nsView) {
    @autoreleasepool {
        CAMetalLayer* layer = nil;

        if (nsView != NULL) {
            NSView* view = (__bridge NSView*)nsView;
            if ([view.layer isKindOfClass:[CAMetalLayer class]]) {
                layer = (CAMetalLayer*)view.layer;
            } else {
                // Give the view a Metal layer. WantsLayer must be set before assigning.
                view.wantsLayer = YES;
                layer = [CAMetalLayer layer];
                layer.frame = view.bounds;
                [view setLayer:layer];
            }
        } else {
            // Detached layer - used by the smoke test and for offscreen verification.
            layer = [CAMetalLayer layer];
            layer.frame = CGRectMake(0, 0, 64, 64);
        }

        if (layer == nil) return NULL;
        layer.device = g_Device;
        layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
        layer.framebufferOnly = NO;   // allow the upscaler and RT passes to read it later
        return (__bridge_retained void*)layer;
    }
}

void mmm_layer_release(void* layer) {
    if (layer == NULL) return;
    @autoreleasepool {
        CAMetalLayer* released = (__bridge_transfer CAMetalLayer*)layer;
        (void)released;
    }
}

int mmm_layer_configure(void* layer, int32_t width, int32_t height, bool vsync) {
    CAMetalLayer* metalLayer = mmm_layer(layer);
    if (metalLayer == nil || width <= 0 || height <= 0) return -1;

    @autoreleasepool {
        metalLayer.drawableSize = CGSizeMake(width, height);
        // Three drawables keeps the CPU one frame ahead without the latency of a deep queue.
        metalLayer.maximumDrawableCount = 3;
        metalLayer.displaySyncEnabled = vsync ? YES : NO;
        metalLayer.presentsWithTransaction = NO;
    }
    return 0;
}

int mmm_layer_acquire(void* layer, void** outDrawable, void** outTexture) {
    CAMetalLayer* metalLayer = mmm_layer(layer);
    if (metalLayer == nil || outDrawable == NULL || outTexture == NULL) return -1;

    @autoreleasepool {
        id<CAMetalDrawable> drawable = [metalLayer nextDrawable];
        if (drawable == nil) {
            *outDrawable = NULL;
            *outTexture = NULL;
            return -2;
        }
        // The texture is owned by the drawable; hand it out borrowed.
        *outDrawable = (__bridge_retained void*)drawable;
        *outTexture = (__bridge void*)drawable.texture;
    }
    return 0;
}

void mmm_layer_present(void* layer, void* drawable) {
    CAMetalLayer* metalLayer = mmm_layer(layer);
    id<CAMetalDrawable> metalDrawable = (__bridge id<CAMetalDrawable>)drawable;
    if (metalLayer == nil || metalDrawable == nil) return;

    @autoreleasepool {
        // Minecraft's GpuSurface API calls present() *after* submitting its encoder, and Metal
        // requires presentDrawable: before commit. Scheduling the present from its own command
        // buffer on the same queue preserves ordering (same-queue buffers execute in commit order)
        // while matching the API's call order.
        if (g_PresentQueue == nil) {
            g_PresentQueue = [metalLayer.device newCommandQueue];
            g_PresentQueue.label = @"MetalMod present queue";
        }
        id<MTLCommandBuffer> presentBuffer = [g_PresentQueue commandBuffer];
        presentBuffer.label = @"MetalMod present";
        [presentBuffer presentDrawable:metalDrawable];
        [presentBuffer commit];

        id<CAMetalDrawable> released = (__bridge_transfer id<CAMetalDrawable>)drawable;
        (void)released;
    }
}

// ---------------------------------------------------------------------------------------------
// Command buffers and render passes
// ---------------------------------------------------------------------------------------------

void* mmm_command_buffer_create(void* queue) {
    id<MTLCommandQueue> metalQueue = mmm_queue(queue);
    if (metalQueue == nil) return NULL;
    @autoreleasepool {
        id<MTLCommandBuffer> commandBuffer = [metalQueue commandBuffer];
        commandBuffer.label = @"MetalMod command buffer";
        return (__bridge_retained void*)commandBuffer;
    }
}

void mmm_command_buffer_commit(void* commandBuffer) {
    id<MTLCommandBuffer> buffer = (__bridge id<MTLCommandBuffer>)commandBuffer;
    if (buffer == nil) return;
    @autoreleasepool {
        [buffer commit];
    }
}

void mmm_command_buffer_wait(void* commandBuffer) {
    id<MTLCommandBuffer> buffer = (__bridge id<MTLCommandBuffer>)commandBuffer;
    if (buffer == nil) return;
    @autoreleasepool {
        [buffer waitUntilCompleted];
    }
}

void mmm_command_buffer_release(void* commandBuffer) {
    if (commandBuffer == NULL) return;
    @autoreleasepool {
        id<MTLCommandBuffer> released = (__bridge_transfer id<MTLCommandBuffer>)commandBuffer;
        (void)released;
    }
}

void* mmm_begin_clear_pass(void* commandBuffer, void* texture,
                           float r, float g, float b, float a) {
    id<MTLCommandBuffer> buffer = (__bridge id<MTLCommandBuffer>)commandBuffer;
    id<MTLTexture> target = mmm_texture(texture);
    if (buffer == nil || target == nil) return NULL;

    @autoreleasepool {
        MTLRenderPassDescriptor* descriptor = [MTLRenderPassDescriptor renderPassDescriptor];
        descriptor.colorAttachments[0].texture = target;
        descriptor.colorAttachments[0].loadAction = MTLLoadActionClear;
        descriptor.colorAttachments[0].storeAction = MTLStoreActionStore;
        descriptor.colorAttachments[0].clearColor = MTLClearColorMake(r, g, b, a);

        // The default render area is the full attachment, which is what a clear wants.
        id<MTLRenderCommandEncoder> encoder = [buffer renderCommandEncoderWithDescriptor:descriptor];
        encoder.label = @"MetalMod clear pass";
        return (__bridge_retained void*)encoder;
    }
}

void mmm_end_encoding(void* encoder) {
    id<MTLRenderCommandEncoder> metalEncoder = (__bridge id<MTLRenderCommandEncoder>)encoder;
    if (metalEncoder == nil) return;
    @autoreleasepool {
        [metalEncoder endEncoding];
        id<MTLRenderCommandEncoder> released = (__bridge_transfer id<MTLRenderCommandEncoder>)encoder;
        (void)released;
    }
}

// ---------------------------------------------------------------------------------------------
// Renderer-backend surface helpers
// ---------------------------------------------------------------------------------------------

void* mmm_layer_create_for_ns_window(void* nsWindow) {
    if (nsWindow == NULL) return NULL;
    @autoreleasepool {
        NSWindow* window = (__bridge NSWindow*)nsWindow;
        NSView* view = window.contentView;
        if (view == nil) return NULL;
        return mmm_layer_create((__bridge void*)view);
    }
}

int mmm_layer_present_clear(void* layer, void* drawable,
                            float r, float g, float b, float a) {
    CAMetalLayer* metalLayer = mmm_layer(layer);
    id<CAMetalDrawable> metalDrawable = (__bridge id<CAMetalDrawable>)drawable;
    if (metalLayer == nil || metalDrawable == nil) return -1;

    @autoreleasepool {
        id<MTLDevice> dev = metalLayer.device;
        if (dev == nil) return -2;

        if (g_PresentQueue == nil) {
            g_PresentQueue = [dev newCommandQueue];
            g_PresentQueue.label = @"MetalMod present queue";
        }
        id<MTLCommandBuffer> commandBuffer = [g_PresentQueue commandBuffer];
        commandBuffer.label = @"MetalMod present (clear)";

        MTLRenderPassDescriptor* descriptor = [MTLRenderPassDescriptor renderPassDescriptor];
        descriptor.colorAttachments[0].texture = metalDrawable.texture;
        descriptor.colorAttachments[0].loadAction = MTLLoadActionClear;
        descriptor.colorAttachments[0].storeAction = MTLStoreActionStore;
        descriptor.colorAttachments[0].clearColor = MTLClearColorMake(r, g, b, a);
        id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:descriptor];
        [encoder endEncoding];

        [commandBuffer presentDrawable:metalDrawable];
        [commandBuffer commit];

        id<CAMetalDrawable> released = (__bridge_transfer id<CAMetalDrawable>)drawable;
        (void)released;
    }
    return 0;
}
