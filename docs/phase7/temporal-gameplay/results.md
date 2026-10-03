# Temporal gameplay implementation and validation — 2026-10-03

Temporal world reconstruction is implemented and installed. All changes remain uncommitted after
`6a18738`. Hardware: Apple M4 Pro, macOS 27.0.1, real Minecraft 26.2 JAR, JDK 26/release 22.
The complete [contract](../temporal-contract.md) replaces the previous jitter-only diagnostic.

## Delivered behavior

- **Temporal Upscaling** On uses MetalFX temporal at SR strengths 25/33/50; Off uses spatial.
  Strength 0, SR Off, menus, unsupported setup and latched failures use native rendering.
- Texel-exact SDR/linear transfer, deterministic scene jitter, current/previous render-time camera
  matrices and camera-relative origins, UUID entity roots, actual submitted moving-block poses,
  bounded depth-validated pose correspondence and conservative reactive coverage are connected.
- Raster masks cover transient/blended content; compute rejection covers disocclusion, sky,
  unknown/ambiguous objects, appearance changes and changed dynamic-light influence regions.
  Previous sky depth is never accepted for newly visible far geometry, even with matching colour.
- A single ordered submission reconstructs the world before the native hand-depth clear. Hands,
  screen effects and HUD render natively without jitter. Production does no per-frame readback/wait.
- History resets/retirement cover configuration, resize/reload, world/dimension changes, reconnect,
  camera cuts/large turns, incompatible projection changes, pause transitions and render gaps.
  Failed encoding uses validated same-frame plain recovery, then latched native fallback.
- F3 reports actual scaler, dimensions, history reset reason and region/object counters. The former
  `enableTemporalJitterProof` preference migrates to `enableTemporalUpscaling`; live UI wins.

## Final verification

`verification-20261003-004341-976103` passed all requested gates with host Metal access:

| Gate | Evidence |
|---|---|
| Canonical mod build | SUCCESS; packaged native bytes match, no validation classes in production JAR |
| Native smoke | [578 passing GPU assertions](smoke.txt), Metal validation enabled |
| Shader inventory | [87/87 + 9/9](inventory.txt), including reactive pipeline variants |
| Lighting inventory | [87/87 + 9/9](inventory-lighting.txt), dynamic/clustered + reactive variants |
| Pixel rendering | [203 assertions](render.txt), world/hand depth ordering, bypass/recovery and 100 mixed transitions |
| Standalone | [All tests passed](standalone.txt), FFI, projection math, real bytecode targets and annotation retention |
| Packaged copied-world game | [385 checks, zero failures, 18,227 frames, 40 stages](game-result.txt) |

[Full game checks](game-checks.txt), [gate result](verification.txt), [artifact](artifact.json).
The separate add-on and focus library are absent from the installed production mod. The source
instance and original saves are untouched by gameplay testing; offline authentication errors are
expected. Full logs/images remain in `build/reports/verification-20261003-004341-976103/game-copy/`.

The final game run checks all reconstruction modes/strengths, native hand/HUD, actual UI callbacks
and persistence, odd resize, inventory/pause, turns, teleport, FOV, render gaps, resource reload,
minimize/restore, Nether/Overworld transitions, reconnect and repeated generations. Real pigs,
chickens, dropped torch, rain/water and a pulsed sticky piston exercise live producers. UUID and
moving-block hooks execute. Dynamic lighting is **On**, matching the user's normal configuration:
two active lights and two changed-light rejection regions are observed; live Off/On also passes.
All resource/pipeline/unbound/missing-attribute health counters remain zero.

## Image stability and corrections

The static GPU fixture renders 64 jittered frames and measures the last two complete Halton cycles.
The wrong MetalFX lookup sign moved its centroid by 2.080 output pixels; the corrected helper
negates the projection sampling offset and measures **0.017724 pixels** (limit 0.20). This catches
whole-image jitter, rather than merely checking that a scaler was created. Camera (-2,0) and
independent-root (-2,0) motion, pose refinement (-3,0), transient coverage, unknown objects,
disocclusion, sky and far geometry replacing sky all pass actual GPU readbacks.

The final gameplay [16-image static cycle](static-sequence/) contains all 16 distinct samples.
Calibrated edge registration measures **0.0721 X / 0.0933 Y output pixels** of coherent movement
in its opaque terrain ROI. It uses a 1.5-pixel low-pass and fits additive brightness; artificial
one-pixel shifts calibrate the estimator. This measures coherent shake in that ROI, not every
high-frequency alias or transient content. [Analysis](image-analysis.json), reproducible
`tools/temporal_validation/analyze.py`.

[Matched strength-50 native/spatial/temporal crop](quality-comparison.png) and
[live entity/piston gallery](motion-gallery.jpg) preserve orientation/composition evidence.
Conservative rejection can soften animated/translucent detail; large/unmatched deformation is
rejected rather than assigned fabricated stationary motion. One crop cannot establish a global
quality winner or every-world visual acceptance.

An exploratory final teleport exposed an existing backend empty-index-count abort under Metal
validation (BUG-034). Empty vertex/index/instance jobs are now no-ops. A GPU regression verifies
all empty forms preserve the clear colour; the complete game run passes that teleport after the fix.

## Timing limits and release status

Six requested uncapped 20-second samples use the same frozen anchor/output (3842×2162), with
strength 50, native/spatial/temporal then reverse order, five-second warmups and no readbacks during
measurement. Effective IMMEDIATE, Vsync false and configured 260 FPS are recorded. Measured cadence
still remains about 60 FPS in the frozen scene; some runs also lose focus. These are **not throughput
or release acceptance**. [Raw records and focus/mode metadata](timing-samples.json), with per-sample
CSV files alongside. Completed temporal submission spans are not whole-frame GPU execution time.

Temporal gameplay implementation is delivered. Phase 7's representative motion-route quality,
uncapped throughput/p95/memory release selection and 7C presentation/frame generation remain
separate acceptance work. No automatic algorithm selection or release winner is claimed.

## Installation

The normal `MetalMod_Test_26.2/mods/metalmod-1.0.0.jar` now matches the final verified JAR:
`2743066fc293952ff225cf64782d3ac128a01128bfd444ad5027df8bc9173567`.
The prior diagnostic JAR is backed up in the verification directory. Config checksum is unchanged:
Metal On, SR On/strength 50, dynamic lights On, clustered Off, UMA On, previous temporal switch On.
That saved switch now enables actual temporal reconstruction after restarting Minecraft.
No test add-on is installed in the normal instance. [Installation record](installation.json).
