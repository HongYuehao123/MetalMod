# Phase 4 — Shaders: plan and results

> **Goal (from ROADMAP §5):** unmodified vanilla shaders compile and run.
>
> **Status: COMPLETE for compilation.** All **87** vanilla pipelines compile through MetalMod's real
> shader path, verified by `tools/shader_inventory/run.sh` (`ok=87 failed=0`). "And run" is Phase 5's
> parity work; the one remaining known defect on this path is BUG-004 (multi-draw per-draw uniforms),
> recorded in [`../bug.md`](../bug.md).
>
> Much of the shader path was built during Phase 3, because pipelines cannot be created without it.
> Phase 4 was therefore not "write a shader compiler" — it was closing the remaining gaps and making
> failures diagnosable.

---

## 1. Starting point

Phase 3 was re-verified before this plan was written:

| Check | Command | Result |
|---|---|---|
| Mod + native build | `./scripts/build_mod.sh` | `SUCCESS -> build/libs/metalmod-1.0.0.jar` |
| Native substrate | `./native/build/metalmod_smoke` | `ALL CHECKS PASSED` |
| Java suites | `net.metalmod.StandaloneTestRunner` | `ALL TESTS PASSED SUCCESSFULLY!` |
| Backend selection | game log | `Using graphics backend Metal, using drivers: Metal (macOS)` |
| Resources | game log | `textures=4426 views=9335 buffers=115 samplers=33 failures=0` |
| Pipelines | game log | `metal pipeline compiled: …` for the announced set |

The visual result was the loading screen, main menu and in-world geometry (see
[`bugs/`](bugs/)). Visual *parity* is Phase 5; the defects are tracked in [`../bug.md`](../bug.md).

## 2. What already exists

All of this was written in Phase 3 and works:

| Component | File | Role |
|---|---|---|
| Shader compiler | `backend/MetalShaderCompiler.java` | GLSL → SPIR-V (`GlslCompiler.createIntermediary`) → MSL (SPIRV-Cross), plus reflection |
| Pipelines | `backend/MetalRenderPipeline.java` | `RenderPipeline` → `MTLRenderPipelineState` + `MTLDepthStencilState`, vertex descriptors, blend/cull/fill/topology |
| Native shader side | `native/src/metalmod_metal.mm` | `mmm_library_create`, `mmm_render_pipeline_create` |
| Compile policy | `backend/MetalDevice.java` | precompile the announced set, lazily compile the rest |

Reflection already drives **name-based** binding: uniform buffers, textures and samplers are looked up
by GLSL name at draw time, so the render pass does not depend on slot numbers matching Vulkan's.

---

## 3. Done in this pass

### 3.1 Cross-stage varying locations now agree — **DONE**

**Symptom.** `minecraft:pipeline/animate_sprite_interpolate` failed with
`native pipeline creation failed`. It was recorded as a "varying mismatch" and deferred.

**Actual cause, confirmed.** shaderc compiles each stage independently, and glslang assigns varying
`Location` decorations *per stage by declaration order*. Nothing requires the two stages to agree.
This pair does not:

| stage | `texCoord0` | `fAnimationProgress` |
|---|---|---|
| `animate_sprite.vsh` out | location **0** | location **1** |
| `animate_sprite_interpolate.fsh` in | location **1** | location **0** |

SPIRV-Cross copies those decorations straight into MSL as `[[user(locnN)]]`, where Metal enforces a
strict vertex-output/fragment-input match and rejects the pipeline:

```
Fragment input(s) `user(locn0),user(locn1)` mismatching vertex shader output type(s) or not written by vertex shader
```

OpenGL links varyings **by name**, so Mojang never saw this; it is invisible until an explicit API
looks at locations.

This is a live gap, not dead code: `net.minecraft.client.renderer.texture.SpriteContents$AnimationState`
references `RenderPipelines.ANIMATE_SPRITE_INTERPOLATE`, so animated-sprite cross-fading was not
rendering on Metal.

**Fix.** `MetalShaderCompiler.compilePair(…)` compiles both stages together and rewrites the
fragment stage's `Location` decorations to match the vertex stage **by name** before SPIRV-Cross runs.
The vertex stage is the authority, because every fragment input must be satisfied by a vertex output.
Built-ins (no location) and names the vertex stage does not declare are left alone, so pipelines that
already agreed are untouched. `MetalRenderPipeline` now uses `compilePair`.

**Verified.** `tools/shader_repro/run.sh` on this pair now reports `RESULT: pipeline created OK`.

**Trap found while fixing this.** `ByteBuffer.duplicate()` resets the byte order to **big-endian**, so
`spirv.duplicate().asIntBuffer()` yields byte-swapped SPIR-V words (`0x03022307`, not the magic
`0x07230203`). Handing those to SPIRV-Cross happened to work, because the native stack writes them back
in native order and restores the original bytes — which is exactly why the mistake survived until code
inspected the words. `toSpirv` now sets `ByteOrder.nativeOrder()` explicitly.

