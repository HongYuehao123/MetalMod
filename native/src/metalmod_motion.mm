// Render-resolution motion vectors for the MetalFX temporal scaler. See metalmod_motion.h for the
// contract, and metalmod_metalfx.mm for the scaler that consumes the result.
//
// One rule shapes this file, the same one that shapes the MetalFX layer: MetalMod owns the queue, so
// this is an ordinary dispatch encoded onto the frame's own queue rather than a second pipeline to
// synchronise. It creates its own command buffer and commits it, which is what orders the dispatch
// behind the level passes that wrote the depth buffer and ahead of the scaler that reads the result.

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

#include "metalmod/metalmod_motion.h"

#include <string.h>

// ---------------------------------------------------------------------------------------------
// The kernel
// ---------------------------------------------------------------------------------------------

// The whole motion model, in one kernel. It is deliberately small enough to read in one sitting,
// because every term in it is a convention that has to agree with the Java side and with MetalFX:
//
//   * NDC is Metal's: x to the right, y up, and the pixel grid starts at the top-left. The depth
//     buffer was written by a projection built for a zero-to-one depth range, so the stored value is
//     the clip-space z directly and no [0,1] conversion happens here.
//
//   * The position is reconstructed by inverting the current view-projection rather than by using the
//     near/far planes explicitly. That keeps the depth convention in exactly one place - the matrix -
//     so a change to the projection cannot silently desynchronise this file.
//
//   * The reprojection already carries the frame-to-frame camera translation: the previous matrix is
//     a view-projection of camera-relative space translated by the camera's own displacement, so the
//     reconstructed position needs no camera position added back.
//
//   * A depth of exactly 1.0 is the far plane. The sky and anything the projection could not resolve
//     has no finite world position, and reprojecting it would produce an arbitrary vector; the far
//     plane gets zero motion, which tells the scaler to trust the history there.
static NSString* const kMMMotionKernelSource = @""
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "\n"
    "struct MMMMotionUniforms {\n"
    "    float4x4 currentInverseViewProjection;\n"
    "    float4x4 previousViewProjection;\n"
    "    float2 inputSize;\n"
    "    float2 pad;\n"
    "};\n"
    "\n"
    "kernel void mmm_motion_reproject(depth2d<float, access::read> depth [[texture(0)]],\n"
    "                                 texture2d<float, access::write> motion [[texture(1)]],\n"
    "                                 constant MMMMotionUniforms& uniforms [[buffer(0)]],\n"
    "                                 uint2 gid [[thread_position_in_grid]]) {\n"
    "    if (gid.x >= uint(uniforms.inputSize.x) || gid.y >= uint(uniforms.inputSize.y)) return;\n"
    "    float d = depth.read(gid);\n"
    "    float2 pixel = float2(gid) + 0.5;\n"
    "    float2 ndc = float2(pixel.x / uniforms.inputSize.x * 2.0 - 1.0,\n"
    "                        1.0 - pixel.y / uniforms.inputSize.y * 2.0);\n"
    "    float4 position = uniforms.currentInverseViewProjection * float4(ndc, d, 1.0);\n"
    "    float2 vector = float2(0.0);\n"
    "    if (d < 1.0 && abs(position.w) > 1e-7) {\n"
    "        float3 relative = position.xyz / position.w;\n"
    "        float4 previous = uniforms.previousViewProjection * float4(relative, 1.0);\n"
    "        if (previous.w > 1e-7) {\n"
    "            float2 previousNdc = previous.xy / previous.w;\n"
    "            float2 previousPixel = float2((previousNdc.x * 0.5 + 0.5) * uniforms.inputSize.x,\n"
    "                                          (0.5 - previousNdc.y * 0.5) * uniforms.inputSize.y);\n"
    "            vector = previousPixel - pixel;\n"
    "        }\n"
    "    }\n"
    "    motion.write(float4(vector, 0.0, 0.0), gid);\n"
    "}\n"
    "\n"
    // --- the overlay: geometry the depth buffer cannot describe -------------------------------
    //
    // A screen-space rectangle per object, drawn over the dispatch's result. The object's own
    // previous position gives the motion, and the depth buffer decides whether the object is
    // actually the surface at that pixel: a mob standing behind a wall must not stamp its motion
    // onto the wall. The test is a range rather than an equality because the object occupies a range
    // of depths, and the one comparison keeps the draw affordable.
    "struct MMMMotionStamp {\n"
    "    float2 boxMin;\n"
    "    float2 boxMax;\n"
    "    float2 motion;\n"
    "    float2 depthRange;\n"
    "};\n"
    "\n"
    "struct MMMMotionOverlayUniforms {\n"
    "    float2 targetSize;\n"
    "    float2 pad;\n"
    "};\n"
    "\n"
    "struct MMMMotionOverlayVertex {\n"
    "    float4 position [[position]];\n"
    "    float2 motion;\n"
    "    float2 depthRange;\n"
    "};\n"
    "\n"
    "vertex MMMMotionOverlayVertex mmm_motion_overlay_vertex(\n"
    "        uint vertexId [[vertex_id]],\n"
    "        uint instanceId [[instance_id]],\n"
    "        constant MMMMotionStamp* stamps [[buffer(0)]],\n"
    "        constant MMMMotionOverlayUniforms& uniforms [[buffer(1)]]) {\n"
    "    MMMMotionStamp stamp = stamps[instanceId];\n"
    "    // A four-vertex triangle strip: the four corners of the box.\n"
    "    float2 corner = float2((vertexId & 1) ? 1.0 : 0.0, (vertexId & 2) ? 1.0 : 0.0);\n"
    "    float2 pixel = mix(stamp.boxMin, stamp.boxMax, corner);\n"
    "    MMMMotionOverlayVertex out;\n"
    "    out.position = float4(pixel.x / uniforms.targetSize.x * 2.0 - 1.0,\n"
    "                          1.0 - pixel.y / uniforms.targetSize.y * 2.0,\n"
    "                          0.0, 1.0);\n"
    "    out.motion = stamp.motion;\n"
    "    out.depthRange = stamp.depthRange;\n"
    "    return out;\n"
    "}\n"
    "\n"
    "fragment float4 mmm_motion_overlay_fragment(MMMMotionOverlayVertex in [[stage_in]],\n"
    "                                            depth2d<float, access::read> sceneDepth [[texture(0)]]) {\n"
    "    float scene = sceneDepth.read(uint2(in.position.xy));\n"
    "    if (scene < in.depthRange.x - 1e-4 || scene > in.depthRange.y + 1e-4) {\n"
    "        discard_fragment();\n"
    "    }\n"
    "    return float4(in.motion, 0.0, 0.0);\n"
    "}\n";

