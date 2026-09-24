# Phase 7 — MetalFX

Status: **7A complete and verified offline (2026-09-25); 7B partially delivered; 7C not
implemented.** This document records what each increment actually delivers, what evidence backs it,
and what is missing - in the same form as [the Phase 6 plan](phase6-plan.md), because a phase that
reports "done" without naming its gaps is how a project accumulates claims it cannot support.

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
6. **F3 says what happened**: the `upscale` line's sizes, and `fx` counting up rather than `blit`.
   The settings page's own status line says the same thing without F3, which is what makes step 5 of
   the five-minute pass possible.

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
| Native temporal scaler | **Done and exercised.** `mmm_fx_temporal_*`, created in the smoke test for the backend's real formats, with `depthReversed = false` matching Minecraft's near-0/far-1 projection, and the reactive-mask and dynamic-resolution descriptor options |
| Projection jitter | **Done and verified.** `metalfx/ProjectionJitter` plus `CameraJitterMixin`: a mean-centred Halton (2,3) sequence, applied as a clip-space translation to `CameraRenderState.projectionMatrix`, gated off |
| History lifecycle | **Helpers implemented.** `requestReset` / `consumeReset` exist; live temporal event/reset integration remains to be completed and verified |
| Motion vectors | **Not implemented.** Nothing publishes them |
| The temporal path itself | **Not enabled.** A `temporal` request resolves to `spatial` |

### 3.2 Why the temporal path is gated rather than enabled

A temporal scaler reconstructs each output pixel from where the corresponding input pixels were in
previous frames. It cannot derive that itself: it needs a motion texture, and MetalFX's own
documentation is explicit that depth reprojection describes camera motion but not independently
moving objects.

Minecraft 26.2 publishes no motion vectors. Producing them means a new shader pass that reads the
level's depth and colour and writes an RG16F texture at the render resolution - and a *useful* one
needs per-draw model transforms as well, or every mob, particle and animated block tears along with
the camera. That is a rendering-pipeline addition, not a scaler integration, and Phase 8C already
owns the contract for it ("scene contracts... current/previous-frame transforms").

So 7B stops at the point where the next step stops being an integration:

- the scaler exists, is created with the right formats and depth convention, and is exercised;
- the jitter exists, is centred, is sub-pixel, and is verified as a sequence;
- history reset helpers exist, with live event/encode integration still outstanding;
- and the path is **off**, with `temporalEnabled()` as the single predicate both the jitter and the
  scaler selection read, so the two cannot disagree.

Enabling it without motion vectors would be worse than not having it: a temporally accumulated frame
with zero motion vectors ghosts the camera's own movement across the whole screen, which looks like a
broken renderer rather than an unfinished feature.

### 3.3 What the jitter verification covers

`./tools/scaling_check/run.sh` asserts that:

- every offset is sub-pixel (largest observed `0.46875`);
- the sixteen phases are distinct, so consecutive frames sample different positions;
- the offsets **sum to zero** over the cycle - the property that stops the image drifting. The raw
  Halton prefix does *not* have this property (it sums to `+0.47` on the base-2 axis over sixteen
  samples), which is why the implementation subtracts the prefix mean rather than assuming `0.5`;
- the clip-space conversion at 1600 pixels moves the image by exactly the requested number of pixels;
- a reset request is delivered exactly once.

### 3.4 What is needed to finish 7B — next priority

1. Produce render-resolution motion: camera reprojection from depth and current/previous transforms,
   plus previous transforms/animated positions for moving geometry. Bring forward the shared Phase
   8C scene contract; completing all of Phase 8 is not a prerequisite.
2. Integrate temporal resource allocation, scaler ownership, queue ordering and depth/motion/jitter
   inputs into the live world path. The existing Spatial caller does not provide this integration.
3. Wire history resets through actual encoding on camera cuts, world/dimension changes and resize.
   Existing reset helpers alone do not establish that these events are handled.
