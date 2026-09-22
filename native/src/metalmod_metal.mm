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

// ---------------------------------------------------------------------------------------------
// Shader libraries, pipelines and drawing
// ---------------------------------------------------------------------------------------------

void* mmm_library_create(void* device, const char* source, size_t length) {
    id<MTLDevice> dev = mmm_device(device);
    if (dev == nil || source == NULL) return NULL;

    @autoreleasepool {
        NSString* text = [[NSString alloc] initWithBytes:source
                                                  length:length
                                                encoding:NSUTF8StringEncoding];
        NSError* error = nil;
        id<MTLLibrary> library = [dev newLibraryWithSource:text options:nil error:&error];
        if (library == nil) {
            NSLog(@"[MetalMod] MSL compilation failed: %@", error.localizedDescription);
            return NULL;
        }
        return (__bridge_retained void*)library;
    }
}

void mmm_library_release(void* library) {
    if (library == NULL) return;
    @autoreleasepool {
        id<MTLLibrary> released = (__bridge_transfer id<MTLLibrary>)library;
        (void)released;
    }
}

// A pipeline plus the depth-stencil state it must be used with (Metal keeps them separate).
typedef struct MMMPipeline {
    void* pipelineState;
    void* depthStencilState;
    int32_t topology;
    int32_t cullMode;
    int32_t triangleFill;
    float depthBiasScale;
    float depthBiasConstant;
} MMMPipeline;

void* mmm_render_pipeline_create(
    void* device,
    void* vertexLibrary, const char* vertexFunction,
    void* fragmentLibrary, const char* fragmentFunction,
    int64_t colorFormat, int32_t colorWriteMask, int32_t blendEnabled,
    int32_t blendSrcColor, int32_t blendDstColor, int32_t blendOpColor,
    int32_t blendSrcAlpha, int32_t blendDstAlpha, int32_t blendOpAlpha,
    int64_t depthFormat, int32_t depthCompare, int32_t depthWrite,
    int32_t topology, int32_t winding, int32_t cullMode, int32_t triangleFill,
    float depthBiasScale, float depthBiasConstant,
    const MMMVertexBufferLayout* buffers, int32_t bufferCount,
    const MMMVertexAttribute* attributes, int32_t attributeCount) {
    id<MTLDevice> dev = mmm_device(device);
    id<MTLLibrary> vlib = (__bridge id<MTLLibrary>)vertexLibrary;
    id<MTLLibrary> flib = (__bridge id<MTLLibrary>)fragmentLibrary;
    if (dev == nil || vlib == nil || flib == nil || vertexFunction == NULL || fragmentFunction == NULL) {
        return NULL;
    }

    @autoreleasepool {
        id<MTLFunction> vfn = [vlib newFunctionWithName:[NSString stringWithUTF8String:vertexFunction]];
        id<MTLFunction> ffn = [flib newFunctionWithName:[NSString stringWithUTF8String:fragmentFunction]];
        if (vfn == nil || ffn == nil) {
            NSLog(@"[MetalMod] MSL function not found: %s / %s", vertexFunction, fragmentFunction);
            return NULL;
        }

        MTLVertexDescriptor* vertexDescriptor = [[MTLVertexDescriptor alloc] init];
        for (int32_t i = 0; i < bufferCount; i++) {
            const MMMVertexBufferLayout* layout = &buffers[i];
            if (layout->bufferIndex < 0 || layout->bufferIndex >= 31) continue;
            vertexDescriptor.layouts[layout->bufferIndex].stride = (NSUInteger)layout->stride;
            vertexDescriptor.layouts[layout->bufferIndex].stepFunction =
                (MTLVertexStepFunction)layout->stepFunction;
            vertexDescriptor.layouts[layout->bufferIndex].stepRate = (NSUInteger)layout->stepRate;
        }
        for (int32_t i = 0; i < attributeCount; i++) {
            const MMMVertexAttribute* attribute = &attributes[i];
            if (attribute->location < 0 || attribute->location >= 31) continue;
            vertexDescriptor.attributes[attribute->location].format =
                (MTLVertexFormat)attribute->format;
            vertexDescriptor.attributes[attribute->location].offset = (NSUInteger)attribute->offset;
            vertexDescriptor.attributes[attribute->location].bufferIndex = (NSUInteger)attribute->bufferIndex;
        }

        MTLRenderPipelineDescriptor* descriptor = [[MTLRenderPipelineDescriptor alloc] init];
        descriptor.vertexFunction = vfn;
        descriptor.fragmentFunction = ffn;
        descriptor.vertexDescriptor = vertexDescriptor;
        descriptor.rasterSampleCount = 1;
        descriptor.colorAttachments[0].pixelFormat = (MTLPixelFormat)colorFormat;
        descriptor.colorAttachments[0].writeMask = (MTLColorWriteMask)colorWriteMask;
        if (blendEnabled) {
            descriptor.colorAttachments[0].blendingEnabled = YES;
            descriptor.colorAttachments[0].sourceRGBBlendFactor = (MTLBlendFactor)blendSrcColor;
            descriptor.colorAttachments[0].destinationRGBBlendFactor = (MTLBlendFactor)blendDstColor;
            descriptor.colorAttachments[0].rgbBlendOperation = (MTLBlendOperation)blendOpColor;
            descriptor.colorAttachments[0].sourceAlphaBlendFactor = (MTLBlendFactor)blendSrcAlpha;
            descriptor.colorAttachments[0].destinationAlphaBlendFactor = (MTLBlendFactor)blendDstAlpha;
            descriptor.colorAttachments[0].alphaBlendOperation = (MTLBlendOperation)blendOpAlpha;
        }
        if (depthFormat != 0) {
            descriptor.depthAttachmentPixelFormat = (MTLPixelFormat)depthFormat;
        }

        NSError* error = nil;
        id<MTLRenderPipelineState> state = [dev newRenderPipelineStateWithDescriptor:descriptor error:&error];
        if (state == nil) {
            NSLog(@"[MetalMod] Render pipeline creation failed: %@", error.localizedDescription);
            return NULL;
        }

        MTLDepthStencilDescriptor* depthDescriptor = [[MTLDepthStencilDescriptor alloc] init];
        depthDescriptor.depthCompareFunction = (MTLCompareFunction)depthCompare;
        depthDescriptor.depthWriteEnabled = depthWrite ? YES : NO;
        id<MTLDepthStencilState> depthState = [dev newDepthStencilStateWithDescriptor:depthDescriptor];

        MMMPipeline* pipeline = (MMMPipeline*)calloc(1, sizeof(MMMPipeline));
        if (pipeline == NULL) return NULL;
        pipeline->pipelineState = (__bridge_retained void*)state;
        pipeline->depthStencilState = (__bridge_retained void*)depthState;
        pipeline->topology = topology;
        pipeline->cullMode = cullMode;
        pipeline->triangleFill = triangleFill;
        pipeline->depthBiasScale = depthBiasScale;
        pipeline->depthBiasConstant = depthBiasConstant;
        return pipeline;
    }
}

