# Upscaling quality audit — 2026-09-29

Scope: inspect the current working tree without changing rendering code, runtime settings,
or installing a JAR. The checkout already contains substantial uncommitted rendering changes.

## Findings

1. **Native no longer means the vanilla backing resolution.** `WindowResolution.applyArray`
   replaces GLFW framebuffer dimensions with logical window dimensions when the default
   `syncWindowResolution=true` is latched by the Metal backend. Both the main-target size and
   swapchain size are redirected. `mmm_layer_configure` uses that size as `drawableSize`.
   Thus a 2560x1440 logical fullscreen window with a 5120x2880 backing store renders native
   at 2560x1440, even at 100% world scale and with upscaling off. This is a fourfold reduction
   in raster pixel count. Core Animation enlarges that image for the panel. Display Settings'
   logical size is not evidence that vanilla rendered only that many pixels. Existing BUG-040
   records a prior live size comparison, but visual parity was not established by that run.

2. **Temporal jitter is applied in the wrong space.** `CameraJitterMixin` calls
   `projection.translate(clipX, clipY, 0)`. JOML post-multiplies this translation, producing
   `P*T*v`, whereas a homogeneous clip translation requires `T*P*v`. The current operation
   translates view-space positions and its screen displacement depends on depth and FOV.
   MetalFX receives `ProjectionJitter.offsetX/Y` as fixed input-pixel offsets, so those two
   contracts disagree. This can impair temporal accumulation; its visual magnitude is not
   measured here. It does not explain native-mode degradation, where jitter is gated off.
   JOML semantics: https://joml-ci.github.io/JOML/apidocs/org/joml/Matrix4f.html

   New CPU reproduction: `bash tools/jitter_check/run.sh`. It tests 16 actual Halton phases
   at depths 0.1, 1, 10, and 100 with the real instance's JOML 1.10.8. All **64/64 cases fail**
   the advertised displacement. An independent `T*P` control passes all 64. In the first phase,
   advertised x jitter is -0.220703 pixels; actual x displacement is -1.772982 at depth 0.1,
   -0.177298 at 1, -0.017730 at 10, and -0.001773 at 100. This test reproduces the hook's
   matrix operation; it does not execute a transformed Mixin or capture a game image.

   Existing scaling checks verify `clip * width / 2 == offset`, without projecting a point.
   Their motion test adds then removes the same incorrect translation, so its cancellation
   can pass despite the contract error. `SceneMotion.prepare` also removes that translation;
   any future repair must review both producer and motion removal, including bob/portal transforms.

3. **The current Temporal path is a different quality tradeoff from direct temporal upscaling.**
   `temporalOutputScale` defaults to zero. `temporalOutput` therefore chooses the world input
   size, then a Spatial scaler enlarges that result to the main target. At 75%/2K this is
   1920x1080 Temporal -> 2560x1440 Spatial -> panel composition. Temporal history cannot
   store the larger output grid in this configuration. This does not prove it has no AA
   benefit, but direct full-output reconstruction and this path need separate quality evaluation.
   The pacing guard can also switch to Spatial while retaining the Temporal request.

4. **Post-FXAA cannot establish equivalence to the old native path.** It runs after the world
   upscale, before HUD/hand rendering, and operates on already sampled RGBA8 pixels. It can
   blend detected edges and soften detail, but cannot observe geometry absent from those
   samples. Current tests prove edge blending and flat-colour preservation, not foliage detail,
   temporal stability, or vanilla parity. It is currently off by default and can still be enabled
   by saved configuration or a launch override. 100% scale bypasses MetalFX, but not this AA hook
   or resolution synchronization.

## Verification in this audit

Canonical build, native smoke, shader inventory (87/87 + 9/9), pixel render check, standalone
tests, scaling check, and mixin check all pass. GPU gates first failed inside the sandbox with
no Metal device, then passed with approved host access. The new CPU jitter regression fails
as intended, identifying a gap in the existing seven gates. No rendering source was edited.
No new in-game or screenshot comparison was performed; past performance figures are historical
evidence in `docs/temporal-performance.md`, not new measurements from this audit.

## Controlled visual test plan

Use a cloned instance/world and the same window mode, coordinates, camera, FOV, resource packs,
render distance, mipmaps, graphics settings, and GUI scale. Do not alter the real play instance.
Compare lossless world crops at equal backing-pixel sizes, plus a short motion recording for shimmer.
Capture the actual main-target/drawable sizes and active effect, not just the requested setting.

| Run | Backend | Resolution sync | World scale | Effect | Post-AA | Purpose |
|---|---|---|---|---|---|---|
| A | Vanilla, mod absent | stock | 100% | none | none | Reference; verify actual framebuffer |
| B | Metal | off | 100% | off | off | Match vanilla backing size; isolate backend |
| C | Metal | on | 100% | off | off | Isolate loss from backing-size change |
| D | Metal | on | 100% | off | on | Isolate FXAA softness and residual aliases |
| E | Metal | on | 75% | Spatial | off | Isolate reduced world source |
| F | Metal | on | 75% | Temporal, default intermediate | off | Current temporal chain |
| G | Metal | on | 75% | Temporal, output scale 1 | off | Direct-output comparison |

For F/G disable the pacing guard in the cloned test launch to hold the effect fixed, warm history
before capture, and record frame time separately. Do not infer quality from fps. B versus A must be
judged before attributing every discrepancy to resolution; if B still differs, investigate sampling,
LOD, post-chain ordering and presentation with matched input/output sizes. After an authorized jitter
repair, rerun F/G to distinguish that defect from the intermediate-resolution tradeoff.
