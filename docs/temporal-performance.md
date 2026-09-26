# The cost of MetalFX temporal upscaling

Status: **measured and explained (2026-09-26).** The mode works and is enabled; this records what it
costs, how that was established, which explanations were falsified, and what the number does and does
not mean. The defect register entry is [BUG-035](../bug.md#bug-035--temporal-upscaling-costs-far-more-in-the-game-than-its-parts-measure);
the phase record is [phase7-plan.md](phase7-plan.md) §3.4.

---

## 1. The claim

At 50% render scale on a 5120-wide output, **MetalFX temporal costs roughly 5.6 ms per frame more than
MetalFX spatial** on the M4 Pro this was measured on. There is no knob on the temporal filter, so that
is the price of the mode rather than a tuning problem. Spatial remains the default; temporal is a
quality option, and the settings page and the F6 action bar now say which is which.

The per-object motion pass is **not** the cost: it is about 0.3 ms of the 5.6.

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

The last row is the decisive one. `-Dmetalmod.skipMotionEncode=true` runs the scaler while skipping
the dispatch, so the two passes stop depending on each other and everything else about the frame is
unchanged. The path barely moves, which puts the motion pass at **0.3 ms** and leaves the scaler with
the whole 5.6 ms premium.

---

## 4. Why the isolated numbers said 2.7 ms

The same scaler, timed on its own against the engine's own render targets
(`WorldRenderTarget.gpuTimeTemporal`, 12 passes), reports **2.70 ms**. The motion pass on its own
reports **0.06 ms**. Added together they are 2.76 ms against a path of 7.26 ms - and the smoke test's
isolated figures are the same order (spatial scaler 3.0 ms, temporal scaler 2.8-3.3 ms, motion
0.10-0.16 ms).

**Both measurements are correct; they are not the same workload.** The isolated timing passes a
constant jitter with unchanging inputs. MetalFX's temporal filter reconstructs each frame from where
the history says the surface was, and when the jitter does not move there is nothing to resample, so
it can shortcut. A frame advances the jitter phase every frame - that is what the projection jitter is
for - and the history is then resampled at a new sub-pixel position every frame. That resampling is
the filter's work, and ~7 ms at 5120x2664 is what it costs.

This also explains why the smoke test's numbers looked so reassuring: a synthetic scene with a still
camera is the filter's cheapest case, and it was being read as if it were a floor.

**Consequence for the diagnostics.** The `temporal pass gpu` line on F3 reports the encoded passes'
own span, which runs with the history already warm. It separates the two passes; it does not price
the mode. The frame time on the F3 head line is the number that answers "what does this cost me".

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

Two of those were genuine defects that survived because the code read as complete, and both are fixed.
The other four are recorded so the same ground is not covered twice.

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
- **The `motion`/`scaler` spans on F3 run with a warm history**, as §4 describes, and must not be added
  up to a frame cost.
- **Quality was not judged.** Nothing here says whether temporal's picture is worth 5.6 ms; that is
  [TESTING.md](../TESTING.md) §6.D.

---

## 8. Reproducing

```bash
# End to end, both paths, with the distribution and the parts against the engine's targets
./tools/scaling_check/run.sh

# The isolated parts at a 5K output: spatial scaler, temporal scaler, motion pass
./native/build/metalmod_smoke

# The two diagnostics behind the falsified hypotheses (both off by default)
... -Dmetalmod.splitTemporalEncode=true     # one command buffer per pass
... -Dmetalmod.skipMotionEncode=true        # scaler without the dispatch
```

In game, **F6** cycles off / spatial / temporal in place at one spot. The F3 head line carries the
frame time, the `upscale` line carries the effect and its own span, and the `temporal pass gpu` line
splits the two passes - read it as a split, not as a price.

---

## 9. Open

- Whether 5.6 ms is worth it is a product decision that needs the picture looked at, which
  [TESTING.md](../TESTING.md) §6.D covers.
- If the cost needs to come down, the only remaining lever is output-side: a lower render scale, or
  temporal only where it earns its keep. The filter itself has no quality/performance setting.
- A per-frame, per-pass GPU timing that does not depend on a warm history would need
  `MTLCounterSampleBuffer` timestamps inside one command buffer; the same gap is already recorded in
  HANDOFF's measurement section, and it would settle this class of question without the wall-clock
  arithmetic.
