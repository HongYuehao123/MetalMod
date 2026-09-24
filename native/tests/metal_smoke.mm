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
#include "metalmod/metalmod_motion.h"

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
    double presentTime = 0.0;
    double presentInterval = 0.0;
    int rc = mmm_layer_acquire(layer, &drawable, &drawableTexture, &presentTime, &presentInterval);
    check("acquire reports a presentation time (0 until the display has shown a frame)",
          presentTime == 0.0 && presentInterval == 0.0, "");
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

// Phase 7A: MetalFX spatial upscaling over our own textures.
//
// The backend upscales its own low-resolution render target into the native-resolution one, so what
// this proves is the whole contract that path depends on: the device reports support, a scaler is
// created for the exact formats and sizes the backend uses, the usage bits it demands are readable
// (so the render target can be built to satisfy it), and encoding a real upscale into a command
// buffer on our own queue produces a correctly sized image with the input's colours in it.
//
// A flat colour is used deliberately: it is invariant under the scaler's filtering, so a pass here
// cannot be a false pass from sampling the wrong texel of a gradient.
static void test_metalfx_spatial(void) {
    printf("\n== MetalFX spatial upscaling (Phase 7A) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("metalfx device", false, "no Metal device"); return; }
    void* queue = mmm_queue_create(device);

    const int64_t kRGBA8 = 70;      // MTLPixelFormatRGBA8Unorm - the backend's main target format
    const int64_t kBGRA8 = 80;      // MTLPixelFormatBGRA8Unorm - the CAMetalLayer's format
    const int IN_W = 32, IN_H = 24, OUT_W = 96, OUT_H = 72;

    check("spatial scaling is supported for RGBA8 input and output",
          mmm_fx_spatial_supported(device, kRGBA8, kRGBA8), mmm_fx_last_error());

    void* scaler = mmm_fx_spatial_create(device, kRGBA8, kRGBA8, IN_W, IN_H, OUT_W, OUT_H, 0);
    check("spatial scaler created", scaler != NULL, mmm_fx_last_error());
    if (scaler == NULL) {
        mmm_queue_release(queue);
        mmm_device_release(device);
        return;
    }

    // The usage bits are what the Java side sizes its render target from, so an unreadable or
    // zeroed answer here is a texture the scaler would later reject.
    uint32_t colorUsage = 0, outputUsage = 0;
    bool haveUsage = mmm_fx_spatial_texture_usage(scaler, &colorUsage, &outputUsage);
    check("scaler reports its texture usage requirements", haveUsage && colorUsage != 0,
          "");
    printf("     color usage 0x%X, output usage 0x%X\n", colorUsage, outputUsage);

    // Exactly the configuration the backend uses: a shared render target as input (MetalMod reads
    // its own targets back) and a second target as output.
    uint32_t inputUsage = colorUsage | 4u | 1u;   // scaler bits | render target | shader read
    void* input = mmm_texture_create_full(device, kRGBA8, IN_W, IN_H, 1, 1, 2, true, inputUsage);
    void* output = mmm_texture_create_full(device, kRGBA8, OUT_W, OUT_H, 1, 1, 2, true,
                                           outputUsage | 4u | 1u | 8u);  // + copy source
    if (input == NULL || output == NULL) {
        check("scaler textures allocated", false, "allocation failed");
        mmm_texture_release(input);
        mmm_texture_release(output);
        mmm_fx_spatial_release(scaler);
        mmm_queue_release(queue);
        mmm_device_release(device);
        return;
    }

    // A flat mid-teal input. Filtering cannot change it, so any deviation in the output is the
    // scaler failing to read the input or failing to write the output.
    unsigned char source[IN_W * IN_H * 4];
    for (int i = 0; i < IN_W * IN_H; i++) {
        source[i * 4 + 0] = 20;
        source[i * 4 + 1] = 160;
        source[i * 4 + 2] = 200;
        source[i * 4 + 3] = 255;
    }
    check("input upload", mmm_texture_replace_region(input, 0, 0, 0, 0, IN_W, IN_H, source,
                                                     IN_W * 4) == 0, "");

    void* cb = mmm_command_buffer_create(queue);
    check("encode spatial upscale",
          mmm_fx_spatial_encode(scaler, cb, input, output, 0, 0) == 0, mmm_fx_last_error());
    mmm_command_buffer_commit(cb);
    mmm_command_buffer_wait(cb);
    mmm_command_buffer_release(cb);

    static unsigned char upscaled[OUT_W * OUT_H * 4];
    memset(upscaled, 0, sizeof(upscaled));
    int rc = mmm_texture_read_region(output, 0, 0, 0, 0, OUT_W, OUT_H, upscaled, sizeof(upscaled),
                                     OUT_W * 4);
    check("upscaled output readback", rc == 0, "");
    if (rc == 0) {
        // Centre and all four corners: a scaler that wrote the wrong region, or flipped the image,
        // still leaves a flat input flat - so what is checked is coverage, not orientation.
        const int probes[5][2] = {
            {OUT_W / 2, OUT_H / 2}, {1, 1}, {OUT_W - 2, 1}, {1, OUT_H - 2}, {OUT_W - 2, OUT_H - 2}};
        bool allCovered = true;
        for (int i = 0; i < 5; i++) {
            const unsigned char* p = &upscaled[(probes[i][1] * OUT_W + probes[i][0]) * 4];
            if (p[1] < 140 || p[1] > 180 || p[2] < 180 || p[2] > 220) {
                allCovered = false;
                printf("     probe (%d,%d) = R%d G%d B%d A%d\n", probes[i][0], probes[i][1],
                       p[0], p[1], p[2], p[3]);
            }
        }
        check("upscaled image covers the whole output with the input's colour", allCovered, "");
    }

    // Perceptual (sRGB-encoded) is the mode the backend uses by default, and it must be creatable
    // too - a mode the OS rejects has to be discovered here rather than as a black screen.
    void* perceptual = mmm_fx_spatial_create(device, kRGBA8, kRGBA8, IN_W, IN_H, OUT_W, OUT_H, 0);
    check("perceptual colour mode is creatable", perceptual != NULL, mmm_fx_last_error());
    mmm_fx_spatial_release(perceptual);

    // The layer's real format. Minecraft's main target is RGBA8 and the drawable is BGRA8, so an
    // RGBA8 -> BGRA8 scaler is what a present-path integration would need.
    void* toBgra = mmm_fx_spatial_create(device, kRGBA8, kBGRA8, IN_W, IN_H, OUT_W, OUT_H, 0);
    check("RGBA8 -> BGRA8 scaler created (present-path format)", toBgra != NULL,
          mmm_fx_last_error());
    mmm_fx_spatial_release(toBgra);

    // A scaler asked for an impossible configuration must fail loudly rather than be created and
    // then produce garbage.
    void* bad = mmm_fx_spatial_create(device, kRGBA8, kRGBA8, 0, 0, OUT_W, OUT_H, 0);
    check("invalid sizes are refused", bad == NULL, "");
    check("release of NULL is safe", true, "");
    mmm_fx_spatial_release(NULL);

    mmm_fx_spatial_release(scaler);
    mmm_texture_release(input);
    mmm_texture_release(output);
    mmm_queue_release(queue);
    mmm_device_release(device);
}

// Phase 7B groundwork: a temporal scaler needs colour, depth and motion textures and keeps history
// across frames. Creating one here proves the device and formats accept it, and that the depth
// convention the backend reports (near 0, far 1) is the one the descriptor is configured with.
static void test_metalfx_temporal(void) {
    printf("\n== MetalFX temporal scaler (Phase 7B) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("metalfx temporal device", false, "no Metal device"); return; }
    void* queue = mmm_queue_create(device);

    const int64_t kRGBA8 = 70;
    const int64_t kDepth32F = 252;   // MTLPixelFormatDepth32Float
    // MTLPixelFormatRG16Float - the two-channel format Apple documents for temporal motion. An
    // earlier revision used 115 here, which is RGBA16Float: the scaler was created for a four-channel
    // motion texture that the producer would never write, and the constant's comment said otherwise.
    const int64_t kRG16F = 65;
    const int IN_W = 32, IN_H = 24, OUT_W = 96, OUT_H = 72;

    bool temporalSupported = mmm_fx_temporal_supported(device, kRGBA8, kDepth32F, kRG16F, kRGBA8);
    printf("     temporal scaling supported: %s\n", temporalSupported ? "yes" : "no");

    void* scaler = mmm_fx_temporal_create(device, kRGBA8, kDepth32F, kRG16F, kRGBA8,
                                          IN_W, IN_H, OUT_W, OUT_H,
                                          /*depthReversed=*/false,
                                          /*dynamicResolution=*/false, 1.0f, 1.0f,
                                          /*reactiveMask=*/false, kRGBA8,
                                          /*jitteredMotion=*/false);
    check("temporal scaler created", scaler != NULL, mmm_fx_last_error());
    // The capability query is an optimisation hint, not a promise: MetalFX answers it per device and
    // per format pair, and creation is the authoritative test. What must hold is that neither can
    // report success while the other cannot deliver - a query that says yes and a create that returns
    // nil would leave the caller choosing a path it cannot run.
    check("the capability query agrees with creation",
          !temporalSupported || scaler != NULL, temporalSupported ? "query said yes" : "query said no");
    if (scaler != NULL) {
        bool depthReversed = true, dynamicResolution = true, reactiveMask = true;
        bool jitteredMotion = true;
        float minScale = 0.0f, maxScale = 0.0f;
        check("descriptor is readable",
              mmm_fx_temporal_describe(scaler, &depthReversed, &dynamicResolution,
                                       &minScale, &maxScale, &reactiveMask, &jitteredMotion), "");
        check("scaler kept Minecraft's depth convention (near 0, far 1)", !depthReversed, "");
        check("NULL-safe encode refusal", mmm_fx_temporal_encode(NULL, NULL, NULL, NULL, NULL,
                                                                 NULL, 0.0f, 0.0f, false) != 0, "");
        mmm_fx_temporal_release(scaler);
    }

    // The dynamic-resolution form is what a render-scale setting would want, because it keeps the
    // scaler's history valid across a resolution change instead of resetting it. Record what this
    // machine does rather than assert it: the answer decides whether Phase 7B can change render
    // scale without flushing history, and a hard assertion here would encode a guess.
    void* adaptive = mmm_fx_temporal_create(device, kRGBA8, kDepth32F, kRG16F, kRGBA8,
                                            IN_W, IN_H, OUT_W, OUT_H,
                                            false, true, 0.5f, 1.0f, false, 0, false);
    printf("     dynamic-resolution temporal scaler: %s\n",
           adaptive != NULL ? "created" : mmm_fx_last_error());
    if (adaptive != NULL) {
        bool dynamic = false;
        float minScale = 0.0f, maxScale = 0.0f;
        mmm_fx_temporal_describe(adaptive, NULL, &dynamic, &minScale, &maxScale, NULL, NULL);
        check("dynamic-resolution range round-trips", dynamic && minScale > 0.0f && maxScale > 0.0f,
              "");
    }
    mmm_fx_temporal_release(adaptive);

    mmm_queue_release(queue);
    mmm_device_release(device);
}

// ---------------------------------------------------------------------------------------------
// Phase 7B: render-resolution motion vectors
// ---------------------------------------------------------------------------------------------

// Column-major 4x4 helpers, matching JOML's `Matrix4f.get(float[])` and MSL's `float4x4`. Written
// out here rather than pulled in, because the test's whole job is to state the convention the kernel
// has to agree with, and a library's own convention would be a second opinion.
static void mat4_identity(float* m) {
    memset(m, 0, 16 * sizeof(float));
    m[0] = m[5] = m[10] = m[15] = 1.0f;
}

static void mat4_translate(float* m, float x, float y, float z) {
    mat4_identity(m);
    m[12] = x;
    m[13] = y;
    m[14] = z;
}

static void mat4_multiply(float* out, const float* a, const float* b) {
    float result[16];
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            float sum = 0.0f;
            for (int k = 0; k < 4; k++) sum += a[k * 4 + row] * b[col * 4 + k];
            result[col * 4 + row] = sum;
        }
    }
    memcpy(out, result, sizeof(result));
}

