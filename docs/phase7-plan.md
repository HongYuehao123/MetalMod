# Phase 7 — MetalFX

Status: **7A complete and verified offline (2026-09-25, awaiting one in-game run); 7B implemented and
verified offline, including a camera motion producer; 7C not implemented.** This document records what
each increment actually delivers, what evidence backs it, and what is missing - in the same form as
[the Phase 6 plan](phase6-plan.md), because a phase that reports "done" without naming its gaps is how
a project accumulates claims it cannot support.

Baseline reviewed: `280f3df`, Minecraft 26.2 client in `MetalMod_Test_26.2`, Apple M4 Pro, macOS 27.

---

## 1. Why this phase exists, and what owning presentation bought

The retired architecture ran MetalFX over MoltenVK's textures through `VK_EXT_metal_objects` and
could never work: MoltenVK presented *after* the hook ran, so anything MetalMod produced was
overwritten, and Minecraft's images were never created with `VkExportMetalObjectCreateInfoEXT`, so
`vkExportMetalObjectsEXT` could not return them at all.

Because MetalMod **is** the backend, all of that is gone by construction. A MetalFX effect is now an
ordinary pass over textures this project created, encoded on the queue this project owns, ahead of a
present this project issues. The only thing Phase 7 inherited from that history is a name: the
config controls that drove the retired pipeline were removed in Phase 5 and have been reintroduced
here as controls that do something.

---

## 2. 7A — Spatial upscaling

### 2.1 The problem that shaped the design

Minecraft renders the **level and the interface into one target** - `GameRenderer.mainRenderTarget()`
- and everything about the interface is expressed in that target's own dimensions: the GUI scale is
`framebufferWidth / guiScale`, every scissor rectangle is in framebuffer pixels, and text is laid out
against `window.getGuiScaledWidth()`. The pre-Phase-5 attempt at render scaling resized that target
and broke the GUI; it was reverted, and the roadmap recorded the reason: *"doing it properly means
rendering the world into its own target and upscaling that."*

7A does exactly that.

### 2.2 How it works

| Piece | Where | What it does |
|---|---|---|
| Scaled level target | `metalfx/WorldRenderTarget` | A second `MainTarget` at `round(native * scale)`, created and resized lazily |
| The redirect | `GameRendererScalingMixin` | `mainRenderTarget()` answers with the scaled target while the level renders, and with the engine's own at every other moment |
| The scope | `LevelRendererScalingMixin` | `LevelRenderer.render` HEAD/RETURN: enter the redirect, then upscale once the level's colour is complete |
| The opening clear | `GameRendererScalingMixin` | `clearColorAndDepthTextures` is split: colour to the level's target, depth to the native one |
| The upscale | `metalfx/MetalFxScaler` | MetalFX spatial scaler, run as one self-contained step on the device queue |
| The settings | `metalfx/RenderScaleSettings`, `MetalModUpscalingConfigScreen` | A control panel under **Options → MetalMod… → MetalFX Upscaling**: scale steps, the upscaler, an in-world change notice, and a live status line reporting the sizes, the path that ran, the frame count and any failure reason |
| The notice | `metalfx/UpscalingNotifier` | An engine toast when a change lands, naming the resolution the next frame will use |
| Diagnostics | `MetalModDebugEntry` | F3 line: sizes, path taken, frame counts, failure reason |

Three details are load-bearing and are commented at their sites:

1. **The depth clear must not follow the redirect.** The interface draws into the native target
   *after* the upscale and depth-tests against its depth buffer. Redirecting the clear would leave
   that buffer holding the previous frame's interface depth. Hence the split.
2. **The split is conditional on a level being drawn.** It happens before the engine decides, so the
   decision is made early in `GameRendererFrameMixin` using the engine's own three conditions. A frame
   with no level - menu, loading screen - takes the engine's clear unchanged.