4. Validate transparency; if using a reactive mask, implement both its producer and encode binding.
5. Enable Temporal only with valid inputs, keep a supported fallback, and expose useful diagnostics.
6. Run applicable offline gates and in-game comparisons of native, Spatial and Temporal, including
   entities, particles, water, foliage, camera movement, disocclusion, HUD sharpness and frame cost.

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
        │  UPSCALE  →  native target                                                │
        │                                                                           │
        │  level post-effect chain (blur, invert, creeper, spider) at native        │
        │  interface → native target, native resolution, native depth               │
        └───────────────────────────────────────────────────────────────────────────┘
                                        present
```

| Question | Answer |
|---|---|
| What the scaler reads | The level target's colour, `RGBA8_UNORM`, with `USAGE_TEXTURE_BINDING` and `USAGE_RENDER_ATTACHMENT` (the usage bits MetalFX reports) |
| What it writes | The native target's colour view, same format |
| Resolution | Input `round(native * scale)`, output the native window size; both dimensions scale, so the aspect ratio never changes |
| Coordinates | Metal convention throughout; no flip is introduced by the upscale, and the level's Y convention is the one the backend already applies |
| Where it sits | **After** everything the level draws, including its post chains; **before** the interface |
| Post-processing | Phase 7A runs the level's post chains at the render resolution. Effects applied *after* upscaling (the level post-effect chain, the interface) are native |
| History ownership | The scaler's, and only its: nothing else reads or writes its history. A resolution change, a world change or a camera cut calls `ProjectionJitter.requestReset()` |
| Who owns the effect | The backend. A shaderpack or a future phase that brings its own temporal processing must declare it and turn this off, as the optional track's acceptance criteria state |
| Feature-off guarantee | Scale 1.0 allocates nothing, creates no scaler and leaves the engine's target in place |

---

## 6. Defects found and fixed during this phase

All three are recorded in [bug.md](../bug.md) with their symptoms and causes.

| # | Defect | Why it mattered |
|---|---|---|
| [BUG-026](../bug.md) | `RenderScaleSettings.active()` was built from a rounded dimension (`scaledSize(1) != 1`), which is true at every scale | The predicate answered "scaling is on" at 1.0, which would have allocated a second, identical target and routed the default frame through the scaling path. Found by the scaling check's own assertion; the predicate now tests the scale |
| [BUG-027](../bug.md) | The Halton jitter prefix does not average to zero (it sums to `+0.47` on the base-2 axis over sixteen samples) | The assumption "Halton is centred" is common and wrong for a finite prefix. An offset with a non-zero mean drifts the image; the prefix mean is now subtracted |
| [BUG-028](../bug.md) | The colour-and-depth split would have cleared the small target on a frame that draws no level | The main menu and the loading screen would have drawn over the previous frame's contents. Fixed by recording the engine's own "is a level being drawn" decision before the clear |

---

## 7. Phase 7 verdict

| Increment | Verdict |
|---|---|
| **7A — Spatial upscaling** | **Delivered and now exercised in game.** Render-resolution controls, the level's own target, MetalFX spatial upscaling at native HUD resolution, resize handling, a measured feature-off path, and a measured in-session gain of about 20% of the frame at 50% scale (13.5 ms against native's 16.9 ms). Quality against native is still unmeasured. |
| **7B — Temporal upscaling** | **Partially delivered.** Native scaler and jitter/reset helpers exist. Motion producers, live temporal encoding and event-driven history resets remain outstanding; bring forward the shared Phase 8C contract. The path remains gated off. |
| **7C — Frame generation** | **Not implemented.** Blocked on 7B's motion vectors and on frame-loop pacing; the pacing measurement is delivered as its groundwork, and the contract is recorded above. |

The roadmap's 7A exit criterion - "spatial and temporal modes render correctly through resizing and
history resets, with measured quality and performance" - is met for spatial in **performance** and
**resize**, and not met for temporal. **Quality is the remaining half**: nobody has compared the
upscaled image against native side by side, which is a visual judgement and needs a session. The
in-game numbers in §2.5 come from one spot in one world, so they establish that the feature pays
*here*, not a general figure.

The next implementation priority is **finishing 7B Temporal** (§3.4), bringing forward the shared
8C scene contract. Outstanding 7A visual checks remain required. Separate AA is optional after
Temporal evaluation, only if time remains (§3.5).
