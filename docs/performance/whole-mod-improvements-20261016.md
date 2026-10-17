# Whole-mod improvement audit — 2026-10-16

Follow-up: [lighting and Sodium temporal fixes](bugfix-lighting-sodium-temporal-20261016.md) close
BUG-049/050 and correct the missing Sodium scene jitter. The subsequent
[native footprint fix](bug051-temporal-footprints-20261016.md) closes BUG-051 on the unchanged image gate. The audit below records the original tested revision.

This audit marks concrete performance/measurement defects, optimization candidates, and remaining
validation work. It accompanies the whole-mod test campaign. New open entries BUG-049/050/051 record
the confirmed findings; production code, defaults, the normal Minecraft instance and the status
of previously registered bugs are unchanged. No FPS improvement is estimated for an unimplemented
candidate.

Source reviewed: `2ca817453f23095bc540119d0b13231537abbfe7`; the working tree was clean at audit
preflight. Current offline verification passed the canonical build, native smoke, ordinary and
lighting-enabled shader inventories, render check, and standalone tests. The verified core JAR is
SHA-256 `ad8fe6e646157beea0ad0795b8a76e6e5bea99666cfc3e716c72c4d0a464a29d`; its embedded native library
matches the build and the validation add-on is absent. See the
[verification result](../../build/reports/verification-20261016-170210-106603/result.txt) and
[artifact identity](../../build/reports/verification-20261016-170210-106603/artifact.json).

The audit itself inspected code and retained evidence without running concurrent GPU workloads.
Source line references below describe the reviewed revision. `build/reports/` evidence is retained
locally and may not be present in a fresh clone.

## Highest priority: Sodium temporal image stability (BUG-051)

