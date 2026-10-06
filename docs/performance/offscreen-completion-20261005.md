# Completed offscreen throughput — 2026-10-05

A disposable benchmark add-on measured actual GPU completion separately from display presentation.
Production core/adapter artifacts and the normal Minecraft instance are unchanged.

## Controlled scene

M4 Pro, external CB272K 60 Hz, 5120×2664 actual output, render distance 32,
Sodium 0.9.2 + existing adapter, Default fluid culling, fixed saved waypoint 13.
Vsync Off, Unlimited FPS, frame generation, spatial and temporal upscaling all Off.
Existing dynamic lighting remains enabled; no shader or quality setting was reduced.

Order: present / offscreen / offscreen / present. Each has 20 seconds warmup,
5 seconds countdown and approximately 30 seconds measured completion time.
Both modes insert one shared-event completion fence after all rendering and utility work,
retire the oldest before starting another frame when two frames are pending, and drain at
measurement boundaries. Every counted frame completed its GPU work. Offscreen keeps upload-ring
rotation, light-ring fencing, frame counters, scene rendering and HUD rendering; it omits drawable
acquisition, the final display blit and presentation. It therefore supplies no display updates.
The offscreen window intentionally retains its previous displayed image during the timed run.

## Results

| Run | Completed GPU FPS | Render-thread CPU ms | Draws/frame | Drawable wait ms |
|---|---:|---:|---:|---:|
| present01 | 59.93 | 3.89 | 16843 | 12.69 |
| offscreen02 | 96.58 | 2.39 | 16836 | 0.00 |
| offscreen03 | 96.54 | 2.33 | 16836 | 0.00 |
| present04 | 60.00 | 4.00 | 16844 | 12.55 |

Presented mean: **59.96 completed FPS**. Offscreen mean: **96.56 completed FPS**,
**61.04% greater throughput**. Both repeated conditions are closely matched.
Presented callbacks report 59.68 / 59.85 Hz; offscreen has zero presented callbacks.
Completion rate uses its own clock including final drain, so its endpoints differ slightly from
F8 frame-loop samples. CPU figures include the diagnostic fencing path, not a core optimization.

This establishes substantial presentation-path overhead in this scene, but does not isolate
compositor blocking from drawable/blit cost or predict a selective-presentation implementation.
It also demonstrates that removing presentation alone does not make this renderer reach 1,000 FPS.
The remaining offscreen interval is ~10.36 ms per completed frame. It is throughput, not an isolated
GPU execution-time measurement. Hardware, scene, distance, Minecraft and shader differences still
prevent a direct comparison with the mcopt video.

Next candidate: acquire drawables late and present selected frames at the hosting display cadence,
while retaining bounded GPU work. Measure completed rendering, displayed cadence and latency;
higher counters alone are insufficient. Terrain draw reduction / conservative occlusion remains a
separate target for the remaining rendering cost. Neither candidate requires abandoning MetalFX or
native ray tracing.

## Validation and scope

- Add-on compiled against real Minecraft 26.2 classes; accepted four live captures; client exited 0.
- All submitted measured frames completed; no focus/menu/pause interruptions, output resize,
  pipeline compilation, census or upscaling work in accepted captures.
- Draw counts match within 0.05%; post-capture terrain/lava images visually match.
- Startup telemetry: zero resource/pipeline failures, unbound bindings, missing attributes,
  slot collisions and binding-kind mismatches. No runtime GPU timeout or Metal error reported.
- Normal instance options, configuration and mod hashes match the original manifest.
- Production artifacts retain previously verified five-gate hashes. No production code changed;
  full production gates were not rerun for this benchmark-only change.
- Initial attempt stopped during warmup before captures to tighten the bound before rendering.
- Running add-on reused its post-capture screenshot filename. Offscreen02 was preserved before
  present04 overwrote it; future add-on uses distinct indices. Images were outside timed intervals.

Evidence: `build/reports/offscreen-completion-20261005/{analysis.json,validation.json,display-analysis.json}`,
archived frame CSVs, completion counts, console, launch metadata and two post-capture images.
Test changes are confined to `tools/metalfx_benchmark`, offscreen mode requires its explicit flag.

Reproduce using the existing fixed Sodium command plus `--offscreen-comparison` and a new output
folder; then run `python3 tools/metalfx_benchmark/analyze_offscreen.py <copied-game-directory>`.
Do not feed these instrumented captures to general throughput/stall analyzers.