// Laid out to match `struct MMMMotionUniforms` in the kernel exactly. Plain float arrays rather than
// a matrix type, so the C side and the MSL side cannot disagree about a wrapper's padding, and
// column-major at both ends (MSL's `float4x4` is column-major and JOML's `Matrix4f.get` writes
// column-major), so nothing is transposed at the boundary.
struct MMMMotionUniforms {
    float currentInverseViewProjection[16];
    float previousViewProjection[16];
    float inputSize[2];
    float pad[2];
} __attribute__((aligned(16)));

// Matches `struct MMMMotionStamp` on both sides: eight floats, 32 bytes, no padding.
struct MMMMotionStampUniforms {
    float boxMinX;
    float boxMinY;
    float boxMaxX;
    float boxMaxY;
    float motionX;
    float motionY;
    float depthMin;
    float depthMax;
} __attribute__((aligned(8)));

struct MMMMotionOverlayUniforms {
    float targetSize[2];
    float pad[2];
} __attribute__((aligned(8)));

// The public ABI struct and the internal copy must stay identical: the stamp API copies one into the
// other per element, so a size or layout drift would be a silent misread rather than a compile error.
static_assert(sizeof(MMMMotionStamp) == sizeof(MMMMotionStampUniforms),
              "the public stamp and the internal copy must have the same size");
