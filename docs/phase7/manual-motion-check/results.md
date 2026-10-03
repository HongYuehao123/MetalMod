# User-controlled treetop performance capture — 2026-10-03

The user reproduced the reported slowdown by manually controlling the camera in a disposable copy
of their saved world, at the saved position near (2392, 128, -2648). No automated pose controller or
world preparation ran. An earlier automated comparison was explicitly cancelled by the user and
is **not evidence for this diagnosis**. The user confirmed the same FPS drop occurred in this capture.

Production HEAD: `0872b35`. Production JAR SHA-256:
`2743066fc293952ff225cf64782d3ac128a01128bfd444ad5027df8bc9173567`.
No production Java/native/render/shader source, installed mod, original save or original config
was changed; no commit was made. The only code changes are in the separate diagnostic add-on.
Existing temporal settings were refreshed after startup, without changing the selected values.

## Measured reproduction

- 5120×2880 output, temporal strength 50 (2560×1440 scene), 32-chunk render distance, Vsync On; copied test simulation distance 5.
- 60-second F8 recording: **3064/3064 focused gameplay frames**, no menus or pauses.
- CPU presentation-boundary average **51.1 FPS**, median 18.830 ms, p95 **26.723 ms**.
- Slowest 60-frame window: **44.54 FPS**, starting about 2.22 seconds into capture.
- Passive real-drawable `presentedTime` observer: capture-window update rate **51.06 Hz**;
  median **16.6675 ms**, p95 **33.3350 ms**. **530/3063** intervals exceed 25 ms.
  Missed 60 Hz refreshes therefore appear on screen, not just in the FPS label.
- Temporal helper submission (decode, inputs, MetalFX, encode, history copies): mean **11.084 ms**,
  p95 **12.328 ms**. It is asynchronous latest-completed telemetry, **not total GPU frame time**.
- Zero shader compiles, zero new FX failures, zero published dynamic lights and zero light rejection
  regions. There are 10 GC collections: occasional isolated hitches, not the sustained slowdown.

Pose and F8 samples align exactly using all **3064 matching helper GPU values**, offset 242 samples
(the passive pose log also includes the five-second countdown and a short export tail).
Display timestamps are trimmed to the same frame window using their common monotonic time base.

## What changes when FPS falls

Exclude the first ten seconds to remove the initial large mesh uploads. Group the remaining frames
by actual submitted draw count rather than assuming that movement itself is expensive:

| Settled views | Frames | Mean draws | FPS | Helper GPU ms | Command creation CPU ms | Other CPU interval ms |
|---|---:|---:|---:|---:|---:|---:|
| Below 4000 draws | 1022 | 2626 | 59.74 | 10.866 | 0.44 | 4.88 |
| Above 6000 draws | 1129 | 6784 | 46.01 | 11.332 | 3.25 | 7.84 |

Across the full capture, draw-count/frame-time correlation is **0.632**, versus **0.073** for the
helper GPU duration. Below-4000 and above-6000 views average about 2636 and 6815 draws respectively;
command creation rises from 0.43 to 3.26 ms and the remaining CPU interval from 4.93 to 7.97 ms.
Those full-capture values are illustrative; exact settled-group values are in `analysis.json`.
Average settled mesh upload volume is only about 1.4/2.0 KB per frame respectively; newly generated
chunks are not necessary to reproduce the sustained difference.

Frames longer than 25 ms spend an average **19.01 ms in drawable acquisition** versus 10.91 ms in
frames below 18 ms. Drawable acquisition wraps `CAMetalLayer.nextDrawable`; it includes ordinary
FIFO backpressure. This identifies the dominant recorded wait, not an extra CPU computation to
optimize away. The backend submits about 63 command buffers per frame in this scene.

JFR sampling also finds terrain draw preparation among the visible Java costs (189 inclusive
`LevelRenderer.prepareChunkRenders` samples from 1683 render-thread samples). Its recorded native
stacks stop inside FFI stubs, so JFR cannot assign the driver's internal GPU/queue wait precisely.

**Interpretation:** changing the view exposes more forest geometry and increases terrain submission
work and driver backpressure. Temporal already spends a steady ~11 ms of the 16.67 ms GPU budget
before accounting for the rest of the frame; the heavier views lose 60 Hz refreshes. The measurement
supports a scene-workload/frame-budget problem, rather than a movement-triggered spike in the
measured temporal helper, light collection or shader compilation. CPU/GPU overlap means these
numbers must not simply be added. Total GPU-frame/stage traces and same-motion spatial/native
controls are still needed to quantify the precise optimization and distinguish GPU execution from
driver scheduling. No claim that one specific temporal kernel or MetalFX itself is proven responsible.

This differs from the earlier BUG-031 dense underground trace: frame/command-creation correlation
here is only 0.181, rather than ~0.99. That earlier explanation must not be substituted for this capture.

## Tree-edge shaking

The user also reports edge shaking **after holding the camera still**. This is recorded as an open
visual defect, not diagnosed from the timing CSV. All samples remain in temporal mode.
There are 23 history resets coinciding with sharp per-frame turns (~26–47° yaw in this capture),
consistent with the current L1 rotation-matrix reset threshold. Such resets can explain loss of
history during rapid turns, but **cannot explain persistent stationary shaking** on their own.
The previous slow sinusoidal/offline static test therefore does not establish stability for this view.
A stationary image sequence from this exact view, with native/spatial controls and jitter/history
counters, is required to distinguish contour displacement from foliage sampling shimmer. No video
or pixel sequence was taken during the performance recording; synchronous screenshot readbacks
would have contaminated its timings.

## Reproduce / inspect

Raw `frames.csv`, `manual-motion.csv`, `display.csv`, `summary.txt` and `analysis.json` are alongside
this report. Regenerate analysis with:

```sh
python3 tools/metalfx_benchmark/analyze_manual.py docs/phase7/manual-motion-check
```

The optional test add-on compiles with `tools/metalfx_benchmark/build_addon.sh`, against the real
client API; it is excluded from the production JAR. `run_manual.py --world WORLD --output NEW_DIR`
creates a disposable copy, retains the user's saved scene/inventory and upscaling preference (32 chunks, full screen, simulation distance 5, muted sound), and launches a passive
recorder. It never controls the camera or executes world commands. Once `manual-ready.txt` exists,
create `capture.start` in that copied game directory to start the ordinary five-second countdown and
60-second F8 recording. The diagnostic log adds camera/temporal counters and asynchronous display
timestamps. Capture output is announced in `manual-complete.txt`. All automated benchmark flags
remain disabled. GPU validation is disabled; screenshots are excluded from timing.
