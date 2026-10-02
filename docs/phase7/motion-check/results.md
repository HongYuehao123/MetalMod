# Motion, stalls and presentation checks — 2026-10-02

The tests ran autonomously in the disposable copied world. The production spatial renderer was
unchanged; all new camera control and presentation observation live in a separate test add-on.
The original instance/world/config were untouched. The usable spatial checkpoint is `aa6693d`.
Measured production JAR SHA-256: `cd39bb667f47efebbf217385ddffa3dbae4b3228df9545af533351578f36d3af`.

## Continuous camera motion

Six accepted 60-second captures at 5120×2880, 32 chunks, Vsync Off, default shared targets.
The camera is sampled before each extraction from a 30-second sinusoid: ±12° yaw and 3×2-block
translation about the copied forest/river anchor. Old/current pose is kept consistent; F8’s
teleport route is disabled for these captures. No screenshot readbacks occur during measurement.
Three native runs alternate with strength 25/33/50%; AA remains enabled for reduced scenes.

| Run | CPU FPS | Mean ms | p95 ms | Scaler-batch median ms |
|---|---:|---:|---:|---:|
| motion01-native | 97.2 | 10.288 | 12.514 | unavailable |
| motion02-sr25 | 106.3 | 9.403 | 12.052 | 2.937 |
| motion03-native | 95.5 | 10.467 | 13.704 | unavailable |
| motion04-sr33 | 114.2 | 8.754 | 10.957 | 2.666 |
| motion05-native | 97.9 | 10.212 | 12.859 | unavailable |
| motion06-sr50 | 128.8 | 7.764 | 9.956 | 2.282 |
| display01-immediate | 98.0 | 10.202 | 12.694 | unavailable |
| display02-fifo | 59.7 | 16.742 | 17.290 | unavailable |
| display03-sr25-fifo | 59.6 | 16.788 | 17.219 | 3.188 |

All nine accepted captures have consistent dimensions, active gameplay/focus, no census, zero
shader compiles and zero SR failures/recoveries: **44,929 captured frames**. Native motion repeats
range 95.5–97.9 FPS. Pose logs confirm the 24° yaw span and small per-frame steps. Scaler timing
is still asynchronous latest-completed AA+FX+copy, not total GPU frame time.

macOS memory pressure WARNING occurred during SR25 and later returned NORMAL. Several FIFO
attempts lost focus and were rejected; none are in the accepted table. The test-only observer’s
app activation fixed the GLFW-window-focus/app-activation distinction for the final display checks.
Treat the one-per-strength motion timings as exploratory, not three alternating release pairs or
a statistical proof of speedup. Spectator prep suppresses effective dynamic lights.

## Presentation timestamps

Three shorter captures use a test-only native observer on the ordinary drawable before presentation.
It registers `addPresentedHandler` and records `presentedTime`, without replacing the presentation
owner or altering its schedule. Effective IMMEDIATE/FIFO modes are asserted. Display samples include
the five-second countdown and export tail at a constant mode. These are on-screen update timestamps,
not input-to-display latency. Two callbacks per run remained pending at export.

| Mode | Unique displayed times | Zero/skipped timestamps | Median interval ms | p95 interval ms | Update rate Hz |
|---|---:|---:|---:|---:|---:|
| display01-immediate | 3122 | 317 | 10.2015 | 19.9078 | 88.76 |
| display02-fifo | 2096 | 0 | 16.6675 | 16.6676 | 59.77 |
| display03-sr25-fifo | 2091 | 0 | 16.6675 | 16.6676 | 59.63 |

FIFO therefore delivers approximately 60 Hz and a 16.67 ms median interval in this session.
IMMEDIATE updates faster but less evenly and skips some submissions. Its update rate does not
measure monitor refresh rate. The earlier CPU-only Vsync pilot was inconclusive; this focused
timestamp test supplies the missing evidence. No display-link/frame-generation pacer was added.

## Dense-scene p95 investigation

The previous same-position dense waypoint (stage 13, roughly 19,000 draws/frame) was partitioned
into its slowest 5% and remaining frames. In SR25, mean command-buffer creation time is 13.53 ms
in slow frames versus 3.97 ms otherwise. SR33 is 12.84 versus 3.58 ms. Frame/create-span correlations
are 0.991 and 0.990. Latest-completed scaler-batch samples remain similar between these groups;
drawable waits, fence waits and GC do not show comparable increases. Native has the same stall pattern.

This locates the measured tail in CPU calls to `[queue commandBuffer]`. Queue/driver backpressure is
a hypothesis; the precise internal cause is unproven. Increasing queue depth could hide stalls by
adding latency, so no unmeasured queue-size change was made. The lighter moving forest/river scene
does not reproduce the dense-scene p95 regression. BUG-031 records the unresolved issue.

## Visual evidence and temporal readiness

Four separate ten-second camera excerpts contain 42/40/40/40 cropped PNG snapshots at native
and 25/33/50%. Readback and PNG encoding reduce actual sampling to about 4 Hz, despite a 10 Hz
request. The 1920×900 centre/lower ROI covers near/mid-range leaves, water and terrain and excludes
most of the distant horizon. Representative beginning and later frames were inspected: orientation
and scene composition are intact, and 50% visibly softens more fine texture/cutout detail. The
ordered snapshots preserve moving-scene evidence, but cannot prove high-frequency shimmer is
solved or provide a quantitative temporal-stability verdict. Animated water is not a matched pixel
reference. MP4s are illustrative 4-fps playback of those snapshots, not real-time screen recordings.

Temporal prototype development can begin from this stable spatial reference and working measurement
harness. An actual M4 Pro probe confirms `MTLFXTemporalScalerDescriptor.supportsDevice` and successful
creation with RGBA8 colour/output, Depth32Float and RG16Float motion at 192×108 → 256×144. This is
support/descriptor validation only: no temporal frames were encoded. SDK 27 headers specify backward
current-to-previous pixel motion in top-left coordinates, jitter offsets and explicit history reset.

The production backend still has no per-object motion texture producer, projection jitter or temporal
history/reset contract, and the SDR colour/exposure convention needs pixel validation. Those are
initial implementation work, not already-complete inputs. Independently moving entities, animated
meshes, hands, particles and translucency must be covered before temporal output is accepted.
The test does not select a release winner or complete Phase 7. Spatial remains the active path.

## Verification and artifacts

All five gates were rerun successfully after the experiments: canonical mod build, validated native
smoke, shader inventory 87/87 + 9/9 without diagnostics, validated pixel render check (194 assertions),
and standalone suite. Logs and per-run summaries/pose/display counts are in this directory.
Raw CSVs, presentation samples, console logs and ordered PNG/MP4 excerpts are preserved under
`build/reports/motion-check/`; manifest hashes record per-capture files. Harness/probes/analyzers
are in `tools/metalfx_benchmark/` and excluded from the production mod JAR.

Apple API context: [MetalFX temporal upscaling](https://developer.apple.com/videos/play/wwdc2022/10103/)
and the installed SDK `MTLFXTemporalScaler.h` / `MTLDrawable.h` (27.0).
