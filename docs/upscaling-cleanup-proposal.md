# Reconstruction of the upscaling implementation and cleanup proposal

2026-09-29. Analysis only; no rendering implementation changes are authorized by this proposal.
Target: keep 2560x1440 output as the working performance budget. No new 5K Temporal experiment.
Read alongside `upscaling-quality-audit.md` and the historical performance measurements.

Implementation note: the user subsequently authorized cleanup. The mode/dimension snapshot,
Off correction, paired clip-jitter fix, explicit target retirement, pacing/capability separation,
and diagnostic/reload isolation are implemented. All seven offline gates pass, scaling has 124
assertions, and the CPU jitter regression passes. The reconstruction below records the pre-cleanup
state; visual experiments and removal of the reload workaround remain deferred. See HANDOFF.md.

## What the current code actually does

### Window and settings

`MetalBackend` latches resolution synchronization before window creation. `WindowMixin` and
`MinecraftSurfaceResolutionMixin` rewrite the framebuffer-size queries and callback arguments.
This changes both Minecraft's main target and the Metal surface configuration. World scale is
a second multiplier applied to that already-selected output size. "Native" is therefore relative
to the effective output, not necessarily to the Retina backing store.

`RenderScaleSettings` resolves world scale and requested effect from session choices, launch flags,
then saved configuration. GUI edits are staged until leaving the page. Post-AA and resolution sync
use separate configuration accessors. `temporalOutputScale` is a launch-only static value.
F6 chooses the effect; when enabling an effect at 100%, it also changes the world scale to the
remembered scale or 50%. That couples effect comparison to resolution changes.

### One frame, in execution order

1. At `GameRenderer.extract` HEAD, clear current scene capture, apply a previously requested
   target change, and invalidate compiled pipelines if rebuilt.
2. Query capabilities **and** update pacing policy through `metalFxUsable`. That method uses
   previous frame state (`scaleThisFrame`, `frameHasLevel`) as part of its observations.
3. Choose whether this frame uses the scaled target, then record whether it has a world.
4. If Temporal is active, advance jitter and apply it during camera extraction. Capture the
   finished projection when `GameRenderer.renderLevel` uploads its projection buffer.
5. At `LevelRenderer.render` HEAD, request the current main-target dimensions, capture camera
   state, and enter the scope that substitutes the world target. A newly detected size change
   waits for a subsequent frame boundary; it is not rebuilt here.
6. Minecraft's frame graph renders the world and its internal world post chains into the selected
   target. At RETURN, leave the substitution scope and reconstruct into the true main target.
7. Optionally run world FXAA, then let later engine passes draw the hand/interface/post effects
   according to their own order. This hook is not a final filter over every game pixel.
8. Present the main image through the Metal surface; asynchronously completed drawable reports
   feed the next pacing observation.

The native-size path avoids the extra world target and MetalFX but still retains resolution
synchronization and the optional AA hook. Its jitter is disabled.

### Current mode table

| Request | World scale | Actual main path |
|---|---|---|
| Any effect | 100% | Main target directly; no Temporal, Spatial, or temporal AA |
| Spatial | Below 100% | Reduced world -> Spatial -> output |
| Temporal, healthy | Below 100% | Reduced world -> motion -> Temporal at world size -> Spatial -> output |
| Temporal, capability/pacing fallback | Below 100% | Reduced world -> Spatial -> output |
| Off | Below 100% | **Reduced world -> Spatial -> output**, despite "Off (native)" labels |
| Scaling unsupported | Below 100% | Main target directly at effective output size |

The Off discrepancy follows directly from the code: `metalFxUsable` distinguishes only Temporal
from every other request, `decideScalingForFrame` gates on capability/target size, and `upscale`
calls Spatial whenever Temporal is inactive. Neither the GUI's staged setting application nor F6
sets world scale to 100% when choosing Off. This is a code-path finding, not a new live capture.

## Which fixes to keep, isolate, or replace

| Existing mechanism | Evidence and limitation | Cleanup disposition |
|---|---|---|
| Frame-boundary replacement and per-frame scaling decision | Prevents stale-target overwrite and mid-frame destruction; BUG-031 | Keep invariant; express it in a single frame plan |
| Correct jitter units and centered Halton sequence | Conversion/sequence tested, but perspective placement is wrong; BUG-030/041 | Keep units/sequence; replace placement and matching motion removal together |
| Scene camera, entity/particle/pushed-block stamps | Native/scaling checks exercise their mechanics; silhouette/transparency quality remains unverified | Preserve contract and tests; do not replace with zero motion |
| MetalFX content region and synchronous initialization | Real omissions identified in BUG-035; performance experiments recorded | Keep |
| Drain before temporal scaler release | Reproduced native exit abort, BUG-033 | Keep until safe deferred destruction proves equivalent |
| Pacing fallback plus Spatial observation | Tests and prior live run support mitigation; not a reconstruction improvement | Separate policy from capability/encoding; keep behavior initially |
| Temporal at world size then Spatial | Performance benefit measured; different reconstruction grid | Name and report the chain explicitly; keep initially, do not assume best quality |
| Post-FXAA | Edge/flat tests pass; player quality dissatisfied | Keep optional/off by default; isolate placement from upscaler selection |
| Full resource reload plus pipeline invalidation on scale changes | F3+T symptom evidence; BUG-029 still says attempted/unconfirmed | Quarantine as compatibility behavior; diagnose before removing either |
| Retain every replaced target forever | Historical resize crash; ownership rationale unsupported by inspected frame-graph path | Replace with explicit owner and safe retirement after tests |
| Split temporal command buffers / skip motion / output override | Diagnostic experiments, not required production behavior | Move behind one diagnostic configuration; skip-motion must identify invalid-quality run |
| Off mode labels and stale linear-blit comments | Disagree with current execution | Define Off as native world rendering and test it |

