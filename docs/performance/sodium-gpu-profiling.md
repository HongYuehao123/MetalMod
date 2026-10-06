# Sodium GPU and presentation investigation — 2026-10-04

Follow-up: [the 2026-10-05 internal/external test](internal-display-20261005.md) matches output
resolution on both screens with the internal display enabled. Both render above 110 FPS, but
observed unique positive presentation timestamps remain near 60/s externally and exceed 100/s
internally. The earlier 60-FPS observation is configuration-specific, not a general Metal cap.

The post-Sodium investigation confirms that the benchmark window is hosted on the external
**CB272K, display ID 2, maximum 60 Hz**, with backing scale 2.0. Native Metal's configured
present mode is IMMEDIATE, but observed presentation still follows roughly 16.67 ms cadence.
This explains why large render-thread CPU savings produce a small FPS increase in this fixture.
It does not establish a universal Metal FPS cap, input latency, or performance on another display.

## Fixture and separation of measurements

Official Sodium 0.9.2+mc26.2 and the guarded optional Metal adapter use the same copied dense
underground waypoint 13, fixed camera, render distance 32, simulation distance 5 and output
5120×2664. Fluid Culling is **Default**, preserving the lava surfaces restored in BUG-046.
Native and spatial strength 25 are tested separately; temporal upscaling and frame generation
are disabled for these measurements. The normal Minecraft installation is untouched.

A diagnostic fresh JVM enables asynchronous render-stage counters. A separate 20-second
Metal System Trace attaches during native warmup/measurement. Its captures are marked
INSTRUMENTED and excluded from throughput/stall comparisons. A second fresh JVM disables
profiling and records clean native/spatial captures plus the hosting-screen log.

Core JAR SHA-256: `bc810c1154d047e97a6f9c7fa2b3609485209bb72053645544f6a5925d86d8d7`.
Adapter: `afc51869a6409a31e002e2ab6519d35a28b2a9f651a3aa77fb166e75d44eca95`.
Neither production artifact changed during this investigation.

## Separate GPU diagnostic

The trace filters to Java PID 61524 and top-level GPU intervals. It contains **1,248 interior
associated GPU frames**, with boundary frame IDs 1 and 1260 omitted and no unassigned intervals.

| Instrumented metric | Mean ms | Median ms | p95 ms |
|---|---:|---:|---:|
| Vertex intervals, sum | 8.33 | 8.30 | 9.49 |
| Fragment intervals, sum | 5.98 | 5.99 | 6.42 |
| Overlapping execution union | 11.37 | 11.38 | 12.51 |
| First-to-last execution span | 16.24 | 16.19 | 17.60 |

Vertex and fragment stages overlap. Associated GPU frame IDs are not F8 CPU frame IDs; frames
may overlap each other. The span includes scheduling gaps. These instrumented values are
workload attribution, not uninstrumented throughput predictions or frame/input latency.
The historical pre-Sodium trace used different fluid culling/renderer conditions and is not a
matched GPU A/B proof.

Counters report mean accumulated vertex/fragment workload of **9.21/7.09 ms native** and
**8.42/4.81 ms spatial**, on polls with available completions. Native/spatial coverage averages
19.82/19.89 completed passes and 0.262/0.125 dropped passes per poll; invalid samples are zero.
Durations are unavailable on 37/52 polls, respectively. Completions are asynchronous and
partial; they must not be added into a whole-frame GPU time. The directional reduction in
fragment work is consistent with spatial upscaling, while substantial vertex work remains.

## Drawable waits and tails

The fresh clean repeat passes both foreground captures without compilation/census, resize,
MetalFX failure or recovery. It is one native/spatial pair, not a new renderer ABBA comparison:

| Clean repeat | FPS | Render-thread CPU ms | p95 frame ms | p99 frame ms |
|---|---:|---:|---:|---:|
| Native | 59.73 | 3.71 | 19.09 | 32.35 |
| Spatial strength 25 | 59.73 | 3.81 | 20.14 | 36.49 |

In the repeat, drawable acquisition averages 12.48/12.24 ms in ordinary native/spatial frames
and 23.45/25.28 ms in the slowest 5%. Creation stays 0.067/0.071 ms ordinarily and 0.087/0.099 ms
in the tails. Unique positive presentation callbacks yield 58.83/58.06 Hz; both median and p95
intervals are 16.67 ms. Native/spatial have 33/58 zero timestamps, which disclose incomplete
timestamp coverage. Callback sampling also includes countdown/export tails. This is consistent
with a roughly 60-Hz cadence plus scheduling variation; it is not evidence that all displayed
updates are perfect or that p99 is improved.

The prior clean Fluid Culling Default control reports ordinary native frames spending **12.63 ms**
in drawable acquisition and the slowest 5% **21.43 ms**. Command-buffer creation is only
0.065/0.084 ms in these groups, with near-zero correlation to frame interval. Spatial waits
are similar, 12.55/20.98 ms. The earlier BUG-031 creation stall is not reproduced or closed.

That control's actual presentation callbacks have median and p95 **16.67 ms**, p99 **33.34 ms**,
and about 59.08 unique displayed updates per second in both modes. Native records 56 F8 frame
intervals over 25 ms; their following interval averages 6.16 ms. Spatial records 43, followed
by 8.07 ms on average. Display callbacks include countdown/export tails and are not aligned
one-to-one with F8 frames. Some CPU interval spikes therefore reflect scheduling variation;
actual missed presentations also occur and remain a useful optimization target.

## Engineering decision and verification

No speculative queue-depth, synchronization or culling change is enabled. The next useful
tests are the same workload on a higher-refresh hosting display, followed by repeated
presentation-tail traces and terrain GPU workload attribution. A higher-refresh test must
record actual output dimensions and cannot be called a matched comparison if those change.
Reducing Java/native batch crossings may buy further CPU headroom, but the current evidence
does not support claiming that it will raise the near-60-Hz displayed rate.

Test tooling now records the actual hosting screen, automatically marks declared diagnostics
INSTRUMENTED, and rejects diagnostic metadata in the CPU stall analyzer. The add-on and display
probe compile against the real client/macOS APIs. The existing production verification applies
to the unchanged artifacts: 921 native checks, 87+9 shader inventory, 204 vanilla+66 Sodium
pixel checks and standalone tests. This investigation does not repeat or extend the earlier
temporal/FG compatibility validation and does not close BUG-035 or BUG-045.
The stall analyzer accepts the clean run and demonstrably rejects the diagnostic metadata.
The final automatic marker addition is compile-verified; the archived diagnostic was explicitly
marked after recording. Normal options/config/mod hashes match the prior baseline manifest.

Evidence: `build/reports/sodium-gpu-profile/` retains separate diagnostic and clean captures,
trace/export/analysis, counter coverage, display cadence, stall summaries and verification.
