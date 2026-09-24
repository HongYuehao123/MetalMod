# Known bugs

Open defects found while bringing up the native Metal backend. Newest first. This is a parking
lot, not a work queue — items move into `ROADMAP.md` when they become scheduled work.

Each entry records: where it was seen, what it looks like, how to reproduce it, and the current
best guess at the cause. Add a screenshot under `docs/bugs/` when one exists.

---

## BUG-035 — Temporal upscaling costs far more in the game than its parts measure

**Status:** **OPEN.** Attribution instrumented; the next in-game reading settles it.
**Severity:** high for the mode. It is the difference between temporal being a trade and being a
regression.

### Symptom

At 5120x2664 native, a light scene - digging straight down - ran **231 fps (4.2 ms)**. The same scene
at 50% render scale with temporal ran **73 fps (13.1 ms)**, and F3 reported `0 entities, 0 particles,
0 stamps`, so the per-object overlay was contributing nothing.

### What is ruled out

Every part measured offscreen, at the same 2560x1332 -> 5120x2664, through each pass's own GPU
timestamps, with noise and a depth gradient uploaded rather than a flat clear so MetalFX's
data-dependent history clamping has something to do:

| Part | Cost |
|---|---:|
| MetalFX spatial scaler | 3.2-3.4 ms |
| MetalFX temporal scaler | 3.1-3.3 ms |
| motion pass (dispatch + overlay) | **0.11-0.16 ms** |

So the effect is *not* meaningfully more expensive than spatial, and the motion pass is a tenth of a
millisecond. Neither can account for a 9 ms gap.

### What is left

The live frame differs from the harness in at least three ways that the harness cannot reproduce: the
depth buffer has been written by hundreds of draws rather than cleared, the two passes' command buffers
sit inside a real frame with the engine's own submission, and the frame is paced by a display. The most
likely candidate is the first - reading a just-rendered depth buffer can require a resolve or
decompression that a cleared one does not - but that is a hypothesis, not a finding.

### Two fixes made while investigating

- **The content region was never set.** Both MetalFX headers list "set `inputContentWidth` and
  `inputContentHeight`" as step 2 of every frame, and the temporal encode skipped it - it defaults to
  zero, so the scaler was being told its input was 0x0. The spatial path has always set it and the two
  are otherwise symmetrical, which is why the omission survived: the code read as complete. It is now
  set from the colour texture, so a resized target cannot disagree with what is bound. Offscreen the
  scaler moved from 3.28 ms to 3.15 ms, so this was not the whole story, but it was wrong.
- **The scaler is now built synchronously.** `requiresSynchronousInitialization` defaults to NO, which
  hands back an interim upscaler and compiles the real one in the background; a frame cost measured in
  that window is not the cost of the effect. It is YES now, paid once at creation - a scale or size
  change - rather than risk reporting a mode's speed from its slowest path.

### Instrumentation added

`mmm_motion_last_gpu_ms()` and `mmm_fx_temporal_last_gpu_ms()` expose the two command buffers' own GPU
spans, recorded from their completion handlers and printed on F3 as `gpu motion X ms + scaler Y ms`.
This costs nothing on the frame and splits the temporal path in the running game, which is the
measurement that was missing.

### Next step

