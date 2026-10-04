#ifndef METALMOD_DISPLAY_LINK_H
#define METALMOD_DISPLAY_LINK_H
#include <stdbool.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Developer-only FIFO presenter. One render-thread producer; native callback
 * owns every drawable. Stop before resizing/releasing layer or normal acquire.
 * Queue is the renderer's queue. Creation starts a private native run loop.
 * Layer must be BGRA8Unorm. Three immutable snapshot pairs, no unbounded queue.
 */
void* mmm_display_link_create(void* layer, void* queue, int32_t width, int32_t height);
/* Ownership guard for the ordinary layer API. */
bool mmm_display_link_owns_layer(void* layer);
bool mmm_display_link_healthy(void* handle);
/* Encodes an SDR snapshot (optional generated first, then real) into a fresh CB
 * on the same queue. Caller commits successful encodes immediately/in order and
 * stops the presenter if a successfully encoded CB is discarded. Source pixels
 * must remain stable until this copy completes. Textures: native-size tracked
 * RGBA8/BGRA8Unorm with shaderRead. NULL real clears using rgba instead.
 * Generated input must already include native hand/UI; no linear colour conversion.
 * Returns 0 accepted, 1 bounded mailbox full (drop render), negative invalid/failure.
 * Render IDs strictly increase. Generated presentations never advance game rings.
 */
int32_t mmm_display_link_submit(void* handle, void* cb, void* real, void* generated,
    uint64_t renderedId, float r, float g, float b, float a);
/* Stop waits at most two seconds for callback thread exit, no GPU wait.
 * 0 confirms sole ownership relinquished; nonzero forbids normal acquisition.
 * Release calls stop; completion handlers retain any resources still in flight.
 */
int32_t mmm_display_link_stop(void* handle);
void mmm_display_link_release(void* handle);
/* Stable 14 x uint64 ABI: callbacks, submitted renders, actual displayed,
 * displayed real, displayed generated, dropped outputs, missed deadlines,
 * mailbox full, last accepted render ID, last displayed render ID,
 * last displayed sequence ID, last actual presented time ns (CA clock),
 * occupied slots, failed flag. Counters do not reset. */
int32_t mmm_display_link_stats(void* handle, uint64_t* values, int32_t capacity);
#ifdef __cplusplus
}
#endif
#endif
