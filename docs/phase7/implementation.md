# Phase 7 implementation and evidence — 2026-10-01

Phase 7 remains **in progress**. The world-only spatial reference is implemented and opt-in.
7A engineering integration passes the offline and packaged runtime checks below; its complete
quality/performance acceptance is pending. 7B has no measured release winner. 7C is explicitly
**deferred**, with frame generation disabled and no display-link presentation owner introduced.

## Delivered behavior

- Options → MetalMod… → Super Resolution… has the requested On/Off switch and strength cycle
  0 → 25 → 33 → 50 → 0%, corresponding to scene scales 100/75/67/50%. Default Off, remembered 25%.
  Strength can change while Off. UI choices override JVM flags, which override saved file values.
  Settings are immutable snapshots at the initial frame clear. Invalid strength falls back to 25%.
- JVM flags: `-Dmetalmod.superResolution=true` and `-Dmetalmod.superResolutionStrength=25`.
  File keys: `enableSuperResolution`, `superResolutionStrength`. Changes apply at a frame boundary.
- Native Off, On+0%, menus without a world and unsupported/failing MetalFX bypass the scaler.
  F3, settings text and F8 distinguish requested from effective behavior, dimensions and actual pixels.
  F8 appends SR counters/status/reason codes; `sr_gpu_ns=-1` explicitly means unavailable.
- A scene target owns reduced RGBA8 colour and D32 depth. The real client framegraph derives its
  translucent/item/particle/weather/cloud targets and post-chain targets from that scene size.
  The persistent entity-outline target follows it through `LevelRenderer.resize`.
- Verified named-client order: native resize check → scene-target substitution at the initial clear
  → scene Globals → world/hand/screen effects → entity outline → post chain → reconstruction before
  `FogRenderer.endFrame` → original native target and native Globals → native-depth clear → GUI.
  The native world clear is bypassed during scaling; complete reconstruction replaces its colour.
  Automatic world screenshots are deferred to the reconstructed native image before GUI.
- Window/backing dimensions, extraction, unjittered projection/culling/picking and input coordinates
  stay native. No viewport or scissor convention is changed. Lighting publication still ends once at
  the existing real surface presentation; no extra simulation/light/staging-ring advancement occurs.
- The ordinary `MTLFXSpatialScaler` works on the existing device/ordered command queue. No Vulkan,
  MoltenVK, separate device or Metal 4 rewrite is introduced. SDK 27.0 requires a **private FX output**;
  only that reconstruction allocation is private. Scene/native targets keep the existing shared default
  (and explicit private-texture experiment). Reconstruction uses a same-format GPU copy to the native
  colour target; the existing presentation shader handles RGBA→BGRA. Vanilla SDR is perceptual;
  no sRGB view, extra gamma transform, HDR conversion or sharpening slider is introduced.
- Each configuration/resource generation validates native scaler, output, scene colour/depth and a
  plain recovery pass before switching. Resources reuse across steady frames. GPU completion retains
  native state; a queue wait retires old generations only at transitions, bounding resize accumulation.
  CPU encode rejection discards the uncommitted buffer and uses the validated plain pass for that
  reduced frame, then renders native. GPU errors latch for subsequent native fallback; there is no
  promised recovery after an actual GPU fault. Failures log once and do not recreate per frame.
  Explicit settings change, resize, level change or UI shader/resource preload permits retry.
- Developer-only failure checks: `metalmod.fxDeny`, `metalmod.fxFailCreate`, `metalmod.fxFailEncode`.
  `-Dmetalmod.fxRecreateEvery=30` deliberately recreates resources every 30 frames for runtime stress;
  omit it for normal play and performance measurements.

## Native rendering prerequisites fixed during validation

**BUG-026:** SkyRenderer retains the target supplied to its constructor. It could retain a retired
scene, crash on missing attachments after recreation, or draw sky into the wrong target after a toggle.
A focused runtime-retained accessor updates that cached target at both scene and UI boundaries.

**BUG-027:** Metal pipeline depth-attachment format must match the actual framebuffer even when
there are no depth tests. Pipelines now cache a borrowed compatible state for depth/no-depth passes,
without altering the declared depth compare/write behavior. The original owns the variant. GUI draws
with and without depth have explicit pixel checks; strict API validation fails on the old behavior.

**BUG-028:** Some vanilla logical std140 buffers end at 12/40/56 bytes whereas generated MSL structs
occupy 16/48/64 bytes. Keep the logical size, field offsets and upload range; allocate 16 additional
backing bytes for uniform buffers and the transient arena so even its final uniform slice has space
for struct tail padding. Lighting ABI v1 remains 2064/16/8849 texels; internal padding is not published.
Strict draw validation originally aborted on these cases and now passes the same real pipelines.

## Verified scope

Hardware: **Apple M4 Pro**, 24 GiB UMA. OS: **macOS 27.0.1 (26A434)**, SDK **27.0**, JDK 26,
Minecraft 26.2 / Fabric Loader 0.19.5. Main-tree uncommitted implementation; artifact hash recorded in
[build-result.txt](build-result.txt). No Gradle or stubs used.

