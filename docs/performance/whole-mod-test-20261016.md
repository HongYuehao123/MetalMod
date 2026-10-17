# Whole-mod verification and performance — 2026-10-16

Follow-up: the [native footprint correction](bug051-temporal-footprints-20261016.md) completes
BUG-051 and passes the unchanged image gate. The results below describe the earlier artifact.

Fresh tests of production commit `2ca817453f23095bc540119d0b13231537abbfe7` on Apple M4 Pro (14 CPU / 20 GPU cores), 24 GB UMA, macOS 27.0.1, Java 26, Minecraft 26.2. Optional Sodium is official 0.9.2+mc26.2 with MetalMod adapter 0.1.2. Test tooling and documentation changes accompany this report; production renderer sources were not edited.

Evidence: [`build/reports/whole-mod-20261016/`](../../build/reports/whole-mod-20261016/), including exact launch commands, artifact hashes, logs, raw frame CSVs, screenshots, [`analysis.json`](../../build/reports/whole-mod-20261016/analysis.json), and [`performance.csv`](../../build/reports/whole-mod-20261016/performance.csv). Historical numbers are isolated in the [baseline appendix](whole-mod-historical-baselines-20261016.md); they are not fresh results or pooled with this run.

**Verdict:** all five offline gates and both renderers’ temporal/FG runtime suites pass, but the independent **Sodium temporal static-image gate fails (BUG-051)**. The performance sweep accepts **22 captures / 38,652 measured frames**. This is not an all-green visual-quality release verdict.

## Correctness and lifecycle

| Suite | Result | Evidence |
|---|---|---|
| Canonical core + adapter build | PASS | Real client JAR; packaged native image matches |
| Native smoke | PASS | 925 checks, Metal API validation enabled |
| Shader inventory | PASS | 87 static + 9 post; repeated with dynamic and clustered lighting enabled |
| Pixel rendering | PASS | 347 assertions: 204 vanilla + 143 Sodium |
| Standalone suite | PASS | Settings, temporal/FG contracts, formats, offsets, lighting ABI/grid, capture and UMA tests |
| fg-sodium-retry/validation | PASS | PASS; checks=186 failures=0 |
| fg-sodium-retry/validation-restart | PASS | PASS; checks=15 failures=0 |
| fg-vanilla/validation | PASS | PASS; checks=186 failures=0 |
| fg-vanilla/validation-restart | PASS | PASS; checks=15 failures=0 |
| temporal-sodium/validation | PASS | PASS; checks=385 failures=0 frames=18206 |
| temporal-vanilla/validation | PASS | PASS; checks=385 failures=0 frames=18219 |


Five-gate logs: [`verification-20261016-170210-106603`](../../build/reports/verification-20261016-170210-106603/result.txt). The initial sandbox smoke attempt could not create a Metal device; the host rerun passed. The optional raw R8Sint texture-buffer probe remains deliberately skipped because the backend uses its tested 2D emulation. LWJGL/JOML Unsafe deprecation messages and offline-identity authentication errors are not shader or renderer failures.

Temporal suites cover native bypass, spatial and temporal strengths 25/33/50, odd dimensions, inventory/pause/settings UI, fast turns, teleport, FOV changes, render gaps, resource reload, rain/entities/items, light toggles, moving blocks, repeated target recreation, minimize/restore, Nether/Overworld travel, reconnect and menu return. Frame-generation suites cover presentation order/cadence, adaptive behavior, native/spatial/temporal paths, protected hand pixels, 5K output, fullscreen, motion, VSync transitions and saved-On fresh-JVM restart.

The first Sodium FG launch ended early: it recorded a startup visibility failure (`bits=10`, focus false), then exited before producing a terminal result. It is retained under `fg-sodium/` and is not counted as a passing run. The separate `fg-sodium-retry/` result is listed independently. A passing retry does not resolve the broader intermittent BUG-045 or establish universal FG image quality.

## Fresh presented performance

All captures use a copied saved world, fixed camera, noon/clear weather, simulation distance 5, external CB272K (60-Hz capability), actual 5120×2664 output, VSync Off / Unlimited, and FG Off. Native means no upscaling; spatial25 and temporal25 use 75% width/height. Sodium uses Fluid Culling = Default. The fixture removes ordinary entities and enters spectator mode: **zero published dynamic sources**, so this is terrain/presentation performance, not many-light or mob-heavy gameplay. AC power was confirmed; thermal-warning status was unavailable.