void mmm_render_pipeline_release(void* pipeline) {
    if (pipeline == NULL) return;
    MMMPipeline* metalPipeline = (MMMPipeline*)pipeline;
    @autoreleasepool {
        if (metalPipeline->pipelineState) {
            id<MTLRenderPipelineState> released = (__bridge_transfer id<MTLRenderPipelineState>)metalPipeline->pipelineState;
            (void)released;
        }
        if (metalPipeline->depthStencilState) {
            id<MTLDepthStencilState> released = (__bridge_transfer id<MTLDepthStencilState>)metalPipeline->depthStencilState;
            (void)released;
        }
    }
    free(metalPipeline);
}

void* mmm_render_pass_begin(void* commandBuffer, int32_t colorCount, void* const* colorTextures,
                            const int32_t* colorLoadClear, const float* clearColors,
                            void* depthTexture, int32_t depthLoadClear, double depthValue,
                            int32_t width, int32_t height) {
    id<MTLCommandBuffer> buffer = (__bridge id<MTLCommandBuffer>)commandBuffer;
    if (buffer == nil) return NULL;

    @autoreleasepool {
        MTLRenderPassDescriptor* descriptor = [MTLRenderPassDescriptor renderPassDescriptor];
        for (int32_t i = 0; i < colorCount && i < 8; i++) {
            id<MTLTexture> texture = mmm_texture(colorTextures[i]);
            if (texture == nil) continue;
            descriptor.colorAttachments[i].texture = texture;
            bool clear = colorLoadClear != NULL && colorLoadClear[i] != 0;
            descriptor.colorAttachments[i].loadAction = clear ? MTLLoadActionClear : MTLLoadActionLoad;
            descriptor.colorAttachments[i].storeAction = MTLStoreActionStore;
            if (clear) {
                if (clearColors != NULL) {
                    descriptor.colorAttachments[i].clearColor = MTLClearColorMake(
                        clearColors[i * 4 + 0], clearColors[i * 4 + 1],
                        clearColors[i * 4 + 2], clearColors[i * 4 + 3]);
                } else {
                    descriptor.colorAttachments[i].clearColor = MTLClearColorMake(0, 0, 0, 0);
                }
            }
        }
        id<MTLTexture> depth = mmm_texture(depthTexture);
        if (depth != nil) {
            descriptor.depthAttachment.texture = depth;
            descriptor.depthAttachment.loadAction = depthLoadClear ? MTLLoadActionClear : MTLLoadActionLoad;
            descriptor.depthAttachment.storeAction = MTLStoreActionStore;
            if (depthLoadClear) {
                descriptor.depthAttachment.clearDepth = depthValue;
            }
        }

        id<MTLRenderCommandEncoder> encoder = [buffer renderCommandEncoderWithDescriptor:descriptor];
        if (encoder == nil) return NULL;
        [encoder setViewport:(MTLViewport){0.0, 0.0, (double)width, (double)height, 0.0, 1.0}];
        [encoder setFrontFacingWinding:MTLWindingCounterClockwise];
        return (__bridge_retained void*)encoder;
    }
}

