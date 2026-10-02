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
- **Native library** (`libmetalmod.dylib`) contains the Metal backend (`mmm_*`), UMA memory pool and
  world-only MetalFX spatial reference (`mmm_fx_*`). Retired MoltenVK interop remains deleted.

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
| 7 — MetalFX | spatial integration implemented and tested (2026-10-01); release quality/performance acceptance pending; 7C deferred; see [docs/phase7/implementation.md](docs/phase7/implementation.md) |
| 8 — native material and lighting foundations | not started |
| 9 — hybrid ray tracing | not started |
| Optional — GLSL shaderpacks | deferred; not an RT prerequisite |

## Camera-motion and actual FIFO timestamp checks (2026-10-02)

Autonomous disposable-world testing now includes six accepted 60-second smooth render-time
camera captures plus three short display-observer captures: 44,929 frames, no shader compiles or
SR failures/recoveries. At 5120×2880, native motion repeats are 95.5–97.9 FPS; one-per-strength
AA-enabled 25/33/50% captures are 106.3/114.2/128.8 FPS. Memory pressure affected part of the
session, so these are exploratory rather than release performance acceptance.

Effective surface modes were asserted. Actual drawable presentation timestamps verify FIFO at
~60 Hz/16.67 ms median with no zero/skipped timestamps in the native and SR25 samples. IMMEDIATE
updates faster but less evenly and skips some submissions. Latency/frame generation remains untested.
The observer only attaches callbacks in the separate test add-on; ordinary presentation is unchanged.

Dense-scene tail analysis locates most of the observed p95 increase in CPU command-buffer creation
calls (SR25 slowest 5%: 13.53 ms versus 3.97 ms otherwise), with frame/create correlations near 0.99.
The driver/queue cause remains unproven; BUG-031 is open. Cropped near/mid-range camera snapshots
show 50% detail softening; ~4 Hz sampling does not establish distant shimmer or full motion acceptance.

M4 Pro temporal support and legal descriptor creation are confirmed. Ready to begin temporal
prototype/input development; no temporal rendering is implemented or accepted. Per-object motion,
jitter, history/reset and colour/exposure pixel contracts remain implementation work. All five gates
were rerun and passed (194 pixel assertions). Evidence: [motion/timestamp results](docs/phase7/motion-check/results.md).

## Spatial throughput pilot and GPU telemetry (2026-10-01)

Added generation-safe completed AA + MetalFX + output-copy GPU timing to F3/F8. Missing,
native, recovery or failed samples remain -1; timestamps are asynchronous and are not total
frame GPU execution time. All five gates pass (87/87 + 9/9 shaders, 194 pixel assertions,
strict native/render Metal validation). The standalone capture-column and native generation/
completion checks pass. A separate Fabric benchmark add-on is excluded from the production JAR.

Twelve valid 60-second captures at 5120×2664/32 chunks in a disposable copied world yielded
uncapped two-run means: native 73.9 FPS, AA-enabled strength 25/33/50% 77.5/81.9/86.6 FPS
(+4.8/+10.8/+17.2%). Strength 25/33% worsened whole-route p95 versus native. Turning AA off
at strength 25% improved throughput only ~1.9% and reduced scaler-batch median ~0.45 ms;
keep AA enabled. Vsync was disabled for throughput and enabled in two separate runs, but F8's
CPU boundary intervals do not establish actual displayed pacing/latency. No pacing claim.

This is a two-repeat route pilot, not full Phase 7 acceptance. Holds/teleports do not prove motion
stability; spectator mode suppresses effective dynamic lights. Temporal remains excluded without
a complete independent-object motion/jitter/history contract. Default Off/remembered 25% remains.
Evidence and reproducible harness: [results](docs/phase7/spatial-benchmark/results.md),
`tools/metalfx_benchmark/`; full CSV/PNG artifacts in `build/reports/spatial-benchmark/`.
The timing build is installed in the normal test instance; restart the client to load it.
Backup of the usable spatial checkpoint: `build/backups/metalmod-before-gpu-timing.jar`.

## Distant terrain mip selection corrected (2026-10-01)

BUG-030 fixes a backend sampler contract error that survived the first AA mitigation. The real
26.2 chunk renderer uses an absent maximum LOD to allow the full mip chain; MetalMod incorrectly
mapped it to no mipmapping. Unbounded samplers now use linear mip filtering, while explicit
level-zero caps remain clamped. The previous installed AA build was confirmed present in the
test instance, and its latest log recorded `input AA On`; this report was not a missed AA toggle.

Three new pixel assertions fail against the old backend and pass after the correction, including
real terrain textureGrad and RGSS textureLod under minification. All five gates pass, with 194
pixel assertions and Metal API validation on smoke/render checks. The rebuilt JAR is installed
in `MetalMod_Test_26.2`; restart the client to load it. Previous installed JAR backup:
`build/backups/metalmod-before-mipmap-fix.jar`.
This fixes texture minification with SR both on and off. The user confirmed the corrected spatial
pipeline is usable. Repeatable quality/performance comparisons and motion acceptance remain
pending. Durable regression evidence: `docs/phase7/mipmap-fix/`.

