#ifndef METALMOD_FRAME_GENERATION_H
#define METALMOD_FRAME_GENERATION_H
#include <stdint.h>
#include <stdbool.h>
#ifdef __cplusplus
extern "C" {
#endif
/* One render-thread owner. Every successful capture/encode CB is committed in order on queue.
 * World is output-sized SDR before hand/HUD; scene/depth are input-sized before hand depth clear.
 * GUI coverage comes from changes after the pre-GUI colour snapshot; MetalFX receives world only.
 */
void* mmm_fg_create(void* device,void* queue,int32_t iw,int32_t ih,int32_t ow,int32_t oh,int64_t format);
void mmm_fg_release(void* handle);
/* Input-sized R8 raster coverage: clear before world rendering, capture visible unstable fragments. */
void* mmm_fg_coverage(void* handle);
/* Output-sized R8 actual GUI fragments; full-screen vignette is modulation, not HUD. */
void* mmm_fg_gui_coverage(void* handle);
int32_t mmm_fg_capture(void* handle,void* cb,void* world,void* scene,void* depth,
    const float* matrices,const float* objects,int32_t count,uint64_t id,
    float dt,float nearPlane,float farPlane,float fov,float jx,float jy,bool reset);
/* Required native hand-coverage and SDR-colour snapshot after hand rendering, before GUI depth clear.
 * A successful capture/encode command buffer must be committed on the same ordered queue.
 */
int32_t mmm_fg_capture_hand(void* handle,void* cb,void* handDepth,void* beforeGuiColor);
void* mmm_fg_ui_texture(void* handle); /* borrowed linear real composite used by last encode */
void* mmm_fg_hand_mask(void* handle); /* borrowed R8 foreground coverage, diagnostic readback only */
/* 0 eligible, 1 warmup, negative failure. Never present warmup. */
int32_t mmm_fg_encode(void* handle,void* cb,void* realComposite);
/* Borrowed diagnostic textures, test readback only: generated/current/previous linear, pre-GUI SDR, hand, motion, world coverage, GUI coverage. */
void* mmm_fg_debug_texture(void* handle,int32_t role);
void* mmm_fg_texture(void* handle); /* borrowed generated SDR */
/* Consumes a retained ordinary drawable on success, or acquires display-only if NULL.
 * 1 means unavailable, caller acquires if needed and presents real.
 * Only generated/real display acquisition occurs here; never rotates staging/light rings.
 */
int32_t mmm_fg_present(void* handle,void* layer,void* drawable,void* realComposite);
/* Display refresh interval, independent of rendered deltaTime (avoids pacing feedback). */
int32_t mmm_fg_present_paced(void* handle,void* layer,void* drawable,void* realComposite,float displayInterval);
/* captures, encodes, eligible, generated actually displayed, real actually displayed,
 * dropped, health flags (bit 0 GPU failure, bit 1 content rejection), last presentedTime ns. Optional slots 8..16: first time,
 * interval count/sum/min/max ns, sub-half-refresh count, missed-refresh count,
 * nonalternating/nonmonotonic presentation count, latest one-second display FPS * 1000.
 * Capacity 8 remains supported. */
int32_t mmm_fg_stats(void* handle,uint64_t* values,int32_t capacity);
#ifdef __cplusplus
}
#endif
#endif
