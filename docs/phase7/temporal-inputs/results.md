> Historical diagnostic checkpoint. The jitter-only switch is superseded by full temporal gameplay
> reconstruction; see [current implementation and results](../temporal-gameplay/results.md).

# Temporal colour, projection and world-depth inputs — 2026-10-02

Uncommitted implementation after `6a18738`; no new commit created. First validated JAR SHA-256:
`6097b1615e1b06a35ba26de7a3bceb1d3b95e3c3ec46e05a1dd7377612d87bf2`.
Hardware/toolchain/instance match the [foundation record](../temporal-foundation/results.md).

## Implemented

- Reusable native SDR sRGB-transfer decode to RGBA16Float and encode back to RGBA8/BGRA8.
  Texel reads preserve orientation without sampling/filtering/resizing; alpha is unchanged.
  RGB overshoot is clamped at the SDR output. sRGB texture formats are rejected to prevent
  hardware decode plus software decode. Queue/usage/format/size validation and asynchronous
  failure/lifetime handling match the native temporal prototype. Optional Panama bindings added.
- Projection helper left-multiplies a clip-space translation on a copy, with X shift
  `-2*jitterX/width`, Y shift `+2*jitterY/height`. Thus positive sampling jitter shifts
  geometry left/up in Metal's top-left framebuffer. Clip Z/W and source camera matrices stay intact.
- Developer-only `-Dmetalmod.temporalJitterProof=true` applies one scene-pixel Halton sample to
  world and hand projection uploads in `GameRenderer.renderLevel`. It requires active reduced
  spatial rendering; Off/100%/menu/fallback do not jitter. The interval closes at reconstruction
  before GUI composition. Dimensions/generation/bypass restart the sequence. F3 and named hook
  counters identify the proof; this is spatial output with jitter, not temporal accumulation.
- The same proof captures scene-sized world depth on the ordered utility queue immediately
  before the hand depth clear. One shared Depth32Float snapshot is reused per scene generation
  and retired with the scene after a bounded queue synchronization. Allocation/copy failure is
  latched per generation and does not replace ordinary spatial rendering.

## Real 26.2 engine discovery

`javap -p -c` on the real Minecraft client JAR establishes this order in `renderLevel(DeltaTracker)`:

1. Copy `CameraRenderState.projectionMatrix`, compose hurt/bob/portal effects, upload through
   `ProjectionMatrixBuffer.getBuffer(Matrix4f)` (bytecode offset 297), render the world.
2. Construct hand perspective, upload through `getBuffer(Projection)` (offset 476).
3. Clear the main scene depth to zero via `CommandEncoder.clearDepthTexture` (offset 499).
4. Draw the hand via `renderItemInHand` (offset 508).

Therefore the depth attachment at the final world/UI reconstruction boundary cannot be treated
as the world's depth: it was cleared and replaced by hand depth. Future motion processing needs
the saved world depth and a separate hand treatment. Depth uses reversed Z in these projections;
near/far are supplied in reversed order to JOML with the device's zero-to-one depth convention.
The world projection hook works on a copy after extraction; culling/picking matrices are unmodified.
Subpixel culling margins still need release validation.

## Verification

All five gates pass with host GPU access:

| Gate | Result |
|---|---|
| `./scripts/build_mod.sh` | SUCCESS; native/Java compilation and packaged JAR |
| `MTL_DEBUG_LAYER=1 ./native/build/metalmod_smoke` | ALL CHECKS PASSED; temporal and transfer shaders actually executed |
| `./tools/shader_inventory/run.sh` | static 87/87, post 9/9, no pipeline diagnostics |
| `MTL_DEBUG_LAYER=1 ./tools/render_check/run.sh` | RENDER CHECK PASSED; 203 assertions, including nine new checks |
| Canonical `net.metalmod.StandaloneTestRunner` | ALL TESTS PASSED SUCCESSFULLY |

Logs: [native](smoke.txt), [shaders](inventory.txt), [pixels](render.txt),
[standalone](standalone.txt).

Native transfer tests check all 256 values in three asymmetric rows for both RGBA/BGRA, CPU
transfer expectations, alpha, unchanged source, output clamping, illegal format/size/queue and
release with work in flight. Pixel checks use a real vanilla GUI pipeline with a subpixel edge:
zero jitter and ±X/±Y select the predicted pixels. The coordinator regression clears world depth
to 0.75, snapshots it, then clears hand depth to 0; both values read back correctly. Disabled
proof adds no depth copy, and the scene-only proof closes before GUI.

Standalone checks include perspective and orthographic/composed projection math, multiple depths,
camera-matrix immutability, identical world/hand sample, generation resets and ordinary-path bypass.
ASM reads the actual client bytecode to require unique projection/depth invocation targets and
their ordering, plus runtime-retained Redirect annotations in the compiled mixin.

## Packaged-game validation

The final packaged JAR was launched from a fresh copied-world directory with `MTL_DEBUG_LAYER=1`
using the separate [validation add-on](../../../tools/temporal_validation/README.md). It ran from
21:58:00–21:59:01 local time on 2026-10-02 and exited normally: **93 checks passed, zero failures,
3384 measured frames across 12 stages**. Source instance/mods/options/saves were not modified.
The launch used an offline test identity; authentication/Realms errors are expected and unrelated
to rendering. All five offline gates were rerun after the loader fix and passed on this final JAR.

