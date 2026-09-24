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
| 7 — MetalFX | **7A done** (verified offline, awaiting one in-game session); **7B implemented** (camera reprojection **plus per-object stamps for entities, particles and pushed blocks**, live encode, resets; verified offline); 7C not started |
| 8 — native material and lighting foundations | not started |
| 9 — hybrid ray tracing | not started |
| Optional — GLSL shaderpacks | deferred; not an RT prerequisite |

## Agreed next priority

**Evaluate Temporal in game.** 7B now runs end to end with both halves of the motion field: a native
camera-reprojection kernel over the level depth, and a per-object overlay that stamps entities,
particles and pushed blocks with their own previous positions (the engine interpolates all three every
frame, so the information was already there). `SceneMotion` publishes the current/previous
view-projection contract shared with Phase 8C, the temporal scaler is encoded on the device queue
behind the level's passes and ahead of the interface, and history resets on camera cuts, world changes
and resizes. What remains is a session: how tight an entity's bounding box is around its silhouette,
how the modes compare in quality and cost, and whether anything in ordinary play still trails. That
comparison procedure is [TESTING.md](TESTING.md) §6.D. Separate AA remains optional afterward. See
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

Phase 7 (MetalFX) is **7A done, 7B implemented, 7C not started**; see
[docs/phase7-plan.md](docs/phase7-plan.md) for the per-increment record, the integration contract,
the seven defects the work found, and the six in-game observations that close 7A. What that means in
a session: **Options → MetalMod… → MetalFX Upscaling** steps the render scale and cycles the
upscaler through **off / MetalFX spatial / MetalFX temporal**, shows the live sizes and which effect
actually ran, and raises a toast in world when a change lands. The world renders at that fraction
into its own target and MetalFX returns it to native, while the HUD, menus and tooltips keep drawing
at native resolution. Temporal adds a native camera-reprojection pass over the level's depth **and a
per-object overlay** that stamps entities, particles and pushed blocks with their own previous
positions, so geometry that moves on its own no longer reprojects as if it were static. When temporal
cannot run - an older device, no motion producer, a depth format the kernel cannot read - the frame
falls back to Spatial and F3 and the settings page say why. **No in-game run has happened yet** - the design's central claim
is verified offscreen against the engine's own `MainTarget` and `FrameGraphBuilder`, not on screen.
[TESTING.md](TESTING.md) §6.E is the five-minute pass that closes 7A; the one observation that decides
it is the HUD at 50%, which must be as sharp as at 100%.

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
| `./scripts/run_smoke.sh` / `native/build/metalmod_smoke` | native device/resource/pipeline/draw/surface/staging/texel/fence and the MetalFX spatial/temporal scalers, `ALL CHECKS PASSED` |
| `./tools/shader_inventory/run.sh` | `static 87/87`, `post 9/9`, no diagnostics from any pipeline |
| `./tools/render_check/run.sh` | 173 pixel assertions, including the 6A/6B/6C terrain, particle, entity, item and moving-block lighting paths, the runtime toggle, the measured zero-work disabled path, and six MetalFX spatial upscaling assertions, `RENDER CHECK PASSED` |
| `./tools/scaling_check/run.sh` | Phase 7 end to end offscreen: the scaled level target through the engine's own `MainTarget` and `FrameGraphBuilder`, the upscale, the resize, the release, the jitter sequence, and the temporal path - the scene contract, the motion field's values and conventions, and the reset lifecycle, `SCALING CHECK PASSED` |
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