static_assert(sizeof(MMMMotionStamp) == 8 * sizeof(float), "a stamp is eight floats");

/// Most stamps one frame can carry. Bounded because the whole set is uploaded and drawn per frame.
/// 2048 covers every visible entity and moving block with room for the particles that matter - a rain
/// shower is the case that reaches it - and a frame that exceeds it drops the tail rather than
/// growing without limit. The upload is 64 KB and each stamp draws a handful of pixels, so the cost
/// that scales is the object count, not the pixel count.
#define MMM_MOTION_STAMP_CAPACITY 2048

// ---------------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------------

struct MMMMotion {
    id<MTLTexture> texture;
    int32_t width;
    int32_t height;
    // The stamp buffer is created with the resource and rewritten in place, so a steady frame does
    // not allocate. Shared storage, because the CPU writes it and the GPU reads it.
    id<MTLBuffer> stampBuffer;
    int32_t stampCount;
    int32_t stampsDropped;
    // What the last run did, for mmm_motion_describe(). The Java side reports it on F3 rather than
    // reconstructing a story from what it asked for.
    char summary[192];
};

static char g_MotionError[512] = {0};

static void mmm_motion_set_error(NSString* message) {
    const char* utf8 = message != nil ? message.UTF8String : "unknown motion failure";
    if (utf8 == NULL) utf8 = "unknown motion failure";
    strncpy(g_MotionError, utf8, sizeof(g_MotionError) - 1);
    g_MotionError[sizeof(g_MotionError) - 1] = '\0';
}

const char* mmm_motion_last_error(void) {
    return g_MotionError;
}

// One compiled pipeline pair per device. Nothing in either shader depends on the size or the formats
// of the textures they read - the overlay writes the same RG16Float the dispatch does - so a resize
// replaces the texture but not the pipelines, and compiling them once keeps a window drag from
// recompiling shaders every frame. Only ever touched from the render thread.
//
// Held as `void*` with a deliberate +1 rather than as ARC-managed globals. A strong global is
// released from a static destructor at image unload, which for a JVM process happens after the
// Objective-C runtime has begun tearing down - and that is an abort at exit, after every check has
// already passed. These objects live for the process by construction, so leaking them is the correct
// lifetime, not a workaround.
static void* g_MotionPipeline = NULL;
static void* g_MotionOverlayPipeline = NULL;
static void* g_MotionPipelineDevice = NULL;

static bool mmm_motion_build_pipelines(id<MTLDevice> metalDevice) {
    if (g_MotionPipeline != NULL && g_MotionOverlayPipeline != NULL
            && g_MotionPipelineDevice == (__bridge void*)metalDevice) {
        return true;
    }
    NSError* error = nil;
    id<MTLLibrary> library = [metalDevice newLibraryWithSource:kMMMotionKernelSource
                                                       options:nil
                                                         error:&error];
    if (library == nil) {
        mmm_motion_set_error([NSString stringWithFormat:@"motion shaders did not compile: %@",
                              error.localizedDescription]);
        return false;
    }
    id<MTLFunction> reproject = [library newFunctionWithName:@"mmm_motion_reproject"];
    if (reproject == nil) {
        mmm_motion_set_error(@"motion entry point mmm_motion_reproject is missing");
        return false;
    }
    id<MTLComputePipelineState> compute =
            [metalDevice newComputePipelineStateWithFunction:reproject error:&error];
    if (compute == nil) {
        mmm_motion_set_error([NSString stringWithFormat:@"motion kernel did not build: %@",
                              error.localizedDescription]);
        return false;
    }

    id<MTLFunction> vertex = [library newFunctionWithName:@"mmm_motion_overlay_vertex"];
    id<MTLFunction> fragment = [library newFunctionWithName:@"mmm_motion_overlay_fragment"];
    if (vertex == nil || fragment == nil) {
        mmm_motion_set_error(@"motion overlay entry points are missing");
        return false;
    }
    MTLRenderPipelineDescriptor* descriptor = [[MTLRenderPipelineDescriptor alloc] init];
    descriptor.label = @"MetalMod motion overlay";
    descriptor.vertexFunction = vertex;
    descriptor.fragmentFunction = fragment;
    descriptor.colorAttachments[0].pixelFormat = MTLPixelFormatRG16Float;
    // The overlay replaces the dispatch's answer inside the shape, so it must not blend with it.
    descriptor.colorAttachments[0].blendingEnabled = NO;
    id<MTLRenderPipelineState> overlay =
            [metalDevice newRenderPipelineStateWithDescriptor:descriptor error:&error];
    if (overlay == nil) {
        mmm_motion_set_error([NSString stringWithFormat:@"motion overlay did not build: %@",
                              error.localizedDescription]);
        return false;
    }

    g_MotionPipeline = (__bridge_retained void*)compute;
    g_MotionOverlayPipeline = (__bridge_retained void*)overlay;
    g_MotionPipelineDevice = (__bridge void*)metalDevice;
    return true;
}