// JOML's `setPerspective(fovY, aspect, zNear, zFar, /*zZeroToOne=*/true)`, which is what the engine
// builds because the Metal backend reports `isZZeroToOne`. Near maps to 0 and far to 1, which is the
// convention the temporal scaler is created with (`depthReversed = false`).
static void mat4_perspective(float* m, float fovYRadians, float aspect, float zNear, float zFar) {
    memset(m, 0, 16 * sizeof(float));
    float f = 1.0f / tanf(fovYRadians * 0.5f);
    m[0] = f / aspect;
    m[5] = f;
    m[10] = zFar / (zNear - zFar);
    m[11] = -1.0f;
    m[14] = zFar * zNear / (zNear - zFar);
}

// Standard cofactor inverse. The kernel is handed an inverse, so the test has to produce one the
// same way the Java side does - by actually inverting a projection, not by asserting a formula.
static bool mat4_invert(float* out, const float* m) {
    float inv[16];
    inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15]
            + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
    inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15]
            - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
    inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15]
            + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
    inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14]
            - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
    inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15]
            - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
    inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15]
            + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
    inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15]
            - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
    inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14]
            + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
    inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15]
            + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
    inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15]
            - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
    inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15]
            + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
    inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14]
            - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
    inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11]
            - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
    inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11]
            + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
    inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11]
            - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
    inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10]
            + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];

    float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
    if (fabsf(det) < 1e-20f) return false;
    det = 1.0f / det;
    for (int i = 0; i < 16; i++) out[i] = inv[i] * det;
    return true;
}

