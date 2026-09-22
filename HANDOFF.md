# MetalMod — current state

Short, factual status. See `ROADMAP.md` for where this is going and `TESTING.md` for how to test.

## What works today

- **Build:** compiles against the **real Minecraft client jar** (no API stubs).
  `./scripts/build_mod.sh` → `build/libs/metalmod-1.0.0.jar`.
- **Metal renderer backend (Phase 1 + Phase 3):** Minecraft selects `MetalBackend`, creates an
  `MTLDevice` and attaches a `CAMetalLayer` to the window. Real render pipelines are compiled from
  the engine's shaders (GLSL → SPIR-V → MSL) and the engine's draw calls are encoded and blitted to
  the drawable. The loading screen, main menu and terrain all render. Mixin:
  `PreferredGraphicsApiMixin`.
- **Real Metal resources (Phase 2):** textures, texture views, buffers and samplers are real Metal
  objects with mapped formats; uploads, readbacks and clears go through them. A run created
  `textures=4416 views=9325 buffers=77 samplers=32 failures=0`.
- **The Metal backend is opt-in and OFF by default** (`preferMetalBackend=false`, also
  `-Dmetalmod.metalBackend=true`). It must be, because until Phase 3 draws the game the window is a
  flat colour and the game is unusable; defaulting it on silently broke normal play. With it off,
  Minecraft runs on Vulkan/OpenGL exactly as before.
- **Integration:** all mixins apply. Verified in-game:
  `+GameRenderer.render +GameRenderer.resize +Window.onFramebufferResize`,
  `F3 debug entry: registered=true verified=true`.
- **F3 section:** MetalMod status lines render in the debug overlay.
- **Config GUI:** opens from Mod Menu; buttons work.
- **Native library:** `libmetalmod.dylib` loads; MetalFX capability queries and Mach VM telemetry work.

## Phase 1 status (Metal backend) — DONE

- **Native Metal substrate: done and verified** (`native/src/metalmod_metal.mm`).
  `./native/build/metalmod_smoke` passes on Apple M4 Pro: device info, exact clear-colour
  round-trip through CPU readback, and `CAMetalLayer` acquire/clear/present.
- **Java backend: done** (`net.metalmod.backend`): `MetalBackend` (GpuBackend),
  `MetalDevice` (GpuDeviceBackend), `MetalCommandEncoderBackend`, `MetalRenderPassBackend`,
  `MetalSurfaceBackend`, `MetalTransientMemory`, and the resource types
  (`MetalTexture/View/Buffer/Sampler/Fence/QueryPool/CompiledPipeline`). `MetalNative` is the
  Panama FFI binding for the `mmm_*` C surface.
- **Backend selection: done.** `PreferredGraphicsApiMixin` prepends `MetalBackend` to
  `getBackendsToTry()`, keeping the vanilla backends as fallback. A `BackendCreationException`
  from Metal degrades cleanly to Vulkan/OpenGL.
- **First light: verified.** A throwaway instance booted with `Using graphics backend Metal`,
  created device + layer, recorded the surface (`1708x960`), and presented 2400+ cleared frames
  before a clean shutdown. See `docs/phase1-boot-trace.md` for the calibration.
- **Still placeholder:** only the pipeline/draw path. `precompilePipeline` returns a valid
  placeholder and every draw is a no-op; Phase 3 replaces both. The interface contract is in
  `docs/backend-api.md`.

## Phase 2 status (resource layer) — DONE

- `net.metalmod.backend` now creates real `MTLTexture` (2D, 2D-array, cube; mip levels),
  `MTLTextureView`, `MTLBuffer` and `MTLSamplerState` objects via `MetalNative`.
- `MetalFormat` holds the single `GpuFormat` → `MTLPixelFormat` / texture-type / usage / sampler
  mapping. All 54 formats are covered.
- Uploads and readbacks use shared storage (`replaceRegion`/`getBytes`); clears are real
  render-pass clears. `MetalTransientMemory` is a real shared `MTLBuffer` bump arena.
- `MetalMod` prints `Metal resources created: textures=… views=… buffers=… samplers=… failures=…`
  with the 30 s hook summary.
- Verification: `./native/build/metalmod_smoke` passes the new resource section (byte-exact texture
  round-trip, mips, view, buffer, sampler, clear), and the in-game run reported `failures=0`.

## Phase 3 status (pipelines and draw calls) — DONE

- `MetalRenderPipeline`: `RenderPipeline` → `MTLRenderPipelineState` + `MTLDepthStencilState`
  (blend, cull, fill, topology, vertex layouts). Precompiled from the engine's `ShaderSource`, with
  lazy compilation for pipelines the engine never announces.