3. **The scaled target is sized before the frame graph imports it.** `LevelRenderer.render` imports
   the target into a `FrameGraphBuilder` as an external resource; the handle captures the dimensions
   at that moment, so a target swapped in later would be rendered at the wrong size.

The feature-off path is the untouched one. At scale 1.0 no target is allocated, no scaler is created,
the redirect answers `null`, and the two added mixin calls are two branches.

### 2.3 What was verified

| Gate | Command | Result |
|---|---|---|
| Native MetalFX, device level | `./scripts/run_smoke.sh` | `ALL CHECKS PASSED`, including scaler creation, usage bits, a real upscale and readback |
| Frame shape, offscreen | `./tools/scaling_check/run.sh` | `SCALING CHECK PASSED` (47 checks) |
| Effect over a real draw | `./tools/render_check/run.sh` | `RENDER CHECK PASSED` (173 assertions), six of them MetalFX |
| Mixin injection points | `./tools/mixin_check/run.sh` | `MIXIN CHECK PASSED` (58 checks) |
| Build, shaders, standalone suite | `./scripts/build_mod.sh`, `./tools/shader_inventory/run.sh`, `StandaloneTestRunner` | all green |

The scaling check is the one that matters most: it drives the engine's **own** `MainTarget` and
`FrameGraphBuilder` - the same classes and the same `importExternal` call `LevelRenderer` makes -
against a Metal device, and asserts the level target receives the frame, the native target does not
until the upscale runs, and that the upscale then puts it there. It also covers the resize path, the
scaler being replaced rather than reused, and the release when the scale returns to 1.0.

### 2.4 What is *not* verified

**No in-game run.** Nothing in 7A has been looked at on screen. Every claim above is from offscreen
harnesses; the mixins are checked for *targets that exist*, not for *behaviour at runtime*. What an
in-game session has to confirm:

1. **The GUI does not move.** This is the whole reason for the design, and the only observation that
   settles it: at 50% scale the HUD, tooltips and menus must be pixel-identical to 100%.
2. **The world is upscaled, not stretched or misaligned.** Look at a block edge and the horizon.
3. **No `Scissor ... out of bounds` and no `lighting variant` regression** in the log.
4. **A window resize at scale 0.5**, including a fullscreen toggle.
5. **A GUI-only frame** (open the pause menu over the world) - the case the conditional clear split
   exists for.
6. **F3 says what happened**: the `upscale` line's sizes and which effect ran, and - when temporal is
   selected - the `motion` line's dispatched count and reset reasons. The settings page's own status
   line says the same thing without F3, which is what makes step 5 of the five-minute pass possible.

[TESTING.md](../TESTING.md) §6.E is the five-minute version of the list above; step 6 there - the HUD
at 50%, which must be as sharp as at 100% - is the one observation that decides the increment.

Until those are done, the honest statement is *"implemented and verified offline"*, not *"works"*.

### 2.5 What 7A does not do

- **No HUD scaling problem is introduced** - the HUD stays native - but the *world* is genuinely
  lower resolution, so at 50% the terrain is softer. That is the trade the setting offers.
- **No sharpening filter.** MetalFX spatial has no sharpness control; the setting is on or off.
- **Quality is unmeasured; performance is measured and positive.** The roadmap asks for "measured
  quality and performance against native-resolution rendering". The performance half is now done, in
  a real session at 5120x2664: **50% + MetalFX spatial runs 13.5 ms / 74 fps against native's
  16.9 ms / 61 fps** at the same spot, with the drawable wait falling from 8.5 ms to 4.7 ms. Render
  scaling saves about 20% of the frame in a full scene, and the upscale is inside the measurement's
  noise ([TESTING.md](../TESTING.md) §6.D3).

  An earlier offscreen measurement said the opposite - a full-target fill cost only 0.35 ms at 75%
  scale, and the upscale into 5K cost 1.5 ms, which made scaling look like a pessimisation. The
  offscreen fill was measuring the wrong thing: a clear is not a terrain pass, and a scene heavy in
  overdraw costs far more per pixel. The correction is recorded rather than quietly dropped, because
  the proxy was plausible and it pointed the wrong way; the in-scene comparison is the number that
  counts, and F10 is what makes it repeatable.

  Quality is measured enough to have found a defect: at 50% scale the sky comes out brighter and less
  saturated than at native (sky-right `129 169 255` against `192 216 255`, with red unchanged so it
  reads as grey). The scaler itself is cleared - flat colours come through with delta 0 and both a
  smooth and a dithered ramp keep their mean to within 0.4 - so the cause is resolution-dependent
  rendering before the upscale, not the effect. Recorded as [BUG-029](../bug.md) with the
  reproduction and the next step.