// ---------------------------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------------------------

void* mmm_motion_create(void* device, int32_t width, int32_t height) {
    g_MotionError[0] = '\0';
    id<MTLDevice> metalDevice = (__bridge id<MTLDevice>)device;
    if (metalDevice == nil) {
        mmm_motion_set_error(@"no Metal device");
        return NULL;
    }
    if (width <= 0 || height <= 0) {
        mmm_motion_set_error([NSString stringWithFormat:@"invalid motion size %dx%d", width, height]);
        return NULL;
    }
    if (!mmm_motion_build_pipelines(metalDevice)) {
        return NULL;
    }

    @autoreleasepool {
        MTLTextureDescriptor* descriptor =
                [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRG16Float
                                                                    width:(NSUInteger)width
                                                                   height:(NSUInteger)height
                                                                mipmapped:NO];
        // Shader read is what the temporal scaler's motionTextureUsage requires; shader write is what
        // the reprojection kernel needs; render target is what lets the overlay draw over the result.
        // Shared storage keeps the texture readable from this process, which is how the offline check
        // verifies the vectors rather than only their presence.
        descriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite
                | MTLTextureUsageRenderTarget;
        descriptor.storageMode = MTLStorageModeShared;

        id<MTLTexture> texture = [metalDevice newTextureWithDescriptor:descriptor];
        if (texture == nil) {
            mmm_motion_set_error([NSString stringWithFormat:
                    @"could not allocate the %dx%d motion texture", width, height]);
            return NULL;
        }
        texture.label = @"MetalMod motion vectors";

        id<MTLBuffer> stamps = [metalDevice newBufferWithLength:
                        (NSUInteger)(MMM_MOTION_STAMP_CAPACITY * sizeof(MMMMotionStampUniforms))
                                                       options:MTLResourceStorageModeShared];
        if (stamps == nil) {
            mmm_motion_set_error(@"could not allocate the motion stamp buffer");
            return NULL;
        }
        stamps.label = @"MetalMod motion stamps";

        MMMMotion* handle = new MMMMotion();
        handle->texture = texture;
        handle->width = width;
        handle->height = height;
        handle->stampBuffer = stamps;
        handle->stampCount = 0;
        handle->stampsDropped = 0;
        snprintf(handle->summary, sizeof(handle->summary), "motion %dx%d, not run yet", width, height);
        return handle;
    }
}

void mmm_motion_release(void* motion) {
    if (motion == NULL) return;
    @autoreleasepool {
        MMMMotion* handle = (MMMMotion*)motion;
        delete handle;
    }
}

void* mmm_motion_texture(void* motion) {
    MMMMotion* handle = (MMMMotion*)motion;
    return handle == NULL ? NULL : (__bridge void*)handle->texture;
}

