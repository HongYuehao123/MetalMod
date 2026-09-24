# Anti-aliasing with MetalFX

Status: **plan; Temporal first, separate AA optional afterward.** This is the chosen work order.
The recorded 7A comparison (13.5 ms versus 16.9 ms at 50% scale) is evidence for that
scene, not a prediction of the cost of any AA implementation.

## 1. The problem and the input contract

MetalFX Spatial reconstructs a larger image from a single frame. It cannot recover subpixel
coverage or temporal samples that were never rendered. At 50% scale in each dimension, one
input pixel covers the area of four output pixels. The result can have both soft detail and
jagged edges; shimmering during movement is a further temporal stability problem.

Apple recommends antialiased, noise-free input for Spatial. Adding AA before it is therefore
compatible with its intended use, not inherently a competing filter. The current MetalMod world
path renders single-sampled and supplies no dedicated AA stage. Mipmaps help texture minification,
but do not antialias geometry silhouettes.

Source: [Apple — Boost performance with MetalFX Upscaling](https://developer.apple.com/videos/play/wwdc2022/10103/).

## 2. First priority — finish Phase 7B Temporal

MetalFX Temporal combines temporal antialiasing and upscaling. Jittered samples from successive
frames can improve edge quality, detail and stability. It replaces Spatial in this mode; a separate
FXAA/SMAA stage is not a prerequisite and should not be stacked ahead of it by default.

The native temporal scaler and projection-jitter helpers exist, and the live world path now runs
temporal end to end: a camera motion producer, the current/previous-transform contract, the encode
and the reset lifecycle are in `metalfx/SceneMotion`, `metalfx/WorldRenderTarget` and
`native/src/metalmod_motion.mm`. What remains is **per-object motion** and an in-game evaluation.

| Piece | State |
|---|---|
| Camera motion | **Done.** Depth is reprojected through the previous frame's view-projection; the conventions (camera-relative space, Metal's zero-to-one depth, unjittered matrices, pixel-space vectors) are each verified offline in the native smoke test and `tools/scaling_check` |
| Moving geometry | **Done for what the engine extracts.** Entities, particles and pushed blocks are stamped with their own previous positions by a native screen-space overlay, over a depth test that keeps a stamp off surfaces the object is not in front of. Geometry whose change is not a position - a texture animation - keeps the camera's answer, which for it is correct |
| Live temporal path | **Done.** Compatible depth/motion/output resources, scaler ownership, command ordering and fallback are wired into world rendering |
| Jitter and history | **Done.** The helpers reach a real encode; resets fire on resize, world/dimension changes and camera cuts, and the scaling check asserts the flag is delivered exactly once |
| Transparency | **Partly addressed, still unlooked at.** Particles carried a real velocity rather than a reactive mask, because their previous position is known and a mask only says "ignore history". Water and cutouts have not been examined under accumulation; an in-game pass is what settles them |
| Acceptance | **Remaining work.** Compare native, Spatial and Temporal at matching scenes and scales, stationary and moving. Check ghosting, disocclusion, shimmer, detail, HUD sharpness and frame cost. [TESTING.md](../TESTING.md) §6.D is the procedure |

Bring forward the current/previous-frame scene contract shared with Phase 8C. Completing all of
Phase 8 is not a prerequisite. Reuse the contract for later RT work, without assuming Temporal
is free or that its full integration has already been verified.

## 3. Optional afterward — separate AA for Spatial

After Temporal is implemented and evaluated, consider separate AA only if time remains and
there is a demonstrated need, such as improving the Spatial fallback. First identify the artifact:
geometric jaggies, cutout shimmer and general softness do not have identical remedies.

### FXAA / SMAA

A practical insertion point is the existing world-upscale boundary:

`Completed world colour → AA into a separate texture → MetalFX Spatial → native interface`

FXAA is the smallest prototype; spatial SMAA is another candidate with more implementation work.
These filters can smooth visible edges without motion vectors. They cannot recover missing samples
or fully solve temporal shimmer, and may soften texture detail. Quality and cost must be compared
against Spatial alone at identical scales; neither is inherently incompatible with Spatial.

If pursued, keep the filter optional, satisfy the scaler's input texture usage requirements,
preserve queue ordering, and leave HUD/text outside the filter. Do not read and write the same
texture in an ordinary fullscreen AA pass.

### MSAA

MSAA adds geometric coverage information before resolving to Spatial's single-sample input.
It is feasible in principle, but owning the level target does not automatically solve the engine's
multipass attachment and depth dependencies.

Required work includes sample-count-aware textures and pipeline cache keys, compatible colour/depth
attachments, resolve handling, and an audit of depth copies and subsequent sampled/post-process reads.
Resolve/store actions must preserve data needed by later passes. Settle those dependencies before
switching the live world target to MSAA.

**Cost correction:** ordinary MSAA does not inherently shade every sample separately. Apple describes
pixel shading once per triangle per pixel, with multiple coverage samples and efficient tile-based
resolves. Consequently, 4× MSAA at 50% scale does not imply native-resolution shading cost or loss of
the entire measured scaling gain. Memory, bandwidth, pass boundaries and edge density still matter;
measure the actual implementation. Nor is Temporal's additional cost known in advance.

Source: [Apple — Harness Apple GPUs with Metal](https://developer.apple.com/videos/play/wwdc2020/10602/).

MSAA helps geometric edges, but ordinary coverage sampling does not fix shader alpha-test boundaries
or texture aliasing. Foliage needs separate consideration, potentially including alpha-to-coverage.
If this route is later justified, first build an offscreen sample-count/pipeline/resolve experiment
comparing solid and cutout geometry, then evaluate depth and multipass integration. No MSAA prototype
is required before starting Temporal.

## 4. Verification and scope

For rendering implementation changes, run all applicable repository offline gates, including scaling
and mixin checks. Offline success is not proof of visual quality: use repeatable in-game comparisons
covering camera motion, moving entities, particles, water, foliage, resize and world transitions.
Record frame cost rather than extrapolating from sample count or the existing Spatial benchmark.

Frame generation and pacing do not replace edge antialiasing. Sharpening does not recover lost coverage,
and the separate sky-colour defect (BUG-029) needs its own verification. There is no commitment to
implement separate AA unless time and the post-Temporal evaluation justify it.
