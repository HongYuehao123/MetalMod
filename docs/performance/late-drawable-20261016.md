# Late drawable acquisition — 2026-10-16

A controlled experiment tests whether moving ordinary drawable acquisition after rendering can
recover the presentation overhead previously seen in the offscreen diagnostic. It does **not**
skip presents, enable frame generation, or reduce render resolution. The result does not justify
enabling it: average throughput changes only **+0.60%**, with worse p99 and render-thread CPU time.

## Implementation

`-Dmetalmod.lateDrawableAcquire=true` opts ordinary Vsync-Off rendering into deferred acquisition.
Default is **false**. FIFO/FG selection remains as before. The staging ring still rotates once at
frame acquisition; the lighting ring and frame counters still close once at present. Every frame
is submitted for presentation; the compositor may still drop frames independently.

`mmm_layer_acquire_display` now flushes pending ordinary render and utility work before waiting
for a drawable, without advancing staging/light rings. Four native regression checks prove a
retained render batch was initially uncommitted, becomes committed at the acquisition boundary,
completes and retains the expected pixels. The test does not require an onscreen drawable, and
its cleanup submits work if the ordering check fails rather than hanging on an unsubmitted buffer.
The benchmark probe now registers callbacks for late-acquired as well as early-acquired drawables,
without registering the same drawable twice.

## Fresh ABBA

Same JVM, early/late/late/early, 20 seconds warmup + 5 seconds countdown + approximately 30
seconds capture each. M4 Pro, external CB272K reporting 60 Hz, actual **5120×2664**, RD32,
Sodium 0.9.2 + separate adapter 0.1.1, Fluid Culling Default. Vsync Off / Unlimited; frame generation,
spatial and temporal reconstruction Off. Existing dynamic-light configuration remains enabled.

The old private world fixture no longer existed. This run copies the normal `新的世界` saved world
read-only and uses the archived fixed waypoint 13. Only this fresh matched ABBA establishes the
comparison; historical absolute FPS from the older copied fixture/session is not a control.

| Run | FPS | p95 ms | p99 ms | Render-thread CPU ms | Presented-time events/s |
|---|---:|---:|---:|---:|---:|
| batch01-native-off | 94.40 | 18.23 | 21.35 | 2.69 | 90.11 |
| batch02-native-on | 94.32 | 18.56 | 29.85 | 2.91 | 89.54 |
| batch03-native-on | 94.57 | 18.24 | 32.42 | 2.94 | 89.91 |
| batch04-native-off | 93.36 | 18.29 | 27.34 | 2.75 | 88.85 |

Early mean **93.88 FPS / 2.72 ms CPU / 24.35 ms p99**; late mean
**94.44 FPS / 2.92 ms CPU / 31.14 ms p99**. These tails are means of per-capture percentiles.
The small throughput difference is not evidence of a useful improvement; no input latency was
measured. Keep the experiment disabled and prioritize terrain/vertex/bandwidth work rather than
promising a presentation-only FPS recovery. Source geometry/material identity for future RT and
MetalFX/history contracts must remain intact in that work.

## Presentation telemetry caveat

Observed positive `MTLDrawable.presentedTime` events reach approximately 89–90/s even though the
hosting screen reports 60 Hz. With Vsync disabled, these events are not a count of complete screen
refreshes or full-frame scanouts. Apple documents that
[displaySyncEnabled=false](https://developer.apple.com/documentation/quartzcore/cametallayer/displaysyncenabled)
allows presentation without waiting for refresh, potentially with tearing, and defines
[presentedTime](https://developer.apple.com/documentation/metal/mtldrawable/presentedtime) as the
presentation host time (zero if not presented/dropped). These timestamps also do not measure
input-to-photon latency. `analyze_display.py` now publishes `presentation_events_per_second` and
an explicit scope note, retaining `displayed_rate_hz` only as a legacy compatibility alias.
Historical probe results remain historical observations, not universal monitor/FPS limits.

## Verification

All five offline gates pass: canonical build, **925 native checks**, **87 static + 9 post** shaders,
**204 vanilla + 66 Sodium** pixel checks, standalone tests. The optional add-on compiles against
the real client. Four foreground, menu-free, unpaused, fixed-resolution captures pass strict
analysis with native effective mode 0, no timed shader compilation/census, no reconstruction work
or recovery. Draw workloads match within 1%. Startup telemetry reports zero binding, attribute,
resource and pipeline errors. Client exits 0. Normal-instance options/config/mod hashes remain
unchanged; no installation or Minecraft/Sodium version upgrade occurred.

Core SHA-256: `a0624dc5448db0024bf9ff25481f7101f0819f55b93f6185d273b202ce1a31e6`.
Evidence: `build/reports/late-drawable-20261016/{analysis.json,display-analysis.json,verification.json}`,
archived frame CSVs, timestamps, console, launch metadata and offline gate logs.

Reproduce with `run_manual.py --submission-benchmark --static-camera
--comparison-property metalmod.lateDrawableAcquire`, the archived route, a source-world copy and
both official Sodium/optional adapter extra mods. Do not pass `--renderer-comparison`, which
selects a fixed renderer capture rather than property ABBA. Default native-only policy is retained.