| Gate | Command / result | Durable evidence |
|---|---|---|
| Build | `./scripts/build_mod.sh` — SUCCESS | [build result](build-result.txt) |
| Native | `MTL_DEBUG_LAYER=1 ./native/build/metalmod_smoke` — ALL CHECKS PASSED | [native log](native-smoke.txt) |
| Shader inventory | `./tools/shader_inventory/run.sh` — static 87/87, post 9/9, no diagnostics | [inventory](shader-inventory.txt) |
| Lighting inventory | `JAVA_TOOL_OPTIONS='-Dmetalmod.dynamicLights=true -Dmetalmod.clusteredLights=true' ./tools/shader_inventory/run.sh` — 87/87 + 9/9 | [lighting inventory](shader-inventory-lighting.txt) |
| Pixels | `MTL_DEBUG_LAYER=1 ./tools/render_check/run.sh` — RENDER CHECK PASSED, **190 PASS assertions** | [pixel log](render-check.txt) |
| Standalone | documented `net.metalmod.StandaloneTestRunner` invocation — ALL TESTS PASSED SUCCESSFULLY | [standalone log](standalone.txt) |

Sandbox `MTLCreateSystemDefaultDevice` failed; GPU gates were rerun successfully with host access.
The native suite executes actual FX encodes/readback at 50/67/75%, even/odd sizes and 100 recreations,
checking colour channels, quadrants/orientation, GPU health, invalid setups and plain recovery.
The pixel suite preserves its baseline checks and exercises actual reduced colour/depth targets,
native one-pixel composition order, Off/100% bypass, resource reuse, latched failures, explicit retry,
menu restoration and 100 mixed toggle/preset/resize transitions. Standalone adds precedence, malformed
strength, floor/pixel math, immutable settings and F8 column/status contract checks.

Packaged test ran from `/tmp/metalmod-phase7-game` with only the embedded native library (no local
`native/build` directory), an offline test identity, copied dependencies and a disposable copy of the
smaller test world. The original installed mod/world/config were not replaced. Both render hooks,
level hooks, GUI rendering and framebuffer-resize hooks ran. Terrain/clouds/particles/items/outlines
use `MetalMod scene / Color`; HUD/vignette/crosshair/GUI use `Main / Color`. At 2560×1440 the actual
scene is 1920×1080; resize to 5120×2664 produces 3840×1998 (56.25% scene pixels). Strict API validation
was enabled, and **at least 100 live scene/scaler generations** completed without a MetalFX failure
or stale-sky crash after the fixes. See [runtime excerpts](runtime-proof.txt).
The 30-second sample showed zero pipeline/resource/unbound/missing-attribute/slot failures.
See [failing validation/crash excerpts before fixes](validation-before-fix.txt).

Runtime target routing and automated pixels are evidence of integration, **not a visual quality or
speedup verdict**. The GUI automation connector could not attach to the running Java application;
no comprehensive screenshot/motion/UI review is claimed. Offline identity auth/Realms errors are
unrelated to graphics; no real account token was used.

## Candidate decision and remaining acceptance

Spatial is the sole active **engineering reference**, with native fallback. Temporal is not implemented
or secretly selected: the client has no complete per-object motion/jitter/history rejection contract
for moving entities, hands, moving blocks, particles and translucency. Camera-only reprojection would
violate the plan. This input-contract failure blocks its promotion; it is not measured proof that
spatial is faster or better. No temporal/scaler history is advanced by this path.

Remaining gates before release/7A–7B acceptance:

- Matched native lighting Off/On baseline, native Off versus On+100% regression pairs, then at least
  three alternating F7/F8 pairs at each reduced preset, GPU-heavy and CPU-heavy scenes. Record
  mean/p95/p99, memory and GPU scaler cost using supported profiling; no FPS claim from pixel counts.
- Matched still/motion visual review of terrain/fine foliage/water/entities/hands/weather/post effects,
  native HUD/inventory/tooltips/GUI scales/mouse hit positions and screenshots.
- Full lifecycle/content/failure matrix: dimensions, offhand and moving lights, chunk crossings,
  disconnect/reconnect, reload, monitor/Retina/minimize/restore and 10-minute steady-scene soak.
  The completed 100-transition automated and live-recreation tests do not replace these cases.
- Select/accept one release approach from the quality/performance evidence. A spatial-only reference
  is not a claimed completed Phase 7 or a final winner.

7C is deferred: interpolation, `CAMetalDisplayLink`, rendered/displayed IDs, UI age on generated
frames, bounded display queues, missed deadlines and measured input-to-display latency have no
implementation/acceptance evidence. Existing FIFO/immediate `nextDrawable` presentation remains
unchanged and is the only presentation owner. Upscaling never enables frame generation.

API sources: installed SDK `MTLFXSpatialScaler.h`, and Apple's
[spatial scaler contract](https://developer.apple.com/documentation/metalfx/mtlfxspatialscalerbase),
[colour-processing modes](https://developer.apple.com/documentation/metalfx/mtlfxspatialscalercolorprocessingmode),
[API validation](https://developer.apple.com/documentation/xcode/validating-your-apps-metal-api-usage).

## Usable spatial checkpoint (2026-10-01)

The user confirmed the spatial pipeline is usable after input AA and BUG-030's unbounded-sampler
mipmap correction. Latest verification passes all five gates, with 194 pixel assertions. Three
new minification regressions fail against the previous backend and pass after the fix, including
real terrain textureGrad and RGSS textureLod. Latest gate evidence: [mipmap-fix](mipmap-fix/).
This is a usable spatial checkpoint, not completion of temporal selection or frame pacing.
Paired F8 benchmarks remain pending.
