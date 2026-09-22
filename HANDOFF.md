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
- **BUG-007 — sampler address modes were swapped (fixed, live for vanilla).** `MTLSamplerAddressMode`
  is ClampToEdge=0, Repeat=2, but the constants had Repeat=0, ClampToEdge=2, so every sampler in the
  game used the opposite mode: atlases sampled with Repeat (sprites bleeding into their neighbours,
  mip tails smearing across the atlas) and tiling textures clamped. Found by auditing the mapping
  tables against the macOS SDK headers — which is where the next two came from as well.
- **BUG-006 — five `MTLBlendFactor` values were wrong (fixed, latent for vanilla).** Metal is not in
  GL order: SourceAlphaSaturated is 10 and the constant factors are 11..14. No vanilla pipeline uses
  those five, but a shaderpack blending with a constant alpha would have blended wrongly and
  silently.
- **BUG-013 — texel buffers (fixed).** `CloudFaces` is an `isamplerBuffer` that the engine binds with
  `setUniform(name, GpuBuffer)`, which MetalMod had no path for, so vanilla clouds texel-fetched
  undefined data. Measured twice rather than guessed: the cloud buffer is 258 bytes at one byte per
  texel, and the natural fix — an `MTLTextureTypeTextureBuffer` — *aborts* Metal for `R8_SINT`. The
  working path is the one SPIRV-Cross already emits (`texture2d<int>` + `spvTexelBufferCoord`), so
  the bytes are presented as a 2D texture, cached per backing buffer.
- **The multi-draw fix from the previous round was incomplete.** Correcting the draw-count limit was
  necessary but not sufficient: `RenderPass.multiDrawIndexed`/`multiDraw` gate on the
  *feature flags* too, so those methods still threw. `multiDrawDirectInterleaved` and
  `multiDrawDirectSeparate` are now reported `true`, because `MetalRenderPassBackend` really does
  implement all four of those methods (it loops over the draw list issuing one indexed draw each).
  `multiDrawIndirect` and `drawIndirect` stay `false`, because those two are genuine no-ops and
  reporting them true would make the engine take a path that silently draws nothing.
  `tools/render_check` now drives Minecraft's own `RenderPass.multiDrawIndexed` and checks that both
  entries of a two-quad call are drawn - a call that threw before the flags were corrected.
- **The present-mode set was checked and is sufficient.** `GpuSurface.configure` throws for a mode the
  backend does not advertise, and `getSupportedVsyncMode` throws if none of its preferences are
  available. With {FIFO, IMMEDIATE} advertised it resolves to FIFO with vsync on and IMMEDIATE with
  vsync off, so neither throws. `FIFO_RELAXED` and `MAILBOX` fall back to those, correctly: Metal's
  `CAMetalLayer` only offers `displaySyncEnabled`, so it cannot express adaptive or mailbox pacing.
- **Two capability reports were wrong, both in the same way.** `DeviceLimits.maxMultiDrawDirectInterleavedDrawCount`
  was 0, which reads like "no batching" but actually means `RenderPass.multiDrawIndexed` throws an
  `IllegalArgumentException` for *any* non-empty call. Vanilla never calls it, so it went unnoticed,
  but it is a trap for any mod that batches. `MetalRenderPassBackend` loops over the draw list, so
  the honest answer is "as many as you give me" — and the Vulkan backend reports exactly this
  fallback (`Integer.MAX_VALUE`) when `VK_EXT_multi_draw` is missing.
- **Device capabilities are reported per feature, and one was wrong.** `DeviceFeatures` is what the
  engine uses to decide which paths it may take, so each value is now a claim about what this backend
  implements. `nonZeroFirstInstance` was reported `false`, which made the engine refuse to pass a
  non-zero `firstInstance` at all — but both native draws already forward it as `baseInstance`, and
  `metalmod_smoke` proves Metal honours it (`[[instance_id]]` includes it: instance colour 3 comes
  back for `firstInstance = 3`). It is now `true`. The rest stay false for stated reasons: the
  indirect and multi-draw-indirect paths are unimplemented no-ops, `shaderDrawParameters` has no
  vanilla consumer, and `persistentMapping` is correctly false because buffers are shared storage
  written directly.
