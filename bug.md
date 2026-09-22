# Known bugs

Open defects found while bringing up the native Metal backend. Newest first. This is a parking
lot, not a work queue — items move into `ROADMAP.md` when they become scheduled work.

Each entry records: where it was seen, what it looks like, how to reproduce it, and the current
best guess at the cause. Add a screenshot under `docs/bugs/` when one exists.

---

## BUG-001 — "Select World" list entry has no background panel (and a garbled name line)

**Status:** open, unfixed. Cosmetic — the screen still works.
**Severity:** low. The world is selectable and the screen is usable; it just looks wrong.
**Seen on:** Metal backend enabled, build `ab30f94` (phase 3) + `15c0133`, window
`5120x2880 -> 5120x2880 (Native)`, 60 fps cap, no resource packs.
**Screenshot:** [`docs/bugs/select-world-2026-09-22.png`](bugs/select-world-2026-09-22.png)

### Symptoms

On the Singleplayer → **Select World** ("选择世界") screen:

- The world-list entry's **background panel is missing**. The world thumbnail and the two text
  lines (world name + last-played date, then game mode / cheats / version) float directly on the
  blurred panorama; there is no dark rounded entry behind them.
- A **compressed / garbled line of the world name** appears just above the normal name line.
  It reads like the same CJK glyphs squeezed to roughly half width and clipped.
- Everything else on the screen is correct: the title and its background, the world thumbnail,
  the normal name/date line, the game-mode line, and all six bottom buttons.
- The blurred menu background also looks unusually coarse/blocky (may or may not be the same bug).

### Reproduce

1. Launch with the Metal backend enabled (`-Dmetalmod.metalBackend=true` or
   `preferMetalBackend=true`) into an instance with at least one saved world.
2. Open **Singleplayer → Select World**.
3. Compare against the same screen on the default backend — there the entry has its dark panel.

### Suspected cause (unconfirmed)

The entry background is a sprite from the GUI atlas, and the garble looks like a text/quad drawn
with a wrong sub-rect or transform, so this is probably a leftover of the Phase 3 atlas work
(`ab30f94`, which fixed the atlas Y-flip and the negative-viewport winding):

- the entry background may use a sprite/pipeline variant that is not bound (a name we do not map,
  or a pipeline the engine never precompiles), or
- the world-list widget may rely on a clip/scissor or per-entry transform we do not reproduce, or
- the garbled line may be a second text draw with a stale `ModelViewMat`/`TextureMat`.

### Workaround

None needed. Use the default (Vulkan/OpenGL) backend if the list is hard to read; select worlds
by their position, which still works.

---

## BUG-002 — Block selection outline is drawn as a huge wireframe box

**Status:** open, unfixed.
**Severity:** low / cosmetic. Nothing breaks; it just looks wrong and is distracting.
**Seen on:** Metal backend enabled, in-world, build `ab30f94`+, `5120x2880` native, 111 fps.
**Screenshot:** [`docs/bugs/inworld-2026-09-22.png`](bugs/inworld-2026-09-22.png)

### Symptoms

The block the player is looking at is outlined by a **giant white/light wireframe quad** covering
a large part of the screen, instead of a thin black line hugging one block. It looks like two nested
wireframe rectangles with a small green/red axis marker near one corner. Depth testing does not hide
it, so it is drawn on top of everything, including the sky.

### Reproduce

1. Metal backend enabled, load a world, look at a nearby block with the crosshair.
2. Compare with the default backend, where the outline is a thin black box around the block.

### Suspected cause (unconfirmed)

The outline goes through a line-rendering pipeline (`lines` / `debug_line` with a wireframe fill or
a line primitive). Likely candidates: the wrong primitive topology for the outline pipeline, a
mis-scaled `ModelViewMat`/`DynamicTransforms` bound to the outline draw, or a `fillMode`/
`lines` mapping bug in `MetalFormat`. Part of the Phase 5 parity work.

### Ruled out (Phase 5)

Checked directly, so these are *not* the cause:

- **Primitive topology.** `MetalFormat.mtlTopology` maps `LINES`/`DEBUG_LINES` to
  `MTLPrimitiveTypeLine` correctly, and MC supplies indices for `QUADS`.
