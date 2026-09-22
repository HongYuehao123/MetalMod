# MetalMod Roadmap

> **Status:** rewritten after discovering that Minecraft 26.2 ships a pluggable graphics backend.
> This document supersedes the "bolt MetalFX onto MoltenVK" architecture that the code currently
> implements. Read §2 before anything else — it changes what this project is.

---

## 1. The goal

Three objectives, in increasing order of ambition:

1. **Run shaderpacks** — existing GLSL packs, and whatever Vulkan-era format appears.
2. **A native Metal renderer backend for Minecraft** — the VulkanMod analogue, and the reason the
   project is called MetalMod.
3. **Native ray tracing on Apple Silicon.**

All three require Metal code and MetalFX. All three are gated on the same prerequisite, which is
goal 2: once MetalMod *is* the renderer, the other two become ordinary features of it rather than
cross-API hacks.

---

## 2. The discovery that shapes everything

**Minecraft 26.2 abstracts the graphics API behind a pluggable backend.** Verified against the real
client jar (`javap -cp <client>.jar -p <class>`):

```java
public interface GpuBackend {                       // com.mojang.blaze3d.systems
    String getName();
    void setWindowHints();
    void handleWindowCreationErrors(GLFWErrorCapture$Error) throws BackendCreationException;
    GpuDevice createDevice(long window, ShaderSource, GpuDebugOptions, Runnable)
            throws BackendCreationException;
}
```

`GpuDevice` is a concrete, API-agnostic class that wraps a `GpuDeviceBackend` — the driver
implementation point. Sub-backends cover the rest:

| Package | Role |
|---|---|
| `com.mojang.blaze3d.opengl` | **existing** OpenGL backend (`GlDevice`, `GlCommandEncoder`, …) |
| `com.mojang.blaze3d.vulkan` | **existing** Vulkan backend (`VulkanDevice`, `VulkanCommandEncoder`, `VulkanGpuSurface`, `VulkanTransientMemory`, `VulkanQueue`, …) — 69 classes, 386 KB of bytecode |
| `com.mojang.blaze3d.framegraph` | `FrameGraphBuilder`, `FramePass` |
| `com.mojang.blaze3d.systems` | `GpuDeviceBackend`, `CommandEncoderBackend`, `RenderPassBackend`, `GpuSurfaceBackend`, query pools, device limits/features |

**Backend selection** is `net.minecraft.client.PreferredGraphicsApi` — an enum
(`DEFAULT`, `OPENGL`, `VULKAN`) whose `getBackendsToTry()` returns a `GpuBackend[]`. One mixin on
that method can put a Metal backend first.

**Shaders are already cross-backend.** The Vulkan path is:

```
ShaderSource.get(id, type)        // GLSL text, from resources or a shaderpack
  -> GlslPreprocessor             // #moj_import, version handling, define injection
  -> lwjgl-shaderc 3.4.1          // GLSL -> SPIR-V
  -> IntermediaryShaderModule     // SPIR-V + reflection (uniform buffers, samplers, in/out)
  -> VulkanShaderModule
```

Reflection uses **SPIRV-Cross** (`org.lwjgl.util.spvc` — already an LWJGL binding present on this
machine), and SPIRV-Cross can emit **MSL**. So the front half of the shader pipeline is reusable
verbatim; a Metal backend replaces only the final step.

### What this means

A Metal backend is **~134 interface members** across twelve types, with a complete worked reference
implementation to read (the Vulkan backend, which is architecturally the closest analogue because it
is also explicit and command-buffer based).

This is *not* the VulkanMod problem. VulkanMod had to reimplement the renderer because Minecraft had
no backend abstraction then. Mojang has since done that work. MetalMod's job is to be a driver.

---

## 3. Why the current architecture is a dead end

The existing code keeps Minecraft on MoltenVK and reaches in with `VK_EXT_metal_objects` to run
MetalFX on MoltenVK's textures, then fights MoltenVK for the `CAMetalLayer`. That was proven
unworkable here:

- **Presentation.** MoltenVK presents *after* the hook runs, so any frame MetalMod produces is
  overwritten. Verified: the pipeline reports `MetalMod does not own presentation` forever.
