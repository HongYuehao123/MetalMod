# Spatial comparison results — 2026-10-01

Measured artifact SHA-256: `cd39bb667f47efebbf217385ddffa3dbae4b3228df9545af533351578f36d3af`.
Usable spatial baseline commit: `aa6693d`; measured build adds scaler-batch timing only.

Apple M4 Pro, macOS 27.0.1, Minecraft 26.2, Java 26.0.1; 5120×2664 output,
32-chunk distance, default shared targets. Twelve 60-second F8 captures in a disposable world copy.
A full-route warmup precedes 20 seconds per setting plus the five-second capture countdown.
Native and AA-enabled strengths are repeated in reversed order; AA-off gets two subsequent repeats.
All twelve captures pass focus/gameplay/dimension/census checks, zero shader compiles, zero SR failures
and zero recoveries. The first ten use Vsync Off and Minecraft’s unlimited setting (260).

| Run | FPS | Mean ms | p95 ms | p99 ms | Completed batch median ms |
|---|---:|---:|---:|---:|---:|
| 01-native | 74.1 | 13.495 | 21.599 | 23.686 | unavailable |
| 02-sr25-aa | 77.7 | 12.868 | 23.707 | 25.453 | 2.680 |
| 03-sr33-aa | 82.0 | 12.200 | 22.832 | 24.604 | 2.515 |
| 04-sr50-aa | 86.6 | 11.541 | 20.528 | 22.130 | 2.015 |
| 05-sr50-aa | 86.6 | 11.541 | 20.462 | 22.114 | 2.013 |
| 06-sr33-aa | 81.8 | 12.222 | 22.790 | 24.576 | 2.513 |
| 07-sr25-aa | 77.3 | 12.937 | 23.610 | 25.372 | 2.685 |
| 08-native | 73.8 | 13.558 | 21.404 | 23.908 | unavailable |
| 09-sr25-noaa | 79.0 | 12.663 | 23.146 | 24.887 | 2.236 |
| 10-sr25-noaa | 79.0 | 12.652 | 23.215 | 24.852 | 2.240 |
| 11-native-vsync | 73.6 | 13.580 | 21.453 | 23.937 | unavailable |
| 12-sr25-aa-vsync | 77.4 | 12.914 | 23.477 | 25.401 | 2.689 |

Two-run FPS means: native **73.9**, SR25+AA **77.5**, SR33+AA **81.9**, SR50+AA **86.6**.
Relative throughput gains are approximately **4.8%, 10.8%, 17.2%** on this route.
The 25/33% settings worsen whole-route p95 versus native; 50% improves it but renders only 25%
of native scene pixels. These results do not establish a universal setting or release winner.

Disabling AA at 25% yields **79.0 FPS** (+1.9% versus AA enabled), and reduces the completed
scaler-batch median from about **2.68 to 2.24 ms**. Keep input AA enabled: the small throughput
difference does not establish equivalent quality, especially after the reported noise problem.

Vsync-on runs yield native 73.6 FPS and SR25 77.4 FPS in the CPU presentation-boundary capture.
The setting is recorded On and the client setter invalidates surface configuration; the backend maps
FIFO to CAMetalLayer displaySyncEnabled. These CPU intervals are not actual display timestamps.
No effective surface-mode snapshot or displayed-frame/latency measurement was collected in this run,
so Vsync/display pacing remains inconclusive. The preserved harness now validates effective mode
before capture for future reproductions. No display-link pacer or artificial FPS cap was added.

## Scope and evidence

This is a two-repeat pilot, not the full Phase 7 acceptance matrix or required three alternating pairs.
The route holds positions and teleports; it cannot prove continuous-motion stability. Spectator mode
suppresses effective dynamic lights despite their configured enablement. Water and clouds animate,
so images are static comparisons, not pixel-identical references. No latency or power claim is made.
No comprehensive GUI/content/lifecycle review or temporal candidate is accepted. Temporal remains
excluded until independent object motion, jitter and history rejection have a validated contract.

The new F3/F8 GPU value measures the latest completed AA + FX + output-copy command buffer.
It may lag/repeat across CPU frames and is not total frame GPU time. Native/recovery and missing
timestamps return -1. Native tests validate generation reset and real completed timestamps across
100 recreation cases; standalone checks the capture column. All five offline gates pass, including
strict Metal validation, shader inventory 87/87 + 9/9 and 194 pixel assertions.

Per-run summaries, analysis, route, raw-CSV hashes and gate logs are in this directory. Full CSVs,
unscaled comparison PNGs, the measured runner source and console are preserved under
`build/reports/spatial-benchmark/`. Reproduction source: `tools/metalfx_benchmark/`. Earlier runs
with focus loss were rejected and are excluded. The original world/options/config were untouched.
