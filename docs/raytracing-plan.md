# Native Minecraft ray tracing — scope and prerequisites

Planning assessment, 2026-09-24. This is a proposed implementation path, not implemented RT support
or a performance claim. Phases 6–7 remain active prerequisites in the roadmap; shaderpack
compatibility is optional. The immediate RT target is a playable hybrid-shadow mode, not full path
tracing or parity with a particular cinematic shaderpack.

## 1. What the Vulkan projects establish

VulkanMod is a useful reference for a renderer replacement that retains ordinary Minecraft play.
Its [historical wiki](https://github.com/xCollateral/VulkanMod/wiki), edited March 15, 2024,
describes a Vulkan voxel renderer replacing OpenGL and says shader support was unfinished. Treat
that as historical context, not a current compatibility matrix or evidence of an RT implementation.
Its [RT request #495](https://github.com/xCollateral/VulkanMod/issues/495), opened August 26, 2024,
points to Vulkanite; a feature request is not evidence of shipped support.

[Vulkanite](https://github.com/MCRcortex/vulkanite) is a separate project. Its README describes
work-in-progress hardware RT through OpenGL/Vulkan interop, exposing extra RT passes for shader
authors. That is an architectural research reference, not proof of a complete native renderer or
an experience MetalMod can reproduce simply by porting the API calls. This review checked the
project documentation, not an executed build or a complete audit of its scene extraction code.

For MetalMod, interpret a similar experience as: install a renderer mod, keep normal worlds and
resource packs usable, expose clear quality controls, and preserve playable fallback rendering.
Native RT adds a separate visual objective. A claim of visual parity with a historical demo would
require identifying its exact version, pack, settings and scenes and capturing a reference run.
We have not established that benchmark.

## 2. Proposed architecture

Keep rasterization for primary visibility, UI and the existing world path. Publish the surface
information needed by the selected RT effect; trace secondary visibility rays against a persistent
scene representation, filter the result if sampled, then compose native lighting before MetalFX
and native-resolution UI. Agree the exact denoising/upscaling order with the selected MetalFX mode.

Apple's [Metal RT guide](https://developer.apple.com/videos/play/wwdc2023/10128/) describes primitive
and instance acceleration structures, building/refitting them, resource residency and intersection
functions. Map these to chunk-local geometry structures (often called BLAS) and a world instance
structure (TLAS). Use native Metal kernels first: GLSL pack stages and Vulkan shader-binding-table
conventions are not requirements for this path.

Choose the surface-buffer implementation after a small bandwidth/correctness prototype. Multiple
render targets can avoid repeated geometry passes, but an entire deferred renderer is not a gate
for the first shadow ray. Expose only data the effect actually needs.

## 3. Minecraft-specific requirements

| Requirement | Proposed first implementation | Later coverage / main risk |
|---|---|---|
| Terrain geometry | Publish decoded triangle positions and indices when chunk-section meshes finish; retain stable geometry versions | Generic draw interception alone loses chunk/material identity; compressed render vertices may need decoding |
| Scene coverage | Include resident geometry within a bounded RT radius, including off-screen occluders | The camera-visible draw list is insufficient for shadows and reflections; report missing or stale coverage |
| Chunk changes | Dirty-section rebuild queue with a per-frame work budget; retire old data only after GPU completion | Edits, neighbour-face changes, chunk unloads and resource reloads must invalidate the right geometry |
| Dynamic objects | Add entity/block-entity geometry after static proof; update instances for rigid movement | Animated/deforming meshes need geometry updates or refits, not just new instance transforms |
| Coordinates | Explicit chunk-local/world/camera-relative transforms, consistent with rasterization | Large world coordinates, teleports and floating-point error cause unstable intersections |
| Materials | Vanilla base colour, geometric normal, UV/atlas lookup, alpha cutoff and material identity | Roughness/metalness defaults for reflections; optional PBR inputs need a separately chosen format |
| Cutouts | Match raster alpha tests at ray hits for leaves, grass and fences with textured holes | Opaque bounding boxes or unconditional triangle hits produce solid-looking foliage shadows |
| Transparency | Explicitly document glass/water policy in the first release | Coloured transmission, refraction and multiple interfaces need separate transport logic |
| Lighting | Trace visibility for one explicit native directional-light contribution | Baked block/sky light cannot be cleanly unbaked by multiplying the final image by a shadow mask |
| Emission | Keep decorative emissive appearance separate from emitted light energy | Lava and other large emissive surfaces need bounded sampling; tracing every source per pixel is unsuitable |
| Temporal data | Current/previous transforms, depth, normals and history validity | Moving objects, edits, disocclusion, camera cuts and dimension changes must reject stale history |
| Composition | Define linear-light inputs, exposure, tone mapping and UI ordering | Avoid duplicate AO, baked/native light, temporal jitter and tone mapping |

RT correctness needs geometry outside the current camera frustum, but does not imply unbounded
world geometry. Publish the ray-distance/coverage limit and use a declared fallback beyond it.
A first opaque-only debug mode is useful evidence; it must not be labelled complete Minecraft
shadow coverage until cutouts and moving occluders are handled.

## 4. Gaps in the current MetalMod backend

The existing device, resource, raster-pipeline, shader-translation and presentation implementation
is reusable. The inspected code still needs:

- A native compute pipeline and dispatch interface, writable image/storage-buffer bindings, and
  explicit synchronization/lifetime handling for compute, raster, AS builds and presentation.
- Acceleration-structure allocation, build/refit/compaction policy, scratch-buffer management,
  instance metadata and ray-intersection resource binding.
- A chunk/scene publication interface above generic `GpuBackend` draw submission. Chunk meshes,
  light snapshots and material versions must agree for each frame.
- Surface outputs and lighting separation. `MetalRenderPipeline.MAX_COLOR_ATTACHMENTS` is currently
  one; expand it if the selected surface-buffer design requires MRT, across Java and native code.
- Effect-specific filtering and GPU timing. `MetalQueryPool` currently does not provide meaningful
  GPU query results; CPU submission and drawable-wait time cannot establish the RT GPU budget.

Phase 8 should provide the minimal contracts and composition proof. Phase 9 implements and tests
real intersections. Do not require all PBR materials or optional shaderpack stages before that proof.

## 5. Delivery gates

1. **8A–8C: foundations.** Inspect depth/normal/material debug views; modulate only the selected
   native direct light with a synthetic visibility mask; verify geometry publication and retirement.
2. **9A: static proof.** A bounded terrain scene with one directional hard shadow. Compare known
   occluder/receiver cases and ray-hit diagnostics; show both coverage and timing. No denoiser is
   necessary for the initial deterministic one-ray visibility test.
3. **9B: playable coverage.** Handle edits, chunk boundaries, unloads, off-screen occluders, alpha
   cutouts and entities. Establish bounded AS-update work and explicitly document transmission gaps.
4. **9C: stable quality.** Add soft-shadow sampling and spatial/temporal filtering. Validate moving
   edges and disocclusion, set a measured GPU/memory budget, then test MetalFX combinations.
5. **9D: expand deliberately.** Reflections require hit-point material evaluation and off-screen
   scene coverage; indirect lighting requires bounce/light sampling and substantially more noise
   control. Give each effect a separate performance and visual gate. Full path tracing is research.

MetalFX temporal upscaling alone should not be assumed to remove noisy ray estimates. Apple's
[feature tables](https://developer.apple.com/metal/capabilities/) list ray tracing and MetalFX denoised
upscaling as separate capabilities. Evaluate any denoised-upscaling API against its actual required
inputs and runtime support before selecting it; keep an effect-specific filtering fallback.
Frame generation is optional for RT operation and cannot substitute for acceptable rendered-frame
latency or fix AS build stalls.

## 6. Hardware, performance and user experience

Use runtime device/API capability queries; API ray-tracing support and hardware-accelerated
intersection performance are separate questions. Begin performance validation on the existing M4
Pro reference machine. Expand the supported hardware matrix only after measured tests, retaining
the working raster path elsewhere. Use Apple's
[capability tables](https://developer.apple.com/metal/capabilities/) for implementation constraints,
not GPU generation names alone.

Proposed controls: RT off/on, supported effect toggles, quality preset, ray distance, and MetalFX
mode. Defaults must work with vanilla resources. Expose missing hardware support and unsupported
combinations clearly. PBR resource packs and artistic presets can be later enhancements.

Before promising an FPS target, record a baseline at fixed resolution, route, render distance,
lighting settings and vsync configuration. Measure rendered-frame GPU time, AS build/update time,
traversal/filter cost, p95/p99 frame time, peak/resident memory and edit/streaming spikes. Compare RT
off/on at the same internal resolution before separately reporting MetalFX or generated-frame FPS.
Set numerical release budgets after the first prototype; no current benchmark supports an RT FPS
promise. Track unified-memory pressure and temporary AS build memory as well as final structure size.

## 7. Validation scenes

- A simple wall/receiver scene and a cave entrance: direct-light visibility and no baked-light
  double counting; check self-intersection bias and chunk seams.
- Leaves, grass, fences and animated textures: matching cutouts and material reloads.
- Block placement/breaking at a chunk border, rapid travel and unload/reload: no stale shadows,
  use-after-free geometry or uncontrolled build spikes.
- Walking entities, moving block entities and first/third person: correct moving occluders and
  declared coverage for hands/particles.
- Glass and water: verify the documented first-release approximation; reserve transmission and
  refraction correctness tests for their own milestone.
- Fast turns, teleportation, resize, world/dimension changes: history invalidation and stable recovery.
- Overworld day/night/weather, Nether and End: explicit light policy per environment.
- Large coordinates, long sessions and repeated resource reloads: precision, lifetime and memory.

Reuse the project's reproducible capture routes and GPU checks where applicable. Add small known-hit
and known-visibility GPU scenes, then in-game visual comparisons. Passing compilation or producing a
plausible still image is insufficient for a playable RT release.

## 8. Difficulty and scope control

Phase 8 is estimated M–L and Phase 9 XL research. The largest risks are scene extraction/lifecycle,
lighting ownership and temporal quality under world changes. A small static RT demo does not resolve
those risks. Prioritise the shadow proof, then the edit/streaming stress scenes before adding effects.
Existing GLSL pack compatibility would introduce another lighting/rendering runtime; it remains an
optional track and is not required to ship native RT.

## 9. Radiance reference and user-provided assets

Reference reviewed 2026-09-24: [Radiance's README](https://github.com/Minecraft-Radiance/Radiance/blob/main/README.md)
describes a Vulkan C++ renderer with hardware RT, backed by
[MCVR](https://github.com/Minecraft-Radiance/MCVR). It recommends PBR resource packs, includes internal
emission textures, and states that packs load without preprocessing from version 0.1.4. It also
documents separate DLSS runtime downloads. These are documented project behaviours, not independently
verified performance or compatibility results. Radiance is a closer native-RT product reference than
VulkanMod's historical renderer documentation; its Vulkan/DLSS implementation is not our Metal API design.

MetalMod's proposed installation contract:

| User provides | Required? | MetalMod supplies |
|---|---|---|
| Supported Minecraft/Fabric installation and Mac/macOS | Yes | Runtime compatibility checks and an actionable capability message |
| MetalMod | Yes | Native RT shaders, material defaults, filtering and quality presets |
| Ordinary resource pack | Optional | Existing colour textures with default material properties |
| Compatible PBR resource pack | Optional | Material decoding for explicitly supported formats/features |
| GLSL shaderpack | No | Native lighting and effects independent of pack code |
| Separate upscaler runtime | No planned requirement for MetalFX | Integration with supported system APIs |

The first RT release must work with vanilla resources. A PBR pack enriches material appearance and
is not required to receive ray-traced shadows. Do not make third-party downloads, custom shaders or
manual material conversion prerequisites for the normal installation flow.

PBR means physically based rendering. Resource packs can describe base colour, surface normals,
roughness/smoothness, reflectance/metallic properties, emission and height. Normal maps affect shading
without adding geometry; height data only changes appearance or geometry if the renderer implements
an appropriate effect. Emissive appearance and emission that illuminates other surfaces are distinct
capabilities. Neither is implied merely by loading a texture.

The first proposed material-format target is **LabPBR 1.3**. Its accompanying `_n` and `_s` textures
encode material properties; native Metal shaders can consume these without GLSL shaderpack support.
Use the [LabPBR specification](https://shaderlabs.org/wiki/LabPBR_Material_Standard) and
[implementation requirements](https://shaderlabs.org/wiki/LabPBR_Implementation_Requirements) as the
source of truth. Publish required-decoding and optional-effect coverage separately. Do not interpret
arbitrary legacy `_s` files as LabPBR or advertise generic compatibility with Bedrock RTX packs.

## 10. Material and settings implementation sequence

All steps below are **planned and unimplemented**. These steps refine the delivery gates in §5;
optional material enhancements do not block 9A–9C. Steps 1–3 establish the first-release experience,
steps 4–6 add PBR support as materials/reflections mature, and steps 7–8 validate each release's
actual feature set. Do not expose an option until its rendering path is implemented and verified.

### Step 1 — Establish vanilla defaults and the material contract (Phase 8A)

- [ ] Define versioned material records and identify the source of each property: built-in default,
  ordinary resource texture, supported PBR map, or explicit override.
- [ ] Use vanilla/resource-pack base colour and alpha with a flat tangent-space normal and documented
  rough nonmetallic defaults where no additional material data exists.
- [ ] Add deliberate built-in overrides for selected materials rather than guessing metallicity or
  emission from pixel brightness. Keep block light emission and material emission ownership explicit.
- [ ] Define colour-space handling: decode colour appropriately and read numerical material channels
  as data. Retain geometric normals separately from shading normals for robust ray offsets.

**Accept when:** vanilla resources and a colour-only replacement pack render without extra downloads;
material debug views show predictable defaults; missing maps never produce black or invalid materials.

### Step 2 — Add capability-aware installation and fallback (Phases 8C–9A)

- [ ] Query the required Metal/device/OS capabilities and report supported modes in the settings UI.
- [ ] Package the native renderer's shaders and required resources with MetalMod; use system MetalFX
  support where available rather than requesting a separate user-installed upscaler library.
- [ ] Preserve raster rendering when RT is unsupported or disabled. Keep MetalFX capability checks
  independent of RT and frame-generation capability checks.
- [ ] Explain unavailable features with a concrete reason and available fallback.

**Accept when:** the supported reference machine can launch without third-party packs or runtime
files, while unsupported-mode selection is prevented or falls back clearly without crashing.

### Step 3 — Ship a small settings surface for the shadow release (Phases 9B–9C)

- [ ] Add RT off/on and Performance / Balanced / Quality presets, with Custom after advanced edits.
- [ ] Expose only implemented effects; shadows are first. Keep future reflections/GI out of the
  active controls until they work.
- [ ] Expose Native / supported MetalFX modes and quality controls; keep frame generation separate.
- [ ] Offer a bounded exposure control without disguising missing or incorrect lighting.
- [ ] Add an Advanced RT-distance control, distinct from Minecraft render distance, with an
  explanation of coverage and performance. Cap it to scene coverage the implementation can supply.
- [ ] Keep denoising enabled by default for sampled effects; developer diagnostics may override it.
- [ ] Persist settings, define which require resource recreation/reload, and apply changes at safe
  frame boundaries with history invalidation where necessary.

**Accept when:** users can enable RT and select a preset without editing configuration files;
settings survive restart; resizing and mode changes preserve correct rendering. Preset values must
come from measured budgets, not arbitrary advertised FPS targets.

### Step 4 — Implement optional LabPBR resource loading (after Step 1)

- [ ] Load associated `_n` and `_s` maps through Minecraft's resource-pack system and honour pack
  priority. Define how companion maps behave when a higher-priority pack replaces only base colour;
  avoid silently combining incompatible maps from unrelated packs.
- [ ] Support partial coverage with per-property defaults, animated textures and resource reloads.
  Validate dimensions and atlas/mipmap alignment; report malformed inputs without crashing.
- [ ] Provide Built-in defaults / Resource-pack materials selection. Treat format metadata as
  authoritative when available; provide a per-pack format override when the format is ambiguous.
  File suffixes alone are not sufficient to distinguish LabPBR from legacy conventions.
- [ ] Decode and test the specified normal orientation/reconstruction and smoothness/reflectance
  channels against LabPBR 1.3, including its special encodings.
- [ ] Expose pack status separately from supported material features; do not imply full compliance
  before the required decoding and behaviour have been verified.

**Accept when:** known reference materials match expected decoding, missing maps use defaults,
pack priority is deterministic, and repeated reloads do not leave stale materials or GPU resources.

### Step 5 — Connect material data to native lighting and reflections (Phase 9D)

- [ ] Apply shading normals consistently to raster-visible and ray-hit surfaces; preserve robust
  geometric intersection and self-shadow handling.
- [ ] Use decoded roughness and reflectance in the material response for native reflections.
- [ ] Implement emission appearance separately from emitted-light sampling, and prevent the same
  emitter being counted through both Phase 6 lights and material emission without an explicit rule.
- [ ] Add normal-mapping and emission controls only for supported behaviour, documenting any limits.

**Accept when:** rough stone, smooth dielectric and metallic reference materials respond differently
and correctly; emissive test surfaces follow the declared lighting policy without doubled energy.
Vanilla defaults must continue to work when all PBR maps are removed.

### Step 6 — Add optional material effects individually (later research)

- [ ] Evaluate height/parallax, material AO, porosity/wetness, subsurface effects and transmission
  separately, guided by user demand and performance.
- [ ] Do not imply that a height map changes ray-visible geometry unless the implementation does so.
- [ ] Avoid multiplying material AO, vanilla AO and ray-traced occlusion indiscriminately.
- [ ] Keep expensive height effects optional; publish a format/feature support matrix.

**Accept when:** each advertised effect has its own visual/performance checks and a defined fallback.
These features are not gates for the first native RT or baseline PBR release.

### Step 7 — Tune advanced controls and memory budgets (each release)

- [ ] Expose per-effect quality only where useful: shadow sampling first, then reflection quality
  and indirect-light bounce limits when those effects ship.
- [ ] Introduce a texture-memory budget when supporting demanding PBR packs. Account for all maps,
  mipmaps, acceleration structures and temporary buffers in unified-memory pressure handling.
- [ ] Choose and explain a predictable downscaling/fallback policy under memory pressure.
- [ ] Benchmark presets with fixed scenes and internal resolution, reporting rendered-frame time
  separately from generated-frame display rate.
- [ ] Keep AS rebuild scheduling, synchronization and low-level denoiser constants internal unless
  a developer diagnostic specifically needs them.

**Accept when:** presets meet published, measured budgets on tested hardware; advanced changes map
to visible quality/cost tradeoffs; large packs fail gracefully rather than exhausting memory.

### Step 8 — Validate the installation and resource-pack experience (release gate)

- [ ] Test a fresh vanilla installation, a colour-only pack, a partially covered LabPBR pack and
  a pack exercising each supported material feature.
- [ ] Test pack stacking, malformed/missing maps, animation, reloads, world transitions and settings
  persistence alongside the RT scene tests in §7.
- [ ] Test RT unavailable, MetalFX unavailable and unsupported combinations with clear fallbacks.
- [ ] Document exact supported Minecraft/macOS/hardware configurations, material formats, effects
  and known limitations. Separate verified support from planned capabilities.

**Accept when:** the basic experience is install → enable RT → choose a preset. Optional PBR packs
improve material detail through the normal resource-pack flow, without making shaderpacks or manual
conversion prerequisites.