// Run one dispatch, wait for it, and read the motion texture back into `out` (2 floats per pixel).
//
// The fence is created *after* the dispatch is committed, which is the direction the fence contract
// documents: it reports that everything committed before it has completed. A shared-storage read
// issued straight after the commit would otherwise race the GPU and read the texture's zeros, which
// is exactly the shape of a false pass.
//
// The texture is RG16Float, so the bytes are half-precision and are widened here. Reading them as
// float32 would decode two pixels' halves as one float and produce a plausible-looking mixture of
// garbage - which is what this test did before the format was honoured.
static bool motion_run_and_read(void* motion, void* queue, void* depth,
                                const float* currentInverse, const float* previous, float* out) {
    if (mmm_motion_run(motion, queue, depth, currentInverse, previous) != 0) return false;
    void* fence = mmm_fence_create(queue);
    bool signaled = mmm_fence_wait(fence, 5000000000LL);
    mmm_fence_release(fence);
    if (!signaled) return false;
    int width = mmm_motion_width(motion);
    int height = mmm_motion_height(motion);
    static __fp16 halves[64 * 64 * 2];
    if (width * height * 2 > 64 * 64 * 2) return false;
    if (mmm_texture_read_region(mmm_motion_texture(motion), 0, 0, 0, 0, width, height, halves,
                                (size_t)width * height * 2 * sizeof(__fp16),
                                (size_t)width * 2 * sizeof(__fp16)) != 0) {
        return false;
    }
    for (int i = 0; i < width * height * 2; i++) out[i] = (float)halves[i];
    return true;
}

