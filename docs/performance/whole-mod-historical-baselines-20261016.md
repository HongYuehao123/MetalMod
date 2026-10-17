# Whole-mod historical performance appendix — 2026-10-16

This appendix inventories **previously recorded evidence**, reviewed on 2026-10-16. It does not
report new benchmark executions or certify the current build. Fresh whole-mod test results belong
in the accompanying current-run report. The measurements below are useful controls and leads for
new experiments; they must not be combined into one performance score.

## Measurement boundaries

- The recorded machine is an **Apple M4 Pro**. The October reports identify **macOS 27.0.1**,
  Minecraft **26.2**, JDK **26** and Java release **22**. Historical artifact versions differ.
- FPS in ordinary captures is the reciprocal of the measured mean CPU frame/presentation-boundary
  interval. It includes waiting and is not isolated GPU execution time or necessarily the number
  of complete images scanned out by the display. Minecraft's numeric maximum-FPS setting **260
  means Unlimited** in this client; older documents sometimes call it a cap.
- Render-thread CPU milliseconds measure consumed thread CPU time, excluding blocked waits. They
  include game work, not just Metal encoding. These samples can include the F8 countdown while
  frame statistics exclude it, so their boundaries are not identical.
- `sr_gpu_ns` and temporal helper durations are asynchronous latest-completed submission spans.
  They may repeat across F8 rows and are **not total GPU frame time**. Instrumented vertex/fragment
  intervals overlap; their sums must not be added to CPU intervals or treated as input latency.
- Offscreen completion rates count bounded GPU-completed work, including the final drain. They
  are neither displayed FPS nor isolated GPU duration. The window receives no new images during
  the offscreen condition.
- Positive `MTLDrawable.presentedTime` events describe presentation host timestamps. With Vsync
  Off their rate can exceed the screen's nominal refresh rate. They do not measure complete
  scanouts, physical refresh or input-to-photon latency. Zero/dropped timestamps and observer
  coverage must remain disclosed.
- Screens, output dimensions, source-world snapshots, settings and renderer versions vary between
  reports. In particular, the October 16 late-acquisition experiment copied the normal saved world
  after the older private fixture disappeared. Its roughly 94 FPS baseline does not supersede or
  directly control the approximately 60 FPS sessions in other reports.
- Most modern performance captures use one Overworld scene at **render distance 32 (RD32)**.
  Spectator preparation suppresses effective dynamic lights even when their configuration is On.
  Such captures do not measure active-light cost. Short pilots and single pairs are identified below;
  none establishes every-world performance, long-term thermals or broad release acceptance.

## Native rendering, storage and scene complexity

### Recorded route — 2026-09-23

The same 14-waypoint Overworld route was captured at **5120×2664, RD32, Vsync Off**, with no
performance renderer mod. This is an older backend and one ordered set of three runs, not ABBA.

| Measurement | Metal shared, default | Metal private targets | Vulkan |
|---|---:|---:|---:|
| Surface waypoint mean, about 6.6k draws | 8.91 ms | 9.72 ms | 8.65 ms |
| Underground waypoint mean, about 19.2k draws | 15.06 ms | 16.40 ms | 21.09 ms |
| Whole-route FPS | 81.1 | 74.3 | 69.2 |
| Whole-route mean | 12.331 ms | 13.454 ms | 14.449 ms |
| p95 | 19.230 ms | 24.912 ms | 25.036 ms |

