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
- **F3 section** renders MetalMod status: backend in use, resolution, frame/GPU time and draw count,
  `unbound/missingAttr/failed` health counters, optional UMA telemetry, and the mixin hook summary.
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
| 5 — vanilla render parity | **verification close-out** (see below) |
| 6+ — dynamic lighting, shaderpacks, MetalFX, ray tracing | not started |

## What is left in Phase 5

1. **Confirm the last GUI fixes in game.** Select World: the first entry should have its background
   panel and an unsquashed name line (BUG-001). Survival inventory: item icons present and the
   player preview the right way up (BUG-025). Both are scissor / Y-flip rules in
   `MetalRenderPassBackend` and `MetalCommandEncoderBackend`, and both have offline assertions.
2. **Measure frame-rate parity against Vulkan/MoltenVK in normal play.** The only numbers so far were
   taken with a menu open and the world not ticking (below), so they are a lower bound on the GPU
   share, not a parity result.

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
| `./tools/render_check/run.sh` | pixel assertions over the mechanisms the Phase 5 bugs implicated, `RENDER CHECK PASSED` |
| `net.metalmod.StandaloneTestRunner` | format tables, multi-draw, sub-buffer offsets, UMA ownership |

A green check is only evidence if it can fail: the scissor assertion is a case in point — it passed
for a long time against the very flip it should have caught.

## Performance (indicative, not final)

Measured by resizing the window at 100% render scale, but **with a menu open, so the world was not
ticking**:

| window | pixels | frame time | fps |
|---|---|---|---|
| 5120×2664 | 13.64 Mpx | 8.85 ms | 113 |
| 1064×536 | 0.57 Mpx | 3.38 ms | 296 |

Fitting `time = CPU + k × megapixels`: a CPU floor of ≈ 3.14 ms (~319 fps ceiling) and
≈ 0.42 ms/Mpx of GPU cost (~65% of frame time at 5K). The CPU floor is optimistic and the GPU share
is a lower bound, because the world was not running. Re-measure in play, ideally as a Metal-vs-Vulkan
A/B.

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
Blaze3D's abstraction, and porting it does not simplify the shaderpack work). Iris is a Phase 7
dependency and lives in the separate `26.2-Fabric` instance.

Override the build target with `METALMOD_MC_INSTANCE`.

## Decisions already made (and why)

| Decision | Reason |
|---|---|
| Compile against the real client jar, not stubs | Stubs caused most integration failures; `javac` now verifies the API |
| Be a `GpuBackend`, not a MoltenVK patch | Only a backend can own presentation, MetalFX and ray tracing |
| Remove main-render-target scaling | It broke the GUI and froze input |
| Remove the LWJGL allocator interception | LWJGL 3.4 needs native function pointers for its fast path; mixing allocator ownership risks corruption. Also a measured pessimisation. |
| Delete the MoltenVK-interop / MetalFX leftovers | Inert: a per-frame hook and native scalers that could never present, plus config that controlled nothing |
| Do not pursue Sodium compatibility | It follows Blaze3D and does not ease shaderpack work (Phase 7) |
