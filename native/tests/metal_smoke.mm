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

int main(void) {
    printf("==================================================\n");
    printf("MetalMod native Metal smoke test\n");
    printf("==================================================\n");
    @autoreleasepool {
        test_device();
        test_clear_and_readback();
        test_surface();
    }
    printf("\n==================================================\n");
    if (g_failures == 0) printf("ALL CHECKS PASSED\n");
    else printf("%d CHECK(S) FAILED\n", g_failures);
    printf("==================================================\n");
    return g_failures == 0 ? 0 : 1;
}