This supported shared storage and the original parity milestone. It is not a current-build
Metal/Vulkan comparison. The earlier manually traversed comparisons had workload and time-order
confounds and should not replace the routed result. A separate Nether/private-storage capture
averaged **119.7 FPS** at roughly **11.3k settled draws**, but had no matched control. No exhaustive
End performance pass was established. Source: [TESTING.md, routed comparison](../../TESTING.md#what-the-routed-comparison-measured)
and [Phase 6 baseline](../phase6-plan.md).

### Submission and binding experiments — 2026-10-04

These experiments use different scenes and cannot be multiplied together to predict cumulative
performance. Existing defaults include ordinary submission batching and buffer-offset updates.

| Experiment | Recorded result | Decision and scope |
|---|---|---|
| Ordinary submission batching | Native FPS 54.46/54.49 Off versus 59.59/59.46 On; p95 about 33.3→18.2 ms; +9.27% throughput | Enabled. Smooth camera, 5120×2664/RD32, about 7.5k draws, native ABBA. Spatial25 single pair 57.23→59.96 FPS. |
| Dense waypoint batching repeat | Native Off/On/Off 56.00/56.83/55.36 FPS; p95 32.05/29.60/32.37 ms | Incomplete ABBA: an instrumented On capture was excluded. The roughly 2.07% difference is exploratory. |
| Buffer-offset updates | Native CPU 6.319→6.190 ms, 2.04% lower; FPS difference -0.20% near 60 | Enabled for scoped CPU headroom. Spatial25 single pair CPU 6.146→6.018 ms. No material tail improvement. |
| Fused indexed calls | Crossings about 22,783→8,255/frame, -63.8%; native CPU difference 0.03%; FPS -0.17% | Remains Off. Reduced call count did not establish meaningful CPU/FPS benefit. |

Sources: [submission batching](submission-batching.md), [dense profiling](dense-profiling.md),
[buffer offsets](buffer-offsets.md), [fused draws](fused-draws.md). Raw accepted/rejected captures
remain in the corresponding directories under `build/reports/`.

## Spatial upscaling

### Held-position route — 2026-10-01

**5120×2664, RD32, shared targets**, 60-second captures in a disposable copied world. Two
reversed-order repetitions cover native and each AA-enabled strength. Strength means dimension
reduction: 25/33/50 render at 75/67/50% linear scene scale. UI remains at output resolution.

| Mode | Two-run mean FPS | Individual p95 values | Gain versus native |
|---|---:|---:|---:|
| Native | 73.9 | 21.599 / 21.404 ms | — |
| Spatial25 + AA | 77.5 | 23.707 / 23.610 ms | 4.8% |
| Spatial33 + AA | 81.9 | 22.832 / 22.790 ms | 10.8% |
| Spatial50 + AA | 86.6 | 20.528 / 20.462 ms | 17.2% |

Spatial25 without input AA averaged **79.0 FPS**; the completed AA+scaler+copy batch median
fell from about **2.68 to 2.24 ms**. This did not establish equivalent quality, so it did not justify
disabling AA. Strength25/33 improved mean throughput while worsening route p95. Strength50
renders only 25% of native scene pixels and visibly loses more fine detail.

This was a two-repeat pilot, not three alternating release pairs. Holds/teleports cannot establish
continuous-motion quality; spectator mode suppresses lights. Earlier CPU-only Vsync readings from
this pilot did not measure actual display cadence. [Full results](../phase7/spatial-benchmark/results.md).

### Smooth forest/river camera — 2026-10-02

**5120×2880, RD32, shared targets, Vsync Off**. The test camera follows a 30-second sinusoid,
±12° yaw and 3×2-block translation. Three native captures alternate with one per reduced strength.

| Mode | FPS | p95 frame interval |
|---|---:|---:|
| Native repeats | 97.2 / 95.5 / 97.9 | 12.514 / 13.704 / 12.859 ms |
| Spatial25 + AA | 106.3 | 12.052 ms |
| Spatial33 + AA | 114.2 | 10.957 ms |
| Spatial50 + AA | 128.8 | 9.956 ms |
| Native FIFO display check | 59.7 | 17.290 ms |
| Spatial25 FIFO display check | 59.6 | 17.219 ms |

The single-per-strength comparisons are exploratory. A memory-pressure WARNING occurred during
Spatial25 and subsequently returned to NORMAL. Effective lights were suppressed. Separate snapshots
sampled at only about 4 Hz; they do not prove high-frequency shimmer is absent. FIFO positive
presentation timestamps had 16.67 ms median/p95 intervals. Vsync-Off event rate was higher but
irregular and must not be interpreted as the panel refresh rate. [Full results](../phase7/motion-check/results.md).

## Temporal reconstruction and frame generation

| Historical fixture | Recorded result | Interpretation |
|---|---|---|
| Temporal implementation validation, Oct 3 | 385 checks; 18,227 frames; all strengths, native hand/HUD, lights, motion, resize/reload, dimensions and reconnect | Correctness evidence. Six 20-second native/spatial/temporal samples at 3842×2162/strength50 stayed near 60 FPS, with focus loss in some samples; explicitly not throughput acceptance. |
| User-controlled treetop reproduction, Oct 3 | 5120×2880, temporal50, RD32, FIFO: 51.1 FPS; median 18.830 ms; p95 26.723 ms; worst 60-frame window 44.54 FPS | All 3,064 captured frames were focused gameplay. Positive presentation timestamps confirm missed 60-Hz intervals. |
| Same treetop, settled workload groups | Below 4k draws: 59.74 FPS; above 6k draws: 46.01 FPS | Temporal helper averages 10.866/11.332 ms in these groups. These spans are not whole-frame GPU time. Heavier views also raise CPU submission/backpressure; precise GPU/driver attribution remains open. |
| FG adaptive headroom, Oct 4 | 60.06 real FPS ordinary; 59.91 with phantom/beacon/orbs; zero generated activity; restart 60.02 real FPS | Preserves full native cadence when interpolation is unnecessary. These are short validation stages. |
| FG final-image delivery, Oct 4 | About 60 presentation images/s from 30 real FPS; native/spatial final moving-world changed fractions 0.8481/0.8280 | Capped camera fixture with positive ordered timestamps and midpoint pixel tests. Protected entities/effects remain at real-render cadence. No general latency or every-scene quality claim. |

**BUG-035 is the dense treetop slowdown. BUG-036 is stationary temporal foliage-edge instability.**
The latter is a separate user-reported visual defect; the timing run collected no pixel sequence
that could diagnose it. The 23 sharp-turn history resets do not explain persistent stationary
shaking. Some older performance summaries use BUG-035 too broadly; use the bug register's distinct
definitions. BUG-031 tracks the older dense command-buffer creation stalls; BUG-045 tracks transient
FG presentation failures. Historical passing retries do not close these issues.

Sources: [temporal gameplay](../phase7/temporal-gameplay/results.md),
[manual treetop capture](../phase7/manual-motion-check/results.md),
[FG rebuild](../phase7/frame-generation/rebuild.md),
[FG delivery](../phase7/frame-generation/delivery.md), [bug register](../../bug.md).
The earlier FG implementation was rejected for visual defects despite cadence passes; those
superseded results are not visual acceptance of the current design.

## Sodium, display configuration and latest terrain specialization

### Renderer comparison — 2026-10-04

Fresh-JVM vanilla/Sodium/Sodium/vanilla order, **5120×2664, RD32**, fixed dense underground scene.
The tested Sodium release is **0.9.2+mc26.2** with MetalMod's optional native adapter.

| Mode and renderer | Render-thread CPU | FPS | Mean capture p95 | Mean capture p99 |
|---|---:|---:|---:|---:|
| Native vanilla Metal | 10.629 ms | 58.61 | 23.942 ms | 30.625 ms |
| Native Sodium Metal | 3.607 ms | 59.83 | 19.084 ms | 31.538 ms |
| Spatial25 vanilla Metal | 10.537 ms | 58.01 | 25.785 ms | 30.150 ms |
| Spatial25 Sodium Metal | 3.705 ms | 59.75 | 18.987 ms | 34.214 ms |

This establishes **66.06%/64.84% lower CPU time**, not a corresponding FPS gain. P95 improves but
p99 worsens. The default Optimized fluid-culling option omitted visible lava patches. A controlled
**Fluid Culling = Default** run restored the observed surfaces and measured native **59.93 FPS,
3.595 ms CPU, p95 18.553 ms** and Spatial25 **59.76 FPS, 3.707 ms CPU, p95 18.728 ms**. That
control is one capture per mode, not a new renderer ABBA. [Full evidence](sodium-compatibility.md).

### Resolution and display — 2026-10-05

Sodium with Default fluid culling, same dense RD32 scene, Vsync Off, native or Spatial25. Both
screens remain enabled; actual output is matched at **3600×2204**. Built-in capability is reported
as 120 Hz and external CB272K as 60 Hz.

| Screen / mode | Rendered FPS | CPU | p95 / p99 | Positive presentation events/s |
|---|---:|---:|---:|---:|
| Internal native | 112.97 | 2.09 ms | 16.49 / 19.67 ms | 101.46 |
| External native | 111.75 | 2.20 ms | 16.58 / 25.33 ms | 59.97 |
| Internal Spatial25 | 121.75 | 2.19 ms | 10.78 / 13.59 ms | 116.53 |
| External Spatial25 | 118.76 | 2.20 ms | 15.68 / 21.07 ms | 59.97 |

One pair per mode/display is exploratory. Zero timestamps and observer countdown/export coverage
are disclosed in the source. Rare internal Spatial25 frame intervals reach **51.10 ms**. This
comparison disproves a universal 60-FPS renderer cap; it does not isolate moving screens from the
resolution difference in older 5120×2664 captures. [Full evidence](internal-display-20261005.md).

### Presentation and shader experiments — 2026-10-05 and 2026-10-16

All rows use native resolution, Sodium, Default fluid culling, RD32, Vsync Off/Unlimited and
**5120×2664**. They still differ in artifact/session/source snapshot and are separate comparisons.

| Experiment | Result | Limit / selection |
|---|---|---|
| Oct 5 presented/offscreen completion ABBA | 59.96 presented vs 96.56 offscreen completed FPS, +61.04% | Identical completion fences/two-frame bound. Diagnostic only; offscreen provides no new display frames. |
| Oct 16 early/late acquisition ABBA | 93.88→94.44 FPS, +0.60%; CPU 2.72→2.92 ms; p99 24.35→31.14 ms | No useful gain; late acquisition remains Off. Fresh source-world/session differs from older fixture. |
| Oct 16 empty-light shader, final completed-work ABBA | 97.69→138.30 completed FPS, +41.57%; interval 10.24→7.23 ms | All offscreen with identical completion fences/two-frame bound. Dynamic lighting On but zero published sources. |
| Same specialization, earlier candidate presented ABBA | 59.20→59.28 FPS, +0.14% | No demonstrated ordinary gameplay FPS gain. Final default-on presented acceptance was 58.83 FPS, a single uncontrolled acceptance capture. |

The final empty-light artifacts are core SHA-256
`13cfa90e5624b81507b9fe43d3e06d5a81ee39d88ebbe66bcb2420b1ae80e559` and separate adapter0.1.2
SHA-256 `8f33e207a8cb9f33f6d91af28d64bb035678cb4b79db1ab4027f2259c53e103a`.
Those recorded artifacts passed **925 native checks, 87+9 shader pipelines, 347 pixel checks and
standalone tests**. Their live performance runs kept temporal and FG Off. Pipeline compilation
alone does not establish fresh gameplay compatibility for those features.

Sources: [offscreen completion](offscreen-completion-20261005.md),
[late acquisition](late-drawable-20261016.md), [empty-light specialization](sodium-empty-lights-20261016.md).

## Lighting and missing circumstances

The historical one-light in-game reading is **0.028 ms extraction and 2 KiB upload**. An isolated
7.4 ms extraction spike did not reproduce. These are F3 observations, not controlled performance
pairs. The deliberate **0/1/16/64-light matrix and flat-versus-clustered selection remain uncaptured**
in the audited historical evidence. The proposed 64-light budget is not a measured result.
[Phase 6 acceptance record](../phase6-plan.md#final-verdict-against-those-gates-2026-09-25).

The principal missing or incomplete historical measurements are:

- Current-artifact temporal/FG compatibility after adapter0.1.2's empty-light specialization.
- Matched RD16/RD32 surface and dense terrain comparisons, plus a controlled resolution sweep.
- Active dynamic-light counts with flat/clustered controls in nonspectator gameplay.
- Isolated performance for Nether/End, heavy entities/particles, weather, water/translucency,
  moving blocks, chunk generation/streaming and resource-pack fallback.
- A ten-minute steady soak, memory plateau through repeated transitions, repeatable thermal/power
  measurements, and input latency. No result above supplies these by implication.
- Representative temporal quality with stationary foliage and rapid motion, and sustained FG
  quality/cadence with protected dynamic content.

## Evidence-led improvement candidates

1. **Reduce terrain vertex work and bandwidth.** The latest empty-light shader has a substantial
   bounded completed-work benefit. Instrumented pre-Sodium dense work reports vertex/fragment
   sums 14.59/6.46 ms and overlapping execution union 17.32 ms; a separate post-Sodium trace
   reports 8.33/5.98 ms and union 11.37 ms. Different conditions prevent a matched GPU speedup
   claim, but both support terrain workload attribution. Preserve geometry/material identity and
   verify conservative visibility before reducing draws. [Dense trace](dense-profiling.md),
   [Sodium trace](sodium-gpu-profiling.md).
2. **Resolve measured tails and temporal scene quality.** BUG-031, BUG-035, BUG-036 and BUG-045
   require their own fixtures. A passing average-FPS sample does not settle them.
3. **Measure lighting before selecting clustered defaults.** The required counters exist, but the
   decisive active-source scaling experiment has not been recorded.
4. **Keep unsuccessful experiments disabled.** Fused calls and late acquisition have no useful
   demonstrated benefit in their repeated native comparisons. More native-call reduction alone
   is not evidence of better rendering throughput.

This appendix changed documentation only. Its review did not run GPU workloads, modify production
code, install a mod, or change a normal Minecraft world/configuration.
