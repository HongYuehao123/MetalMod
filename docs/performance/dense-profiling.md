# Dense terrain investigation — 2026-10-04

This is the historical pre-Sodium investigation. The later
[post-Sodium GPU and presentation profile](sodium-gpu-profiling.md) identifies the hosting
60-Hz display and measures the current optional adapter with Fluid Culling Default.

The original route's waypoint 13 now reproduces approximately 19,200 draws per frame on the
current backend. The next substantial optimization target is terrain visibility/geometry and
submission organization; this investigation does not implement a Sodium adapter or claim a new
production FPS improvement. Existing batching and buffer-offset updates remain enabled.

## Throughput comparison

A disposable copy of the prior `Spatial benchmark` world holds the saved underground waypoint
at `(1672.2309, 25.1744, -2539.3662)`, yaw `-603.2986`, pitch `7.50003`. Render distance 32,
simulation distance 5, Vsync Off / effective IMMEDIATE, windowed Retina output **5120×2664**,
4 GiB Java heap. The original investigation used 5120×2880 and an older renderer, so these
numbers must not be presented as a matched comparison with that historical build. Spectator
preparation removes moving lights/entities; this is a heavy submission/terrain fixture.

| Accepted run | FPS | p95 frame ms | Render-thread CPU ms |
|---|---:|---:|---:|
| Native batching off, first | 56.00 | 32.05 | 11.681 |
| Native batching on | 56.83 | 29.60 | 11.336 |
| Native batching off, last | 55.36 | 32.37 | 11.270 |
| Spatial 25, batching off | 56.09 | 31.03 | 11.072 |
| Spatial 25, batching on | 56.34 | 30.33 | 11.058 |

An additional native-on capture was sampled with `/usr/bin/sample` during measurement. It is
retained and marked `INSTRUMENTED.txt`, excluded by both throughput and stall analyzers. The
clean native comparison is **off/on/off, not complete ABBA**: approximately 2.07% higher FPS
and 1.21% lower CPU time against the two off means. One enabled capture is insufficient to
establish a reliable small gain. Spatial is a single pair. No accepted capture contains
unfocused/paused/menu intervals, pipeline compilation, active census or MetalFX failures/recovery.

Command-buffer creation in native captures now averages **0.060–0.181 ms/frame**. In the slowest
5%, it averages **0.048–0.533 ms**; frame/create correlations range from -0.414 to 0.290. The
earlier 13 ms creation stall is not reproduced, including with batching disabled. Other current
backend changes, the fixture differences and driver/session state prevent attributing that
absence solely to batching or closing BUG-031.

Slow-frame drawable waits average **20.3–21.0 ms** in the clean native captures. Observed display
timestamps have 16.67 ms median and 33.33 ms p95 intervals; native displayed update rates are
55.0–56.5 Hz. This is presentation cadence, not monitor capability or input latency. The trace
lists a 120 Hz built-in display and a 60 Hz external display, but does not establish which
display hosted the game. Both GPU pressure and presentation scheduling need consideration.

## Separate CPU/GPU diagnostics

A separate copied-world session enables render-stage counters and records a 20-second Metal
System Trace during native rendering. Its FPS is not part of the throughput comparison.

The exported trace is filtered to the test Java PID and top-level GPU execution intervals.
Instruments associates work with GPU frame IDs; those IDs are not F8 CPU frame IDs. Excluding
the first/last associated frames leaves **1,122 frames**, with one unassigned interval omitted:

| Instrumented GPU metric | Mean ms | Median ms | p95 ms |
|---|---:|---:|---:|
| Associated vertex intervals, sum | 14.59 | 14.91 | 16.14 |
| Associated fragment intervals, sum | 6.46 | 6.45 | 7.08 |
| Union of associated GPU execution intervals | 17.32 | 17.47 | 18.67 |
| First-to-last execution span | 28.41 | 27.51 | 37.69 |

Vertex/fragment stages overlap. The union merges intervals within each associated frame; the
span includes scheduling gaps. Different GPU frames can also overlap, so these values must not
be summed into latency or used as an uninstrumented throughput prediction. Trace attachment
and counter sampling add overhead; they support workload attribution rather than a release
performance claim.

F8 counters independently report accumulated vertex workload near 15–16 ms per polling interval.
Spatial 25 reduces fragment work from about 7.51 to 5.76 ms while vertex work remains near
15.85 versus 15.39 ms. Native averages 19.84 completed passes and 2.01 dropped passes per poll;
spatial averages 20.57 completed and 1.17 dropped, with zero invalid samples. These asynchronous
partial-coverage readings are not frame-aligned GPU timings or a matched native/spatial proof.

The sampled native-on capture includes Apple's indexed-draw encoding and argument-state work.
Java JIT frames are largely unresolved; the short sample does not quantitatively isolate the
Java chunk renderer, meshing or culling costs. The initial Time Profiler trace was taken during
warmup with saved spatial strength 50 and is retained separately.

## Next implementation target

The evidence supports reducing terrain work rather than changing queue depth or enabling fused
FFI calls. Start the optional Sodium Metal adapter with correct direct draws and region parameters,
then measure visibility/arena/meshing behavior against the same vanilla Metal fixture. Merely
reducing CPU crossings leaves Metal's per-draw encoding and terrain vertex workload present.
Any independent GPU culling path needs a section/geometry contract and conservative visibility
tests before it can skip draws. This spectator fixture alone is insufficient to validate culling.

Preserve stable geometry/material identity for future acceleration structures, and validate
MetalFX projection jitter, temporal motion/reactive coverage, lighting and frame-generation
composition on the new terrain path. Existing vanilla validation does not establish Sodium
compatibility. See [the tested Sodium incompatibility and integration gates](sodium-compatibility.md).

## Reproduction, tools and verification

The test-only runner now supports a read-only world source, explicit route waypoint, fixed
camera and separate diagnostic mode. Throughput analysis rejects GPU profiling, resizing,
compilation/census and MetalFX failures/recovery; instrumented captures are disclosed/excluded.
`analyze_metal_trace.py EXPORT_XML --pid PID` resolves Instruments XML references and merges
overlapping execution intervals rather than adding stages.

All five offline gates pass on the current production code: canonical build, **919 native
checks**, static **87/87**, post **9/9**, Render Check and standalone tests. The test add-on
compiles against the real client API and both new modes completed successfully. The rebuilt
production JAR has byte-identical ZIP entry contents to the measured JAR; only packaging
timestamps change its SHA-256. No normal Minecraft instance, world, config or installed JAR
was replaced.

Evidence: `build/reports/dense-profiling/` contains raw captures, exclusions, comparison/stall/
display summaries, separate diagnostic counters, CPU/Metal traces, GPU interval analysis and
gate logs. `verification-summary.json` records both JAR hashes and scope. BUG-031 remains open;
BUG-035's temporal treetop fixture and BUG-045's transient FG failures are not resolved here.