Read those two numbers in the same scene as the 231-versus-73 comparison. Whichever is large names the
suspect: a large `motion` means the depth read is the cost and the pass needs a different form; a large
`scaler` means MetalFX's temporal filter is genuinely this expensive at 5K here, and the honest answer
is that the mode is a trade rather than a win - or that its output texture must be private
([BUG-032](#bug-032--metalfx-is-handed-a-shared-storage-output-texture-which-it-documents-as-private))
before it runs at full speed.

---

## BUG-034 — Every entity carried half a bounding box of spurious vertical motion

**Status:** **FIXED**, offline.
**Severity:** conspicuous on the one thing the object overlay exists for. It would have put a vertical
smear on every mob, which is precisely the artefact the overlay was added to remove.

### Symptom

None in a frame - found by a test, before any session could see it. A player-sized entity five blocks
in front of a still camera, moved half a block sideways, produced a motion vector of
`(-7.14, 12.85)` where it had to be `(-7.14, 0)`. That 12.85 is exactly half the box's height in
pixels, which is the tell.

### Cause

Two places disagreed about what the captured box stores. `recordBox` wrote the box's **minimum** y
into the per-object history, because that is what the sample row holds and what the eight corners are
built from. `buildStamps` projected the current box's **centre** - `y + height / 2` - against that
stored value, so the previous centre sat half a box below the current one and the difference was
motion. Every entity's stamp was therefore offset downward by half its own height: a player by 12.85
pixels, a small mob by less, an ender dragon by hundreds.

### Why the existing checks missed it

They only ever asserted the x component, and x is unaffected by a constant y error. The check that
found it moves **two** entities in opposite directions and asserts both signs appear, which is also
what would catch an instance-indexing bug where every stamp took the first object's motion.

### Fix

The history stores the centre, which is what the stamp builder projects; the sample row keeps the
minimum, which is what the corners need. The scaling check now asserts that a purely horizontal move
carries no vertical component at all - `worst |motion.y| = 6.2e-6` after the fix against `12.85`
before it, so the assertion fails loudly if the two representations drift apart again.

---

## BUG-033 — Releasing the temporal scaler while its work is in flight aborts the process at exit

**Status:** **FIXED**, verified over repeated runs.
**Severity:** high for the gate, latent in game. It aborts the JVM after every check has passed, and
the same release runs on every resize.

### Symptom

`tools/scaling_check/run.sh` printed `SCALING CHECK PASSED` and then died:

```
SCALING CHECK PASSED
libc++abi: terminating due to uncaught exception of type std::__1::system_error:
mutex lock failed: Invalid argument
Abort trap: 6
```

Intermittent: three runs in five aborted, two exited cleanly. `echo $?` through a pipe had hidden it -
`$?` was the pipe's exit status, not the script's - which is why Gate 6 looked green.

### What ruled other causes out

- **Not pre-existing at Phase 7A.** Built at `f441a20` and ran the same check three times: exit 0
  every time. The committed Phase 7B work introduced it.
- **Not the motion kernels.** Holding the compiled compute and overlay pipelines as ARC-strong
  globals meant they were released from a static destructor at image unload, which is the classic
  shape of this abort. Releasing them deliberately (`__bridge_retained` into a `void*`, never
  released) is correct for a process-lifetime cache and is part of the fix - but it only reduced the
  rate, to roughly three in five from every run.
- **Not the motion resources or the device.** With the temporal scaler intentionally leaked, five
  runs in five were clean while the motion texture, stamp buffer, spatial scaler and device were all
  still destroyed normally.

### Cause

`WorldRenderTarget.temporalScalerRelease()` destroyed the `MTLFXTemporalScaler` immediately, and the
last frame's encode can still be in flight at that moment. Draining the queue first turned five runs
in five green, which is the whole of the evidence. The spatial scaler shows no such behaviour: Phase
7A released one at exit for a whole phase without this. The asymmetry is observed, not explained.

### Fix

`temporalScalerRelease()` synchronises the device queue before destroying the scaler. The stall is
bounded by one frame and only happens when a scaler is actually being replaced - a resize or a
teardown - not per frame. The pipeline cache was moved to a deliberate process-lifetime retain at the
same time.

---

## BUG-032 — MetalFX is handed a shared-storage output texture, which it documents as private

**Status:** **OPEN, accepted.** The upscaled pixels are verified correct offscreen; the requirement is
recorded rather than quietly ignored.
**Severity:** latent. Nothing observed in a frame, but the API contract is not met.

### Symptom

None on screen. With Metal API validation enabled (`MTL_DEBUG_LAYER=1 ./native/build/metalmod_smoke`)
the process aborts inside MetalFX:

```
MetalFXSpatialScalingEffectV1.mm:306: failed assertion `outputTexture must have private storage mode'
```

### Cause

`MTLFXTemporalScalerBase.outputTexture` is documented as "you are responsible for providing a texture
with a private `storageMode`", and the spatial effect asserts the same thing. MetalMod creates every
`MTLTexture` with `MTLStorageModeShared`, which was measured 9% faster than private storage for its
render targets (see `MetalTexture`), and both the spatial and the temporal upscale write straight into
the engine's shared main target.

### What is verified

The requirement is not enforced in release builds, and `tools/scaling_check` now asserts the
*outcome* rather than assuming the shortcut is harmless: with a shared output the temporal upscale
puts a known flat colour into the native target within a tolerance of 8, and the spatial path passes
its own pixel checks. So the shortcut works on this OS and this hardware.

### Next step

If a future OS starts enforcing it, the fix is a private intermediate colour target that the scaler
writes and a blit into the engine's target - one full-resolution copy per frame. That is the cost
being avoided; it has not been paid because the shortcut currently produces the right pixels.

---

## BUG-031 — The upscale could overwrite a frame that had decided not to scale

**Status:** **FIXED**, offline.
**Severity:** visible in the exact case it fires - the world would show an older frame.

### Symptom

None observed in game; found by reading the invariant against the code. When a frame's boundary
decision is "do not scale this frame" - a window resize in flight, or MetalFX becoming unusable while
a level target still exists - the level draws straight into the engine's own target. `upscale` was
called unconditionally at the end of `LevelRenderer.render` and only checked that a level target
existed, so it would take the *stale* small target and write it over the frame that had just been
rendered natively. The screen would hold the previous frame's world, with this frame's interface
drawn on top.

### Cause

`WorldRenderTarget.upscale` did not consult `scaleThisFrame`, the flag the frame boundary sets to
record the one decision the class comment says a frame may make.

### Fix

`upscale` returns without touching the target unless `scaleThisFrame` is set, and records
"this frame rendered at native resolution" as the reason. The redirect and the upscale now agree
about which frame they are, which is what the once-per-frame decision exists for.

---

## BUG-030 — Projection jitter was expressed in window pixels, not render pixels

**Status:** **FIXED**, offline.
**Severity:** degraded the temporal path this phase delivers; invisible while temporal was gated off.

### Symptom

At a render scale below 100%, the projection was offset by a clip-space translation derived from the
*window's* width and height, while the level renders into a target that is a fraction of that size.
A clip translation of `2j/W_window` moves an image `W_render` pixels wide by `j * scale` pixels, not
by `j`. The temporal scaler is told its offset in *input*-texture pixels, so it would have been
reprojecting a frame that had been jittered by a different amount than it was told - the accumulated
image soft or vibrating rather than sharper - and the offset sequence would no longer have averaged
to zero in the unit the scaler works in.

### Cause

`CameraJitterMixin` converted the offset against `Projection.width()` / `height()`, which the engine
sets up from `Window.getWidth()` / `getHeight()` (the framebuffer size). The render target is
`round(native * scale)` and the level's viewport is that target, so the conversion had to use it.

### Fix

The clip-space offset is now derived from `RenderScaleSettings.scaledSize(...)`, which is the same
function that sizes the level target, so the projection and the scaler cannot disagree about what one
input pixel is. `tools/scaling_check` covers the whole chain: two frames rendered with *different*
jitter phases and a still camera must produce exactly zero motion, which is only true if the offset is
removed before the reprojection matrices are built.

---

## BUG-029 — The sky is brighter and less saturated at a render scale below 100%

**Status:** **FIX ATTEMPTED, awaiting in-game confirmation.** A resource reload after the scale change is
requested; the reasoning and the evidence for it are below.
**Severity:** cosmetic but conspicuous. The sky is the largest smooth field on screen, so a shift there
is more visible than the same shift anywhere else.

### Symptom

At 50% render scale with MetalFX spatial, the sky comes out brighter and less saturated than at native
resolution. Measured in one session at one spot, by the F10 frame sampler reading the presented frame:

| Sample point | Native | 50% + MetalFX | Difference |
|---|---|---|---|
| sky-top | 163 175 203 | 183 190 203 | +20, +15, 0 |
| sky-left | 162 175 204 | 183 191 204 | +21, +16, 0 |
| sky-right (pure sky) | 129 169 255 | 192 216 255 | **+63, +47, 0** |
| ground | 212 115 167 | 211 116 166 | −1 |

Green and blue rise while **red stays within a unit**, which is why it reads as "grey"; the terrain at
the same sample point is unchanged.

### What has been ruled out

The scaler is not filtering the colour wrongly. Offscreen, through the real MetalFX effect:

| Input | Result |
|---|---|
| flat sky blue (120 167 255), grass green, stone grey | **exact**, delta 0 on every channel |
| a smooth blue-to-pale ramp, 128x128 to 256x256 | mean shifts by **0.4** |
| the same ramp with a ±1 LSB checkerboard dither on it | mean shifts by **0.4** |

A reconstruction filter cannot be blamed for a mean shift when it does not shift any mean. It is also
worth noting the ratio here is exactly 2x, which is the scaler's most favourable case.

### What is established

The difference is in what the frame **contains before the upscale**, so it is resolution-dependent
rendering rather than the effect. The sky is drawn by `core/sky`, whose fragment shader is
`apply_fog(ColorModulator, ...)` against the `Fog` block, so the prime suspect is something in the sky
or fog path that reads the render area or viewport rather than the window - the two differ only when
scaling is on.

### How to reproduce

1. Stand with sky and ground both in view.
2. F3 open, press F10, wait five seconds, press F10 again.
3. Compare the `frame sample` lines for `sky-right`: the difference is tens of units.

### What an offscreen reproduction could not do

Drawing the real `core/sky` pipeline at two target sizes was attempted in `tools/render_check` and then
removed. Two problems, both fatal to it as a check:

1. The sky's own geometry is a large dome on the far plane, and the fragment colour is
   `apply_fog(...)`. A synthetic quad with the sky's fog on saturates to a constant at both sizes - a
   comparison between two identical white fields, which is a check that cannot fail. With the fog off it
   compares the projection and viewport only, and those agree, which rules out one hypothesis and does
   not explain the symptom.
2. Comparing means wastes the signal. The defect is **red unchanged while green and blue rise**, and on
   a synthetic field there is no reason for that asymmetry to appear even if the cause were present.

The lesson worth keeping: a check whose output is a constant is worse than no check, because it reads
as evidence. What this needs is the real frame, which is what the F10 sampler already reads.

### The evidence that identified it

**Toggling the render scale causes it, and F3+T fixes it.** That single observation is worth more than
the offscreen work above, because a resource reload is a specific, named operation: the engine clears
its pipeline cache and recompiles every static pipeline (`ShaderManager.reload` calls
`GpuDevice.clearPipelineCache` and then recompiles). So a pipeline compiled while the render target was
a different size is replaced by the reload, and the colour it produced goes with it.

The reload also refreshes textures and resource-backed state, so it is not certain that the pipeline
cache was the *only* stale thing - which is an argument for doing the reload rather than for guessing
further.

### The fix

`WorldRenderTarget.refresh` detects a change in the render-scale **setting** - as opposed to a window
resize, which was never implicated - and asks the engine for a resource reload through
`Minecraft.delayTextureReload`, which runs it on the main thread between frames rather than inside one.
Requested once per change, not per frame.

Both halves of what the reload does are also done directly where they are cheap: the frame hook that
rebuilds the target invalidates the compiled pipelines on the rebuild signal, so the correct pipelines
are in place even before the reload completes.

### If it does not fix it

Fall back to this measurement: compare the **world target before the upscale** against the main target
after it, in the same frame, on the same scene. That splits the remaining possibilities in one
measurement:

- the two agree in the sky region &rarr; the difference is introduced by the upscale after all, and the
  synthetic tests were measuring the wrong input;
- they already differ &rarr; the scaled pass renders the sky differently, and the next place to look is
  the uniform values the engine sets for that pass - fog distances and the projection among them -
  which are the only inputs that change between the two.

The sampler needs one addition for this: reading the world target's sky strip alongside the main
target's, in the same sample.

---

## BUG-026 — The render-scale predicate answered "on" at native scale

**Status:** **FIXED** (Phase 7A) — found and fixed by `tools/scaling_check` before any in-game run.
**Severity:** high had it shipped. The default configuration would have taken the scaling path.

### Symptom

`RenderScaleSettings.active()` reported `true` at a render scale of exactly 1.0.

### Cause

The predicate was expressed in terms of the scaled *dimension*:

```java
return scaledSize(1) != 1 || scaledSize(0) != 0;
```

`scaledSize` clamps its result to at least one pixel, so `scaledSize(1)` is `1` at every scale and
`scaledSize(0)` is also `1` — the first term is always false and the second always true. The
predicate was a constant.

Nothing in the feature was wrong; the entry condition was. Had it shipped, every frame at the default
setting would have allocated a second render target the size of the first, filled it with a copy of
the frame and blitted it back — the feature-off path the roadmap requires to be untouched.

### Fix

`active()` now tests the scale itself (`renderScale() != 1.0`), and `scaledSize`'s clamping is
documented as a pixel-count rule rather than a proxy for "is scaling on". `tools/scaling_check`
asserts the predicate's answer in both directions, which is what caught it.

---

## BUG-027 — The Halton jitter sequence does not average to zero

**Status:** **FIXED** (Phase 7B) — found by `tools/scaling_check`, fixed before the path was usable.
**Severity:** would have been a slow visual defect, not a crash: a permanently drifting image.

### Symptom

Over one sixteen-frame cycle, the projection jitter summed to `-0.469` on the x axis and `-0.593` on
y rather than to zero.

### Cause

The sequence was generated as `halton(i, base) - 0.5`, on the common assumption that a Halton sequence
is centred. It is centred in the limit; a **finite prefix** is not. Over sixteen samples the base-2
axis sums to `+0.47`, so subtracting `0.5` leaves a net offset of `-0.03` per frame — a sub-pixel bias
that accumulates into a visible drift over a session.

### Fix

The prefix mean is measured and subtracted instead of assumed: the constants are centred by
construction. The check asserts the sum over the cycle rather than sampling one offset, because a
single offset looks perfectly reasonable either way.

---

## BUG-028 — A frame with no level in it would have cleared the wrong target

**Status:** **FIXED** (Phase 7A) — found by inspection while wiring the redirect; no in-game run yet.
**Severity:** high. The main menu, the loading screen and any interface-only frame would have shown
the previous frame's contents behind them.

### Symptom

None observed. The defect is in the shape of the redirect rather than in an outcome: the frame's
opening `clearColorAndDepthTextures` is split so the colour goes to the scaled level target and the
depth to the engine's own, and that split is only correct when the frame is about to draw a level.

### Cause

The split was applied whenever the redirect was configured. The engine's clear runs *before* it
decides whether to draw a level, so on a frame that draws only the interface — the main menu, the
loading screen, the pause background with no world — the colour went to a target nothing would read,
and the native target kept the previous frame. The interface then drew over stale pixels.

### Fix

`GameRendererFrameMixin` records the engine's own three-term decision
(`isGameLoadFinished() && renderLevel && level != null`) at the head of the frame, before the clear,
and the split is conditional on it. `tools/scaling_check` asserts both states of that flag; a frame
with no level takes the engine's clear unchanged.

---

## BUG-025 — Inventory player preview is upside down, and the item icons vanished

**Status:** **FIXED** (Phase 5) — **confirmed in game**. The player preview is upright and the
item icons are present in their slots and the right way up.
**Severity:** medium. The inventory is usable but the preview is wrong and the icons were missing.

### Symptoms

- The player model at the top-left of the survival inventory rendered **upside down**.
- After the BUG-001 scissor fix landed, the inventory **item icons disappeared** from their slots.

### Cause

Two Y-convention bugs in the GUI's offscreen targets, and they interact.

1. **The picture-in-picture entity target was not flipped.** `PictureInPictureRenderer` renders each
   preview into a texture labelled `"UI " + label + " texture"` (the inventory player is
   `"UI entity texture"`) with the same Y-down ortho the GUI uses, then `blitTexture` samples it with
   an **inverted V** (`v0 = 1, v1 = 0`). `needsYFlip` only knew `/atlas/` and `"UI items atlas"`, so
   the entity pass kept Metal's orientation and the inverted-V blit turned it over.
2. **The scissor was converted on a pass that was already flipped.** BUG-001's fix converted the
   engine's bottom-up scissor to Metal's top-left unconditionally. On a pass whose viewport is
   already Y-flipped the framebuffer mapping is already mirrored, so the engine's `y` must be
   applied directly - which is exactly what the native region clear (`mmm_clear_textures_region`)
   does. `GuiItemAtlas.drawToSlot` clears a slot and then scissors the **same** rectangle
   (`RenderSystem.enableScissorForRenderTypeDraws(left, textureSize - bottom, ...)`); converting only
   the scissor moved the clip off the slot it had just cleared and the icon was wiped.

### Fix

- `MetalCommandEncoderBackend.needsYFlip` now flips every `"UI "` target, which covers the item
  atlas and all six PiP textures (entity, player skin, banner result, book model, profiler chart,
  oversized item).
- `MetalRenderPassBackend.enableScissor` converts the Y only when the pass's viewport is *not*
  flipped, and applies it directly otherwise.
- Locked in by a new `tools/render_check` assertion: a scissor on a flipped-viewport target stays at
  the given coordinates (top-left quadrant), so it coincides with the region clear. Removing either
  half of the fix fails a check.

The native viewport flip already reverses the front-face winding
(`setFrontFacingWinding:(height < 0 ? Clockwise : CounterClockwise)`), so back-face culling stays
correct for an entity drawn in a flipped pass.

---

## BUG-001 — "Select World" list entry has no background panel (and a garbled name line)

**Status:** **FIXED** (Phase 5) — **confirmed in game**. The scissor rectangle was mirrored
vertically; Select World entries now have their background panels and a single normal name line.
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

### Suspected causes (superseded — see "Root cause" below)

None of these was the cause; they are kept because they are the reasons the two offline mechanisms
that *were* checked were checked. The entry background is a sprite from the GUI atlas, and the garble
looks like a text/quad drawn with a wrong sub-rect or transform, so this was originally read as a
leftover of the Phase 3 atlas work (`ab30f94`, which fixed the atlas Y-flip and the negative-viewport
winding):

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

GUI clipping was *not* ruled out — it was the bug. `RenderPass.enableScissor` is what stops a sprite
or a line of text spilling outside its panel, and it takes a **bottom-up** rectangle (the GL
convention): `GuiRenderer.enableScissor` converts its top-down `ScreenRectangle` with
`window.height - bottom` before calling it, and `GlCommandEncoder` forwards the values straight to
`glScissor`, whose origin is bottom-left. Metal's `setScissorRect` origin is **top-left**, and the
backend passed the values through unchanged, so every scissor was mirrored vertically. See "Root
cause" below. The render check "confirmed" the wrong convention for a year of this bug's life:

```
before: PASS  scissor(0,0,32,32) draws the top-left quadrant   <- asserted the off-by-a-flip
after : PASS  scissor(0,0,32,32) draws the bottom-left quadrant <- (0,0,W/2,H/2) is bottom-left
```

and separately blends 50%-alpha white over black, which lands at exactly `R128` — so blend state
(enabled, factors, op) is plumbed correctly.

### Root cause (identified, fixed)

The scissor Y was never flipped. The engine's scissor rectangle is bottom-up, Metal's is top-left,
and `MetalRenderPassBackend.enableScissor` handed the engine's values to `mmm_render_pass_set_scissor`
unchanged, so `setScissorRect` mirrored the rect about the vertical centre of the target.

That explains both symptoms exactly. The Select World list's scissor is the list rect; mirrored, its
top edge lands part-way down the first entry, so:

- the selection/background panel for the first entry is clipped away ("no dark rounded entry
  behind them"), and
- the top of the first entry is cut. The entry's first text line is the world name, drawn at
  `getContentY() + 1`; only its lower few pixels survive the clip, which is the "compressed/garbled"
  line. The lines below it, and the bottom buttons, are below the clip and look normal.

The fix is in `MetalRenderPassBackend.enableScissor`: convert the engine's bottom-up Y to Metal's
top-left origin (and apply it directly on a pass whose viewport is already Y-flipped — see BUG-025,
which that refinement fixes):

```java
int metalY = this.owner.viewportFlipped() ? y : this.height - (y + height);
MetalNative.renderPassSetScissor(this.encoder, x, metalY, width, height);
```

Verified offline by correcting the render-check assertion above (it now probes the bottom-left
quadrant, which is what `enableScissor(0,0,W/2,H/2)` means in the engine's convention). Before the
fix that corrected check fails; after it, it passes. In-game confirmation is still pending.

### Workaround

None needed. Use the default (Vulkan/OpenGL) backend if the list is hard to read; select worlds
by their position, which still works.

---

## BUG-002 — Block selection outline is drawn as a huge wireframe box

**Status:** **FIXED** (Phase 5) — **confirmed in game**. Root cause was BUG-012; the selection
outline now hugs the targeted block.
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

## BUG-024 — Inventory item icons are upside down, and some never appear

**Status:** **FIXED** (Phase 5) — **confirmed in game**. The inventory icons are present and
the right way up.
**Severity:** medium - the screen works, the icons are wrong.
**Found on:** the second in-game run. Reported as "everything might not show up, and for those showed
up, it is upside down."

### Reading

Upside down is a Y-flip, and this is the same family as BUG-022 and BUG-001's atlas flip: a surface is
composited into a target without the flip that Minecraft's UV convention assumes, or read back with
one too many.

`/atlas/` targets are flipped (see the atlas comment in `MetalCommandEncoderBackend`). The block and
particle atlases are named `minecraft:textures/atlas/*.png` and therefore covered. So the question is
which target the **item** path uses, and whether its label is covered - MC has a separate GUI item
atlas (cleared one slot at a time, which is what BUG-010 was about) and a `UiLightmap` class of its
own, so the UI has paths the world does not.

"Some never appear" is consistent with the same cause: a sprite composited into the wrong row reads
back as the wrong sprite, and a sprite that lands outside its slot reads as transparent.

### Cause

The GUI item atlas is created as a texture labelled **`"UI items atlas"`**, and the flip test was
`label.contains("/atlas/")`. It therefore did not match, the item icons were composited into it
unflipped, and the inventory sampled them upside down - with sprites landing in the wrong slot, which
is why some never appeared at all. The block and particle atlases are named
`minecraft:textures/atlas/<name>.png` and were covered, which is why only the UI item atlas was
affected.

### Fix

`needsYFlip` now recognises both families instead of one substring. The reasoning behind the rule is
written down with it: Minecraft composites these targets with a projection that assumes a Y-down NDC
and samples them with `v = row / height`, so every such pass must flip, and the target label is the
only signal available because the matrix arrives later as the `Projection` uniform.

### Making the next one visible instead of silent

A missed label is a silent, wrong-looking frame - which is how this one survived a fix that was
already in place for its two siblings. Every distinct colour-target label the engine renders into is
now logged once, with whether it was flipped:

```
[MetalMod] render target 'minecraft:textures/atlas/render-check.png' yFlip=true
[MetalMod] render target 'UI items atlas' yFlip=true
[MetalMod] render target 'Lightmap' yFlip=true
...
```

So the set of targets is read off a run rather than reasoned about, and a target being rendered into
without the flip appears in `logs/latest.log` rather than showing up as a wrong picture later.

---

## BUG-023 — Water puts a glaze on itself, and its edge lags the surface

**Status:** **FIXED** (Phase 5) — **confirmed in game**. `writeToBuffer` and `copyToBuffer` were
synchronous CPU memcpys, so a section could be drawn with the next section's data.
**Severity:** medium for appearance; it is the most visible remaining artefact.
**Found on:** the second in-game run. Reported as "the water is more like only glaze where water is.
It feels like the water is lagging a little bit, as if you fly through the edge of water in creative
mode, you will find for one second that the edge is away from the surface."

### Reading

Two things follow from that description, and they point away from the guesses already eliminated:

- **The glaze is local to the water**, so it is not a full-screen tint, not the lightmap (BUG-022),
  and not a texture-sampling problem shared with other blocks.
- **"For one second the edge is away from the surface" is a temporal symptom.** A single frame cannot
  show it; something is being drawn from data that is a frame or more old, or a pass is reading a
  texture that another pass writes later in the same frame.

`WATER_MASK` declaring `WRITE_NONE` was the obvious candidate for a water pass painting the scene, and
a render check now proves the mask is honoured - so that is eliminated, not assumed.

### What translucency actually is in 26.2 (established)

Water is not blended into the main target at all. `assets/minecraft/post_effect/transparency.json`
renders translucent geometry into **six colour+depth target pairs** and then composites them in one
pass:

- pass 1: `core/screenquad` + `post/transparency` -> target `final`, with **twelve** inputs
- pass 2: `core/screenquad` + `post/blit` -> `minecraft:main`

`post/transparency.fsh` declares six colour samplers and six **depth** samplers
(`MainSampler`/`MainDepthSampler`, `Translucent`, `ItemEntity`, `Particles`, `Weather`, `Clouds`),
insertion-sorts the layers by their depth, and blends them front to back:

```glsl
vec3 blend(vec3 dst, vec4 src) { return (dst * (1.0 - src.a)) + src.rgb; }
```

So the water's appearance is the product of one depth-sorted composite over twelve sampled textures.
Anything that makes that composite read stale, unsorted or mis-bound data shows up as *a translucent
film where the water is, at the wrong depth* - which is exactly the report.

Both passes use `core/screenquad`, so they are now covered by the BUG-022 viewport flip; the two flips
cancel for the final image, so that fix is neutral here rather than the cause.

### Ruled out already

- **`WATER_MASK`'s `WRITE_NONE`** - measured honoured (see the write-mask check).
- **Depth textures being unsampleable.** Metal needs `MTLTextureUsageShaderRead` to sample a depth
  texture, and `MetalFormat.mtlTextureUsage` grants `ShaderRead | PixelFormatView` unconditionally, with
  a comment explaining that the presentation blit needs it. So the six depth samplers can be read.

### Root cause (identified)

"**As long as the player is in the water everything is fine**" is the detail that settles it. It rules
out anything about how water *looks* and points at **depth-testing the translucent layer against the
opaque scene**:

- Outside the water, the translucent pass must reject water that is *behind* opaque terrain. Its
  layer is a separate FBO with its own depth buffer, so that buffer has to be **initialised from the
  main depth buffer** before the translucent pass runs - otherwise it is empty, and with reversed-Z an
  empty depth buffer is `0.0`, i.e. *far*, so every water fragment passes the test. Water behind a
  hill then composites over it: a translucent film wherever water exists, with the water's edge not
  matching the surface it should be hidden by.
- Inside the water, the water is in front of everything, so the depth test gives the same answer
  whether or not that initialisation happened. **Everything looks fine.**

That is exactly the reported asymmetry, and it also explains "for one second the edge is away from the
surface": the initialisation is not happening at the point in the frame the engine assumes.

And the mechanism is concrete. MC initialises those depth buffers with a **texture-to-texture copy**,
and `MetalCommandEncoderBackend.copyTextureToTexture` is not a GPU blit - it is a CPU round trip:

```java
MetalNative.queueSynchronize(this.device.queueHandle());
MemorySegment temp = arena.allocate(size);
if (MetalNative.textureReadRegion(src.handle(), ...) == 0) {
    MetalNative.textureReplaceRegionRaw(dst.handle(), ...);
}
```

It commits and waits for an empty command buffer, reads the source back into CPU memory and uploads
it into the destination. That is a separate, synchronised CPU operation rather than a piece of the
frame's command buffer, so it cannot be ordered the way the engine assumes it is, and it stalls the
pipeline for a full-size depth buffer on every frame that uses it.

### Correction: the depth copy was never the cause

A behavioural check now proves the CPU round trip **does** copy depth. It defines the target's
starting state (a 0.0 quad), copies a source holding 0.75, then loads the target and draws at 0.5:

```
PASS  the depth-copy source renders a 0.75 quad -> R255
PASS  a copied depth buffer ... 0.5 against a copied 0.75 leaves the clear -> R0
PASS  control: 0.5 against a cleared 0.0 is accepted, so the draw itself works -> R255
```

So the earlier claim in this entry - that a CPU round trip left the translucent layers depth-testing
against an empty buffer - is **wrong**, and BUG-023's cause is still open. The first version of this
check was vacuous on two counts: it cleared the depth *before* loading it, so it never sampled the
copied data, and its assertion was inverted, so it passed only when the copy failed.

What the round trip genuinely costs is a synchronised stall on a full-size depth buffer, which is
worth removing for its own sake but is not what the glaze is.

### Confirmed fixed

Confirmed in game. The cause was **`writeToBuffer` and `copyToBuffer` being synchronous CPU memcpys**:
a buffer transfer that the engine expects to be a GPU-side operation blocked the CPU and could leave a
section's data mid-flight, so a section rendered with another's - per-region, per-frame, and visible
the moment the camera moved, which is exactly the reported behaviour.

The texture-copy blit fixed earlier is a separate, real improvement (it removes a stall and a CPU
write that races the GPU) but it was not this bug, and the note that once stood here claiming the
blit "did not help" was measured against a session that had not reloaded the jar.

### Lesson worth keeping

Two measurement failures cost real time on this bug: judging a fix from a build the running process
had not loaded (compare the jar's mtime against the log's first line), and - separately - three checks
in this area that passed while proving nothing (an inverted depth assertion, a depth check that
cleared the depth before loading it, and a readback helper that read GPU-written memory without
synchronising). A green check is only evidence if it can fail.

### Superseded: the fix and why it was reverted once

`copyTextureToTexture` is now `mmm_copy_texture_to_texture`, a `MTLBlitCommandEncoder` copy committed
on the device queue. It replaced a CPU round trip, and the round trip's defect is a **race**, not a
missing copy: `replaceRegion` writes shared memory from the CPU, while the GPU may still be reading
that same texture for the previous frame's composite.

That fits the artefact exactly, which the user described as *transient, random, and only while
moving*: a stale value differs from the current one only while the camera moves, and which texels
tear depends on timing. It also explains why water is the only sufferer - the translucent layer's
depth buffer is what is being rewritten.

This fix was written once before and **reverted on a bad measurement**. The render check's `readback`
helper read the texture immediately without synchronising the queue, so it only ever saw data the CPU
had written itself; against a GPU blit it reported zeros every time. The helper now synchronises, and
both colour copy cases pass through the blit:

```
PASS  copyTextureToTexture copies the whole texture byte for byte
PASS  a 2x2 copy at (1,1) changes exactly that rectangle
PASS  a copied depth buffer ... 0.5 against a copied 0.75 leaves the clear -> R0
```

A helper that reads GPU-written memory without synchronising is the same failure mode as the vacuous
depth check in the section above: it passes, and it says nothing.

### Superseded: the blit is written but not yet usable

`mmm_copy_texture_to_texture` exists, is declared and is bound, and `copyTextureToTexture` is
deliberately still on the CPU path with a comment saying why. Wiring the blit in makes both colour
copy cases in `tools/render_check` read back **zeros**, so the blit itself is wrong somewhere -
origin, size, slice or level - and it must not replace a working path until that is found. The two
colour copy cases are the reproducer. Note the argument order: the native signature is
`(sourceSlice, sourceLevel)` and passing `(level, slice)` silently copies the wrong mip for any
texture with more than one level, which is a trap worth keeping in mind while debugging it.

### Verified

The existing colour copy tests pass through the new path, and they were never able to catch this
because a CPU round trip copies colour perfectly well. So the check is a **depth** copy, and it is
asserted by behaviour rather than by reading depth back - reading and writing a depth texture from the
CPU is exactly the path that was wrong, so asserting through it would have tested the harness:

1. render a quad at depth 0.75 into the source (control: the write path works);
2. copy the source into a target that was cleared to 0.0;
3. draw a quad at 0.5 against the target with `GREATER_THAN_OR_EQUAL` - the copied 0.75 rejects it and
   the clear survives;
4. the same draw against an uncopied target accepts it, so a quad that never draws cannot pass this.

All four pass.

### Superseded: the staleness hypothesis

The leading hypothesis *was* **staleness of the six translucent layers**, not geometry or UVs: a
layer that still holds the previous frame's contents composites as a ghost, which reads as a film
where the water is and an edge sitting away from the current surface - and it would last as long as it
takes for the layer to be overwritten, matching "for one second".

1. **Are those targets cleared each frame, and by whom?** MC can clear through the frame graph's load
   action *or* through a separate `clearColorTexture` call. MetalMod's `clearColorTexture` runs
   `mmm_clear_textures`, which creates and commits **its own command buffer immediately** rather than
   joining the frame's. If a clear lands in a different command buffer from the pass that renders into
   that target, the ordering is no longer what the engine assumes. Check which of the two MC uses for
   the six translucent targets.
2. **Instrument it before changing anything.** Extend the per-target census line in
   `MetalCommandEncoderBackend` to report whether the colour and depth attachments carried a clear
   (`render target 'X' yFlip=.. clear=.. depthClear=..`). One run then says whether the layers are
   loaded rather than cleared, which decides between a clearing bug and a compositing one.
3. **`translucent_terrain`'s depth state**, since the layer's own depth buffer is what the composite
   sorts by - if the translucent pass wrote depth when it should not, or vice versa, the sort order is
   wrong even with correct data.

### Fixed: writeToBuffer was a CPU memcpy, so one frame read the next frame camera

Both the uniform writes and the mesh uploads were synchronous CPU `MemorySegment.copy` calls into
shared Metal buffers. Vulkan records both as `vkCmdCopyBuffer` operations in the frame, so the queue
orders the write behind everything committed before it. The one that produces a
**chunk-border-aligned** artefact is the uniform one.

`GlobalSettingsUniform.update` rewrites the `Globals` block (`CameraBlockPos`, `CameraOffset`, ...)
**every frame** with `CommandEncoder.writeToBuffer`, and nothing fences that buffer. The block is
exactly what `terrain.vsh` adds to each vertex:

```glsl
vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
```

With the CPU write, frame N+1's camera position lands in the buffer while the GPU is still executing
frame N. Every draw frame N records after that write combines frame N's `ChunkSection` (its own fenced
ring-buffer slot) with frame N+1's camera - so **one displayed frame is split into two different
offsets**, and the boundary between the two groups is a section border. That is the reported "black
spots more likely on the side of chunk borders": a shifted water surface at a grazing angle opens the
dark void behind it. Standing still, frames N and N+1 carry the same camera, so the race is invisible.
Diving into the water removes the grazing view of the surface, so it goes quiet there too.

The mesh path (`CommandEncoder.copyToBuffer`) had the same defect and was fixed first, but it was not
the hot path here: because MetalMod reported `persistentMapping=false`, the engine used
`StagingBuffer.Cpu`, whose `copyTo` goes through `writeToBuffer`, not `copyToBuffer`.

### Fix

- `writeToBuffer` now stages the bytes in a temporary `MTLBuffer` and records an
  `MTLBlitCommandEncoder` copy on the device queue (`mmm_write_buffer_bytes`), then commits it. The
  command buffer retains the staging buffer, so the write is ordered behind the previous frame's
  reads and before this frame's draws.
- `copyToBuffer` does the same with `mmm_copy_buffer_to_buffer`.
- The device feature flags are deliberately left as they were. Vulkan reports
  `writeToBufferIsSlow=false` as well and therefore also takes the `StagingBuffer.Cpu` path; what made
  that path wrong was the synchronous `writeToBuffer`, not the staging choice. `persistentMapping`
  only matters to `StagingBuffer.create`, and only together with `writeToBufferIsSlow`.

### Verified

- `metalmod_smoke`: a buffer section checks that a fenced `copyToBuffer` and a fenced
  `writeToBuffer` are byte-exact, that source/destination offsets are honoured with the untouched gap
  preserved, and that out-of-range and null copies are refused.
- `tools/render_check`: a `bufferCopyCheck` drives both through the real `CommandEncoderBackend` and
  asserts the same, synchronising the queue first because both are now GPU operations.
- `./scripts/build_mod.sh`, `metalmod_smoke`, `StandaloneTestRunner`, `tools/render_check` and
  `tools/shader_inventory` all pass.

### Correction to the earlier reading

The `post/transparency` composite over six colour+depth layers is **not** on this path. It runs only
when `GameRenderState.useShaderTransparency()` is true, which needs the "Improved Transparency"
option (`improvedTransparency` in `options.txt`, false here). With it off `getTransparencyChain()`
returns null and `LevelRenderer` draws translucent terrain straight into the main target, so the
"six layers / depth copy" reasoning elsewhere in this entry applies to the shader path, not this
report. The attached recording also spends frames 1-42 and 539-638 in the pause menu; its gameplay
is frames 43-538, and the water interior over that span carries no large toggling patch, which is
consistent with the corruption being short-lived rather than a steady region. In-game confirmation
is still pending.

### How to reproduce it usefully

Fly through a water edge in creative while recording; a screenshot cannot show a one-second lag. A
frame sequence through the transition is what will show whether the water is drawn from the previous
frame's mask or geometry.

---

## BUG-022 — Sky light never reached the ground: the lightmap was stored mirrored

**Status:** **FIXED** (Phase 5) — **confirmed in game**. Torch light reads correctly in caves and
sky light reaches the ground.
**Severity:** critical for appearance. The whole world rendered at night-time brightness under a
daylight sky.
**Found on:** the first successful in-game run, from "the lighting from the sky doesn't seem to work
correctly" - a bright sky over a dark ground.

### Cause

Every pass except one carries Minecraft's Y convention in its projection matrix. `core/screenquad`
does not: it builds the full-screen triangle from `gl_VertexID` and sets `gl_Position = uv * 2 - 1`
directly. Vulkan's NDC `y = +1` is the **last** framebuffer row and Metal's is the **first**, so those
passes came out vertically mirrored whenever the projection could not compensate - and the atlas
viewport flip only covered targets whose label contains `/atlas/`.

The lightmap is one of those passes, and the mirror is exactly what the symptom looks like:

- `core/lightmap` writes a full sky level at `texCoord.y = 1`.
- Terrain samples it at `v = skyLevel / 256 + 0.5 / 16`, which for a full sky level is close to 1.
- `v = 0` is the first row of the data, so the ground was reading the row that holds level 0.

Daylight sky, night-time ground. Block light was mirrored onto the same axis, which is why torches
would have looked wrong too.

### Fix

`MetalRenderPassBackend.setPipeline` now flips the viewport for a pass whose pipeline uses
`minecraft:core/screenquad`, which is the same correction as the atlas path and for the same reason.
The two are kept exclusive, so a pass is never flipped twice. The native viewport call already flips
the front-face winding with a negative height.

This is general rather than a special case for the lightmap: every `screenquad` pass - the lightmap and
the whole post-processing chain - now matches Vulkan's orientation, and an odd-length post chain (a
single `invert`, say) was previously producing a mirrored image for the same reason.

### Verified

The offscreen check asserts the lightmap in **engine orientation** rather than shader orientation: no
light in the first row, sky light in the last, block light in the first column. Against the unfixed
build all four corners come back swapped, which is the bug reproduced exactly:

```
FAIL  no light at the first row, texCoord (0.03, 0.03) -> 64 128 255 (expected 0 0 0)
FAIL  sky light at the last row, texCoord (0.03, 0.97) -> 0 0 0 (expected 64 128 255)
FAIL  block light at texCoord (0.97, 0.03) -> 255 255 236 (expected 255 242 236)
FAIL  both lights at texCoord (0.97, 0.97) -> 255 242 236 (expected 255 255 255)
```

and all four pass after it. The earlier version of this check asserted the *shader's* orientation,
which passed happily while the game was wrong - a check written to agree with the code rather than
with the engine is worse than no check.

---

## BUG-021 — F3 said the backend was inactive while Metal was drawing every frame

**Status:** **FIXED** (Phase 5) — diagnostic only, no rendering effect.
**Severity:** low for play, high for anyone checking whether the backend is on.
**Found on:** the first successful in-game run, while reading the F3 overlay.

### Symptom

A session rendering correctly on Metal - terrain, sky, clouds, HUD, 62.9 fps, all health counters
zero - showed this as the first MetalMod line in F3:

```
[MetalMod] Pipeline: inactive (inactive (Vulkan interop not registered))
```

### Cause

`MetalModDebugEntry` reported `VulkanFrameManager.isPipelineActive()` under the bare label
`Pipeline`. That is the **MetalFX frame pipeline** - the upscaler - which is inactive by design in this
build because MetalMod does not own presentation. Nothing on the line said so, so the most prominent
status indicator in the game read as if the render backend were off, on a session where it was
demonstrably on.

The Metal backend's own state was only ever in the log (`Metal backend ENABLED`,
`presenting real frames on Metal`), which is not where anyone looks.

### Fix

The first line now answers the question it appears to answer, from the engine's own record rather than
from the mod's request flag:

```
[MetalMod] Backend: Metal (active)
[MetalMod] MetalFX: inactive (Vulkan interop not registered)
```

`RenderSystem.tryGetDevice().getDeviceInfo().backendName()` is what the engine selected, and MC's own
`Using graphics backend Metal` line is printed from the same field. The upscaler keeps its own line,
named for what it is.

---

## BUG-020 — Entering a world aborted: texel buffers were one row wide

**Status:** **FIXED** (Phase 5) — **confirmed in game**. The world loads normally.
**Severity:** critical. The first frame in a world killed the process.
**Found on:** the first in-game run of the Phase 5 fixes, build `424b111`.

### Symptom

Loading into a world aborted on the render thread with an assertion rather than a Java exception:

```
[Render thread/INFO]: Resizing Dynamic Transforms UBO, capacity limit of 4 reached during a single
frame. New capacity will be 8.
-[MTLTextureDescriptorInternal validateWithDevice:]:1421: failed assertion `Texture Descriptor
Validation
MTLTextureDescriptor has width (181818) greater than the maximum allowed size of 16384.'
```

### Cause

Two bugs in the texel-buffer emulation added for BUG-013, which re-expresses a buffer as a 2D texture
because Metal has no buffer textures.

**The width.** The texture was built `texels x 1`, so a 181818-byte cloud buffer asked for a
181818-wide texture. Metal's maximum texture dimension on this device is 16384, so the descriptor was
rejected and the process aborted. The cloud buffer is a few hundred bytes in a small scene, which is
why every offline check passed: this only appears once there are enough cloud quads to exceed the
limit, and the report's view distance of 32 was enough.

**The layout.** Metal has no buffer textures, so SPIRV-Cross emulates one as a 2D texture and emits

```metal
uint2 spvTexelBufferCoord(uint tc)
{
    return uint2(tc % 4096, tc / 4096);
}
```

with the width baked in as a literal. The generated texture therefore has to be exactly that wide -
a `texels x 1` texture reads the correct texel only while the index stays under 4096. The engine's own
cloud buffer holds three bytes per quad, so passing 4096 texels needs a few thousand cloud quads; the
small-buffer case hid it, and a second row was never exercised.

### Fix

`MetalShaderCompiler.TEXEL_BUFFER_WIDTH` is now set explicitly through
`SPVC_COMPILER_OPTION_MSL_TEXEL_BUFFER_TEXTURE_WIDTH` rather than inheriting SPIRV-Cross's default, so
the width the shader divides by and the width the texture is laid out with cannot drift. The value is
4096, which is SPIRV-Cross's own default, so every pipeline compiled before this still agrees with it.

`MetalDevice.texelTexture` lays the data out as `TEXEL_BUFFER_WIDTH x ceil(texels / width)` and uploads
it as whole rows plus a last partial row. Padding the tail to a full row would read past the end of
the backing buffer, which is a ring allocation the engine owns.

### Verified

`tools/render_check` now presents a **20000-texel** buffer as a texture - past 4096, so the second and
later rows are real, and past 16384, so a one-row texture could not be allocated at all. It asserts
the texture is 4096x5 and then reads every texel back and compares it with where
`spvTexelBufferCoord(tc)` would look for it: 20000/20000 match. Making the texture 2048 wide instead
fails both assertions at the first texel, so the check is sensitive to the width rather than merely to
the absence of an abort.

---

## BUG-019 — Post-processing passes were never precompiled, only skipped

**Status:** **FIXED** (Phase 5). The chain rendered anyway through the lazy path; what was missing
was the compile and the report.
**Severity:** low as it stood, high if the lazy path ever has no shader source - the whole chain
disappears without a word.
**Found by:** following the post-processing chain after the objective called it out, and reading what
`PostChain` actually calls.

### What the post-processing chain looks like

It is a separate shader space from the 87 `RenderPipeline`s the inventory covers, and it is not
shaped like anything else in it:

- `PostChain` builds each pass from `RenderPipelines.POST_PROCESSING_SNIPPET`, which declares **no
  colour target** (all eight entries null) and **no vertex format** (all sixteen null).
- The vertex shader is `core/screenquad`, which builds a full-screen triangle from `gl_VertexID`
  alone - no vertex buffer is bound for the draw.
- Each pass is precompiled through the **one-argument** `GpuDevice.precompilePipeline(pipeline)`,
  which passes a null `ShaderSource` straight through to the backend.
- The blur is six such passes (`box_blur` ping-ponging between `swap` and `minecraft:main`), and the
  same space holds `invert`, `creeper`, `spider`, `entity_outline` and `transparency`.

### Cause

`MetalDevice.precompilePipeline` returned early on a null source:

```java
// The engine announces some pipelines (the blur chain) without a ShaderSource. There is
// nothing to compile, and those passes are skipped by the draw path.
if (shaderSource == null) {
    return new MetalCompiledPipeline(true);
}
```

The comment is what made this worth chasing, and it is wrong: the draw path is not skipped.
`pipelineFor` compiles lazily from the most recent source, so the chain does render. But the
precompile did nothing, which means:

- a pass that cannot compile is reported at draw time, if at all, instead of when the chain is built;
- if no source has been seen yet - a post chain built before `ShaderManager` supplies one - then
  `pipelineFor` returns null and the pass is dropped with no diagnostic anywhere.

### Fix

A null source now resolves to the stored one, so post passes compile eagerly like everything else.
When there is genuinely no source at all the case is counted through
`reportResourceFailure`, which the telemetry reports, rather than being treated as ready. Both stale
comment blocks that described the passes as skipped were corrected.

### What was measured, not assumed

Two suspicions were tested and one was wrong, which is why the check exists:

- A null colour target becomes `MTLPixelFormatInvalid`, and Metal has no dynamic-rendering
  equivalent, so this looked certain to fail. It does not: Metal accepts the state in a pass whose
  colour attachment is `RGBA8_UNORM` and writes the target correctly. The check records that, so
  nobody "fixes" it into a per-target pipeline cache on the strength of the reasoning alone.
- `tools/render_check` now renders a real post pass: `core/screenquad` plus `post/blit`, three
  vertices and no vertex buffer, a white input texture and a red `ColorModulate`, into an `RGBA8`
  target. It asserts the pipeline compiled (eagerly now, as the log line shows) and that the target
  comes back red.

---

## BUG-018 — `maxColorAttachments` claimed 8, but the pipeline builds one

**Status:** **FIXED** (Phase 5). Latent for vanilla; would break a multi-target mod or shaderpack.
**Severity:** low for vanilla, high for anything that renders to more than one target.
**Found by:** auditing every value reported to the engine through `DeviceLimits`, `DeviceFeatures`
and `HintsAndWorkarounds` against what the backend actually implements.

### Cause

`DeviceInfo.limits().maxColorAttachments()` was reported as Metal's own 8, while
`MetalRenderPipeline` builds pipeline state from `pipeline.getColorTargetState()` - the **first**
target only. The render pass does accept a count and attaches all of them, so the pipeline is the
binding constraint.

`CommandEncoder.createRenderPass` checks the attachment count against that reported value and throws
when it is exceeded. Reporting 8 therefore removed the engine's only guard: a pass with two
attachments would be created, the pipeline would write the first, and the second would silently keep
its clear value. A wrong frame is worse than an error, which is the same reasoning that kept
`multiDrawIndirect` and `drawIndirect` false and that made `maxMultiDrawDirectInterleavedDrawCount`
worth correcting when 0 turned out to mean "calling `multiDrawIndexed` at all throws".

### Fix

The limit now comes from `MetalRenderPipeline.MAX_COLOR_ATTACHMENTS`, which is 1 and sits next to the
code that builds the single target - so the claim and the implementation cannot drift apart. Vanilla
is unaffected: the census showed no vanilla pipeline declares more than one colour target.

### Verified

`tools/render_check` walks every pipeline in `RenderPipelines` and asserts none declares more targets
than the reported limit - 87 checked. Pinning the constant alone would not catch a future pipeline
that needs two; the check names the offending pipeline when that happens, and the answer is to
implement multi-target pipelines rather than to raise the number. Confirmed failing (naming all 87) by
temporarily reporting 0.

---

---

## BUG-017 — A pipeline with no depth state inherited the previous one's

**Status:** **FIXED** (Phase 5) — **confirmed in game**. No z-fighting on water edges, item
frames or the hotbar.
**Severity:** high; 30 of the 87 vanilla pipelines declare no depth state.
**Found by:** asking what *else* is encoder state after finding BUG-016.

### Cause

`MMLRenderCommandEncoder`'s depth-stencil state persists between binds, exactly like the depth bias,
and `mmm_render_pass_set_pipeline` only called `setDepthStencilState` when the pipeline had one:

```objc
if (metalPipeline->depthStencilState) {
    [metalEncoder setDepthStencilState:...];
}
```

A pipeline that declares no depth state therefore kept the previously bound pipeline's compare
function *and its depth write*. Thirty of the 87 vanilla pipelines declare none - the whole GUI and
text family, the sky, the post-processing blits - so this is not a corner case. A no-depth pipeline
drawn after a depth-writing one would depth-test against geometry that should not occlude it, and
write depth that later draws should not see.

### Fix

The native side now owns a single `MTLDepthStencilState` with `MTLCompareFunctionAlways` and depth
writes off, and binds it for every pipeline that declares no depth state - so the state is always
explicit rather than inherited.

Binding that state in a render pass with no depth attachment is safe: the check below draws a
no-depth pipeline into a pass *with* depth, and all the GUI checks (which run in passes without one)
still pass, so Metal accepts both arrangements.

### Verified

`tools/render_check` now draws a full-screen quad at z = 1.0 with depth writing, then the same quad at
z = 0.5 through a pipeline that declares no depth state, into one pass with a D32_FLOAT attachment. If
the second pipeline is genuinely depth-free its colour wins; before the fix it inherited
`GREATER_THAN_OR_EQUAL`, was rejected, and the first colour survived. Confirmed failing before and
passing after.

---

## BUG-016 — Depth bias leaked from one pipeline to every later draw

**Status:** **FIXED** (Phase 5) — **confirmed in game**. Depth bias no longer leaks; no z-fighting
on coplanar surfaces.
**Severity:** high; five vanilla pipelines set a depth bias and they are mixed into the world pass.
**Found by:** auditing every pipeline state dimension against how Metal actually stores each one.

### Cause

Metal's depth bias lives on the **render command encoder**, not on the pipeline state, and
`mmm_render_pass_set_pipeline` only called `setDepthBias` for the non-zero case:

```objc
if (metalPipeline->depthBiasScale != 0.0f || metalPipeline->depthBiasConstant != 0.0f) {
    [metalEncoder setDepthBias:... slopeScale:... clamp:0.0f];
}
```

So the last biased pipeline's bias stayed in force for the rest of the pass. Five vanilla pipelines
bias - `CRUMBLING`, `TEXT_POLYGON_OFFSET`, `TEXT_GRAYSCALE_POLYGON_OFFSET`, `LINES_DEPTH_BIAS` and
`WORLD_BORDER` - and they are drawn among ordinary geometry, so everything after one of them was
pushed toward the viewer. That is the shape of a subtle full-scene depth error rather than an obvious
failure, which is why nothing had caught it.

### Fix

`setDepthBias` is now called unconditionally on every pipeline bind, including the zero case, which
restores Metal's default. The values themselves were already correct; only the reset was missing.

### Verified

Two pipelines are built over the same `core/gui` shader, one with an 8x slope-scaled bias and one
without, and both draw the same steeply sloped quad in one pass with the biased draw first. The
biased draw writes a depth pushed toward the viewer, so:

- if the second pipeline's bias was reset, its depth is below the stored one,
  `GREATER_THAN_OR_EQUAL` rejects it, and the first colour survives;
- if the bias leaked, the depths are equal, the test passes, and the second colour wins.

A **slope-scaled** bias is used rather than a constant one because Metal's constant term is in units
of the format's minimum resolvable difference: a constant of 10 - what vanilla uses - shifts the depth
by about ten float epsilons, far too little to tell the two cases apart. Confirmed failing before the
native fix and passing after.

---

---

## BUG-015 — `TRIANGLE_FAN` had no Metal primitive, so the sky disc was truncated

**Status:** **FIXED** (Phase 5) — **confirmed in game**. The sky renders as a full dome.
**Severity:** high for the sky; the sky disc is a single fan draw.
**Found by:** taking a census of every vanilla pipeline's `PrimitiveTopology`.

### Cause

`MetalFormat.mtlTopology` mapped `PrimitiveTopology.TRIANGLE_FAN` to `MTLPrimitiveTypeTriangle`. That
is the closest Metal has, but it is not the same shape: a triangle **list** over ten vertices draws
`(0,1,2)`, `(3,4,5)` and `(6,7,8)`, while a **fan** draws the eight wedges `(0,1,2)`, `(0,2,3)`, …,
`(0,8,9)`.

`SkyRenderer.renderSkyDisc` makes exactly one non-indexed call:

```java
pass.setPipeline(RenderPipelines.SKY);
pass.setVertexBuffer(0, this.topSkyBuffer.slice());
pass.draw(10, 1, 0, 0);
```

and `buildSkyDisc` fills those ten vertices with a centre plus nine rim points stepping `-180` to
`+180` - so the fan is the disc, and a triangle list over it is three stray triangles. `SKY` and
`SUNRISE_SUNSET` both use this topology. This is probably why the sky "looked right" in the original
report: with the disc missing, the fog/clear colour behind it is still sky-coloured.

### Fix

Metal has no fan primitive at all, so the fan is expanded into an **indexed triangle list**. A fan
over `v0..vN-1` is the triangles `(v0, vi, vi+1)` for `i` in `1..N-2`, which is exactly the prefix of
the index sequence `0,1,2, 0,2,3, 0,3,4, …`. One buffer holding that whole pattern therefore serves
every vertex count - the draw just takes the first `3*(N-2)` indices - and the fan's own start vertex
becomes Metal's `baseVertex`, so the pattern never has to be rewritten for a particular draw.

That last part matters. Every draw in a command buffer reads memory at execution time, not at encode
time, so a shared index buffer rewritten per draw would give every fan the pattern written last.
`mtlTopology` now returns a negative sentinel for `TRIANGLE_FAN`, and
`mmm_render_pass_draw_fan` draws from the cached, prefix-stable buffer. An *indexed* fan cannot be
expanded that way - its vertex order lives in the index buffer - so that case is reported in the
telemetry as `indexedFans=` rather than drawn plausibly-but-wrongly. No vanilla pipeline does it.

### Verified

`tools/render_check` now draws a ten-vertex fan through the real `SKY` pipeline - a centre plus nine
rim points spanning the full circle, which is the shape `buildSkyDisc` produces - and asserts that the
centre and all eight sampled points around it are covered while the corner is not. Reverting the native
expansion to the old triangle-list behaviour fails it: the centre goes to `R0` and only **1 of 8**
sample points is covered, which is the truncation described above.

---

## BUG-014 — `LINES` was drawn as line primitives instead of triangles

**Status:** **FIXED** (Phase 5) — **confirmed in game**. Block outlines and F3+G chunk border
lines are clean.
**Severity:** high; this is the block-selection outline of BUG-002, plus chunk borders and leash lines.
**Found by:** comparing every `PrimitiveTopology` mapping against the Vulkan backend's.

### Cause

`MetalFormat.mtlTopology` mapped `PrimitiveTopology.LINES` to `MTLPrimitiveTypeLine`, which reads
correctly and is wrong. Minecraft's `LINES` is not a line primitive:

- `rendertype_lines.vsh` offsets each vertex perpendicular to the segment by
  `+/- LineWidth / ScreenSize`, chosen by {@code gl_VertexID % 2}. Four vertices therefore make one
  segment's quad, and drawing them as lines pairs the indices into short perpendicular ticks.
  `SECONDARY_BLOCK_OUTLINE` - the block outline itself - is a `LINES` pipeline.
- `PrimitiveTopology.LINES` reports `indexCount(4) == 6`; `QUADS` is the only other topology that
  does. `BufferBuilder` also duplicates every vertex written to a `LINES` buffer, so the four
  vertices of a segment are start, start, end, end.
- `VulkanConst.toVk(PrimitiveTopology.LINES)` is `VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST`.

Real line primitives are `DEBUG_LINES` and `DEBUG_LINE_STRIP`, which keep their own mappings.

### Fix

`LINES` now maps to `MTLPrimitiveTypeTriangle`. `DEBUG_LINES` and `DEBUG_LINE_STRIP` are unchanged.

### Verified

The old render check drew two vertices with no index buffer and read the centre row, which passed
under either mapping because the two-vertex case happens to produce a line through the centre - so it
could not detect this. It now draws MC's real geometry (four vertices, six indices) and samples rows
4px above and below the centre. Reverting the mapping fails both: they come back `R0`, because line
primitives draw only the two vertical edges and one diagonal of the quad.

One trap is worth recording, because it cost a detour. The index pattern matters and it is **not** the
quad pattern: `BufferBuilder`'s vertex duplication puts the four corners in the zig-zag order
top-left, bottom-left, top-right, bottom-right, and `RenderSystem`'s `sharedSequentialLines` generator
emits `i, i+1, i+2, i+3, i+2, i+1` for it. The quad pattern `0,1,2,0,2,3` over that order leaves a
V-shaped hole through the middle of the band, which is what the check reported until it used MC's own
pattern.

---

## BUG-013 — Texel buffers had no binding path (vanilla clouds)

**Status:** **FIXED** (Phase 5) — **confirmed in game**. Clouds are visible and drifting.
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

**Status:** **FIXED** (Phase 5) — **confirmed in game**. F3 reads
`unbound/missingAttr/failed: 0/0/0` with no `unbound` log lines.
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

**Status:** **FIXED** (Phase 5) — **confirmed in game**. Terrain renders without snapping or
sections at the wrong offset.
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

**Status:** **FIXED** (Phase 5) — **confirmed in game**. Both paths were verified offline first;
terrain and entities render shaded rather than as flat black silhouettes.
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
