# Phase 7B temporal gameplay contract v1

Temporal world reconstruction is implemented and selected by the in-game **Temporal Upscaling**
switch. Spatial remains available when the switch is Off. This replaces the jitter-only diagnostic:
there is no gameplay path that deliberately jitters spatial output. All changes after `6a18738`
remain uncommitted. Phase 7 release quality/performance selection is a separate acceptance decision.

## Resources and sampling

| Resource | Contract |
|---|---|
| Scene | Reduced RGBA8/BGRA8 perceptual SDR, decoded texel-exactly to linear RGBA16Float |
| Depth | Reduced Depth32Float, reversed Z, zero means sky/no geometry |
| Motion | RG16Float, previous minus current **unjittered** position in scene pixels; X right, Y down; scale factors 1 |
| Reactive | R8Unorm, 0 normal history, 1 reject; raster coverage and correspondence confidence are combined conservatively |
| Output | Native RGBA16Float private MetalFX output, converted to SDR before native hand/HUD composition |
| Exposure | Fixed 1, auto exposure disabled, no HDR/tone-mapping change |
| Sizes | Fixed per generation; device-reported ratios checked without silent preset clamping |

The copied world projection is translated in clip space by (-2*jx/width,+2*jy/height).
Geometry moves by -jitter, so pixel centres sample at +jitter. The compute pass reconstructs
unjittered coordinates using pixel+jitter and samples previous raster history at previous-jitter.
**MetalFX receives (-jx,-jy)**, the texture lookup offset needed to return that sampled texture to
the reference frame. The measured static-image regression caught and corrected the opposite sign:
centroid movement fell from 2.080 to 0.018 output pixels over a complete 16-sample cycle.
The raw `mmm_fx_temporal_encode` API accepts the MetalFX lookup offset directly; the complete
`mmm_fx_temporal_frame_encode` API accepts the projection's sampling offset and converts it.

Source matrices, extraction, picking and input stay unjittered. The cube/frustum test adds a
conservative two-scene-pixel margin. First-person hands and screen effects render at native
resolution without jitter, after world reconstruction and the native hand-depth clear. They do
not need fabricated world motion vectors. HUD and menus also stay native.

## Camera and independent objects

`TemporalSceneMotion` captures the actual composed world projection and extracted render-time
camera rotation/origin before jitter. It pairs previous/current camera-relative origins in double
precision before conversion to float. Previous-from-current clip reprojection supplies terrain
motion, with depth agreement rejecting disocclusion and invalid/behind-camera history. Previous depth zero
is always rejected, including far geometry with near-zero depth and matching sky colour.

Entity render states carry stable UUIDs. Positions and bounds come from extracted render-time
states; moving blocks use actual submitted poses and block/state identities. Only a successfully
submitted previous frame supplies an old identity. New/missing states, overlapping bounds, object
capacity overflow, invalid coordinates and incompatible depths reject history.

Independent object pixels apply previous-minus-current root translation. A depth-validated,
bounded ±4-scene-pixel rendered-colour patch search refines deformation/pose correspondence.
Ambiguous or unmatched patches reject history rather than claiming zero motion. This is a hybrid
render-state/appearance producer, not a per-vertex previous-skinning buffer. Deformation beyond the
search radius is conservatively rejected; scene acceptance must include those cases.

## Reactive coverage

Temporal scene passes get an internal R8 raster attachment. The instrumented fragment shader
preserves original colour, alpha, discard and depth behavior. Blended/transient fragments (particles,
water/translucency, weather, clouds, sky, glint, portals and screen-quad post effects) mark coverage
as reactive. MAX blending preserves rejection through transparent layers; opaque stable surfaces
overwrite occluded coverage. These variants compile for all 87 vanilla and 9 post pipelines,
including dynamic/clustered lighting variants. The public Blaze3D attachment contract stays intact.

Sky/no depth, disocclusion, changed opaque/animated appearance and uncertain object matches add
compute rejection. Changed/new/removed dynamic-light influence volumes also reject history using
Phase 6's existing published snapshot; its ABI and publication cadence are unchanged. Conservative
rejection can reduce temporal detail in transient regions; it avoids using unsupported history.

## Ordering, ownership and reset lifecycle

One ordered command buffer encodes SDR decode → motion/reactive inputs → MetalFX temporal → SDR
encode → current colour/depth history copies. Pending world/utility rendering precedes it. World
history is captured before vanilla clears depth for the hand. There is no per-frame GPU wait or
CPU texture readback. Completion retains native ownership and latches asynchronous errors.

Configuration/resource retirement waits only at transitions. First use, Off/On, algorithm/preset,
size/format/device changes, reload, world/dimension replacement and disconnect retire/restart
history. Camera cuts over eight blocks, large turns, incompatible projection changes, pause/resume,
missing rendered frames and gaps over 250 ms reset camera/object history and the Halton sequence.
Minimize/restore is covered by the elapsed render-gap policy. F3 reports reset count/reason and
object count. Native and strength-0 bypass perform no temporal work.

Unsupported creation fails to native rendering before scene jitter. Encode failure uses a
prevalidated plain reconstruction for that frame, then latches native fallback. Explicit changes
permit a retry; steady failed frames do not repeatedly create resources. The plain recovery is
not an accumulated temporal result and can contain one jittered frame. GPU errors are latched.

## Verification

Run `python3 scripts/verify_mod.py` for all five gates, lighting shader inventory, embedded-native
identity and production/add-on separation. Add `--runtime-source PATH --runtime-world FOLDER`
for automated copied-world gameplay. Native smoke executes camera/root/pose motion, transient
coverage, unknown objects, disocclusion, sky and static jitter stability on the actual GPU.
Pixel checks distinguish preserved world depth from cleared native hand depth and exercise bypass,
recovery, denial and repeated preset/resize transitions. The separate gameplay add-on checks
actual temporal/spatial/native modes, live UI persistence, hand/HUD composition, depth, turns,
teleport, FOV, render gaps, reload and generation stress, and saves image sequences.

Current evidence and acceptance limits: [gameplay results](temporal-gameplay/results.md).