int32_t mmm_motion_width(void* motion) {
    MMMMotion* handle = (MMMMotion*)motion;
    return handle == NULL ? 0 : handle->width;
}

int32_t mmm_motion_height(void* motion) {
    MMMMotion* handle = (MMMMotion*)motion;
    return handle == NULL ? 0 : handle->height;
}

bool mmm_motion_depth_format_supported(void* device, int64_t depthFormat) {
    id<MTLDevice> metalDevice = (__bridge id<MTLDevice>)device;
    if (metalDevice == nil) return false;
    // The kernel reads the depth buffer as a `depth2d<float, access::read>`, which is what a depth
    // texture has to be declared as in Metal. Only the single-channel depth formats can be bound
    // there; a combined depth/stencil format carries a second aspect the kernel does not declare.
    switch ((MTLPixelFormat)depthFormat) {
        case MTLPixelFormatDepth16Unorm:
        case MTLPixelFormatDepth32Float:
            return YES;
        default:
            return NO;
    }
}

const char* mmm_motion_describe(void* motion) {
    MMMMotion* handle = (MMMMotion*)motion;
    if (handle == NULL) return "motion: absent";
    return handle->summary;
}

int32_t mmm_motion_stamp_capacity(void* motion) {
    return motion == NULL ? 0 : MMM_MOTION_STAMP_CAPACITY;
}

int mmm_motion_set_stamps(void* motion, const MMMMotionStamp* stamps, int32_t count) {
    MMMMotion* handle = (MMMMotion*)motion;
    if (handle == NULL || handle->stampBuffer == nil) return -1;
    if (count < 0) return -2;
    if (stamps == NULL || count == 0) {
        handle->stampCount = 0;
        handle->stampsDropped = 0;
        return 0;
    }
    int32_t stored = count > MMM_MOTION_STAMP_CAPACITY ? MMM_MOTION_STAMP_CAPACITY : count;
    memcpy(handle->stampBuffer.contents, stamps,
           (size_t)stored * sizeof(MMMMotionStampUniforms));
    handle->stampCount = stored;
    handle->stampsDropped = count - stored;
    return stored;
}

void mmm_motion_stamp_stats(void* motion, int32_t* outStored, int32_t* outDropped) {
    MMMMotion* handle = (MMMMotion*)motion;
    int32_t stored = handle == NULL ? 0 : handle->stampCount;
    int32_t dropped = handle == NULL ? 0 : handle->stampsDropped;
    if (outStored != NULL) *outStored = stored;
    if (outDropped != NULL) *outDropped = dropped;
}

// ---------------------------------------------------------------------------------------------
// The run
// ---------------------------------------------------------------------------------------------