- **The shader binding layer now reports clean.** With BUG-012 and BUG-013 fixed, the inventory
  compiles all 87 pipelines and reports **no diagnostics at all** — no slot collisions, no
  reflection/MSL mismatches for uniform buffers, textures or vertex attributes, and no binding-kind
  mismatches. That is the first time the full pipeline set has been clean.
- **BUG-012 — uniform blocks shared Metal slots (fixed, live for vanilla).** Each resource's Metal
  slot came from its SPIR-V `binding`, but glslang emits duplicate bindings: every shader importing
  `fog.glsl` gets `Fog` at binding 0 alongside another block also at 0, so both landed in MSL buffer
  16 and the second bind overwrote the first. `terrain.vsh` computes its vertex position from
  `Globals`. **This is very likely the actual cause of BUG-003** — and it was invisible to the
  unbound-binding diagnostic, because nothing was missing: both were bound, to the same place. Slots
  now come from a per-stage counter, and `checkUniqueSlots` reports any collision for all 87
  pipelines (none). BUG-013 records the remaining texel-buffer gap (`CloudFaces`, vanilla clouds).
- **BUG-011 — fences were no-ops (fixed, live for vanilla).** `MetalFence.awaitCompletion` returned
  `true` immediately on the premise that "submission is synchronous"; it is not — MetalMod commits
  command buffers without waiting. `MappableRingBuffer.rotate` awaits a slot's fence with an unbounded
  timeout before recycling it, so the CPU could overwrite data the GPU was still reading: a
  write-after-read that shows up as intermittently corrupted streamed data rather than an error.
  `MetalFence` now wraps an `MTLSharedEvent` signalled in commit order. This is a strong candidate for
  any *intermittent* artefacts in BUG-002/003, which a deterministic bug would not explain.
- **BUG-010 — sub-rectangle clears (fixed, live for vanilla).** The region variant of
  `clearColorAndDepthTextures` dropped its `x/y/width/height` and cleared the whole attachment, because
  a Metal render pass clears a whole attachment and the load action ignores the scissor. Vulkan honours
  the rect via `VkClearRect`, so the backends disagreed. `GuiItemAtlas` clears one slot-sized rectangle
  at a time into the GUI item atlas, so every slot already rendered was being erased — a likely
  contributor to BUG-001. Now cleared with a scissored full-screen triangle (colour *and* depth), with
  the no-rectangle path keeping the fast load-action clear. Proven by a native test.
- **BUG-008 — mip filtering (fixed).** `mmm_sampler_create` hardcoded
  `MTLSamplerMipFilterNotMipmapped` behind a `// TEST: force mip 0` comment, so no sampler ever read a
  mip level and `lodMaxClamp` was ignored. The filter now follows the engine's `maxLod`. This is the
  one behaviour change in the batch that can be isolated in a run without a rebuild:
  `-Dmetalmod.mipFilter=off` restores the old behaviour.
- **BUG-009 — transient-arena slice offsets (fixed, latent).** Sub-buffers share the parent's handle
  while `slice(0, size)` reports offset 0 — right for the CPU upload paths, which read through the
  offset `data` segment, and wrong for GPU binding. `MetalBuffer` now carries a base offset that the
  GPU binding paths add; a no-op for every buffer that owns its handle. Vanilla only uploads through
  `TransientMemory`, so it never showed, but it would have bitten anything streaming vertices.

Also for Phase 5:

- **Four fixes are now proven against real Metal behaviour**, not just against headers or reasoning:
  the mip filter (level 1 sampled when asked), the address modes (REPEAT wraps, CLAMP_TO_EDGE
  clamps), the region clear (rectangle changed, outside preserved, depth included), and the fence
  (a clear is only visible after waiting on it).
  All three are in `metalmod_smoke`, which now covers mip selection, address modes, region clears,
  resources, draw and surface.
- **Diagnostics are now cross-checked against the pipeline's own declaration.** `verifyBindingKinds`
  compares the reflection against `BindGroupLayout`, which states authoritatively whether each uniform
  is a `UNIFORM_BUFFER` (bound with a `GpuBufferSlice`) or a `TEXEL_BUFFER` (bound with a
  `GpuBuffer`). Running it over all 87 pipelines names exactly two real problems - `CloudFaces` in
  `clouds` and `flat_clouds` (BUG-013) - and nothing else.
