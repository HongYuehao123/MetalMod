# Phase 7B temporal prototype — input contract v1

Started 2026-10-02 from spatial checkpoint `92f90bf`. This is an offline native prototype,
not an accepted release candidate or an active gameplay upscaler. Spatial remains the reference.
The implemented surface is `mmm_fx_temporal_*` in `native/src/metalmod_temporal.mm`, with
optional Panama bindings in `MetalNative` and sampling math in `TemporalSampling`.

## Implemented resource and sampling conventions

| Input/output | ABI v1 convention |
|---|---|
| Colour | Scene-sized, linear RGB `RGBA16Float`, straight alpha. Diagnostic scenes are opaque. No spatial AA prepass, automatic exposure, HDR tone mapping or implicit sRGB texture view |
| Depth | Scene-sized `Depth32Float`. Caller explicitly supplies `depthReversed`; far is 1 for forward depth and 0 for reversed depth. Scene/depth sample orientation must match |
| Motion | Scene-sized `RG16Float`. **Previous minus current** unjittered position, in scene pixels, X right/Y down. MetalFX scale factors are both 1. Jitter is excluded |
| Reactive mask | Scene-sized `R8Unorm`, 0 normal accumulation, 1 reject history; intermediate values blend rejection. Required even for opaque tests |
| Output | Native-sized, linear `RGBA16Float`, private storage as required by MetalFX. Owned by caller; not a drawable or the SDR UI target |
| Exposure | Scaler-owned shared 1×1 `R16Float` containing 1; auto exposure disabled, pre-exposure 1 |
| Jitter | Current scene sampling offset in scene pixels, top-left coordinates, each component within ±0.5. Deterministic 16-sample Halton(2,3) helper, restarted on history reset |
| Sizes | Fixed for a scaler generation, positive dimensions, output at least input, bounded at 16384. Aspect ratio preserved within one input pixel of rounding; both output/input ratios validated against device-reported support, with no silent preset clamping |

The installed SDK's `MTLFXTemporalScaler.h` defines the vector direction, scale factors,
reactive mask, exposure and private output requirements. See Apple's
[temporal scaler](https://developer.apple.com/documentation/metalfx/mtlfxtemporalscaler) and
[motion-vector convention](https://developer.apple.com/documentation/metalfx/mtlfxtemporalscalerbase/motionvectorscalex).
ABI v1 uses the original command-buffer API and macOS 14.4 reactive masks; it does not depend on
macOS 27 output-resolution or jittered-motion features. Device support and legal creation are
separate checks. No format support is inferred solely from the GPU name.

`TemporalSampling.motion` converts unjittered NDC into this pixel convention:
`motion.x = (previous.x - current.x) * width/2`,
`motion.y = (current.y - previous.y) * height/2`.
An object moving right/down by ten scene pixels therefore publishes (-10,-10).
This assumes NDC +Y up. The renderer producer still must validate the actual projection and
texture orientation using asymmetric camera/object tests; unit arithmetic does not prove it.

## Submission and history ownership

All calls and mutable scaler state are render-thread-owned. Inputs, output, device and command
buffer are caller-owned Metal handles, retained as needed by the scaler/submission. Input texture
usage must include the scaler's SDK-reported requirements, exposed by
`mmm_fx_temporal_texture_usage` / `MetalNative.temporalTextureUsage` for roles 0–4. The output must additionally be private.
Use tracked resources and one ordered command queue per generation. Complete source rendering
and utility uploads before encoding. The prototype never creates or commits command buffers,
waits for the GPU, owns presentation, or advances the Phase 6 light publication cadence.

First encoding always resets history. Thereafter the caller explicitly requests resets.
Null arguments, bad texture/device/queue contracts and nonfinite/out-of-range jitter fail before
encoding. A successful encode consumes this generation's history: commit it in order. If its
command buffer is discarded, retire the generation before further use. An encoding exception or
asynchronous GPU error latches failure; retire the generation rather than retrying each frame.
Completion retains the generation, so releasing the caller's handle with work in flight is safe.
Caller texture reuse still requires normal GPU synchronization.

The future frame coordinator must reset on first use, Off/On, size/format/algorithm changes,
world/dimension change, disconnect/reconnect, teleport/camera cut, incompatible FOV/projection
change, reload, minimize/restore, a missed rendered frame and resumption after a long pause.
Publish the reason and restart jitter/previous transforms together. This lifecycle integration
is pending; an explicit native reset flag is not proof that game reset triggers are implemented.

## Producer work required before gameplay integration

1. Verify actual SDR transfer/alpha semantics with ramps and reference readbacks. Add bounded
   scene-only SDR→linear input and linear→SDR output conversions with an identity test against
   native colour. `RGBA8Unorm` perceptual scene data cannot simply be cast to float. Keep HUD native
   and preserve the vanilla composition boundary; this is an SDR reconstruction conversion, not HDR.
2. Apply jitter only to scene rendering. Keep extraction, culling margins, picking, input and UI
   consistent with the unjittered camera. Capture previous/current render-time transforms and each
   frame's own camera-relative origin. Do not substitute simulation tick transforms.
3. Generate object motion for terrain, moving blocks, entities and hands. Reject new, missing,
   invalid or behind-camera previous geometry. Define stable object identity and previous-pose
   lifetime; a depth-only camera vector pass is an incomplete producer.
4. Publish conservative reactive coverage for particles, water, translucent layers, animated
   textures, sky, disocclusion and rapidly changing lighting. Missing motion must not be passed as
   valid stationary geometry. Test the resulting masks and moving-object vectors in debug views.
5. Connect the complete generation/history lifecycle, then run recorded gameplay quality/performance
   comparisons and the complete Phase 7 acceptance matrix. No temporal promotion before those gates.

## Verification of this increment

Native smoke adds three recreated histories with fifteen real MetalFX encodes, alternating nonzero
jitter, forward/reversed depth, flat linear-colour checks, abrupt colour changes with explicit
reset, illegal shared-output rejection, invalid dimensions/jitter and queue rejection, and release
before final submission completes. Asymmetric quadrants with full reactive rejection and nonzero
motion check current-frame replacement and orientation. These synthetic tests do not establish
object-motion accuracy, projection jitter, partial reactive-mask quality, image alpha, disocclusion
or game SDR conversion correctness.
Standalone tests check jitter determinism/bounds, NDC motion signs, invalid geometry, actual Panama
creation/release and ABI float/bool arguments on the null rejection path. Native tests exercise the
valid encode path. Unsupported hardware explicitly skips active temporal encoding.

Run all five gates using `AGENTS.md`; enable `MTL_DEBUG_LAYER=1` for smoke and pixel rendering.
Results for this increment are recorded in `temporal-foundation/` and `HANDOFF.md`.