int mmm_motion_run(void* motion, void* queue, void* depthTexture,
                   const float* currentInverseViewProjection,
                   const float* previousViewProjection) {
    g_MotionError[0] = '\0';
    MMMMotion* handle = (MMMMotion*)motion;
    id<MTLCommandQueue> metalQueue = (__bridge id<MTLCommandQueue>)queue;
    id<MTLTexture> depth = (__bridge id<MTLTexture>)depthTexture;
    if (handle == NULL || handle->texture == nil) return -1;
    if (metalQueue == nil) return -2;
    if (depth == nil) return -3;
    if (currentInverseViewProjection == NULL || previousViewProjection == NULL) return -4;
    if (depth.width != (NSUInteger)handle->width || depth.height != (NSUInteger)handle->height) {
        snprintf(handle->summary, sizeof(handle->summary),
                 "motion skipped: depth is %lux%lu against the %dx%d motion texture",
                 (unsigned long)depth.width, (unsigned long)depth.height,
                 handle->width, handle->height);
        mmm_motion_set_error(@"the depth texture does not match the motion texture's size");
        return -5;
    }

    if (g_MotionPipeline == NULL) return -6;
    id<MTLComputePipelineState> pipeline =
            (__bridge id<MTLComputePipelineState>)g_MotionPipeline;

    MMMMotionUniforms uniforms;
    memcpy(uniforms.currentInverseViewProjection, currentInverseViewProjection,
           sizeof(uniforms.currentInverseViewProjection));
    memcpy(uniforms.previousViewProjection, previousViewProjection,
           sizeof(uniforms.previousViewProjection));
    uniforms.inputSize[0] = (float)handle->width;
    uniforms.inputSize[1] = (float)handle->height;
    uniforms.pad[0] = 0.0f;
    uniforms.pad[1] = 0.0f;

    MMMMotionOverlayUniforms overlayUniforms;
    overlayUniforms.targetSize[0] = (float)handle->width;
    overlayUniforms.targetSize[1] = (float)handle->height;
    overlayUniforms.pad[0] = 0.0f;
    overlayUniforms.pad[1] = 0.0f;

    @autoreleasepool {
        id<MTLCommandBuffer> commandBuffer = [metalQueue commandBuffer];
        commandBuffer.label = @"MetalMod motion vectors";
        id<MTLComputeCommandEncoder> encoder = [commandBuffer computeCommandEncoder];
        encoder.label = @"MetalMod motion reprojection";
        [encoder setComputePipelineState:pipeline];
        [encoder setTexture:depth atIndex:0];
        [encoder setTexture:handle->texture atIndex:1];
        [encoder setBytes:&uniforms length:sizeof(uniforms) atIndex:0];

        // A 16x16 threadgroup is the usual sweet spot for a full-screen dispatch; the grid is
        // dispatched exactly, so the kernel's own bounds check is a second guard rather than the only
        // one, and a size that is not a multiple of the group needs no padding pass.
        NSUInteger wide = 16, high = 16;
        MTLSize threadsPerGroup = MTLSizeMake(wide, high, 1);
        MTLSize groups = MTLSizeMake(((NSUInteger)handle->width + wide - 1) / wide,
                                     ((NSUInteger)handle->height + high - 1) / high, 1);
        [encoder dispatchThreadgroups:groups threadsPerThreadgroup:threadsPerGroup];
        [encoder endEncoding];

        // The overlay, in the same command buffer and therefore ordered after the dispatch: what the
        // depth could not describe is drawn over what it could. Load rather than clear, or the
        // dispatch's whole result would be discarded.
        if (handle->stampCount > 0 && g_MotionOverlayPipeline != NULL) {
            MTLRenderPassDescriptor* pass = [MTLRenderPassDescriptor renderPassDescriptor];
            pass.colorAttachments[0].texture = handle->texture;
            pass.colorAttachments[0].loadAction = MTLLoadActionLoad;
            pass.colorAttachments[0].storeAction = MTLStoreActionStore;
            id<MTLRenderCommandEncoder> overlay =
                    [commandBuffer renderCommandEncoderWithDescriptor:pass];
            overlay.label = @"MetalMod motion overlay";
            [overlay setRenderPipelineState:
                    (__bridge id<MTLRenderPipelineState>)g_MotionOverlayPipeline];
            [overlay setVertexBuffer:handle->stampBuffer offset:0 atIndex:0];
            [overlay setVertexBytes:&overlayUniforms length:sizeof(overlayUniforms) atIndex:1];
            [overlay setFragmentTexture:depth atIndex:0];
            // The box is given as two corners, so a four-vertex triangle strip covers it.
            [overlay drawPrimitives:MTLPrimitiveTypeTriangleStrip
                        vertexStart:0
                        vertexCount:4
                      instanceCount:(NSUInteger)handle->stampCount];
            [overlay endEncoding];
        }
        [commandBuffer commit];

        snprintf(handle->summary, sizeof(handle->summary),
                 "motion %dx%d dispatched (%lux%lu groups), %d stamps%s",
                 handle->width, handle->height,
                 (unsigned long)groups.width, (unsigned long)groups.height,
                 handle->stampCount,
                 handle->stampsDropped > 0 ? " (some dropped)" : "");
    }
    return 0;
}