Each timed capture lasts approximately 30 seconds after a 60-second initial world warmup and 20-second mode warmup. Capture validation rejects focus loss, pause/menu frames, resolution changes, timed shader compilation/census, and scaler failure/recovery. Screenshots occur after timing. CPU is render-thread consumed time, excluding blocked waits. FPS derives from CPU frame-boundary intervals; it is not physical display refresh, isolated GPU time, or input latency. p95/p99 are frame intervals; lower is better.

| Capture | Mode | Rendered FPS | CPU ms | p95 ms | p99 ms | Frames >50 ms |
|---|---|---|---|---|---|---|
| dense16-sodium | native | 59.53 | 3.397 | 19.86 | 32.06 | 0 |
| dense16-vanilla | native | 59.30 | 4.456 | 20.91 | 30.00 | 1 |
| dense32-a1 | native | 57.86 | 11.834 | 23.47 | 35.17 | 1 |
| dense32-a1 | sr25 | 57.57 | 11.184 | 24.75 | 33.24 | 0 |
| dense32-a2 | native | 58.17 | 11.711 | 24.72 | 33.28 | 0 |
| dense32-a2 | sr25 | 58.16 | 11.289 | 24.09 | 32.01 | 0 |
| dense32-b1 | native | 59.13 | 3.649 | 21.34 | 32.34 | 0 |
| dense32-b1 | sr25 | 58.96 | 3.676 | 20.57 | 33.60 | 0 |
| dense32-b2 | native | 59.06 | 3.584 | 19.91 | 33.43 | 1 |
| dense32-b2 | sr25 | 58.93 | 3.711 | 21.29 | 32.62 | 0 |
| surface16-sodium | native | 59.63 | 3.372 | 19.38 | 32.28 | 0 |
| surface16-vanilla | native | 59.60 | 3.590 | 21.14 | 31.96 | 0 |
| surface32-sodium | native | 59.53 | 3.502 | 20.01 | 34.05 | 0 |
| surface32-sodium | sr25 | 59.26 | 3.629 | 20.64 | 33.17 | 0 |
| surface32-vanilla | native | 59.63 | 4.458 | 22.90 | 29.57 | 1 |
| surface32-vanilla | sr25 | 59.63 | 4.486 | 22.25 | 30.37 | 2 |
| temporal-performance-sodium | native | 59.13 | 3.497 | 20.82 | 33.64 | 0 |
| temporal-performance-sodium | sr25 | 59.46 | 3.735 | 20.73 | 34.09 | 0 |
| temporal-performance-sodium | temporal25 | 56.70 | 3.527 | 31.39 | 36.56 | 1 |
| temporal-performance-vanilla | native | 59.73 | 4.480 | 19.96 | 32.44 | 0 |
| temporal-performance-vanilla | sr25 | 59.60 | 4.501 | 18.30 | 32.31 | 0 |
| temporal-performance-vanilla | temporal25 | 49.79 | 5.493 | 34.53 | 35.23 | 0 |


### Repeated dense 32-chunk comparison

Four fresh JVMs ran vanilla/Sodium/Sodium/vanilla. Aggregate FPS is the inverse of mean capture frame time. Tail values below are means of the two per-capture percentiles, not pooled percentiles. Other scenario pairs above are exploratory single repeats.

native: Sodium rendered FPS changes **+1.86%** and render-thread CPU falls **69.28%** in this fixture.

sr25: Sodium rendered FPS changes **+1.87%** and render-thread CPU falls **67.13%** in this fixture.

| Mode | Renderer | FPS | CPU ms | Mean p95 ms | Mean p99 ms |
|---|---|---|---|---|---|
| native | vanilla | 58.02 | 11.773 | 24.10 | 34.22 |
| native | sodium | 59.10 | 3.616 | 20.62 | 32.88 |
| sr25 | vanilla | 57.86 | 11.236 | 24.42 | 32.63 |
| sr25 | sodium | 58.95 | 3.693 | 20.93 | 33.11 |


![Native-resolution scenario comparison](../../build/reports/whole-mod-20261016/performance-summary.png)

![Matched spatial and temporal performance](../../build/reports/whole-mod-20261016/feature-performance.png)

### Presentation observations

