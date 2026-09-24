# Phase 6 — Dynamic lighting

Status: **6A, 6B and 6C are implemented and verified; 6D is partly implemented. Phase 6 is NOT
complete** - the lifecycle, scaling-measurement, performance and consumer gates in §5 are unmet, and
the implemented cap is 32 lights rather than the proposed 256. The feature is usable and the wiring is
confirmed in game. Updated 2026-09-24.
Baseline reviewed: `bdcd5c0`, Minecraft 26.2 client in `MetalMod_Test_26.2`, Apple M4 Pro.

> **Recovery note.** The 6A/6B sources were lost before they were committed. They were reconstructed
> from the compiled classes in `build/` and the dangling git blobs left by the interrupted `git add`
> (`git fsck --lost-found`). Two defects in that reconstruction were found and fixed rather than
> carried forward: the standalone suite asserted the light-record intensity at the wrong byte offset,
> and the published light buffer was uploaded from the first light-using draw instead of from the
> light set, so a snapshot published later in a frame never reached the GPU. Both are now covered by
> assertions that fail if they return.


## 1. Phase 5 final check

**Phase 5 is complete for the tested vanilla scope.** This is not a claim of compatibility with
all 26.2+ releases, shaderpacks, Sodium, or all Apple GPUs.

Fresh verification against the current checkout:

| Gate | Result |
|---|---|
| `scripts/build_mod.sh` | native library, Java sources, standalone tests and mod jar built |
| `scripts/run_smoke.sh` | `ALL CHECKS PASSED` |
| `tools/shader_inventory/run.sh` | static 87/87; post-processing 9/9; no diagnostics |
| `tools/render_check/run.sh` | 85 PASS lines; `RENDER CHECK PASSED` |
| `net.metalmod.StandaloneTestRunner` | `ALL TESTS PASSED SUCCESSFULLY!` |

GPU suites initially failed inside the execution sandbox because no Metal device was available.
All four were rerun successfully outside it. Logs from this review are in
`/tmp/metalmod-final-{build,smoke,shaders,render,tests}.log` (temporary, not archival evidence).
No game was launched, no jar installed, and no new visual sign-off is claimed by this review.
The visual evidence is the recorded in-game confirmation in `bug.md`, `TESTING.md` §5.5 and
commit `a854894`.

Saved capture summaries were read directly from the test instance's `debug/metalmod` directory:

| Overworld capture | Above-ground mean | Underground mean | Overall mean | p95 |
|---|---:|---:|---:|---:|
| Metal private, `20260923-210627-128-Metal-8906586634466559950` | 9.72 ms | 16.40 ms | 13.454 ms | 24.912 ms |
| Metal shared, `20260923-210855-595-Metal-11755047853226574815` | 8.91 ms | 15.06 ms | 12.331 ms | 19.230 ms |
| Vulkan, `20260923-211042-119-Vulkan-17763907244290032167` | 8.65 ms | 21.09 ms | 14.449 ms | 25.036 ms |

Resolution 5120×2664, render distance 32, vsync off, FPS cap 260; same 14-waypoint route.
Stages 0 and 13 are the long dwells; reported positions agree between backends. Metal shared
saved 4867/4867 gameplay frames and Vulkan 4154/4154. Vulkan does not expose draw counts in these
captures, so identical GPU work cannot be independently established from that column. Storage
labels above follow the recorded session analysis in TESTING.md; these older summaries predate
the explicit storage-mode header.

This supports the milestone's comparable-performance criterion on this machine. It is one ordered
session, not a randomized repeated benchmark: do not generalize the 29% underground advantage or
attribute the storage regression to a specific hardware mechanism without further evidence.
The Nether has an unpaired route; neither a matched Nether comparison nor an exhaustive End pass
is established by these captures. Shared textures remain the measured default.

Non-blocking carryovers: GPU query values are empty (`MetalQueryPool`); indirect draws are not
implemented and their feature flags remain false; shaderpack/compute support is future work.
Drawable wait is not GPU execution time. These limits matter when budgeting Phase 6.

## 2. Scope and lighting ownership

Keep the roadmap's broader light model, but deliver it in explicit increments:

1. **6A: contract and one-light proof.** One synthetic point light on terrain, with a verified
   camera-relative position and an unchanged feature-off path.
2. **6B: moving sources.** Held items (both hands, first/third person), dropped items and explicitly
   configured emitting entities; smooth render-time interpolation and lifecycle correctness.
3. **6C: bounded scaling and coverage.** Cluster lists, overflow policy, entities, cutouts,
   translucent terrain/water, particles where appropriate, regression scenes and performance gates.