- **The present path still converts formats.** The `CAMetalLayer` keeps its BGRA8 format while the
  main target is RGBA8, so the present remains a full-screen fragment shader rather than a copy-engine
  blit. Unchanged from Phase 5, and still the first thing to fix if present cost ever matters.

---

## 3. 7B — Temporal upscaling

### 3.1 What is delivered

| Piece | State |
|---|---|
| Native temporal scaler | **Done and exercised.** `mmm_fx_temporal_*`, created for the backend's real formats - `RGBA8` colour, `Depth32Float` depth, `RG16Float` motion, `RGBA8` output - with `depthReversed = false` matching Minecraft's near-0/far-1 projection, and the reactive-mask and dynamic-resolution descriptor options available. Dynamic resolution is reported as unsupported on this machine and is left off. |
| Camera motion (depth reprojection) | **Done and verified.** `native/src/metalmod_motion.mm` plus the `mmm_motion_*` surface: one Metal compute kernel that reconstructs each pixel's camera-relative world position from the level depth and reprojects it through the previous frame's view-projection, writing an `RG16Float` motion texture at the render resolution. |
| Object motion (screen-space overlay) | **Done and verified.** The same resource also carries a set of screen-space stamps - a box, a pixel motion and a clip-depth range each - drawn over the dispatch's result by a second native pass. Entities, particles and pushed blocks supply them, because the depth buffer holds only the current frame and cannot say where they were. |
| The scene contract | **Done.** `metalfx/SceneMotion` captures the current and previous finished projection matrix, the camera's view rotation and position, the frame's jitter, and the moving objects the engine extracts. Shared with Phase 8C. |
| Live temporal path | **Done.** `WorldRenderTarget.upscaleTemporal` creates the motion resource, builds the matrices and the stamps, dispatches the motion pass and encodes the scaler on the device queue, in that order, between the level's passes and the interface. |
| Projection jitter | **Done, and corrected.** The mean-centred Halton (2,3) sequence advances per temporal frame. The clip-space conversion now uses the *render* target's size rather than the window's, which is [BUG-030](../bug.md). |
| History lifecycle | **Done.** `requestReset`/`consumeReset` reach a real encode; resets are wired to camera cuts, world/dimension changes and every target rebuild or resize. |
| Transparency / reactive mask | **Not used, and no longer needed for the case that asked for it.** Particles were the reason a reactive mask was on the table - they write no depth, so the depth producer gives their pixels the background's motion. Their own previous position is available, so they carry a real velocity instead, which is strictly better than telling the scaler to ignore history there. A mask remains the tool for a future surface whose motion genuinely cannot be known. |
| Moving geometry | **Done for the moving geometry the engine extracts.** Entities (via `EntityRenderDispatcher.extractEntity`), particles (via `SingleQuadParticle.extractRotatedQuad`) and pushed blocks (via `PistonHeadRenderer.extractRenderState`) are stamped with their own previous positions. What is not covered is geometry whose change is not a position - a texture animation, or a block that changes shape in place - and those keep the camera's answer, which for them is correct. |

### 3.2 How the motion is produced, and what it deliberately does not claim