void mmm_render_pass_end(void* encoder) {
    id<MTLRenderCommandEncoder> metalEncoder = (__bridge id<MTLRenderCommandEncoder>)encoder;
    if (metalEncoder == nil) return;
    @autoreleasepool {
        [metalEncoder endEncoding];
        id<MTLRenderCommandEncoder> released = (__bridge_transfer id<MTLRenderCommandEncoder>)encoder;
        (void)released;
    }
}

void mmm_render_pass_set_pipeline(void* encoder, void* pipeline) {
    id<MTLRenderCommandEncoder> metalEncoder = (__bridge id<MTLRenderCommandEncoder>)encoder;
    MMMPipeline* metalPipeline = (MMMPipeline*)pipeline;
    if (metalEncoder == nil || metalPipeline == NULL) return;
    @autoreleasepool {
        if (metalPipeline->pipelineState) {
            [metalEncoder setRenderPipelineState:(__bridge id<MTLRenderPipelineState>)metalPipeline->pipelineState];
        }
        if (metalPipeline->depthStencilState) {
            [metalEncoder setDepthStencilState:(__bridge id<MTLDepthStencilState>)metalPipeline->depthStencilState];
        }
        [metalEncoder setCullMode:(MTLCullMode)metalPipeline->cullMode];
        [metalEncoder setTriangleFillMode:(MTLTriangleFillMode)metalPipeline->triangleFill];
        if (metalPipeline->depthBiasScale != 0.0f || metalPipeline->depthBiasConstant != 0.0f) {
            [metalEncoder setDepthBias:metalPipeline->depthBiasConstant
                            slopeScale:metalPipeline->depthBiasScale
                                 clamp:0.0f];
        }
    }
}

void mmm_render_pass_set_vertex_buffer(void* encoder, void* buffer, int64_t offset, int32_t index) {
    id<MTLRenderCommandEncoder> metalEncoder = (__bridge id<MTLRenderCommandEncoder>)encoder;
    id<MTLBuffer> metalBuffer = (__bridge id<MTLBuffer>)buffer;
    if (metalEncoder == nil || metalBuffer == nil) return;
    [metalEncoder setVertexBuffer:metalBuffer offset:(NSUInteger)offset atIndex:(NSUInteger)index];
}

void mmm_render_pass_set_fragment_buffer(void* encoder, void* buffer, int64_t offset, int32_t index) {
    id<MTLRenderCommandEncoder> metalEncoder = (__bridge id<MTLRenderCommandEncoder>)encoder;
    id<MTLBuffer> metalBuffer = (__bridge id<MTLBuffer>)buffer;
    if (metalEncoder == nil || metalBuffer == nil) return;
    [metalEncoder setFragmentBuffer:metalBuffer offset:(NSUInteger)offset atIndex:(NSUInteger)index];
}

void mmm_render_pass_set_vertex_texture(void* encoder, void* texture, int32_t index) {
    id<MTLRenderCommandEncoder> metalEncoder = (__bridge id<MTLRenderCommandEncoder>)encoder;
    id<MTLTexture> metalTexture = mmm_texture(texture);
    if (metalEncoder == nil || metalTexture == nil) return;
    [metalEncoder setVertexTexture:metalTexture atIndex:(NSUInteger)index];
}

void mmm_render_pass_set_fragment_texture(void* encoder, void* texture, int32_t index) {
    id<MTLRenderCommandEncoder> metalEncoder = (__bridge id<MTLRenderCommandEncoder>)encoder;
    id<MTLTexture> metalTexture = mmm_texture(texture);
    if (metalEncoder == nil || metalTexture == nil) return;
    [metalEncoder setFragmentTexture:metalTexture atIndex:(NSUInteger)index];
}