- **Synchronisation.** Two `MTLCommandQueue`s on one device have no implicit ordering, so the Metal
  pass and MoltenVK's pass need explicit `MTLSharedEvent` cross-queue sync.
- **Interop.** Minecraft's images are not created with `VkExportMetalObjectCreateInfoEXT`, so
  `vkExportMetalObjectsEXT` cannot return their textures without a Vulkan layer that rewrites
  `vkCreateImage`.
- **Ceiling.** MoltenVK cannot expose MetalFX or ray tracing at all.

Once MetalMod **is** the backend, every one of these disappears by construction: MetalMod owns the
device, the swapchain, the command queues and presentation. MetalFX becomes an ordinary pass over
our own textures, and ray tracing becomes possible at all.

---

## 4. What happens to the existing code

### Keep, reworked

| Component | Fate |
|---|---|
| `scripts/build_classpath.py`, `build_mod.sh` | **Keep.** Already migrated to compiling against the real client jar instead of API stubs (see §8). |
| `net.metalmod.Diagnostics` | Keep. Hook-application reporting paid for itself repeatedly. |
| `net.metalmod.debug.MetalModDebugEntry` | Keep. F3 status is the project's best observability channel. |
| `MetalConfig`, config screen | Keep, extended for backend selection. |
| Native build plumbing (CMake, Metal linkage) | Keep; evolves into the backend's native bridge. |
| `UnifiedMemoryManager` (telemetry only) | Keep for F3 metrics. |

### Retire

| Component | Why |
|---|---|
| `VulkanFrameManager` | Superseded: no VkImage plumbing needed once we own the device. |
| `MetalBridge` Vulkan interop (`metalmod_register_vulkan_device`, `metalmod_process_frame`) | Superseded by backend-owned textures. |
| `metalmod_pacer.mm`, `metalmod_compositor.mm`, spatial/temporal/interpolator wrappers | MetalFX returns in Phase 8, against our own textures, with no interop or pacing hacks. |
| `RenderTargetMixin` | Already removed — scaling the main render target breaks the GUI (`Scissor ... out of bounds for render area`) and froze input. |
| `JitterHelper` | Returns in Phase 8 with temporal upscaling. |
| `MetalMemoryAllocator` | **Removed.** LWJGL 3.4 requires native function pointers (`getMalloc`, `getAlignedFree`, …) for its fast allocation path; a Java pool cannot supply them honestly, and mixing libc- and pool-allocated pointers behind one `free()` risks corruption. It was also a measured pessimisation. |

---

## 5. Phases

Effort is relative sizing, not a schedule: **S** ≈ days, **M** ≈ weeks, **L** ≈ months, **XL** ≈
multiple months. Estimates assume one experienced developer.

### Phase 0 — Build foundation ✅ **DONE**

Compile against the **real client jar** rather than generated API stubs.

- Why it mattered: three separate multi-hour failures came from stubs disagreeing with reality
  (intermediary `class_XXXX` targets; `@Retention(CLASS)` hiding `@Inject`/`@Accessor` from Mixin;
  `RenderTarget.resize(int,int,boolean)`, `Window.getFramebufferWidth()`,
  `Minecraft.getMainRenderTarget()` and `resizeDisplay()` not existing).
- Result: `javac` now verifies every Minecraft API call. The migration immediately surfaced four
  genuine mismatches, including the LWJGL allocator contract above.
- Deleted `scripts/generate_stubs.py` and the stub build path.

**Still missing:** a launch/debug loop (`runClient`). Compilation is verified; running still means
copying a jar into the instance. Loom would give a proper dev loop — desirable but no longer a
correctness prerequisite.

### Phase 1 — First light: device, surface, clear  · **M**  · ✅ **DONE**

**Native substrate: DONE and verified.** `native/src/metalmod_metal.mm` provides device, queue,
texture create/readback, `CAMetalLayer` surface (acquire/clear/present) and command-buffer/clear-pass
encoding behind a C API. `native/tests/metal_smoke.mm` proves it end to end on the target machine:
Apple M4 Pro, 16384 max texture, clear colour round-trips exactly through CPU readback, and a
detached `CAMetalLayer` acquires, clears and presents a drawable. Run it with
`./native/build/metalmod_smoke`.