static void test_motion_vectors(void) {
    printf("\n== motion vectors (Phase 7B) ==\n");
    void* device = mmm_device_create();
    if (device == NULL) { check("motion device", false, "no Metal device"); return; }
    void* queue = mmm_queue_create(device);

    const int64_t kDepth32F = 252;
    const int W = 16, H = 12;

    check("Depth32Float is readable by the motion kernel",
          mmm_motion_depth_format_supported(device, kDepth32F), "");
    check("a combined depth/stencil format is refused",
          !mmm_motion_depth_format_supported(device, 260), "");

    void* motion = mmm_motion_create(device, W, H);
    check("motion resource created", motion != NULL, mmm_motion_last_error());
    if (motion == NULL) {
        mmm_queue_release(queue);
        mmm_device_release(device);
        return;
    }
    check("motion size round-trips",
          mmm_motion_width(motion) == W && mmm_motion_height(motion) == H, "");
    check("invalid sizes are refused", mmm_motion_create(device, 0, H) == NULL, "");
    check("release of NULL is safe", true, "");
    mmm_motion_release(NULL);

    uint32_t depthUsage = kUsageShaderRead | kUsageRenderTarget;
    void* depth = mmm_texture_create_full(device, kDepth32F, W, H, 1, 1, 2, true, depthUsage);
    check("depth texture allocated", depth != NULL, "");

    static float vectors[W * H * 2];

    // 1. An identity current inverse with a previous projection translated by exactly one NDC unit
    //    on each axis. The kernel's pixel mapping is x: 0.5*(ndc+1)*width and y: 0.5*(1-ndc)*height,
    //    so the expected motion is (+0.5*W, -0.5*H) exactly. This is the check that pins the
    //    convention: a flipped y, a transposed matrix, an off-by-half pixel or a wrong pixel scale
    //    all move the answer, and the depth value is irrelevant because identity and translation
    //    carry no perspective divide.
    float identity[16];
    mat4_identity(identity);
    float shifted[16];
    mat4_translate(shifted, 1.0f, 1.0f, 0.0f);
    check("depth clear", mmm_clear_textures(queue, NULL, false, 0, 0, 0, 0, depth, true, 0.5) == 0,
          "");
    if (motion_run_and_read(motion, queue, depth, identity, shifted, vectors)) {
        float expectedX = 0.5f * W;
        float expectedY = -0.5f * H;
        // Tolerance is half-precision, not float: the texture is RG16Float, so a value of 8 is stored
        // to the nearest 0.0078 and 6 to the nearest 0.0039.
        bool allMatch = true;
        for (int i = 0; i < W * H; i++) {
            if (fabsf(vectors[i * 2] - expectedX) > 0.02f
                    || fabsf(vectors[i * 2 + 1] - expectedY) > 0.02f) {
                allMatch = false;
                printf("     pixel %d = (%.4f, %.4f), expected (%.4f, %.4f)\n",
                       i, vectors[i * 2], vectors[i * 2 + 1], expectedX, expectedY);
                break;
            }
        }
        check("a one-unit NDC shift is exactly half the texture in pixels, with y down", allMatch,
              "");
    } else {
        check("motion dispatch and readback", false, mmm_motion_last_error());
    }

    // 2. The same camera twice must produce zero motion at every pixel and every depth. This is the
    //    round trip that catches an inverse that does not match its forward matrix - which is the
    //    failure mode that would silently smear the whole image.
    float perspective[16];
    mat4_perspective(perspective, 70.0f * 3.14159265f / 180.0f, (float)W / (float)H,
                     0.05f, 1000.0f);
    float inversePerspective[16];
    check("perspective inverse", mat4_invert(inversePerspective, perspective), "");
    check("depth clear", mmm_clear_textures(queue, NULL, false, 0, 0, 0, 0, depth, true, 0.3) == 0,
          "");
    if (motion_run_and_read(motion, queue, depth, inversePerspective, perspective, vectors)) {
        float worst = 0.0f;
        for (int i = 0; i < W * H * 2; i++) worst = fmaxf(worst, fabsf(vectors[i]));
        check("an unmoved camera produces no motion at all", worst < 1e-4f, "");
    } else {
        check("motion dispatch and readback", false, mmm_motion_last_error());
    }

    // 3. The depth buffer has to be used, not ignored. A camera moved one unit to the right shifts a
    //    nearer surface further across the image than a farther one, and the whole field stays
    //    uniform because every pixel is the same distance away.
    float previousRight[16];
    float translateRight[16];
    mat4_translate(translateRight, 1.0f, 0.0f, 0.0f);
    mat4_multiply(previousRight, perspective, translateRight);

    float nearMotion = 0.0f, farMotion = 0.0f;
    bool uniform = true;
    check("depth clear", mmm_clear_textures(queue, NULL, false, 0, 0, 0, 0, depth, true, 0.2) == 0,
          "");
    if (motion_run_and_read(motion, queue, depth, inversePerspective, previousRight, vectors)) {
        nearMotion = vectors[0];
        for (int i = 0; i < W * H; i++) {
            if (fabsf(vectors[i * 2] - nearMotion) > 1e-3f
                    || fabsf(vectors[i * 2 + 1]) > 1e-3f) uniform = false;
        }
    }
    check("depth clear", mmm_clear_textures(queue, NULL, false, 0, 0, 0, 0, depth, true, 0.8) == 0,
          "");
    if (motion_run_and_read(motion, queue, depth, inversePerspective, previousRight, vectors)) {
        farMotion = vectors[0];
    }
    // A camera that moved to the right makes the world appear to move left, so a surface's previous
    // image position is to the *right* of where it is now and the motion vector is positive - the
    // sign MetalFX's "previous position minus current position" convention gives.
    check("a camera move produces uniform motion on a flat depth plane", uniform, "");
    check("a nearer surface moves further across the image than a farther one",
          nearMotion > 0.0f && farMotion > 0.0f && nearMotion > farMotion + 0.01f, "");
    // Uniform NDC depth is not uniform view depth: the pixel shift is proportional to 1/z, and with
    // this projection the ratio between the two planes is exactly 4. Checking the ratio rather than
    // the magnitude pins the depth reconstruction without hard-coding a constant.
    check("the shift scales as one over view depth",
          fabsf(nearMotion / farMotion - 4.0f) < 0.05f, "");
    printf("     motion at depth 0.2: %.3f px, at depth 0.8: %.3f px\n", nearMotion, farMotion);

    // 4. The far plane has no finite world position, so its motion is defined to be zero rather than
    //    an arbitrary reprojection of a point at infinity.
    check("depth clear", mmm_clear_textures(queue, NULL, false, 0, 0, 0, 0, depth, true, 1.0) == 0,
          "");
    if (motion_run_and_read(motion, queue, depth, inversePerspective, previousRight, vectors)) {
        bool allZero = true;
        for (int i = 0; i < W * H * 2; i++) if (fabsf(vectors[i]) > 1e-6f) allZero = false;
        check("the far plane carries zero motion rather than noise", allZero, "");
    }

    // 5. A depth buffer of the wrong size is refused rather than read out of bounds.
    void* wrongDepth = mmm_texture_create_full(device, kDepth32F, W - 1, H, 1, 1, 2, true, depthUsage);
    check("a mismatched depth size is refused",
          mmm_motion_run(motion, queue, wrongDepth, inversePerspective, perspective) != 0, "");
    mmm_texture_release(wrongDepth);

    // 6. The overlay: geometry the depth buffer cannot describe, stamped over the dispatch's answer.
    //
    // The base case here is a still camera on a flat plane, which the kernel already answers with
    // zero - so anything non-zero afterwards is the stamp, and anything zero where a stamp covers is
    // the depth test rejecting it. That separation is the whole point of the check.
    check("depth clear", mmm_clear_textures(queue, NULL, false, 0, 0, 0, 0, depth, true, 0.5) == 0,
          "");
    MMMMotionStamp covering = {0.0f, 0.0f, (float)(W / 2), (float)H, 3.0f, -4.0f, 0.4f, 0.6f};
    check("a stamp is accepted", mmm_motion_set_stamps(motion, &covering, 1) == 1, "");
    if (motion_run_and_read(motion, queue, depth, identity, identity, vectors)) {
        bool allStamped = true;
        for (int i = 0; i < W * H; i++) {
            bool inLeftHalf = (i % W) < W / 2;
            float wantX = inLeftHalf ? 3.0f : 0.0f;
            float wantY = inLeftHalf ? -4.0f : 0.0f;
            if (fabsf(vectors[i * 2] - wantX) > 1e-2f
                    || fabsf(vectors[i * 2 + 1] - wantY) > 1e-2f) {
                allStamped = false;
                printf("     pixel %d = (%.3f, %.3f), expected (%.1f, %.1f)\n",
                       i, vectors[i * 2], vectors[i * 2 + 1], wantX, wantY);
                break;
            }
        }
        check("a stamp replaces the depth-derived motion exactly inside its box", allStamped, "");
    }

    // The depth test: a stamp whose range excludes the surface's depth must change nothing, or an
    // object behind a wall would take the wall's motion with it.
    MMMMotionStamp hidden = {0.0f, 0.0f, (float)W, (float)H, 9.0f, 9.0f, 0.8f, 1.0f};
    check("a hidden stamp is accepted", mmm_motion_set_stamps(motion, &hidden, 1) == 1, "");
    if (motion_run_and_read(motion, queue, depth, identity, identity, vectors)) {
        bool untouched = true;
        for (int i = 0; i < W * H * 2; i++) if (fabsf(vectors[i]) > 1e-4f) untouched = false;
        check("a stamp the depth test rejects changes nothing", untouched, "");
    }

    // Clearing the stamps must put the depth-derived answer back, so a frame with no moving geometry
    // pays for the buffer but not for the draw.
    check("stamps can be cleared", mmm_motion_set_stamps(motion, NULL, 0) == 0, "");
    int32_t stored = -1, dropped = -1;
    mmm_motion_stamp_stats(motion, &stored, &dropped);
    check("cleared stamps report as zero", stored == 0 && dropped == 0, "");

    // A count past the capacity is clamped rather than written past the buffer.
    int32_t capacity = mmm_motion_stamp_capacity(motion);
    check("the stamp capacity is positive", capacity > 0, "");
    static MMMMotionStamp flood[2048];
    int32_t over = capacity + 17 < 2048 ? capacity + 17 : 2048;
    check("an oversized stamp set is clamped", mmm_motion_set_stamps(motion, flood, over) == capacity,
          "");
    mmm_motion_stamp_stats(motion, &stored, &dropped);
    check("the dropped stamps are counted", stored == capacity && dropped == over - capacity, "");
    mmm_motion_set_stamps(motion, NULL, 0);

    printf("     %s\n", mmm_motion_describe(motion));

    mmm_texture_release(depth);
    mmm_motion_release(motion);
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
        test_motion_vectors();
        test_metalfx_spatial();
        test_metalfx_temporal();
    }
    printf("\n==================================================\n");
    if (g_failures == 0) printf("ALL CHECKS PASSED\n");
    else printf("%d CHECK(S) FAILED\n", g_failures);
    printf("==================================================\n");
    return g_failures == 0 ? 0 : 1;
}
