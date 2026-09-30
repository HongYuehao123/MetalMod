# MetalMod — current state

Short, factual status. See `ROADMAP.md` for where this is going, `bug.md` for open defects, and
`TESTING.md` for how to test.

## What this is

A native **Metal renderer backend** for Minecraft 26.2, which abstracts its graphics API behind
`GpuBackend` / `GpuDeviceBackend`. MetalMod implements that interface; it does not patch MoltenVK.
Groundwork and the backend architecture are in `ROADMAP.md` §2; the interface contract is in
`docs/backend-api.md`.

## What works today

- **Build:** compiles against the **real Minecraft client jar** (no API stubs).
  `./scripts/build_mod.sh` → `build/libs/metalmod-1.0.0.jar`.
- **Metal backend.** The engine selects `MetalBackend` through `PreferredGraphicsApiMixin`, creates
  an `MTLDevice`, attaches a `CAMetalLayer` to the window, and MetalMod presents real frames.
- **Real resources.** `MTLTexture` / view / buffer / sampler / fence are real Metal objects with
  mapped formats; uploads, readbacks and clears go through them.
- **Real pipelines and draws.** `RenderPipeline` → `MTLRenderPipelineState` + `MTLDepthStencilState`,
  compiled from the engine's shaders (GLSL → SPIR-V → MSL via SPIRV-Cross). The engine's draw calls,
  including `drawMultipleIndexed`, scissor, uniforms/textures/samplers and the presentation blit, are
  encoded.
- **All vanilla shaders compile**: `static 87/87` and `post 9/9`, no diagnostics.
- **A normal world renders.** The loading screen, main menu, a loaded world (terrain, entities,
  particles, sky/weather, clouds, water), the HUD, items and text all draw, with every telemetry
  counter at zero. BUG-002 (selection outline) and BUG-003 (flat black terrain/entities) are
  confirmed fixed in game.
- **F3 section** renders MetalMod status: backend in use, resolution, frame time and GPU-wait time
  (the CPU/GPU split), draw count, `unbound/missingAttr/failed` health counters, optional UMA
  telemetry, and the mixin hook summary.
- **Config GUI** opens from Mod Menu; it exposes the Metal backend toggle and the UMA memory option.
- **Native library** (`libmetalmod.dylib`) now contains only the Metal backend (`mmm_*`) and the UMA
  memory pool; the retired MoltenVK-interop/MetalFX code is gone (see below).

## The Metal backend is opt-in

`preferMetalBackend = false` by default (also settable with `-Dmetalmod.metalBackend=true`). The
backend is chosen once at startup by `PreferredGraphicsApiMixin`, which prepends `MetalBackend` and
keeps the vanilla backends as a fallback, so a `BackendCreationException` degrades to Vulkan/OpenGL.

## Phase status

| Phase | State |
|---|---|
| 0 — build foundation | done |
| 1 — first light (device, surface, clear) | done; `native/build/metalmod_smoke` proves it |
| 2 — resource layer | done |
| 3 — pipelines and draw calls | done |
| 4 — shaders (87/87, post 9/9) | done |
| 5 — vanilla render parity | **done**; final check in `docs/phase6-plan.md` |
| 6 — dynamic lighting | **done (2026-09-25)**; occlusion and linear composition handed to 8B, consumer/ownership to 8, two evidence items carried forward |
| 7 — MetalFX | **7A/7B implemented, offline gates pass; final acceptance pending.** Historical live performance measured; current cleanup still needs 2K visual/resize/reset validation. [Completion review](docs/phase7-completion-review.md). 7C not started |
| 8 — native material and lighting foundations | not started |
| 9 — hybrid ray tracing | not started |
| Optional — GLSL shaderpacks | deferred; not an RT prerequisite |

## Temporal cost is measured and GPU work attributed (2026-09-26)

**2026-09-29 native image regression (BUG-040):** The player reports the unmodified game and
earlier MetalMod native builds looked clean, while the newer native 2K image is aliased even with
FXAA. Resolution sync changed the 2560x1440 fullscreen window from a 5120x2880 Retina main target
and drawable to 2560x1440; this removed four source samples per output pixel. At the same surface
coordinates, a cloned Metal run with Retina sync disabled confirmed a 5120x2880 drawable and held
59.6–60.0 fps in settled ten-second samples with FXAA off. Targeted window capture was denied by
macOS, so visual parity remains unverified. At the user's request, 2K remains the default drawable
and Retina 5K remains a restart-required quality option. Post-FXAA stays off by default because
it did not restore the missing samples. The 2K native image quality gap remains open; the
resolution difference is a strong candidate but has not been isolated as the only cause.
The 5K Temporal budget in dense scenes is still a separate constraint.
After restoring the 2K default, the real test instance's other current choices were preserved:
85% world scale, Temporal requested, and post-FXAA off. The verified JAR is installed and all
seven offline gates pass.