Two SDK realities worth remembering: Metal exposes **no** max-texture-size query (the value is
derived from `supportsFamily:`), and `[MTLDevice newCommandBuffer]` is the Metal 4 API returning
`id<MTL4CommandBuffer>` — Metal 3 command buffers must come from a queue.

**Java side: DONE** (`net.metalmod.backend`) — the full `GpuDeviceBackend`,
`CommandEncoderBackend`, `RenderPassBackend` and `GpuSurfaceBackend` surfaces, `MetalNative`
as the Panama binding, and `PreferredGraphicsApiMixin` prepending Metal while keeping the vanilla
backends as fallback (a `BackendCreationException` degrades cleanly). Textures, buffers, samplers
and pipelines are deliberately placeholders and every draw is a no-op; that is Phase 2/3 work.

**Verified first light.** In a throwaway instance, Minecraft logged `Using graphics backend Metal`,
created the device and a `CAMetalLayer`, and presented 2400+ cleared frames at ~60 fps before a
clean shutdown. The full boot sequence — and why a clear-only backend cannot boot — is recorded in
`docs/phase1-boot-trace.md`.

**Done when:** Minecraft boots and presents a cleared window on Metal. ✅ Achieved; the window shows
a pulsing first-light colour because all draws are inert.

**Risks:** window/layer ownership with GLFW; present-mode negotiation; device-loss handling.

### Phase 2 — Resource layer  · **M**  · ✅ **DONE**

- Real `MetalTexture` / `MetalTextureView` / `MetalBuffer` / `MetalSampler` / `MetalFence`.
- `GpuFormat` → `MTLPixelFormat` mapping for all 54 formats. Minecraft 26.2 has no sRGB formats,
  so none are mapped; the 3-channel formats have no Metal equivalent and map to the 4-channel format
  of the same component type (their uploads are skipped rather than written with a wrong stride).
- Shared storage throughout, with `replaceRegion`/`getBytes` upload and readback. The
  `TransientMemory` ring allocator is a real shared `MTLBuffer` sliced into sub-buffers.
- Render-pass clears (colour and depth) are real.
- Deferred to Phase 3: `MTLCounterSampleBuffer` query pools, GPU-side blits, and private storage
  for GPU-only resources.

**Done when:** the game creates all its textures, buffers and samplers without falling back.
✅ Verified in a throwaway instance: `textures=4416 views=9325 buffers=77 samplers=32 failures=0`,
3600+ frames presented. The native smoke test adds byte-exact texture upload/readback, mip levels,
texture views, buffers, samplers and a clear round-trip.

### Phase 3 — Pipelines and draw calls · **M** · ✅ **DONE**

- `MetalRenderPipeline` — `RenderPipeline` → `MTLRenderPipelineState` + `MTLDepthStencilState`; blend, cull, polygon mode, vertex layouts, primitive topology. Pipelines are precompiled from the engine's `ShaderSource` and compiled lazily on first use for the ones the engine never announces (e.g. `mojang_logo`).
- Shader path: GLSL → SPIR-V via `GlslCompiler.createIntermediary` → MSL via SPIRV-Cross, with the reflected buffer/texture/sampler indices read back for name-based binding.
- Full `RenderPassBackend`: indexed draws (direct, multi and grouped), scissor, deferred name→slot binding, debug groups. Indirect draws are accepted but skipped for now.
- Real blit of the engine's render target into the drawable (built-in full-screen-triangle MSL pipeline).

**Done when:** simple geometry renders correctly (the sky and a flat-coloured world). ✅ Verified: the
Mojang loading screen (logo + bar), the main menu (logotype, buttons, sliders, splash, blurred
panorama) and an in-world view (sky + terrain silhouette) all render.

Bugs found and fixed during the phase (kept here because they are easy to reintroduce):

1. **Vertex buffers and uniform buffers share Metal's per-stage buffer index space.** SPIRV-Cross
   emitted `DynamicTransforms` at `[[buffer(0)]]` while `VertexFormat` slot 0 was also bound at
   index 0, so the UBO overwrote the vertex data and nothing rasterised. Vertex-stage uniform buffers
   are shifted by 16 (`VERTEX_BUFFER_INDEX_OFFSET`) above the attribute slots.