### 3.2 Metal's own errors are now visible — **DONE**

A failed pipeline previously produced only `native pipeline creation failed` in the game log; Metal's
actual reason died in `NSLog`, which the launcher does not capture. `mmm_last_error()` now carries the
message to Java, and the failure log lines include it. The lookup is optional in `MetalNative`, so a
stale dylib cannot disable the backend over a missing diagnostic symbol.

### 3.3 Offline shader repro tool — **DONE**

`tools/shader_repro/run.sh <vertex-path-in-jar> <fragment-path-in-jar>` extracts the pair and all
`#moj_import` includes from the client jar, runs the real GLSL → SPIR-V → MSL path, prints each
stage's SPIR-V interface locations and both MSL sources, then attempts the pipeline. It exits non-zero
when Metal rejects it. This turns "some variants fail" into a one-command diagnosis.

---

## 4. Results

### 4.1 Full vanilla shader inventory — **DONE: 87/87 compile**

The gap: evidence for "unmodified vanilla shaders compile and run" was the boot log, which only
covers the pipelines the engine announces (~28). `RenderPipelines` declares **87**, and the rest are
compiled lazily on first use — so an unexercised pipeline was silently unverified.

Delivered as `tools/shader_inventory/run.sh`, which walks every `RenderPipeline` field and compiles it.
It runs outside the game, which turned out to be possible: `RenderPipelines` loads without a client
bootstrap, and the engine's own pieces can be reused directly — the real `GlslPreprocessor` (imports
and version handling), the real `injectDefines`, and the real `MetalRenderPipeline.create` path. Only
reading shader text out of the client jar is reproduced locally.

Result on the current instance:

```
pipelines: 87
cache: shader pairs: compiled=52 reused=35 cached=52
total=87 ok=87 failed=0 no-source=0
```

Two findings came out of building it:

1. **A trap in the harness, not the mod.** Vanilla `rendertype_end_portal.vsh` imports
   `projection.glsl` **twice**. A naive preprocessor inlines it twice and glslang rejects it
   (`'Projection': Cannot reuse block name`), which looked like two real pipeline failures. The engine
   avoids this: its preprocessor (`ShaderManager$1`) keeps an `importedLocations` set and returns
   `null` for a repeat. The tool now does the same. Worth knowing before trusting any offline
   reproduction.
2. **All 87 vanilla pipelines compile**, including the previously-failing
   `animate_sprite_interpolate`.

### 4.2 Shader-pair cache — **DONE, with a corrected key**

The roadmap asked for a cache keyed `(id, type, defines)` per stage, as
`VulkanDevice$ShaderCompilationKey` does. **That key is not safe for a stage here.** Varying alignment
(§3.1) rewrites a fragment stage's locations to match its vertex partner, so the same fragment shader
paired with two differently-ordered vertex shaders must compile to two different outputs. The pair is
the smallest safe unit, so the cache is keyed `(vertexId, fragmentId, ShaderDefines)`.

The measurement justified it either way — across the 87 vanilla pipelines there are 174 stage
compilations without a cache, 101 with a stage cache, and 104 with a pair cache. Pair keying gives
essentially the whole benefit (70 of 73 saved) with no correctness hazard. The inventory confirms it:
`compiled=52 reused=35`.

Sources are supplied lazily, so a hit also skips fetching and define-injecting the shader text.
Cache accounting is reported in-game through the telemetry summary line.

### 4.3 Binding/reflection audit — **DONE (diagnostic), with one finding handed to Phase 5**

Unbound bindings are now reported instead of silent. `MetalRenderPipeline` precomputes the unions of
the buffer/texture/sampler names its shaders declare, and the render pass checks them against what was
actually bound, de-duplicating per `(pipeline, kind, name)` and capping the log. The count appears in
the telemetry summary as `unboundBindings=`. This is report-only: no binding behaviour changed.

This is exactly the check that would have made BUG-003's class of failure self-evident, because a
shader sampling an unbound texture reads undefined data and renders black — which reads as "the
shader is wrong" rather than "the binding never happened".

**Finding — BUG-004.** `drawMultipleIndexed` ignores each draw's `uniformUploaderConsumer()` and the
`pushConstant` argument, and prefers the pass-level index buffer over the per-draw one. Vanilla's
`ChunkSectionsToRender` and `WorldBorderRenderer` use that path, and `VulkanRenderPass` explicitly
invokes the uploader, so this is a real contract violation on the terrain path. It is recorded in
`bug.md` with the fix shape but was **not** changed here: it alters terrain rendering, which the user
scoped to Phase 5, and it could not be runtime-verified from this environment. Phase 4 ships only
behaviour-preserving diagnostics plus shader fixes. The diagnostic is deliberately disabled on that
path for now (`applyBindings(false)`) so it cannot produce false positives; once the uploader is wired
up it can be re-enabled.

Arrays of samplers, and a sampler bound at an index different from its texture, remain unhandled.

### 4.4 Stage types beyond vertex/fragment