**2026-09-29 image-quality follow-up:** A player screenshot at 75% world scale showed
1920x1080 -> 2560x1440 Spatial, even though Temporal was requested. The guard had latched after
missed refreshes, and the game's next Spatial samples also missed many refreshes. The guard now
checks Spatial for ten seconds: if Spatial also misses the same threshold, it restores Temporal
and leaves the guard suppressed for that world/configuration; if Spatial holds cadence, it keeps
the performance fallback. FXAA's edge threshold, search reach and blend were made more conservative
to retain distant foliage detail. Full-size or 90%-size Temporal output at 75% input lost cadence
in the dense cave (about 55–57 fps in the later settled samples), so the two-stage default remains.
All seven offline gates pass. In the live cloned cave, the guard switched at 57.9 fps, after which
Spatial held 59.7–60.0 fps with only one further missed refresh in 30 seconds. The requested 75% world scale
still limits source detail, irrespective of post-AA. The verified JAR was installed in the real
MetalMod test instance. Its saved render scale was restored from 0.75 to 1.0 for that launch:
the player's prior surface log showed native 2560x1440 near 60 fps, and this removes the 1080p
world source visible in the screenshot. At the screenshot's coordinates, the cloned native-size
surface view held 59.5–60.0 fps after loading, with one 58.2 fps movement interval. The window
then became iconified and its later 10 fps background samples were discarded. See BUG-039.

**2026-09-29 world AA:** A full-resolution FXAA pass now runs after the completed world image
(native, Spatial, or Temporal plus Spatial) and before the HUD. It was initially on by default,
but is now off by default after BUG-040. It has a live toggle on the Upscaling screen plus
`-Dmetalmod.postAA=true` for comparison. Native smoke verifies
edge blending and flat-region preservation; the scaling gate verifies the native-size path and off
switch. At the 2560x1440 dense cave route with VSync on, AA on averaged 58.6 fps during the turn
and 59.7 fps in the final view; the AA-off control averaged 58.9 and 59.8 fps. A 100% render-scale
live run confirmed the FXAA pass at 2560x1440. All seven offline gates pass. Player judgement of
fine-detail softness and shimmer remains open; window capture did not produce an image on this host.

**2026-09-29 fix:** Temporal now reconstructs at the world render size, with a Spatial pass
finishing the 2560x1440 output. The performance run used 67% world scale (1715x965); the user
later changed the test instance to 100%/Spatial, which the AA installation preserved.
In the cloned cave with VSync on, the dense-view result improved from 51.9 fps at the former 85%
direct Temporal configuration to 59.8 fps, with a 18.14 ms p95. At the same 67% source resolution,
direct Temporal was 59.4 fps with a 19.76 ms p95. All seven offline gates and the final live
route pass. The pacing guard remains for scenes that still exceed the display budget. The change
trades source detail for frame time while keeping the output and HUD at 2K. Full measurements:
[docs/temporal-performance.md](docs/temporal-performance.md).

**Fast movement / 60 Hz follow-up:** At 2560x1440 output in a cloned dense cave, 85% Temporal
averaged 56.1 fps while turning and 51.3 fps facing the densest view; matched 85% Spatial held
59.8 and 59.7 fps. Draw counts were comparable. A 70% Temporal control still fell to 55.1 fps in
the dense final view. The corrected drawable presentation callback is live and reports 16.7/33.3 ms
intervals. A new guard switches Temporal to Spatial at the same resolution/scale after sustained
display misses; the live follow-up switched during the turn and then held 59.7–59.9 fps for three
ten-second samples. Seven offline gates and the live run pass. This mitigates the stutter while
BUG-035 remains open for Temporal cost reduction. See BUG-037.