2. **`MemorySegment.asByteBuffer()` is big-endian.** MC writes floats without setting the order, so
   mapped buffers read as zeros/garbage. Buffers and transient memory now use the native order.
3. **Render passes must commit in `submitRenderPass()`.** The engine records a pass on one encoder
   but calls `submit()` on another, so deferring the commit dropped every draw.
4. **The presentation blit needs `MTLTextureUsageShaderRead`.** MC creates its main target as
   render-target-only; the usage mapping now grants shader-read (and pixel-format-view) to every
   texture, which are free on Apple silicon.
5. **Atlas compositing assumed a Y-down (Vulkan) NDC.** `TextureAtlas.uploadInitialContents`
   renders every sprite into the atlas with `ortho2D(0, w, 0, h)`, which maps atlas row 0 to
   NDC y = -1. Metal's NDC is Y-up, so the atlas came out vertically mirrored and sprite UVs sampled
   the wrong sprite (unselected buttons drew status icons). Atlas render passes now use a flipped
   viewport **and a flipped front-face winding** — a negative Metal viewport mirrors Y and reverses
   triangle winding, so without the winding flip the mirrored quads are back-face culled and the
   atlas ends up empty.

**Open item, fixed in Phase 4:** `minecraft:pipeline/animate_sprite_interpolate` failed to build.
Root cause was not what it looked like: shaderc assigns varying `Location` decorations per stage by
declaration order, and this pair's vertex and fragment stages disagreed, which Metal rejects outright
(`Fragment input(s) user(locn0),user(locn1) mismatching vertex shader output type(s)`). OpenGL links
varyings by name, so vanilla never saw it. The pair is live — `SpriteContents$AnimationState` uses
it — so animated-sprite cross-fading was not rendering. Fixed by aligning the fragment stage's
locations to the vertex stage's by name in `MetalShaderCompiler.compilePair`. See
[`docs/phase4-plan.md`](docs/phase4-plan.md) §3.1.

### Phase 4 — Shaders · **M** · ✅ **DONE**

> Detail, evidence and results: [`docs/phase4-plan.md`](docs/phase4-plan.md).

- Reuse the GLSL front end: `GlslPreprocessor` → shaderc → SPIR-V.
- **SPIR-V → MSL via SPIRV-Cross** (`org.lwjgl.util.spvc`, already bundled), then
  `newLibraryWithSource:` / `newLibraryWithData:`.
