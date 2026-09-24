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

// ---------------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------------

struct MMMMotion {
    id<MTLTexture> texture;
    int32_t width;
    int32_t height;
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

// One compiled pipeline per device. The kernel has no size or format in it, so a resize replaces the
// texture but not the pipeline; compiling it once keeps a window drag from recompiling shaders every
// frame. Only ever touched from the render thread.
static id<MTLComputePipelineState> g_MotionPipeline = nil;
static void* g_MotionPipelineDevice = NULL;

static id<MTLComputePipelineState> mmm_motion_pipeline(id<MTLDevice> metalDevice) {
    if (g_MotionPipeline != nil && g_MotionPipelineDevice == (__bridge void*)metalDevice) {
        return g_MotionPipeline;
    }
    NSError* error = nil;
    id<MTLLibrary> library = [metalDevice newLibraryWithSource:kMMMotionKernelSource
                                                       options:nil
                                                         error:&error];
    if (library == nil) {
        mmm_motion_set_error([NSString stringWithFormat:@"motion kernel did not compile: %@",
                              error.localizedDescription]);
        return nil;
    }
    id<MTLFunction> function = [library newFunctionWithName:@"mmm_motion_reproject"];
    if (function == nil) {
        mmm_motion_set_error(@"motion kernel entry point mmm_motion_reproject is missing");
        return nil;
    }
    id<MTLComputePipelineState> pipeline =
            [metalDevice newComputePipelineStateWithFunction:function error:&error];
    if (pipeline == nil) {
        mmm_motion_set_error([NSString stringWithFormat:@"motion pipeline did not build: %@",
                              error.localizedDescription]);
        return nil;
    }
    g_MotionPipeline = pipeline;
    g_MotionPipelineDevice = (__bridge void*)metalDevice;
    return g_MotionPipeline;
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
    if (mmm_motion_pipeline(metalDevice) == nil) {
        return NULL;
    }

    @autoreleasepool {
        MTLTextureDescriptor* descriptor =
                [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRG16Float
                                                                    width:(NSUInteger)width
                                                                   height:(NSUInteger)height
                                                                mipmapped:NO];
        // Shader read is what the temporal scaler's motionTextureUsage requires; shader write is what
        // the kernel needs. Shared storage keeps the texture readable from this process, which is how
        // the offline check verifies the vectors rather than only their presence.
        descriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
        descriptor.storageMode = MTLStorageModeShared;

        id<MTLTexture> texture = [metalDevice newTextureWithDescriptor:descriptor];
        if (texture == nil) {
            mmm_motion_set_error([NSString stringWithFormat:
                    @"could not allocate the %dx%d motion texture", width, height]);
            return NULL;
        }
        texture.label = @"MetalMod motion vectors";

        MMMMotion* handle = new MMMMotion();
        handle->texture = texture;
        handle->width = width;
        handle->height = height;
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

    id<MTLComputePipelineState> pipeline = g_MotionPipeline;
    if (pipeline == nil) return -6;

    MMMMotionUniforms uniforms;
    memcpy(uniforms.currentInverseViewProjection, currentInverseViewProjection,
           sizeof(uniforms.currentInverseViewProjection));
    memcpy(uniforms.previousViewProjection, previousViewProjection,
           sizeof(uniforms.previousViewProjection));
    uniforms.inputSize[0] = (float)handle->width;
    uniforms.inputSize[1] = (float)handle->height;
    uniforms.pad[0] = 0.0f;
    uniforms.pad[1] = 0.0f;

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
        [commandBuffer commit];

        snprintf(handle->summary, sizeof(handle->summary),
                 "motion %dx%d dispatched (%lux%lu groups)",
                 handle->width, handle->height,
                 (unsigned long)groups.width, (unsigned long)groups.height);
    }
    return 0;
}