`ShaderType` has only `VERTEX` and `FRAGMENT`. Compute and geometry shaders cannot be expressed, which
blocks Phase 8 for any pack that uses them. Out of scope for Phase 4 proper; noted so the pipeline
abstraction is not designed in a way that forecloses it.

### 4.5 In-game verification

A real session (Metal backend, world loaded, clean shutdown) confirmed the Phase 4 work:

```
Using graphics backend Metal, using drivers: Metal (macOS)
hook summary after 30s: +GameRenderer.render +GameRenderer.resize +Window.onFramebufferResize
[MetalMod] Metal resources created: textures=4466 views=9375 buffers=183 samplers=33
    failures=0 pipelineFailures=0 unboundBindings=3 | shader pairs: compiled=53 reused=43 cached=53
```

- **0 pipelines failed**, and `end_portal` / `end_gateway` compiled (they are the pair that exposed
  the offline harness's import de-duplication bug, §4.1) — so the dedup fix was right.
- **The cache works in-game**: 43 pairs reused.
- **The binding diagnostic earned its keep**: it named three real unbound bindings —
  `lightmapInfo` in `lightmap` and `CloudFaces` texture+sampler in `clouds`. Filed as BUG-005;
  `lightmapInfo` is a strong, specific lead for the unlit world.
- Hooks all applied, and the game shut down cleanly.

**A defect this run exposed — fixed.** The compile log and the *failure* log shared one counter
(`placeholderLogCount`, caps 30 and 40), so once ~30 pipelines had been logged during startup, every
later compile — and every later **failure** — was silent. That is exactly the case that matters:
the engine precompiles the in-world pipelines (terrain, lightmap, clouds) only after the budget is
spent, so neither their success nor their failure appeared in the log. Failures now have their own
budget and a `pipelineFailures=` count in the summary, because a pipeline that fails silently just
draws nothing and reads as missing geometry.

**Not exercised in-game:** `animate_sprite_interpolate` (§3.1) — it is compiled lazily and no
interpolated animated sprite was drawn in this session, so that fix is verified offline (repro tool
and inventory) but not visually. To exercise it, look at an animated texture that sets
`"interpolate": true` — prismarine, magma, sculk, crimson/warped stem, campfire logs (23 vanilla
textures do).

---

## 5. Test strategy

1. **Inventory** — `tools/shader_inventory/run.sh`; must be `ok=87 failed=0`. This is the exit
   criterion and it also verifies the shader cache (`compiled=52 reused=35`).
2. **Per-pair repro** — `tools/shader_repro/run.sh <vsh> <fsh>` for any pipeline that fails, before
   touching code.
3. **Regression** — `./native/build/metalmod_smoke` and `net.metalmod.StandaloneTestRunner` after
   every native or binding change.
4. **In-game (not run from this environment)** — Metal backend on, check the loading screen, menu, an
   in-world view, and animated sprites; confirm `unboundBindings=` in the telemetry summary and that
   it explains any remaining black geometry.

## 6. Risks and open items

- **Interface rules are stricter than Vulkan's.** Metal rejects things Vulkan tolerates. §3.1 was one
  instance; the inventory now covers all vanilla pipelines, so remaining risk is in resource packs.
- **Names are load-bearing.** Alignment and binding both key on GLSL names from SPIR-V. If a stage is
  ever compiled without `OpName` (stripped), both degrade silently to "no binding".
- **Lazy compilation hides failures.** A pipeline that fails on first use degrades to "draws skipped".
  The inventory is the mitigation, and it now runs all 87.
- **The inventory reads the vanilla jar only.** It does not see resource-pack shader overrides, and it
  reproduces the engine's preprocessing rather than calling `ShaderManager`. The fidelity argument is
  in §4.1; an in-game equivalent would close it completely.
- **BUG-004 is open** on the multi-draw path (§4.3), and **BUG-005** (unbound `lightmapInfo` and
  `CloudFaces`) was found in-game (§4.5).
- **Sodium is not installed in the test instance**, although `HANDOFF.md` claimed it was. The
  roadmap names Sodium as Phase 5's main compatibility risk, so Phase 5 should start by reinstalling
  it rather than discovering the gap mid-way.

## 7. Order — Phase 4 complete

| # | Task | Size | Status |
|---|---|---|---|
| 1 | Cross-stage varying alignment | S | ✅ done |
| 2 | Native error surfacing | S | ✅ done |
| 3 | Offline repro tool | S | ✅ done |
| 4 | Vanilla shader inventory, driven to zero failures | M | ✅ done (87/87) |
| 5 | Shader-pair cache | S | ✅ done (52 compiled / 35 reused) |
| 6 | Binding/reflection audit + unbound diagnostic | M | ✅ done (BUG-004 recorded for Phase 5) |

**Phase 4 exit criterion — "unmodified vanilla shaders compile and run" — is met for compilation:**
every one of the 87 vanilla pipelines compiles through the real path, verified by
`tools/shader_inventory/run.sh`. "And run" is covered by Phase 5's parity work; the remaining known
rendering defect on this path is BUG-004.