## Spatial MetalFX input quality (2026-10-01)

Spatial reconstruction now receives an edge-aware SDR anti-aliasing prepass, enabled by default
only when reduced-resolution MetalFX is active. It preserves centre alpha, skips low-contrast
regions, clamps border samples and has a bounded subpixel contribution for high-frequency input.
The per-generation intermediate and pipeline are reused; source scene/depth, native GUI, native
bypass and plain recovery are unchanged. `-Dmetalmod.fxAntialias=false` disables the prepass for
matched diagnostic A/B captures; generation startup logs its state. This is spatial filtering,
not temporal reconstruction, and can soften intentional texture detail.

All five offline gates pass, including strict Metal validation for smoke and pixel render tests.
The new GPU assertions exercise flat colour/alpha preservation, unmodified source input,
checker alias suppression, staircase coverage, broad edge interiors, and filtered/unfiltered
real MetalFX reconstruction. The existing 190 pixel assertions pass. Representative foliage
appearance, camera-motion stability and added GPU cost have not yet been accepted in game;
BUG-029 therefore remains mitigated rather than closed. No temporal-quality or performance gain
is claimed from these synthetic checks. An isolated alternating A/B GPU measurement on M4 Pro
(3840×2160 scene → 5120×2880 output, synthetic high-frequency pattern, 20 measured samples per
mode after warmup) measured FX+copy medians 1.728 ms without AA and 2.332 ms with AA: +0.604 ms.
This is not whole-game frame timing. Local logs and the benchmark source are in
`build/reports/metalfx-aa/`; the rebuilt JAR includes the default-on prepass.

## Phase 5 close-out and next step

Phase 7 now has an opt-in **world-only MetalFX spatial reference**. Options → MetalMod… →
Super Resolution… exposes On/Off and strength 0/25/33/50% (scene scale 100/75/67/50%). Off and
On+0% bypass reconstruction. World passes, hands, outlines and post effects use reduced colour/depth
attachments; reconstruction completes before native GUI and native Globals restoration. Window,
extraction, picking, frustum and GUI coordinates stay native. Resources are reused, retirement is
bounded by a queue wait at configuration transitions, and failures latch to native with a validated
same-frame plain recovery for CPU-side encode failure. Lighting ABI v1/cadence is unchanged.

All five offline gates pass on Apple M4 Pro, including real spatial operations and 100 mixed
transitions; packaged-JAR runtime hooks and reduced-world/native-HUD target routing were exercised
in an isolated copy of the test world. That run found BUG-026 (SkyRenderer retained a retired scene
object); the sky target now follows both frame boundaries. Exact commands, evidence and remaining
acceptance work: [docs/phase7/implementation.md](docs/phase7/implementation.md).

**Phase 7 is not declared complete.** Temporal lacks the required object-motion input contract and
is absent from the active path; spatial is an engineering reference, not a measured winner.
Representative visual review, native Off/On+100% regression pairs, F7/F8 quality/performance pairs,
full lifecycle/content matrix and the ten-minute steady soak remain acceptance gates. **7C frame
generation/display-link pacing is explicitly deferred**; existing FIFO/immediate presentation remains
the sole presentation owner. No FPS improvement or latency claim is made.

Phase 5 is complete for the tested vanilla 26.2/M4 Pro scope. All fourteen visual checks were
confirmed in game; the routed Overworld comparison meets the comparable-performance criterion.
The final review reran all five offline gates successfully.

Phase 6 (dynamic lighting) is **complete**; see [docs/phase6-plan.md](docs/phase6-plan.md) for the
final verdict per gate, the carried-forward evidence items and the limits, and
[docs/lighting-abi.md](docs/lighting-abi.md) for the published light-record contract that Phase 8
builds on. `TESTING.md` §5.6 is the final-test checklist.

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

Run all five before trusting a change:

| Command | Reports |
|---|---|
| `./scripts/build_mod.sh` | compiles the mod and every non-JUnit test |
| `./scripts/run_smoke.sh` / `native/build/metalmod_smoke` | native device/resource/pipeline/draw/surface/staging/texel/fence, `ALL CHECKS PASSED` |
| `./tools/shader_inventory/run.sh` | `static 87/87`, `post 9/9`, no diagnostics from any pipeline |
| `./tools/render_check/run.sh` | 167 pixel assertions, including the 6A/6B/6C terrain, particle, entity, item and moving-block lighting paths, the runtime toggle, and the measured zero-work disabled path, `RENDER CHECK PASSED` |
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
- **Real GPU timing.** F3/F8 now expose the latest completed AA + MetalFX + output-copy
  submission duration (unavailable for native/recovery). This is an asynchronous scaler-batch
  measurement, not total GPU frame time. `GPU wait` remains a proxy. A per-frame GPU execution time needs
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
