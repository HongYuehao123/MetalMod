# Stage 1 backend and lifecycle implementation

Completed: 2026-10-17. Stage 1A–1D of
[the performance plan](performance-and-blaze3d-plan.md) is implemented and qualified. The final
rollout decision retains the optional pass/native-texel paths as explicit opt-ins; see the
completion results and default decision below.

## Implementation and defaults

| Area | Behavior | Default / rollback |
|---|---|---|
| Submission-ticket fences | One completion timeline per command queue; a fence snapshots its latest submitted ticket. No shared-event allocation or signal-only command buffer per fence. | On; `-Dmetalmod.fenceTickets=false` selects the retained event-fence control. |
| Full clears | Pending values retain exact texture views and queues. Matching attachment load actions absorb clears; unmatched attachments materialize before a pass can sample them. Copy, explicit-command, fence, CPU-access and presentation boundaries submit/materialize pending work. | Off; `-Dmetalmod.deferredClears=true`. |
| Encoder continuation | Consecutive ordinary logical passes may share an encoder with identical attachment view identities/subresources and load semantics. Reset resources, viewport, winding, scissor, depth bias/state, stencil reference and blend constants between logical passes. Blits, explicit work, clears and bounded batch boundaries end continuation. | Off; `-Dmetalmod.encoderContinuation=true`. |
| 2D texel fallback | Key by storage object, absolute suballocation offset, byte range, format and shader layout. Compare exact bytes to observe retained mapped writes. Reuse unchanged generations; changed contents get immutable textures retained through encoding of their consumers. | On; `-Dmetalmod.texelCache=false` disables unchanged-content reuse. |
| Native texels | Opt-in SPIRV-Cross native texture-buffer layout, only when every texel declaration in a pipeline is eligible. Verified formats: R32_FLOAT, R32_UINT, R32_SINT, RGBA32_FLOAT. Aligned ranges use direct views; unaligned/unpadded ranges use aligned immutable snapshots with the same shader layout. | Off; `-Dmetalmod.nativeTexelBuffers=true`. R8_SINT and mixed unsupported-format pipelines keep 2D emulation. |
| Lighting staging | Each fenced light-ring slot owns reusable list/grid/texture staging. Encode directly without per-light direct buffers or intermediate float arrays; clear removed records on reuse. | Always; ABI v1 and the RGBA32F 2D light texture remain unchanged. |
| Transient storage | Use the engine block allocator. Spill instead of wrapping within a frame; recycle frame generations only after GPU completion, with at most two retired generations pending after a boundary. Handle minimum-size/size-multiple semantics and oversized allocations. | Always; correctness fix. Memory retention depends on peak allocation and frames in flight; this is not a claim of lower total UMA usage. |
| Compile tasks | Release the region reference on every normal `CompileTask.doTask` return, including cancellation returns. Never clear it from a concurrent cancellation callback. | On; `-Dmetalmod.compileRegionDrop=false`. The historical 78 MB saving is not established by these tests. |
| Vignette | Skip zero RGB only for the exact verified vanilla shader fingerprint and blend equation: RGB = destination × (1 − source), alpha = destination. Retain nonzero/world-border tints, replacement shaders and enhanced composition paths. | On when the proof holds and SR/FG are off; `-Dmetalmod.skipBlackVignette=false`. |

A fence creation flushes pending work immediately, independently of blocking waits. Poll-only
consumers therefore make progress. Completed handlers can arrive out of order; only a contiguous
completed prefix advances the timeline. Closing a fence does not cancel work. Handles retain their
timeline after queue release, and finite/infinite waits use a condition variable rather than GPU
signal submissions. Production rendering/utility/FX submissions on the device queue go through
the tracked commit helper; independent native display/FG worker queues keep their own lifecycle.

Deferred-clear matching resolves parent texture views to mip and array-slice identities and checks
format. A different mip or slice cannot consume a base attachment clear. Unmatched clears at
render-pass creation encode into the caller's existing command buffer, rather than submitting and
invalidating its lease. CPU uploads/readbacks await any newly materialized clears. Partial clears
retain their scissored draw path. Y-flip/scissor conversion rules are unchanged.