| Capture | Mode | Unique presentation events/s | Zero timestamps | Callbacks |
|---|---|---|---|---|
| dense16-sodium | native | 58.20 | 49 | 2089 |
| dense16-vanilla | native | 58.14 | 44 | 2082 |
| dense32-a1 | native | 57.34 | 20 | 2029 |
| dense32-a1 | sr25 | 57.32 | 14 | 2028 |
| dense32-a2 | native | 57.44 | 29 | 2048 |
| dense32-a2 | sr25 | 57.80 | 13 | 2039 |
| dense32-b1 | native | 58.12 | 36 | 2079 |
| dense32-b1 | sr25 | 58.08 | 32 | 2068 |
| dense32-b2 | native | 58.23 | 27 | 2068 |
| dense32-b2 | sr25 | 58.28 | 25 | 2068 |
| surface16-sodium | native | 58.43 | 43 | 2091 |
| surface16-vanilla | native | 58.26 | 48 | 2090 |
| surface32-sodium | native | 58.00 | 54 | 2092 |
| surface32-sodium | sr25 | 58.28 | 37 | 2080 |
| surface32-vanilla | native | 58.28 | 49 | 2092 |
| surface32-vanilla | sr25 | 57.94 | 59 | 2090 |
| temporal-performance-sodium | native | 58.23 | 33 | 2074 |
| temporal-performance-sodium | sr25 | 58.03 | 47 | 2086 |
| temporal-performance-sodium | temporal25 | 56.54 | 3 | 1986 |
| temporal-performance-vanilla | native | 58.00 | 61 | 2094 |
| temporal-performance-vanilla | sr25 | 58.23 | 46 | 2087 |
| temporal-performance-vanilla | temporal25 | 49.72 | 1 | 1743 |


The passive observer includes countdown/export tails at the same mode. Zero timestamps disclose skipped or unpresented drawables; outstanding callbacks are retained in each display-counts.txt. Unique presentedTime event rate is not physical refresh, a complete scanout count, or input latency; IMMEDIATE-mode events can exceed screen refresh.

## Lighting preparation microbenchmark

The new [lighting harness](../../tools/lighting_benchmark/README.md) independently checks cell membership, overflow, ABI bytes, unused entries and large-coordinate alignment. It measures CPU preparation only: no source discovery, live game, shader evaluation, upload synchronization or GPU rendering. Two fresh JVM runs are shown as ranges of batch-median microseconds per operation. These are not individual-call p95s, frame times, or gameplay FPS.

| Light distribution | Snapshot encode µs | Grid build µs | Cluster packing µs |
|---|---|---|---|
| empty | 0.368–0.372 | 0.189–0.191 | 7.938–8.010 |
| scattered_1 | 0.630–0.663 | 0.218–0.225 | 7.867–7.966 |
| overlapping_1 | 0.620–0.628 | 0.214–0.232 | 7.909–7.926 |
| scattered_16 | 4.953–5.005 | 0.782–0.813 | 7.958–7.979 |
| overlapping_16 | 4.768–5.224 | 0.760–0.817 | 7.905–7.924 |
| scattered_64 | 14.010–21.551 | 2.802–2.994 | 8.232–8.339 |
| overlapping_64 | 14.562–23.608 | 2.941–3.171 | 8.092–8.099 |
| synthetic_full_window_64 | 12.110–14.212 | 124.785–131.469 | 11.588–11.607 |


The radius-128 full-window case is synthetic and exceeds normal collector radii. Cluster packing always prepares 141,600 direct payload bytes; 64-light snapshot encoding requests 4,112 direct payload bytes across 65 direct-buffer allocations. Requested payload is not live memory, GPU bandwidth, or power consumption. Full many-light gameplay cost remains unmeasured.

## Image stability and frame generation

| Renderer | Static registration | Unique jitter samples | X/Y range, output pixels |
|---|---|---|---|
| sodium | FAIL | 16 | 1.4100, 1.4447 |
| vanilla | PASS | 16 | 0.1962, 0.1719 |


**BUG-051:** current Sodium fails at 1.4100/1.4447 pixels; vanilla passes at 0.1962/0.1719. An archived October 4 Sodium sequence also fails at 1.5005/1.4329, predating empty-light specialization. Current Sodium displacement strongly follows jitter (R² 0.990/0.996), but the exact projection/reconstruction cause is unverified. The runtime PASS does not include this separate image-analysis verdict.

![Static temporal registration](../../build/reports/whole-mod-20261016/jitter-stability.png)

Image registration is calibrated using synthetic one-pixel shifts and checks coherent motion in one 512×288 ROI across 16 jitter samples, with a 0.25-pixel range limit. It does not prove distant shimmer, disocclusion quality, all moving-object behavior, or perceptual equivalence. Frozen native/spatial/temporal comparison images are retained in each validation directory. BUG-035/036 remain open unless their exact scenes and symptoms are resolved.

