# Performance Roadmap & Blaze3D Architecture Strategy — 2026-10-17

This document defines the staged engineering plan to close the remaining performance gap on Apple
Silicon and evaluates whether MetalMod should retain Minecraft 26.2's `Blaze3D` (`GpuBackend` /
`GpuDeviceBackend`) abstraction or move toward a pure native Metal renderer without `Blaze3D`.


## Implementation review — 2026-10-17

Stage 0 has no blocking architectural fault and is **complete**: instrumentation, five offline
gates, live F8/Computer checks, and vanilla RD16/RD32 baseline/completion fixtures. See
[coverage and fixture commands](stage0-resource-telemetry.md) and
[live results, artifact provenance and presentation variability](stage0-live-20261017.md).
Keep the hybrid backend direction. Treat later stages as design proposals, with these corrections:

- The “95% complexity / <10% GPU” split and per-pass tile-traffic estimates are hypotheses,
  not measured proportions. Encoder closure does not by itself prove a full attachment round-trip;
  measure native execution and attachment load/store behavior before claiming saved bandwidth.
- A ticket fence must preserve submission/progress semantics even for polling-only consumers.
  Deferring every submission until a blocking wait can leave a zero-timeout polling loop stuck.
- Deferred clears and encoder continuation require mip/slice/view identity, attachment load/store
  actions, visibility/readback/copy barriers and complete logical-pass state reset, beyond Y-flip.
- Native texel views need alignment/format/shader-layout eligibility; cache invalidation must cover
  mapped writes and suballocations. `R8_SINT` remains on the existing safe fallback.
- Black vignette tint alone does not prove a draw is a no-op: blending may still darken the target.
  Gate any skip on the actual shader output and blend equation; do not implement the tint-only rule.
- Variable terrain draw lengths cannot be collapsed into a single instanced draw without a defined
  index/instance mapping. Preserve translucent order and exact-position fallback precision, and
  establish full resident scene ownership separately from raster visibility before RT use.

Stage 0 attribution is an opt-in diagnostic with CPU API time and logical bytes, not GPU timing or
actual memory traffic. Instrumented results must have separate ordinary throughput controls. New
builds carry their own artifact hashes and provenance; `349ef76` is the starting revision, not an
identity claim for modified artifacts. Fresh matched gameplay runs are recorded in the live report; later optimization comparisons still
require fresh matched controls.

---

## Part 1: Should We Keep Blaze3D or Move Forward Totally Without It?

### Verdict: Keep `Blaze3D` as the Engine Substrate, Bypass It Selectively on Hot Paths

Moving forward **totally without Blaze3D** (rewriting all of Minecraft's rendering in custom Metal
code, the pre-26.2 VulkanMod approach) would be a **high-cost architectural trap**. However,
treating `Blaze3D`'s per-draw Java `RenderPass` API as the ceiling for terrain and GPU-driven passes
is **also wrong**.

The right architecture—and what `mcopt` (`0.3.0-alpha.2`) actually does under the hood—is a
**two-tier hybrid**:

1. **Tier 1 — Keep `Blaze3D` (`GpuBackend` / `GpuDeviceBackend`) for General Engine Rendering:**
   - Retain `MetalBackend`, `MetalDevice`, `MetalCommandEncoderBackend`, `MetalRenderPassBackend`,
     and `MetalShaderCompiler` for the engine's 87 vanilla pipelines and 9 post-processing passes
     (GUI, fonts, HUD, item atlases, entities, block entities, particles, weather, sky, clouds,
     outline/OIT framegraphs).
2. **Tier 2 — Bypass `Blaze3D`'s Per-Draw Loop Directly into Native Metal for Hot Paths:**
   - Already done for **Phase 7 MetalFX Spatial/Temporal/Frame Generation** (`metalmod_metalfx.mm`,
     `metalmod_temporal_frame.mm`, `metalmod_frame_generation.mm`), which operate directly on native
     `MTLTexture` and `MTLCommandBuffer` handles.
   - Next required for **Chunk Terrain Rendering** (and **Phase 8/9 Ray Tracing**): bypass
     `RenderPass.drawIndexed` / `drawMultipleIndexed` loops so terrain is driven by a native Metal
     arena, compute visibility culler, and indirect instanced vertex-pulling pipeline in
     `libmetalmod.dylib`.

