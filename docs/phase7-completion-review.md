# Phase 7A / 7B completion review — 2026-09-29

Verdict: **both implemented; neither fully accepted as finished.** This distinguishes delivered
code from the roadmap's criterion: correct rendering through resize/history resets with measured
quality and performance. Phase 7C's frame-generation requirements are separate and do not block
closing 7A/7B individually.

| Requirement | 7A Spatial | 7B Temporal |
|---|---|---|
| Engine integration, controls, world target, HUD separation | Implemented; offline coverage | Implemented; offline coverage |
| Upscale produces output pixels | Native/render/scaling gates pass | Native/scaling gates pass for current Temporal -> Spatial chain |
| Off bypass | Fixed and tested at a remembered 75% scale | Same shared fix |
| Resize and resource lifetime | Offline replacements/teardown pass; current live stress pending | Same, plus motion/scaler rebuild coverage; current live stress pending |
| Projection/motion/history contract | No temporal history needed | Corrected jitter regression passes; camera/object motion and resets pass offline |
| Performance | Historical live comparisons demonstrate benefit in measured scenes | Historical live cost and 2K pacing comparisons exist; cleanup build not remeasured live |
| Controlled visual quality | Not signed off; native 2K regression remains open | Not signed off; corrected jitter has no live visual evaluation |

Latest cleanup verification: canonical build, native smoke, shader inventory (87/87 + 9/9), render
check, standalone tests, scaling check (124 passing assertions), mixin check, and the new CPU
jitter regression all pass. This review inspected those results; it did not rerun an unchanged
build or create new runtime measurements. The cleanup JAR has not been installed in the play instance.

## User quality evidence and priority

The subsequent user report makes Spatial a demonstrated quality failure: distant forests have
unbearable aliasing, and FXAA does not solve it (BUG-044). Spatial fixes take priority. Temporal is
reported near native with blurred edges; its softness is secondary and the cause remains unisolated.
This is useful user evidence, though its exact build/scale was not supplied. Neither phase is closed;
Spatial needs a quality correction, not merely documentation of a preferred filter.

## What prevents closure

- **7A:** at fixed 2560x1440 output, demonstrate intended quality against native world rendering,
  stable HUD/hand/interface, correct fullscreen/windowed resize and GUI-only frames on the cleaned
  build. Record the quality/performance tradeoff explicitly. BUG-040's reported native image
  degradation cannot be treated as resolved by an offline flat-colour test. The BUG-029 sky-reload
  workaround is retained and its symptom must also be checked during mode/scale transitions.
- **7B:** additionally evaluate still-camera convergence, camera motion, rapid turns, mob silhouettes,
  particles, piston blocks, water/cutout/transparency, and dimension/camera-cut history resets. Record
  quality and frame time at the same input/output sizes; distinguish requested Temporal from actual
  Spatial fallback. The pre-cleanup performance figures do not prove the corrected jitter's visual
  behavior or the current resource lifetime's live stability.
- **Shared contract risk:** BUG-032 remains open: shared-storage MetalFX output works in tested
  release builds but violates the documented private-output contract and can fail Metal validation.
  A final closure record must resolve this or explicitly accept the tested platform restriction;
  it must not claim unrestricted API compliance. No storage change is made by this review.

Reactive masks are absent, but absence alone is not a stated mandatory 7B deliverable. Test actual
transparency/disocclusion behavior first; introduce a mask only if defects establish the need.
Likewise, extra AA and Temporal AA at 100% are separate features, not automatic prerequisites.

## Smallest useful final acceptance run

Use the cleaned build in a cloned instance, 2K output, fixed scene/settings and a chosen render scale.
No additional 5K Temporal benchmark is required.

1. Native/Off, Spatial, Temporal: verify actual target/stage sizes and fallback reasons, then compare
   stationary edges, foliage and texture detail with post-FXAA initially off.
2. Move and turn through that scene; record a short lossless/motion comparison and matching frame
   times. Confirm Temporal is actually running for the quality sample.
3. Resize, switch fullscreen, open/close menus, and change scale/effect repeatedly. Check HUD,
   sky/fog, logs, stale frames and resource stability.
4. In Temporal, exercise moving objects/transparency and a dimension change or camera cut. Confirm
   resets and no persistent trails/blended old-world frames.

Record pass/fail and known limits against the table above. Only then mark each increment finished,
or explicitly close it with named accepted limitations. Implementation work on Phase 8 may proceed
independently under the existing roadmap, but that does not change 7A/7B's acceptance status.
