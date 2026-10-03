> 2026-10-03 implementation update: temporal gameplay inputs, reconstruction, reactive coverage
> and history lifecycle are connected. The user explicitly requested a live temporal switch,
> superseding this plan's earlier single-method UI restriction during testing. The switch selects
> spatial or temporal; it does not run both scalers in series. See
> [contract](phase7/temporal-contract.md) and [evidence](phase7/temporal-gameplay/results.md).
> Quality/performance release selection and 7C pacing remain separate acceptance work.

# Phase 7 — Native MetalFX and presentation pacing

Status: **7A spatial and 7B temporal gameplay implementation delivered (2026-10-03); release acceptance pending.**
Temporal producers/history integration are connected. Release quality/performance selection remains
pending; 7C is explicitly deferred. See [temporal gameplay evidence](phase7/temporal-gameplay/results.md)
and [contract v1](phase7/temporal-contract.md). The 2026-10-02 offline foundation preceded this integration.
Baseline inspected: `ee49799`, Minecraft 26.2 native Metal backend.
This document defines acceptance gates; implementation results are recorded separately.

User-confirmed controls: **Super Resolution On/Off, percentage/strength and a live Temporal Upscaling toggle.**
Ship **one upscaling approach**, chosen by measured performance and image quality. Spatial,
temporal or a justified combination are implementation candidates, not separate player modes.
The user-requested temporal toggle selects the candidate during testing; there is no automatic runtime switch between competing methods.

## 1. Starting point and scope

Phase 6 is closed with named carryovers; see [phase6-plan.md](phase6-plan.md),
[lighting-abi.md](lighting-abi.md), and [ROADMAP.md](../ROADMAP.md). Preserve its dynamic-light
behavior and ABI. Capture native-resolution lighting Off/On baselines before changing resolution.
Dense-light performance, static-index correctness and downstream ownership remain assigned to
Phase 8; unshadowed light leakage remains Phase 8B work. Include offhand, chunk crossings,
dimension changes and reconnects in Phase 7 regression sessions.

The checkout, rather than stale feature descriptions, determines what must be built:

| Observed implementation | Consequence for Phase 7 |
|---|---|
| `native/CMakeLists.txt` builds `metalmod_metal.mm` and `metalmod_memory.mm`, with no MetalFX source or framework link | Add native scalers and their build integration; none can be assumed reusable |
| `MetalSurfaceBackend.blitFromTexture()` receives the engine's main colour view; `present()` copies it to a drawable | Presentation alone is too late to separate the HUD from the world |
| `GameRendererMixin` currently records hook diagnostics only | Discover and verify the actual 26.2 world/UI boundary before adding hooks |
| `MetalNative` owns backend FFI; `MetalBridge` is the retained UMA surface | Put new backend MetalFX bindings beside backend bindings, not in retired interop code |
| Old scaling controls and Vulkan interop were removed | Introduce new, tested configuration; do not restore old wrappers |
| Main-render-target scaling previously broke GUI clipping and froze input | World-only resolution changes and native UI/input mapping are a release blocker |
| GPU query/timing support is incomplete | Pixel ratios and drawable wait cannot establish GPU savings |

At planning time, `AGENTS.md` described existing MetalFX files and smoke coverage that were absent
from the baseline checkout. The 2026-10-01 implementation reconciles those claims with actual spatial
coverage; temporal and display pacing remain deferred. Its build commands and architectural rules remain applicable, but those Phase 7
status claims are not proof of implementation. Reconcile them when implementation begins. The
packaged description in `fabric.mod.json` also still advertises the retired Vulkan/MetalFX path;
correct it as part of the implementation's documentation/metadata pass.

Deliver in order: **7A integration and spatial reference → 7B evaluate and ship one Super Resolution
approach → 7C frame generation/pacing**. 7A is an engineering baseline, not a commitment to ship
multiple upscalers. This revises the older roadmap's requirement to deliver both spatial and temporal
modes. Do not call the whole phase complete when only the integration prototype is done. Shaderpacks,
Sodium/Iris, ray tracing, dynamic resolution, HDR conversion and unrelated renderer rewrites are
outside this phase. Define interfaces that Phases 8–9 can extend without introducing HDR now.

## 2. Player controls and percentage semantics

Place a **Super Resolution…** page under Options → MetalMod… and the same Mod Menu entry point.
It has exactly two upscaling controls:

