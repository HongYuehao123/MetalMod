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

### Ruled out (Phase 5)

`tools/render_check` now renders a quad that maps each corner of the screen onto one texel of a 2x2
texture with four distinct colours, through the real `gui_textured` pipeline:

```
PASS  texCoord samples top-left     -> R255 G0 B0   (texel 0,0)
PASS  texCoord samples top-right    -> R0 G255 B0   (texel 1,0)
PASS  texCoord samples bottom-left  -> R0 G0 B255   (texel 0,1)
PASS  texCoord samples bottom-right -> R255 G255 B255 (texel 1,1)
```

So `texCoord0` samples the texel the engine meant, in the right orientation. A Y flip anywhere in
the path — the obvious explanation for "the wrong sprite is drawn" — would put those colours in the
wrong quadrants, and it does not. Same for the atlas detection: `TextureAtlas.createTexture` builds
its label from the atlas identifier, which contains `textures/atlas/…`, so the `/atlas/` test that
triggers the compositing flip is correctly keyed.

The post-processing blit is ruled out too. The blur behind the menu is a chain of
`copyTextureToTexture` calls, and the engine asks for sub-rectangles of it — a wrong row stride or a
dropped region would look exactly like the "unusually coarse/blocky" blur reported here. The render
check copies a 4x4 texture with a distinct colour per texel, whole and then as a 2x2 rectangle at
(1,1):

```
PASS  copyTextureToTexture copies the whole texture byte for byte
PASS  a 2x2 copy at (1,1) changes exactly that rectangle
```

so both the stride and the region handling are correct.

The atlas compositing *flip* is ruled out as well. `TextureAtlas` composites with
`ortho2D(0, w, 0, h)`, putting atlas row 0 at NDC y = -1 — a Y-down assumption — so the engine flips
the viewport for targets labelled `/atlas/`. The render check draws a quad covering only NDC y in
[-1, 0] into such a target and reads it back:

```
PASS  atlas target: NDC y=-1 lands in framebuffer row 0 (red at the top, was green if unflipped)
      -> top R255 G0 / bottom R0 G255
```

so the flip does what it claims — and because the pipeline used culls, that also proves the winding
flip a negative viewport requires.

GUI clipping and blending are ruled out too. `RenderPass.enableScissor` is what stops a sprite or a
line of text spilling outside its panel, and a scissor landing in the wrong place — or flipped
vertically, since Metal's scissor origin is top-left like Minecraft's — would corrupt a panel exactly
as described here. The render check draws a full-screen quad with `enableScissor(0, 0, 32, 32)`:

```
PASS  scissor(0,0,32,32) draws the top-left quadrant (B255) and nothing else (opposite quadrant B0)
```

and separately blends 50%-alpha white over black, which lands at exactly `R128` — so blend state
(enabled, factors, op) is plumbed correctly too.

### Most likely already fixed

This was reported against build `ab30f94` (Phase 3), before any of the Phase 5 work. Three of the
fixes since are all plausible causes of exactly these symptoms, and none had been made yet:

- **BUG-007** (sampler address modes were swapped) — every atlas would have sampled with `REPEAT`
  instead of `CLAMP_TO_EDGE`, so sprites bleed into their neighbours. That is precisely "the wrong
  sprite is drawn" and "the panel is missing".
- **BUG-010** (sub-rectangle clears wiped the whole attachment) — every GUI atlas slot already
  rendered was erased.
- **BUG-012** (uniform blocks shared a Metal slot) — GUI uniform values would be wrong.

So the leading hypothesis is that BUG-001 is already fixed by the batch and simply has not been seen
since. What would settle it is a run, not more code: the remaining unexplained piece is text
rendering and the sprite stitching itself, neither of which an offscreen harness can reach without a
full client bootstrap.

### Workaround

None needed. Use the default (Vulkan/OpenGL) backend if the list is hard to read; select worlds
by their position, which still works.

---

