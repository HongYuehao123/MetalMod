# Spatial MetalFX comparison harness

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