- **A tooling bug had been hiding diagnostics.** `tools/shader_inventory` captured `System.err` to
  attribute failures per pipeline and then **discarded it for successful compiles** - which is exactly
  where the new diagnostics write. So an earlier claim in this file that "all 87 vanilla pipelines
  report no unmapped vertex attributes" was not evidence of anything. It now prints diagnostics for
  successful pipelines too. Corrected finding: several pipelines (`entity_shadow`, `beacon_beam_*`)
  declare vertex attributes their shader has no input for, which is harmless - the extras are dropped
  and the shader never reads them.
- **One diagnostic was inverted.** The unmapped-vertex-attribute check reported the harmless case and
  described it as the shader reading undefined data, which is wrong. The real hazard is the opposite -
  a shader input the vertex descriptor does not provide, which makes Metal reject the pipeline - and
  that is what `reportMissingVertexAttribute` now checks.
- **Enum tables are now pinned rather than trusted.** `MetalFormatTest` asserts every blend factor,
  blend op, compare function, primitive topology, sampler address/filter, texture type/usage and the
  write-mask bits against the SDK header values, plus all 55 `GpuFormat` → `MTLPixelFormat` entries
  (all verified correct). These tables give no runtime feedback — a wrong entry merely renders
  differently — which is why the two bugs above stayed invisible until they were read.
- **New diagnostic:** a `VertexFormat` element with no matching shader input is now reported instead
  of silently dropped from the vertex descriptor. All 87 vanilla pipelines report none.
- **F3 now shows `unbound/unmapped/failed`** counters, so a black or missing object can be explained
  without reading the log.
- **Checked and found correct** (so not worth re-investigating): the hardcoded `D32_FLOAT` depth
  format is what MC actually creates; every vertex element format vanilla uses (`FLOAT_32` x1/2/3,
  `SINT_16` x2, `SNORM_8` x4, `UNORM_8` x4) maps correctly; vertex attributes all resolve.
- **BUG-002's candidate causes were checked and ruled out** (topology mapping, front-face winding,
  atlas-only viewport flip, vertex descriptor, missing bindings). What remains is the *values* on the
  outline draw — most plausibly `LineWidth` vertex data or the `ScreenSize` uniform, since the
  shader's thickness is `LineWidth / ScreenSize`. That needs runtime inspection.
- **Deliberately left alone:** indirect draws have no vanilla callers, and all-false `DeviceFeatures`
  is the conservative direction given the paths that are not implemented.

- **Eleven rendering mechanisms are verified offline.** `tools/render_check` covers uniform values
  reaching a shader as colour, uniform blocks placing geometry, the entity vertex format with
  per-face lighting and four uniform blocks, screen-space line expansion, UV orientation, texture
  copies (whole and by rectangle), the atlas compositing flip, `multiDrawIndexed` through Minecraft's
  own `RenderPass`, scissor clipping, alpha blending, and 16-bit indices with non-zero
  `firstIndex`/base-vertex offsets. Each one is a mechanism one of the open bugs implicates, and the
  harness has eliminated five BUG-001 theories.
- **A wrong pixel can now be diagnosed without a debugger.** `-Dmetalmod.dumpMsl=<substring>` prints
  the generated MSL for the matching shader pairs, with `all` for every pair. The MSL is where the
  varyings, their interpolation and the `[[attribute(N)]]` / `[[buffer(N)]]` indices are decided, and
  none of that survives into the GLSL. It is what located the entity overlay-semantics mistake in the
  test harness itself rather than in the backend.
