# Spatial MetalFX comparison harness

Performance baseline policy (2026-10-05): `run_manual.py` now defaults to **external display,
Vsync Off, Unlimited FPS, native-only rendering**, with spatial/temporal upscaling and frame
generation off in the disposable instance. Native submission ABBA has four captures; fixed-renderer
mode has one native capture. `--compare-spatial` explicitly restores spatial comparisons described
below. Historical feature-validation tools and archived results remain separate. Option 260 is
the real client's Unlimited sentinel, not a 260-FPS cap. `--render-distance` records the distance;
32 retains the dense stress fixture, while mcopt's linked demo labels distance 16.

This optional Fabric test add-on drives the existing F7 route and F8 capture APIs. It is **not
included in the production mod**. Build the normal mod first, then run `./tools/metalfx_benchmark/build_addon.sh`.
Install `build/metalfx-benchmark/spatial-benchmark.jar` and the built MetalMod JAR only in a
**disposable, isolated instance with a copied world**. Capture preparation changes game rules,
weather/time, player mode and entities. Never install this add-on in the normal test instance.

Create an empty `.metalmod-benchmark-copy` marker in the isolated game directory; the add-on
refuses to start without it. Copy the desired route to the isolated instance's `debug/metalmod/routes/minecraft.overworld.json`.
Launch that world using the normal Fabric client arguments plus `-Dmetalmod.benchmark=true`.
The add-on uses the loaded world, warms the full route, runs twelve 60-second captures, then exits.
It requests window focus at run boundaries, rejects captures containing any unfocused frame,
and retries them up to three times. It also checks the effective surface present mode before capture. Keep the game in front during measurement. Remove the add-on after testing.

The order is native, 25/33/50% strength with AA, reverse 50/33/25%, native, two 25% strength
runs without AA, then native/SR25 with Vsync enabled. The first ten runs disable Vsync and
use Minecraft's unlimited FPS setting. Each setting gets a 20-second warmup and the capture's
five-second countdown. Screenshots are taken outside measurement. Screenshot export compensates
for vanilla's bottom-up readback assumption only in this test add-on.

Run `python3 tools/metalfx_benchmark/analyze.py /path/to/isolated-instance/debug/metalmod`.
The analyzer requires consistent output resolution, active gameplay/focus, no census, no shader
compilation, and zero SR failures/recoveries. It saves `analysis.json`, including whole-route
statistics and settled waypoint statistics after excluding each waypoint's first two seconds.
Review the raw F8 metadata to confirm Vsync, render distance, route and scene dimensions.

`sr_gpu_ns` is the latest completed AA + MetalFX + output-copy command-buffer span. Samples
are asynchronous and may repeat across CPU-frame rows. It is not whole-frame GPU time and
must not be summed with other command-buffer spans. Native/bypass/recovery reports -1.

This route harness does not interpolate movement. A route consisting of holds and teleports
provides reproducible throughput and static comparisons; it does not prove continuous-motion
quality, ghosting behavior, latency or dynamic-light performance in spectator mode.

## Smooth-camera check

Use `-Dmetalmod.motionBenchmark=true` instead of `-Dmetalmod.benchmark=true`. The disposable
clone marker remains mandatory. The first saved waypoint is the anchor. A test-only Mixin sets a
render-time sinusoidal camera pose before extraction: 30-second period, ±12° yaw, 3×2-block
translation. Old/current pose is set consistently to avoid a second tick interpolation. F8's
teleport route is temporarily moved aside and restored after completion. Eight captures alternate
native and reduced strengths, then validate FIFO native/SR25. `motion.txt` records pose-step
statistics and the actual configured surface mode; no screenshots are taken during these captures.

After timing, four separate ten-second quality excerpts are recorded at native/25/33/50%.
The centre/lower 1920×900 terrain ROI is saved at nominal 10 Hz under `motion-quality/`.
Readback/PNG costs affect these excerpts; they are visual evidence only, not performance samples.
Use the real camera motion and saved images together; pose counters alone do not establish image
quality. External memory-pressure events must be preserved and qualify performance conclusions.
This experiment covers camera motion in the copied forest/river scene, not independently moving
objects or temporal history correctness.