**2026-09-29 dense-cave repeat:** With VSync confirmed on in both F8 summaries, Temporal at
2176x1224 -> 2560x1440 averaged **57.1 fps** while turning and **51.9 fps** in the final view;
matched Spatial held **59.9** and **60.0 fps** at nearly equal draw counts. Separate Metal traces
put Temporal preprocessing/middle/postprocessing at median **0.706/1.343/0.819 ms**, versus
Spatial scale/sharpen at **0.280/0.085 ms**; motion reprojection was **0.050 ms**. This isolates
about **2.5 ms of additional MetalFX GPU work** at the 2K output. Instruments disturbed later
frame rates, so the unprofiled F8 captures are used for pacing. See the new 2K section of
[docs/temporal-performance.md](docs/temporal-performance.md). This led to the two-stage 2K path
and live frame-time comparison recorded above; visual quality still needs a player evaluation.

A second clean pair with VSync **off** found, in the same final cave view, Spatial at
**15.03 ms / 66.5 fps** and Temporal at **17.27 ms / 57.9 fps** with essentially identical
19,880-draw workloads. Spatial had 1.64 ms of 60 Hz headroom; Temporal added 2.24 ms, crossing
the 16.67 ms budget even before VSync. With VSync on it reached 51.9 fps as display refreshes
were missed. The no-VSync Spatial value also matches the user's reported 60–70 fps. The three
MetalFX Temporal stages explain the incremental cost. See the full
four-run table in [docs/temporal-performance.md](docs/temporal-performance.md).

**Unattended turn probe, same day:** The game was started from HMCL's recorded command with
`--quickPlaySingleplayer` and an opt-in `-Dmetalmod.turnProbe=true` capture. At the saved viewpoint,
50% and 85% Temporal turned at 120 degrees/s while holding about 59–60 fps. A cloned world at the
recorded cave route's densest point also held roughly 59 fps while turning through 18,000–20,700
draws. The 50% surface Metal trace put the three Temporal stages around 2 ms in total. Moving the
same probe to 85% in the dense cave reproduced the frame-budget failure.

**Retina resolution fix implemented and its 2K output verified in game.** The test instance requests a
2560x1440 fullscreen mode, while the 5K display was giving Minecraft a 5120-wide framebuffer and
MetalFX a 5K output. The Metal backend now latches a **Retina Resolution** choice at window
creation and uses the GLFW window dimensions for Minecraft's cached framebuffer size, its resize
callback and its surface configuration. The CAMetalLayer drawable therefore follows that effective
size. The setting stays on by default at the user's request and needs a restart;
`-Dmetalmod.syncWindowResolution=false` selects Retina-sized targets for comparison. All seven
offline gates pass; the cloned game run
reported a 2560-wide MetalFX output. Fullscreen/windowed resize and HUD alignment still need a
direct visual pass. See BUG-036.

**MetalFX temporal costs about 5.6 ms a frame more than spatial** at 50% scale on a 5120-wide output,
and removing this project's motion pass saves about **0.3 ms**. Measured two ways that agree: offscreen through the scaling check (spatial 1.64 ms
against temporal 7.26 ms, 40 iterations with the queue drained, uniform rather than spiky) and in game
at one hilltop with F6 (12.7 ms / 91 fps against 17.3 ms / 58 fps, matching draw counts).

The earlier 2.7 ms scaler GPU span captured only MetalFX's middle stage. A live 5K Metal System
Trace showed median **1.69 ms preprocessing + 2.81 ms main + 2.56 ms postprocessing**; the three
stages total about 7.06 ms, consistent with the full-path cost. Pre/post have MetalFX-owned
command-buffer labels outside the caller's buffer. The spatial trace showed 0.401 ms scaling and
0.099 ms sharpening. In separate, settled 5K game launches, temporal logged 19.4-19.8 ms and spatial
12.9-13.7 ms at similar draw counts, excluding the later AFK cap. The previous jitter-shortcut theory
was disproved by a fixed-phase A/B (7.087 against 7.059 ms). F3's temporal GPU span remains partial.

An independent resolution sweep found that both input and output pixels contribute. At essentially
the same 2560x1332 input, the three temporal stages fell from **7.06 ms at 5120x2664 output** to
**4.64 ms at 3840x1998** and **3.77 ms at 3200x1666**. At fixed 5K output, raising input from
2560x1332 to 4096x2131 raised them to **9.46 ms**. This supports the next implementation experiment:
temporal to a 3200- or 3840-wide intermediate, then spatial to 5K. The traces predict a 2-3 ms saving
after the roughly 0.5 ms spatial pass, but the full two-stage chain and image quality are not measured.

