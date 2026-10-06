# Ordinary render submission batching

MetalMod's Java command encoders previously created and committed a command buffer per render
pass. Moving ownership to the Java encoder alone was unsafe: Minecraft can call `submit()` on a
different encoder object from the one that recorded the pass. Native queue ownership solves this
without relying on Java object identity.

Ordinary rendering now leases a shared native command buffer. Ending a render pass still ends its
real Metal encoder and releases the Java lease. The native owner retains the pending buffer.
Buffer uploads and GPU copies outside an open pass append ordered blits to the same buffer. Writes inside an
open pass use the existing separate utility path, submitted after the closed pass at the next
boundary. Staging data remains protected by the existing three-slot GPU-reader retirement ring.

The batch flushes at 64 render encoders, explicit command-buffer creation, waits, fences, readback,
presentation, frame retirement and queue release/switch. It never carries a frame across drawable
acquisition. There is no new frame-in-flight policy or GPU-completion wait on the render path.
Native batching state has the same single render-thread owner as utility batching; the independent
frame-generation presentation service does not access it.

`mmm_command_buffer_create` keeps its explicit-buffer semantics and flushes pending ordinary work.
MetalFX, temporal colour/motion and frame generation retain their existing explicit buffers.
Attachment actions, render encoders, shader inputs, light ABI, camera motion and hand/HUD coverage
are unchanged. Future ray tracing can use the existing explicit boundary for compute/acceleration
structure work; batching does not introduce a geometry or material representation.

The Java native binding is optional for older dylibs. Batching is enabled by default;
`-Dmetalmod.commandBatching=false` restores per-pass submissions for comparison. The disposable
benchmark changes that property between captures on the render thread. F8 records the effective
choice. Its native `submissions` field counts physical commits; Java telemetry counts logical
pass/presentation leases and is labelled accordingly.

## Verification

Native smoke tests cover 130 released pass leases with interleaved staged writes and copies,
64-pass splitting, explicit MetalFX ordering, open-pass upload fallback, fence/wait flushes,
staging-ring reuse, queue switches/release and eight draws consuming distinct versions of one
uniform buffer. Metal validation is enabled. Real vanilla-pipeline pixel checks, shader inventory,
standalone tests and the canonical build are also required.

Gameplay performance uses a disposable copy of the saved world, stable weather/time, a fixed
smooth camera motion, 32-chunk rendering and uncapped immediate presentation. Native runs follow
an off/on/on/off sequence; spatial strength 25 follows with a single off/on pair. Each has 20s
warm-up and 30s measurement after the F8 countdown, following an initial 60s warm-up. Unfocused
captures are rejected and retained. No profiling counter sampling or screenshots run during the
measurements. Display pacing is included in frame intervals; total GPU frame time is unmeasured.

Run after the canonical build:

```sh
python3 tools/metalfx_benchmark/run_manual.py --world '新的世界' \
  --output /private/tmp/metalmod-submission-NEW --submission-benchmark
python3 tools/metalfx_benchmark/analyze_submission.py \
  /private/tmp/metalmod-submission-NEW/debug/metalmod
```

Evidence and measured results are saved under `build/reports/submission-batching/`.
The lighter smooth scene does not reproduce the original dense 19,000-draw waypoint; BUG-031
must remain open until that route is retested. These results do not establish a universal FPS gain.

## Measured gameplay result (2026-10-04)

M4 Pro, 5120x2664 drawable, 32 chunks, ~7470 draws/frame, dynamic lights enabled,
QoS and GPU-stage sampling off. The same benchmark JAR runs both modes by changing the property.
The final packaged JAR enables the measured path by default and adds the uniform-version regression.

| Mode | Baseline FPS | Batched FPS | Baseline p95 | Batched p95 | Submissions/frame off → on |
|---|---:|---:|---:|---:|---:|
| Native repeat 1 | 54.46 | 59.59 | 33.31 ms | 18.06 ms | 47.41 → 22.99 |
| Native repeat 2 (on then off) | 54.49 | 59.46 | 33.27 ms | 18.36 ms | 49.07 → 22.99 |
| Spatial 25, single pair | 57.23 | 59.96 | 24.36 ms | 17.75 ms | 50.05 → 24.97 |

Native mean frame time decreases **8.49%**, corresponding to **9.27%** higher throughput.
Spatial's single pair shows **4.77%** higher throughput. Native command-buffer creation CPU time
averages 0.543/1.017 ms off versus 0.064/0.064 ms on; spatial is 0.496 versus 0.062 ms.
Draw counts differ by less than 0.1%, spatial recovery/failure counts stay zero, and accepted captures
contain no unfocused intervals. One spatial baseline lost focus and was rejected; the raw capture
and rejection marker are preserved. The near-60Hz results include macOS display pacing and do not
predict uncapped gains at every resolution or camera location.

Raw accepted and rejected CSVs, index, console, launch arguments, JAR hashes and the JSON analysis
are under `build/reports/submission-batching/`. The normal instance is not modified by this work.

The final default-enabled build passes all five offline gates and **385/385** packaged temporal
copied-world gameplay checks (18,474 frames), including native/spatial/temporal, history resets,
camera/entity/moving-block motion, lighting, inventory/settings, resize, minimize, dimension
changes and reconnect. Logs/checks are under `build/reports/submission-batching/temporal/`.

The initial broad frame-generation sequence passes 183/184 on both batching and the per-pass
baseline. Both fail the same coverage-area fixture: 1,251,104 pixels on versus 1,249,331 off out of
3,686,400. That fixture bounds phantom/orb/beam coverage, but the production reactive mask also
correctly includes cloud fragments (`MetalRenderPipeline.reactiveFragment`). Visible clouds make
its arbitrary one-third limit fail independently of batching. Both original results are preserved
in `framegen-initial/` and `framegen-baseline/`.

The disposable test add-on now disables clouds only for the entity coverage scene, explicitly
checks that isolation and restores/checks the copied cloud preference before subsequent stages.
It changes no production shader, threshold or frame-generation behavior. All other stages retain
clouds. This isolates the intended object test rather than declaring the baseline failure a pass.

The first isolated retry passes the object-coverage check (674,595 pixels, 18.3%) and cloud-setting
restoration, but its opening stages are occluded (`visibility bits=10`); no real/generated OS
presentation callbacks arrive until the test window becomes visible. It is preserved as a failed
run (`framegen-occluded/`, 172/181 checks). The add-on now waits for one second of active, visible,
unoccluded startup before starting any measured stage; it still asserts visibility within stages.
This changes fixture preparation, not the renderer or any display-cadence acceptance criterion.

The final visible rerun passes **191/191** broad frame-generation checks plus **15/15** saved-On
fresh-JVM restart checks. Object coverage is 678,777 pixels (18.4%); clouds are restored for all
remaining stages. Native/spatial/temporal modes, native hand/HUD pixels, settings, odd resize,
5K/fullscreen, motion, minimize/restore, VSync changes and menu bypass pass. The normal JAR/config
hashes match their pre-validation values. Accepted final logs/checks are in `framegen-final/`.
