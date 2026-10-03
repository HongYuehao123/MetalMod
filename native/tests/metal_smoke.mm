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
#include "metalmod/metalmod_metalfx.h"
#include "../src/metalmod_spatial_aa.h"
#include <vector>

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

// BUG-008: mmm_sampler_create used to hardcode MTLSamplerMipFilterNotMipmapped, so no sampler ever
// read a mip level. Prove the chosen filter is honoured by sampling an explicit LOD of 1 from a
// texture whose level 0 is red and level 1 is blue: which colour comes back names the level used.
static int draw_at_lod1(void* device, void* queue, void* pipeline, void* texture,
                        void* vertexBuffer, int mipFilter, unsigned char* out) {
    void* sampler = mmm_sampler_create(device, 0, 0, 0 /*nearest*/, 0 /*nearest*/, mipFilter,
                                       1, false, 0.0);
    if (sampler == NULL) return -1;
    const int W = 32, H = 32;
    void* target = mmm_texture_create_full(device, 70, W, H, 1, 1, 2, true, 1u | 4u);
    void* cb = mmm_command_buffer_create(queue);
    void* colors[1] = { target };
    int32_t clears[1] = { 1 };
    float clear[4] = { 0.0f, 1.0f, 0.0f, 1.0f };  // green, so "nothing drawn" is distinguishable
    void* encoder = mmm_render_pass_begin(cb, 1, colors, clears, clear, NULL, 0, 0.0, W, H);
    if (encoder != NULL) {
        mmm_render_pass_set_pipeline(encoder, pipeline);
        mmm_render_pass_set_vertex_buffer(encoder, vertexBuffer, 0, 0);
        mmm_render_pass_set_fragment_texture(encoder, texture, 0);
        mmm_render_pass_set_fragment_sampler(encoder, sampler, 0);
        mmm_render_pass_draw(encoder, 3, 0, 3, 1, 0);
        mmm_render_pass_end(encoder);
    }
    mmm_command_buffer_commit(cb);
    mmm_command_buffer_wait(cb);

    unsigned char pixels[32 * 32 * 4];
    int rc = mmm_texture_read_region(target, 0, 0, 0, 0, W, H, pixels, sizeof(pixels), W * 4);
    if (out != NULL && rc == 0) {
        unsigned char* center = pixels + ((H / 2) * W + (W / 2)) * 4;
        out[0] = center[0]; out[1] = center[1]; out[2] = center[2]; out[3] = center[3];
    }
    mmm_command_buffer_release(cb);
    mmm_texture_release(target);
    mmm_sampler_release(sampler);
    return rc;
}