- Translate reflection output (uniform buffers, samplers, inputs/outputs) into Metal bindings.
- Shader cache (the roadmap asked for a per-stage `(id, type, defines)` key, as
  `VulkanDevice$ShaderCompilationKey`; the cache is keyed on the **pair** instead, because varying
  alignment makes a fragment stage's compiled form depend on its vertex partner — see the plan §4.2).

Most of the mechanism landed during Phase 3, because pipelines cannot be created without it. What
Phase 4 added:

- **Cross-stage varying locations now agree.** `animate_sprite_interpolate` failed because shaderc
  assigns varying locations per stage by declaration order and this pair's stages disagreed; OpenGL
  links by name, Metal rejects it. Fixed by aligning the fragment stage to the vertex stage by name.
- **Metal's own errors are visible** (`mmm_last_error`), instead of dying in `NSLog`.
- **Every vanilla pipeline compiles**: a new inventory compiles all **87** `RenderPipelines` fields
  through the real path — the boot log only ever proved the ~28 the engine announces.
- **A shader-pair cache**, plus an **unbound-binding diagnostic** that reports a shader sampling
  something nothing bound, instead of rendering black silently.

**Done when:** unmodified vanilla shaders compile and run. ✅ **87/87 compile**, verified by
`tools/shader_inventory/run.sh`. "Run" belongs to Phase 5's parity work; the open draw-path defect is
BUG-004.

**Risk:** MC's `ShaderType` has only `VERTEX` and `FRAGMENT`. Any pack needing compute or geometry
shaders — common in modern shaderpacks — requires extending the pipeline beyond what the vanilla
abstraction models. Plan for that in Phase 7.

### Phase 5 — Vanilla render parity  · **L–XL**

The bulk of the work, and where "it compiles" becomes "it plays".

- World/terrain, entities, block entities, particles, sky/weather.
- GUI, text, item rendering, tooltips, debug overlays.
- Post-processing chains, framebuffers, shadow/lightmap passes.
- `HintsAndWorkarounds` / `DeviceFeatures` / `DeviceLimits` reporting so the game takes Metal-appropriate paths.

**Done when:** a normal session is visually indistinguishable from Vulkan/MoltenVK, at comparable
frame rate.

**Compatibility risk:** Sodium replaces terrain rendering. It sits on Blaze3D's abstraction in
modern versions, so it should follow, but its terrain path is the performance-critical one and needs
dedicated testing. Iris is the shaderpack loader and is a Phase 7 dependency.

**Progress (in flight).** Phase 5 began with the draw-path defects rather than the visual ones,
because they are what make a visual symptom fixable. Twenty-one bugs were found and fixed
(BUG-004 … BUG-021; see `bug.md`). Most were in the *values* rather than the plumbing: per-draw chunk
uniforms never uploaded, uniform blocks keyed by instance name, five wrong `MTLBlendFactor` values,
swapped sampler address modes, a hardcoded mip filter, arena sub-buffers binding the parent's offset
0, sub-rectangle clears wiping a whole attachment, a no-op `GpuFence`, duplicate SPIR-V bindings that
shared a Metal slot, and unsupported texel buffers. The last one is worth noting because `R8_SINT`
buffer textures make Metal abort the process outright, so it had to be re-expressed as a 2D `R8Sint`
texture with `spvTexelBufferCoord`.

The other two came from a different method: **census the pipeline space, then compare each mapping
against the engine's own Vulkan backend.** All 87 pipelines were tabulated by topology, blend
function, colour format, depth state and vertex stride, which showed that no vanilla pipeline uses
more than one colour target (so the single-attachment assumption is safe for vanilla) and that
`LINES` and `TRIANGLE_FAN` were mapped to Metal primitives that merely look close - `LINES` is quad
geometry and `TRIANGLE_FAN` has no Metal equivalent at all. Reading the code would not have found
either; `VulkanConst.toVk` and `PrimitiveTopology.indexCount` said so outright.

The same audit found two more, in the same shape as BUG-008 and BUG-011 - a value stored but never
applied, or applied but never reset. Depth bias and the depth-stencil state are both **encoder** state
in Metal, not pipeline state, and the backend set each one only when it was non-default. So the last
biased pipeline left its bias on every later draw in the pass (five vanilla pipelines bias), and a
pipeline declaring no depth state inherited the previous one's compare function and depth write
(thirty vanilla pipelines declare none). Neither throws; both are the kind of full-scene depth error
that reads as "not quite right". Both are now set unconditionally on every bind.

The same round found the post-processing chain: a **separate shader space** from the 87 pipelines,
built at runtime by `PostChain` from the `post_effect` JSONs, shaped unlike anything else in the
pipeline space (no colour target, no vertex format, a full-screen triangle from `gl_VertexID`, and a
one-argument precompile that passes a null shader source), and compiled by no tool at all. All nine
of its shader pairs now compile in the inventory, and one pass renders in the render check. See
BUG-019.

The census also drove the opposite conclusion for blending. Vanilla uses ten distinct blend
functions, and the harness exercised exactly one of them, so all eight factors vanilla actually
blends with rested on an SDK-header table and nothing else - the same kind of evidence that let
BUG-006 hide. Every one of the ten is now rendered and compared against the blend equation evaluated
on the CPU, along with the five factors BUG-006 corrected that vanilla never uses but Phase 7's
shaderpacks will. The CPU model rounds its inputs to 8 bits first, so the expectation matches the
attachment exactly rather than within a tolerance that could hide an off-by-one; breaking any single
factor mapping now fails precisely the cases that use it.

**In-game confirmation (first successful run).** The build installed after BUG-020 loaded a world and
held it for minutes. The 30-second telemetry reported every health counter at zero -

```
textures=4460 views=9369 buffers=4139 samplers=33
failures=0 pipelineFailures=0 unboundBindings=0 missingVertexAttributes=0
slotCollisions=0 bindingKindMismatches=0 indexedFans=0
shader pairs: compiled=56 reused=44 cached=56
```

- and a screenshot shows terrain with textures and lighting, a graded sky with fancy clouds, water,
  the HUD, hotbar items, legible debug text and the debug coordinate axes, at 62.9 fps at 5120x2664
  with RGSS filtering on. That is the first evidence that the Phase 5 work holds up outside the
  harness. It also immediately produced two things the harness could not: BUG-020 (the world-load
  abort) and BUG-021 (F3 reporting the upscaler as "Pipeline", so a working session displayed
  "Pipeline: inactive").

Verification moved from "it compiles" to "it renders the right pixel", which is what caught that
three of those fixes were incomplete. Five offline gates now cover the phase:

| Suite | Reports |
|---|---|
| `scripts/build_mod.sh` | compiles the mod and every non-JUnit test |
| `scripts/run_smoke.sh` | native device/pipeline/draw/surface, 11 sections |
| `tools/shader_inventory/run.sh` | `static 87/87` and `post 9/9`, no diagnostics from any pipeline |
| `tools/render_check/run.sh` | 74 assertions over 24 mechanisms, real vanilla pipelines |
| `net.metalmod.StandaloneTestRunner` | format tables, multi-draw, sub-buffer offsets |

What remains for Phase 5 is the part that needs a running game: BUG-001 (missing GUI sprites),
BUG-002 (the block-selection outline) and BUG-003 (the flat black world) are all runtime observations
from a build that predates most of these fixes, and every mechanism they implicate now renders
correctly offline. The next step is an in-game run on a current build, not more reading.

### Phase 6 — Dynamic lighting  · **M–L**

Vanilla lighting is baked: one block-light and one sky-light value per block, updated on the CPU.
That gives a shader or a path tracer nothing to work with except a lightmap texture, and nothing that
moves. Both shaderpacks (Phase 7) and ray tracing (Phase 9) need a real light model, so it is its
own phase rather than a detail of either.

- **A light-source model.** Point/spot/area lights with colour and intensity, emitted by blocks,
  entities, held items and the sky, collected per frame into a GPU-readable light buffer.
- **Movable ("dynamic") lights.** The OptiFine/Iris dynamic-lights behaviour: a held torch, a
  dropped torch or a glowing entity lights nearby surfaces without a block update. On a native
  backend this can be a renderer-side additive light pass instead of CPU light propagation.
- **Scalable evaluation.** Clustered-forward or deferred lighting so many lights stay affordable,
  with light culling and per-cluster lists.
- **Expose it downstream.** Publish lights, intensities and shadow-casting flags to shaderpacks as
  uniforms, and keep the data in a form a BLAS/TLAS path tracer can sample in Phase 9.

**Done when:** emissive and movable sources affect the scene consistently, and a shaderpack can read
the light set instead of reconstructing lighting from the vanilla lightmap alone.

**Dependencies:** needs Phase 3 (draw calls) plus the lightmap and post-processing passes from
Phase 5. It is a prerequisite for good shaderpack lighting (Phase 7) and for any ray-traced lighting
(Phase 9).

**Risks:** many lights is a performance problem before it is a correctness problem — keep the light
buffer bounded and cull aggressively. Vanilla's own lighting must keep working unchanged, and the
feature stays user-toggleable so a pack that brings its own lighting is not double-lit.

### Phase 7 — Shaderpacks  · **L**

- A shaderpack-aware `ShaderSource` (packs ship GLSL, so the Phase 4 path applies).
- Injecting pack-declared passes (shadow, deferred, composite) into the frame graph.
- Extending beyond vertex/fragment for packs that use compute — likely a backend-specific extension to `RenderPipeline`.
- Pack-provided uniforms, samplers, custom textures, and buffer formats.

**Done when:** a representative set of popular packs loads without errors and produces correct
output. Feature coverage, not a single pack, is the milestone.

### Phase 8 — MetalFX, natively  · **S–M**

Now straightforward, because MetalMod owns the device and the swapchain:

- Temporal/spatial upscaling over our own textures — no `VK_EXT_metal_objects`, no format
  reconciliation, no presentation fight.
- Temporal upscaling needs motion vectors, which vanilla does not produce: either the render graph
  exposes them or we add a depth-reprojection pass.
- Frame generation via `CAMetalDisplayLink` pacing, presenting the interpolated frame on its own
  refresh. This costs about one refresh of input latency (~8 ms at 120 Hz) — a deliberate trade, not
  a free win.

### Phase 9 — Ray tracing  · **XL (research)**

- Build BLAS/TLAS from chunk meshes; rebuild strategy for chunk edits.
- Hybrid raster + RT: shadows, reflections, ambient occlusion first; full path tracing later.
- Denoising and temporal accumulation on top of Phase 8's machinery.

Apple silicon M3 and later have hardware ray tracing; this is the objective that most justifies a
native Metal backend, since MoltenVK cannot express it at all.

---

## 6. Honest risk assessment

- **Scale.** This is a multi-month project. The Vulkan backend — written by people with full access
  to the engine and paid to do it — is 69 classes. Treat Phase 5 as the real cost.
- **Performance is not guaranteed.** MoltenVK is mature and well-tuned. A new backend may be
  *slower* than the Vulkan path for a long time. The justification for MetalMod is ray tracing,
  MetalFX, and control over presentation — **not** an assumed frame-rate win. If raw FPS on the
  current Vulkan path is the only goal, this project is the wrong tool.
- **Upstream drift.** Mojang maintains its own backends. When the abstraction changes, ours breaks
  and theirs does not. Budget ongoing maintenance.
- **Feature gaps.** Apple GPUs differ from the Vulkan feature set MC targets; some assumptions will
  need `DeviceFeatures` negotiation rather than hardcoding.
- **Ecosystem.** Sodium and Iris must work, or the mod is not usable in practice.

---

## 7. Immediate next step

**Phase 5 — vanilla render parity, in progress.** Phases 0–4 are done, and Phase 4's exit criterion
is met: all 87 vanilla pipelines compile, verified by `tools/shader_inventory/run.sh`. Phase 5's
twenty-one fixes (BUG-004 … BUG-021) are in and all four offline suites are green — see the
progress note under Phase 5 above.

The next step is **an in-game run on a current build**, not more static analysis. BUG-001, BUG-002
and BUG-003 are runtime observations from a build predating most of these fixes, and the leading
hypothesis for each is that it is already fixed: the harness now reproduces every mechanism they
implicate — uniform-block slots, atlas samplers, region clears, the screen-space line expansion, the
entity vertex format — and each renders correctly. What the run has to settle is which of them
survive, and it also confirms the twenty-one fixes. The first run earned its keep immediately: it found a crash the
offline suites could not reach (BUG-020) and a misleading status line (BUG-021).

Install the current jar and re-check:

```bash
cp build/libs/metalmod-1.0.0.jar \
   "$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2/mods/"
```

Watch `logs/latest.log` for the 30 s telemetry line, which reports `failures=`, `pipelineFailures=`,
`unboundBindings=`, `missingVertexAttributes=`, `slotCollisions=` and `bindingKindMismatches=` — all
of them should stay at zero.

---

## 8. Verified facts and how to re-verify

Everything in §2 was read from the real client jar, not assumed:

```bash
INST="$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2"
JAR="$INST/MetalMod_Test_26.2.jar"
JAVAP=/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home/bin/javap

# the backend abstraction
$JAVAP -cp "$JAR" -p com.mojang.blaze3d.systems.GpuBackend
$JAVAP -cp "$JAR" -p com.mojang.blaze3d.systems.GpuDeviceBackend
$JAVAP -cp "$JAR" -p com.mojang.blaze3d.systems.RenderPassBackend
$JAVAP -cp "$JAR" -p net.minecraft.client.PreferredGraphicsApi

# the shader pipeline
$JAVAP -cp "$JAR" -p com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule
$JAVAP -cp "$JAR" -p com.mojang.blaze3d.preprocessor.GlslPreprocessor

# the reference backend, for calibration
unzip -l "$JAR" | grep "blaze3d/vulkan/"
```

Note the environment specifics that matter: this instance runs with **named mappings** (the client
jar contains zero `class_XXXX` entries), on **Vulkan 1.2.334 / MoltenVK 1.4.2**, Apple M4 Pro,
macOS 27, Java 26.