- `MetalShaderCompiler`: GLSL → SPIR-V (`GlslCompiler.createIntermediary`) → MSL (SPIRV-Cross),
  plus reflection to drive name-based uniform/texture/sampler binding. Vertex-stage uniform buffers
  are offset above the vertex-attribute slots (they share Metal's per-stage buffer index space).
- `MetalRenderPassBackend`: indexed / multi / grouped draws, scissor, deferred bindings, debug
  groups. Indirect draws are accepted but skipped.
- Real presentation blit (built-in full-screen-triangle MSL pipeline).
- Verification: Mojang loading screen (logo + bar), main menu (logotype, buttons, sliders, splash,
  blurred panorama) and an in-world view (sky + terrain). See `ROADMAP.md` Phase 3 for the five
  bugs fixed along the way.

Re-verified after the Phase 4 work below: `./scripts/build_mod.sh` succeeds,
`./native/build/metalmod_smoke` prints `ALL CHECKS PASSED`, and `net.metalmod.StandaloneTestRunner`
prints `ALL TESTS PASSED SUCCESSFULLY!`.

An in-game session (Metal backend, world loaded, clean shutdown) reported
`textures=4466 views=9375 buffers=183 samplers=33 failures=0 pipelineFailures=0 unboundBindings=3 |
shader pairs: compiled=53 reused=43 cached=53` — no failed pipelines, the cache reusing 43 pairs,
and the binding diagnostic naming three real unbound bindings (BUG-005).

## Phase 4 status (shaders) — DONE

Plan, evidence and results: `docs/phase4-plan.md`.

- **All 87 vanilla pipelines compile.** `tools/shader_inventory/run.sh` walks every `RenderPipelines`
  field, compiles it through the real GLSL → SPIR-V → MSL → pipeline path outside the game, and
  reports a list. Result: `total=87 ok=87 failed=0 no-source=0`. The boot log only ever proved the
  ~28 pipelines the engine announces; the rest were compiled lazily and so were unverified.
- **Cross-stage varying locations (fixed).** `animate_sprite_interpolate` failed because shaderc
  assigns varying `Location` decorations per stage by declaration order, and this pair's two stages
  disagreed — OpenGL links by name so vanilla never noticed, but Metal rejects the pipeline.
  `MetalShaderCompiler` now aligns the fragment stage's locations to the vertex stage's by name.
- **Shader-pair cache.** Keyed `(vertexId, fragmentId, ShaderDefines)`. The roadmap's per-stage
  `(id, type, defines)` key is *not* usable here: varying alignment makes a fragment stage's compiled
  form depend on its vertex partner. Pair keying gives essentially the whole benefit — the inventory
  reports `compiled=52 reused=35` over 87 pipelines.
- **Unbound bindings are visible.** `MetalRenderPipeline` exposes the names its shaders declare and
  the render pass reports anything nothing bound, de-duplicated and capped; the count appears in the
  telemetry summary as `unboundBindings=`. Report-only. It immediately found three in-game
  (BUG-005), including `lightmapInfo` in the lightmap pass.
- **Pipeline failures can no longer be silent.** Success and failure logging used to share one
  counter, so once ~30 pipelines had been logged at startup, every later failure — including all the
  in-world ones the engine precompiles last — was swallowed. Failures now have their own budget and a
  `pipelineFailures=` count in the summary.
- **Metal errors are visible.** `mmm_last_error()` carries Metal's own message (shader compile or
  pipeline validation) to the Java log; previously it only reached `NSLog` and was never captured.
- **Offline repro tool.** `tools/shader_repro/run.sh <vsh> <fsh>` runs one shader pair through the
  real pipeline outside the game and prints the SPIR-V interfaces, both MSL sources and Metal's
  verdict. Exits non-zero on rejection.
- **Found and recorded, not fixed: BUG-004.** `drawMultipleIndexed` never invokes each draw's
  `uniformUploaderConsumer`, nor its own index buffer, on a path vanilla terrain uses. It changes
  rendering, belongs to Phase 5, and could not be runtime-verified here. See `bug.md`.

**Traps worth remembering:**

- `ByteBuffer.duplicate()` resets byte order to big-endian, so `spirv.duplicate().asIntBuffer()`
  returns byte-swapped SPIR-V words. Feeding them to SPIRV-Cross works by accident (the native stack
  writes them back in native order), but any code that *inspects* the words needs
  `ByteOrder.nativeOrder()`.
- The engine's preprocessor de-duplicates `#moj_import` per file: `ShaderManager$1` keeps an
  `importedLocations` set and returns `null` for a repeat. Vanilla `rendertype_end_portal.vsh`
  imports `projection.glsl` twice, so an offline reproduction that does not de-duplicate invents two
  failures that the game does not have.

## Phase 5 status (vanilla render parity) — IN PROGRESS

Working through the known defects in evidence-ranked order. Two root causes for the missing/wrong
terrain are fixed:

- **BUG-004 — multi-draw uniforms (fixed).** `drawMultipleIndexed` never invoked each draw's
  `uniformUploaderConsumer`, so the per-draw `ChunkSection` block (`ModelViewMat`, `ChunkPosition`)
  was never bound and terrain had no transform at all. It now invokes the consumer, honours the
  per-draw index buffer/type, and diagnoses bindings on that path. Coverage:
  `MetalRenderPassBackendTest` (7 assertions).