void mmm_render_pass_set_vertex_sampler(void* encoder, void* sampler, int32_t index) {
    id<MTLRenderCommandEncoder> metalEncoder = (__bridge id<MTLRenderCommandEncoder>)encoder;
    id<MTLSamplerState> metalSampler = (__bridge id<MTLSamplerState>)sampler;
    if (metalEncoder == nil || metalSampler == nil) return;
    [metalEncoder setVertexSamplerState:metalSampler atIndex:(NSUInteger)index];
}

void mmm_render_pass_set_fragment_sampler(void* encoder, void* sampler, int32_t index) {
    id<MTLRenderCommandEncoder> metalEncoder = (__bridge id<MTLRenderCommandEncoder>)encoder;
    id<MTLSamplerState> metalSampler = (__bridge id<MTLSamplerState>)sampler;
    if (metalEncoder == nil || metalSampler == nil) return;
    [metalEncoder setFragmentSamplerState:metalSampler atIndex:(NSUInteger)index];
}

void mmm_render_pass_set_scissor(void* encoder, int32_t x, int32_t y, int32_t width, int32_t height) {
    id<MTLRenderCommandEncoder> metalEncoder = (__bridge id<MTLRenderCommandEncoder>)encoder;
    if (metalEncoder == nil) return;
    MTLScissorRect rect = {(NSUInteger)MAX(0, x), (NSUInteger)MAX(0, y),
                           (NSUInteger)MAX(1, width), (NSUInteger)MAX(1, height)};
    [metalEncoder setScissorRect:rect];
}

void mmm_render_pass_draw(void* encoder, int32_t topology, int32_t vertexStart, int32_t vertexCount,
                          int32_t instanceCount, int32_t firstInstance) {
    id<MTLRenderCommandEncoder> metalEncoder = (__bridge id<MTLRenderCommandEncoder>)encoder;
    if (metalEncoder == nil) return;
    [metalEncoder drawPrimitives:(MTLPrimitiveType)topology
                     vertexStart:(NSUInteger)MAX(0, vertexStart)
                     vertexCount:(NSUInteger)MAX(0, vertexCount)
                   instanceCount:(NSUInteger)MAX(1, instanceCount)
                    baseInstance:(NSUInteger)MAX(0, firstInstance)];
}

void mmm_render_pass_draw_indexed(void* encoder, int32_t topology, void* indexBuffer,
                                  int64_t indexBufferOffset, int32_t indexType, int32_t indexCount,
                                  int32_t instanceCount, int32_t firstIndex, int32_t baseVertex,
                                  int32_t firstInstance) {
    id<MTLRenderCommandEncoder> metalEncoder = (__bridge id<MTLRenderCommandEncoder>)encoder;
    id<MTLBuffer> indices = (__bridge id<MTLBuffer>)indexBuffer;
    if (metalEncoder == nil || indices == nil) return;
    NSUInteger offset = (NSUInteger)indexBufferOffset;
    if (firstIndex > 0) {
        offset += (NSUInteger)firstIndex * (indexType == MTLIndexTypeUInt16 ? 2u : 4u);
    }
    [metalEncoder drawIndexedPrimitives:(MTLPrimitiveType)topology
                             indexCount:(NSUInteger)MAX(0, indexCount)
                              indexType:(MTLIndexType)indexType
                            indexBuffer:indices
                      indexBufferOffset:offset
                          instanceCount:(NSUInteger)MAX(1, instanceCount)
                             baseVertex:(NSInteger)baseVertex
                           baseInstance:(NSUInteger)MAX(0, firstInstance)];
}

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
            NSLog(@"[MetalMod] CAMetalLayer nextDrawable returned nil (outstanding drawables not presented)");
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
        [buffer addCompletedHandler:^(id<MTLCommandBuffer> completed) {
            if (completed.status == MTLCommandBufferStatusError) {
                NSLog(@"[MetalMod] render command buffer error: %@", completed.error);
            }
        }];
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

// Built-in blit pipeline: a full-screen triangle sampling the engine's render target. Cached
// because the drawable format is fixed (BGRA8) and the pipeline is identical every frame.
static id<MTLRenderPipelineState> g_BlitPipeline = nil;
static id<MTLSamplerState> g_BlitSampler = nil;