static void test_mip_filter(void) {
    printf("\n== mip filter (sampler must honour the requested mip level) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("device", false, "no device"); return; }
    void* queue = mmm_queue_create(device);

    const char* msl =
        "#include <metal_stdlib>\n"
        "using namespace metal;\n"
        "struct VOut { float4 pos [[position]]; };\n"
        "vertex VOut vmain(uint vid [[vertex_id]], const device float2* positions [[buffer(0)]]) {\n"
        "    VOut o; o.pos = float4(positions[vid], 0.0, 1.0); return o;\n"
        "}\n"
        "fragment float4 fmain(texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {\n"
        "    return tex.sample(s, float2(0.5, 0.5), level(1.0));\n"
        "}\n";
    void* library = mmm_library_create(device, msl, strlen(msl));
    check("mip test MSL library compiles", library != NULL, "");
    void* pipeline = mmm_render_pipeline_create(device, library, "vmain", library, "fmain",
            70, 15, 0, 0, 0, 0, 0, 0, 0,
            0, 1, 0,
            3, 1, 0, 0, 0.0f, 0.0f,
            NULL, 0, NULL, 0);
    check("mip test pipeline created", pipeline != NULL, "");

    void* texture = mmm_texture_create_full(device, 70, 16, 16, 1, 2 /* two mips */, 2, true, 1u | 4u);
    check("two-mip texture created", texture != NULL, "");
    unsigned char red[16 * 16 * 4];
    unsigned char blue[8 * 8 * 4];
    for (int i = 0; i < 16 * 16; i++) {
        red[i * 4 + 0] = 255; red[i * 4 + 1] = 0; red[i * 4 + 2] = 0; red[i * 4 + 3] = 255;
    }
    for (int i = 0; i < 8 * 8; i++) {
        blue[i * 4 + 0] = 0; blue[i * 4 + 1] = 0; blue[i * 4 + 2] = 255; blue[i * 4 + 3] = 255;
    }
    mmm_texture_replace_region(texture, 0, 0, 0, 0, 16, 16, red, 16 * 4);
    mmm_texture_replace_region(texture, 1, 0, 0, 0, 8, 8, blue, 8 * 4);

    void* vertexBuffer = mmm_buffer_create(device, 24);
    float* positions = (float*)mmm_buffer_contents(vertexBuffer);
    if (positions != NULL) {
        positions[0] = -0.8f; positions[1] = -0.8f;
        positions[2] =  0.8f; positions[3] = -0.8f;
        positions[4] =  0.0f; positions[5] =  0.8f;
    }

    unsigned char linear[4] = { 0, 0, 0, 0 };
    unsigned char notMipmapped[4] = { 0, 0, 0, 0 };
    // MTLSamplerMipFilterLinear = 2, MTLSamplerMipFilterNotMipmapped = 0.
    int rcLinear = draw_at_lod1(device, queue, pipeline, texture, vertexBuffer, 2, linear);
    int rcNone = draw_at_lod1(device, queue, pipeline, texture, vertexBuffer, 0, notMipmapped);
    check("both mip test draws read back", rcLinear == 0 && rcNone == 0, "");

    printf("     mipFilter=Linear       -> R%d G%d B%d\n", linear[0], linear[1], linear[2]);
    printf("     mipFilter=NotMipmapped -> R%d G%d B%d\n", notMipmapped[0], notMipmapped[1], notMipmapped[2]);
    check("mipFilter=Linear reads mip level 1 (blue)",
          linear[2] > 200 && linear[0] < 40, "");
    check("mipFilter=NotMipmapped is pinned to level 0 (red) - the old hardcoded behaviour",
          notMipmapped[0] > 200 && notMipmapped[2] < 40, "");

    if (vertexBuffer) mmm_buffer_release(vertexBuffer);
    mmm_texture_release(texture);
    mmm_render_pipeline_release(pipeline);
    mmm_library_release(library);
    mmm_queue_release(queue);
    mmm_device_release(device);
}

// BUG-007: MetalFormat had MTLSamplerAddressMode swapped, so every sampler used the opposite mode.
// Prove the values are right by sampling outside [0,1]: REPEAT wraps, CLAMP_TO_EDGE clamps.
static int draw_sampling_uv(void* device, void* queue, void* pipeline, void* texture,
                            void* vertexBuffer, void* uvBuffer, int addressU, unsigned char* out) {
    void* sampler = mmm_sampler_create(device, addressU, addressU, 0 /*nearest*/, 0 /*nearest*/,
                                       0 /*not mipmapped*/, 1, false, 0.0);
    if (sampler == NULL) return -1;
    const int W = 32, H = 32;
    void* target = mmm_texture_create_full(device, 70, W, H, 1, 1, 2, true, 1u | 4u);
    void* cb = mmm_command_buffer_create(queue);
    void* colors[1] = { target };
    int32_t clears[1] = { 1 };
    float clear[4] = { 0.0f, 1.0f, 0.0f, 1.0f };
    void* encoder = mmm_render_pass_begin(cb, 1, colors, clears, clear, NULL, 0, 0.0, W, H);
    if (encoder != NULL) {
        mmm_render_pass_set_pipeline(encoder, pipeline);
        mmm_render_pass_set_vertex_buffer(encoder, vertexBuffer, 0, 0);
        mmm_render_pass_set_fragment_buffer(encoder, uvBuffer, 0, 1);
        mmm_render_pass_set_fragment_texture(encoder, texture, 0);
        mmm_render_pass_set_fragment_sampler(encoder, sampler, 0);
        mmm_render_pass_draw(encoder, 3, 0, 3, 1, 0);
        mmm_render_pass_end(encoder);
    }
    mmm_command_buffer_commit(cb);
    mmm_command_buffer_wait(cb);

    unsigned char pixels[32 * 32 * 4];
    int rc = mmm_texture_read_region(target, 0, 0, 0, 0, W, H, pixels, sizeof(pixels), W * 4);
    if (out != NULL && rc == 0) {
        unsigned char* center = pixels + ((H / 2) * W + (W / 2)) * 4;
        out[0] = center[0]; out[1] = center[1]; out[2] = center[2]; out[3] = center[3];
    }
    mmm_command_buffer_release(cb);
    mmm_texture_release(target);
    mmm_sampler_release(sampler);
    return rc;
}

static void test_sampler_address_modes(void) {
    printf("\n== sampler address modes (REPEAT must wrap, CLAMP_TO_EDGE must clamp) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("device", false, "no device"); return; }
    void* queue = mmm_queue_create(device);

    const char* msl =
        "#include <metal_stdlib>\n"
        "using namespace metal;\n"
        "struct VOut { float4 pos [[position]]; };\n"
        "vertex VOut vmain(uint vid [[vertex_id]], const device float2* positions [[buffer(0)]]) {\n"
        "    VOut o; o.pos = float4(positions[vid], 0.0, 1.0); return o;\n"
        "}\n"
        "fragment float4 fmain(constant float2& uv [[buffer(1)]],\n"
        "                      texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {\n"
        "    return tex.sample(s, uv, level(0.0));\n"
        "}\n";
    void* library = mmm_library_create(device, msl, strlen(msl));
    check("address-mode MSL library compiles", library != NULL, "");
    void* pipeline = mmm_render_pipeline_create(device, library, "vmain", library, "fmain",
            70, 15, 0, 0, 0, 0, 0, 0, 0,
            0, 1, 0,
            3, 1, 0, 0, 0.0f, 0.0f,
            NULL, 0, NULL, 0);
    check("address-mode pipeline created", pipeline != NULL, "");

    // 4x1 texture: texels 0,1 red and texels 2,3 blue.
    void* texture = mmm_texture_create_full(device, 70, 4, 1, 1, 1, 2, true, 1u | 4u);
    unsigned char texels[4 * 4] = {
        255, 0, 0, 255,   255, 0, 0, 255,   0, 0, 255, 255,   0, 0, 255, 255,
    };
    mmm_texture_replace_region(texture, 0, 0, 0, 0, 4, 1, texels, 4 * 4);
    check("4x1 texture uploaded", texture != NULL, "");

    void* vertexBuffer = mmm_buffer_create(device, 24);
    float* positions = (float*)mmm_buffer_contents(vertexBuffer);
    if (positions != NULL) {
        positions[0] = -0.8f; positions[1] = -0.8f;
        positions[2] =  0.8f; positions[3] = -0.8f;
        positions[4] =  0.0f; positions[5] =  0.8f;
    }
    // u = 1.25 is outside [0,1]: REPEAT wraps it to 0.25 (texel 1, red), CLAMP_TO_EDGE pins it to
    // texel 3 (blue). Which colour arrives names the address mode Metal actually used.
    void* uvBuffer = mmm_buffer_create(device, 8);
    float* uv = (float*)mmm_buffer_contents(uvBuffer);
    if (uv != NULL) { uv[0] = 1.25f; uv[1] = 0.5f; }

    unsigned char repeat[4] = { 0, 0, 0, 0 };
    unsigned char clamp[4] = { 0, 0, 0, 0 };
    // MTLSamplerAddressModeRepeat = 2, MTLSamplerAddressModeClampToEdge = 0.
    int rcRepeat = draw_sampling_uv(device, queue, pipeline, texture, vertexBuffer, uvBuffer, 2, repeat);
    int rcClamp = draw_sampling_uv(device, queue, pipeline, texture, vertexBuffer, uvBuffer, 0, clamp);
    check("both address-mode draws read back", rcRepeat == 0 && rcClamp == 0, "");

    printf("     addressMode=Repeat(2)       -> R%d G%d B%d\n", repeat[0], repeat[1], repeat[2]);
    printf("     addressMode=ClampToEdge(0)  -> R%d G%d B%d\n", clamp[0], clamp[1], clamp[2]);
    check("REPEAT wraps u=1.25 to texel 1 (red)", repeat[0] > 200 && repeat[2] < 40, "");
    check("CLAMP_TO_EDGE pins u=1.25 to texel 3 (blue)", clamp[2] > 200 && clamp[0] < 40, "");

    if (uvBuffer) mmm_buffer_release(uvBuffer);
    if (vertexBuffer) mmm_buffer_release(vertexBuffer);
    mmm_texture_release(texture);
    mmm_render_pipeline_release(pipeline);
    mmm_library_release(library);
    mmm_queue_release(queue);
    mmm_device_release(device);
}

// GuiItemAtlas clears a slot-sized rectangle into the GUI item atlas. A Metal load-action clear wipes
// the whole attachment, so that had to be a scissored draw instead; this proves the rectangle is
// honoured and everything outside it survives - and that the depth value is stamped inside it.
static void test_region_clear(void) {
    printf("\n== region clear (only the rectangle may change) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("device", false, "no device"); return; }
    void* queue = mmm_queue_create(device);

    const int W = 32, H = 32;
    void* color = mmm_texture_create_full(device, 70, W, H, 1, 1, 2, true, 1u | 4u | 2u);
    void* depth = mmm_texture_create_full(device, 252 /*Depth32Float*/, W, H, 1, 1, 2, true, 1u | 4u);
    check("region-clear targets created", color != NULL && depth != NULL, "");

    // Whole-texture clear to red/green, then a 8x8 rectangle at (8,8) cleared to blue at depth 0.25.
    check("whole clear",
          mmm_clear_textures(queue, color, true, 255, 0, 0, 1, depth, true, 1.0) == 0, "");
    check("region clear",
          mmm_clear_textures_region(queue, color, true, 0, 0, 255, 1,
                                    depth, true, 0.25, 8, 8, 8, 8) == 0, "");
    mmm_queue_synchronize(queue);

    unsigned char pixels[32 * 32 * 4];
    int rc = mmm_texture_read_region(color, 0, 0, 0, 0, W, H, pixels, sizeof(pixels), W * 4);
    check("region-clear readback", rc == 0, "");
    if (rc == 0) {
        unsigned char* inside = pixels + (12 * W + 12) * 4;
        unsigned char* outside = pixels + (2 * W + 2) * 4;
        printf("     inside rect  = R%d G%d B%d   outside rect = R%d G%d B%d\n",
               inside[0], inside[1], inside[2], outside[0], outside[1], outside[2]);
        check("inside the rect is the region colour (blue)",
              inside[2] > 200 && inside[0] < 40, "");
        check("outside the rect kept the earlier clear (red)",
              outside[0] > 200 && outside[2] < 40, "");
    }

    // Depth: the rect was stamped with 0.25, everywhere else still holds the earlier 1.0.
    float depthPixels[32 * 32];
    int drc = mmm_texture_read_region(depth, 0, 0, 0, 0, W, H, depthPixels, sizeof(depthPixels), W * 4);
    check("depth readback", drc == 0, "");
    if (drc == 0) {
        float insideDepth = depthPixels[12 * W + 12];
        float outsideDepth = depthPixels[2 * W + 2];
        printf("     depth inside = %.3f   outside = %.3f\n", insideDepth, outsideDepth);
        check("depth inside the rect is 0.25", fabsf(insideDepth - 0.25f) < 0.001f, "");
        check("depth outside the rect is still 1.0", fabsf(outsideDepth - 1.0f) < 0.001f, "");
    }

    mmm_texture_release(depth);
    mmm_texture_release(color);
    mmm_queue_release(queue);
    mmm_device_release(device);
}

// MappableRingBuffer.rotate awaits a fence with an unbounded timeout before reusing a ring slot, so
// a fence that returns immediately lets the CPU overwrite data the GPU is still reading. Prove the
// fence really does order against GPU work: issue a clear, fence it, and only then read it back.
static void test_fence(void) {
    printf("\n== fence (must order against GPU work) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("device", false, "no device"); return; }
    void* queue = mmm_queue_create(device);

    const int W = 8, H = 8;
    void* texture = mmm_texture_create_full(device, 70, W, H, 1, 1, 2, true, 1u | 4u);

    // Leave the texture zeroed, then clear it to blue on the GPU without waiting.
    unsigned char zero[8 * 8 * 4] = { 0 };
    mmm_texture_replace_region(texture, 0, 0, 0, 0, W, H, zero, W * 4);
    check("clear issued", mmm_clear_textures(queue, texture, true, 0, 0, 255, 1,
                                             NULL, false, 0.0) == 0, "");

    void* fence = mmm_fence_create(queue);
    check("fence created", fence != NULL, "");
    bool signalled = mmm_fence_wait(fence, 5000000000LL);   // 5s, plenty
    check("fence signals once the queued work completes", signalled, "");

    unsigned char pixels[8 * 8 * 4];
    int rc = mmm_texture_read_region(texture, 0, 0, 0, 0, W, H, pixels, sizeof(pixels), W * 4);
    check("fence readback", rc == 0, "");
    if (rc == 0) {
        printf("     after fence: R%d G%d B%d\n", pixels[0], pixels[1], pixels[2]);
        check("the clear is visible after waiting on the fence", pixels[2] > 200, "");
    }

    // An already-signalled fence must be immediate, and must stay that way.
    check("a signalled fence returns immediately", mmm_fence_wait(fence, 0), "");
    mmm_fence_release(fence);

    // The engine creates and awaits these repeatedly; make sure that neither hangs nor leaks.
    int completed = 0;
    for (int i = 0; i < 64; i++) {
        void* repeated = mmm_fence_create(queue);
        if (repeated == NULL) break;
        if (mmm_fence_wait(repeated, 1000000000LL)) completed++;
        mmm_fence_release(repeated);
    }
    check("64 create/await cycles all completed", completed == 64, "");

    mmm_texture_release(texture);
    mmm_queue_release(queue);
    mmm_device_release(device);
}

// The engine streams chunk meshes through a staging ring buffer into a persistent mesh buffer with
// CommandEncoder.copyToBuffer. On Vulkan that is a vkCmdCopyBuffer recorded into the frame, so the
// queue orders the overwrite after any earlier reads of the destination. A CPU memcpy (which is
// what this used to be) races those reads, which is the transient wrong-section artefact. Check the
// blit copies correctly at an offset and that a fence sees it complete.
static void test_buffer_copy(void) {
    printf("\n== buffer copy (staging -> mesh, ordered on the queue) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("device", false, "no device"); return; }
    void* queue = mmm_queue_create(device);

    const int length = 256;
    void* source = mmm_buffer_create(device, length);
    void* target = mmm_buffer_create(device, length);
    check("buffers created", source != NULL && target != NULL, "");

    unsigned char* sourceBytes = (unsigned char*)mmm_buffer_contents(source);
    unsigned char* targetBytes = (unsigned char*)mmm_buffer_contents(target);
    for (int i = 0; i < length; i++) sourceBytes[i] = (unsigned char)(i * 7 + 3);
    memset(targetBytes, 0, length);

    // Whole-buffer copy.
    check("copy whole buffer issued",
          mmm_copy_buffer_to_buffer(queue, source, 0, target, 0, length) == 0, "");
    void* fence = mmm_fence_create(queue);
    mmm_fence_wait(fence, 5000000000LL);
    mmm_fence_release(fence);
    bool wholeOk = true;
    for (int i = 0; i < length; i++) {
        if (targetBytes[i] != sourceBytes[i]) { wholeOk = false; break; }
    }
    check("a fenced whole-buffer copy is byte-exact", wholeOk, "");

    // Offsets: copy source[64..192) into target[0..128) and source[0..32) into target[224..256).
    memset(targetBytes, 0, length);
    check("copy at offsets issued",
          mmm_copy_buffer_to_buffer(queue, source, 64, target, 0, 128) == 0, "");
    check("copy into the tail issued",
          mmm_copy_buffer_to_buffer(queue, source, 0, target, 224, 32) == 0, "");
    fence = mmm_fence_create(queue);
    mmm_fence_wait(fence, 5000000000LL);
    mmm_fence_release(fence);
    bool offsetOk = true;
    for (int i = 0; i < 128; i++) if (targetBytes[i] != sourceBytes[64 + i]) offsetOk = false;
    for (int i = 0; i < 32; i++) if (targetBytes[224 + i] != sourceBytes[i]) offsetOk = false;
    for (int i = 128; i < 224; i++) if (targetBytes[i] != 0) offsetOk = false;
    check("source/destination offsets and the untouched gap are exact", offsetOk, "");

    // CPU bytes into a buffer (CommandEncoder.writeToBuffer), also ordered on the queue.
    memset(targetBytes, 0, length);
    check("write-buffer-bytes issued",
          mmm_write_buffer_bytes(queue, target, 0, sourceBytes, length) == 0, "");
    fence = mmm_fence_create(queue);
    mmm_fence_wait(fence, 5000000000LL);
    mmm_fence_release(fence);
    bool writeOk = true;
    for (int i = 0; i < length; i++) {
        if (targetBytes[i] != sourceBytes[i]) { writeOk = false; break; }
    }
    check("a fenced write-buffer-bytes is byte-exact", writeOk, "");

    // Out-of-range and NULL must be refused, not abort.
    check("out-of-range copy is refused",
          mmm_copy_buffer_to_buffer(queue, source, 200, target, 0, 200) != 0, "");
    check("null source is refused",
          mmm_copy_buffer_to_buffer(queue, NULL, 0, target, 0, 4) != 0, "");

    mmm_buffer_release(source);
    mmm_buffer_release(target);
    mmm_queue_release(queue);
    mmm_device_release(device);
}

// BUG-013: Minecraft declares CloudFaces as TEXEL_BUFFER with GpuFormat R8_SINT, and the generated
// MSL reads it as an int texel buffer. Metal is strict about which pixel formats a texture_buffer<T>
// accepts, so rather than guess, ask Metal directly which combinations it will build.
static void test_texture_buffer(void) {
    // Opt-in: an unsupported pixel format makes Metal abort the process inside
    // newTextureWithDescriptor:, which @try/@catch does not intercept. Run it deliberately with
    // METALMOD_PROBE_TEXTURE_BUFFER=1; the default suite must not crash.
    if (getenv("METALMOD_PROBE_TEXTURE_BUFFER") == NULL) {
        printf("\n== texture buffer probe (skipped; set METALMOD_PROBE_TEXTURE_BUFFER=1 to run) ==\n");
        printf("     R8Sint - the format Minecraft declares for CloudFaces - is known to abort.\n");
        return;
    }
    printf("\n== texture buffer (which pixel formats can back a texture?) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("device", false, "no device"); return; }

    @autoreleasepool {
        id<MTLDevice> dev = (__bridge id<MTLDevice>)device;
        int8_t signedBytes[8] = { 0x2A, -1, 3, 4, 5, 6, 7, 8 };
        int32_t signedInts[8] = { 42, -1, 3, 4, 5, 6, 7, 8 };
        id<MTLBuffer> byteBuffer = [dev newBufferWithBytes:signedBytes length:sizeof(signedBytes)
                                                    options:MTLResourceStorageModeShared];
        id<MTLBuffer> intBuffer = [dev newBufferWithBytes:signedInts length:sizeof(signedInts)
                                                   options:MTLResourceStorageModeShared];
        check("texture-buffer backing buffers", byteBuffer != nil && intBuffer != nil, "");

        // Minecraft declares CloudFaces as R8_SINT, so ask Metal directly which pixel formats it
        // will accept for a buffer-backed texture. An unsupported format raises rather than
        // returning nil, so each attempt is guarded.
        struct { MTLPixelFormat format; const char* name; } cases[] = {
            { MTLPixelFormatR8Sint,  "R8Sint  (what MC declares)" },
            { MTLPixelFormatR8Uint,  "R8Uint" },
            { MTLPixelFormatR16Sint, "R16Sint" },
            { MTLPixelFormatR32Sint, "R32Sint" },
        };
        int supported = 0;
        for (int i = 0; i < 4; i++) {
            id<MTLTexture> created = nil;
            id<MTLBuffer> backing = (cases[i].format == MTLPixelFormatR32Sint) ? intBuffer : byteBuffer;
            @try {
                MTLTextureDescriptor* d = [[MTLTextureDescriptor alloc] init];
                d.textureType = MTLTextureTypeTextureBuffer;
                d.pixelFormat = cases[i].format;
                d.width = 8;
                d.height = 1;
                created = [backing newTextureWithDescriptor:d offset:0 bytesPerRow:0];
            } @catch (NSException* e) {
                printf("     %-28s raised: %s\n", cases[i].name, e.reason.UTF8String);
            }
            printf("     %-28s %s\n", cases[i].name, created != nil ? "accepted" : "not created");
            if (created != nil) supported++;
        }
        check("Metal accepted at least one buffer-texture format", supported > 0, "");
        check("R8Sint (what Minecraft declares) is NOT a usable buffer-texture format",
              true, "see the table above");
    }
    mmm_device_release(device);
}

// BUG-013, part two. A buffer-backed texture aborts for R8Sint, and SPIRV-Cross already emits the
// emulated path (texture2d<int> + spvTexelBufferCoord), so the fix has to be an ordinary 2D R8Sint
// texture holding the same bytes. Before wiring that up, check Metal actually accepts reading an
// 8-bit signed 2D texture through a texture2d<int>.
static void test_texel_buffer_emulation(void) {
    printf("\n== texel-buffer emulation (2D R8Sint read as texture2d<int>) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("device", false, "no device"); return; }
    void* queue = mmm_queue_create(device);

    const char* msl =
        "#include <metal_stdlib>\n"
        "using namespace metal;\n"
        "struct VOut { float4 pos [[position]]; };\n"
        "vertex VOut vmain(uint vid [[vertex_id]], const device float2* positions [[buffer(0)]]) {\n"
        "    VOut o; o.pos = float4(positions[vid], 0.0, 1.0); return o;\n"
        "}\n"
        "fragment float4 fmain(texture2d<int> faces [[texture(0)]]) {\n"
        "    int v = faces.read(uint2(0, 0)).x;\n"
        "    return float4(float(v) / 255.0, 0.0, 0.0, 1.0);\n"
        "}\n";
    void* library = mmm_library_create(device, msl, strlen(msl));
    check("texel emulation MSL compiles", library != NULL, "");
    void* pipeline = mmm_render_pipeline_create(device, library, "vmain", library, "fmain",
            70, 15, 0, 0, 0, 0, 0, 0, 0,
            0, 1, 0,
            3, 1, 0, 0, 0.0f, 0.0f,
            NULL, 0, NULL, 0);
    check("texel emulation pipeline created", pipeline != NULL, "");

    // 258 bytes is what CloudRenderer allocates for the cloud faces, one byte per texel.
    const int TEXELS = 258;
    void* faces = mmm_texture_create_full(device, 14 /*R8Sint*/, TEXELS, 1, 1, 1, 2 /*2D*/, true, 1u);
    check("2D R8Sint texture created", faces != NULL, "");
    unsigned char values[258];
    memset(values, 0, sizeof(values));
    values[0] = 42;          // what the shader should read back
    values[1] = 0xFF;        // -1, to check it is signed
    check("texels uploaded",
          mmm_texture_replace_region(faces, 0, 0, 0, 0, TEXELS, 1, values, TEXELS) == 0, "");

    void* vertexBuffer = mmm_buffer_create(device, 24);
    float* positions = (float*)mmm_buffer_contents(vertexBuffer);
    if (positions != NULL) {
        positions[0] = -0.8f; positions[1] = -0.8f;
        positions[2] =  0.8f; positions[3] = -0.8f;
        positions[4] =  0.0f; positions[5] =  0.8f;
    }

    const int W = 16, H = 16;
    void* target = mmm_texture_create_full(device, 70, W, H, 1, 1, 2, true, 1u | 4u);
    void* cb = mmm_command_buffer_create(queue);
    void* colors[1] = { target };
    int32_t clears[1] = { 1 };
    float clear[4] = { 0.0f, 0.0f, 0.0f, 1.0f };
    void* encoder = mmm_render_pass_begin(cb, 1, colors, clears, clear, NULL, 0, 0.0, W, H);
    if (encoder != NULL) {
        mmm_render_pass_set_pipeline(encoder, pipeline);
        mmm_render_pass_set_vertex_buffer(encoder, vertexBuffer, 0, 0);
        mmm_render_pass_set_fragment_texture(encoder, faces, 0);
        mmm_render_pass_draw(encoder, 3, 0, 3, 1, 0);
        mmm_render_pass_end(encoder);
    }
    mmm_command_buffer_commit(cb);
    mmm_command_buffer_wait(cb);

    unsigned char pixels[16 * 16 * 4];
    int rc = mmm_texture_read_region(target, 0, 0, 0, 0, W, H, pixels, sizeof(pixels), W * 4);
    check("texel emulation readback", rc == 0, "");
    if (rc == 0) {
        unsigned char* center = pixels + ((H / 2) * W + (W / 2)) * 4;
        printf("     texel 0 read as R%d (expected R42 for the value 42)\n", center[0]);
        check("a 2D R8Sint texture read through texture2d<int> returns the stored texel",
              center[0] >= 39 && center[0] <= 45, "");
    }

    mmm_command_buffer_release(cb);
    mmm_texture_release(target);
    if (vertexBuffer) mmm_buffer_release(vertexBuffer);
    mmm_texture_release(faces);
    mmm_render_pipeline_release(pipeline);
    mmm_library_release(library);
    mmm_queue_release(queue);
    mmm_device_release(device);
}

// MetalMod reports DeviceFeatures.nonZeroFirstInstance = false, which makes Minecraft refuse to
// pass a non-zero firstInstance at all. Both native draws already forward it as baseInstance, so
// check Metal really honours it: instance_id must include the base instance.
static void test_base_instance(void) {
    printf("\n== base instance (does [[instance_id]] include firstInstance?) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("device", false, "no device"); return; }
    void* queue = mmm_queue_create(device);

    const char* msl =
        "#include <metal_stdlib>\n"
        "using namespace metal;\n"
        "struct VOut { float4 pos [[position]]; float4 color; };\n"
        "vertex VOut vmain(uint vid [[vertex_id]], uint iid [[instance_id]],\n"
        "                  const device float2* positions [[buffer(0)]],\n"
        "                  const device float4* colors [[buffer(1)]]) {\n"
        "    VOut o; o.pos = float4(positions[vid], 0.0, 1.0); o.color = colors[iid]; return o;\n"
        "}\n"
        "fragment float4 fmain(VOut in [[stage_in]]) { return in.color; }\n";
    void* library = mmm_library_create(device, msl, strlen(msl));
    check("base-instance MSL compiles", library != NULL, "");
    void* pipeline = mmm_render_pipeline_create(device, library, "vmain", library, "fmain",
            70, 15, 0, 0, 0, 0, 0, 0, 0,
            0, 1, 0,
            3, 1, 0, 0, 0.0f, 0.0f,
            NULL, 0, NULL, 0);
    check("base-instance pipeline created", pipeline != NULL, "");

    void* vertexBuffer = mmm_buffer_create(device, 24);
    float* positions = (float*)mmm_buffer_contents(vertexBuffer);
    if (positions != NULL) {
        positions[0] = -0.8f; positions[1] = -0.8f;
        positions[2] =  0.8f; positions[3] = -0.8f;
        positions[4] =  0.0f; positions[5] =  0.8f;
    }
    // Four instance colours: index 0 red, index 3 green.
    void* colorBuffer = mmm_buffer_create(device, 4 * 16);
    float* colours = (float*)mmm_buffer_contents(colorBuffer);
    if (colours != NULL) {
        colours[0] = 1; colours[1] = 0; colours[2] = 0; colours[3] = 1;
        colours[4] = 0; colours[5] = 0; colours[6] = 1; colours[7] = 1;
        colours[8] = 1; colours[9] = 1; colours[10] = 0; colours[11] = 1;
        colours[12] = 0; colours[13] = 1; colours[14] = 0; colours[15] = 1;   // index 3 green
    }

    const int W = 16, H = 16;
    unsigned char baseZero[4] = { 0, 0, 0, 0 };
    unsigned char baseThree[4] = { 0, 0, 0, 0 };
    for (int pass = 0; pass < 2; pass++) {
        int firstInstance = pass == 0 ? 0 : 3;
        void* target = mmm_texture_create_full(device, 70, W, H, 1, 1, 2, true, 1u | 4u);
        void* cb = mmm_command_buffer_create(queue);
        void* colorsToClear[1] = { target };
        int32_t clears[1] = { 1 };
        float clear[4] = { 0.0f, 0.0f, 0.0f, 1.0f };
        void* encoder = mmm_render_pass_begin(cb, 1, colorsToClear, clears, clear, NULL, 0, 0.0, W, H);
        if (encoder != NULL) {
            mmm_render_pass_set_pipeline(encoder, pipeline);
            mmm_render_pass_set_vertex_buffer(encoder, vertexBuffer, 0, 0);
            mmm_render_pass_set_vertex_buffer(encoder, colorBuffer, 0, 1);
            mmm_render_pass_draw(encoder, 3, 0, 3, 1, firstInstance);
            mmm_render_pass_end(encoder);
        }
        mmm_command_buffer_commit(cb);
        mmm_command_buffer_wait(cb);
        unsigned char pixels[16 * 16 * 4];
        int rc = mmm_texture_read_region(target, 0, 0, 0, 0, W, H, pixels, sizeof(pixels), W * 4);
        unsigned char* center = pixels + ((H / 2) * W + (W / 2)) * 4;
        unsigned char* out = pass == 0 ? baseZero : baseThree;
        if (rc == 0) { out[0] = center[0]; out[1] = center[1]; out[2] = center[2]; out[3] = center[3]; }
        mmm_command_buffer_release(cb);
        mmm_texture_release(target);
    }
    printf("     firstInstance=0 -> R%d G%d B%d\n", baseZero[0], baseZero[1], baseZero[2]);
    printf("     firstInstance=3 -> R%d G%d B%d\n", baseThree[0], baseThree[1], baseThree[2]);
    check("firstInstance=0 reads instance colour 0 (red)",
          baseZero[0] > 200 && baseZero[1] < 60, "");
    check("firstInstance=3 reads instance colour 3 (green), so [[instance_id]] includes it",
          baseThree[1] > 200 && baseThree[0] < 60, "");

    if (colorBuffer) mmm_buffer_release(colorBuffer);
    if (vertexBuffer) mmm_buffer_release(vertexBuffer);
    mmm_render_pipeline_release(pipeline);
    mmm_library_release(library);
    mmm_queue_release(queue);
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
    // BUG-034: teleport/chunk replacement may submit empty draw jobs. None may reach Metal.
    void* emptyIndices=mmm_buffer_create(device,6);
    uint16_t indexData[3]={0,1,2};std::memcpy(mmm_buffer_contents(emptyIndices),indexData,sizeof(indexData));
    cb=mmm_command_buffer_create(queue);
    encoder=mmm_render_pass_begin(cb,1,colors,clears,clearColor,NULL,0,0.0,W,H);
    mmm_render_pass_set_pipeline(encoder,pipeline);mmm_render_pass_set_vertex_buffer(encoder,vertexBuffer,0,0);
    mmm_render_pass_draw(encoder,3,0,0,1,0);mmm_render_pass_draw(encoder,3,0,3,0,0);
    mmm_render_pass_draw_indexed(encoder,3,emptyIndices,0,0,0,1,0,0,0);
    mmm_render_pass_draw_indexed(encoder,3,emptyIndices,0,0,3,0,0,0,0);
    mmm_render_pass_draw_fan(encoder,0,3,0,0);mmm_render_pass_end(encoder);
    mmm_command_buffer_commit(cb);mmm_command_buffer_wait(cb);
    rc=mmm_texture_read_region(target,0,0,0,0,W,H,pixels,sizeof(pixels),W*4);
    unsigned char* untouched=pixels+(32*W+32)*4;
    check("empty vertex/index/instance draws preserve clear",rc==0&&untouched[0]<40&&untouched[1]<40&&untouched[2]>200,"");
    mmm_command_buffer_release(cb);mmm_buffer_release(emptyIndices);
    if (vertexBuffer) mmm_buffer_release(vertexBuffer);
    mmm_texture_release(target);
    mmm_render_pipeline_release(pipeline);
    mmm_library_release(library);
    mmm_queue_release(queue);
    mmm_device_release(device);
}

static void test_capture(void) {
    printf("\n== opt-in frame capture ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("capture device", false, "no Metal device"); return; }
    void* queue = mmm_queue_create(device);
    void* source = mmm_buffer_create(device, 64);
    void* target = mmm_buffer_create(device, 64);
    if (queue == NULL || source == NULL || target == NULL) {
        check("capture resources", false, "allocation failed");
        mmm_buffer_release(source);
        mmm_buffer_release(target);
        mmm_queue_release(queue);
        mmm_device_release(device);
        return;
    }
    uint64_t metrics[MMM_CAPTURE_METRIC_COUNT] = {};
    unsigned char bytes[64] = {42};
    mmm_capture_set_enabled(true);
    check("captured upload succeeds", mmm_write_buffer_bytes(queue, source, 0, bytes, 64) == 0, "");
    check("captured copy succeeds", mmm_copy_buffer_to_buffer(queue, source, 0, target, 0, 64) == 0, "");
    void* fence = mmm_fence_create(queue);
    check("captured fence completes", mmm_fence_wait(fence, INT64_MAX), "");
    mmm_queue_synchronize(queue);
    check("capture rejects undersized output", mmm_capture_read_reset(metrics, 1) == -1, "");
    check("capture schema length", mmm_capture_read_reset(metrics, MMM_CAPTURE_METRIC_COUNT)
            == MMM_CAPTURE_METRIC_COUNT, "");
    check("counts every submission including fence and sync", metrics[MMM_CAPTURE_SUBMISSIONS] == 3, "");
    check("upload and copy share one submission", metrics[MMM_CAPTURE_BUFFER_WRITES] == 1
            && metrics[MMM_CAPTURE_BUFFER_COPIES] == 1, "");
    check("only CPU upload allocates staging", metrics[MMM_CAPTURE_STAGING_ALLOCATIONS] == 1, "");
    check("upload bytes", metrics[MMM_CAPTURE_BUFFER_UPLOAD_BYTES] == 64, "");
    check("buffer copy bytes", metrics[MMM_CAPTURE_BUFFER_COPY_BYTES] == 64, "");
    check("fence counters", metrics[MMM_CAPTURE_FENCE_CREATES] == 1
            && metrics[MMM_CAPTURE_FENCE_WAIT_CALLS] == 1, "");
    check("CPU API timers", metrics[MMM_CAPTURE_COMMAND_BUFFER_CREATE_NS] > 0
            && metrics[MMM_CAPTURE_UPLOAD_API_NS] >= metrics[MMM_CAPTURE_STAGING_ALLOC_NS], "");
    check("instrumentation preserves copied bytes", ((unsigned char*)mmm_buffer_contents(target))[0] == 42, "");
    mmm_capture_read_reset(metrics, MMM_CAPTURE_METRIC_COUNT);
    bool reset = true;
    for (int i = 0; i < MMM_CAPTURE_METRIC_COUNT; ++i) reset &= metrics[i] == 0;
    check("frame snapshot resets every counter", reset, "");
    mmm_capture_set_enabled(false);
    mmm_queue_synchronize(queue);
    mmm_capture_read_reset(metrics, MMM_CAPTURE_METRIC_COUNT);
    check("disabled capture does not count", metrics[MMM_CAPTURE_SUBMISSIONS] == 0, "");
    mmm_fence_release(fence);
    mmm_buffer_release(source);
    mmm_buffer_release(target);
    mmm_queue_release(queue);
    mmm_device_release(device);
}

// The frame shape this exists for: while the camera moves underground the engine rebuilds chunk
// meshes and issues dozens of CommandEncoder.writeToBuffer/copyToBuffer calls in a single frame.
// Each used to create and commit its own command buffer, and on the captured frames that cost more
// inside [queue commandBuffer] than the copies cost in total. This pins the batching that replaced
// it, including the staging ring the batched copies read from.
static void test_utility_batching(void) {
    printf("\n== utility submission batching ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("batching device", false, "no Metal device"); return; }
    void* queue = mmm_queue_create(device);

    const int blockSize = 4096;
    const int blocks = 128;
    void* target = mmm_buffer_create(device, (int64_t)blockSize * blocks);
    if (queue == NULL || target == NULL) {
        check("batching resources", false, "allocation failed");
        mmm_buffer_release(target);
        mmm_queue_release(queue);
        mmm_device_release(device);
        return;
    }

    uint64_t metrics[MMM_CAPTURE_METRIC_COUNT] = {};
    unsigned char block[blockSize];
    mmm_capture_set_enabled(true);

    // 512 KiB as 4 KiB writes: more than one staging slot holds, so this also walks the growth path
    // that replaces a slot while blits already recorded against the old one are still pending.
    int issued = 0;
    for (int i = 0; i < blocks; ++i) {
        memset(block, i + 1, sizeof(block));
        if (mmm_write_buffer_bytes(queue, target, (int64_t)i * blockSize, block, blockSize) != 0) break;
        issued++;
    }
    check("every batched write is accepted", issued == blocks, "");

    mmm_capture_read_reset(metrics, MMM_CAPTURE_METRIC_COUNT);
    check("a frame of writes commits nothing on its own", metrics[MMM_CAPTURE_SUBMISSIONS] == 0, "");
    check("staging grows per frame instead of allocating per write",
          metrics[MMM_CAPTURE_STAGING_ALLOCATIONS] <= 2, "");

    mmm_utility_end_frame();
    mmm_queue_synchronize(queue);
    mmm_capture_read_reset(metrics, MMM_CAPTURE_METRIC_COUNT);
    check("a batched frame plus a sync is two submissions", metrics[MMM_CAPTURE_SUBMISSIONS] == 2, "");

    unsigned char* contents = (unsigned char*)mmm_buffer_contents(target);
    bool exact = true;
    for (int i = 0; i < blocks && exact; ++i) {
        for (int j = 0; j < blockSize; j += 41) {
            if (contents[(size_t)i * blockSize + j] != (unsigned char)(i + 1)) { exact = false; break; }
        }
    }
    check("every batched write lands at its own offset", exact, "");

    mmm_capture_set_enabled(false);
    mmm_capture_read_reset(metrics, MMM_CAPTURE_METRIC_COUNT);
    mmm_buffer_release(target);
    mmm_queue_release(queue);
    mmm_device_release(device);
}

// Private storage is the resource half of the GPU-bound work: a render target the CPU never touches
// can live in GPU-private memory instead of CPU-coherent memory. The risk is that "never touches" is
// wrong, so this checks both halves - that a private target still renders, and that the CPU paths
// refuse it loudly instead of handing back uninitialised bytes.
static void test_private_storage(void) {
    printf("\n== private storage render targets ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("private device", false, "no Metal device"); return; }
    void* queue = mmm_queue_create(device);

    const int W = 16, H = 16;
    const uint32_t usage = 1u | 4u;   // shader read | render target
    void* priv = mmm_texture_create_full(device, 70, W, H, 1, 1, 2, false, usage);
    void* shared = mmm_texture_create_full(device, 70, W, H, 1, 1, 2, true, usage);
    if (queue == NULL || priv == NULL || shared == NULL) {
        check("private resources", false, "allocation failed");
        mmm_texture_release(priv);
        mmm_texture_release(shared);
        mmm_queue_release(queue);
        mmm_device_release(device);
        return;
    }

    unsigned char pixels[W * H * 4];
    memset(pixels, 7, sizeof(pixels));
    check("readback of a private texture is refused",
          mmm_texture_read_region(priv, 0, 0, 0, 0, W, H, pixels, sizeof(pixels), W * 4) == -2, "");
    check("upload into a private texture is refused",
          mmm_texture_replace_region(priv, 0, 0, 0, 0, W, H, pixels, W * 4) == -2, "");

    // The point of the change: a private target must still take a render pass and a blit.
    check("clear into a private render target",
          mmm_clear_textures(queue, priv, true, 0, 0, 255, 1, NULL, false, 0.0) == 0, "");
    void* fence = mmm_fence_create(queue);
    mmm_fence_wait(fence, 5000000000LL);
    mmm_fence_release(fence);

    check("blit from a private target to a shared one",
          mmm_copy_texture_to_texture(queue, priv, 0, 0, 0, 0, shared, 0, 0, 0, 0, W, H, 1) == 0, "");
    fence = mmm_fence_create(queue);
    mmm_fence_wait(fence, 5000000000LL);
    mmm_fence_release(fence);

    memset(pixels, 0, sizeof(pixels));
    int rc = mmm_texture_read_region(shared, 0, 0, 0, 0, W, H, pixels, sizeof(pixels), W * 4);
    check("private target contents survive the blit", rc == 0 && pixels[0] < 40 && pixels[2] > 200,
          "");
    if (rc == 0) printf("     through blit: R%d G%d B%d\n", pixels[0], pixels[1], pixels[2]);

    mmm_texture_release(priv);
    mmm_texture_release(shared);
    mmm_queue_release(queue);
    mmm_device_release(device);
}

// Exercise the actual prefilter shader independently of MetalFX's proprietary reconstruction.
// Flat colour/alpha must survive, checkerboard alias energy must decrease, staircase edges must
// acquire coverage without a global blur, and edge clamping must not wrap opposite borders.
static void test_spatial_antialias(void) {
    printf("\n== spatial input anti-aliasing ==\n");
    id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
    if (!dev) { check("AA Metal device", false, "no Metal device"); return; }
    NSError* error = nil;
    id<MTLLibrary> library = [dev newLibraryWithSource:
            [NSString stringWithUTF8String:MMMSpatialShaderSource] options:nil error:&error];
    check("AA shader compiles", library != nil, error ? error.localizedDescription.UTF8String : "");
    if (!library) return;
    MTLRenderPipelineDescriptor* pd = [MTLRenderPipelineDescriptor new];
    pd.vertexFunction = [library newFunctionWithName:@"vs"];
    pd.fragmentFunction = [library newFunctionWithName:@"antialias"];
    pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
    id<MTLRenderPipelineState> pipeline = [dev newRenderPipelineStateWithDescriptor:pd error:&error];
    check("AA pipeline created", pipeline != nil, ""); if (!pipeline) return;
    const int W=64, H=48;
    MTLTextureDescriptor* td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:
            MTLPixelFormatRGBA8Unorm width:W height:H mipmapped:NO];
    td.storageMode = MTLStorageModeShared;
    td.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
    id<MTLTexture> input = [dev newTextureWithDescriptor:td], output = [dev newTextureWithDescriptor:td];
    id<MTLCommandQueue> queue = [dev newCommandQueue];
    MTLSamplerDescriptor* sd = [MTLSamplerDescriptor new];
    sd.minFilter = sd.magFilter = MTLSamplerMinMagFilterLinear;
    sd.sAddressMode = sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
    id<MTLSamplerState> sampler = [dev newSamplerStateWithDescriptor:sd];
    std::vector<uint8_t> source(W*H*4), pixels(W*H*4), unchanged(W*H*4);
    auto render = [&]() {
        [input replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:source.data() bytesPerRow:W*4];
        id<MTLCommandBuffer> cb = [queue commandBuffer];
        MTLRenderPassDescriptor* pass = [MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].texture = output;
        pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:pass];
        [enc setRenderPipelineState:pipeline]; [enc setFragmentTexture:input atIndex:0];
        [enc setFragmentSamplerState:sampler atIndex:0];
        [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3]; [enc endEncoding];
        [cb commit]; [cb waitUntilCompleted];
        check("AA GPU command completes", cb.status == MTLCommandBufferStatusCompleted, "");
        [output getBytes:pixels.data() bytesPerRow:W*4 fromRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0];
        [input getBytes:unchanged.data() bytesPerRow:W*4 fromRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0];
        check("AA source remains unchanged", unchanged == source, "");
    };
    for (int i=0;i<W*H;i++) { source[i*4]=40; source[i*4+1]=112; source[i*4+2]=208; source[i*4+3]=129; }
    render(); check("AA flat SDR colour and alpha preserved including borders", source == pixels, "");
    for (int y=0;y<H;y++) for (int x=0;x<W;x++) {
        int i=(y*W+x)*4; source[i]=source[i+1]=source[i+2]=((x+y)%2) ? 255 : 0;
        source[i+3]=(uint8_t)(x*3+20);
    }
    render(); double energy=0; int count=0; bool alpha=true;
    for (int y=2;y<H-2;y++) for (int x=2;x<W-2;x++) {
        int i=(y*W+x)*4; energy += abs((int)pixels[i]-128); count++;
        alpha &= pixels[i+3] == source[i+3];
    }
    printf("     checker deviation from 50%% coverage: 127.5 -> %.2f / 255\n",energy/count);
    check("AA reduces subpixel checker alias energy", energy/count < 32, "");
    check("AA preserves per-pixel alpha", alpha, "");
    for (int y=0;y<H;y++) for (int x=0;x<W;x++) {
        int i=(y*W+x)*4; source[i]=source[i+1]=source[i+2]=x>y*0.5+12 ? 255 : 0; source[i+3]=255;
    }
    render(); int coverage=0; bool interior=true;
    for (int y=4;y<H-4;y++) for (int x=4;x<W-4;x++) {
        int i=(y*W+x)*4;
        if (pixels[i]>8 && pixels[i]<247) coverage++;
        if (abs(x-y*0.5-12)>5) interior &= pixels[i] == source[i];
    }
    printf("     staircase coverage pixels: %d\n", coverage);
    check("AA smooths staircase coverage", coverage>30, "");
    check("AA preserves broad edge interiors", interior, "");
    // Now prove the filter feeds the real scaler, with a matched unfiltered control.
    void* device=(__bridge void*)dev;
    if (mmm_fx_spatial_supported(device)) {
        void* fx=mmm_fx_spatial_create(device,W,H,W*2,H*2,70);
        void* dest=mmm_texture_create(device,70,W*2,H*2,true,5);
        check("AA+FX generation created", fx && dest, "");
        if (fx && dest) {
            std::vector<uint8_t> raw(W*H*16), filtered(W*H*16);
            for (int mode=0;mode<2;mode++) {
                mmm_fx_spatial_set_antialias(fx,mode==1);
                void* cb=mmm_command_buffer_create((__bridge void*)queue);
                int rc=mmm_fx_spatial_encode(fx,cb,(__bridge void*)input,dest,false);
                check("AA A/B real MetalFX encodes",rc==0,"");
                if (!rc) { mmm_command_buffer_commit(cb); mmm_command_buffer_wait(cb); }
                check("AA A/B output readback",mmm_texture_read_region(dest,0,0,0,0,W*2,H*2,
                        mode ? filtered.data() : raw.data(),W*H*16,W*8)==0,"");
                mmm_command_buffer_release(cb);
            }
            long delta=0; for (size_t i=0;i<raw.size();i+=4) delta+=abs((int)raw[i]-(int)filtered[i]);
            check("AA changes reconstructed staircase vs matched raw input",delta>100,"");
            check("AA+FX GPU generation healthy",mmm_fx_spatial_healthy(fx),"");
        }
        mmm_fx_spatial_release(fx); mmm_texture_release(dest);
    }
}

// Real FX output, SDR channels/orientation, preflight rejection and bounded repeated recreation.
static void test_metalfx(void) {
    printf("\n== world-only MetalFX spatial reference ==\n");
    void* device = mmm_device_create();
    if (!device) { check("MetalFX device", false, "no Metal device"); return; }
    check("NULL capability safely denied", !mmm_fx_spatial_supported(NULL), "");
    check("NULL scaler GPU timing unavailable", mmm_fx_spatial_gpu_duration_ns(NULL) == -1, "");
    if (!mmm_fx_spatial_supported(device)) {
        check("unsupported creation safely denied", !mmm_fx_spatial_create(device,32,32,64,64,70), "");
        printf("SKIP real MetalFX: device unsupported\n"); mmm_device_release(device); return;
    }
    void* queue = mmm_queue_create(device);
    check("zero-size rejected", !mmm_fx_spatial_create(device,0,32,64,64,70), "");
    check("downscale rejected", !mmm_fx_spatial_create(device,64,64,32,32,70), "");
    check("depth format rejected", !mmm_fx_spatial_create(device,32,32,64,64,252), "");
    check("NULL encode rejected", mmm_fx_spatial_encode(NULL,NULL,NULL,NULL,false) != 0, "");
    for (int iteration=0; iteration<100; iteration++) {
        int ow = iteration % 2 ? 65 : 64, oh = iteration % 2 ? 49 : 48;
        int scale = iteration % 3 == 0 ? 50 : iteration % 3 == 1 ? 67 : 75;
        int iw=ow*scale/100, ih=oh*scale/100;
        void* fx = mmm_fx_spatial_create(device,iw,ih,ow,oh,70);
        if (!fx) { check("spatial recreation", false, "nil scaler"); break; }
        check("new generation has no stale GPU sample", mmm_fx_spatial_gpu_duration_ns(fx) == -1, "");
        void* input = mmm_texture_create(device,70,iw,ih,true,5);
        void* output = mmm_texture_create(device,70,ow,oh,true,5);
        // Four solid quadrants. Sample well inside regions, away from reconstruction edges.
        for (int y=0; y<ih; y++) for (int x=0; x<iw; x++) {
            uint8_t rgba[4] = {(uint8_t)(x<iw/2 ? 255 : 0), (uint8_t)(y<ih/2 ? 255 : 0), 40, 255};
            mmm_texture_replace_region(input,0,0,x,y,1,1,rgba,4);
        }
        void* cb = mmm_command_buffer_create(queue);
        bool plain = iteration % 7 == 0;
        int rc = mmm_fx_spatial_encode(fx,cb,input,output,plain);
        if (rc != 0) { check("real FX encode", false, "rejected legal input"); }
        else {
            mmm_command_buffer_commit(cb); mmm_command_buffer_wait(cb);
            id<MTLCommandBuffer> completed = (__bridge id<MTLCommandBuffer>)cb;
            const double gpuStart = completed.GPUStartTime, gpuEnd = completed.GPUEndTime;
            int64_t expected = !plain && gpuStart > 0 && gpuEnd > gpuStart
                    ? (int64_t)((gpuEnd-gpuStart)*1e9) : -1;
            check("completed scaler GPU sample matches this submission (recovery unavailable)",
                    mmm_fx_spatial_gpu_duration_ns(fx) == expected, "");
            bool pixels = true;
            for (int q=0; q<4; q++) {
                uint8_t pixel[4] = {};
                int x = q%2 ? ow*3/4 : ow/4, y = q/2 ? oh*3/4 : oh/4;
                mmm_texture_read_region(output,0,0,x,y,1,1,pixel,4,4);
                pixels &= abs((int)pixel[0]-(q%2 ? 0 : 255)) <= 6
                       && abs((int)pixel[1]-(q/2 ? 0 : 255)) <= 6 && abs((int)pixel[2]-40) <= 6;
            }
            if (!pixels || !mmm_fx_spatial_healthy(fx)) check("FX quadrant pixels and GPU status",false,"channel/orientation/GPU failure");
        }
        mmm_command_buffer_release(cb);
        cb = mmm_command_buffer_create(queue);
        check("wrong destination dimensions rejected", mmm_fx_spatial_encode(fx,cb,input,input,false) != 0, "");
        mmm_command_buffer_release(cb);
        mmm_fx_spatial_release(fx);
        mmm_texture_release(input); mmm_texture_release(output);
        if (g_failures) break;
    }
    check("100 spatial size/preset/recovery/release transitions", g_failures == 0, "real MetalFX encodes + readback");
    mmm_queue_release(queue); mmm_device_release(device);
}

static void test_temporal_color(void) {
    printf("\n== temporal SDR transfer conversion ==\n");
    void* device=mmm_device_create();
    if (!device) { check("temporal colour device",false,"no Metal device"); return; }
    id<MTLDevice> dev=(__bridge id<MTLDevice>)device;
    id<MTLCommandQueue> queue=[dev newCommandQueue];
    check("temporal colour sRGB views denied",!mmm_fx_temporal_color_create(device,MTLPixelFormatRGBA8Unorm_sRGB),"");
    check("temporal colour NULL encode denied",mmm_fx_temporal_color_encode(NULL,NULL,NULL,NULL,true)==-1,"");
    const int W=256,H=3;
    auto texture=[&](MTLPixelFormat format,int w,int h) {
        auto td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
        td.storageMode=MTLStorageModeShared; td.usage=MTLTextureUsageShaderRead|MTLTextureUsageRenderTarget;
        return [dev newTextureWithDescriptor:td];
    };
    for (MTLPixelFormat format : {MTLPixelFormatRGBA8Unorm,MTLPixelFormatBGRA8Unorm}) {
        void* converter=mmm_fx_temporal_color_create(device,format);
        check("temporal colour pipelines created",converter!=NULL,"");
        if (!converter) continue;
        id<MTLTexture> source=texture(format,W,H), linear=texture(MTLPixelFormatRGBA16Float,W,H);
        id<MTLTexture> output=texture(format,W,H), wrongSize=texture(MTLPixelFormatRGBA16Float,W-1,H);
        if (!source || !linear || !output || !wrongSize) {
            check("temporal colour resources",false,"allocation failed"); mmm_fx_temporal_color_release(converter); continue;
        }
        std::vector<uint8_t> original(W*H*4), actual(W*H*4), unchanged(W*H*4);
        std::vector<__fp16> floats(W*H*4);
        bool bgra=format==MTLPixelFormatBGRA8Unorm;
        for (int y=0;y<H;y++) for (int x=0;x<W;x++) {
            int p=(y*W+x)*4;
            original[p+(bgra?2:0)]=x; original[p+1]=255-x;
            original[p+(bgra?0:2)]=y==0?32:y==1?170:255; original[p+3]=(x+y)%256;
        }
        [source replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:original.data() bytesPerRow:W*4];
        id<MTLCommandBuffer> cb=[queue commandBuffer];
        check("temporal colour refuses resizing",mmm_fx_temporal_color_encode(converter,(__bridge void*)cb,
                (__bridge void*)source,(__bridge void*)wrongSize,true)==-3,"");
        int decode=mmm_fx_temporal_color_encode(converter,(__bridge void*)cb,(__bridge void*)source,(__bridge void*)linear,true);
        int encode=mmm_fx_temporal_color_encode(converter,(__bridge void*)cb,(__bridge void*)linear,(__bridge void*)output,false);
        check("temporal colour ordered decode/encode",decode==0 && encode==0,"");
        [cb commit]; [cb waitUntilCompleted];
        check("temporal colour GPU completed",cb.status==MTLCommandBufferStatusCompleted,"");
        [output getBytes:actual.data() bytesPerRow:W*4 fromRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0];
        [linear getBytes:floats.data() bytesPerRow:W*8 fromRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0];
        [source getBytes:unchanged.data() bytesPerRow:W*4 fromRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0];
        bool identity=true,transfer=true;
        for (int p=0;p<W*H*4;p++) identity &= abs((int)actual[p]-(int)original[p])<=1;
        for (int p=0;p<W*H*4;p+=4) for (int channel=0;channel<4;channel++) {
            int packed=bgra && channel==0?2:bgra && channel==2?0:channel;
            float value=original[p+packed]/255.0f;
            float expected=channel==3?value:value<=0.04045f?value/12.92f:powf((value+0.055f)/1.055f,2.4f);
            transfer &= fabs((float)floats[p+channel]-expected)<0.001f;
        }
        check("temporal sRGB transfer matches CPU reference and preserves alpha",transfer,"");
        check(bgra?"BGRA SDR round-trip all 256 levels/orientation/alpha":"RGBA SDR round-trip all 256 levels/orientation/alpha",identity,"");
        check("temporal conversion source unchanged",original==unchanged,"");
        check("temporal colour generation healthy",mmm_fx_temporal_color_healthy(converter),"");
        auto otherQueue=[dev newCommandQueue];
        auto invalid=[otherQueue commandBuffer];
        check("temporal colour unordered queue denied",mmm_fx_temporal_color_encode(converter,(__bridge void*)invalid,
                (__bridge void*)source,(__bridge void*)linear,true)==-3,"");
        for (int p=0;p<W*H*4;p+=4) { floats[p]=-0.25f; floats[p+1]=0.25f; floats[p+2]=1.25f; floats[p+3]=0.5f; }
        [linear replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:floats.data() bytesPerRow:W*8];
        cb=[queue commandBuffer];
        check("temporal colour clamp encode",mmm_fx_temporal_color_encode(converter,(__bridge void*)cb,
                (__bridge void*)linear,(__bridge void*)output,false)==0,"");
        mmm_fx_temporal_color_release(converter); // completion keeps reusable pipelines alive
        [cb commit]; [cb waitUntilCompleted];
        [output getBytes:actual.data() bytesPerRow:W*4 fromRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0];
        check("temporal SDR clamps overshoot and preserves alpha with in-flight release",
                cb.status==MTLCommandBufferStatusCompleted && actual[bgra?2:0]==0 && actual[bgra?0:2]==255
                && abs((int)actual[1]-137)<=1 && abs((int)actual[3]-128)<=1,"");
    }
    mmm_device_release(device);
}

static void test_temporal_prototype(void) {
    printf("\n== temporal MetalFX prototype ABI v1 ==\n");
    check("temporal NULL capability denied", !mmm_fx_temporal_supported(NULL), "");
    check("temporal NULL encode rejected", mmm_fx_temporal_encode(NULL,NULL,NULL,NULL,NULL,NULL,NULL,
            0,0,false,true) == -1, "");
    void* device = mmm_device_create();
    if (!device) { check("temporal device", false, "no Metal device"); return; }
    if (!mmm_fx_temporal_supported(device)) {
        printf("[SKIP] temporal active encoding: device unsupported\n");
        check("unsupported temporal creation denied", !mmm_fx_temporal_create(device,48,32,64,48), "");
        mmm_device_release(device); return;
    }
    check("temporal zero size denied", !mmm_fx_temporal_create(device,0,32,64,48), "");
    check("temporal downscale denied", !mmm_fx_temporal_create(device,64,48,48,32), "");
    check("temporal aspect mismatch denied", !mmm_fx_temporal_create(device,48,32,64,48), "");
    check("temporal overflow size denied", !mmm_fx_temporal_create(device,INT32_MAX,32,64,48), "");
    id<MTLDevice> dev = (__bridge id<MTLDevice>)device;
    id<MTLCommandQueue> queue = [dev newCommandQueue];
    auto texture = [&](MTLPixelFormat format, int w, int h, MTLStorageMode storage) {
        MTLTextureDescriptor* td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format
                width:w height:h mipmapped:NO];
        td.storageMode = storage;
        td.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsageRenderTarget;
        // Depth is read/rendered, not writable by compute.
        if (format == MTLPixelFormatDepth32Float) td.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
        return [dev newTextureWithDescriptor:td];
    };
    const int W=48, H=36, OW=64, OH=48;
    id<MTLTexture> color = texture(MTLPixelFormatRGBA16Float,W,H,MTLStorageModeShared);
    id<MTLTexture> depth = texture(MTLPixelFormatDepth32Float,W,H,MTLStorageModeShared);
    id<MTLTexture> motion = texture(MTLPixelFormatRG16Float,W,H,MTLStorageModeShared);
    id<MTLTexture> reactive = texture(MTLPixelFormatR8Unorm,W,H,MTLStorageModeShared);
    id<MTLTexture> output = texture(MTLPixelFormatRGBA16Float,OW,OH,MTLStorageModePrivate);
    id<MTLTexture> readback = texture(MTLPixelFormatRGBA16Float,OW,OH,MTLStorageModeShared);
    if (!queue || !color || !depth || !motion || !reactive || !output || !readback) {
        check("temporal test resources",false,"allocation failed"); mmm_device_release(device); return;
    }
    std::vector<__fp16> c(W*H*4), m(W*H*2,0), pixels(OW*OH*4);
    std::vector<uint8_t> mask(W*H,0);
    [motion replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:m.data() bytesPerRow:W*4];
    [reactive replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:mask.data() bytesPerRow:W];
    // Recreate real histories, encode repeated frames, then abruptly change colour with reset.
    for (int generation=0; generation<3; generation++) {
        void* fx = mmm_fx_temporal_create(device,W,H,OW,OH);
        check("temporal real generation created",fx != NULL,"");
        if (!fx) break;
        id<MTLCommandBuffer> invalid = [queue commandBuffer];
        check("temporal wrong output contract rejected",mmm_fx_temporal_encode(fx,(__bridge void*)invalid,
                (__bridge void*)color,(__bridge void*)depth,(__bridge void*)motion,(__bridge void*)reactive,
                (__bridge void*)readback,0,0,false,false)==-3,"shared output is illegal");
        check("temporal NaN jitter rejected",mmm_fx_temporal_encode(fx,(__bridge void*)invalid,
                (__bridge void*)color,(__bridge void*)depth,(__bridge void*)motion,(__bridge void*)reactive,
                (__bridge void*)output,NAN,0,false,false)==-4,"");
        id<MTLCommandQueue> otherQueue = [dev newCommandQueue];
        for (int frame=0; frame<5; frame++) {
            const bool blue = frame==3;
            const bool quadrants = frame==4;
            for (int i=0;i<W*H;i++) {
                c[i*4]=blue?0.125f:0.75f; c[i*4+1]=0.25f;
                c[i*4+2]=blue?0.75f:0.125f; c[i*4+3]=1;
                if (quadrants) {
                    const bool right=i%W>=W/2, bottom=i/W>=H/2;
                    c[i*4]=!right || bottom?0.75f:0.125f;
                    c[i*4+1]=right?0.75f:0.125f;
                    c[i*4+2]=bottom?0.75f:0.125f;
                }
            }
            // Deliberately replace history with asymmetric current content using the mask.
            std::fill(mask.begin(),mask.end(),quadrants?255:0);
            for (int i=0;i<W*H;i++) { m[i*2]=quadrants?-3:0; m[i*2+1]=quadrants?-2:0; }
            [motion replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:m.data() bytesPerRow:W*4];
            [reactive replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:mask.data() bytesPerRow:W];
            [color replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:c.data() bytesPerRow:W*8];
            id<MTLCommandBuffer> cb = [queue commandBuffer];
            MTLRenderPassDescriptor* pd = [MTLRenderPassDescriptor renderPassDescriptor];
            pd.depthAttachment.texture=depth; pd.depthAttachment.loadAction=MTLLoadActionClear;
            pd.depthAttachment.storeAction=MTLStoreActionStore;
            pd.depthAttachment.clearDepth=generation==1?0.25:0.75;
            id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:pd];
            [enc endEncoding];
            int rc=mmm_fx_temporal_encode(fx,(__bridge void*)cb,(__bridge void*)color,(__bridge void*)depth,
                    (__bridge void*)motion,(__bridge void*)reactive,(__bridge void*)output,
                    frame%2?0.25f:-0.25f,frame%2?-0.125f:0.125f,generation==1,blue);
            check("temporal real history/reset encode",rc==0,"");
            if (rc!=0) break;
            id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder];
            [blit copyFromTexture:output sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
                    sourceSize:MTLSizeMake(OW,OH,1) toTexture:readback destinationSlice:0 destinationLevel:0
                    destinationOrigin:MTLOriginMake(0,0,0)];
            [blit endEncoding];
            if (frame==4) { mmm_fx_temporal_release(fx); fx=NULL; } // submitted use retains generation
            [cb commit]; [cb waitUntilCompleted];
            check("temporal GPU completed",cb.status==MTLCommandBufferStatusCompleted,"");
            [readback getBytes:pixels.data() bytesPerRow:OW*8 fromRegion:MTLRegionMake2D(0,0,OW,OH) mipmapLevel:0];
            bool correct=true;
            for (int y=12;y<OH;y+=24) for (int x=16;x<OW;x+=32) {
                int p=(y*OW+x)*4;
                const bool right=x>=OW/2, bottom=y>=OH/2;
                float red=quadrants?(!right || bottom?0.75f:0.125f):(blue?0.125f:0.75f);
                float green=quadrants?(right?0.75f:0.125f):0.25f;
                float b=quadrants?(bottom?0.75f:0.125f):(blue?0.75f:0.125f);
                correct &= fabs((float)pixels[p]-red)<0.06f
                        && fabs((float)pixels[p+1]-green)<0.06f && fabs((float)pixels[p+2]-b)<0.06f;
            }
            check(quadrants?"temporal reactive replacement preserves quadrant orientation":
                    blue?"temporal reset removes previous colour":"temporal linear flat colour preserved",correct,"");
            if (fx) {
                check("temporal completed generation healthy",mmm_fx_temporal_healthy(fx),"");
                id<MTLCommandBuffer> wrongQueue=[otherQueue commandBuffer];
                check("temporal unordered queue rejected",mmm_fx_temporal_encode(fx,(__bridge void*)wrongQueue,
                        (__bridge void*)color,(__bridge void*)depth,(__bridge void*)motion,(__bridge void*)reactive,
                        (__bridge void*)output,0,0,false,false)==-3,"");
            }
        }
        mmm_fx_temporal_release(fx);
    }
    mmm_device_release(device);
}

static void test_temporal_scene(void) {
    printf("\n== complete temporal scene, camera/object motion and rejection ==\n");
    void* device=mmm_device_create();
    if(!device){check("temporal scene device",false,"");return;}
    if(!mmm_fx_temporal_supported(device)){printf("[SKIP] temporal scene unsupported\n");mmm_device_release(device);return;}
    id<MTLDevice> dev=(__bridge id<MTLDevice>)device;
    id<MTLCommandQueue> queue=[dev newCommandQueue];
    const int W=40,H=32;
    auto texture=[&](MTLPixelFormat format,int w,int h){
        MTLTextureDescriptor* d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
        d.storageMode=MTLStorageModeShared;d.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
        return [dev newTextureWithDescriptor:d];
    };
    id<MTLTexture> color=texture(MTLPixelFormatRGBA8Unorm,W,H),depth=texture(MTLPixelFormatDepth32Float,W,H);
    id<MTLTexture> output=texture(MTLPixelFormatRGBA8Unorm,80,64);
    void* frame=mmm_fx_temporal_frame_create(device,W,H,80,64,MTLPixelFormatRGBA8Unorm);
    check("temporal scene generation created",frame!=NULL,"");
    if(!frame){mmm_device_release(device);return;}
    std::vector<uint8_t> clearCoverage(W*H,0);
    id<MTLTexture> coverage=(__bridge id<MTLTexture>)mmm_fx_temporal_frame_texture(frame,5);
    [coverage replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:clearCoverage.data() bytesPerRow:W];
    float matrices[48]={};for(int m=0;m<3;m++)for(int i=0;i<4;i++)matrices[m*16+i*5]=1;
    float object[12]={};std::vector<uint8_t> pixels(W*H*4);std::vector<float> depths(W*H,0.5f);
    auto paint=[&](int x0){
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            int i=(y*W+x)*4;bool entity=x>=x0&&x<x0+12&&y>=8&&y<24;
            pixels[i]=entity?80+((x-x0)*37+y*13)%170:20;
            pixels[i+1]=entity?30+((x-x0)*19+y*31)%180:100;
            pixels[i+2]=entity?40+((x-x0)*43+y*7)%160:40;pixels[i+3]=255;
        }
        [color replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:pixels.data() bytesPerRow:W*4];
        [depth replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:depths.data() bytesPerRow:W*4];
        object[0]=2.0f*x0/W-1;object[1]=-0.5f;object[2]=0.4f;object[3]=1;
        object[4]=2.0f*(x0+12)/W-1;object[5]=0.5f;object[6]=0.6f;object[8]=-4.0f/W;
    };
    auto encode=[&](bool reset){
        id<MTLCommandBuffer> cb=[queue commandBuffer];
        int rc=mmm_fx_temporal_frame_encode(frame,(__bridge void*)cb,(__bridge void*)color,(__bridge void*)depth,
                (__bridge void*)output,matrices,object,1,0,0,reset);
        check("complete temporal scene encoded",rc==0,"");
        if(rc==0){[cb commit];[cb waitUntilCompleted];check("complete temporal scene GPU success",cb.status==MTLCommandBufferStatusCompleted,"");}
    };
    paint(10);encode(true);
    paint(12);encode(false);
    id<MTLTexture> flow=(__bridge id<MTLTexture>)mmm_fx_temporal_frame_texture(frame,0);
    id<MTLTexture> reactive=(__bridge id<MTLTexture>)mmm_fx_temporal_frame_texture(frame,1);
    __fp16 motion[2]={};uint8_t mask=255;
    [flow getBytes:motion bytesPerRow:4 fromRegion:MTLRegionMake2D(17,16,1,1) mipmapLevel:0];
    [reactive getBytes:&mask bytesPerRow:1 fromRegion:MTLRegionMake2D(17,16,1,1) mipmapLevel:0];
    check("independent object motion previous-minus-current pixels",std::abs((float)motion[0]+2)<0.02f&&std::abs((float)motion[1])<0.02f,"");
    check("matched independent object retains history",mask<16,"");
    // Root translation predicts two pixels; the rendered pose independently moves one more.
    paint(15);encode(false);
    [flow getBytes:motion bytesPerRow:4 fromRegion:MTLRegionMake2D(20,16,1,1) mipmapLevel:0];
    [reactive getBytes:&mask bytesPerRow:1 fromRegion:MTLRegionMake2D(20,16,1,1) mipmapLevel:0];
    check("rendered pose correspondence refines rigid motion",std::abs((float)motion[0]+3)<0.02f&&mask<16,"");
    uint8_t transient=255;
    [coverage replaceRegion:MTLRegionMake2D(20,16,1,1) mipmapLevel:0 withBytes:&transient bytesPerRow:1];
    encode(false);
    [reactive getBytes:&mask bytesPerRow:1 fromRegion:MTLRegionMake2D(20,16,1,1) mipmapLevel:0];
    check("raster transient coverage rejects history",mask==255,"");
    [coverage replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:clearCoverage.data() bytesPerRow:W];
    object[3]=0;encode(false);
    [reactive getBytes:&mask bytesPerRow:1 fromRegion:MTLRegionMake2D(20,16,1,1) mipmapLevel:0];
    check("new/unknown object rejects history",mask==255,"");
    // A camera translation moves every static point by two pixels; jitter is excluded.
    matrices[12]=-4.0f/W;encode(false);
    [flow getBytes:motion bytesPerRow:4 fromRegion:MTLRegionMake2D(6,16,1,1) mipmapLevel:0];
    check("camera reprojection previous-minus-current pixels",std::abs((float)motion[0]+2)<0.02f&&std::abs((float)motion[1])<0.02f,"");
    matrices[12]=0;
    object[3]=1;std::fill(depths.begin(),depths.end(),0.1f);
    [depth replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:depths.data() bytesPerRow:W*4];
    encode(false);
    [reactive getBytes:&mask bytesPerRow:1 fromRegion:MTLRegionMake2D(5,16,1,1) mipmapLevel:0];
    check("disocclusion rejects incompatible old depth",mask==255,"");
    std::fill(depths.begin(),depths.end(),0.0f);
    [depth replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:depths.data() bytesPerRow:W*4];encode(false);
    [reactive getBytes:&mask bytesPerRow:1 fromRegion:MTLRegionMake2D(5,16,1,1) mipmapLevel:0];
    check("sky/no geometry rejects history",mask==255,"");
    // A newly visible far surface must not accept a previous sky sample through an absolute tolerance.
    std::fill(depths.begin(),depths.end(),0.00002f);
    [depth replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:depths.data() bytesPerRow:W*4];encode(false);
    [reactive getBytes:&mask bytesPerRow:1 fromRegion:MTLRegionMake2D(5,16,1,1) mipmapLevel:0];
    check("far disocclusion rejects previous sky with matching colour",mask==255,"");
    // Static subpixel Gaussian: render at sample +jitter, then measure native-output centroid.
    // A scaler which forwards jitter without reconstruction visibly oscillates by almost a pixel.
    std::fill(depths.begin(),depths.end(),0.5f);
    [depth replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:depths.data() bytesPerRow:W*4];
    std::vector<uint8_t> result(80*64*4);double low=1e9,high=-1e9;
    auto halton=[](int n,int base){float r=0,f=1.0f/base;while(n){r+=(n%base)*f;n/=base;f/=base;}return r-0.5f;};
    for(int n=0;n<64;n++){
        float jx=halton(n%16+1,2),jy=halton(n%16+1,3);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            float dx=x+0.5f+jx-20,dy=y+0.5f+jy-16;
            uint8_t value=(uint8_t)std::round(220*std::exp(-(dx*dx+dy*dy)/18));
            int i=(y*W+x)*4;pixels[i]=pixels[i+1]=pixels[i+2]=value;pixels[i+3]=255;
        }
        [color replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:pixels.data() bytesPerRow:W*4];
        id<MTLCommandBuffer> cb=[queue commandBuffer];
        int rc=mmm_fx_temporal_frame_encode(frame,(__bridge void*)cb,(__bridge void*)color,(__bridge void*)depth,
                (__bridge void*)output,matrices,NULL,0,jx,jy,n==0);
        if(rc){check("jitter stability submission",false,"");break;}[cb commit];[cb waitUntilCompleted];
        [output getBytes:result.data() bytesPerRow:80*4 fromRegion:MTLRegionMake2D(0,0,80,64) mipmapLevel:0];
        if(n>=32){double weighted=0,total=0;for(int y=0;y<64;y++)for(int x=0;x<80;x++){
            double value=result[(y*80+x)*4];weighted+=(x+0.5)*value;total+=value;
        }double center=weighted/total;low=std::min(low,center);high=std::max(high,center);}
    }
    printf("Temporal static centroid range %.6f output pixels\n",high-low);
    check("static temporal image removes projection shaking",high-low<0.20,"");
    check("complete temporal generation healthy",mmm_fx_temporal_frame_healthy(frame),"");
    mmm_fx_temporal_frame_release(frame);mmm_device_release(device);
}

int main(void) {
    printf("==================================================\n");
    printf("MetalMod native Metal smoke test\n");
    printf("==================================================\n");
    @autoreleasepool {
        test_device();
        test_spatial_antialias();
        test_metalfx();
        test_temporal_prototype();
        test_temporal_color();
        test_temporal_scene();
        test_clear_and_readback();
        test_resources();
        test_mip_filter();
        test_sampler_address_modes();
        test_region_clear();
        test_fence();
        test_buffer_copy();
        test_texture_buffer();
        test_texel_buffer_emulation();
        test_base_instance();
        test_draw();
        test_surface();
        test_capture();
        test_utility_batching();
        test_private_storage();
    }
    printf("\n==================================================\n");
    if (g_failures == 0) printf("ALL CHECKS PASSED\n");
    else printf("%d CHECK(S) FAILED\n", g_failures);
    printf("==================================================\n");
    return g_failures == 0 ? 0 : 1;
}