| Control | Behavior |
|---|---|
| `Super Resolution: OFF / ON` | One click toggles the preference. Off renders at native framebuffer resolution and skips all upscaler passes. Default: Off |
| `Strength: 25%` | One click cycles **0% → 25% → 33% → 50% → 0%**, corresponding to render scales **100% → 75% → 67% → 50%**. Default remembered strength: 25%. Editable while Off; changing it does not enable upscaling |

Define strength as the percentage reduction in **each dimension of the 3D scene**:
`render scale = 100% - strength`. Higher strength renders fewer scene pixels and asks the chosen
approach to reconstruct more of the output. This is a resolution control, not a sharpness slider.
Show render scale and actual dimensions beneath it. Keep the single preset button requested by the
user; a slider snapping to the same values may replace it, but do not add a second strength control.
Add persistent explanatory text and a tooltip; support keyboard activation and narration as well as mouse clicks.
Keep the labels short enough for the existing settings layout.

| Render-scale preset (100% minus strength) | Approximate native scene pixel count | Approximate scene pixel reduction |
|---|---:|---:|
| 50% | 25% | 75% |
| 67% | 44.9% | 55.1% |
| 75% | 56.3% | 43.8% |
| 100% | 100% | 0% |

For output dimensions `W × H` and preset `s`, start from
`w = max(1, floor(W*s/100))`, `h = max(1, floor(H*s/100))` for a nonzero drawable. Validate against
the selected scaler's supported dimensions, ratios and any alignment requirements. If adjustment is
required, show the actual dimensions and compute the actual ratio, rather than reporting the
nominal preset as exact. Zero-sized/minimized surfaces suspend rendering instead of allocating 1×1
frames. Preserve the output aspect ratio to pixel-rounding tolerance.

Example at 3840×2160 and 75%:

```text
Super Resolution: ON          Strength: 25%
Render scale: 75%
Scene 2880×1620 → Display 3840×2160
Scene pixels: 56.3% of native (43.8% fewer). HUD: native resolution.
```

This is the visible percentage of scene pixel work. It is **not total GPU work saved**: geometry,
lighting collection, uploads, CPU simulation, UI and the scaler have separate costs. Make no FPS
promise from this number. Show actual performance separately in F3/F8.

At **On + strength 0% (render scale 100%)**, retain the preference but bypass MetalFX and jitter;
show `Native (100%)`.
At **Off**, retain the remembered preset but show effective scale 100% and `Native (Off)`.
Thus the preset button never changes the On/Off setting. No hidden frame generation is enabled
by the upscaling toggle.

Proposed new persisted keys: `enableUpscaling=false`, `upscaleRenderPercent=75`.
If JVM overrides are added, use the established UI > JVM > saved-file precedence. Validate malformed
or unsupported values with a single diagnostic and a safe default; ignore obsolete interop keys.
Save failures must be visible without undoing a working session setting. Round-trip test both keys.

Apply an immutable settings snapshot at a safe frame boundary. Rapid clicks coalesce to the latest
request. Do not mutate textures, scaler objects or pipelines in a GUI callback. Distinguish requested
settings, pending changes and effective operation; never display an active scaler that failed to
initialize. Unsupported backends/devices show a short reason and native rendering while preserving
the preference. No restart is required for scale or On/Off changes; backend selection still requires it.

## 3. Render integration contract

### 3.1 Discover the frame graph before changing it

Use the real Minecraft jar (`javap`/existing API tools), pass census and a GPU capture to locate:
world rendering, world-dependent post effects, hand rendering, outlines, UI composition, screenshots
and final surface submission. Record named classes/method signatures and resource producers and
consumers in this document before implementing hooks. Do not infer a world boundary from a texture
label alone or assume a historical Mixin signature still exists.

Proposed 7A ordering, subject to that discovery gate:

```text
Scene at selected resolution (world, depth, scene effects and verified hand path)
  → scene post-processing with matching dimensions
  → MetalFX spatial reconstruction to framebuffer resolution
  → native-resolution HUD, menus, text, cursor and UI previews
  → existing ordered surface presentation
```

Assign every actual post effect to a side of the reconstruction boundary. Effects that need depth
must use depth and colour in matching coordinates; screen-sized effects must use their actual target
dimensions. Verify hand depth/FOV and translucent ordering explicitly. Some effects may need a
different ordering in 7B; document the change before coding. Never globally scale every render target
or change logical window dimensions to trick the engine into low-resolution rendering.