- Off and strength 0 run at native resolution with no jitter or depth snapshots.
- Ordinary spatial strength 25 remains healthy with the proof disabled.
- Proof strengths 25/33/50 execute both projection redirects with equal world/hand counts and
  scene-sized rendered depth snapshots containing finite reversed-Z values in [0,1].
- Resize to logical 1921×1081 (3842×2162 Retina framebuffer) recreates healthy scene resources;
  strength 25 uses the expected rounded 2881×1621 scene.
- Inventory at GUI scale 2 and pause at scale 4 retain the world/hand proof and close its interval
  before GUI. Disabling the proof stops depth work; returning to native and the title menu bypass
  it entirely. Every stage passes the one-native-pixel output marker and reports zero resource,
  pipeline, unbound-binding and missing-attribute failures.
- All three named live hook diagnostics are present. Screenshot review confirms world orientation,
  the held torch, HUD, inventory icons and pause/title composition without blank/missing regions.

The first exploratory launch exposed independently extracted copies of the embedded dylib in
`MetalBridge` and `MetalNative`, registering duplicate Objective-C classes (BUG-032). Both now
share synchronized `NativeLibrary.load()`. The repeat packaged launch has no duplicate-class or
Metal validation diagnostics. One exploratory assertion also sampled shortly after an automatic
window resize reset the generation; the add-on now waits for 120 frames in the current proof
generation, as well as five seconds and 120 stage frames. This corrected the test's timing.

Evidence: [result](runtime/result.txt), [checks](runtime/checks.txt), [console](runtime/console.txt),
[contact sheet](runtime/contact-sheet.jpg). Full-resolution screenshots and console are preserved
under `build/reports/temporal-input-runtime/`; the disposable game is
`/private/tmp/metalmod-temporal-input-validation-final`.

The colour tests prove transfer/round-trip invariants, not gameplay temporal reconstruction quality.
Camera/object motion, per-content reactive-mask production, hand/world temporal treatment and
full history lifecycle are still required before any active temporal candidate. No performance,
ghosting, native frame-time or temporal release-acceptance claim is made.

## Repeatable verification and installed build

Added `python3 scripts/verify_mod.py`: canonical build, packaged embedded-native comparison and
absence of test add-on classes, all five gates with Metal validation, an additional dynamic/clustered
lighting shader inventory and optional copied-world gameplay. Logs and the artifact SHA-256 are
saved per run; a failed gate exits nonzero. The [quick testing instructions](../../../TESTING.md#quick-verification)
cover both automated and manual checks.

The fresh run `verification-20261002-230009-726806` passed all five gates plus packaged gameplay.
The lighting-enabled shader inventory was then run separately and passed 87/87 + 9/9 with no
diagnostics; it is now included by default in the verification runner. Gameplay was expanded to
13 stages, adding exact dimension assertions and the actual Super Resolution settings screen:
**113 checks passed, zero failures, 3617 frames**. Screenshot review confirms the settings screen
shows the expected 75% scene scale and native output dimensions.

The rebuilt production JAR SHA-256 is
`7721bc453f993c37b500babc8c973c59d10f3d6d761c31d42f5817ce880f1adc`.
It is now installed in the normal `MetalMod_Test_26.2/mods/metalmod-1.0.0.jar`; installed and built
hashes match. The previous installed JAR is backed up in that report directory as
`previous-installed-metalmod.jar.backup`. The normal config's checksum is unchanged: Metal on,
spatial On/strength 50, dynamic lights on, clustered lights off, UMA pool on. The developer jitter
proof is not enabled for normal play. No test add-on was installed in the normal instance, no
source saves were modified and no commits were created.

Evidence: [verification](runtime-expanded/result.txt), [artifact](runtime-expanded/artifact.json),
[game result](runtime-expanded/game-result.txt), [checks](runtime-expanded/game-checks.txt),
[installation record](runtime-expanded/installation.json), [settings screenshot](runtime-expanded/settings.jpg).
The completed temporal scaler remains an offline prototype; motion/reactive/history production,
full lifecycle coverage and Phase 7 quality/performance release acceptance remain pending.

## In-game Temporal Input Test switch

The Super Resolution screen now includes **Temporal Input Test: ON/OFF**. It changes live on the
next rendered frame, saves `enableTemporalJitterProof` (default Off) and takes precedence over
`-Dmetalmod.temporalJitterProof`. The status identifies active diagnostic jitter, waiting for a
reduced world, or Off, and names the actual MetalFX spatial/native output. The screen explains
that there is no temporal accumulation. Native/Off/menu/fallback bypass the proof while retaining
the requested preference.

`verification-20261002-230831-486468` passed all five gates, the lighting shader inventory and
**138 gameplay checks over 4195 frames across 15 stages**. The copied-world test invokes the real
button callbacks for On/Off and On at native resolution, with opposing JVM flags, then reloads
config to check persistence. It verifies actual rendered world/hand jitter/depth work when On,
no proof work when Off/native, and zero rendering health failures. Screenshots confirm labels,
status and spacing. No full temporal reconstruction is selected by this switch.

The packaged and installed JAR hash is
`a9f1e8b9ce8d02166de7742a3cd3613b38bef24758b00ae203079bafda8d90f5`.
The normal test instance is updated with a backup and unchanged config; the new switch initially
remains Off. No commits were made. Evidence: [verification](ui-switch/result.txt),
[game checks](ui-switch/game-checks.txt), [installation](ui-switch/installation.json),
[On screenshot](ui-switch/09b-sr-settings.jpg), [Off screenshot](ui-switch/09c-test-off.jpg),
[native bypass screenshot](ui-switch/09d-test-native.jpg).