static bool mmm_ensure_blit_pipeline(id<MTLDevice> device) {
    if (g_BlitPipeline != nil) return true;

    @autoreleasepool {
        static const char* kMsl =
            "#include <metal_stdlib>\n"
            "using namespace metal;\n"
            "struct VOut { float4 pos [[position]]; float2 uv; };\n"
            "vertex VOut blit_v(uint vid [[vertex_id]]) {\n"
            "    float2 p = float2((float)((vid << 1) & 2), (float)(vid & 2));\n"
            "    VOut o; o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); o.uv = float2(p.x, 1.0 - p.y); return o;\n"
            "}\n"
            "fragment float4 blit_f(texture2d<float> src [[texture(0)]], sampler s [[sampler(0)]],\n"
            "                       VOut in [[stage_in]]) { return src.sample(s, in.uv); }\n";

        NSError* error = nil;
        NSString* text = [NSString stringWithUTF8String:kMsl];
        id<MTLLibrary> library = [device newLibraryWithSource:text options:nil error:&error];
        if (library == nil) {
            NSLog(@"[MetalMod] blit MSL failed: %@", error.localizedDescription);
            return false;
        }
        id<MTLFunction> vertexFn = [library newFunctionWithName:@"blit_v"];
        id<MTLFunction> fragmentFn = [library newFunctionWithName:@"blit_f"];
        if (vertexFn == nil || fragmentFn == nil) return false;

        MTLRenderPipelineDescriptor* descriptor = [[MTLRenderPipelineDescriptor alloc] init];
        descriptor.vertexFunction = vertexFn;
        descriptor.fragmentFunction = fragmentFn;
        descriptor.rasterSampleCount = 1;
        descriptor.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
        id<MTLRenderPipelineState> pipeline =
            [device newRenderPipelineStateWithDescriptor:descriptor error:&error];
        if (pipeline == nil) {
            NSLog(@"[MetalMod] blit pipeline failed: %@", error.localizedDescription);
            return false;
        }

        MTLSamplerDescriptor* samplerDescriptor = [[MTLSamplerDescriptor alloc] init];
        samplerDescriptor.minFilter = MTLSamplerMinMagFilterLinear;
        samplerDescriptor.magFilter = MTLSamplerMinMagFilterLinear;
        samplerDescriptor.sAddressMode = MTLSamplerAddressModeClampToEdge;
        samplerDescriptor.tAddressMode = MTLSamplerAddressModeClampToEdge;
        g_BlitSampler = [device newSamplerStateWithDescriptor:samplerDescriptor];
        g_BlitPipeline = pipeline;
        return g_BlitSampler != nil;
    }
}

void mmm_layer_set_present_queue(void* queue) {
    // The device owns the queue; this static strong reference just mirrors it for the present path.
    g_PresentQueue = (__bridge id<MTLCommandQueue>)queue;
}

int mmm_layer_present_texture(void* layer, void* drawable, void* sourceTexture) {
    CAMetalLayer* metalLayer = mmm_layer(layer);
    id<CAMetalDrawable> metalDrawable = (__bridge id<CAMetalDrawable>)drawable;
    id<MTLTexture> source = mmm_texture(sourceTexture);
    if (metalLayer == nil || metalDrawable == nil) return -1;
    if (source == nil) return -2;

    @autoreleasepool {
        id<MTLDevice> dev = metalLayer.device;
        if (dev == nil) return -3;
        if (!mmm_ensure_blit_pipeline(dev)) return -4;

        if (g_PresentQueue == nil) {
            g_PresentQueue = [dev newCommandQueue];
            g_PresentQueue.label = @"MetalMod present queue";
        }
        id<MTLCommandBuffer> commandBuffer = [g_PresentQueue commandBuffer];
        commandBuffer.label = @"MetalMod present (blit)";

        MTLRenderPassDescriptor* descriptor = [MTLRenderPassDescriptor renderPassDescriptor];
        descriptor.colorAttachments[0].texture = metalDrawable.texture;
        descriptor.colorAttachments[0].loadAction = MTLLoadActionDontCare;
        descriptor.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:descriptor];
        [encoder setRenderPipelineState:g_BlitPipeline];
        [encoder setFragmentTexture:source atIndex:0];
        [encoder setFragmentSamplerState:g_BlitSampler atIndex:0];
        [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [encoder endEncoding];

        [commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> completed) {
            if (completed.status == MTLCommandBufferStatusError) {
                NSLog(@"[MetalMod] present blit command buffer error: %@", completed.error);
            }
        }];
        [commandBuffer presentDrawable:metalDrawable];
        [commandBuffer commit];

        id<CAMetalDrawable> released = (__bridge_transfer id<CAMetalDrawable>)drawable;
        (void)released;
    }
    return 0;
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