- **Rendering is now verified offline, not just compilation.** `tools/render_check` drives the real
  backend — device, command encoder, render pass, uniform and vertex binding, depth, draw, readback —
  with two actual vanilla pipelines and asserts the pixels. `minecraft:pipeline/gui` is rendered with
  three different `ColorModulator` values and comes back blue, red and grey exactly as expected, and
  `minecraft:pipeline/solid_terrain` renders a full-screen quad white, which requires `Globals`,
  `ChunkSection`, `Projection` and `Fog` to all be bound correctly. That terrain case is precisely
  what BUG-012 broke, so the harness would have caught it. It also caught a mistake in my own test
  first: the terrain pipeline uses reversed-Z, so the quad was correctly depth-rejected until the
  test used near = 1.0 and cleared depth to 0.0. It also draws a 2px line through the real `LINES`
  pipeline and checks a row 8px away stays untouched, which is BUG-002's mechanism: the line
  expansion divides by `ScreenSize` from `Globals`, the block that collided with `Fog`, so a
  mis-bound `Globals` turned the outline into a screen-filling quad. BUG-002's root cause is
  therefore BUG-012, confirmed at render level. It also maps each corner of the screen onto one
  texel of a 2x2 texture through `gui_textured`, which confirms `texCoord0` samples the right texel
  in the right orientation - ruling out a Y flip as the cause of BUG-001's "wrong sprite". It also
  copies a 4x4 texture whole and as a 2x2 rectangle at (1,1), which rules out the post-processing
  blit behind the blur as the cause of the "coarse/blocky" background. It also draws into a target
  labelled `/atlas/` and checks that NDC y = -1 lands in framebuffer row 0, verifying the atlas
  compositing flip and the winding flip a negative viewport needs. BUG-001's leading hypothesis is
  therefore that it is already fixed by BUG-007 (atlas samplers bleeding), BUG-010 (region clears)
  or BUG-012 (uniform slots) - all reported against a build predating them.

- **Entity rendering is verified offline, and that is what closed BUG-003's entity half.** It draws a
  full-screen quad through the real `ENTITY_CUTOUT` pipeline with its real 36-byte vertex format -
  `Position`, `Color`, `UV0`, `UV1`, `UV2` and the `Normal` attribute no other pipeline uses - and
  asserts white with both light directions along the normal. `ENTITY_CUTOUT` is compiled with
  `PER_FACE_LIGHTING`, so `gl_FrontFacing` selects the front or the back light colour; the back colour
  is `Color * 0.4` = 102, which means a winding regression fails the check rather than passing quietly.
  A second draw turns fog on: every corner of the quad is at `length((1,1,1)) = 1.732` and
  `sphericalVertexDistance` is `length(Position)` evaluated *per vertex*, so the varying is the
  constant 1.732 across the whole quad - not the 1.0 that interpolating the position would suggest -
  which with environmental fog from 0 to 2 is a fog value of exactly 0.866 and puts a blue FogColor at
  34 34 255. That draw is what proves `Lighting` and `Fog` have separate Metal slots (BUG-012). A
  third draw uses a non-white `ColorModulator`, because `DynamicTransforms` is the one block the vertex
  and fragment stages *share* and it is bound at a different Metal slot in each, so this proves the
  fragment stage's copy is bound too. `Sampler1` is read with `texelFetch` in the vertex stage, so that
  path is covered as well.

  The neutral overlay texel matters and was worth checking rather than assuming. The shader blends the
  opposite way round from what the name suggests:
  `color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a)`, so alpha 1 keeps the entity colour and
  alpha 0 paints the overlay colour straight on. `OverlayTexture`'s generation loop confirms the texel
  `NO_OVERLAY` points at (u = 0, v = 10) is white with alpha 255, and its grid is white in RGB
  everywhere with only alpha varying (178 for the hurt rows, 255 down to 63 across the rest). The
  harness's first version used a transparent overlay, which is the *full-black-overlay* case, and the
  resulting black pixel was a mistake in the test rather than in MetalMod.

**To make progress past this point, an in-game run is needed.** Everything still open (BUG-001,
BUG-002, BUG-003) and every fix in this batch are runtime observations — the static surface has been
audited end to end and no further defect can be settled by reading code.

## What does not work

- **Vanilla visual parity (Phase 5):** some GUI screens are missing sprites (BUG-001) and the
  block-selection outline is wrong (BUG-002). Both mechanisms are now reproduced and pass offline, so
  the leading hypothesis for each is that it is already fixed by BUG-007/010/012 and simply has not
  been looked at since. Terrain and entity rendering (BUG-003) both now render correctly offline —
  terrain through `SOLID_TERRAIN` and entities through `ENTITY_CUTOUT` with the real 36-byte vertex
  format — so what is left there is in-game confirmation, not a known defect.
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