Native texel creation validates format, aligned offset, padded row stride and buffer bounds before
calling Metal. The row stride must cover and align the row, including for buffer-backed textures;
see [Apple's buffer-texture API contract](https://developer.apple.com/documentation/metal/mtlbuffer/maketexture(descriptor:offset:bytesperrow:)?language=objc).
The pair cache includes the native-layout choice, so native and 2D shaders cannot alias. Pending
GPU writes to texel buffers are synchronized before opening a pass whose fallback may inspect CPU
storage. Exact comparison avoids assuming that a map/unmap counter sees all writes.

## Verification

Raw logs and disposable instances are under `build/reports/stage1-20261017/` and the adjacent
`build/reports/stage1-*.log` files. Final verification and artifact hashes are indexed there.

- Canonical build against the real 26.2 jar.
- Native smoke: **976 checks**, including poll-only fences, adjacent fences, close/lifetime,
  upload retirement, clear absorption, continuation, CPU access, distinct mip/slice clear identity,
  safe native formats, offsets, range rejection and shader pixels.
- Shader inventory: **87/87 static + 9/9 post**, no diagnostics.
- Pixel suite: **250 core checks**, and **393 with official Sodium 0.9.2 fixtures and all optional
  Stage 1 paths enabled**. Includes unchanged/changed texel generations, nested suballocations,
  aligned native and unaligned snapshot reads, logical-pass scissor reset, actual vignette RGBA
  preservation, shader-replacement rejection, existing atlas/Y-flip and temporal/FG contracts.
- Standalone suite: light ABI/staging reuse and a 64-byte transient allocator forced through spill,
  oversized allocation and 24 GPU-consumer/recycling generations, alongside existing tests.
- Benchmark-tool tests: existing analyzer/fixture guards; backend overrides are explicit launch
  arguments and recorded in comparison metadata. No Gradle or normal-instance installation.

## Copied-world observations

The first dense RD32 diagnostic uses the existing zero-based route waypoint 13, 5120×2664,
vanilla terrain, native resolution, SR/FG/Vsync off, all optional Stage 1 backend paths on, and
resource attribution on. It exited cleanly with zero resource/pipeline/binding health failures.
Across 1,753 frames it recorded **9.687 submissions/frame** and **12.573 native render passes/frame**,
against approximately **22.069** and **21.669** in the clean Stage 0 dense attribution control.
These are operation-count observations across diagnostics, not a paired throughput claim. The Mac
was locked during the initial diagnostic; exclude its FPS from performance conclusions.

Computer subsequently inspected the copied existing world at RD16, started/stopped F8, opened
and closed the creative inventory, and observed correctly rendered item icons, tooltips and HUD.
The manual capture saved **923 frames**, with the in-game shader fingerprint matching the offline
vignette proof. This exercised partial atlas clears alongside the optional full-clear/continuation
paths. The copied game closed and saved cleanly. GUI activity/capture makes this a correctness
check, not performance data.

A separate static dense offscreen ABBA compares event fences with ticket fences, bounds GPU work
at two frames in flight, and requires submitted/completed equality and unchanged draw workload.
Its indexed result is recorded separately. No inference about tile bandwidth or whole-frame GPU
speed follows from the reduced native-pass count alone. The later completion qualification below closes the lifecycle, stress and enhanced-composition
checks and retains the optional paths off by default.

## Fence completion ABBA

The retained event-fence control and ticket path ran offscreen in order **off/on/on/off** at the
same dense waypoint, 5120×2664, RD32, native, with all other Stage 1 options fixed on. Resource
attribution and GPU-stage counters were off. Every frame had matching focus/pause/menu controls;
all four runs used the same two-frame completion bound and passed the strict draw-workload gate.

| Fence path | Completed frames | Completed FPS | Render-thread CPU ms/frame | Native submissions/frame |
|---|---:|---:|---:|---:|
| Event control A1 | 1,959 / 1,959 submitted | 65.295 | 9.703 | 18.920 |
| Tickets B1 | 1,957 / 1,957 | 65.254 | 9.542 | 8.613 |
| Tickets B2 | 1,944 / 1,944 | 65.026 | 9.813 | 8.615 |
| Event control A2 | 1,944 / 1,944 | 65.009 | 9.339 | 18.925 |

Mean completed rates are **65.152 event / 65.140 ticket FPS** (**−0.019%**), effectively unchanged.
Mean CPU is **9.521 / 9.677 ms** (tickets +1.64%); the run spread is larger than this difference,
so no CPU gain is established. Submissions fall about **54.5%**, while draws remain approximately
19,161/frame. This is a fence-path comparison on the new tracked-queue implementation, not a
comparison against the old entire Stage 0 backend. It includes the diagnostic completion fences.
The exact tested intermediate jar is `76ee4054c8383fe2297cf2e62e5d1b5b6134d9e6e8f862bbbe55f1533783c3e5`.
Subsequent bounds/error-path and native-only vignette-composition safeguards are covered by the
final offline gates and final copied-world run; retain each launch's actual hash.

Raw result: `build/reports/stage1-20261017/fence-completion-result.json`. No displayed-FPS,
whole-frame GPU-time, or general workload speedup claim is made.

## Final artifact and instance protection

Final jar SHA-256: `941315e697c29b4d4474df83476eac1132bafac66d86962d7857c6dc920081d6`.
All five offline gates pass for this artifact. The final copied-world GPU-stage diagnostic exited
0 and saved **1,710 frames / 27 resource-attribution rows**, with all optional Stage 1 paths enabled.
It records **9.705 submissions/frame** and **12.642 native render passes/frame**. Completed stage
workload averages approximately **14.484 ms vertex / 6.435 ms fragment per read interval**;
these are asynchronous aggregate stage samples, may overlap, and must neither be added nor called
whole-frame GPU time. There are **344 dropped pass samples** and **zero invalid samples**; preserve
that sampling limitation. No tile-bandwidth comparison or GPU-time improvement is established.

The exact final launch manifest, capture, gate-log hashes and intermediate-artifact results are
indexed by `build/reports/stage1-20261017/verification.json`. All four disposable JVMs exited 0.
The source instance protection manifest compares **691 before / 691 after, zero changes**, including
all existing worlds, mods, config, options and version JSON. No normal HMCL-instance mod install
was performed. The initial diagnostic, manual test and fence ABBA retain their earlier explicit
artifact hashes; their results are not silently reassigned to the final jar.

## Completion validation — 2026-10-17

The follow-up uses the same final production jar above and new disposable copies under
`build/reports/stage1-completion-20261017/`. All optional Stage 1 paths are explicitly enabled in
the lifecycle runs, with launch arguments and actual copied jar hashes retained.

- Full temporal lifecycle, including odd resize, inventory/settings, pause, motion, teleport,
  FOV/render gaps, resource reload, light suppression/re-enable, moving blocks, generation stress,
  minimize/restore, dimension travel and reconnect: **476 checks / 10,926 frames, zero failures**.
  Independent stationary image registration passes at **0.034066 / 0.015740 pixels**, below the
  unchanged 0.25-pixel limit. Matched native/spatial/temporal quality images were also inspected.
- Frame-generation lifecycle and presentation: **192 checks**, including native/spatial/temporal,
  UI, resize, 5120-wide output, camera motion, minimize/restore, VSync fallback and menu. A fresh
  JVM with saved On passes **16 restart checks**. Generated/real order and protected hand pixels pass.
- Capped-30 native/spatial turning delivery: **23 runtime checks**, plus both independent final-image
  comparisons pass. Generated midpoint motion survives final HUD composition.
- A bounded test-only sampler inspects **128 actual completed vanilla compile tasks** after all
  return hooks execute: **zero retain a region reference**. This verifies release in the real
  engine; it does not establish a universal heap reduction. The historical 78 MB estimate is retired.

An early performance attempt observed F3 activation during warmup. Its control and interrupted
second launch remain archived and excluded. The benchmark now suppresses debug overlays/charts
during warmup, rejects/retries any timed overlay interruption, and writes `benchmark-controls.json`.
The optional-path analyzer also checks the actual test add-on contents, exact startup-switch order,
source/core/camera/dimensions, clean capture controls and a 1% draw-workload bound. No results from
that earlier attempt are used to select defaults.

### Optional-path performance qualification and default decision

Four fresh JVMs per fixture ran the optional startup switches **off/on/on/off**, with the same
production jar, copied source, fixed camera, output dimensions and renderer. All eight launches
exit 0, with zero logged backend health failures. Attribution/GPU-stage counters are disabled for these
captures. Test add-on contents and F3/chart controls match.

The dense RD32, 5120×2664 comparison passes the 1% draw-workload bound:

| Metric | Optional group Off | Optional group On |
|---|---:|---:|
| Mean rendered FPS | 57.492 | 58.312 |
| Render-thread CPU ms/frame | 11.316 | 10.911 |
| Mean capture p95 interval ms | 24.300 | 22.803 |
| Mean capture p99 interval ms | 44.491 | 40.284 |
| Native submissions/frame | 11.698 | 9.687 |
| Native render passes/frame | 21.612 | 12.572 |
| Draws/frame | 19,171.322 | 19,169.648 |

This scoped group comparison gives **1.43% higher rendered FPS, 3.58% lower CPU time,
17.19% fewer submissions and 41.83% fewer native render passes**. It does not isolate each switch,
measure whole-frame GPU time, or compare the entire Stage 1 backend against Stage 0.
Native texel layouts have no demonstrated throughput benefit in the vanilla fixtures: the
observed cloud texels use the deliberately retained R8_SINT fallback.

The surface RD16 runs are individually clean, but their draws range from **2,109.244 to
2,202.514/frame**, a **4.303% spread**. The analyzer rejects the matched performance comparison;
no surface speedup/CPU-gain ratio is accepted. Raw off/on/on/off FPS are **57.840 / 58.339 /
56.462 / 58.496**, and CPU ms/frame are **3.173 / 3.339 / 3.174 / 3.407**. Saved surface and dense
terrain images were reviewed. These short static fixtures do not establish a general motion-route
or tail improvement.

Qualification closes with the defaults in the table above: ticket fences, exact-content fallback
cache, staging reuse, fenced transient storage, region release and the guarded native vignette skip
are enabled; **deferred clears, encoder continuation and native texel layouts remain explicit
opt-ins**. Global activation of the optional group is declined for this release because the
accepted gain is modest and confined to one matched workload. The optional implementations and
their lifecycle/correctness checks are complete. Future default changes require their own matched
workload evidence. Raw results are `dense-performance.json` and `surface-performance.json` in the
completion report directory.


### Final closure

The final test-add-on version also passes **114 checks / 2,187 frames** with region release enabled
and **114 checks / 2,187 frames** with it disabled: **128/128 sampled tasks release the region** in
the enabled run, versus **128/128 retain it** in the control. The actual Sodium 0.9.2+mc26.2 release
and separately built adapter pass **114 integration/lighting-transition checks / 2,130 frames**
with all optional switches set. This is integration evidence; the separate Sodium release-quality
scope is unchanged.

All **15 accepted follow-up JVM launches** (including saved-On restart) finish successfully.
All logged backend health maxima are zero. The production jar is unchanged from the artifact that passed
all five offline gates; its SHA-256 and every recorded gate-log hash were rechecked. The final
benchmark-tool suite passes **16 unit tests + 11 analyzer checks**. No Gradle was used.

The final source protection audit reports **691 before / 691 after files, zero changes** across
the normal HMCL instance's worlds, mods, config, options and version JSON.
[The committed completion manifest](stage1-completion-20261017.json) records exact artifacts,
results, raw report paths, rejected performance attempts and selected defaults. Stage 1 has no
remaining implementation or required validation work under these defaults.