`profile_stalls.py ROOT [--stage 13]` compares the slowest 5% of frame intervals with the remaining
frames and correlates frame time with measured command-buffer creation calls. These are CPU API
spans. They locate stalls; they do not prove which Metal driver/GPU scheduling mechanism caused them.
The optional `temporal_probe.mm` checks support/descriptor creation only; it does not encode temporal
frames or supply a working motion/history contract.

## Actual presentation timestamps

Build the optional observer separately:

```
xcrun clang++ -std=c++17 -fobjc-arc -dynamiclib tools/metalfx_benchmark/display_probe.mm \
  -framework Metal -framework AppKit -o /tmp/metalmod-display-probe.dylib
```

In the disposable instance, add `-Dmetalmod.displayProbe=/tmp/metalmod-display-probe.dylib`
and `-Dmetalmod.displayCheckOnly=true` with the motion benchmark flag. This runs three shorter
30-second F8 captures (native IMMEDIATE, native FIFO, SR25 FIFO), then the four visual excerpts.
The test-only Mixin observes the existing drawable before ordinary presentation; it does not own,
replace or reschedule presentation. Native `addPresentedHandler` callbacks store actual
`presentedTime` values in a bounded, generation-guarded buffer. Zero timestamps represent skipped
or unpresented drawables. Samples include the countdown/export tail at the same mode. Callbacks
still pending at export are counted separately by submitted-versus-callback totals.

The observer also activates its own application at test boundaries before GLFW focus requests;
this addresses macOS app activation independently of making a GLFW window key. Run
`python3 tools/metalfx_benchmark/analyze_display.py /path/to/isolated-instance/debug/metalmod`.
It reports unique displayed times, interval distributions and zero timestamps. Displayed update
rate in IMMEDIATE mode is not a monitor-refresh-rate measurement, and neither mode's data measures
input-to-display latency. The observer and camera Mixins are never shipped in MetalMod.

## User-controlled reproduction

`python3 tools/metalfx_benchmark/run_manual.py --world WORLD --output NEW_DIRECTORY` launches
an isolated copy of a saved world from the normal test instance, using its saved camera/inventory
and upscaling preference, 32 chunks, full screen and simulation distance 5. It adds only passive
camera/history/display recording. No automatic camera movement or world commands run.
After `manual-ready.txt` appears, create `capture.start` in the copied game directory. The ordinary
F8 five-second countdown and 60-second capture run while the human demonstrates the issue.
`manual-complete.txt` names the export. No screenshots occur during timing. Compile/run requires
host Minecraft/GPU access. Analyze with `analyze_manual.py CAPTURE_FOLDER`; the analysis requires
exact pose/frame telemetry alignment. See `docs/phase7/manual-motion-check/results.md` for the
first human-controlled recording and its limitations.

## Dense waypoint profiling

`run_manual.py --submission-benchmark --source-world PATH --world COPY_NAME
--anchor-route ROUTE_JSON --anchor-waypoint 13 --static-camera --output NEW_DIRECTORY`
copies the specified source world and holds a selected Overworld waypoint. The source is
read-only; configurations/libraries still come from the normal test instance. Without the
overrides, the saved player and smooth camera remain the default. Comparison metadata records
the exact position, camera mode and packaged JAR hash.

`--diagnostic-only --gpu-stage-timing --compare-spatial` runs two enabled native/SR25 captures for profiling.
These are diagnostic measurements, not the six-capture throughput comparison. External CPU/Metal
tracing must run separately from throughput measurements. Mark any accidentally instrumented
throughput capture with `INSTRUMENTED.txt`; `analyze_submission.py` excludes it and reports the
remaining comparison counts rather than claiming a complete ABBA.
The add-on automatically marks declared diagnostic/counter captures with `INSTRUMENTED.txt`.
The display probe logs the game window's hosting screen, display ID, maximum refresh rate,
window/screen bounds and backing scale when activating outside timed captures. Screen capability
and observed `presentedTime` cadence are separate measurements; neither establishes input latency.