4. **6D: downstream contract.** Incremental static block-source indexing, environment metadata,
   a versioned GPU representation and a diagnostic shader consumer. Phases 8–9 consume the native
   contract; actual packs belong to the optional compatibility track.

Point lights are the first implemented type. Reserve type/flags/version fields for spot and area
lights, but do not advertise those types as supported before their evaluators exist. The sky is an
environment/directional source, not a local point light. Shadows and physical area-light evaluation
are later extensions, not implied by a `castsShadow` request flag.

Vanilla baked block and sky lighting continue to operate. Initially only sources absent from vanilla
propagation add visible illumination. Placed torches and lava may be indexed for downstream consumers
but must not also be added on top of their baked contribution. Track whether a source already
contributes to vanilla lighting. Glowing outlines and emissive materials do not automatically imply
that an entity emits environmental light; use explicit source definitions.

Expose exclusive ownership modes: vanilla only, MetalMod dynamic contribution, or external pack
ownership. A future pack can read the light set while suppressing MetalMod's evaluation. Document
that the vanilla-compatible path is an artistic extension, not a physical replacement of baked light.

## 3. Recommended rendering path

Prefer a bounded forward-lighting extension, growing into clustered forward, over committing now
to a deferred renderer. Clustered shading assigns local lights to spatial groups of samples, reducing
per-sample work; it is applicable to forward and deferred rendering. See the original
[Clustered Deferred and Forward Shading research](https://research.chalmers.se/en/publication/161725).
This is an architectural recommendation, not a measured MetalMod result.

Facts checked in this checkout and the actual client jar:

- `terrain.vsh` constructs a camera-relative position from `Position`, `ChunkPosition`,
  `CameraBlockPos` and `CameraOffset`. Its inputs contain no normal. It multiplies vertex color
  by `sample_lightmap` before passing it to `terrain.fsh`.
- `terrain.fsh` receives that combined color and UVs, applies chunk visibility and fog. It does
  not receive separate albedo, position, normal and lighting outputs suitable for deferred shading.
- `entity.vsh` has a normal and separate lightmap color, but its transform conventions differ.
  Entity `Lighting` contains two directional shading vectors, not the world emitter list.
- `LevelRenderer.render(...)` consumes `state.level.CameraRenderState`; `addMainPass(...)` uses
  `state.level.LevelRenderState`. Those names/signatures were verified with `javap`. The latter
  holds entity/block-entity render states. The extraction producer and exact hook ordering still
  need bytecode inspection before selecting injections.
- `GameRendererMixin` currently records diagnostics only. The backend has no light extraction
  layer. A draw hook alone cannot identify all world emitters.

A color-plus-depth post-pass alone cannot correctly relight arbitrary materials, recover their
unlit color, or handle multiple transparent layers. Do not multiply or divide the already-lit final
color to simulate an albedo buffer. A deferred approach would require explicit material/normal
outputs and a separate transparency strategy; keep that as an alternative if the forward proof fails.

For the first proof, use distance-based vanilla-style illumination without promising Lambertian
normals. Separate the original color/tint and baked light contribution in shader variants, evaluate
the dynamic contribution before fog, and preserve alpha, cutout thresholds, overlays and chunk fade.
A later normal-aware variant must deliberately choose additional terrain attributes or validated
geometric derivatives; neither should be assumed equivalent to authored material normals.

Use explicit shader variants and pipeline identification, not arbitrary text replacement across all
resource-pack shaders. Extend cache identity/invalidation for the lighting variant and source reload;
the existing pair cache must never return the unlit partner for a lit request. Unsupported replacement
shaders should retain a visible diagnostic and a vanilla fallback. GUI/inventory previews, sky,
clouds, outlines and fullbright/emissive passes need an explicit exclusion policy.

## 4. CPU/GPU contract and integration work

Keep source discovery independent of Metal resources. Extract an immutable snapshot at the engine's
appropriate world-state boundary; render code consumes that snapshot and does not retain mutable
world objects or assume visible entity draw lists contain every relevant emitter. Cull by the light's
influence volume: an offscreen source can illuminate an onscreen surface.

Proposed logical record: stable source ID, source kind, dimension/generation, position, finite radius,
linear RGB/intensity, supported light type, baked-contribution flag, and requested/effective shadow
flags. Define intensity units as artistic units calibrated against a reference torch, not lumens.
Keep world coordinates in CPU doubles; subtract the same frame camera origin before uploading floats.
Specify GPU record offsets/alignment, count, ABI version and frame generation explicitly. Test Java,
GLSL/SPIR-V/MSL and native agreement. Clamp or reject invalid radius, non-finite values and negatives.

GPU upload: one bounded snapshot per frame, using existing fenced buffer lifetime patterns. Avoid
per-light FFI calls, per-chunk uploads and chunk mesh rebuilds when a light moves. Verify fragment
buffer reflection/binding and limits; current uniform-buffer support does not establish a general
storage-buffer or compute path. A small uniform array is enough for the first proof. Larger indexed
lists require a tested buffer/texture representation before promising capacity.

Start with CPU-built conservative spatial/cluster lists. Compute-driven culling can follow profiling;
it is not currently an available backend operation. Proposed experimental caps: 256 active point
lights and 32 entries per cluster, configurable only after correctness testing. Define a stable,
contribution-aware selection with hysteresis and explicit dropped-light counters. Exhaustion must
never cause out-of-bounds reads or flickering arbitrary selection. Size the cluster grid from a
memory/work budget; do not scan every light for every pixel or allocate lists without a hard bound.

Collect nearby entity sources incrementally; keep block emitters in a section index updated on load,
unload and block-state changes. No scan of all loaded blocks every frame. Clear snapshots and GPU
state on disconnect, dimension changes and resource reload; handle teleports, item pickup/despawn,
extinguishing, hand swaps, spectators and paused frames. Runtime toggles must not alter world light
values or save data.

## 5. Acceptance gates before declaring Phase 6 complete

- **Baseline:** all five Phase 5 gates remain green. Feature off and zero lights reproduce the
  existing render-check output; no world scans, extra passes or light uploads while disabled.
- **Image correctness:** known pixels for a light at the center, at radius and outside it; moving
  across chunk boundaries; two colored lights; large world coordinates; fog; cutout alpha;
  water/transparency; entities and both held hands. Verify color-space/composition rules explicitly.
- **Lifecycle:** pickup, despawn, source removal, dimension switch, disconnect/reconnect, reload,
  resize and toggle produce neither stale illumination nor leaked resources. Source interpolation
  follows the frame camera without swimming or a one-tick lag.
- **Scope honesty:** no extra lighting of GUI/sky/fullbright paths; no doubling of placed torches.
  Initial unshadowed lights can leak through walls: expose this limitation and include a wall scene.
  Occlusion/shadows require their own design; do not claim their absence is a bug already solved.
- **Scaling:** deterministic scenes with 0/1/16/64/256 lights and intentional overflow, including
  sources outside the view whose influence reaches it. Record selected/culled/dropped counts,
  list occupancy, extraction/culling time, upload bytes, frame median/p95 and allocation rate.
- **Performance:** repeat interleaved feature-off/on/off/on captures of one route after warm-up.
  Proposed target on this M4 Pro: disabled overhead within measurement noise; 64-light stress scene
  adds at most 10% median and p95 frame time. This is a proposed budget, not a result. Use at least
  three paired repetitions; record settings/build/thermal drift. Add true GPU timing before drawing
  GPU-specific conclusions, or label wall-time evidence as such.
- **Consumer:** a diagnostic shader reads the published light records, ABI version and shadow flags;
  external ownership disables built-in evaluation. Actual shaderpack compatibility remains optional and does not block native RT.

### Current verdict against those gates (2026-09-24)

| Gate | Verdict |
|---|---|
| Baseline | **Met.** All five Phase 5 gates green. Feature-off compiles the vanilla pipelines and is asserted; extraction, the block index and every upload sit behind the switch, though "zero uploads while disabled" is argued from the call graph rather than measured. |
| Image correctness | **Met offline, partly confirmed in game.** The render check covers falloff at centre/radius/outside, two coloured lights, large world and camera coordinates, fog order, cutout, translucency, entities, items and moving blocks, all with explicit composition assertions. Not covered: the offhand specifically, and a literal chunk-boundary crossing (the large-origin case exercises the same arithmetic). |
| Lifecycle | **Not met.** Pickup, despawn, dimension switch, disconnect/reconnect and resize have no recorded evidence, in game or offline. Buffer churn is covered by the four-rotation fence test, and interpolation by the fractional-camera case, but that is not the gate. |
| Scope honesty | **Met except the wall scene.** GUI, emissive and glint paths are excluded and asserted; placed torches are indexed but never added on top of their baked light. A wall scene demonstrating the remaining leakage has not been captured. |
| Scaling | **Not met.** Deterministic 0/1/16/64/256 scenes have not been run, and the cap is 32 lights with 4 entries per cell rather than the proposed 256 and 32. Counters for selected/dropped/occupancy exist on F3; extraction and culling time, upload bytes and allocation rate are not recorded. |
| Performance | **Not met.** No measurement of any kind. The interleaved off/on/off/on route captures and the 10% budget are untouched. |
| Consumer | **Not met.** No diagnostic shader consumer and no ownership modes. The records, ABI version and cluster table are published and unit-tested, but nothing shipped reads them as a consumer. |

Closing the gaps, cheapest first: a wall scene and an in-game lifecycle pass cost a session each; the
scaling and performance gates need the capture route and, for honest numbers, true GPU timing; the
consumer gate is a new slice. Raising the cap to the proposed 256 is a separate piece of work - it
needs a buffer or texture-backed list rather than the current uniform array, and it should not be
attempted before the performance gate, which is what would justify it.

## 6. Implementation update (2026-09-24)

**6A proof landed.** `-Dmetalmod.pointLightProof=true` selects a camera-centred synthetic amber
point source. `TerrainLightVariant` adapts only the known vanilla 26.2 terrain pair, after matching
both preprocessed shader hashes and pipeline IDs. Unknown/resource-pack terrain keeps its original
shaders and logs a skip. The 32-byte point-light UBO has tested finite/range validation, camera
subtraction in doubles before float conversion, and explicit offsets.

**First 6B slice landed behind `-Dmetalmod.dynamicLights=true`.** `LevelExtractor.extract` publishes
an immutable camera-frame snapshot before drawing. It gathers nearby dropped light-emitting block
items and held/offhand block items, interpolates entity positions at partial tick,
keeps the eight highest estimated contributions with stable tie-breaking, and leaves placed blocks
on vanilla's baked path. Three shared Metal buffers rotate per frame; a queue-ordered shared-event
fence completes before a reused slot is overwritten. A resource-level change clears the current set.
F3 displays `count/8`; the hook checklist reports extraction and world-change hooks. Feature collection
also requires the Metal backend to be enabled, avoiding scans on the default Vulkan path.

The terrain shader sums the bounded eight-entry set and fills remaining vanilla lightmap headroom
before fog, while preserving material alpha, cutout and translucent pipeline blending. The zero-source
path remains pixel-identical in the render check. The startup property is experimental: lighting is
unshadowed, bounded to eight nearby contributors, warm-tinted for block items, limited to recognized
vanilla terrain shaders and the M4 Pro validated in offline tests. No config-menu toggle exists yet.

**Evidence gathered:**

- `scripts/build_mod.sh` compiles main code and all standalone tests.
- `tools/render_check/run.sh` on Metal verifies center/edge/outside falloff, zero intensity equals
  vanilla, large-world camera/chunk coordinates, fog order, cutout discard, translucent blending,
  baked full-bright preservation, two coloured lights, empty snapshot, and four rotations through
  the fenced buffer ring. All assertions pass.
- `net.metalmod.StandaloneTestRunner` passes the point-light and snapshot ABI/validation checks.
- `tools/shader_inventory/run.sh` with `JDK_JAVA_OPTIONS=-Dmetalmod.dynamicLights=true` compiles
  all 87 vanilla pipelines and 9 post chains with no diagnostics. The default inventory and native
  smoke were rerun successfully against the final build as well.

### 6C — bounded clustered scaling (implemented)

`-Dmetalmod.clusteredLights=true` (on top of `dynamicLights`) selects the clustered variant.

- **CPU cluster grid.** `LightClusterGrid` partitions a 128-block window centred on the camera into
  8×8×8 cells of 16 blocks, and assigns every light's influence box to the cells it touches. The
  window origin is snapped to the cell size and narrowed against the camera, so a fragment's cell is
  stable as the camera moves. The extent is not arbitrary: it must be at least twice the collector's
  search radius, because a source within that radius can illuminate a surface a further radius away.
  `LightClusterTest` asserts that invariant.
- **Bounded and deterministic.** 4 entries per cell; a full cell keeps the strongest contributors
  (the set is in contribution order) and every eviction is counted. Sources whose influence box
  misses the window entirely are counted as unreachable rather than clamped into an edge cell where
  they would light surfaces they cannot reach. No index outside the published record range is ever
  written.
- **Selection hysteresis.** A source kept by the previous frame wins unless a challenger beats its
  estimated contribution by 1.25×, so two near-equal sources cannot swap places every frame and make
  the lighting flicker.
- **GPU representation.** The cluster table is an RGBA32F texture read with `texelFetch`: one header
  texel, one texel per cell and two texels per light record, all in a single row so a texel address is
  a flat index. A texture rather than an indexed uniform array because the table is tens of kilobytes
  and array addressing inside a uniform block proved unreliable on this backend — see the note below.
- **Bounded shader loop.** The fragment stage evaluates at most `ENTRIES_PER_CELL` lights from its
  own cell, each index clamped to the published count, so an exhausted or corrupted table can only
  produce a dimmer pixel, never an out-of-bounds read.
- **Raised cap.** The flat light set is now 32 records (was 8); the clustered variant still publishes
  the same set, so the two representations are directly comparable.

**Particle coverage added (2026-09-24), from an in-game report that particles stay dark.** Particles
are a separate shader pair: `opaque_particle` / `translucent_particle` with `core/particle.{vsh,fsh}`.
`ParticleLightVariant` now adapts them with the same hash-verified treatment as terrain, and the
adapter core was factored so both families share one copy of the light maths: the only per-family
inputs are the recorded hashes, the expression naming the camera-relative position, and the expression
sampling the surface.

The camera-relative frame was established from the shaders rather than assumed. `particle.vsh` places
its vertex with `ProjMat * ModelViewMat * vec4(Position, 1.0)` and no `ChunkPosition`/`CameraBlockPos`
term, while terrain reaches the same expression through `pos = Position + (ChunkPosition -
CameraBlockPos) + CameraOffset`, which is world-minus-camera. Both multiply by the same `ModelViewMat`,
so `Position` must already be camera-relative - and it has to be, because every particle in a batch
shares one vertex buffer and one draw, leaving no per-particle model transform that could place a
model-local coordinate. `Position` is therefore passed through unchanged.

Two things about the pair are easy to get wrong, and the offline check now covers both:

- The particle vertex format is `[Position, UV0, Color, UV2]` - the same four elements as terrain's
  28-byte vertex but in a **different order**. Reusing terrain's vertex bytes feeds UV0's bytes to
  Color and Color's to UV0, which samples a black texel with zero alpha and the draw discards. The
  check found exactly that, as "nothing rasterizes", and now builds a particle-format buffer.
- `particle.fsh` applies `ColorModulator` itself and discards `color.a < 0.1`, instead of terrain's
  `#ifdef ALPHA_CUTOUT`. The variant adds the dynamic contribution to the modulated surface and
  leaves alpha and the discard test untouched; the check asserts the discard still fires under a light.

**Entity coverage added (2026-09-24).** Entities are a third shader pair, `core/entity.{vsh,fsh}`, and
`EntityLightVariant` adapts them with the same hash-verified treatment.

The open question was whether `Position` is camera-relative world space or model-local, since an entity
draw can carry a model transform. It is camera-relative, and the shaders say so rather than the
guesswork: `entity.vsh` computes `sphericalVertexDistance = fog_spherical_distance(Position)` and
places the vertex with `ProjMat * ModelViewMat * vec4(Position, 1.0)`, while `terrain.vsh` reaches the
identical expression through `pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset` and
computes its fog from `pos`. Fog distance is distance from the camera, both draws share the same
`ModelViewMat`, and both are correct in game, so the two expressions denote the same thing. Entity
rotation and limb animation are baked into the vertex data, which is why no model transform appears
here. The `Normal` attribute and the `Lighting` block are untouched: the first slice is still
distance-based illumination without a Lambertian claim.

**Item coverage added (2026-09-24), from an in-game report that the held item stayed flat.** Items
are a fourth pair, `core/item.{vsh,fsh}`, used for the item in the player's hand and for item entities
in the world. The pair is the same shape as `core/entity` line for line, so `ItemLightVariant` reuses
the model-shaped adapter and differs only by its recorded fingerprints; `Position` is camera-relative
world by the same fog-argument as everywhere else.

The inventory interaction is the interesting part. Vanilla draws inventory and GUI item previews
through these same two pipelines, so **no pipeline-level test can separate them from the held and
dropped items**. What separates them is the contribution rule itself: the dynamic term fills only the
lightmap headroom vanilla left, and the GUI draws items fully lit, so there is no headroom and the
addition is exactly zero. That is an explicit policy rather than an accident of coordinates, and the
render check asserts it exactly - a fully lit lightmap must leave the frame pixel-identical. Anything
that ever draws an item with a dimmed lightmap outside the world would be affected, which is the
limitation to keep in mind.

Two things differ from terrain and particles, and the offline check covers both:

- The entity fragment stage shades over several statements rather than one (surface sample, per-face
  vertex colour, `ColorModulator`, overlay, baked lightmap). The variant captures the surface sample
  where it is taken and adds the dynamic contribution immediately before fog, so nothing is applied
  twice, and it takes the headroom from the interpolated `lightMapColor` varying instead of computing
  it in the vertex stage - one fewer varying than terrain.
- **Emissive pipelines are excluded rather than adapted.** `lightMapColor` exists only when `EMISSIVE`
  is undefined, and an emissive pass ignores the lightmap by design, so there is no baked headroom to
  fill and nothing to relight. That is the explicit exclusion policy the plan asks for, and the check
  asserts it so it cannot quietly become an oversight. The excluded pipelines are
  `entity_translucent_emissive`, `energy_swirl` and `eyes`.

### 6D — downstream contract (partially implemented)

Implemented:

- **Static block-source index.** `BlockLightIndex` caches emitting blocks per chunk section. A section
  is read at most once, the first time the search window asks for it - not when its chunk loads - with
  a per-frame budget spent nearest-first, so a teleport fills in around the player instead of in
  chunk-load order. A palette check (`maybeHas`) answers "does this section contain an emitter at all"
  before any block is read, so an emitter-free section costs a palette lookup rather than 4096 of them.
  A cached section is dropped when the client unloads its chunk (`ClientChunkCacheLightMixin`). The
  index is bounded (512 sections, 48 emitters per section) with least-recently-used eviction, and an
  evicted or unloaded section returns to "unknown" rather than "empty", so it is re-read if it is
  wanted again. **No frame scans all loaded blocks, and nothing is queued for sections outside the
  search window.**
- **Baked-contribution flag.** Every indexed source carries `baked`, which is true when vanilla
  already bakes that block's emission into the lightmap. The collector's dynamic set skips those, so a
  placed torch is indexed for consumers and is *not* added on top of its baked contribution. The flag
  is per record rather than per block type, because it is a statement about the source, not the block.
- **Environment record.** `EnvironmentRecord` (version 1) publishes the dimension id, height range,
  sky/ceiling flags, the dimension's ambient term, game time, day fraction, sky darken and the
  interpolated rain and thunder levels. It is a versioned value a consumer can check, not a shader
  semantic: the sky remains vanilla's, and nothing here adds a second ambient term.
- **F3 diagnostics.** The debug section reports the light count, the static index's emitter/section/
  scan/eviction counters, and the environment summary.

### In-game settings page (added 2026-09-24)

The switches are no longer launch-only. **Options -> MetalMod...** (and Mod Menu -> MetalMod) opens the
mod's settings; from there **Lighting** exposes dynamic lighting, clustered lists and the point-light
proof as toggles, persisted to `config/metalmod.properties` (`enableDynamicLights`,
`enableClusteredLights`, `enablePointLightProof`). The Lighting page is separate from the main config
screen because lighting is the part of this mod that keeps growing; the main screen stays the short
list of switches that change what the backend is.

The Options entry is `OptionsScreenMetalModMixin`, which adds one button the ordinary way
(`addRenderableWidget`) and positions it between the button grid and the footer, following vanilla's
`repositionElements`. It deliberately does not join the screen's `HeaderAndFooterLayout`: that layout
is built in the constructor and re-populated by `init()`, and it registers its children through a
separate visit step, so depending on that bookkeeping to place one button would be more fragile than
positioning it directly. The injection reports itself as the `OptionsScreen.init` hook, because "the
button is missing" and "the injection did not apply" look identical from the screen.

**They apply while the game is running.** A lighting variant is chosen when a pipeline is *compiled*,
not when the device is created, so a toggle writes the setting, asks the device to rebuild, and the
render thread adopts it at the end of the next presented frame: every compiled pipeline is dropped and
each rebuilds with the other variant on its next draw. The frame boundary matters - a render pass
created earlier in the same frame still holds the old handle - and the compiled *shader pairs* are
kept, because that cache is keyed on the variant and its sources, so switching back is free.

**Precedence had to be corrected in game.** Three things can decide a switch, in this order:

1. an **in-game choice** made on the Lighting page, which wins for the rest of the session;
2. a **`-D` launch flag**, which seeds the session's starting value;
3. the **saved config file**, which decides when neither of the others applies.

A launch flag beating the file matters because the offline tools drive the lighting paths that way; a
saved toggle that silently outvoted them would make those runs lie about what they exercised. But a
flag must not beat the *user*. The first version resolved the flag on every read, which meant that
launching with `-Dmetalmod.dynamicLights=true` pinned the value: the toggle wrote the setting, asked
for a rebuild, and the rebuild re-resolved the same flag and adopted the same value. The row was also
disabled as "locked by a flag", so it could not even be clicked. `LightingSettings.choose*` now records
a session choice that outranks the flag, the rows are never disabled, and each caption reports the
value the running game will actually use.

`MetalMod_Test_26.2` launches with `-Dmetalmod.dynamicLights=true`. That still seeds each session as
"on"; removing the flag from the instance's JVM options leaves the saved file in charge instead.

**Second defect, found by removing the flag.** With no launch flag, turning dynamic lighting on did
nothing. `LightingSettings` was only half-wired: the *device* consulted it, but the extraction hook
still read `Boolean.getBoolean("metalmod.dynamicLights")` directly. So without the flag the pipeline
rebuilt as the lit variant while `LightCollector.extract` was never called - a lit shader with an
empty light set, which looks exactly like the feature being off. With the flag present the hook ran,
which is why the toggle appeared to work only in that configuration.

Both lighting hooks now go through `LightingSettings`, and `LightingSettingsTest` grew a source-level
guard: only `LightingSettings.java` may name a lighting property literal. It is a source check rather
than a behavioural one because the failure mode *is* "something else resolved the switch", which no
test of the resolver can observe. The guard was verified to fail by adding a second reader and
watching it report it.

Not implemented, and not claimed:

- **No diagnostic *shader* consumer.** The light records, the ABI version and the cluster table are
  published and unit-tested, but no shipped shader reads them as a consumer yet; the offscreen render
  check is the only consumer. Phases 8–9 will consume the native contract; actual packs belong to
  the optional compatibility track.
- **No explicit ownership modes.** There is no "vanilla only / MetalMod / external pack" selector and
  no pack-facing API. `-Dmetalmod.dynamicLights=false` is the current way to get the vanilla path.
- **No block-state-change invalidation.** A section is rescanned when it is (re)loaded or evicted, not
  when a single block changes, so a torch placed into an already-scanned section is not picked up
  until that section is reloaded. This is the remaining half of "updated on load, unload and block
  state changes".
- **No `castsShadow` flag and no occlusion.** Lighting is unshadowed and leaks through walls. That is
  a documented limitation, not a solved problem.
- Spot and area light types remain reserved-but-unsupported; no evaluator advertises them.

## 7. Evidence (2026-09-24)

All five Phase 5 gates remain green against the Phase 6 build:

| Gate | Result |
|---|---|
| `scripts/build_mod.sh` | native library, mod sources, standalone tests and jar built |
| `scripts/run_smoke.sh` | `ALL CHECKS PASSED` |
| `tools/shader_inventory/run.sh` | static 87/87, post 9/9, no diagnostics — also with `-Dmetalmod.dynamicLights=true -Dmetalmod.clusteredLights=true` |
| `tools/render_check/run.sh` | 165 PASS lines; `RENDER CHECK PASSED` |
| `net.metalmod.StandaloneTestRunner` | `ALL TESTS PASSED SUCCESSFULLY!` |

What the render check now covers for lighting, beyond the 6A assertions listed in §6:

- **6B:** an empty published set is pixel-identical to a vanilla device, one published red light lifts
  only red, a light at exactly its own radius and beyond it contributes nothing, two coloured lights
  sum into one pixel, four successive publications each reach the GPU through the fenced buffer ring,
  and a full 32-light set sums to the same pixel as one light of the same total intensity.
- **6C:** a clustered light lights the fragment it reaches, a light outside the cluster window lights
  nothing and is counted as unreachable, an overflowing cell still lights the fragment (clamped, not
  out of bounds), an empty grid is pixel-identical to vanilla, and GUI is excluded from clustering.
- **Particles:** the drawn variant really is the particle pair (flat set *and* cluster table
  declared), an unlit particle equals the baked lightmap alone, a published red source lifts only red
  and preserves alpha, a source outside its radius changes nothing, and the `alpha < 0.1` discard
  still fires under a light.
- **Entities:** the same shape of assertions through `ENTITY_CUTOUT`, plus a headroom term that is
  pinned at both ends - a partially lit entity must gain only the lightmap's remaining headroom, and a
  fully lit one must gain nothing - and the emissive-exclusion policy.
- **Items:** the same again through `ITEM_CUTOUT`, including the full-brightness case that is the
  entire argument for leaving the inventory alone.

**Block coverage added (2026-09-24).** `core/block.{vsh,fsh}` is flat-shaded like the particle pair,
so `BlockLightVariant` reuses that adapter. Its camera-relative position is
`Position + ModelOffset`, not `Position`: `block.vsh` builds `pos = Position + ModelOffset` and takes
its fog from `pos`, so the per-draw model offset has to be added first. That is the one family where
`Position` alone is the wrong frame, and it is exactly the difference that would put the light in the
wrong place if it had been assumed. These pipelines are reachable only through `SOLID_MOVING_BLOCK` /
`CUTOUT_MOVING_BLOCK` / `TRANSLUCENT_MOVING_BLOCK` - moving blocks, not inventory previews.

`core/glint` is deliberately excluded: an enchantment glint is an additive overlay, like the outline,
not a lit material.

### Buried sources are dropped (added 2026-09-24)

Reported in game: a glow squid sealed inside wool and dirt still lit the room, while a torch in the
same place did not. The first diagnosis was occlusion, and the block-reading rule below was built for
it - but that was the wrong cause, and the section is kept because it is still worth having. The
actual cause was that a glow squid should not have been a source at all: vanilla has **no entity
light**, light is a block property, and the squid's glow is an emissive texture rather than a light
level. A synthetic squid light invents illumination the game does not have, and a sealed squid is
simply the case where that is impossible to miss. The squid branch was removed. The torch is right because it is vanilla baked light, which propagates through the
world and is stopped by opaque blocks; the squid was wrong because the dynamic contribution is
unshadowed, so a distance falloff reached straight through the surrounding wall.

Full occlusion needs shadow maps and its own design, but the worst case is not a wall at all - it is a
source that is *buried*. A source inside opaque material, or in a cell with no opening, emits nothing
even in vanilla, because the light has no first step to take. `LightOcclusion` decides that from at
most seven block reads and the source is dropped outright; the count appears on F3 as `buried N`,
because "the light went out" and "the light was never collected" look identical on screen.

Opaqueness is `getLightDampening() >= 15`, the same quantity vanilla's light engine subtracts while
propagating. That is the right predicate rather than "is solid": water damps by 1 and still passes
light, so a dropped item in shallow water keeps its light, and glass damps by 0 and is transparent.
With the squid gone this guards the remaining non-vanilla sources: a dropped item that ends up inside
a block lights nothing, rather than shining through it.

**What this is not.** It is not occlusion. A source in an open room still lights the far side of the
wall behind it, and a source in a cavity larger than one cell still escapes, because neither is
decidable one cell at a time. Those remain the documented limitation of an unshadowed evaluator; what
this removes is the case that reads as a plain bug - a light that is visibly encased and still
glowing.

`LightClusterTest` covers the grid's own arithmetic: the window/extent invariant, cell assignment for
a near and a far light, overflow keeping the strongest entries, every published index being in range,
orphan and empty cases, window-origin snapping under camera movement, and cell arithmetic at a world
coordinate of order 3×10⁷. `BlockLightIndexTest` covers section keys, dirty/scan bookkeeping,
determinism of the publication and the environment record's version contract.
`PointLightTest` covers the 32-byte record layout, the second record's stride, and rejection of
non-finite, negative, out-of-range and overflowing input.

**Confirmed in game (2026-09-24, first Phase 6 run).** The log for the session records all five hooks
applied - `+GameRenderer.render +GameRenderer.resize +Window.onFramebufferResize
+LevelExtractor.extract +LevelExtractor.setLevel` - and a resource summary of
`failures=0 pipelineFailures=0 unboundBindings=0 missingVertexAttributes=0 slotCollisions=0
bindingKindMismatches=0 indexedFans=0` with `privateCpuAccess=0`. There is **no** `point-light proof
skipped` line, so the vanilla terrain pair matched its recorded hashes and the lit variant really was
built. F3 at render distance 32 reported `dynamic lights 1/32 dropped 0`,
`block sources 1053 in 512 sections`, and `environment minecraft:overworld t=186193 day=0.76
darken=11 rain=0.00 thunder=0.00 sky=yes v1`, which is the collector, the static index and the 6D
environment record all running in a real world.

**Not yet proven:** the in-game run above confirms wiring, not appearance. Held/dropped item
lifecycle, clustering quality, wall leakage, the large-coordinate path and frame cadence are still
unsigned, and no in-game screenshot of a lit terrain surface has been reviewed. No performance
measurement exists for the clustered path: the plan's 64-light budget is still a proposal. There is
no true GPU timing, so any future number must be labelled wall-time evidence. Particles and entities
remain unlit by design of this increment (see §6C).

### Defect found by that run: the static index queued the whole world

F3 reported `block sources 1053 in 512 sections, 88888 pending`. The 512 is the section cap working.
The pending count was a real defect: the chunk-load hook marked *every* section of *every* loaded
chunk for scanning, which at render distance 32 is about a hundred thousand sections, while the index
only ever queries the few hundred inside its 32-block window. Almost all of that bookkeeping was
written and never read, and the counter it produced was meaningless.

Sections are now read on demand, which removed the dirty set entirely; the chunk hook was reduced to
invalidation on unload. `pending` now means "sections inside the current search window not yet read"
and is bounded by that window. The palette prefilter was added at the same time, because on-demand
reading moved the scan cost onto the frame that first asks.

### Note: one representation changed during implementation

The cluster table was first published as an indexed `ivec4` array inside a uniform block. The header
of that block reached the shader correctly while every indexed element read as zero, on a device
where the identical flat `MetalModLights[...]` array worked. The representation was changed to an
RGBA32F texture read with `texelFetch` rather than spending the phase on that difference. The
observation is recorded here because it is a real constraint on this backend: an indexed array inside
a uniform block is not currently a dependable way to publish a table, and any future table-shaped
publication should start from a texture or a buffer the backend controls.
