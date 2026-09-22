// Standalone smoke test for the native Metal substrate.
//
// Proves the device / texture / clear / readback / surface path works on this machine without
// involving Minecraft at all. Run via: cmake --build native/build --target metalmod_smoke
//
// Phase 1 depends on exactly these primitives, so if this passes the native half of the backend is
// de-risked; if it fails, the failure is isolated from the game.

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

#include <math.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include "metalmod/metalmod_metal.h"

// MTLPixelFormat / MTLTextureUsage raw values, passed through the C API so the mapping table lives
// on the Java side in one place.
static const int64_t kBGRA8Unorm = 80;
static const uint32_t kUsageShaderRead = 1;
static const uint32_t kUsageRenderTarget = 4;

static int g_failures = 0;

static void check(const char* what, bool ok, const char* detail) {
    printf("[%s] %s%s%s\n", ok ? "PASS" : "FAIL", what,
           detail && detail[0] ? " - " : "", detail ? detail : "");
    if (!ok) g_failures++;
}

static void test_device(void) {
    printf("\n== device ==\n");
    void* device = mmm_device_create();
    check("MTLCreateSystemDefaultDevice", device != NULL, "");
    if (device == NULL) return;

    char name[256] = {0};
    char vendor[128] = {0};
    char driver[128] = {0};
    int rc = mmm_device_info(device, name, sizeof(name), vendor, sizeof(vendor), driver, sizeof(driver));
    check("mmm_device_info", rc == 0, "");
    printf("     name   : %s\n", name);
    printf("     vendor : %s\n", vendor);
    printf("     driver : %s\n", driver);
    printf("     maxTexture2D      : %lld\n", (long long)mmm_device_max_texture_size(device));
    printf("     maxBufferLength   : %lld\n", (long long)mmm_device_max_buffer_size(device));
    printf("     recommendedWorkingSet : %lld bytes\n",
           (long long)mmm_device_recommended_working_set(device));

    check("max texture size is sane", mmm_device_max_texture_size(device) >= 8192, "");
    check("max buffer size is sane", mmm_device_max_buffer_size(device) > 0, "");
    mmm_device_release(device);
}