Export the `metal-gpu-intervals` table from a saved Instruments Metal System Trace, then run
`python3 tools/metalfx_benchmark/analyze_metal_trace.py EXPORT_XML --pid PID`. It filters to
the chosen process and top-level execution intervals, excludes unassigned work and boundary
frames, and reports both overlapping interval union and first-to-last span. Instruments GPU
frame IDs are not F8 CPU frame IDs. Stages and separate frames can overlap; instrumented
results are workload diagnostics, not release throughput or input latency measurements.


### Optional Sodium renderer comparison

`run_manual.py --submission-benchmark --renderer-comparison vanilla` captures native using one
fixed renderer in a fresh JVM; it does not toggle the submission property. Add `--compare-spatial`
only for an explicit native/spatial-strength-25 feature comparison.
For Sodium, select `--renderer-comparison sodium` and supply the tested Sodium and optional
MetalMod adapter JARs with two `--extra-mod` arguments. Use the same `--source-world`,
`--anchor-route`, `--anchor-waypoint`, `--static-camera` and core JAR for all runs.
Launch in vanilla/Sodium/Sodium/vanilla order, each in a new output directory, then run:

```sh
python3 tools/metalfx_benchmark/analyze_renderer.py /path/to/a1 /path/to/b1 /path/to/b2 /path/to/a2
```

The analyzer enforces clean captures and matching camera, source, resolution and core JAR,
rejects instrumentation/compile/recovery/menu/focus changes, and reports both CPU and frame-time
percentiles. A terrain image is captured only after each timed export. Images and quality
fixtures must be reviewed independently; lower CPU usage does not establish rendering parity.
See `docs/performance/sodium-compatibility.md` for accepted evidence and current parity limits.

`--sodium-options /path/to/sodium-options.json` copies an explicit options override into the
new diagnostic instance and records its SHA-256. Use this to isolate a quality option while
keeping the same adapter/backend; the normal installation is never written. Lava parity
investigation: `quality.hidden_fluid_culling=false` (Fluid Culling = Default).

### Internal/external display check

`--display internal` moves only the disposable game window to the built-in screen and fits
its visible frame before the 60-second warmup. `--display external` selects the first external
screen and preserves the requested window size. Missing requested screens stop the test.
`--window-width W --window-height H` sets initial logical content dimensions (default 2560×1440);
the window manager can constrain them, so compare the actual F8 pixel dimensions. All screen
placement happens outside timed captures. The screen log includes a `builtIn` flag.

To isolate refresh/presentation from resolution, run the internal test first, then choose an
external window size yielding the same actual output dimensions. Analyze with:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/metalfx_benchmark/analyze_display_comparison.py INTERNAL_COPY EXTERNAL_COPY
```

This requires matching renderer/core/mod/options hashes, camera/source and actual dimensions,
plus consistent correct hosting-screen snapshots. One launch per screen is an exploratory
comparison, not ABBA. Run `analyze_display.py` separately and disclose zero timestamps;
rendered FPS and unique observed presentation updates are different measurements.

## Bounded offscreen completion comparison

Add `--offscreen-comparison` to the native-only fixed Sodium submission command. This optional
add-on runs present/offscreen/offscreen/present without changing core production code or submission
properties. Both conditions enforce at most two pending rendered frames and insert completion
fences. Offscreen retains frame resource lifecycles and omits drawable/blit/presentation.
Use `analyze_offscreen.py <copied-game-directory>`; general analyzers reject its instrumented captures.
Completed FPS is not displayed FPS. The offscreen window retains its last displayed image.
See `docs/performance/offscreen-completion-20261005.md` for measured results and limitations.