Imported external targets are not internal pool allocations. In the real client bytecode,
`FrameGraphBuilder.importExternal` creates an `ExternalResource` holding the supplied object;
allocator acquire/release operations run on `InternalVirtualResource`. This does not establish that
every engine reference is gone at return, but contradicts the comment that import alone transfers
ownership to the pool. `retired` grows with replacements, including Temporal intermediate targets
that are not imported into the level graph. `close` drops references without `destroyBuffers`,
and the inspected `MetalTexture` has explicit native release in `close`, with no cleaner. The
proposal must provide a lifetime rule, not rely on Java garbage collection to release native handles.

Two additional paths need regression coverage rather than an immediate speculative fix:

- Temporal failure occurs after projection jitter has already been applied; same-frame Spatial
  fallback presents the jittered input without temporal accumulation. Next-frame policy and
  failure reporting must make this exceptional frame explicit.
- The reset flag is consumed before command-buffer creation/accepted encoding. An unsuccessful
  submission must not silently acknowledge a history reset. Inspect commit behavior as part of
  this test: the current Java path commits its command buffer even on an encode failure.

## Proposed structure

Keep the existing Blaze3D and native C ABI boundaries. Avoid rewriting the renderer.

1. **Resolution policy:** one immutable size record distinguishing logical window, backing
   framebuffer, output, world input, and Temporal output. All pixel conversions use these named
   sizes, never recompute from mutable settings in the middle of a frame. 2K remains the default.
2. **Frame planner:** snapshot settings, world presence, resource availability, capability, and
   performance policy into one immutable plan. It selects native, Spatial, or the explicit
   Temporal/Spatial chain and records requested mode, effective mode, and reason. Capability
   queries do not mutate pacing/history state. Thin mixins consume this same plan.
3. **Resource owner:** own world/intermediate/scaler/motion/AA resources as a coherent generation.
   Build at a boundary; keep the old generation until the engine/GPU no longer uses it; release
   every owned target once. Initially a bounded drain at changes is acceptable if it restores
   correctness; asynchronous retirement can follow later. Never add a per-frame drain.
4. **Temporal frame contract:** carry actual projection, pixel jitter, render dimensions, previous
   scene data, and reset generation together. Centralize apply/remove jitter helpers. Explicitly
   test composition with bob and portal effects; merely changing one `translate` call is insufficient.
5. **Pass execution:** encode the plan's selected chain; keep optional world AA separate. Preserve
   queue ordering and the rule that an unscaled frame cannot be overwritten by a stale world.

Move benchmarking/readback experiments out of the core coordinator where practical. Keep useful
telemetry, but derive it from the completed plan/result rather than overlapping boolean flags.
These are responsibility boundaries; they do not require five new public frameworks/classes.

## Sequence and acceptance criteria

| Step | Scope | Evidence required before proceeding |
|---|---|---|
| 1 | Add characterization checks before moving code | Off at reduced scale; mode transitions; dimensions; no-world frame; fallback; capture existing chains |
| 2 | Extract frame planning with current behavior | Same pixels/pass order for native, Spatial and Temporal chains; preserve known safety gates |
| 3 | Repair Off semantics and paired jitter contract as distinct changes | Off uses output-size world with no scaler; depth/FOV/phase jitter tests pass; motion/bob tests pass |
| 4 | Establish resource ownership/retirement | Repeated resize/scale/mode cycle has bounded live resources, no stale handles, clean teardown |
| 5 | Isolate reload workaround and diagnostics | Toggle sky/fog capture identifies stale state; remove only what controlled evidence makes redundant |
| 6 | Judge quality at fixed 2K output | Matched scene: native AA off/on, Spatial, current Temporal chain, direct 2K Temporal output; report frame time and shimmer/detail separately |

Step 6 is evaluation, not a predetermined architecture change. Temporal AA at 100% is a separate
possible feature and is not part of the behavior-preserving cleanup. Direct 5K Temporal is excluded.
Pre-upscale AA for Spatial is likewise a separate experiment after the frame contract is trustworthy.

The existing seven gates remain mandatory for implementation work. Supplement them with meaningful
projection, mode-selection, lifetime and nonuniform image checks; uniform-colour output alone
does not validate reconstruction quality. Add sustained moving thin-edge/cutout sequences against
a higher-sample offline reference where feasible, and use in-game motion captures for the real scene.

## Limits of this analysis

This pass inspected source, historical defect records, and the real client's resource bytecode.
It changed documentation only. It did not launch the game, change rendering code, alter play-instance
configuration, or install a JAR. The preceding audit passed all seven existing offline gates and
reproduced BUG-041 with a deliberately failing CPU regression. Those results are not claimed as
fresh runtime verification for this documentation-only proposal.