### Why Abandoning Blaze3D Entirely Is a Trap

1. **Non-Terrain Passes Carry Much of the Compatibility Surface; Their Workload Share Needs Measurement:**
   - In Minecraft 26.2, Mojang already refactored the entire client around `GpuBackend`. Supporting
     all of vanilla rendering through `Blaze3D` takes ~134 interface methods across 12 backend
     classes.
   - Throwing away `Blaze3D` means manually reimplementing and maintaining custom Metal renderers
     for every entity type, block entity, font/text layout, GUI screen, item model, particle emitter,
     and post-processing effect. That costs months of engineering and breaks every Fabric UI/entity
     mod, for negligible FPS gain because those passes are not what bottlenecks dense scenes.
2. **Even `mcopt` Does Not Abandon Mojang's Backend Abstraction:**
   - In `mcopt`, `MetalBackend` still implements Mojang's `GpuBackend` (renamed from `blaze3d` to
     `renderpearl` in 26.3) and translates vanilla shaders via SPIRV-Cross for entities, GUI, sky,
     particles, and post-processing.
   - What `mcopt` replaces is specifically **`ChunkSectionsToRender.renderGroup` in `LevelRenderer`**
     ([OwnLevelRendererMixin.java](https://github.com/noahdunnagan/mcopt/blob/7b1d0fb49b4aa03f0a8ebd31a42f93c5fa1cbc6c/metal/src/main/java/mcopt/metal/mixin/own/OwnLevelRendererMixin.java))
     and **`SectionCompiler.compile`** (`OwnMesher`), redirecting terrain into `OwnTerrain` (`mcown.m`
     + `terrain.metal`).
3. **Blaze3D Resource Ownership Already Gives Us Native Metal Handles:**
   - Because `MetalDevice` *is* the `Blaze3D` backend, every `GpuTexture` and `GpuBuffer` created by
     Minecraft is already a native `MTLTexture` or `MTLBuffer` owned by `libmetalmod.dylib`. We do
     not need to remove `Blaze3D` to write pure `.metal` compute/render kernels or build
     `MTLAccelerationStructure`s—we already have direct C++ access to every underlying Metal object.

### Where Blaze3D *Does* Hurt Performance (and How We Fix It)

`Blaze3D` introduces three specific bottlenecks that our performance plan directly eliminates:

| Blaze3D Bottleneck | Why It Hurts on Apple Silicon | How We Solve It Without Dropping Blaze3D |
|---|---|---|
| **1. Per-section / per-batch Java draw calls** (`RenderPass.drawIndexed`) | In our RD32 cave fixture, even with Sodium, [MetalDrawBatch.java](../../compat/sodium/src/main/java/net/metalmod/sodium/MetalDrawBatch.java#L21) issues **~16,814 separate indexed draws** and **~17,498 FFI calls** per frame, leaving vertex/tiler stages processing uncompacted per-region geometry. | **Bypass `RenderPass` draw loops for terrain:** pass the native `MTLRenderCommandEncoder` to a native terrain dispatcher that uses GPU compute visibility lists + indirect instanced vertex pulling (`MTLBuffer` arena). |
| **2. Granular `RenderPass` open/close and standalone `clear*Texture` calls** | Minecraft opens ~20 `RenderPass`es and 5 standalone clears per frame. Closing the native `MTLRenderCommandEncoder` at logical boundaries can incur attachment stores/reloads. A hypothetical full RGBA8 store+reload is ~109 MB at 5120×2664; actual traffic needs native measurement. | **Decouple logical `Blaze3D` passes from native `MTLRenderCommandEncoder` lifetime:** queue full clears as pending `MTLLoadActionClear` state and keep the native encoder open across consecutive compatible passes. |
| **3. Heavyweight `GpuFence` & string-keyed uniform/texel bindings** | `Blaze3D` creates ~10.34 `GpuFence` objects per frame (each currently allocating an `MTLSharedEvent` and flushing the command batch) and binds texel buffers that trigger 2D texture uploads. | **Replace per-fence events with submission ticket indices**, and add a **format-aware native texel-buffer fast path** (`MTLTexture` buffer views for supported formats). |

---

## Part 2: Phased Performance Improvement Plan

### Stage 0 — Resource-Labelled Baseline & Telemetry Instrumentation (Effort: S)

Before changing rendering paths, add lightweight diagnostic attribution so every subsequent stage is
measured against exact counters on `349ef76`:

1. **Labelled Upload & Clear Telemetry:** Record bytes and API wall time by resource/format label
   (distinguishing cloud `R8_SINT` texel emulation, lightmap updates, sprite atlas animations, and
   chunk staging uploads) and log whether each clear is full-attachment vs. scissored/partial.
2. **Matched Benchmark Fixtures:** Establish two canonical Vsync-Off, Unlimited, Native-Only
   (FG/SR/Temporal Off) benchmarks alongside completed-offscreen GPU controls:
   - **Fixture A (Surface Spin/Flight, RD16):** Closer to public `mcopt` workload profiles.
   - **Fixture B (Dense Underground Waypoint 13, RD32, 5120×2664):** Existing heavy vertex/draw stress test.

---

### Stage 1 — Low-Risk Backend & Lifecycle Wins Underneath Blaze3D (Effort: S–M)

**Complete — 2026-10-17:** 1A–1D are implemented and qualified through all five offline gates,
copied-world temporal/FG lifecycle and image checks, compile-region enabled/disabled controls,
Sodium integration, and fresh-JVM performance qualification. Ticket fences, exact-content 2D
texel caching, lighting staging reuse, fenced transient blocks, compile-task region release and
the verified native-only vignette skip are enabled by default. The final rollout decision retains
deferred clears, encoder continuation and native texel-buffer layouts as explicit opt-ins: the
accepted dense gain is modest, and the surface comparison fails its workload-match gate.
See [implementation, qualification and defaults](stage1-backend-lifecycle.md).

These improvements optimize our `Blaze3D` implementation in `native/src/metalmod_metal.mm` and
`src/main/java/net/metalmod/backend/` without changing terrain geometry or mod compatibility:

#### 1A. Submission-Ticket Fences (`MetalFence`)
- **Problem:** [mmm_fence_create](../../native/src/metalmod_metal.mm#L578) currently allocates a new
  `MTLSharedEvent`, flushes pending command batches, and commits a dedicated signal command buffer
  (~10.34 times/frame).
- **Change:** Maintain a single shared monotonically increasing submission ticket counter (or one
  shared `MTLSharedEvent` timeline on the device queue). A fence records the ticket of the latest
  pending/submitted command buffer. Define submission progress independently of blocking waits:
  polling-only consumers must eventually observe completion, and fences must retain the engine's
  submission semantics. A blocking wait on an uncommitted ticket must flush it. Adjacent fences
  with no intervening GPU work may share a ticket.
- **Validation:** Non-blocking polls (`timeout = 0`), finite/infinite waits, immediate close, staging
  and Phase 6 light-ring retirement, and all 5 offline gates.

#### 1B. Deferred Full-Attachment Clears & Compatible Render Encoder Continuation
- **Problem:** [mmm_clear_color_texture / depth](../../native/src/metalmod_metal.mm#L1253) starts and
  commits an immediate clear pass, and [mmm_render_pass_end](../../native/src/metalmod_metal.mm#L993)
  ends the `MTLRenderCommandEncoder` even when the next `Blaze3D` pass targets the exact same color
  and depth attachments.
- **Change:**
  1. Record full-texture clears as pending clear values on the native texture wrapper. Materialize
     them as `MTLLoadActionClear` when the next render pass opens on that attachment (or flush
     immediately if the texture is sampled, copied, read back, or presented before a render pass).
  2. Keep the native `MTLRenderCommandEncoder` open across consecutive `Blaze3D` `RenderPass`es that
     bind identical color/depth targets and compatible Y-flip orientation, resetting scissor/depth
     bias/stencil state at logical pass boundaries.
- **Validation:** Verify pixel-identical output across all core + Sodium `RenderCheck` assertions (393 at closure),
  Y-flipped UI/atlas passes (`BUG-001` / `BUG-025`), and temporal/FG capture hooks. Measure render
  passes/frame and GPU fragment/tile time.

#### 1C. Format-Aware Native Texel Buffers & Offset-Safe Fallback Cache
- **Problem:** [MetalDevice.texelTexture](../../src/main/java/net/metalmod/backend/MetalDevice.java#L1030)
  re-uploads every texel-buffer uniform into a 4096-wide 2D texture on every bind.
- **Change:**
  1. Fix the 2D fallback cache key to include storage identity, absolute `slice.offset()` and range, tracking content generations so unchanged
     texel buffers (such as static cloud data between ticks) are not re-uploaded every frame. Exact
     byte comparison is required when retained mapped views prevent reliable write-generation tracking.
  2. For formats proven safe on Apple Silicon Metal (excluding `R8_SINT`, which triggered `BUG-013`),
     support native buffer-backed `MTLTexture` views (`newTextureWithDescriptor:offset:bytesPerRow:`)
     paired with `SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE` on eligible shaders.
  3. Preserve the Phase 6 `RGBA32F` 2D light-data texture contract (`MetalModLightData`) unchanged.

#### 1D. CPU Memory & No-Op Pass Hygiene
- **Lighting Staging Reuse:** Replace per-frame `float[]` and per-light direct `ByteBuffer`
  allocations in [MetalDevice.publishLightFrame](../../src/main/java/net/metalmod/backend/MetalDevice.java#L252)
  and [PointLight.java](../../src/main/java/net/metalmod/lighting/PointLight.java#L41) with reusable
  ring-backed staging buffers (`whole-mod-improvements-20261016.md` P2).
- **Transient Arena Lifetime Guard:** Guard [MetalTransientMemory.java](../../src/main/java/net/metalmod/backend/MetalTransientMemory.java#L45)
  against ring wrap-around overwriting in-flight GPU data when frame throughput increases.
- **Compile-Task Region Release & Black Vignette Skip:** Null out `RenderSectionRegion` after chunk
  compile completes (historical source estimate ~78 MB; validate here). A vignette skip needs proof
  that shader output and blending leave the target unchanged; pure black tint alone is insufficient.

---

### Stage 2 — Selective Vsync-Off Presentation & Frame Completion Pacing (Effort: M)

- **Problem:** Our offscreen completion benchmark proved that our M4 Pro completes **138.30 FPS**
  offscreen in the dense 5K RD32 cave (with empty-light specialization), yet presented FPS remains
  pinned near **59–60 FPS** (or ~94 FPS in fresh Vsync-Off sessions) with ~12.5–13.3 ms spent in
  `nextDrawable` acquisition. Simply deferring `nextDrawable` to the end of the frame
  (`lateDrawableAcquire`) still presents every rendered frame and gained only `+0.60%`.
- **Change:** Implement opt-in **Selective Presentation (`-Dmetalmod.selectivePresent=true`)** for
  Vsync-Off (`IMMEDIATE`) mode:
  1. Render the scene to MetalMod's offscreen color target as usual.
  2. Bound in-flight GPU work using Stage 1A's completion tickets (at most 2 rendered frames in flight
     on the GPU) so the CPU never queues unbounded work.
  3. Query the hosting window's display refresh cadence (`NSScreen` / `CVDisplayLink` or adaptive
     scanout latch timing—**never** hardcoding `CGMainDisplayID()`). Only acquire a `CAMetalDrawable`
     and encode the presentation blit when the frame will latch the next display refresh.
  4. On skipped presents, still advance and retire upload staging, Phase 6 dynamic-light rings, and
     Phase 7 temporal history buffers safely.
- **Validation:** Report **Completed GPU FPS**, **Presented Drawable Events/s**, **p95/p99 frame
  intervals**, and **frame-age latency proxy** separately. Verify zero interference with the Phase 7C
  Frame Generation presenter.

---

### Stage 3 — GPU-Driven Terrain Pipeline & Compact Shaders (Effort: L — Highest GPU Leverage)

This is the structural centerpiece that addresses the remaining vertex/tiler and draw-call gap.
We should implement it in two steps so it benefits both **Sodium users** and **standalone Vanilla
MetalMod users**, while feeding **Phase 8/9 Ray Tracing**:

#### Step 3A: Native Multi-Draw & Indirect Vertex Pulling for the Sodium Adapter
- **Current bottleneck:** [MetalDrawBatch.java](../../compat/sodium/src/main/java/net/metalmod/sodium/MetalDrawBatch.java#L21)
  loops `for (int i = 0; i < size; ++i) pass.drawIndexed(...)` in Java, and `MetalDrawContext` copies
  32-byte region uniforms per region (`~16,800` draws/frame).
- **Change:**
  1. Replace the Java `for` loop in `MetalDrawBatch` with a single native batch call
     (`mmm_sodium_draw_batch`) that packs `[counts, bases, first, regionData]` into a shared buffer.
  2. Convert the native Sodium terrain shader into a **vertex-pulling `.metal` / MSL pipeline** that
     reads Sodium's compact 16-byte vertices and per-region transforms from `MTLBuffer`s indexed by
     `instance_id` / `vertex_id`, collapsing hundreds of per-section draws per region into **one
     instanced/indirect draw per terrain layer** (solid, cutout, translucent).
  3. Narrow terrain vertex-to-fragment varyings (`half4` color/fog, and condition-specific lean
     fog/fade variants for fully faded-in interior chunks) while keeping full `float3` world/camera-relative
     position precision for Phase 6 Dynamic Lighting and Phase 7 Temporal motion vectors.

#### Step 3B: Standalone Native Terrain Arena & Compute Visibility Lists (No-Sodium Path)
- Build MetalMod's own optional native terrain layer (`net.metalmod.terrain.*` + `metalmod_terrain.mm`)
  directly on vanilla 26.2 chunk sections so MetalMod achieves top-tier terrain performance **without
  requiring Sodium**:
  1. **Compact 40-Byte Quad Records + Exact-Position Fallback:** Encode opaque/cutout quads into
     40-byte GPU records (with lossless fallback to 64-byte compact quads for non-axis-aligned or
     complex-tinted quads) stored in a sub-allocated shared `MTLBuffer` arena.
  2. **GPU Compute Section/Unit Culling:** Dispatch a lightweight Metal compute pass (`8 lanes/section`)
     that frustum- and face-culls 32-quad units and writes survivor indices into size-classed
     `MTLDrawPrimitivesIndirectArguments` buffers.
  3. **Hardware-Gated Hi-Z Occlusion (Optional):** Only enable two-phase depth-pyramid occlusion
     where resolution and GPU core count make it a net win (respecting the finding that >6 MP on
     12+ core GPUs is faster without unit occlusion).
  4. **Unified Scene Ownership for Phase 8/9 Ray Tracing:** Because the native terrain arena owns all
     resident chunk geometry in shared UMA memory, Phase 8C/9A can build `MTLAccelerationStructure`
     bounding boxes/triangles directly from the complete resident arena (not the raster-culled
     survivor list) with zero duplicate CPU-to-GPU uploads.
  5. **Built-In `VERIFY` Mode:** Include `-Dmetalmod.terrain.verify=true` to compare decoded GPU
     quads and rendered pixels against the reference vanilla path automatically.

---

## Summary Priority Matrix

| Stage | Target | Primary Bottleneck Addressed | Effort | Risk |
|---|---|---|---|---|
| **Stage 0** | Resource-labelled upload/clear/pass telemetry & RD16+RD32 baselines | Attribution blindness | **S** | Very Low |
| **Stage 1A** | Submission-ticket fences (`MetalFence`) | ~10.34 `MTLSharedEvent` allocs & batch flushes/frame | **S** | Low |
| **Stage 1B** | Deferred full clears & compatible `MTLRenderCommandEncoder` continuation | ~20 pass closes + 5 standalone clears/frame (tile store/load bandwidth) | **M** | Medium (must guard Y-flip & barriers) |
| **Stage 1C** | Format-aware native texel buffers & offset-safe 2D cache | Per-bind 2D texture uploads (`MetalDevice.texelTexture`) | **S–M** | Low (keep `R8_SINT` on cached 2D fallback) |
| **Stage 1D** | Lighting ring staging reuse, transient wrap guard, `compileRegionDrop` | Per-frame direct buffer allocations & ~78 MB idle chunk-compile heap | **S** | Very Low |
| **Stage 2** | Opt-in selective Vsync-Off presentation with 2-frame completion bound | ~12.5–13.3 ms `nextDrawable` presentation stall when Vsync is Off | **M** | Medium (must retire rings on skipped presents) |
| **Stage 3A** | Native batch & instanced vertex pulling for Sodium adapter + `half4`/lean shaders | ~16,800 Java/FFI draw calls & vertex/parameter bandwidth | **M–L** | Medium |
| **Stage 3B** | Standalone native terrain arena, 40-byte quad records & GPU compute culling (RT-ready) | Full independence from Sodium + maximum GPU terrain throughput + Phase 9 RT scene | **L** | Medium–High (staged behind `VERIFY` gates) |