| Renderer run | Stage | Generated presented | Paired real presented | Observed paired delivery/s | Ordering errors |
|---|---|---|---|---|---|
| fg-vanilla | fast-native | 0 | 0 | 0.00 | 0 |
| fg-vanilla | limited-on | 111 | 110 | 59.00 | 0 |
| fg-vanilla | spatial-on | 148 | 147 | 60.00 | 0 |
| fg-vanilla | temporal-on | 298 | 297 | 60.00 | 0 |
| fg-sodium-retry | fast-native | 0 | 0 | 0.00 | 0 |
| fg-sodium-retry | limited-on | 113 | 112 | 60.00 | 0 |
| fg-sodium-retry | spatial-on | 149 | 148 | 60.00 | 0 |
| fg-sodium-retry | temporal-on | 299 | 298 | 60.00 | 0 |


These short correctness stages run with Metal validation and a test-only force flag where required. The 30-FPS-limited stage tests approximately two delivered images per real pair. Zero generated activity in the fast-native stage is intended adaptive behavior. Protected content can retain real-frame cadence. These counters are not input latency or an unconditional doubling of gameplay performance.

## Improvements marked

See the [prioritized code audit](whole-mod-improvements-20261016.md) and [bug register](../../bug.md). No optimization gain is claimed for an unimplemented proposal.

1. **BUG-051 / highest quality priority:** trace and correct Sodium temporal projection/reconstruction alignment, and require the calibrated image gate.
2. **BUG-049 / P2: duplicate clustered publication.** The fresh pixel test observes six uploads and two grid builds for one published snapshot/one draw. Track representation readiness by generation and tighten the counter regression.
3. **BUG-050 / P2: stale lighting work telemetry.** Suppressed/empty-source frames can repeat an earlier extraction/query/index duration. Reset work counters independently of source publication before using these columns to attribute performance.
4. **P2: reduce lighting allocation and packing.** Remove per-light direct-buffer allocation and reuse safe staging storage; preserve RGBA32F texture ABI and fencing. Measure 0/1/16/64 active-light gameplay after fixing duplicate work.
5. **P2: collect static block-light data on demand.** Current active extraction builds a future-consumer index whose presently producible baked entries are discarded. Measure chunk-loading/emitter-heavy routes before changing the contract.
6. **P3: terrain bandwidth and render-pass lifetime.** Continue lean shader/geometry work and investigate compatible pass continuation/store elimination with explicit dependency analysis. Prior private-storage, fused-draw and late-acquire experiments do not justify enabling them by default.
7. **Test gaps:** repeated FG visibility/order/cadence trials; exact treetop temporal reproductions; entity/weather/chunk-streaming performance; long steady soak; matched active-light count sweeps; complete frame GPU timing.

## Limits and reproducibility

This is an extensive automated sweep on one M4 Pro and one Minecraft/Sodium version. It does not cover every mod combination, multiplayer server, resource pack, Mac GPU/OS, End performance, sustained 10-minute steady-scene memory/thermal behavior, input-to-photon latency, or power use. Nether transition correctness is tested; a fresh matched Nether throughput benchmark is not. The legacy display-link path has native lifecycle coverage, not a fresh on-screen pacing acceptance claim here.

The default native path and optional Sodium adapter were rebuilt once and used consistently. Artifact hashes:

```json
{
  "metalmod-1.0.0.jar": "ad8fe6e646157beea0ad0795b8a76e6e5bea99666cfc3e716c72c4d0a464a29d",
  "metalmod-sodium-0.1.2.jar": "deda643d8fd5e62a67dcc442a737e2009842c48768ccc576fb7e732b15706e56"
}
```

Normal-instance integrity: `{   "files_before": 688,   "files_after": 688,   "changed_files": [],   "unchanged": true }`. No normal installation was performed.

Exact commands and outcomes: [`planned-jobs.json`](../../build/reports/whole-mod-20261016/planned-jobs.json), [`matrix-status.json`](../../build/reports/whole-mod-20261016/matrix-status.json), [`followup-jobs.json`](../../build/reports/whole-mod-20261016/followup-jobs.json), [`followup-status.json`](../../build/reports/whole-mod-20261016/followup-status.json). The new `--compare-temporal` fixed-renderer option adds native/spatial25/temporal25 captures without enabling FG; the analyzer distinguishes active temporal telemetry from plain recovery. Its standalone acceptance/rejection regression passes 11 cases. See the [benchmark README](../../tools/metalfx_benchmark/README.md).
