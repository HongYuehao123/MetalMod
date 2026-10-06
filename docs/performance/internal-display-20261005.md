# Internal display test — 2026-10-05

The built-in display is a useful choice for this workload: observed unique positive presentation
timestamps increase from about **60/s externally to 101/s native and 117/s spatial internally**.
Rendered FPS is similar on both screens at matched resolution; faster rendering alone does not
make the 60-Hz external display show every rendered frame. This refines the previous investigation's
roughly 60-FPS observation; it is not a universal 60-FPS limit in the Metal renderer.

## Matched scene and resolution

Two fresh-JVM copied-world launches use official Sodium 0.9.2+mc26.2, the same guarded adapter
and unchanged core JAR, Fluid Culling Default, fixed underground waypoint 13, render distance 32,
simulation distance 5, Vsync Off / IMMEDIATE and max FPS 260. Temporal and frame generation are
disabled. Each launch has a 60-second initial warmup, 20-second mode warmup, five-second capture
countdown, and one 30-second native and one 30-second spatial-strength-25 capture.

The internal window fits the visible built-in screen, yielding **3600×2204 output**. The external
control requests logical content 1800×1102 and independently verifies identical pixel output.
Internal hosting snapshots report Built-in Retina Display, ID 1, maximum 120 Hz; external snapshots
report CB272K, ID 2, maximum 60 Hz. Both backing scales are 2.0. AC power is confirmed during the
control. Both displays remain enabled. No GPU counters, Instruments trace or CPU sampler runs.

| Screen / mode | Rendered FPS | Render-thread CPU ms | p95 frame ms | p99 frame ms | Unique positive presentation timestamps/s |
|---|---:|---:|---:|---:|---:|
| Internal native | 112.97 | 2.09 | 16.49 | 19.67 | 101.46 |
| External native | 111.75 | 2.20 | 16.58 | 25.33 | 59.97 |
| Internal spatial 25 | 121.75 | 2.19 | 10.78 | 13.59 | 116.53 |
| External spatial 25 | 118.76 | 2.20 | 15.68 | 21.07 | 59.97 |

The comparison analyzer enforces the same renderer, core/extra-mod/options hashes, camera/world,
static camera setting, reconstruction mode and actual output dimensions, and correct consistent
hosting-screen snapshots. All four captures pass focus/menu/paused, resize, compilation/census
and MetalFX-failure/recovery checks. The snapshot logs are outside timed captures. This is a single
internal/external pair, not ABBA, and does not establish a reliable 1–3% rendered-FPS advantage.

## Presentation and tail limits

The observer reports 3,959/4,271 internal callbacks, of which **403/183 have zero timestamps**,
and 3,917/4,176 external callbacks, of which **1,816/2,067 have zero timestamps**, native/spatial
respectively. Positive timestamps are unique. Sampling includes the five-second countdown and
export tail, and is not one-to-one aligned with F8 CPU frame IDs. The table therefore separates
rendered throughput from observed presentation timestamps; it does not claim input latency,
perfect callback coverage or that all submitted frames were shown.

External median/p95 positive timestamp intervals remain 16.67 ms in both modes. Internal median
intervals are 8.74 ms native and 7.87 ms spatial, with p95 17.65/13.66 ms. ProMotion/presentation
scheduling can vary; these observations are not proof of a fixed refresh rate above 120 Hz.

Spatial 25 renders the world at **2700×1653**, then composites at 3600×2204. It raises internal
render throughput by about 7.8% and observed timestamp rate by about 14.9% in this single pair.
The earlier external run's 5120×2664 output is different in pixel count and aspect ratio, so
its 59.73 FPS must not be used to claim that moving the window alone doubled renderer performance.
Enabling the internal display, changing resolution, and macOS scheduling state are not individually
isolated against that historical run. The matched control clearly shows external rendering can
exceed 60 FPS while its observed positive presentation timestamps still follow 60-Hz cadence.

Rare stalls remain: maximum internal frame intervals are 36.73 ms native and 51.10 ms spatial;
the latter includes 47.68 ms drawable acquisition, with only 0.10 ms creation. External maxima
are 28.34/22.54 ms. One short pair cannot establish long-term tail reliability. BUG-031, BUG-035
and BUG-045 are not closed by this test, and temporal/FG compatibility is not revalidated here.

## Tooling, verification and retained evidence

The test-only runner now supports explicit internal/external placement and initial logical size.
The display probe uses a weak reference to its identified GLFW window because AppKit key/main
window lookup can temporarily be nil after placement. An initial attempt stopped during warmup
for that lookup failure, before any timed capture; it is retained as INTERRUPTED and excluded.
The corrected probe and Java add-on compile against macOS and the real client API and complete
both launches. The new comparison analyzer accepts these matched captures. No production code
or packaged artifact changed; prior five-gate validation still applies to the identical artifacts.
Normal options/config/mod hashes match the earlier baseline manifest, and source worlds are read-only.

Core SHA-256: `bc810c1154d047e97a6f9c7fa2b3609485209bb72053645544f6a5925d86d8d7`.
Adapter SHA-256: `afc51869a6409a31e002e2ab6519d35a28b2a9f651a3aa77fb166e75d44eca95`.

Raw captures, screen logs, launch/config metadata, post-capture images, display/stall analyses,
matched comparison and verification are retained in `build/reports/internal-display-20261005/`.