### 3.2 Resources and ownership

| Resource | Required contract |
|---|---|
| Scene colour | World-only, `w × h`; record pixel format, alpha semantics, transfer function and colour-processing mode. Preserve current SDR appearance in 7A |
| Scene depth | Matching scene viewport; record format, clear value, depth range, reversed-depth convention and sampling orientation |
| Reconstructed colour | `W × H`, GPU-readable for subsequent composition; format/usage must satisfy the scaler and compositor |
| UI/output colour | Native framebuffer pixels, independent of scene scale; UI atlases and picture-in-picture targets retain their established sizes and Y-flips |
| Motion (7B) | Explicit format, resolution, vector direction/units, Y orientation and jitter inclusion; current/previous transforms use consistent origins |
| History (7B/7C) | Per surface/world generation, keyed by dimensions, formats and algorithm; validity and reset reason are explicit |

Apple's spatial descriptor exposes input/output formats, dimensions, colour processing and device
support. Use those checks rather than guessing from the GPU name.
[Apple spatial descriptor](https://developer.apple.com/documentation/metalfx/mtlfxspatialscalerdescriptor)

Keep allocations shared by default. Inspect actual MetalFX texture usage/storage requirements in
the installed SDK; if a specific target requires another storage mode, isolate and measure that
exception. Do not globally enable the private-texture experiment. Preserve upload batching.
Determine RGBA scene/BGRA drawable conversion and gamma behavior explicitly; prevent double gamma,
channel swaps and redundant full-screen copies.

Give one backend frame coordinator ownership of scaler objects, resource generations and teardown.
Reuse resources until size/format/settings change. Stage a complete replacement, validate it, then
publish it atomically; retain old resources until all GPU references complete. Bound retired
generations so resize storms cannot accumulate unlimited memory. Handle failed creation, interrupted
resize, world unload, resource reload and shutdown without dangling Panama handles.

Encode reconstruction after its inputs are complete and before UI consumes its output. Preserve
the backend's queue dependency chain, including pending utility work. Do not introduce a second
unsynchronized presentation queue or per-frame CPU `waitUntilCompleted` calls. Define C ABI ownership,
null/error behavior, exact-width fields and matching Java layouts before adding bindings.

The Off path uses the existing native-resolution rendering route, with no scene scaling, motion
pass, jitter, history update or MetalFX encoding. Retire retained resources safely. Menu-only frames
also bypass reconstruction. A transition failure must not strand the renderer with scaled UI.

## 4. Implementation milestones and stop conditions

### 7A.0 — Baseline and integration proof

- [ ] Run all five existing offline gates and save baseline results with commit, instance and hardware.
- [ ] Capture the same native-resolution route with dynamic lighting Off and On; preserve the flat
      lighting default. Save screenshots of world edges, text, inventory items and player preview.
- [ ] Record the actual engine frame graph and hook signatures described in §3.1.
- [ ] Prove a world/UI split at 100% with no scaler. Add a contrasting scene/UI test pattern and
      verify GUI scale, clipping and mouse hit positions. Check post effects and hand rendering.
- [ ] Probe SDK/API availability and runtime support separately for spatial, temporal and interpolation.
      Record supported formats/ratios and descriptor creation results on the test machine.

**Stop condition:** if world/UI separation or hook application cannot be demonstrated, do not
reduce the main target as a shortcut. Resolve the frame boundary first.

### 7A.1 — Native spatial scaler and offline proof

- [ ] Add `native/src/metalmod_metalfx.mm` and an owned C API header under `native/include/metalmod/`;
      add MetalFX framework/build integration and `MetalNative` bindings. These files are new work.
- [ ] Implement capability query, create/recreate, encode and release, plus structured error status.
- [ ] Add supported-device smoke tests for all sub-native presets, odd sizes, format validation,
      recreation and teardown. Unsupported hardware must explicitly report a skip and still exercise
      fallback tests; a skip cannot count as proof of active scaling.
- [ ] Add pixel tests for orientation, channel order, colour ramps, edge coverage and native UI.
      Use invariant/tolerance checks rather than assuming identical MetalFX pixels across OS versions.

**Exit evidence:** actual MetalFX encoding is observed, output changes as expected, invalid setup
fails safely and resources survive repeated recreation with Metal validation enabled.

### 7A.2 — Runtime controls, lifecycle and comparison baseline

- [ ] Add the two controls, persistence and requested/effective telemetry from §2.
- [ ] Connect scene resolution and reconstruction to the proven frame boundary.
- [ ] Rebuild every affected world attachment/post target coherently; leave UI coordinates native.
- [ ] Implement fallback: validate before switching; if creation fails, use native rendering on the
      next safe frame and expose the reason. If failure occurs after a reduced scene is rendered,
      use a validated plain scale/copy for that frame when the GPU remains usable, then return to
      native. Never sample incomplete output. Device/command-buffer failures follow normal backend
      recovery; a same-frame recovery is not guaranteed after a GPU failure.
- [ ] Prevent retry loops: log once per failure/configuration generation; retry on an explicit toggle,
      relevant capability/resource change or other documented recovery event.
- [ ] Complete §6 verification and record quality/performance before marking 7A done.

### 7B — Evaluate candidates and release one approach

**Planning choice: evaluate temporal as the preferred final candidate, with spatial as the measured
reference.** The hypothesis is better reconstruction of fine edges in motion at reduced resolution;
this is not a claim that it already wins on Minecraft or this hardware. Its motion/history cost and
ghosting risk can outweigh the benefit. Make the final choice from §6's actual results, using the
following decision gate rather than shipping both and asking the player to choose:

| Candidate | Selection rule |
|---|---|
| Temporal | Choose if motion/history correctness passes, quality improves over spatial in the representative still/motion scenes, and total frame-time benefit over native remains repeatable at the intended strengths |
| Spatial | Choose if temporal has unresolved artifacts, unacceptable p95/memory cost or no useful quality advantage relative to its cost; document the rejected candidate and remove it from the release path |
| Combined | Consider only if the measurements identify a specific remaining defect and the combination improves it within the same frame-time/memory budgets. Define one coherent pipeline; do not simply run two complete upscalers in series |

Compare native, spatial and temporal at identical scene/output sizes and lighting settings. Record
image-quality observations, mean/p95/p99 frame intervals, memory and scaler cost for all three
sub-native strengths. The 75% render preset is the initial target for balancing quality and speed;
confirm or revise the default based on results. Correctness is mandatory; for candidates that pass,
prefer the demonstrated quality gain while retaining useful speedup. If results are equivalent
within variation, choose the simpler/lower-cost implementation. If none pass, do not ship the feature.

Ship only the winner in the active rendering path; retain comparison evidence/tests without
maintaining an unused production upscaler. Unsupported hardware or a failure of the winner falls
back to **native rendering**, not a second upscaling algorithm. The user sees only Super Resolution
and strength; algorithm names belong in developer diagnostics. A later algorithm replacement needs
the same comparison gate. No measurements exist yet, so the winner is an explicit implementation
decision, not a benchmark result invented during planning.

For the temporal candidate:

- [x] Inspect the selected temporal API and define a versioned colour/depth/motion/jitter/exposure
      contract. Record supported scale ranges; never silently clamp a requested preset without
      reporting its effective dimensions.
- [x] Add deterministic projection jitter only to the scene. Keep HUD, input, culling/frustum margins
      and picking consistent with the unjittered camera. Test signs with known camera translations.
- [x] Generate camera and object motion using previous/current render-time transforms. Handle
      camera-relative origin changes, moving blocks, animated entities, first-person hands and newly
      appearing geometry. Camera-only depth reprojection is insufficient for independent motion.
- [x] Define coverage and rejection behavior for particles, water, translucency, animated textures,
      sky and disocclusion. Do not fabricate valid vectors where previous geometry is unavailable.
- [x] Reset history on first use, Off/On, scale/format/algorithm changes, resize, world/dimension change,
      disconnect/reconnect, teleport/camera cut, incompatible FOV/projection change and resource reload.
      Decide and test reset behavior after a long pause, minimization and missing frames.
- [ ] Validate static subpixel edges, moving mobs/items, fast turns, newly uncovered surfaces, rain,
      water and dynamic-light changes with recorded sequences, not only screenshots.
- [ ] Reject the temporal candidate if its input contract or visual acceptance is incomplete.
      Record the decision, then validate the single selected approach through all release gates.
      Selecting spatial with evidence can complete 7B; delivering both algorithms is not required.

Implementation uses render-state root transforms plus bounded depth-validated pose correspondence;
unknown/ambiguous deformation is reactive. The hand is composed natively after world reconstruction.
Automated evidence covers a copied bamboo/river world, not every representative release scene.

Consult Apple's [temporal scaler descriptor](https://developer.apple.com/documentation/metalfx/mtlfxtemporalscalerdescriptor)
and installed SDK for precise input and support requirements. Freeze the conventions in the plan
before implementation; do not copy jitter/motion signs from another renderer without validation.

### 7C — Frame generation and display pacing, independently gated

Retain this roadmap milestone, but **upscaling On does not authorize automatic frame generation**.
Develop it behind a developer-only flag, Off by default. A player-facing frame-generation control
requires a later product decision; it must not turn the two-state upscaling switch into a mode cycle.
If that decision is deferred, record 7C as deferred rather than declaring all Phase 7 complete.

- [ ] Probe interpolation independently and verify its selected API works with the backend command
      model. Apple exposes both ordinary and Metal 4-specific support queries; do not assume a full
      Metal 4 backend rewrite is required just because interpolation was introduced with Metal 4.
      [Apple interpolation descriptor](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatordescriptor?language=objc)
- [ ] Separate simulation/rendered frame IDs from display/generated frame IDs. Interpolated frames
      must not tick Minecraft, republish lights, advance temporal scene history twice, or prematurely
      retire the lighting/staging rings currently associated with surface presentation/acquisition.
- [ ] Establish one drawable/presentation owner and bounded queues. Integrate `CAMetalDisplayLink`
      scheduling without racing the current `nextDrawable` loop or calling game logic on its callback.
      Display-link updates provide a drawable and scheduling timestamps.
      [Apple display-link updates](https://developer.apple.com/documentation/quartzcore/cametaldisplaylink/update)
- [ ] Keep UI readable and undistorted; specify how the native UI layer is composited onto real and
      generated frames, and how its age relates to input. Discard stale interpolated frames after cuts.
- [ ] Test FIFO/immediate behavior, FPS limits, variable refresh, missed deadlines, monitor changes,
      minimize/restore and shutdown. Fall back to normal presentation when interpolation/pacing fails.
- [ ] Record rendered FPS, displayed FPS, generated/dropped frames and delivery intervals separately.
      Compare latency using a documented input-to-display measurement method; FPS is not latency.
      Establish and record an acceptable latency budget before promotion. Without latency evidence,
      7C remains experimental even if displayed FPS rises.

## 5. Detecting problems during implementation

This is a living risk register, not a guarantee that every defect can be predicted. For every failed
gate: save a minimal reproduction and evidence, identify the affected milestone, add a regression
check that demonstrably fails on the defect, and record a confirmed bug in [bug.md](../bug.md).
Keep unverified risks here rather than inventing confirmed BUG entries. Update this table and
[HANDOFF.md](../HANDOFF.md) after each milestone. Do not advance past a failed exit criterion.

| Risk / symptom | Detection before release | Response / owner milestone |
|---|---|---|
| UI blur, vanished items, flipped preview or frozen/misaligned input | Native UI test pattern; in-game inventory, GUI scales, tooltips and mouse clicks at every preset; BUG-001/025 pixel regressions | Stop 7A integration; repair world/UI boundary and preserve existing scissor/Y-flip rules |
| Only final blit shrinks/scales while world still renders full size | Inspect actual world attachments, pass viewports and scene pixel counters in a GPU capture | 7A must prove reduced scene rendering; a scaled final image alone fails |
| Black frame, channel swap, gamma shift or clipped depth effects | Colour ramps, corner markers, alpha edges, screenshots and Metal validation; inspect each post target size | Correct formats, transfer function, usage and pass ordering in 7A |
| Resize/toggle crash, leak or stale texture | Repeated resize/toggle cycles, resource generation logs, GPU completion checks, memory plateau | Fix lifetime/atomic replacement in 7A; bound retired generations |
| API absent, unsupported ratio or nil scaler creation | Capability denial and creation-failure injection; packaged-jar launch on supported/unsupported paths | Fall back to native; do not load a different upscaling method or break backend startup |
| Hook compiles but never runs | Mixin diagnostics plus frame-boundary counters in an actual launched client | Verify named signatures and runtime annotation retention before proceeding |
| Light changes lag, duplicate work or wrong buffer retirement | Phase 6 pixel suite, moving light routes, published/rendered/displayed frame IDs | Keep ABI v1 and one light publication per real rendered frame; audit 7C cadence changes |
| Temporal trails, shimmer or disappearing geometry | Moving-object and disocclusion recordings; debug motion/jitter views; history reset assertions | Block 7B promotion until motion coverage/rejection is correct |
| A stalled frame grows into input lag or presentation deadlock | Queue-depth, missed-deadline and drawable-ownership logs; rapid focus/monitor changes | Bound queues, drop stale generated frames and revert pacing in 7C |
| Lower resolution makes performance worse | Matched repeated route runs; scaler/pass cost and allocation counts; CPU-bound control scene | Investigate overhead/copies; no performance claim or automatic preset change |
| UI says On while scaler is unavailable or bypassed | Requested/effective state assertions plus injected failure, Off and 100% runs | Show effective Native and reason; preserve user preference |
| Tests pass while feature is never used | Assert active encode count, scene/output dimensions and hook counters; intentionally disable/wrong-order the path to verify the check fails | Repair the test before accepting any milestone |

Minimum F3/F8 additions: requested toggle/preset, effective implementation, scene/output dimensions,
actual scene pixel percentage, bypass/fallback reason, configuration/resource generation, scaler
creation/failure/encode counts, history resets by reason, and allocation/retirement counts. Include
OS/GPU/build and settings in captures. Keep diagnostics bounded and detailed captures opt-in.
Preserve existing CPU wait labels; report GPU timing as unavailable unless independently measured.
Use supported timestamp counters or GPU profiling to isolate scaler cost; do not sum overlapping
command-buffer durations and label the sum frame GPU time.

## 6. Verification and acceptance

### Mandatory offline gates for implementation changes

Run in dependency order (build before binaries). Never use Gradle or stub jars.

```bash
./scripts/build_mod.sh
./native/build/metalmod_smoke
./tools/shader_inventory/run.sh
./tools/render_check/run.sh

INSTANCE="${METALMOD_MC_INSTANCE:-$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2}"
CLASSPATH="build/classes:build/test-classes:$(python3 scripts/build_classpath.py "$INSTANCE")"
/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home/bin/java \
  --enable-native-access=ALL-UNNAMED \
  -cp "$CLASSPATH" net.metalmod.StandaloneTestRunner
```

Require build SUCCESS, native ALL CHECKS PASSED, static 87/87 and post 9/9 with no diagnostics,
RENDER CHECK PASSED and ALL TESTS PASSED SUCCESSFULLY. Preserve all baseline assertions (currently
documented as 167; record the actual run count) and add Phase 7 checks. Re-run shader inventory with
dynamic and clustered lighting enabled as in [TESTING.md](../TESTING.md). A sandbox `no Metal
device` failure is an environment blocker: obtain host GPU access and rerun; never record it as a pass.

Extend standalone tests for strength-to-render-scale mapping, preset cycling/math, malformed config,
UI/JVM/file precedence, immutable
pending settings, fallback selection, resource generations and history-reset rules. Native smoke and
pixel tests must exercise real supported MetalFX operations, plus explicit failure paths. Do not use
test doubles as the only evidence for Metal behavior.

### In-game matrix

| Dimension | Required cases |
|---|---|
| Controls | Off; On at 50/67/75/100%; preset changes while Off; Off/On remembers preset; save/relaunch; rapid mixed clicks |
| Surface | Retina and odd pixel sizes; window/fullscreen; GUI scales; resize while rendering; minimize/restore; monitor/backing-scale change |
| Content | Menu-only; Overworld/Nether/End; terrain, cutout foliage, water, particles, entities, hand, outlines, weather and vanilla post effects |
| UI | HUD/F3/chat/text/crosshair; inventory/player preview/items/tooltips; world-selection list; mouse hit locations; screenshots |
| Lighting/lifecycle | Lighting Off/On and clustered path; held/offhand/dropped/moving lights; chunk boundary; dimension change; disconnect/reconnect; resource reload |
| Failure | MetalFX denied; failed allocation/create/encode; invalid preset; missing drawable; unavailable backend; teardown with frames in flight |
| Temporal only | Camera turns/cuts, FOV changes, object motion, disocclusion, pause recovery and all reset triggers |
| Interpolation only | Render/display rate mismatch, late frames, input latency, vsync modes and normal-presentation fallback |

Run at least 100 mixed scale/toggle/resize transitions and a 10-minute steady scene soak. Require
no crash, validation error, corrupted frame, stuck input, unbounded memory growth or repeated scaler
allocation once settings stabilize. Run packaged-jar validation so a local development dylib cannot
hide missing native resources or symbols. Record which hardware/OS combinations were actually tested.

### Performance and image-quality evidence

Use F7 routes and F8 captures with fixed output resolution, render distance, lighting, storage mode,
weather/time where controllable, vsync and FPS cap. Warm up pipelines/chunks. Use at least three
paired native/preset runs, alternate ordering, and report each run plus aggregates; do not benchmark
with a paused menu as a substitute for gameplay. Include a GPU-heavy scene and a CPU-heavy control.

Record mean/p95/p99 real frame intervals, transition hitches, memory/allocation counts and measured
scaler cost when available. Keep displayed/generated FPS separate in 7C. Compare Off after changes
against the saved native baseline; also compare On+100% bypass against Off. Investigate a repeatable
>5% mean or p95 regression in either native comparison before release (a proposed regression budget,
not an existing measured result). A performance benefit at a sub-native preset requires repeatable
improvement outside run-to-run variation; record neutral/negative results honestly.

Save matched native/preset images and motion clips covering §6's content. Require native-resolution
UI, no orientation/colour/ordering defects and no unexplained temporal trails. Lower-detail imagery
at lower presets is an explicit quality tradeoff; document it rather than treating all presets as
visually equivalent. Agree the temporal quality and interpolation latency acceptance record before
promoting either path. Automated pixel checks cannot replace in-game visual review.

## 7. Files, evidence and handoff

Expected implementation touchpoints (new names are proposals):

| Area | Files / responsibility |
|---|---|
| Configuration and controls | `MetalConfig`, `MetalModConfigScreen`, new upscaling settings screen and immutable settings snapshot |
| World/render boundary | Focused verified mixins; `metalmod.mixins.json`; backend frame coordinator and scene targets |
| Native integration | New `metalmod_metalfx.mm`/header, `native/CMakeLists.txt`, `MetalNative`; retain device/queue ownership |
| Presentation/lifecycle | `MetalSurfaceBackend`, `MetalDevice`, `metalmod_metal.mm`; 7C additionally audits lighting and utility-ring cadence |
| Telemetry | `MetalModDebugEntry`, `PerformanceCapture`/`PerformanceRecording`, native capture ABI where needed |
| Tests | `native/tests/metal_smoke.mm`, `tools/render_check/RenderCheck.java`, shader inventory and standalone runner coverage |
| Documentation | This plan, `HANDOFF.md`, `ROADMAP.md`, `TESTING.md`, confirmed defects in `bug.md`, accurate `AGENTS.md` status and package metadata |

Store durable summaries and small reference evidence under `docs/phase7/` when implementation
starts; link larger captures by stable location. Each milestone record includes commit/build,
OS/GPU/SDK, instance, settings, commands/results, screenshots/clips, benchmark pairs, failures and
remaining limits. Temporary `/tmp` logs alone are not a durable acceptance record.

Pending engineering decisions are owned by explicit gates: exact world/UI hooks and post ordering
(7A.0), legal formats/usage/ratios (7A.0–7A.1), temporal vector and exposure conventions (before 7B),
and interpolation UI/cadence/latency policy (before 7C). Resolve and write down each decision before
its dependent implementation; do not silently fill a gap with an assumption.

Completion checklist:

- [ ] **7A accepted:** the requested two controls work, native UI/input is preserved, fallback and
      lifecycle cases pass, all offline gates pass, and quality/performance evidence is recorded.
- [ ] **7B accepted:** quality/performance comparisons select one approach, its applicable input and
      lifecycle gates pass, the release has only that upscaling path with native fallback, and rejected
      alternatives are documented. If temporal is selected, motion coverage and history resets pass.
      Absence of support on one machine is not universal validation.
- [ ] **7C accepted or explicitly deferred:** independent interpolation, pacing and latency evidence;
      any release/UI decision is recorded. Deferred work remains visible in phase status.
- [ ] Update roadmap/handoff with the exact delivered scope and remaining risks; no broad Phase 7
      completion claim from a spatial-only demo or successful shader compilation.

Planning verification (2026-09-30): repository sources/status and linked project documents reviewed;
Apple API references checked; documentation links and diff checked. No renderer code changed, no
game launched and no fresh build/runtime/performance result is claimed by this planning change.