MetalFX needs a motion texture and documents that depth reprojection on its own describes camera
motion but not independently moving objects. This increment produces the camera half exactly: the
level's depth is turned back into a camera-relative world position by inverting the current
view-projection, and that position is reprojected with the previous frame's view-projection - which
also carries the camera's own displacement between the two frames, so no world coordinate is ever
materialised in a float.

Three conventions are load-bearing, and each has a check:

- **Camera-relative space.** Minecraft's level shaders build a camera-relative position and multiply it
  by a rotation-only view, so the matrix the kernel inverts is `projection * viewRotation`, not a
  world-space view-projection.
- **Unjittered.** The descriptor's `jitteredMotionVectors` option is off, so the jitter the projection
  was given is removed before the matrices are built - for the *previous* frame as well as this one, or
  a still camera produces motion equal to the change in jitter phase. The offset itself is passed to
  the scaler in input-texture pixels, which is now the unit the projection was actually jittered in.
- **Metal's depth convention.** The backend reports `isZZeroToOne`, so the stored depth is the
  clip-space z directly and a depth of exactly 1.0 is the far plane, which is given zero motion rather
  than an arbitrary reprojection of a point at infinity.

The object half is a different mechanism, because the information is a different kind. Depth cannot
say where a mob was; the engine can, because it interpolates every rendered position between two
ticks. So the objects the engine extracts are captured at their extraction hooks and drawn over the
depth-derived field:

- **An entity** carries a bounding box and a world position, and its identity is its entity id, so the
  previous frame's position is an exact lookup. Its depth *is* in the depth buffer, so the stamp only
  has to correct the difference between where it is and where it was, and a stamp below the jitter is
  skipped.
- **A particle** is the opposite case: drawn with a depth test and no depth write, so the
  depth-derived motion at its pixels belongs to whatever is behind it and is wrong for the particle
  even when the particle is standing still. Every particle is therefore stamped, and the previous
  position is kept on the particle itself by the extraction hook rather than re-derived from its tick
  fields - a tick lands between two rendered frames one frame in three at sixty frames per second.
- **A pushed block** is a full cube whose block position never changes while the piston's interpolated
  offset carries it, which makes the identity and the movement exact and separate.

The stamps are drawn as instanced screen-space quads with the depth buffer bound to the fragment
stage, and a pixel is only overwritten when the depth read there falls inside the object's own clip
depth range. That is what keeps a mob standing behind a wall from taking the wall's pixels with it,
and it is checked directly: the scaling harness stamps an entity whose depth range contradicts the
scene depth and asserts that the frame is untouched.

### 3.3 What was verified

| Gate | Command | Result |
|---|---|---|
| Native motion kernel and overlay | `./scripts/run_smoke.sh` | `ALL CHECKS PASSED`, including twenty motion assertions: an exact convention check (a one-NDC-unit shift is exactly half the texture in pixels, with y down), an unmoved camera producing zero motion on a perspective projection, uniform motion on a flat depth plane scaling as one over view depth with the documented sign, zero motion at the far plane, a mismatched depth size being refused, a stamp replacing the depth-derived motion exactly inside its box, a stamp the depth test rejects changing nothing, and the stamp table being clamped and counted rather than overrun. |
| Frame shape and the live path | `./tools/scaling_check/run.sh` | `SCALING CHECK PASSED` (91 assertions), of which twenty-six are temporal or its cost: the resource is sized to the render resolution, the frame runs temporally, the scaler's output reaches the native target, a still camera gives zero motion, a moved camera gives uniform signed motion, **different jitter phases with a still camera still give zero motion**, an entity with no previous position contributing nothing, an entity's own movement being stamped while the rest of the frame keeps the depth-derived answer, **a stamp the depth buffer contradicts being rejected**, particles and pushed blocks being stamped, **two entities moving apart keeping their own motions** and a horizontal move carrying no vertical component, a first frame and a camera cut each requesting a reset while a continuous camera does not, and a resize keeping the path running. |
| Mixin injection points | `./tools/mixin_check/run.sh` | `MIXIN CHECK PASSED` (72 checks), including `GameRendererProjectionMixin`, `EntityMotionMixin`, `ParticleMotionMixin` and `PistonMotionMixin` with their exact targets and descriptors. |
| Build, shaders, render check, standalone suite | `./scripts/build_mod.sh`, `./tools/shader_inventory/run.sh`, `./tools/render_check/run.sh`, `StandaloneTestRunner` | all green. |