Seven hypotheses were tested. Five were falsified (the depth read, one command buffer against two, the
motion texture's storage mode, the engine targets' storage mode, advancing jitter) and **two were real defects now
fixed**: the scaler's content region was never set, and it was being built asynchronously, so it ran
its interim upscaler. There is no exposed knob on the temporal filter, so the mode is currently a **quality mode with a
measurable price**; spatial stays the default and the settings page and F6 label the trade. Full
record, resolution sweep, limits and reproduce steps: [docs/temporal-performance.md](docs/temporal-performance.md);
defect entry: BUG-035.

## Agreed next priority

**User priority, 2026-09-29: fix Spatial distant-forest quality first (BUG-044).** The user reports
unbearable far-forest aliasing that FXAA does not solve. Spatial is quality-blocked, rather than merely
awaiting an optional preference check. Temporal is reported near native with blurred edges remaining;
that is secondary, and its cause is not yet attributed to MetalFX itself. Keep 2K output. First isolate
the world input, Spatial output and optional AA on a matched forest view; investigate cutout sampling,
mip/LOD/coverage and source resolution based on that evidence. No new 5K Temporal test is needed.
The report does not identify the exact build/scale, so current-cleanup validation remains pending.

**2026-09-29 authorized cleanup implemented, verified offline:** `UpscalingPlan` snapshots output/
world sizes and requested effect at extraction, before capability selection and jitter. Off now
renders the world directly at output resolution while remembering the user's scale. Jitter application
and motion removal share homogeneous clip-space helpers; the 64 depth/phase cases and bob/portal
composition regression pass. External world/intermediate targets are explicitly owned and retired
until a subsequent frame boundary plus GPU drain, then destroyed; teardown frees the final generation.
Pacing observations are separated from capability checks. Temporal diagnostic launch flags and the
unconfirmed BUG-029 reload workaround are isolated into dedicated helpers. History resets survive
failed encoding, and explicit configuration changes clear the Temporal failure latch.
All seven gates pass; scaling now has 124 passing assertions, including repeated target retirement,
Off mode and teardown. The 2K default, optional post-FXAA and current Temporal-at-world-size/Spatial
finish remain. No play-instance JAR was installed and no live visual quality claim is made.
Next: in-game resize/toggle and image-quality evaluation at 2K; BUG-040 remains open.

**Cleanup proposal, analysis only:** The user requested reconstruction of the existing code before
implementation. [Proposal](docs/upscaling-cleanup-proposal.md) records current frame ordering,
fix dispositions, a 2K architecture and staged tests. Source tracing also found that Off below 100%
still executes Spatial; real client bytecode separates imported external resources from pooled
internal resources, so the indefinite target-retention rationale requires correction. Rendering
changes remain deferred; no runtime installation or new live run occurred in this analysis.

**2026-09-29 read-only rendering audit:** Native at 100% still follows the default logical-size
resolution override, so it is not a matched-resolution vanilla comparison. A new CPU projection
test also confirms BUG-041: Temporal jitter is a view-space translation rather than a fixed
clip-space displacement (64/64 cases fail; independent control passes). All seven existing offline
gates were rerun and pass with host GPU access. Render code and installed runtime were left unchanged.
See [quality audit](docs/upscaling-quality-audit.md) for the controlled visual comparison plan.

**Earlier priority, superseded by Spatial quality above: judge Temporal's picture.** 7B runs end to end with both halves of the motion field - a native
camera-reprojection kernel over the level depth, and a per-object overlay that stamps entities,
particles and pushed blocks with their own previous positions (the engine interpolates all three every
frame, so the information was already there) - and its cost is now known and explained. What remains
is the human half: how tight an entity's bounding box is around its silhouette, whether the temporal
picture is worth 5.6 ms, and whether anything in ordinary play still trails. F6 cycles off / spatial /
temporal at one spot, so all three can be compared in one session; the procedure is
[TESTING.md](TESTING.md) §6.D. Separate AA remains optional afterward. See
[the corrected AA plan](docs/antialiasing-plan.md) and [the Phase 7 record](docs/phase7-plan.md).
This is a planning decision, not a verification result.

## Phase 5 close-out and next step

Phase 5 is complete for the tested vanilla 26.2/M4 Pro scope. All fourteen visual checks were
confirmed in game; the routed Overworld comparison meets the comparable-performance criterion.
The final review reran all five offline gates successfully.