The independent static-image gate fails on current Sodium with **1.4100/1.4447 output-pixel
X/Y ranges** versus the 0.25-pixel limit; matched vanilla passes at **0.1962/0.1719**. Both have
16 jitter phases and calibrated one-pixel controls. The earlier October 4 Sodium sequence also
fails (1.5005/1.4329), predating empty-light specialization. See [BUG-051](../../bug.md#bug-051--sodium-temporal-output-retains-coherent-stationary-jitter)
and the [fresh report](whole-mod-test-20261016.md).

Prioritize tracing Sodium's projection binding through scene jitter and reconstruction, then
require a passing image gate on the same scene before accepting Sodium temporal quality.
Correlation with jitter is strong, but the exact cause is unproven. The runtime suite's 385 passing
checks do not include this image verdict. Make both checks visible in release verification;
do not raise the threshold to conceal the failure.

The separate 5K/RD32 feature captures also measure temporal25 at **49.79 FPS vanilla / 56.70
FPS Sodium**, versus approximately 59 FPS native/spatial. Profile temporal GPU workload and
presentation before selecting a default. This is a measured performance concern alongside
BUG-035, not proof of one particular expensive kernel.

## Confirmed findings

### P2 — Duplicate clustered-light publication

Tracked as [BUG-049](../../bug.md#bug-049--clustered-light-bindings-upload-the-same-snapshot-twice).

**Trigger:** dynamic and clustered lighting enabled, one unchanged published light snapshot, then
the automatic bindings of an ordinary clustered pipeline.

[MetalDevice.publishLightFrame](../../src/main/java/net/metalmod/backend/MetalDevice.java#L208)
invalidates the upload when its `clustered` argument changes (lines 218–220). However, every upload
already builds the list, grid, and data texture whenever the device's clustered setting is enabled
(lines 223–240). [Pipeline setup](../../src/main/java/net/metalmod/backend/MetalRenderPassBackend.java#L192)
requests the list with `false`, followed by the grid with `true` (lines 192–200). The second request
therefore rebuilds and uploads data that is already current. Further requests with alternating
representations can repeat that work within the frame.

This is reproduced by the current verification, despite the overall gate passing:

```text
PASS  lighting on uploads the set and builds the cluster list  uploads=6 clusterBuilds=2
```

Evidence: [current render log, line 439](../../build/reports/verification-20261016-170210-106603/05-render-check.log#L439).
The earlier exact-final artifact shows the
[same result](../../build/reports/sodium-empty-lights-20261016/final-render.log#L439).
[RenderCheck](../../tools/render_check/RenderCheck.java#L1309) publishes one snapshot and draws one
quad (lines 1309–1318), but only asserts that uploads and builds are greater than zero. It accepts
the redundant six uploads/two builds. No visual failure or gameplay FPS penalty is established.

**Proposed change:** track readiness of each representation for the publication generation, or
remove representation invalidation when both have already been encoded. Preserve refresh after a
new same-frame source publication and the existing fenced resource ring.

**Required validation:** assert exactly one cluster build and three logical uploads for a new
generation; repeatedly bind list/grid/texture and switch compatible pipelines/passes without
increasing the counts. Publish another snapshot within the same frame and require exactly one new
build with correct new pixels. Exercise 0/1/16/64 lights, flat-to-clustered transitions, explicit
caller light uniforms, source removal, and ring reuse. Measure the resulting CPU/allocation effect
separately before claiming a gameplay benefit.

### P2 — Suppressed lighting can report stale extraction cost

Tracked as [BUG-050](../../bug.md#bug-050--suppressed-lighting-repeats-stale-extraction-timing-in-captures).

**Trigger:** a prior extraction followed by spectator suppression or disabled lighting when the
published snapshot is already empty.

[LevelExtractorLightMixin](../../src/main/java/net/metalmod/mixin/LevelExtractorLightMixin.java#L34)
does not extract in that state and only calls `clear()` if the snapshot contains sources
(lines 34–39). Old work counters therefore remain available. Even when
[LightCollector.clear()](../../src/main/java/net/metalmod/lighting/LightCollector.java#L37) runs, it
resets the split query/index times but leaves `extractNanos`, `examined`, and `allocated` unchanged
(lines 37–46). [MetalDevice.endDynamicLightFrame](../../src/main/java/net/metalmod/backend/MetalDevice.java#L380)
copies these values into each frame's capture statistics (lines 385–396).

The retained final default capture contains **1,763 rows**, all with zero published lights and
exactly the same extraction values: `774041` ns total, `62750` ns entity query, and `523125` ns block
index. See [frames.csv](../../build/reports/sodium-empty-lights-20261016/default-final/debug/metalmod/20261016-162554-904-Metal-8011078320655016255/frames.csv).
This pattern is consistent with the stale-counter path; it is not a measurement of that work
executing on every recorded frame. Frame intervals and unrelated native timing columns are not
invalidated by this finding.

**Proposed change:** reset per-frame extraction work statistics independently of immutable light-set
publication, including suppressed/disabled frames. If the fields intentionally represent the last
extraction, label them that way and include an extraction-generation/frame marker so consumers can
avoid repeatedly attributing their cost.

**Required validation:** active extraction followed by spectator and disabled transitions, each
with both empty and nonempty source sets. Require zero work for frames that do not extract, without
forcing unnecessary GPU publication; re-enable and require a fresh, attributable sample. Check the
CSV as well as F3. Do not use existing stale samples to estimate a static-index optimization gain.

### P3 — UMA purge check describes more behavior than it tests

[UnifiedMemoryTest.java, lines 75–78](../../src/test/java/net/metalmod/UnifiedMemoryTest.java#L75)
prints PASS after calling the purge entry point without asserting a postcondition. The
[native function, lines 185–189](../../native/src/metalmod_memory.mm#L185) is intentionally a no-op:
there is currently no idle scratch pool to reclaim. This is a callable-entry-point smoke check,
not evidence of reclamation under pressure. Rename the check accordingly; when reclamation exists,
test that live allocation contents/ownership survive and eligible scratch is actually retired.

The standalone runner's void call to this suite does **not** swallow its existing assertion
failures: [UnifiedMemoryTest.java](../../src/test/java/net/metalmod/UnifiedMemoryTest.java#L22)
exits 1 on allocation, alignment, readback and telemetry assertion failures (lines 22–39, 55–65).
The [current standalone log](../../build/reports/verification-20261016-170210-106603/06-standalone.log)
reports these checks passing. No verdict-propagation defect is claimed.

## Optimization candidates requiring measurement

| Priority | Candidate and source evidence | Scope and proposed validation |
|---|---|---|
| P2 | Reuse CPU lighting encode storage. [MetalDevice.java, lines 252–257](../../src/main/java/net/metalmod/backend/MetalDevice.java#L252) creates the grid float array, a separate record array and a direct upload buffer. [LightSnapshot.java, lines 35–38](../../src/main/java/net/metalmod/lighting/LightSnapshot.java#L35) creates another direct buffer and [PointLight.java, lines 41–48](../../src/main/java/net/metalmod/lighting/PointLight.java#L41) creates a small direct buffer for every light. | Address duplicate publication first. Encode into reusable staging storage associated with the existing ring while preserving ABI v1, camera-relative precision and GPU lifetime rules. Compare allocation rate and CPU/p95/p99 at 0/1/16/64 lights; require byte-exact layout and existing pixel checks. Allocation opportunities are concrete; an FPS gain is unmeasured. |
| P2 | Make static-light indexing demand-driven. [LightCollector.java, lines 167–171](../../src/main/java/net/metalmod/lighting/LightCollector.java#L167) collects the index and then skips every baked entry; [BlockLightIndex.java, lines 54–61](../../src/main/java/net/metalmod/lighting/BlockLightIndex.java#L54) says every currently produced entry is baked. | The index is an intentional future-consumer contract, so preserve its availability. Consider activating its maintenance when a consumer or diagnostic requests it. Validate teleports, chunk unload/reload, emitter-rich caves, source lifecycle and consumer activation. Measure true per-frame costs after correcting telemetry; do not infer a gain from stale counters. |
| P3 research | Continue compatible render passes or discard attachments whose contents are no longer needed. [metalmod_metal.mm, lines 949–965](../../native/src/metalmod_metal.mm#L949) always stores attachments; [lines 993–999](../../native/src/metalmod_metal.mm#L993) end the encoder at pass close. | Requires an explicit dependency/ownership design across uploads, texture copies, fences, readbacks and presentation, including temporal/FG capture. Validate ordering and pixel equivalence before controlled GPU bandwidth/time measurements. Simply deleting a pass-close boundary is unsafe; see [existing design limits](mcopt-techniques.md#verification-and-remaining-work). |
| P3 research | Further terrain geometry/visibility and shader-output reduction. Existing dense-scene profiling identified substantial vertex work; the empty-light Sodium specialization already removes unused lighting outputs. | Profile current final artifacts in dense native and nonempty-light scenes before choosing the next target. Preserve culling, fluids, cutout/translucency, motion and lighting contracts. No predicted performance percentage; see [dense profiling](dense-profiling.md) and [empty-light specialization](sodium-empty-lights-20261016.md). |

## Open defects and release validation priorities

These are existing findings, not new fixes. A successful broad harness run does not close a visual
or intermittent defect unless it exercises the reported circumstance.

| Priority | Existing item | Evidence needed |
|---|---|---|
| P1 release validation | [BUG-045: transient FG visibility/order/cadence failures](../../bug.md#bug-045--transient-fg-verification-visibilityordercadence-failures) | Repeat foreground startup, focus/occlusion recovery, resize/fullscreen, capped-30 delivery and uncapped fallback with saved-On restart. Retain failed attempts and verify actual generated world pixels, ordering and presentation timestamps together. A passing unchanged retry alone does not identify the original cause. |
| P1 release validation | [BUG-036: stationary temporal foliage instability](../../bug.md#bug-036--stationary-foliage-edges-reported-unstable-with-temporal) | Capture stationary and slow-turn treetop sequences with matched native/spatial controls, identical resolution/strength and reported jitter/history resets. Require pixel/video evidence; reset counts alone do not explain stationary shaking. |
| P2 | [BUG-035: dense temporal treetop slowdown](../../bug.md#bug-035--dense-treetop-views-miss-60-hz-refreshes-with-temporal) | Reproduce the same dense 5K/32-chunk scene and distinguish CPU work, completed GPU intervals, drawable waits and delivered cadence. The previous approximately 46-FPS heavy view is a historical result, not a current measurement. |
| P2 | [BUG-031: historical command-buffer creation stalls](../../bug.md#bug-031--dense-scene-slow-frames-stall-in-command-buffer-creation) | Match the original scene and settings before attribution. Later retests did not reproduce the old stall even with batching disabled; document recurrence or non-reproduction without claiming a root-cause fix. |
| P2 coverage | Deterministic dynamic-light scaling | Complete 0/1/16/64-source native/spatial/temporal comparisons, plus flat versus clustered and matched lighting-off controls. Record source count, dropped/overflowed lights, uploads, allocation, CPU, completed GPU metrics and frame tails. The [Phase 6 record](../phase6-plan.md#final-verdict-against-those-gates-2026-09-25) explicitly carried the deliberate 16/64-light measurements forward (lines 202–216). |
| P2 coverage | Current Sodium empty-light specialization with temporal/FG | Test empty→nonempty→empty sources and explicit overrides under temporal/FG, resource reload, dimension/reconnect and window changes. The specialization's final ordinary/offscreen tests do not by themselves establish live temporal/FG compatibility; [its report](sodium-empty-lights-20261016.md) states that limit. |

P1 above means evidence needed before a broader release-quality claim; it does not assert a newly
discovered crash or prescribe enabling an experimental feature.

## Defaults and measurement rules to retain

- Keep private render-target storage off: the existing route measured a regression.
- Keep fused indexed draws and late drawable acquisition experimental/off: their reduced crossings
  or changed scheduling did not establish a useful gameplay FPS gain. See [fused draws](fused-draws.md)
  and [late acquisition](late-drawable-20261016.md).
- Preserve shared UMA resources, utility submission batching, the lighting texture ABI and the
  scissor/viewport convention. Do not replace the light table with dynamically indexed uniform
  arrays.
- Report render throughput, completed offscreen throughput and observed presentation events
  separately. Offscreen rendering produces no new displayed frames; presentation timestamps do not
  establish physical refresh or input latency. Native API wall time is not GPU execution time.
- Keep profiling instrumentation out of final throughput comparisons, disclose invalid/excluded
  captures, and use matched repeated trials before selecting a default. A single scene, one light
  count or one successful restart is a scoped result.


## Follow-up: footprint-kernel GPU cost

BUG-051 correctness is fixed. Eight isolated complete-native-batch GPU runs retain all variability
and show +10.0% mean cost (+4.1% median of run medians), without presentation/readback. Consider an
exact-match fast path that skips local-range work only when it is provably equivalent. Require
bit-equivalence, native appearance/depth regressions and the unchanged gameplay image gate;
no estimated game FPS gain is attached. See [all measurements](bug051-temporal-footprints-20261016.md).
