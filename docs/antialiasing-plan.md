# Anti-aliasing with MetalFX

Status (2026-09-29): **Spatial distant-forest aliasing is the first priority (BUG-044).** The user
reports unacceptable output that final FXAA does not solve. Temporal is near native in the user's
assessment, with edge softness remaining as a secondary issue. 2K remains the performance target;
the separate native quality gap (BUG-040) remains open.

## Current priority: Spatial foliage reconstruction

At a fixed forest view, record actual output/world dimensions, effective scaler, mipmap settings,
resource pack and AA state. Compare native 2K, the reduced world input, Spatial output with AA off,
and the final AA result. Repeat with slight camera movement to separate static aliasing from shimmer.
This must establish whether the world input already loses cutout coverage/detail, or the scaler and
final filtering introduce or amplify the objectionable pattern.

Investigate mip/LOD and alpha-cutout sampling/coverage, world scale, and input antialiasing based on
that comparison. These are hypotheses, not identified causes. A final-image edge blend cannot be the
acceptance test for a distant forest. Retain detail and reduce aliasing/shimmer within the 2K budget;
do not replace the defect with broad blur or assume more sharpening repairs missing samples.
The next work is input/reconstruction diagnosis, not another post-FXAA parameter adjustment.

Do not assume ordinary MSAA fixes leaf alpha-test boundaries, or choose an AA architecture before
isolating the failure. No new 5K Temporal benchmark is part of this priority. Temporal remains a
comparison/reference mode; its softness needs separate stage isolation rather than an assumption
that MetalFX intrinsically fails on scenes with many edges.

The sections below retain prior implementation history. Their earlier Temporal-first ordering is
superseded by this priority.

The recorded 7A comparison (13.5 ms versus 16.9 ms at 50% scale) is evidence for that
scene, not a prediction of the cost of any AA implementation.

## Implemented final-image pass

The user observed jagged edges at native scale and after both upscalers. The implemented pass
runs **after** the world reaches its final output size and **before** the interface:

`World at native size, or MetalFX output → FXAA scratch → main target → HUD`

This location covers all three rendering modes with one pass and leaves HUD glyphs sharp. The
shader detects strong local luminance gradients and blends samples along the edge; flat regions
are returned unchanged. Its own pixel test confirms a diagonal edge gains intermediate values,
flat corners and alpha survive, and in-place sampling is rejected. The scaling check verifies the
pass at 100% render scale and the off switch. The 2560x1440 cloned cave run with VSync on measured
58.6/59.7 fps for turning/final view with FXAA, against 58.9/59.8 fps without it. The pass also ran
in a live 100% render-scale game. Use the Upscaling page or `-Dmetalmod.postAA=false` to compare.

FXAA does not add geometric coverage samples and may soften fine detail. The host's window capture
failed, so a player still needs to judge its image quality on foliage, text-like textures, and
moving edges. A pre-Spatial input AA pass remains an option if final-image FXAA alone is insufficient.

The later native-mode report changed the diagnosis: the unmodified game and old MetalMod builds
were clean at the same fullscreen setting, while the newer native Metal image was aliased even with
FXAA. The recent resolution-sync feature changed the actual drawable from the 5120x2880 Retina
backing to the 2560x1440 logical window. That discards four raster pixels for every panel pixel
group before FXAA can run. The user chose to keep the 2K drawable as the default; Retina 5K is
available as a comparison option and FXAA remains off by default. A cloned 5K native run at the reported surface
coordinates held roughly 60 fps, but macOS denied window capture, so visual parity needs a new
player check. Efficient 2K antialiasing is still a separate rendering problem.

The player's follow-up screenshot showed distant foliage soft at 75% world resolution, with
Temporal requested but Spatial running because the pacing guard had latched. The guard now restores
Temporal if a ten-second Spatial comparison misses the same refresh threshold. The final FXAA
filter also uses a stronger edge threshold, a shorter search and a 65% edge blend to avoid treating
small foliage texture gradients as silhouettes. A dense-scene trial of 75% input with full-size or
90%-size Temporal output fell to roughly 55–57 fps once the view filled with geometry, so the
render-sized Temporal plus Spatial finish remains the default. A post filter cannot reconstruct
details that the 1920x1080 world render never sampled; 100% world scale is the sharpest option
when the scene's frame budget permits it.

## 1. The problem and the input contract

MetalFX Spatial reconstructs a larger image from a single frame. It cannot recover subpixel
coverage or temporal samples that were never rendered. At 50% scale in each dimension, one
input pixel covers the area of four output pixels. The result can have both soft detail and
jagged edges; shimmering during movement is a further temporal stability problem.

Apple recommends antialiased, noise-free input for Spatial. Adding AA before it is therefore
compatible with its intended use. The MetalMod world path renders single-sampled and now runs a
final-image FXAA stage. Mipmaps help texture minification,
but do not antialias geometry silhouettes.

Source: [Apple — Boost performance with MetalFX Upscaling](https://developer.apple.com/videos/play/wwdc2022/10103/).

## 2. Secondary priority — Temporal edge softness and acceptance

MetalFX Temporal combines temporal antialiasing and upscaling. Jittered samples from successive
frames can improve edge quality, detail and stability. Temporal reconstructs at the world render
size and Spatial finishes the native image; the final FXAA pass is separately switchable.

The native temporal scaler and projection-jitter helpers exist, and the live world path now runs
temporal end to end: a camera motion producer, the current/previous-transform contract, the encode
and the reset lifecycle are in `metalfx/SceneMotion`, `metalfx/WorldRenderTarget` and
`native/src/metalmod_motion.mm`. Per-object motion is implemented; an in-game image evaluation remains.

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

## 3. Other AA options after the final-image FXAA pass

Consider further AA work if the final-image pass leaves a demonstrated need. First identify the artifact:
geometric jaggies, cutout shimmer and general softness do not have identical remedies.

### FXAA / SMAA

A possible additional insertion point is the existing world-upscale boundary:

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
and the separate sky-colour defect (BUG-029) needs its own verification. Further AA work is driven first by the reported Spatial foliage failure and the matched input/output
comparison above.