Phase 6 (dynamic lighting) is **complete**; see [docs/phase6-plan.md](docs/phase6-plan.md) for the
final verdict per gate, the carried-forward evidence items and the limits, and
[docs/lighting-abi.md](docs/lighting-abi.md) for the published light-record contract that Phase 8
builds on. `TESTING.md` §5.6 is the final-test checklist.

Phase 7 (MetalFX) is **7A/7B implemented and measured, final acceptance pending; 7C not started**; see
[docs/phase7-plan.md](docs/phase7-plan.md) for the per-increment record, the integration contract,
the defects the work found, and the in-game observations that close 7A. What that means in
a session: **Options → MetalMod… → MetalFX Upscaling** steps the render scale and cycles the
upscaler through **off / MetalFX spatial / MetalFX temporal**, shows the live sizes and which effect
actually ran, applies its edits when the page is left rather than per click, and raises a toast in
world when a change lands. The world renders at that fraction
into its own target and MetalFX returns it to native, while the HUD, menus and tooltips keep drawing
at native resolution. Temporal adds a native camera-reprojection pass over the level's depth **and a
per-object overlay** that stamps entities, particles and pushed blocks with their own previous
positions, so geometry that moves on its own no longer reprojects as if it were static. When temporal
cannot run - an older device, no motion producer, a depth format the kernel cannot read - the frame
falls back to Spatial and F3 and the settings page say why. **F6** cycles off / spatial / temporal in
place at one spot; that is how the cost above was established, and how the picture should be judged
next. [TESTING.md](TESTING.md) §6 is the procedure; §6.D is the temporal pass.

What that means in a session, concretely: held and dropped items, entities, particles and moving
blocks light the world; a source buried in or sealed by opaque blocks contributes nothing; a placed
torch is never lit twice because vanilla's baked light stays the authority for it; spectator mode
turns the feature off by itself while the player's own setting is left alone; and the switches are on
**Options → MetalMod… → Lighting**, taking effect at the next frame boundary. Phase 6 does not shadow
anything - light still passes through walls - and that is Phase 8B's, by design rather than by
omission.

| Switch | Effect |
|---|---|
| `-Dmetalmod.pointLightProof=true` | one synthetic camera-centred amber light, terrain only |
| `-Dmetalmod.dynamicLights=true` | the real moving-source set: held and dropped block items |
| `-Dmetalmod.clusteredLights=true` | with `dynamicLights`: evaluate through the 16-block cluster grid |

All three are also **in-game now**: **Options -> MetalMod... -> Lighting** (or Mod Menu -> MetalMod),
persisted to `config/metalmod.properties`, applied at the next frame boundary without a restart. An
in-game choice beats a `-D` launch flag, which in turn beats the saved file, so a flag seeds a session
without ever freezing a setting (`MetalMod_Test_26.2` launches with `-Dmetalmod.dynamicLights=true`).

The lit variants cover the three terrain pipelines, the two particle pipelines, the non-emissive
entity pipelines and the two item pipelines (held and dropped items). Each is a separate shader pair,
which is why terrain could be lit while the dust, the mobs and the item in your hand stayed dark.
Emissive entity passes are excluded by policy, and the inventory is excluded by the headroom rule
rather than by pipeline - see `docs/phase6-plan.md`. Sources that are buried in, or sealed by, opaque
blocks are dropped; everything else about an unshadowed evaluator still applies. Glow squids are not
a source at all - vanilla entities emit no light, and their glow is an emissive texture. The startup log names the switches each session and logs every
variant it applies, so a log alone says which path ran.

The sources were lost before they were committed and were reconstructed from the compiled classes and
the interrupted `git add`'s dangling blobs; the recovery found and fixed a wrong test offset and an
upload that only refreshed on the first light-using draw. That reconstruction is now covered by
in-game running: Mixin application, source lifecycle and in-world appearance have all been exercised,
and defects it hid - a moving block lit in the wrong frame, a cluster cell that packed three entries
into a slot claiming four, a glow squid treated as a source - were found in game and fixed. The one
thing still unmeasured is the clustered path's cost, which is why it is not the default.

## Retired architecture (deleted in Phase 5)

The original design kept Minecraft on MoltenVK and reached in for MetalFX; it could not own
presentation and could not express MetalFX or ray tracing, so it was replaced by the backend. The
leftovers have now been removed rather than left inert:

- Deleted Java: `net.metalmod.render.VulkanFrameManager`, `net.metalmod.render.JitterHelper`, and the
  per-frame calls they had in `GameRendererMixin` / `WindowMixin` (those mixins now only record hook
  diagnostics).
- Deleted native: `metalmod_bridge.mm`, `metalmod_pacer.mm`, `metalmod_compositor.mm`,
  `metalmod_spatial.mm`, `metalmod_temporal.mm`, `metalmod_interpolator.mm`,
  `metalmod_internal.h`, the bundled Vulkan headers and the orphaned compositor shader.
- Trimmed: `MetalBridge` binds only the UMA/memory surface; the MetalFX scaling/preset/frame-gen
  config fields and their config-screen buttons are gone, as is the F3 "MetalFX" line.

The dylib no longer exports `metalmod_init`, `metalmod_configure`, `metalmod_process_frame`,
`metalmod_register_vulkan_device`, `metalmod_has_vulkan_interop`, `metalmod_get_telemetry`, the
MetalFX capability queries, or the window-title status functions.

## Verification gates

Run all of these before trusting a change:

| Command | Reports |
|---|---|
| `./scripts/build_mod.sh` | compiles the mod and every non-JUnit test |
| `./scripts/run_smoke.sh` / `native/build/metalmod_smoke` | native device/resource/pipeline/draw/surface/staging/texel/fence, the MetalFX spatial/temporal scalers, and the Phase 7B motion kernel and screen-space overlay, `ALL CHECKS PASSED` |
| `./tools/shader_inventory/run.sh` | `static 87/87`, `post 9/9`, no diagnostics from any pipeline |
| `./tools/render_check/run.sh` | 173 pixel assertions, including the 6A/6B/6C terrain, particle, entity, item and moving-block lighting paths, the runtime toggle, the measured zero-work disabled path, and six MetalFX spatial upscaling assertions, `RENDER CHECK PASSED` |
| `./tools/scaling_check/run.sh` | Phase 7 end to end offscreen: the scaled level target through the engine's own `MainTarget` and `FrameGraphBuilder`, the upscale, the resize, the release, the jitter sequence, and the temporal path - the scene contract, the motion field's values and conventions, the per-object stamps and their depth test, the reset lifecycle, and each path's cost distribution against spatial at the same sizes, `SCALING CHECK PASSED` |
| `./tools/mixin_check/run.sh` | every mixin target, injected method, `@Shadow` member and `@At` descriptor against the client jar, `MIXIN CHECK PASSED` |
| `net.metalmod.StandaloneTestRunner` | format tables, multi-draw, sub-buffer offsets, UMA ownership |

A green check is only evidence if it can fail: the scissor assertion is a case in point — it passed
for a long time against the very flip it should have caught.

## Performance

The latest routed comparison uses 5120×2664, render distance 32, vsync off, on Apple M4 Pro:

| scene | Metal shared | Vulkan |
|---|---:|---:|
| above ground | 8.91 ms | 8.65 ms |
| underground | 15.06 ms | 21.09 ms |
| overall mean | 12.331 ms | 14.449 ms |
| p95 | 19.230 ms | 25.036 ms |

This meets the Phase 5 criterion for this scene/hardware, not a universal performance claim.
`TESTING.md` records the route and storage experiment; `docs/phase6-plan.md` records the final
review. The Nether remains unpaired. F3 drawable wait is a CPU wait/pacing measurement, not GPU
execution time or a precise CPU/GPU split.

Worth not re-investigating: the terrain path is structurally identical on both backends.
`ChunkSectionsToRender.renderGroup` binds the pipeline once per layer and calls `drawMultipleIndexed`,
and `VulkanRenderPass.drawMultipleIndexed` loops per section exactly as this backend does.
`multiDrawIndexed` — the one call that batches on Vulkan via `vkCmdDrawMultiIndexedEXT` — has **no
engine caller at all**. So there is no batching deficit to close by implementing indirect draws.

Earlier numbers taken by resizing the window with a menu open (world not ticking) are superseded -
they measured resolution scaling only, and the CPU floor they implied was optimistic.

### Completed optimizations and remaining opportunities

- **Batch utility submissions.** *Done.* `mmm_write_buffer_bytes`, `mmm_copy_buffer_to_buffer` and
  `mmm_copy_texture_to_texture` share one pending command buffer with a single blit encoder, and
  uploads go into a per-frame staging ring. It cut the underground upload path's cost by 95%.