static void test_clear_and_readback(void) {
    printf("\n== clear + CPU readback ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("device", false, "no device"); return; }
    void* queue = mmm_queue_create(device);
    check("command queue", queue != NULL, "");
    if (queue == NULL) return;

    const int32_t size = 64;
    void* texture = mmm_texture_create(device, kBGRA8Unorm, size, size,
                                       /*storageShared=*/true,
                                       kUsageShaderRead | kUsageRenderTarget);
    check("shared render-target texture", texture != NULL, "");
    if (texture == NULL) return;
    check("texture dimensions reported", mmm_texture_width(texture) == size &&
                                        mmm_texture_height(texture) == size, "");

    void* cb = mmm_command_buffer_create(queue);
    check("command buffer", cb != NULL, "");
    void* encoder = mmm_begin_clear_pass(cb, texture, 0.25f, 0.50f, 0.75f, 1.0f);
    check("begin clear pass", encoder != NULL, "");
    mmm_end_encoding(encoder);
    mmm_command_buffer_commit(cb);
    mmm_command_buffer_wait(cb);

    unsigned char pixels[64 * 64 * 4];
    int rc = mmm_texture_read(texture, pixels, sizeof(pixels), size * 4);
    check("CPU readback", rc == 0, rc != 0 ? "texture not CPU-readable" : "");

    if (rc == 0) {
        // BGRA8Unorm layout in memory is B, G, R, A.
        int b = pixels[0], g = pixels[1], r = pixels[2], a = pixels[3];
        printf("     pixel(0,0) = R%d G%d B%d A%d  (expected ~R64 G128 B191 A255)\n", r, g, b, a);
        bool ok = abs(r - 64) <= 2 && abs(g - 128) <= 2 && abs(b - 191) <= 2 && a == 255;
        check("clear colour round-trips", ok, "");

        // Confirm the whole surface was cleared, not just the first texel.
        bool uniform = true;
        for (int i = 0; i < size * size; i++) {
            const unsigned char* p = pixels + i * 4;
            if (abs(p[0] - b) > 2 || abs(p[1] - g) > 2 || abs(p[2] - r) > 2) { uniform = false; break; }
        }
        check("entire texture cleared", uniform, "");
    }

    mmm_command_buffer_release(cb);
    mmm_texture_release(texture);
    mmm_queue_release(queue);
    mmm_device_release(device);
}

static void test_surface(void) {
    printf("\n== surface (detached CAMetalLayer) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("device", false, "no device"); return; }

    void* layer = mmm_layer_create(NULL);   // no view: offscreen layer
    check("layer create", layer != NULL, "");
    if (layer == NULL) return;

    check("layer configure", mmm_layer_configure(layer, 64, 64, /*vsync=*/false) == 0, "");

    void* queue = mmm_queue_create(device);
    void* drawable = NULL;
    void* drawableTexture = NULL;
    int rc = mmm_layer_acquire(layer, &drawable, &drawableTexture);
    check("acquire drawable", rc == 0 && drawable != NULL && drawableTexture != NULL,
          rc != 0 ? "nextDrawable returned nil (expected without a display)" : "");

    if (rc == 0) {
        void* cb = mmm_command_buffer_create(queue);
        void* encoder = mmm_begin_clear_pass(cb, drawableTexture, 0.1f, 0.2f, 0.3f, 1.0f);
        check("clear drawable", encoder != NULL, "");
        mmm_end_encoding(encoder);
        mmm_command_buffer_commit(cb);
        mmm_command_buffer_wait(cb);
        mmm_command_buffer_release(cb);

        mmm_layer_present(layer, drawable);
        check("present drawable", true, "");
    }

    mmm_queue_release(queue);
    mmm_layer_release(layer);
    mmm_device_release(device);
}

// Phase 2 resource layer: real textures with mips, upload/readback, texture views, buffers,
// samplers and a render-pass clear.
static void test_resources(void) {
    printf("\n== resources (texture, view, buffer, sampler, clear) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) {
        check("device", false, "no device");
        return;
    }
    void* queue = mmm_queue_create(device);

    const int64_t kRGBA8 = 70;  // MTLPixelFormatRGBA8Unorm
    const int32_t W = 4, H = 4;

    // 2D texture, one layer, two mip levels, shared storage, shaderRead|renderTarget|view.
    void* texture = mmm_texture_create_full(device, kRGBA8, W, H, 1, 2, 2 /*2D*/, true, 1u | 4u | 16u);
    check("texture create (2 mips)", texture != NULL, "");

    unsigned char pixels[16 * 4];
    for (int i = 0; i < W * H; i++) {
        pixels[i * 4 + 0] = (unsigned char)(i * 10);
        pixels[i * 4 + 1] = (unsigned char)(200 - i);
        pixels[i * 4 + 2] = 0x33;
        pixels[i * 4 + 3] = 0xFF;
    }
    int rc = mmm_texture_replace_region(texture, 0, 0, 0, 0, W, H, pixels, W * 4);
    check("texture upload (mip 0)", rc == 0, "");

    unsigned char readback[16 * 4];
    rc = mmm_texture_read_region(texture, 0, 0, 0, 0, W, H, readback, sizeof(readback), W * 4);
    check("texture readback", rc == 0, "");
    check("texture round-trips byte-exact",
          rc == 0 && memcmp(pixels, readback, sizeof(pixels)) == 0, "");

    unsigned char mip1[4 * 4];
    for (int i = 0; i < 4; i++) { mip1[i*4+0]=1; mip1[i*4+1]=2; mip1[i*4+2]=3; mip1[i*4+3]=4; }
    check("texture upload (mip 1)", mmm_texture_replace_region(texture, 1, 0, 0, 0, 2, 2, mip1, 2 * 4) == 0, "");

    void* view = mmm_texture_create_view(texture, kRGBA8, 2 /*2D*/, 1, 1, 0, 1);
    check("texture view (mip 1)", view != NULL, "");

    const int64_t kBufferLength = 256;
    void* buffer = mmm_buffer_create(device, kBufferLength);
    check("buffer create", buffer != NULL, "");
    check("buffer length", mmm_buffer_length(buffer) == kBufferLength, "");
    void* contents = mmm_buffer_contents(buffer);
    check("buffer contents pointer", contents != NULL, "");
    if (contents != NULL) {
        memset(contents, 0xAB, (size_t)kBufferLength);
        check("buffer is CPU-writable", ((unsigned char*)contents)[255] == 0xAB, "");
    }

    // addressU, addressV, minFilter, magFilter, mipFilter, maxAnisotropy, hasMaxLod, maxLod
    void* sampler = mmm_sampler_create(device, 2, 2, 1, 1, 2, 1, false, 0.0);
    check("sampler create", sampler != NULL, "");

    // Clear a render-target texture and read it back (shared storage, so CPU-readable).
    void* renderTarget = mmm_texture_create_full(device, kRGBA8, 2, 2, 1, 1, 2 /*2D*/, true, 1u | 4u);
    check("render-target texture", renderTarget != NULL, "");
    check("clear colour texture",
          mmm_clear_textures(queue, renderTarget, true, 1.0f, 0.0f, 0.0f, 1.0f,
                             NULL, false, 0.0) == 0, "");
    usleep(50 * 1000);  // the clear is committed asynchronously
    unsigned char cleared[2 * 2 * 4];
    rc = mmm_texture_read_region(renderTarget, 0, 0, 0, 0, 2, 2, cleared, sizeof(cleared), 2 * 4);
    check("cleared texture readable", rc == 0, "");
    if (rc == 0) {
        check("clear value round-trips",
              cleared[0] > 250 && cleared[1] < 5 && cleared[2] < 5 && cleared[3] > 250, "");
    }

    if (sampler) mmm_sampler_release(sampler);
    if (buffer) mmm_buffer_release(buffer);
    if (view) mmm_texture_release(view);
    if (texture) mmm_texture_release(texture);
    if (renderTarget) mmm_texture_release(renderTarget);
    if (queue) mmm_queue_release(queue);
    mmm_device_release(device);
}

// Phase 3 draw path: compile MSL, build a pipeline, render a triangle into a texture, read it back.
static void test_draw(void) {
    printf("\n== draw (MSL pipeline, triangle, readback) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("device", false, "no device"); return; }
    void* queue = mmm_queue_create(device);

    const char* msl =
        "#include <metal_stdlib>\n"
        "using namespace metal;\n"
        "struct VOut { float4 pos [[position]]; float4 color; };\n"
        "vertex VOut vmain(uint vid [[vertex_id]], const device float2* positions [[buffer(0)]]) {\n"
        "    VOut o; o.pos = float4(positions[vid], 0.0, 1.0); o.color = float4(1.0, 0.0, 0.0, 1.0); return o;\n"
        "}\n"
        "fragment float4 fmain(VOut in [[stage_in]]) { return in.color; }\n";

    void* library = mmm_library_create(device, msl, strlen(msl));
    check("MSL library compiles", library != NULL, "");

    // No vertex descriptor entries: the vertex function indexes a device buffer directly.
    void* pipeline = mmm_render_pipeline_create(device, library, "vmain", library, "fmain",
            70 /*RGBA8Unorm*/, 15 /*write all*/, 0,
            0, 0, 0, 0, 0, 0,
            0 /*no depth*/, 1, 0,
            3 /*triangle*/, 1 /*ccw*/, 0 /*no cull*/, 0 /*fill*/, 0.0f, 0.0f,
            NULL, 0, NULL, 0);
    check("render pipeline created", pipeline != NULL, "");

    const int W = 64, H = 64;
    void* target = mmm_texture_create_full(device, 70, W, H, 1, 1, 2, true, 1u | 4u);
    check("draw target texture", target != NULL, "");

    void* vertexBuffer = mmm_buffer_create(device, 24);
    float* positions = (float*)mmm_buffer_contents(vertexBuffer);
    if (positions != NULL) {
        positions[0] = -0.8f; positions[1] = -0.8f;
        positions[2] =  0.8f; positions[3] = -0.8f;
        positions[4] =  0.0f; positions[5] =  0.8f;
    }

    void* cb = mmm_command_buffer_create(queue);
    void* colors[1] = { target };
    int32_t clears[1] = { 1 };
    float clearColor[4] = { 0.0f, 0.0f, 1.0f, 1.0f };
    void* encoder = mmm_render_pass_begin(cb, 1, colors, clears, clearColor, NULL, 0, 0.0, W, H);
    check("render pass begins", encoder != NULL, "");
    if (encoder != NULL) {
        mmm_render_pass_set_pipeline(encoder, pipeline);
        mmm_render_pass_set_vertex_buffer(encoder, vertexBuffer, 0, 0);
        mmm_render_pass_draw(encoder, 3, 0, 3, 1, 0);
        mmm_render_pass_end(encoder);
    }
    mmm_command_buffer_commit(cb);
    mmm_command_buffer_wait(cb);

    unsigned char pixels[64 * 64 * 4];
    int rc = mmm_texture_read_region(target, 0, 0, 0, 0, W, H, pixels, sizeof(pixels), W * 4);
    check("draw readback", rc == 0, "");
    if (rc == 0) {
        unsigned char* center = pixels + (32 * W + 32) * 4;
        unsigned char* corner = pixels + (1 * W + 1) * 4;
        printf("     center = R%d G%d B%d A%d   corner = R%d G%d B%d A%d\n",
               center[0], center[1], center[2], center[3], corner[0], corner[1], corner[2], corner[3]);
        check("triangle covered the centre (red)", center[0] > 200 && center[1] < 40 && center[2] < 40, "");
        check("clear preserved outside (blue)", corner[0] < 40 && corner[1] < 40 && corner[2] > 200, "");
    }

    mmm_command_buffer_release(cb);
    if (vertexBuffer) mmm_buffer_release(vertexBuffer);
    mmm_texture_release(target);
    mmm_render_pipeline_release(pipeline);
    mmm_library_release(library);
    mmm_queue_release(queue);
    mmm_device_release(device);
}

int main(void) {
    printf("==================================================\n");
    printf("MetalMod native Metal smoke test\n");
    printf("==================================================\n");
    @autoreleasepool {
        test_device();
        test_clear_and_readback();
        test_resources();
        test_draw();
        test_surface();
    }
    printf("\n==================================================\n");
    if (g_failures == 0) printf("ALL CHECKS PASSED\n");
    else printf("%d CHECK(S) FAILED\n", g_failures);
    printf("==================================================\n");
    return g_failures == 0 ? 0 : 1;
}