- **Front-face winding.** `mmm_render_pass_begin` sets `MTLWindingCounterClockwise` and
  `mmm_render_pass_set_pipeline` sets cull/fill; the negative-height (flipped) viewport that reverses
  winding is applied only to `/atlas/` passes, each with its own encoder.
- **Vertex descriptor.** A new diagnostic reports any `VertexFormat` element with no matching shader
  input. Compiling all 87 vanilla pipelines reports **none**, so `LineWidth` and friends are all
  mapped and the shader is not reading undefined attribute data.
- **Missing bindings.** The unbound-binding diagnostic named only `lightmapInfo` and `CloudFaces`
  (BUG-005), neither of which is on this path.

That leaves the *values* rather than the plumbing — most plausibly `LineWidth` vertex data or the
`ScreenSize` uniform, since the shader's thickness is `LineWidth / ScreenSize`. This needs a runtime
inspection of those two on the outline draw, not more static reading.

### Workaround

Turn the selection outline off in Options (if the pack allows) or ignore it; it does not affect play.

---

## BUG-005 — Two pipelines draw with bindings that were never set

**Status:** **FIXED** (Phase 5) — pending in-game confirmation.
**Severity:** high for world appearance — one of these is the lightmap.
**Found by:** the Phase 4 unbound-binding diagnostic, in-game (run `[10:21:31]`, see below).

### Fix

Uniform blocks are now reflected by their block **type** name, not the GLSL instance name. The engine
binds by type name: `BindGroupLayouts` declares `LightmapInfo`, and `Lightmap` is the class that
binds it. `lightmap.fsh` is the one vanilla shader that names its instance —

```glsl
layout(std140) uniform LightmapInfo { ... } lightmapInfo;
```

— while every other vanilla block omits the instance name, so the two conventions happened to agree
and only the lightmap broke. Verified offline: the fragment shader now reflects as `[LightmapInfo]`.


### Symptoms

The diagnostic named these during a normal in-world session:

```
[MetalMod] unbound uniform buffer 'lightmapInfo' in minecraft:pipeline/lightmap
[MetalMod] unbound texture 'CloudFaces' in minecraft:pipeline/clouds
[MetalMod] unbound sampler 'CloudFaces' in minecraft:pipeline/clouds
```

**`lightmapInfo` is the significant one.** The `lightmap` pipeline renders the lightmap texture that
shades all terrain and entities; if its uniform block is never bound, the lightmap is wrong and the
world is lit incorrectly or not at all. This is very likely a direct, named cause of the flat/unlit
world in BUG-003 — a much more specific lead than "shader variants are not bound".

`CloudFaces` unbound means the cloud pass samples an unbound texture.

### Reproduce

1. Metal backend enabled, enter a world.
2. Watch stderr for `[MetalMod] unbound …` lines (they also appear in `logs/latest.log`).
3. The count is reported in the 30 s telemetry summary as `unboundBindings=`.

### Suspected cause (unconfirmed)

Either the engine does not call `bindTexture`/`setUniform` for these names on this path, or the
reflected GLSL name does not match the name the engine binds under (the lightmap uniform is a
`std140` block, where SPIRV-Cross reports an empty *variable* name and the block *type* name is used
as a fallback — a likely place for a mismatch). Needs a breakpoint/log of the names the engine
actually passes to `RenderPass.setUniform`/`bindTexture` for those two pipelines.

### Workaround

None. Fixed in Phase 5.

---

## BUG-004 — Multi-draw chunk passes never upload their per-draw uniforms

**Status:** **FIXED** (Phase 5) — pending in-game confirmation.
**Severity:** high for world rendering; was a direct cause of missing/wrong terrain.
**Found by:** the Phase 4 binding audit, then confirmed by reading the shaders.

### Fix

`drawMultipleIndexed` now invokes each draw's `uniformUploaderConsumer` before encoding, uses the
per-draw index buffer/type when present, and re-enables the binding diagnostic on that path. Coverage:
`MetalRenderPassBackendTest` (7 assertions).