- **Storage-mode experiment.** *Implemented, off by default after a measured regression.* With
  `-Dmetalmod.privateTextures=all`, `MetalTexture` creates private storage for
  anything with `USAGE_RENDER_ATTACHMENT`; depth buffers and colour targets are what that claims, and
  the startup log names them. The rule keys on the render-attachment bit rather than the copy flags,
  because `RenderTarget` declares the constant 15 (`COPY_DST|COPY_SRC|TEXTURE_BINDING|
  RENDER_ATTACHMENT`) on both its depth and colour texture - classifying on the copy flags claimed
  nothing at all. Readback from a private texture goes through a shared staging texture.
  `mmm_buffer_create` still creates every buffer `MTLStorageModeShared` - the engine's mapping and
  upload paths touch buffer bytes directly, so a private split there needs the same staging treatment
  the texture path now has.
- **Presentation copy.** The `CAMetalLayer` keeps its default BGRA8 format while the main target is
  RGBA8, so the present is a full-screen fragment shader; matching the formats would allow a
  copy-engine blit.
- **Render-pass store actions.** Every attachment is `MTLStoreActionStore`, including depth buffers
  that the next pass clears. Relaxing that to `DontCare` needs lookahead - the encoder is already
  ended by the time the next pass begins - so it needs either engine cooperation or an encoder the
  backend holds open across the frame.
- **Publish table-shaped data as a texture.** The 6C cluster table first shipped as an indexed
  `ivec4` array inside a uniform block: its header reached the shader while every indexed element read
  as zero, on a device where an identical flat array worked. It now ships as an RGBA32F texture read
  with `texelFetch`. Treat an indexed array inside a uniform block as unproven on this backend.
- **Real GPU timing.** `GPU wait` is a proxy. A per-frame GPU execution time needs
  `MTLCounterSampleBuffer` timestamps; summing command-buffer `GPUStartTime`/`GPUEndTime` spans does
  not work (command buffers on one queue overlap execution). This is now the main measurement gap:
  the frame is GPU-bound in its heaviest moments and the capture can only infer that from the
  drawable wait.

## Environment specifics that matter

- Minecraft **26.2** with **named mappings** — the client jar contains **zero** `class_XXXX` entries,
  so mixins must target named classes. Mixin rejects an *entire* mixin if any one entry in `targets`
  is missing.
- Fabric Loader 0.19.x, Java 26, Apple M4 Pro, macOS 27.
- Graphics: **Vulkan 1.2.334 via MoltenVK 1.4.2** for the fallback path.
- `compatibilityLevel` in `metalmod.mixins.json` must match the emitted bytecode, so
  `build_mod.sh` pins `javac --release 22`.
- **Annotation retention is load-bearing:** `@Mixin` is `CLASS`; `@Shadow`, `@Inject`,
  `@ModifyVariable`, `@At`, `@Accessor` are `RUNTIME`. Declaring the latter as `CLASS` compiles fine
  and then does nothing.
- **The engine's scissor rectangle is bottom-up** (GL convention); Metal's is top-left. Convert on
  the way in. This was BUG-001.

## Instance under test

```
$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2
```

Mods present: Fabric API, Mod Menu, Placeholder API, and the built MetalMod jar.
**Sodium is deliberately not installed** — compatibility with it is not pursued (it sits on
Blaze3D's abstraction, and porting it does not simplify the shaderpack work). Iris lives in the separate
`26.2-Fabric` instance; integration is optional and is not a core dependency.

Override the build target with `METALMOD_MC_INSTANCE`.

## Decisions already made (and why)

| Decision | Reason |
|---|---|
| Compile against the real client jar, not stubs | Stubs caused most integration failures; `javac` now verifies the API |
| Be a `GpuBackend`, not a MoltenVK patch | Only a backend can own presentation, MetalFX and ray tracing |
| Remove main-render-target scaling | It broke the GUI and froze input |
| Remove the LWJGL allocator interception | LWJGL 3.4 needs native function pointers for its fast path; mixing allocator ownership risks corruption. Also a measured pessimisation. |
| Delete the MoltenVK-interop / MetalFX leftovers | Inert: a per-frame hook and native scalers that could never present, plus config that controlled nothing |
| Do not pursue Sodium compatibility | It follows Blaze3D and does not ease optional shaderpack work |