### 3.4 What is not verified, and what remains

1. **No in-game run of temporal.** The same caveat as 7A applies: the mixins are checked for targets
   that exist, not for behaviour at runtime. A session has to confirm that a moving camera produces a
   stable image, that turning the camera does not smear, that a dimension change does not blend two
   worlds, and that the frame cost is what it is.
2. **No in-game confirmation of the object path either.** The stamps are verified against synthetic
   positions in the harness and their mixins are verified to resolve; whether a real mob's silhouette
   is covered tightly enough by its bounding box, and whether a piston's two moving halves are both
   inside one stamp, are observations only a session can make.
3. **No reactive mask.** The scaler can be told per pixel to favour the current frame where its motion
   is unreliable, but the mask needs a producer - per-object identity or coverage - and the render
   state this engine exposes has no such identifier. Enabling the descriptor option without the mask
   would be a claim the code cannot back, so it stays off.
4. **Transparency is unvalidated as a temporal case.** Water, particles and cutouts render correctly in
   the spatial path's pixel checks; what they do under accumulation has not been looked at.
5. **The temporal cost is measured offscreen, not in a scene.** The scaling check times the whole
   upscale step with the queue drained after each iteration, at 1280x666 -> 2560x1332, and times the
   spatial path at the same sizes in the same run:

   | Path | Mean |
   |---|---:|
   | MetalFX spatial | 0.919 ms |
   | MetalFX temporal | 2.240 ms |
   | motion dispatch + overlay + temporal reconstruction | 1.321 ms |

   Against the measured 13.5 ms scaled frame at 50%, that is about a tenth of the frame for the
   temporal path - real, and well inside it. What offscreen cannot say is how the cost behaves in a
   scene with hundreds of moving objects and a rain shower, which is what `TESTING.md` §6.D step 10
   is for. The number is the effect's floor, not a scene's.

### 3.5 Anti-aliasing — optional separate work after Temporal

The chosen order is **Temporal first, separate AA only afterward if time remains**. Temporal already
combines antialiasing and upscaling. [The anti-aliasing plan](antialiasing-plan.md) records the inputs,
integration work and corrected alternatives.

Apple recommends antialiased input for Spatial: a pre-upscale FXAA/SMAA filter is a valid optional
experiment for that path, with softness and motion stability assessed visually. MSAA adds coverage
information but needs compatible attachments, resolves and depth handling. It does not inherently
run fragment shading per sample, so its cost cannot be inferred to consume the measured scaling gain.
Neither alternative is a prerequisite for Temporal, and no extra AA stage is planned ahead of Temporal.

## 4. 7C — Frame generation

**Not implemented.** Recording the contract rather than a stub, because the blockers are structural
and a stub would misrepresent them.

MetalFX frame interpolation needs three things this renderer does not currently have:

1. **Motion vectors**, the same ones 7B needs and does not have. Interpolation is *entirely* motion:
   without them there is nothing to interpolate between.
2. **Two drawables per game frame.** Delivering an interpolated frame means presenting twice per
   rendered frame, which means the `CAMetalLayer`'s drawable pool and the game's frame loop have to
   agree about pacing instead of the loop simply blocking in `nextDrawable`.
3. **A pacer.** `CAMetalDisplayLink` is the mechanism, and what is delivered is where Phase 7C
   starts.