The payload turned out to be exactly what terrain needed. `chunksection.glsl` declares

```glsl
layout(std140) uniform ChunkSection { mat4 ModelViewMat; float ChunkVisibility;
                                      ivec2 TextureSize; ivec3 ChunkPosition; };
```

and `ChunkSectionsToRender` passes `GpuBufferSlice[] chunkSectionInfos` as the payload. With the
consumer never invoked, `ChunkSection` was never bound, so terrain had no chunk position and no
model-view matrix at all.

`pushConstant` is opaque to the backend — `RenderPass` passes it straight through and
`VulkanRenderPass` only forwards it to the same consumer — so no push-constant reflection was needed
for vanilla. It is still needed for Sodium, which uses `layout(push_constant)` under `VULKAN`.


### Symptoms

Every draw issued through `RenderPassBackend.drawMultipleIndexed` renders with stale or absent
uniforms. A shader that reads a per-draw uniform (a section transform, for instance) samples
whatever was bound before, or nothing at all.

### Evidence

`MetalRenderPassBackend.drawMultipleIndexed` ignores both `pushConstant` and each draw's
`uniformUploaderConsumer()`, and also ignores the per-draw `indexBuffer()`/`indexType()` in favour of
the pass-level arguments:

```java
for (RenderPass.Draw<T> draw : draws) {
    if (draw.vertexBuffer() != null) setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
    applyBindings(false);          // no uploader call
    ...draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
}
```

`VulkanRenderPass.drawMultipleIndexed` does the opposite: for each draw it first calls
`draw.uniformUploaderConsumer().accept(pushConstant, uploader)`, where `uploader` forwards to
`setUniform(name, slice)`, and it prefers `draw.indexBuffer()` / `draw.indexType()` when they are
non-null. `net.minecraft.client.renderer.chunk.ChunkSectionsToRender` and `WorldBorderRenderer` are
vanilla callers, so this is the terrain path, not a corner case.

### Fix shape

Mirror the Vulkan contract: construct a `RenderPass.UniformUploader` that forwards to
`setUniform(String, GpuBufferSlice)`, invoke the consumer before each draw when present, and use the
per-draw index buffer/type when they are set. The binding diagnostic can then be re-enabled on this
path (`applyBindings(true)`), because uploader-supplied uniforms land in the same map.

### Why it was not fixed in Phase 4

It is a draw-path behaviour change on the terrain path, which is Phase 5 work, and it could not be
runtime-verified from the development environment (no game run). Phase 4 shipped only
behaviour-preserving diagnostics plus the shader fixes; this is left to Phase 5 where it can be
validated against a real world.

### Workaround

None. Use the default (Vulkan/OpenGL) backend for normal play.

---

## BUG-003 — Entities (squids/fish) and terrain render as flat black silhouettes

> **Not a mystery artifact:** the small black shapes in the sky are **squids and fish** — real
> entities that are being drawn, but without their textures/lighting. They only look unrecognisable
> because entity rendering is unfinished. Filed so the missing entity/world shading is tracked.

**Status:** open, unfixed. **Scheduled for Phase 5 (vanilla render parity)** — not Phase 4.
**Severity:** low / cosmetic, but it is the most visible sign that Phase 5 shading work is unfinished.
**Seen on:** Metal backend enabled, in-world, build `ab30f94`+, `5120x2880` native.
**Screenshot:** [`docs/bugs/inworld-2026-09-22.png`](bugs/inworld-2026-09-22.png)

### Symptoms

Squids and fish (and other entities) render as **solid black silhouettes**, and terrain renders the
same way — geometry is there, but flat black with no textures or lightmap. GUI, text and the hotbar
render correctly, and the sky colour is right.

### Suspected cause (unconfirmed)

Entity and terrain shaders are not fully bound yet: they are likely sampling an unbound
lightmap/texture or using a shader variant whose inputs are not all mapped. This is Phase 5
(vanilla parity) work, not a Phase 3 regression and not a Phase 4 blocker — Phase 4 only makes
bindings *observably* correct (see `docs/phase4-plan.md` §4.3).

### Workaround

None. Use the default backend for normal play until Phase 5.
