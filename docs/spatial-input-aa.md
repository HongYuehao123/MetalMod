# Spatial foliage input antialiasing

Implemented 2026-09-29; final offline verification completed 2026-09-30. Spatial now receives a resolved **4x MSAA world input**. Verified vanilla
cutout terrain uses alpha-to-coverage; HUD, Native and Temporal use their existing sample count.
This addresses a reproduced reduced-input coverage failure, rather than adding another final filter.
BUG-044 remains open pending a matched in-game forest comparison.

## Coverage contract

The unmodified terrain shader first filters texture alpha (including RGSS and mip sampling), then
discards the whole fragment below `ALPHA_CUTOUT`. Reducing world resolution increases the texture
footprint; the hard decision removes fractional leaf coverage before Spatial sees it. Ordinary MSAA
alone retains that hard discard and therefore does not correct leaf texture boundaries.

`SpatialCoverageVariant` verifies the original vanilla vertex and fragment fingerprints and the
cutout pipeline identity before adapting it. Optional lighting adaptation runs first. A native-owned,
reflected uniform selects the original alpha test in single-sample passes, or coverage in 4x passes:

- At magnification, a derivative-width threshold supplies fractional edge coverage.
- During minification, blend toward the mip's filtered alpha, which represents leaf area. This also
  retains coverage when a distant mip is almost constant and its alpha derivative is zero.
- Alpha-to-coverage selects samples; alpha-to-one keeps surviving opaque cutout samples opaque.
  Non-cutout pipelines retain their normal blending.

Unrecognized resource-pack shaders keep their original shader source and alpha test. They receive
geometric MSAA where attachments are compatible, but the vanilla foliage correction does not apply.

## Attachments, depth and lifetime

The engine's `MainTarget` attachments remain ordinary single-sample textures. A render-thread registry
owns private four-sample companions for Spatial's world pair and compatible passes sharing its depth.
Pipelines retain their creation descriptor and lazily cache a four-sample state. Both sample-count
variants are exercised for all 87 vanilla and nine post-processing pipelines by the shader inventory.

Every participating pass stores and resolves color and depth to the engine textures. Depth resolves
with **Max**, matching Minecraft's reversed Z. Ordinary LOAD passes preserve per-sample depth and
coverage, allowing a later background draw to fill uncovered samples without overwriting the leaf.
External clears, texture uploads and copies invalidate the affected companion; a small seed pass
refreshes only invalid LOAD attachments from their resolved values. A depth-only copy does not collapse
unmodified color coverage. Unsupported attachment layouts retain the ordinary pass.

Changing registration waits for queue completion at a frame boundary. Texture release purges its
companion, releasing a view leaves its parent registered, and disabling or teardown releases the
registry. Input resolution changes use the existing world-target retirement contract. Registration
is bypassed in menu, Native and Temporal frames; an effective Spatial fallback can enable it at the
next extraction boundary.

Four-sample RGBA8 plus D32 companions add about **63.3 MiB** at 1920x1080, excluding other world passes'
compatible attachments. Pass resolves and retained sample storage add bandwidth; this is not free AA.

## Controls

Options → MetalMod → MetalFX Upscaling → **Spatial foliage AA**. On by default. Like the other
controls, edits apply on leaving the page, including Esc. The setting persists as
`spatialAntialiasing=true`; `-Dmetalmod.spatialAA=false` selects an initial comparison state, and an
in-game choice overrides it. Final-image FXAA remains independently switchable. F3 identifies active
Spatial input coverage.

## Offline evidence

`tools/render_check/run.sh` now includes a nonuniform synthetic leaf mask with eight mip levels,
the real vanilla cutout/RGSS shader and MetalFX Spatial, with FXAA absent. Compare 64→128 Spatial to
a 512-raster reference reduced to 128, over eight small UV/camera phases. Final measurements:

| Metric | Single-sample input | Coverage input |
|---|---:|---:|
| Raster-reference mean squared error | 1804.08 | 1299.70 |
| Excess inter-frame pixel variation against reference motion | 1020.94 | 428.33 |
| Constant distant-mip reference error | 17496.15 | 5385.99 |

These are approximately **28%, 58% and 69% reductions**, respectively, for this fixture. They do not
establish whole-forest acceptance. The test also rejects the adaptation for changed pack shaders.
Images are emitted under `build/foliage-check/` for inspection.

The native smoke suite verifies fractional coverage, opaque cutout alpha, reversed-Z resolve,
per-sample depth across passes, color-only/depth-only loads, external clears/copies, view lifetime
and disabling. `MTL_DEBUG_LAYER=1 native/build/metalmod_smoke --spatial-coverage-only` validates this
path independently. Full-suite Metal validation still encounters the pre-existing MetalFX shared
output/private-storage assertion (BUG-032); the ordinary mandatory suite passes.

A synthetic three-pass GPU benchmark at 1920x1080 input measured **0.122 ms single-sampled versus
1.652 ms with coverage**, about **1.530 ms added**, in one run on M4 Pro. This measures raster/store/
resolve cost with a simple shader; it is not the forest's frame time or a performance guarantee.

## Remaining acceptance

At the same 2K forest view and render scale, switch Spatial foliage AA off/on with final FXAA off.
Record F3's actual dimensions/effective mode, stationary detail, slow camera motion and frame time.
Check leaf density, silhouettes, water, entities and resize transitions. Compare Temporal separately.
MSAA and coverage retain sampled area, but cannot reconstruct texture structure below the input's
sampling limit; residual shimmer and sample quantization may remain. Do not label BUG-044 fixed
without this player-visible comparison.