What *is* delivered is the measurement that pacer will be validated against, because it is useful on
its own and it is the thing that cannot be added later without re-running every comparison:
`mmm_present_note` reads each drawable's `presentedTime` and classifies the intervals, and F3 reports
the last interval, its p95 over a rolling window, the share of intervals that did not wait for an
extra refresh, and the dropped count. A CPU frame timer says when a frame was *submitted*; this says
when it was *shown*, which is the only evidence that interpolation helped.

Frame generation is independently validated by the roadmap and is not an algorithmic prerequisite for
ray tracing, so leaving it here does not block Phase 8 or 9.

---

## 5. The integration contract

The roadmap asks Phase 7 to publish where upscaling sits relative to post-processing and HUD
composition, so Phases 8–9 can compose against it. This is that contract, as implemented:

```
        ┌─────────────────────────── GameRenderer.render ───────────────────────────┐
        │ opening clear: colour → level target, depth → native target               │
        │                                                                           │
        │  ┌── LevelRenderer.render (redirect active) ──────────────────────────┐    │
        │  │ frame graph at the RENDER resolution:                              │    │
        │  │   sky → main → clouds → weather → always-on-top                    │    │
        │  │   translucency post-chain, entity-outline post-chain               │    │
        │  └────────────────────────────────────────────────────────────────────┘    │
        │                                                                           │
        │  MOTION (temporal only)  depth + previous view-projection → RG16F          │
        │  UPSCALE  →  native target                                                │
        │                                                                           │
        │  level post-effect chain (blur, invert, creeper, spider) at native        │
        │  interface → native target, native resolution, native depth               │
        └───────────────────────────────────────────────────────────────────────────┘
                                        present
```

| Question | Answer |
|---|---|
| What the scaler reads | Spatial: the level target's colour, `RGBA8_UNORM`, with `USAGE_TEXTURE_BINDING` and `USAGE_RENDER_ATTACHMENT` (the usage bits MetalFX reports). Temporal adds the level target's `Depth32Float` depth and an `RG16Float` motion texture at the render resolution |
| What it writes | The native target's colour view, same format |
| Resolution | Input `round(native * scale)`, output the native window size; both dimensions scale, so the aspect ratio never changes |
| Coordinates | Metal convention throughout; no flip is introduced by the upscale, and the level's Y convention is the one the backend already applies |
| Where it sits | **After** everything the level draws, including its post chains and - when temporal is on - the motion dispatch; **before** the interface |
| Motion ownership | `SceneMotion`: the native kernel, the current/previous matrices and the camera history. `WorldRenderTarget` owns the resource's lifetime and the queue order; nothing else reads the motion texture |
| Post-processing | Phase 7A runs the level's post chains at the render resolution. Effects applied *after* upscaling (the level post-effect chain, the interface) are native |
| History ownership | The scaler's, and only its: nothing else reads or writes its history. A resolution change, a world change or a camera cut calls `ProjectionJitter.requestReset()`, which the next temporal encode consumes exactly once |
| Who owns the effect | The backend. A shaderpack or a future phase that brings its own temporal processing must declare it and turn this off, as the optional track's acceptance criteria state |
| Feature-off guarantee | Scale 1.0 allocates nothing, creates no scaler and leaves the engine's target in place |
| Fallback | Temporal unable to run - unsupported device, no motion producer, unreadable depth, or three consecutive failures - runs Spatial for the frame and states the reason on F3 and the settings page. Spatial unable to run renders natively |

---

## 6. Defects found and fixed during this phase

All eight are recorded in [bug.md](../bug.md) with their symptoms and causes. BUG-029, the sky-colour
shift at a render scale below 100%, is a ninth and is still open.