## BUG-002 — Block selection outline is drawn as a huge wireframe box

**Status:** root cause fixed (BUG-012); needs an in-game look to confirm.
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

### Root cause identified — BUG-012

`rendertype_lines.vsh` expands each line into a screen-space quad, and the expansion is:

```glsl
vec2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * ScreenSize);
vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * LineWidth / ScreenSize;
```

`ScreenSize` comes from **`Globals`**, which shared a Metal buffer slot with `Fog` before BUG-012. The
divisor was therefore fog data, the offset blew up, and the "line" expanded into a screen-filling
quad — which is exactly what this bug describes, including the "two nested wireframe rectangles"
(the expanded quad's edges) and why depth testing did not hide it.

`LineWidth` is the only other input to that offset and it is per-vertex data from the CPU, so
`ScreenSize` is the only value that could produce the symptom.

**Verified, not assumed.** `tools/render_check` now draws a 2px line through the real `LINES`
pipeline with `ScreenSize = 64` and checks the result:

```
PASS  thin line lights the centre row -> R255
PASS  thin line does not cover a row 8px away -> R0 (a giant quad would)
```

A mis-bound `Globals` would light the far row too. This test would have caught the original bug, and
it confirms the fix at render level rather than by inspection.

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

## BUG-013 — Texel buffers had no binding path (vanilla clouds)

**Status:** **FIXED** (Phase 5) — pending in-game confirmation.
**Severity:** clouds rendered with undefined data; also a Sodium prerequisite.
**Found by:** chasing the `CloudFaces` half of BUG-005.

### Cause

`rendertype_clouds.vsh` declares a **buffer texture**:

```glsl
uniform isamplerBuffer CloudFaces;
... texelFetch(CloudFaces, index).r ...
```

and `BindGroupLayouts` declares it as `UniformType.TEXEL_BUFFER` with `GpuFormat.R8_SINT`. The engine
binds it through **`RenderPass.setUniform("CloudFaces", GpuBuffer)`** — a *buffer*, not a texture
(`CloudRenderer` passes its `utb` ring buffer, "uniform texel buffer").

MetalMod reflected it as a sampled image, so it landed in the texture/sampler maps, while
`setUniform` recorded it under `uniforms` where `applyBindings` looked for it with
`pipeline.vertexBuffer("CloudFaces")` — which is -1, because it is in the texture map. Nothing was
bound and the shader texel-fetched undefined data. That is what the Phase 4 diagnostic reported
in-game (`unbound texture 'CloudFaces'`).

### What the data looks like (measured)

`CloudRenderer` allocates `utb` at **258 bytes** and sets `quadCount = data.position() / 3`, so the
buffer holds **three bytes per quad, one byte per texel** — the `R8_SINT` declaration is right and the
shader sign-extends.

### The obvious fix does not work (measured)

Presenting the buffer as an `MTLTextureTypeTextureBuffer` would need no copy, but Metal **aborts the
process** for `R8_SINT`:

```
METALMOD_PROBE_TEXTURE_BUFFER=1 ./native/build/metalmod_smoke
== texture buffer (which pixel formats can back a texture?) ==
[PASS] texture-buffer backing buffers
Abort trap: 6
```

and the failure is a hard `abort()`, not a catchable `NSException`. The probe is opt-in behind that
environment variable so the normal suite cannot crash.

### Fix

SPIRV-Cross already emits the emulated path and the generated MSL shows it:

```metal
texture2d<int> CloudFaces [[texture(0)]];
int cellX = CloudFaces.read(spvTexelBufferCoord(index)).x;
```

so it wants an ordinary 2D texture of `width = byteCount, height = 1`. `MetalRenderPipeline` now
records every uniform the pipeline declares `TEXEL_BUFFER` together with the format and the slot the
shader reads it at, and `MetalRenderPassBackend.setUniform` presents a texel-buffer slice as a 2D
texture instead of a uniform buffer — cached per backing buffer, with the bytes re-uploaded each use
because the cloud faces are rebuilt every frame.

The unbound-sampler diagnostic skips texel buffers: `read()` takes no sampler, so the MSL has no
sampler argument and the engine correctly never supplies one.

### Verified

`metalmod_smoke` reads a 2D `R8Sint` texture through `texture2d<int>` and gets the stored texel back
(`R42` for the value 42), so the mechanism the fix relies on works. With the fix in place the shader
inventory reports **no diagnostics from any of the 87 pipelines** — no slot collisions, no
reflection/MSL mismatches, no binding-kind mismatches.

---

## BUG-012 — Two uniform blocks shared one Metal slot, so one overwrote the other

**Status:** **FIXED** (Phase 5). Was live for vanilla, in nearly every shader.
**Severity:** critical — the shader read another block's data.
**Found by:** dumping the SPIR-V descriptor bindings instead of assuming they were unique.

### Cause

`MetalShaderCompiler.collect` derived each resource's Metal slot from its SPIR-V
`binding` decoration:

```java
int mslBuffer = stage == 0 ? binding + VERTEX_BUFFER_INDEX_OFFSET : binding;
```

**glslang emits duplicate bindings.** Every shader that imports `fog.glsl` gets its `Fog` block at
binding 0, alongside another block also at binding 0:

```
core/terrain.vsh     Globals binding=0, Fog binding=0   -> both msl_buffer 16
core/entity.vsh      Lighting binding=0, Fog binding=0  -> both msl_buffer 16
core/rendertype_clouds.vsh  DynamicTransforms binding=0, Fog binding=0 -> both 16
```

Two blocks in one Metal buffer index means the second bind overwrites the first, and the shader reads
the wrong uniform data. `Fog` is imported by almost every vanilla shader, so this was not an edge
case. Sampled images happened to get unique bindings, which is why only buffers were affected.

For terrain this is severe: `terrain.vsh` computes

```glsl
vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
```

from `Globals`, which shared slot 16 with `Fog`. Terrain would be positioned using fog data, so the
geometry lands in the wrong place — a direct, mechanical explanation for the broken world in
BUG-003, and one that a "missing binding" diagnosis would never have found because nothing was
missing: both were bound, to the same place.

### Fix

MetalMod binds by name, so the Metal slots only have to be **unique per stage** — they do not have to
match Vulkan's numbering. `collect` now assigns slots from a per-stage counter (`Slots`): uniform
buffers from 16 in the vertex stage (0..15 stay reserved for vertex attributes) and from 0 in the
fragment stage, with independent texture and sampler counters. The descriptor set/binding
decorations are still read, because they identify *which* SPIR-V resource a binding applies to.

Verified for the three worst shaders:

```
terrain  buffers = {ChunkSection=16, Globals=17, Projection=18, Fog=19}
entity   buffers = {Projection=16, DynamicTransforms=17, Lighting=18, Fog=19}
clouds   buffers = {CloudInfo=16, Projection=17, DynamicTransforms=18, Fog=19}
```

and by compiling all 87 vanilla pipelines: **zero collisions** reported.

### The first fix was incomplete, and the MSL proved it

Assigning slots from a counter fixed what `MetalShaderCompiler` *recorded*, but not the underlying
problem. SPIRV-Cross keys `spvc_compiler_msl_add_resource_binding` on `(descriptor set, binding)`, and
with `Globals` and `Fog` both on binding 0 it could not tell them apart — it applied one block's
binding to the other. So the MSL disagreed with the map, which is worse than the original collision
because it looks fixed:

```
terrain   map: {ChunkSection=16, Globals=17, Projection=18, Fog=19}
          MSL:  ChunkSection[16], Projection[18], Globals[19]      <- Globals bound at 17, read at 19
entity    map: {Projection=16, DynamicTransforms=17, Lighting=18, Fog=19}
          MSL:  Projection[16], DynamicTransforms[17], Lighting[19] <- Lighting bound at 18, read at 19
```

The real fix is `normalizeBindings`: rewrite every SPIR-V `Binding` decoration to a unique number
before SPIRV-Cross sees the module, so each resource is identifiable. The numbers are arbitrary,
because MetalMod binds by name. With that, the map and the MSL agree exactly:

```
terrain  buffers = {ChunkSection=16, Globals=17, Projection=18, Fog=19}
         MSL     ChunkSection[16], Globals[17], Projection[18]
entity   buffers = {Lighting=18, Projection=16, DynamicTransforms=17}
         MSL     Projection[16], DynamicTransforms[17], Lighting[18]
```

### Guard

`verifyMslSlots` parses the generated MSL for `[[buffer(N)]]` / `[[texture(N)]]` and checks each
against the reflection map, reporting any disagreement — this is the check that would have caught the
incomplete first fix immediately, and it is silent across all 87 pipelines. `checkUniqueSlots` runs
after each stage is collected and reports any two resources sharing a slot
(`MetalDevice.reportSlotCollision`), so a regression is named in the log instead of silently
corrupting uniforms. It reports nothing for the 87 vanilla pipelines.

---

## BUG-011 — GpuFence was a no-op, so ring-buffer slots were reused while in flight

**Status:** **FIXED** (Phase 5). Was live for vanilla, on every streaming ring buffer.
**Severity:** high — intermittent corruption of streamed data, not a clean failure.
**Found by:** checking what the engine actually does with `GpuFence`.

### Cause

`MetalFence.awaitCompletion` returned `true` immediately:

```java
/** Phase 1 fence: submission is synchronous from the CPU's point of view, so completion is
    immediate. */
public boolean awaitCompletion(long timeoutNanos) { return !this.closed; }
```

The premise is wrong. MetalMod **commits** command buffers; it does not wait for them (only
`copyTextureToBuffer` synchronises, and only for its own readback). So "submission is synchronous"
was never true.

### Impact

Live for vanilla. `MappableRingBuffer.rotate` awaits the slot's fence **with an unbounded timeout**
before recycling it:

```java
GpuFence fence = this.fences[this.current];
if (fence != null) { fence.awaitCompletion(Long.MAX_VALUE); fence.close(); ... }
```

so it is explicitly relying on the fence to block until the GPU is done with that slot. Returning
`true` at once let the CPU overwrite data the GPU was still reading — write-after-read, which shows
up as intermittently corrupted streamed geometry rather than an error. `StagedVertexBuffer$GpuBufferPool`
and `RenderSystem`'s async tasks use the same contract.

### Fix

`MetalFence` now wraps an `MTLSharedEvent`. `mmm_fence_create` creates the event *and* enqueues a
command buffer that signals it — command buffers on one queue run in commit order, so when that
signal fires, everything committed before the fence was created has completed. `awaitCompletion`
maps to `waitUntilSignaledValue:timeoutMS:`, with a non-positive timeout polling once and a very
large one waiting indefinitely, which is what `Long.MAX_VALUE` needs.

Because the engine already waits unbounded, this costs nothing relative to what the engine intended;
it just makes the wait real.

### Verified, not assumed

`metalmod_smoke` issues a GPU clear without waiting, creates a fence, waits on it, and only then
reads the texture back:

```
after fence: R0 G0 B255
```

so the fence genuinely orders against queued GPU work. It also checks that an already-signalled fence
returns immediately and that 64 create/await cycles complete (the engine does this every frame).

---

## BUG-010 — Sub-rectangle clears wiped the whole attachment

**Status:** **FIXED** (Phase 5). Was live for vanilla, on the GUI item atlas.
**Severity:** high for GUIs — every item slot already rendered was erased.
**Found by:** comparing the clear paths against the Vulkan backend's implementation.

### Cause

`MetalCommandEncoderBackend.clearColorAndDepthTextures(..., x, y, width, height)` ignored the
rectangle and cleared the whole attachment:

```java
public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc color,
                                       GpuTexture depthTexture, double depth,
                                       int x, int y, int width, int height) {
    clearColorAndDepthTextures(colorTexture, color, depthTexture, depth);   // rect dropped
}
```

A Metal render pass clears a whole attachment — the load action ignores the scissor, so the
rectangle cannot simply be forwarded. Vulkan has `VkClearRect`, and `VulkanCommandEncoder` builds a
render pass with a `RenderArea(x, y, width, height)` and issues `vkCmdClearAttachments` with that
rect, so the two backends disagreed.

### Impact

Live for vanilla, not latent. `GuiItemAtlas` clears one **slot-sized** rectangle at a time into the
GUI item atlas:

```java
clearColorAndDepthTextures(texture, CLEAR_COLOR, depthTexture, 0.0,
        slotX, textureSize - slotY, slotTextureSize, slotTextureSize);
```

Clearing the whole atlas there erased every slot already rendered, so items drawn into the GUI item
atlas would lose their earlier entries. `PictureInPictureRenderer`, `GameRenderer` and
`LevelRenderer` also use the rectangle form. A contributor to the GUI problems in BUG-001.

### Fix

`mmm_clear_textures_region` clears the rectangle with a **scissored full-screen triangle**: the pass
loads rather than clears, the scissor is the rectangle, and a small MSL pipeline writes the clear
colour — plus the clear depth via `[[depth(any)]]` with an always-pass/always-write depth state, so
the depth rectangle is honoured too. Pipelines are cached per (colour format, depth format).

The no-rectangle variants keep using the load-action clear, which is the fast path and is correct
when the rectangle is the whole attachment.

### Verified, not assumed

`metalmod_smoke` clears a 32x32 colour+depth target to red at depth 1.0, then clears an 8x8 rectangle
at (8,8) to blue at depth 0.25 and reads both back:

```
inside rect  = R0 G0 B255   outside rect = R255 G0 B0
depth inside = 0.250        outside = 1.000
```

So the rectangle is cleared and everything outside it survives — including the depth, which would
have been the easy half to get wrong.

---

## BUG-009 — Transient-arena slices bound the wrong GPU offset

**Status:** **FIXED** (Phase 5). Was latent for vanilla; would have broken any streaming use.
**Severity:** latent until hit, then high — a shader would read the start of the 64 MB arena instead
of its own data.
**Found by:** auditing the arena's offset handling against `GpuBuffer`/`GpuBufferSlice` semantics.

### Cause

`MetalTransientMemory` hands out sub-buffers of one 64 MB `MTLBuffer`. A sub-buffer shares its
parent's **handle** and holds a `data` segment that is already offset into the parent:

```java
public static MetalBuffer sub(int usage, long size, MetalBuffer parent, long offset) {
    MemorySegment slice = parent.data.asSlice(offset, size);
    return new MetalBuffer(usage, size, parent.handle, slice, false, parent);   // parent handle
}
```

Minecraft's `GpuBuffer.slice(offset, length)` returns `new GpuBufferSlice(this, offset, length)`, so
`sub.slice(0, size)` reported **offset 0** while the handle pointed at the whole parent arena.

That was consistent for the CPU paths, which read through the sub-buffer's already-offset `data`
segment (`dataSlice`, `map`, `copyBufferToTexture`, `writeToBuffer`), and wrong for the GPU binding
paths, which combine the handle with the slice offset:

```java
MetalNative.renderPassSetVertexBuffer(encoder, handleOf(slice.buffer()), slice.offset(), index);
```

So every transient allocation bound the arena at byte 0.

### Why it never showed

`TransientMemory` is used by exactly two vanilla classes — `SpriteContents$AnimatedTexture` and
`CubeMapTexture` — and both only *upload* through it, which takes the correct CPU path. Terrain and
GUI vertex data come from dedicated `GpuBuffer`s. It was a trap for anything that streams vertices or
uniforms through the arena (Sodium and Iris both do).

### Fix

`MetalBuffer` now records a `baseOffset` (the sub-buffer's byte offset inside the handle it shares),
`MetalBuffer.sub` accumulates it, and the GPU binding paths add it:

- `MetalRenderPassBackend.absoluteOffset(GpuBufferSlice)` = base + slice offset, used for vertex and
  fragment uniform buffers and for `setVertexBuffer`.
- `setIndexBuffer` records the buffer's base, and the four indexed-draw call sites pass it instead of
  a hardcoded `0`.

Every buffer that owns its handle has base 0, so this is a no-op for all existing non-arena paths.
Covered by seven assertions in `MetalRenderPassBackendTest` that pin the invariant *"a slice's
(handle, offset) pair addresses the bytes the buffer's `data` segment does"*.

---

## BUG-008 — Mip filtering was disabled by a leftover test hack

**Status:** **FIXED** (Phase 5) — the change most worth confirming in-game.
**Severity:** affects all minification — aliasing/moiré on terrain and atlases.
**Found by:** auditing the native sampler creation against what the engine asks for.

### Cause

`mmm_sampler_create` hardcoded:

```c
descriptor.mipFilter = MTLSamplerMipFilterNotMipmapped;  // TEST: force mip 0
```

so **no sampler ever sampled a mip level**, while `lodMaxClamp` was still set from the sampler's
`maxLod`. The engine expresses LOD selection by supplying `maxLod`, and `NotMipmapped` — which is
also `MTLSamplerDescriptor`'s default — silently ignores it. That is a contract violation of the same
kind as the wrong enum tables, not a tuning choice.

### Fix

The caller now chooses the filter, and `MetalSampler` derives it from the engine:

```java
MetalFormat.mtlSamplerMipFilter(maxLod.isPresent())   // present -> MTLSamplerMipFilterLinear
```

Mipmapping is only enabled for samplers the engine actually asked to clamp LODs on; one with no
`maxLod` stays `NotMipmapped`, exactly as before. The engine fills its own mip levels — its
`GpuDeviceBackend` has no mip-generation entry point, so it must populate every level it allocates,
and the block atlas composites each level explicitly. A single-level texture is unaffected either
way, because Metal clamps LOD to the texture's level count.

**Override for the in-game A/B:** `-Dmetalmod.mipFilter=off` restores the old behaviour; `nearest`
or `linear` forces a filter. Five assertions cover the selection logic.

### Verified, not assumed

`metalmod_smoke` now proves it rather than reasoning about it. It builds a two-level texture (level 0
red, level 1 blue), samples a fixed texel at an explicit `level(1.0)`, and reads the result back:

```
mipFilter=Linear       -> R0 G0 B255     (level 1)
mipFilter=NotMipmapped -> R255 G0 B0     (level 0)
```

So the filter parameter really is honoured, and the old hardcoded value really did pin every sample
to level 0. That also confirms the failure mode was exactly as described, and that a single-level
texture is unaffected.

### What to look for in-game

Distant terrain should lose the shimmer it had with level-0 minification; nothing should look newly
wrong or blocky. If it does, `-Dmetalmod.mipFilter=off` isolates this change with no rebuild. The
likely reason the hack existed is the atlas bleeding caused by BUG-007, which is now fixed.

---

## BUG-007 — Sampler address modes were swapped (live for vanilla)

**Status:** **FIXED** (Phase 5).
**Severity:** high — it inverted every sampler in the game.
**Found by:** auditing every MC → Metal enum table against the macOS SDK headers.

### Cause

`MTLSamplerAddressMode` is not GL order and is not guessable: `ClampToEdge = 0`,
`MirrorClampToEdge = 1`, `Repeat = 2`, `MirrorRepeat = 3`. `MetalFormat` had
`ADDRESS_REPEAT = 0`, `ADDRESS_MIRROR_REPEAT = 1`, `ADDRESS_CLAMP_TO_EDGE = 2`, so
`mtlSamplerAddress` returned the exact opposite of the intended mode for both of Minecraft's values:

| MC `AddressMode` | was | Metal value at that slot | now |
|---|---|---|---|
| `CLAMP_TO_EDGE` | 2 | Repeat | **0** (ClampToEdge) |
| `REPEAT` | 0 | ClampToEdge | **2** (Repeat) |

### Impact

Live for vanilla, not latent. `AddressMode` is referenced by `AbstractTexture`, `ReloadableTexture`,
`SamplerCache`, `LevelRenderer` and `RenderTypes`. In practice every atlas sampled with **Repeat**, so
sprites bled into their neighbours at the edges and mip tails smeared across the atlas, and any
genuinely tiling texture was clamped instead. Likely a contributor to the sprite problems in BUG-001
and to terrain texture artefacts.

### Fix

Corrected the constants. `MetalFormatTest` now pins the address modes, the min/mag filters and the
`MTLTextureType`/`MTLTextureUsage` values against the SDK header, so this class of error cannot
return silently.

### Verified, not assumed

`metalmod_smoke` proves Metal actually behaves as the corrected table claims. It uploads a 4x1
texture (texels 0-1 red, texels 2-3 blue) and samples at `u = 1.25`, which is outside [0,1]:

```
addressMode=Repeat(2)       -> R255 G0 B0   (wrapped to texel 1)
addressMode=ClampToEdge(0)  -> R0 G0 B255   (pinned to texel 3)
```

So REPEAT wraps and CLAMP_TO_EDGE clamps, with the raw values the mapping now produces. Had the two
still been swapped, those two lines would be the other way round.

---

## BUG-006 — Five MTLBlendFactor values were wrong (latent for vanilla)

**Status:** **FIXED** (Phase 5). Latent for vanilla; would break shaderpacks and mods.
**Found by:** auditing every MC → Metal enum table against the macOS SDK headers.

### Symptoms

Any pipeline blending with a constant colour/alpha, or with source-alpha-saturate, selected the
wrong Metal blend factor — silently. Wrong blending does not throw and is hard to spot in a still
frame, which is why a test pins this now.

### Cause

`MTLBlendFactor` is **not** in GL order. The SDK header has
`MTLBlendFactorSourceAlphaSaturated = 10` and only then the blend-colour/alpha (constant) factors at
11..14. `MetalFormat.mtlBlendFactor` had `SRC_ALPHA_SATURATE` at 14 and the four constant factors at
10..13:

| MC factor | was | Metal value at that slot | now |
|---|---|---|---|
| `SRC_ALPHA_SATURATE` | 14 | OneMinusBlendAlpha | **10** |
| `CONSTANT_COLOR` | 10 | SourceAlphaSaturated | **11** |
| `ONE_MINUS_CONSTANT_COLOR` | 11 | BlendColor | **12** |
| `CONSTANT_ALPHA` | 12 | OneMinusBlendColor | **13** |
| `ONE_MINUS_CONSTANT_ALPHA` | 13 | BlendAlpha | **14** |

### Impact

Checked by enumerating all 87 vanilla pipelines: none of the five is used (vanilla blends only with
`ZERO`, `ONE`, `SRC_COLOR`, `ONE_MINUS_SRC_COLOR`, `SRC_ALPHA`, `ONE_MINUS_SRC_ALPHA`, `DST_COLOR`
and `ONE_MINUS_DST_COLOR`, which were already correct). Vanilla parity was therefore never affected,
but a shaderpack using constant-alpha blending would have blended wrongly with no error anywhere.

### Fix

Corrected the table. `MetalFormatTest` now pins all 15 blend factors, the 5 blend operations, the 8
compare functions, the 8 primitive topologies and the write-mask bits against the SDK header values.
The build also compiles every standalone test under `src/test/java` automatically, so a new test file
no longer needs `build_mod.sh` edited.

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

### The whole chain, checked rather than inferred

The fix only works if every link lines up, so each was verified:

| link | evidence |
|---|---|
| the engine supplies the payload | `ChunkSectionsToRender` passes `GpuBufferSlice[] chunkSectionInfos` |
| the consumer names it | `LevelRenderer.lambda$prepareChunkRenders$1` calls `uploader.upload("ChunkSection", sections[index])` |
| the backend invokes the consumer | `MetalRenderPassBackendTest` (7 assertions) |
| the name resolves against reflection | `chunksection.glsl` declares `layout(std140) uniform ChunkSection { … }`, and blocks are keyed by type name (BUG-005) |

So the upload arrives as `"ChunkSection"` and `applyBindings` finds the reflected `ChunkSection` block.
Had the consumer used the GLSL instance name instead, the fix would have bound nothing.


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

**Status:** **both paths verified offline** (Phase 5) — pending in-game confirmation.
**Severity:** low / cosmetic, but it is the most visible sign that Phase 5 shading work is unfinished.
**Seen on:** Metal backend enabled, in-world, build `ab30f94`+, `5120x2880` native.
**Screenshot:** [`docs/bugs/inworld-2026-09-22.png`](bugs/inworld-2026-09-22.png)

### Symptoms

Squids and fish (and other entities) render as **solid black silhouettes**, and terrain renders the
same way — geometry is there, but flat black with no textures or lightmap. GUI, text and the hotbar
render correctly, and the sky colour is right.

### Suspected cause (unconfirmed)

**Update (Phase 5): the mechanic is now identified — see BUG-012.** `terrain.vsh` computes
`pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset` from the `Globals` block, and
`Globals` shared Metal buffer slot 16 with `Fog` because glslang emitted duplicate SPIR-V bindings
for them. Both were bound; the second overwrote the first, so terrain was positioned from fog data.
That fits the symptom far better than "a binding was missing", which is what the original note
guessed — nothing was missing. BUG-012 is fixed; this entry stays open until a run confirms the
world looks right.

### Verified offline (Phase 5)

Both halves of this bug now render correctly through the real pipelines in
[`tools/render_check`](tools/render_check/RenderCheck.java), so there is no remaining *known* defect
behind the black silhouettes:

- **Terrain.** A full-screen quad through `SOLID_TERRAIN` comes out white, which requires `Globals`,
  `ChunkSection`, `Projection` and `Fog` to all reach the shader with the values they were given.
  Before BUG-012 was fixed this is exactly what failed.
- **Entities.** A full-screen quad through `ENTITY_CUTOUT` uses the real 36-byte entity vertex format
  — `Position`, `Color`, `UV0`, `UV1`, `UV2` and the `Normal` attribute no other pipeline uses — and
  comes out white, with a second draw proving the `Fog` block and a third proving the fragment stage
  of the shared `DynamicTransforms` block. `ENTITY_CUTOUT` is compiled with `PER_FACE_LIGHTING`, so
  `gl_FrontFacing` selects between the front light colour and the back one (`Color * 0.4` = 102), and
  the check asserts 255 rather than 102: a winding regression cannot pass it.

One trap is worth recording, because it produced a *black* entity in the harness and looked exactly
like this bug. `entity.fsh` blends the opposite way round from what the name suggests:

```glsl
color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
```

so alpha **1** keeps the entity's own colour and alpha **0** paints the overlay colour straight on.
`OverlayTexture`'s generation loop confirms that the texel `NO_OVERLAY` points at (u = 0, v = 10) is
white with alpha 255 — the texture is white in RGB everywhere and only alpha varies — so a
"transparent" overlay is the *full-black-overlay* case. The harness's first version uploaded
`(0,0,0,0)` and got black; the fault was in the test, not the backend.

### Workaround

None. Use the default backend for normal play until Phase 5.
