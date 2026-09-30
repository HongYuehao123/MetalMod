# The cost of MetalFX temporal upscaling

Status: **regression measured, GPU work attributed, and resolution scaling characterised (2026-09-26).** The mode works and is
enabled. Live Metal traces identify the GPU passes the earlier command-buffer timer missed and show
that their cost follows both input and output pixel counts. The defect register entry is [BUG-035](../bug.md#bug-035--temporal-upscaling-costs-far-more-in-the-game-than-its-parts-measure);
the phase record is [phase7-plan.md](phase7-plan.md) §3.4.

---

## 2026-09-29: render-sized Temporal history and native Spatial finish

The Temporal path now writes its history at the world render resolution and uses MetalFX Spatial
for the final upscale to the 2560x1440 window. This reduces the output pixels processed by
Temporal while keeping the HUD at the native resolution. The old full-output path remains available
for controlled measurements with `-Dmetalmod.temporalOutputScale=1.0`.

The cloned cave route, VSync on, pacing guard disabled, showed these settled F8 results. Each run
used the same 40-second 120-degree/s turn and ten-second dense final view; the final views had
about 19,900 draws per frame.

| World render | Temporal output | Turning FPS | Dense-view FPS | Dense-view p95 |
|---|---|---:|---:|---:|
| 85% (2176x1224) | 2K direct | 57.1 | 51.9 | 32.54 ms |
| 85% | 85%, then Spatial | 57.5 | 54.5 | 30.39 ms |
| 75% (1920x1080) | 75%, then Spatial | 58.7 | 58.0 | 22.25 ms |
| 67% (1715x965) | 2K direct control | 59.0 | 59.4 | 19.76 ms |
| 67% | 67%, then Spatial | 59.3 | 59.8 | 18.14 ms |
| 50% (1280x720) | 50%, then Spatial | 59.6 | 59.8 | 17.34 ms |

The 67% route is the best tested balance for this M4 Pro and 60 Hz 2K display: it retains more
source detail than 50%, and the large drop seen at 85% is absent. It does not promise a hard
60.0 fps; some individual frames still exceed 16.67 ms. At the same 67% source resolution, the
two-stage path improves dense-view p95 by 1.62 ms over direct Temporal. The offscreen scaling gate
passes with a new assertion that Temporal output matches the render resolution and with a pixel
check that the Spatial finish reaches the native target. All seven offline gates pass. The test
instance used 67% for this benchmark; the user later selected 100%/Spatial. Its output remains 2560x1440.

The captures are under `/tmp/metalmod-turn-instance/debug/metalmod/`. The final default path is
`20260929-203959-441-Metal-9537876569903134338`, and the direct-output control is
`20260929-204219-422-Metal-1635131400578671789`.

---

## 2026-09-29: dense-scene 2K trace and clean VSync comparison

The cloned Overworld route's cave waypoint 13 was tested at 85% world scale
(2176x1224 input, 2560x1440 Metal output) on the M4 Pro. The unattended probe held the camera
still for 10 seconds, turned it at 120 degrees/s for 40 seconds, and held the final view for
10 seconds. Both clean F8 captures had VSync on, no open screen or paused frames, and the same
route. The Temporal pacing guard was disabled for measurement.

| Mode | Turning FPS | Turning p95 frame | Final-view FPS | Final-view p95 frame | Mean draws while turning |
|---|---:|---:|---:|---:|---:|
| Temporal | 57.1 | 24.46 ms | 51.9 | 32.41 ms | 18,981 |
| Spatial | 59.9 | 17.72 ms | 60.0 | 17.96 ms | 19,076 |

A second clean pair used the same waypoint, route and 85% scale with VSync off:

| Mode | Turning FPS | Turning mean frame | Final-view FPS | Final-view mean frame | Mean draws in final view |
|---|---:|---:|---:|---:|---:|
| Temporal | 65.2 | 15.33 ms | 57.9 | 17.27 ms | 19,880 |
| Spatial | 76.1 | 13.14 ms | 66.5 | 15.03 ms | 19,876 |

This is the causal frame-budget result. In the final view, Spatial leaves only **1.64 ms** below
the 16.67 ms 60 Hz budget, while Temporal adds **2.24 ms** to the measured frame. Temporal is
therefore below 60 fps even without VSync. VSync then turns modest over-budget frames into missed
display slots, taking the measured final-view result from 57.9 to 51.9 fps. The turning period
has a 2.19 ms Temporal penalty and a 19.0 ms p95 with VSync off, which explains intermittent
misses even though its 15.33 ms mean is below 16.67 ms. Spatial's final-view 66.5 fps without
VSync matches the reported 60–70 fps range. The clean no-VSync captures are
`/tmp/metalmod-turn-instance/debug/metalmod/20260929-201115-506-Metal-14923935935541661171`
and `.../20260929-201320-875-Metal-8918668726443389744`.

The source captures are
`/tmp/metalmod-turn-instance/debug/metalmod/20260929-200300-391-Metal-6554284856944528058`
and `.../20260929-200514-341-Metal-6990449857944540401`. Their `summary.txt` files record
`Vsync: true` and 2560x1440 output. The renderer log showed the Temporal presentation counter
rising from 14 to 156 dropped intervals during the turn and to 238 in the dense final view;
Spatial rose from 9 to 13 over the corresponding capture and stayed at 13 afterward.

Separate 12-second Metal System Traces were attached during the same cave turn with the clone's
VSync off, to expose unthrottled GPU work. Instruments' save step disturbed subsequent frame
rates, so those runs are used only for GPU stage timings. Top-level GPU interval medians were:

| GPU stage | Temporal | Spatial |
|---|---:|---:|
| MetalFX preprocessing | 0.706 ms | — |
| MetalFX middle processing | 1.343 ms | — |
| MetalFX postprocessing | 0.819 ms | — |
| MetalMod motion reprojection | 0.050 ms | — |
| MetalFX scale fragment work | — | 0.280 ms |
| MetalFX sharpen fragment work | — | 0.085 ms |

The three Temporal stages sum to about **2.87 ms** of median GPU spans per frame, versus about
**0.37 ms** for Spatial's two fragment stages: approximately **2.5 ms of incremental scaler work**
at this 2K output. The sums are stage timings, not an end-to-end frame timer. The motion pass is
small. With nearly equal scene draw counts, the MetalFX Temporal stages are the measured extra
work that consumes the 60 Hz headroom in this view. Traces remain at
`/tmp/metalmod_20260929_temporal.trace` and `/tmp/metalmod_20260929_spatial.trace` in the current
local session; the F8 captures above are the unprofiled pacing comparison.

This result motivated the Temporal pass with a smaller intermediate output followed by Spatial
to 2560x1440, measured above. It needed to save enough of the
roughly 2.5 ms incremental cost to hold the 16.7 ms refresh budget in the dense view; lowering
input scale alone did not do so at 70% in the earlier cave test.

---

## 1. The claim

At 50% render scale on a 5120-wide output, **MetalFX temporal costs roughly 5.6 ms per frame more than
MetalFX spatial** on the M4 Pro this was measured on. There is no exposed quality/performance knob on the temporal filter. A live Metal trace now shows
that its preprocessing, main and postprocessing stages account for the full-path latency. Spatial remains the default; temporal is a
quality option, and the settings page and the F6 action bar now say which is which.

Removing the motion dispatch saved about 0.3 ms of the 5.6. The Metal trace attributes most of the
remaining GPU time to MetalFX itself, including stages outside the caller-owned command buffer.
An independent-resolution sweep shows why 5K is especially expensive: at essentially the same
2560x1332 input, reducing temporal's output from 5120x2664 to 3840x1998 reduced the three MetalFX
stages from **7.06 to 4.64 ms**; 3200x1666 reduced them to **3.77 ms**.

---

## 2. The in-game observation

One session, one hilltop, **F6** cycling the effect without moving. Render resolution 2560x1440,
output 5120x2880, 5,981 against 5,971 draw calls, vsync off.

| Mode | Frame | FPS | p95 | wait |
|---|---:|---:|---:|---:|
| spatial | 12.7 ms | 91 | 12.6 ms | 4.3 ms |
| temporal | 17.3 ms | 58 | 25.5 ms | 2.4 ms |

Temporal adds **4.6 ms** here. Its F3 line reported `motion 2560x1440, 3 ent + 10 part, 10 stamps,
resets 2` and `temporal gpu motion 2.0 + scaler 3.2 ms`.

An earlier pair, taken in a hole rather than on a hill, read native **4.2 ms** against temporal
**13.1 ms** - but those two frames also differ in draw count (40 against 287) and one is visibly
unlit, so they are not the same world state. The hilltop A/B is the one to trust, because the draw
counts match and the mode was changed in place.

The in-game number agrees with the harness: +4.6 ms in the frame, +5.6 ms offscreen.

---

## 3. Offscreen, end to end

`tools/scaling_check/run.sh` times each path with the queue drained after every iteration, at
2560x1332 rendered and 5120x2664 presented, 40 iterations each. It prints the distribution, not just
the mean, because a mean cannot tell uniform work from occasional spikes and the two have opposite
explanations.

| Path | mean | min | median | max |
|---|---:|---:|---:|---:|
| spatial | 1.64 ms | 1.55 | 1.58 | 3.28 |
| temporal | 7.26 ms | 7.07 | 7.21 | 8.09 |
| temporal, motion dispatch removed | 6.99 ms | 6.89 | 6.99 | 7.28 |

Uniform: every iteration pays it, so it is steady-state work rather than something periodic.

The last row isolates the motion contribution. `-Dmetalmod.skipMotionEncode=true` runs the scaler while skipping
the dispatch, so the two passes stop depending on each other and everything else about the frame is
unchanged. The path barely moves, which puts the motion pass at about **0.3 ms**. It does not
identify whether the remaining delay is scaler execution or scheduling around it.

---

## 4. Controlled jitter comparison and the live GPU trace

The same scaler, timed on its own against the engine's render targets
(`WorldRenderTarget.gpuTimeTemporal`, 12 passes), reports about **2.7 ms**. The motion pass alone
reports about **0.05 ms**. The complete, fenced path takes about **7.1 ms**. A GPU command-buffer
span and a CPU wall-clock wait measure different things; their difference is not automatically
additional scaler execution.

The earlier explanation was that the isolated timer holds jitter constant, letting MetalFX skip
resampling. A controlled A/B now changes only the jitter phase in the *same complete path*. A test-only
switch in `ScalingCheck` rewinds only the jitter phase before each temporal cost iteration, so
`beginFrame()` always selects the same phase. It leaves reset and history state alone. Both runs use the same target sizes, motion encode,
scaler, five-frame warm-up, queue drain and 40 measured iterations, in separate JVMs:

| Jitter | Spatial mean | Temporal mean | Temporal median | Temporal minus spatial |
|---|---:|---:|---:|---:|
| advancing | 1.616 ms | 7.059 ms | 7.052 ms | 5.443 ms |
| fixed phase | 1.716 ms | 7.087 ms | 7.049 ms | 5.371 ms |

Fixing jitter changed temporal wall time by **0.028 ms** in the opposite direction. That is far too small to account for the
roughly 5.5 ms premium, so the shortcut explanation is **falsified for this workload**. The two
isolated scaler GPU timings were 2.669 and 2.670 ms, respectively. This harness clears depth each
iteration and has no real color scene; it proves the jitter claim wrong in the synthetic workload,
not how a populated game scene behaves.

The full-path measurement includes CPU submission, queue scheduling and completion as well as GPU
work. The live GPU trace below resolves the large gap from the individual command-buffer span.

### Live 5K Metal System Trace

On 2026-09-26, the game was launched into the local single-player test world at **5120x2664 output,
2560x1332 input**, with the Metal backend. A three-second Instruments Metal System Trace was captured
for temporal and then spatial in separate launches. The traces' `metal-gpu-intervals` table shows:

| GPU stage | Intervals | Median duration |
|---|---:|---:|
| Temporal preprocessing | 212 | 1.69 ms |
| Temporal main processing | 207 | 2.81 ms |
| Temporal postprocessing | 211 | 2.56 ms |
| Motion reprojection | 190 | 0.083 ms |
| Spatial scale | 559 | 0.401 ms |
| Spatial sharpen | 545 | 0.099 ms |

The three temporal stage medians total about **7.06 ms**, consistent with the approximately 7.1 ms
fenced path. A representative trace sequence has motion at 1003.48 ms, temporal preprocessing at
1003.57 ms, main processing at 1005.24 ms and postprocessing at 1007.99 ms. The stages execute on
the compute channel in that order. The preprocessing and postprocessing intervals have MetalFX-owned
command-buffer labels, while the middle interval belongs to the caller's `MetalMod command buffer`.
That is why the caller's 2.7 ms GPU timestamp missed much of the effect: MetalFX submitted GPU work
outside that buffer. The F3 `temporal pass gpu` line is a partial span and should not be called the
scaler's total GPU cost.

The game logs support the scale of the difference. Once the scene had settled and the engine limit
reported `NONE`, temporal logged **19.4-19.8 ms** with roughly **6,600-6,800 draws**; spatial logged
**12.9-13.7 ms** with roughly **6,600-6,900 draws**. These were separate launches and the world can
tick between them, so they are corroboration rather than a tightly paired F6 comparison. A later
`SHORT_AFK` 30-fps cap was excluded. Dummy local login credentials produced expected 401 errors for
online services; the local world and Metal renderer still ran.

Ring-buffering motion or changing command-buffer boundaries has little reason to remove roughly 7 ms
of actual scaler GPU work. The original trace artifacts were saved under
`/tmp/metalmod-live-test-20260926/` for inspection; they are not committed to the repository.

### Independent input/output resolution sweep

A second set of three-second Metal System Traces varied one side of the effect at a time. Every run
started from the same copy of the local world, waited for two uncapped ten-second telemetry windows,
and used the same M4 Pro and native Metal backend. Medians are from the trace's
`metal-gpu-intervals` table:

| Input | Temporal output | Motion | Pre | Main | Post | MetalFX total |
|---|---|---:|---:|---:|---:|---:|
| 2560x1332 | 5120x2664 | 0.083 ms | 1.69 ms | 2.81 ms | 2.56 ms | **7.06 ms** |
| 3413x1776 | 5120x2664 | 0.150 ms | 2.07 ms | 3.58 ms | 2.75 ms | **8.40 ms** |
| 3840x1998 | 5120x2664 | 0.195 ms | 2.28 ms | 3.985 ms | 2.86 ms | **9.125 ms** |
| 4096x2131 | 5120x2664 | 0.220 ms | 2.415 ms | 4.18 ms | 2.86 ms | **9.455 ms** |
| 2560x1332 | 3840x1998 | 0.084 ms | 1.15 ms | 1.97 ms | 1.52 ms | **4.64 ms** |
| 2560x1333 | 3200x1666 | 0.083 ms | 0.967 ms | 1.67 ms | 1.13 ms | **3.767 ms** |

Both dimensions matter. With output fixed at 5K, increasing input pixels 2.56 times raised MetalFX
cost 34%. With input fixed, reducing output pixels to 56% of 5K cut MetalFX cost 34%; reducing them
to 39% cut it 47%. A descriptive fit for these six points is about **0.45 ms per input megapixel +
0.40 ms per output megapixel + a small fixed term**. It is a local model for this GPU and these sizes,
not a MetalFX guarantee, but it matches the observations within a few tenths of a millisecond.

The settled game telemetry moved in the same direction. At 5K/50%, temporal was 19.4-19.8 ms. With
the same input at 3840x1998 output it was 14.7-15.3 ms, and at 3200x1666 it was 13.3-14.3 ms, with
roughly 5,900-6,100 draws in the two smaller-output runs. The different window sizes make these frame
times supporting evidence; the GPU interval table is the controlled comparison.

This result supports an **experimental two-stage path**: temporal upscale 2560x1332 to either
3200x1666 or 3840x1998, then spatially upscale that intermediate texture to the 5120x2664 display.
The measured temporal portion would be 3.77 or 4.64 ms instead of 7.06 ms. The existing 5K spatial
trace cost about 0.50 ms, so the first estimate is roughly 4.3 or 5.1 ms before accounting for the
extra intermediate texture and any changed spatial cost. That is a plausible 2-3 ms saving, not yet
a measured end-to-end result. It needs a prototype and image-quality comparison before becoming a
user-facing mode. The sweep artifacts are under `/tmp/metalmod-temporal-sweep-20260926/`.

---

## 5. Hypotheses tried and falsified

| Hypothesis | Test | Result |
|---|---|---|
| The depth read in compute is expensive | Remove the motion dispatch from the path | 6.99 against 7.26 ms - **0.3 ms**, so no |
| Two command buffers cost more than one | `-Dmetalmod.splitTemporalEncode=true` against the merged default | 7.07 against 7.09 ms - no |
| The motion texture's storage mode | Private texture plus a staging readback for the harness | 7.26 against 7.07 ms - no |
| The engine's targets' storage mode | `-Dmetalmod.privateTextures=all` (earlier pass) | No difference |
| The motion texture's content region was unset | Fixed: `inputContentWidth`/`inputContentHeight` now set | A real bug, 3.28 → 3.15 ms, not the cost |
| MetalFX was running its interim upscaler | Fixed: `requiresSynchronousInitialization = YES` | A real bug, not the cost |
| Advancing jitter causes the full-path premium | Fixed phase versus advancing phase in the same fenced path | 7.087 versus 7.059 ms - no |

Two of those were genuine defects that survived because the code read as complete, and both are fixed.
The other five are recorded so the same ground is not covered twice.

---

## 6. What was changed as a result

- The content region is set every frame, as both MetalFX headers require.
- The scaler is built synchronously, so a cost is never measured on MetalFX's interim upscaler.
- The motion dispatch and the scaler share one command buffer by default. This is neutral for cost and
  is the better shape; the split form stayed behind `-Dmetalmod.splitTemporalEncode=true` because it is
  what produces the per-pass spans.
- The motion texture is private, with a staging readback (`mmm_motion_read`) for the harnesses.
  Neutral for cost; correct storage for a texture only the GPU touches.
- The upscaler button and the F6 action bar label the trade ("faster" against "sharper, costs more")
  rather than offering the two as interchangeable.
- The scaling check prints each path's min/median/mean/max, times the parts against the engine's own
  targets rather than against textures it made itself, and no longer asserts on the difference between
  two complete paths - that assertion read like a property while asserting a number.

---

## 7. Limits

- **One machine, one GPU, one resolution.** M4 Pro, macOS 27, 50% scale on a 5120-wide output. The
  absolute milliseconds are this configuration's; the shape - the scaler is the cost, the motion pass
  is not - is what generalises.
- **No native-versus-temporal pair in the same A/B.** The hilltop F6 cycle was photographed for spatial
  and temporal; native was captured in a separate frame. F6 cycles off as well, so the next pass can
  have all three in one session.
- **The offscreen harness has no display pacing.** It measures wall time with a drained queue, which is
  execution plus synchronisation, and it is a fair comparison between paths but not an FPS prediction.
- **The `motion`/`scaler` spans on F3 are command-buffer GPU spans.** The scaler value misses
  MetalFX preprocessing and postprocessing; use the full frame time for mode comparison.
- **Quality was not judged.** Nothing here says whether temporal's picture is worth 5.6 ms; that is
  [TESTING.md](../TESTING.md) §6.D.

---

## 8. Reproducing

```bash
# End to end, both paths, with the distribution and the parts against the engine's targets
./tools/scaling_check/run.sh

# The isolated parts at a 5K output: spatial scaler, temporal scaler, motion pass
./native/build/metalmod_smoke

# Full-path fixed-jitter A/B: separate JVMs, same 5K target and fenced timing
JAVA_TOOL_OPTIONS=-Dmetalmod.scalingCheckFixedJitter=true ./tools/scaling_check/run.sh

# The other diagnostic switches (off by default)
... -Dmetalmod.splitTemporalEncode=true     # one command buffer per pass
... -Dmetalmod.skipMotionEncode=true        # scaler without the dispatch
```

In game, **F6** cycles off / spatial / temporal in place at one spot. The F3 head line carries the
frame time, the `upscale` line carries the effect and its own span, and the `temporal pass gpu` line
splits the two passes - read it as a split, not as a price.

---

## 9. Next experiments

1. **Prototype the supported intermediate-output experiment behind a diagnostic flag.** Upscale
   2560x1332 temporally to 3200x1666 and 3840x1998, then spatially to 5120x2664. Measure the complete
   chain, including both effects, the extra texture and final composition. The current traces predict
   a 2-3 ms saving; only the complete chain can confirm it.
2. **Compare image quality at both intermediate sizes.** Check fine terrain detail, foliage, particles,
   entity edges and camera motion against direct-to-5K temporal and direct spatial. Prefer 3840 if its
   smaller predicted saving buys a visibly better result.
3. **Judge quality against the measured cost.** Capture native, spatial and temporal at one spot with
   F6 and comparable draw counts, including motion, particles and fine terrain detail. Keep Spatial
   the default unless the temporal picture earns its measured frame-time premium.

Lowering render scale alone attacks only the input term while leaving the expensive 5K output term.
The intermediate-output design is now evidence-backed, but its end-to-end saving and quality remain
to be demonstrated.
