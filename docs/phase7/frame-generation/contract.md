# Phase 7C frame-generation foundation — ABI v1

**Gameplay update:** world/motion/native composited UI and ordinary-owner pair presentation are
implemented; see [gameplay contract and verification](gameplay.md). The foundation checkpoint below
is historical. Gameplay does not use the experimental CAMetalDisplayLink owner.

2026-10-03: implementation started at user request. This increment supplies an offline native
MetalFX interpolator and optional Panama bindings. It does not yet deliver generated frames to
the game window. No player control or gameplay flag is exposed at this checkpoint.

## Implemented boundary

`native/include/metalmod/metalmod_interpolation.h` is the authoritative C ABI.
`MetalNative.interpolation*` binds all six functions optionally so an older dylib does not disable
the renderer. The support query uses `supportsDevice:` and the factory uses
`newFrameInterpolatorWithDevice:` with ordinary `MTLCommandBuffer`; neither the Metal 4 factory
nor a Metal 4 renderer migration is needed. Capability is independent of temporal upscaling.
The supported-path probe and encodes passed on Apple M4 Pro/macOS 27.0.1.

| Input | Contract |
|---|---|
| Current/previous world colour | Linear SDR RGBA16Float at output resolution, top-left orientation, separate immutable snapshots |
| Depth | Depth32Float at input resolution; explicit reversed-depth flag |
| Motion | RG16Float at input resolution; unjittered current-to-previous lookup in top-left input pixels; wrapper applies output/input scale |
| Output | RGBA16Float at output resolution, private storage as required by MetalFX |
| Optional UI | Native output-size RGBA16Float overlay; not precomposited with world colour; premultiplied alpha is the intended future producer contract, not yet validated |
| Projection metadata | Positive physical near/far distances, vertical FOV in degrees, aspect from output dimensions, jitter in input pixels |
| Time | Real rendered-frame interval in seconds, finite and in (0, 0.25]; longer gaps require restarting/resetting at a subsequent valid interval |

All textures must be on the same device, tracked, 2D, single-sample, and satisfy usage-query bits.
Shared inputs remain supported. Only output requires private storage. Same-size and reduced
motion/depth inputs are tested; other scale ratios remain unvalidated. No combined-scaler
configuration is used. Camera matrices/new macOS 27 options remain outside this macOS 26 API boundary.

## Ordering, IDs and ownership

Each generation belongs to one render thread and latches its first command queue. The caller
encodes into a fresh, unsubmitted buffer with no open encoder, commits it in call order on that
queue, and keeps input pixels immutable until GPU completion. It must retire the generation if it
discards any successful encode's command buffer. Inputs must not be overwritten merely because
the interpolator retains their texture objects. Completion handlers retain the generation and
that encode's inputs; GPU failure latches unhealthy. An encode exception requires discarding the
buffer and retiring the generation. No retry loop is installed.

Rendered IDs are unsigned 64-bit values, current > previous, strictly increasing even at reset.
For consecutive non-reset pairs, previous ID and texture identity must match the prior successful
current input. ID validation cannot detect modified pixel contents; immutable snapshots are a
caller obligation. Invalid arguments do not advance history. First use resets automatically;
explicit cuts reset history. IDs identify rendered inputs, not simulation ticks or displayed frames.

Return 1 means an encoded warmup output that must not be displayed or counted as generated.
Return 0 means the output is eligible for the future scheduler. Conservatively exclude the reset
encode and the following two pairs: the measured moving-object sequence reproduced current colour
in the first two pairs after an explicit reset, then produced the midpoint. This is a conservative
policy verified on this device, not an Apple guarantee for every configuration. A fresh generation
produced a midpoint earlier; it uses the same guard for consistency. Return 0 does not guarantee
visual quality for arbitrary content. Negative results deny the encode.

The module never acquires/presents a drawable, ticks Minecraft, publishes lights, rotates staging
rings or advances temporal scene history. Existing presentation stays the sole owner.

## Next gameplay increment

Before enabling a developer-only frame-generation flag, implement and verify:

1. A world-colour snapshot boundary before native hand/screen effects/HUD, with depth/motion
   producers available independently of the experimental temporal-upscaling preference. Current
   surface colour already contains hand/HUD and cannot be passed directly as world colour.
2. Native hand/UI composition for both real and generated frames, with an explicit UI-age policy.
   The optional MetalFX UI texture binding has no gameplay producer or pixel acceptance yet.
3. The developer-only `CAMetalDisplayLink` owner and bounded pair mailbox are now implemented;
   see [display-link contract](display-link.md). Its unlocked real-window gate is pending. Connect
   gameplay generated SDR/hand/UI outputs only after independent producer validation.
4. Separate rendered/displayed/generated/dropped IDs and timestamps. Use only successful eligible
   outputs; discard on cut/resize/world change/minimize/failure and fall back to normal presentation.
5. Pacing, delivery, lifecycle and input-to-display latency evidence before any player-facing control
   or performance claim. Ordinary FIFO/IMMEDIATE behavior remains the current gameplay path.

Temporal quality/performance issues BUG-035/036 and spatial release acceptance remain open.
Frame generation does not resolve them and is not automatically enabled by Super Resolution.

## Verification

Real native smoke covers capability/creation, invalid dimensions, scalar metadata and output
storage, stale/mismatched IDs, wrong queue, repeated use, native/reduced inputs, normal/reversed
depth, reset eligibility and release before commit/completion. Static quadrants check linear colour
and orientation; a moving rectangle checks generated centroid against the midpoint of two real
poses and rejects a repeated real frame. Tests wait/read back only in the offline harness.
Standalone tests cover optional Panama symbols and real creation/usage queries from the packaged
JAR. Existing render/shader gates check regressions, not display-link delivery or latency.