- **BUG-005 — uniform blocks keyed by the wrong name (fixed).** Blocks were keyed by the GLSL
  *instance* name; the engine binds by block *type* name (`BindGroupLayouts` declares
  `LightmapInfo`). `lightmap.fsh` is the only vanilla shader that names its instance, which is why
  the lightmap was the one thing the diagnostic caught.

Also for Phase 5:

- **New diagnostic:** a `VertexFormat` element with no matching shader input is now reported instead
  of silently dropped from the vertex descriptor. All 87 vanilla pipelines report none.
- **F3 now shows `unbound/unmapped/failed`** counters, so a black or missing object can be explained
  without reading the log.
- **BUG-002's candidate causes were checked and ruled out** (topology mapping, front-face winding,
  atlas-only viewport flip, vertex descriptor, missing bindings). What remains is the *values* on the
  outline draw — most plausibly `LineWidth` vertex data or the `ScreenSize` uniform, since the
  shader's thickness is `LineWidth / ScreenSize`. That needs runtime inspection.
- **Deliberately left alone:** indirect draws have no vanilla callers, and all-false `DeviceFeatures`
  is the conservative direction given the paths that are not implemented.

**To make progress past this point, an in-game run is needed.** Every remaining item (BUG-001,
BUG-002, BUG-003 visual confirmation) is a runtime observation, not something that can be settled by
reading code.

## What does not work

- **Vanilla visual parity (Phase 5):** terrain and entities render unlit/flat (BUG-003), some GUI
  screens are missing sprites (BUG-001), and the block-selection outline is wrong (BUG-002).
  BUG-003 is Phase 5 work, not a Phase 4 shader item.
- **Multi-draw uniforms (BUG-004, Phase 5):** `drawMultipleIndexed` ignores each draw's
  `uniformUploaderConsumer()` and per-draw index buffer. Vanilla's chunk terrain path uses it, so
  this is likely a direct cause of the flat world.
- **Upscaling / frame generation:** inactive until the mod owns presentation and the later phases.
- **Shaderpacks, MetalFX, ray tracing:** not started.

## The performance question

Measured by resizing the window (both at 100% render scale, so only resolution differs):

| window | pixels | frame time | fps |
|---|---|---|---|
| 5120×2664 | 13.64 Mpx | 8.85 ms | 113 |
| 1064×536 | 0.57 Mpx | 3.38 ms | 296 |

Pixels fell 23.9×, fps rose only 2.6×. Fitting `time = CPU + k × megapixels`:

- **CPU floor ≈ 3.14 ms (~319 fps ceiling)**, independent of resolution
- **GPU ≈ 0.42 ms per megapixel → ~65% of frame time at 5K**

Interpretation: at native 5K the GPU is the dominant cost, so resolution-based upscaling has real
headroom (+36% at 77% scale, +55% at 67%, +94% at 50%, by that linear model). At a small window the
game becomes CPU-bound, which is why the gain is not proportional.

Caveat: both measurements had a menu open, so the world was not ticking — the CPU floor is
optimistic and the GPU share is therefore a lower bound. Worth re-measuring in normal play.

## Environment specifics that matter

- Minecraft **26.2** with **named mappings** — the client jar contains **zero** `class_XXXX` entries,
  so mixins must target named classes. Mixin rejects an *entire* mixin if any one entry in `targets`
  is missing.
- Fabric Loader 0.19.2, Java 26, Apple M4 Pro, macOS 27.
- Graphics: **Vulkan 1.2.334 via MoltenVK 1.4.2**.
- `compatibilityLevel` in `metalmod.mixins.json` must match the emitted bytecode, so
  `build_mod.sh` pins `javac --release 22`.
- **Annotation retention is load-bearing:** `@Mixin` is `CLASS`; `@Shadow`, `@Inject`,
  `@ModifyVariable`, `@At`, `@Accessor` are `RUNTIME`. Declaring the latter as `CLASS` compiles fine
  and then does nothing.

## Instance under test

```
$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2
```

Mods present: Fabric API 0.161.0, Mod Menu 20.0.2, Placeholder API 3.1.0. **Sodium is *not*
installed here** (it is in the separate `26.2-Fabric` instance) even though earlier revisions of this
file said it was. The roadmap names Sodium as Phase 5's main compatibility risk, so reinstall it
before Phase 5 parity testing rather than discovering the gap mid-way.

Override the build target with `METALMOD_MC_INSTANCE`.

## Decisions already made (and why)

| Decision | Reason |
|---|---|
| Compile against the real client jar, not stubs | Stubs caused 3 of 4 integration failures; `javac` now verifies the API |
| Remove main-render-target scaling | It broke the GUI and froze input |
| Remove the LWJGL allocator interception | LWJGL 3.4 needs native function pointers for its fast path; a Java pool cannot supply them, and mixing allocator ownership risks corruption. Also a measured pessimisation. |
| Retire the MoltenVK-interop architecture | Cannot own presentation; cannot express MetalFX or ray tracing. Superseded by the backend plan. |