| # | Defect | Why it mattered |
|---|---|---|
| [BUG-026](../bug.md) | `RenderScaleSettings.active()` was built from a rounded dimension (`scaledSize(1) != 1`), which is true at every scale | The predicate answered "scaling is on" at 1.0, which would have allocated a second, identical target and routed the default frame through the scaling path. Found by the scaling check's own assertion; the predicate now tests the scale |
| [BUG-027](../bug.md) | The Halton jitter prefix does not average to zero (it sums to `+0.47` on the base-2 axis over sixteen samples) | The assumption "Halton is centred" is common and wrong for a finite prefix. An offset with a non-zero mean drifts the image; the prefix mean is now subtracted |
| [BUG-028](../bug.md) | The colour-and-depth split would have cleared the small target on a frame that draws no level | The main menu and the loading screen would have drawn over the previous frame's contents. Fixed by recording the engine's own "is a level being drawn" decision before the clear |
| [BUG-030](../bug.md) | The projection jitter was converted against the *window* size while the level renders at a fraction of it | The scaler would have been told an offset in input pixels that the frame was not jittered by. Found while building the motion matrices; the conversion now uses the same function that sizes the level target |
| [BUG-031](../bug.md) | `upscale` did not consult the frame's "am I scaling" decision, so it could write the stale level target over a frame that had rendered natively | Found by reading the class's own once-per-frame invariant against the code. The redirect and the upscale now agree about which frame they are |
| [BUG-032](../bug.md) | MetalFX documents its output texture as private-storage; this backend hands it a shared one | **Open.** The pixels are verified correct offscreen and the requirement is not enforced in release builds, but the contract is not met. Recorded rather than ignored |
| [BUG-033](../bug.md) | Releasing the MetalFX temporal scaler while its own encoded work was in flight aborted the JVM at exit | It happened after every check had passed, and a pipe had been hiding the exit code, so the gate looked green. Found by checking the scaling check's own exit status rather than a `tail` of its output |
| [BUG-034](../bug.md) | The object history stored a box's minimum y while the stamp builder projected its centre | Every entity carried a spurious downward vector of half its own height - 12.85 px for a player at five blocks. Found by the check that moves two entities in opposite directions; the single-object checks only ever looked at x |

---

## 7. Phase 7 verdict

| Increment | Verdict |
|---|---|
| **7A — Spatial upscaling** | **Delivered and now exercised in game.** Render-resolution controls, the level's own target, MetalFX spatial upscaling at native HUD resolution, resize handling, a measured feature-off path, and a measured in-session gain of about 20% of the frame at 50% scale (13.5 ms against native's 16.9 ms). Quality against native is still unmeasured. |
| **7B — Temporal upscaling** | **Implemented and verified offline, with a camera motion producer.** The native kernel, the scene contract, the live encode, the jitter (corrected to render pixels) and the reset lifecycle are all in place and covered by the smoke and scaling checks. It is enabled as a setting, falls back to Spatial with a stated reason when it cannot run, and reports its own state on F3 and the settings page. The named remainder is **moving geometry**: independently moving objects reproject as if static and ghost, because the per-object previous transforms are Phase 8C's contract. No in-game run and no measured temporal cost yet. |
| **7C — Frame generation** | **Not implemented.** Blocked on 7B's per-object motion vectors and on frame-loop pacing; the pacing measurement is delivered as its groundwork, and the contract is recorded above. |

The roadmap's 7A exit criterion - "spatial and temporal modes render correctly through resizing and
history resets, with measured quality and performance" - is met for spatial in **performance** and
**resize**, and for temporal in **resize**, **history resets** and the offline correctness of its
motion source. It is not met for temporal quality, which needs a session, and moving geometry is a
stated gap rather than a completed item. The in-game numbers in §2.5 come from one spot in one world,
so they establish that the feature pays *here*, not a general figure.

The next implementation priorities are the two visual checks 7A still owes (§2.4), then an in-game
evaluation of Temporal, and then **per-object motion for moving geometry** - bringing forward the
shared 8C previous-transform contract, which is now the only thing standing between this motion source
and a complete one. Separate AA remains optional after that, only if the evaluation justifies it
(§3.5), and 7C remains blocked on the same motion vectors plus a pacer.
