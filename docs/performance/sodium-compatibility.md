# Sodium compatibility investigation — 2026-10-04

The user reopened Sodium integration as an optional performance goal. The current roadmap's
earlier decision not to pursue it is superseded. It was recorded as a Phase 5 compatibility risk;
Phases 3/4 established the Metal pipelines and shader translator.

## Separate packaging restored (2026-10-05)

The user subsequently restored separate core + adapter packaging. Current builds produce a
standalone core and, with `METALMOD_SODIUM_JAR` set, the optional `metalmod-sodium-0.1.1.jar`.
The fatal pre-launch guard is retained in that adapter. Official 0.9.3-alpha.1 requires Minecraft
26.3 and changes the renderer API; support requires a corresponding port. See
[specific verification and release review](sodium-separate-20261005.md).

## Unified packaging (historical, 2026-10-05)

The user requested one JAR with automatic installed-mod detection. The canonical build now
bundles only MetalMod's integration classes into `metalmod-1.0.0.jar`, without Sodium itself or a
mandatory Sodium runtime dependency. The mixin plugin registers factory targets only for the
supported release; when Sodium is absent, no Sodium-targeted mixins/classes are loaded by it.
Other Sodium versions fail fatally in Fabric pre-launch with an explicit compatibility message;
throwing only from a Mixin plugin proved insufficient (BUG-047). The old separate adapter is
rejected by Fabric's `breaks` metadata to prevent duplicate mixins. Installation requires a restart.
The separate-JAR descriptions and hashes below record the earlier experimental builds.
Current verification is recorded in `build/reports/sodium-autodetect-20261005/` and
`docs/performance/sodium-autodetect-20261005.md`.

## Initial unadapted release and failure

- Official [Sodium 0.9.2 for Minecraft 26.2](https://github.com/CaffeineMC/sodium/releases/tag/mc26.2-0.9.2).
- Fabric artifact `sodium-fabric-0.9.2+mc26.2.jar`, downloaded through the official project's
  Modrinth version API. Published SHA-512 verified before use. Metadata and artifact are in
  `build/reports/sodium-compatibility/`.
- Test command: `python3 tools/temporal_validation/run.py --source-game "$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2" --world '新的世界' --output /private/tmp/metalmod-sodium-probe-20261004 --extra-mod build/reports/sodium-compatibility/sodium-fabric-0.9.2+mc26.2.jar`.
- Only a copied world/instance was changed. The normal instance was not given Sodium.
- Startup and Sodium's solid-terrain shader compilation succeed. First world rendering crashes:

```
ClassCastException: net.metalmod.backend.MetalRenderPassBackend cannot be cast to
net.caffeinemc.mods.sodium.mixin.core.GlRenderPassAccessor
    at GLDrawContext.setContext(GLDrawContext.java:20)
    at DefaultChunkRenderer.render(DefaultChunkRenderer.java:111)
```

Archived evidence: `build/reports/sodium-compatibility/console.log`, `crash.txt`, `DrawBackend.txt`,
`draw-context.txt`, `VKDrawContext.txt`, `VKMultiDrawContext.txt` and `chunk-renderer-api.txt`.
These API dumps use the tested release JAR, not speculative mappings or API stubs.

## Confirmed cause

`DrawBackend.chooseBackend()` checks for the concrete vanilla `VulkanDevice`. Any other backend,
including MetalMod, is assigned `OPENGL`. `DrawContext.create()` then creates `GLDrawContext`,
which assumes the render pass is vanilla OpenGL and calls GL directly.

Forcing the Vulkan enum does not fix this: `VKDrawContext` casts the underlying render pass to
`VulkanRenderPassAccessor`, obtains a Vulkan command buffer and pipeline layout, and calls
`VK13.nvkCmdPushConstants` with 20 bytes of region/camera/time/id data. It needs actual Vulkan
objects. MetalMod must continue to own Metal commands and presentation directly.

The earlier roadmap assumption that Sodium would follow Blaze3D automatically was incorrect.
Indirect draws are still unimplemented, but they are not the cause of this first failure.

## Integration gates

1. Add an optional, version-scoped Metal draw context selected by MetalMod's backend identity.
   Keep Sodium absent from the core mod's mandatory dependencies. Use the published release's
   actual APIs and retain vanilla/OpenGL/Vulkan selection for those backends.
2. Bind Sodium region parameters through a documented Metal-compatible shader buffer; preserve
   its vertex format, index/base-vertex semantics and per-region updates. Establish correct
   direct multi-draw rendering first; only advertise indirect capabilities once implemented.
3. Verify chunks, translucency, culling, arena resizing/defragmentation and world/dimension reloads.
   The observed first crash does not establish that these later paths work.
4. Verify dynamic-light shader adaptation and temporal colour/jitter, camera/object motion,
   reactive coverage, history resets, hand/HUD composition and frame-generation lifecycle on
   Sodium terrain. Existing vanilla fixtures do not prove Sodium compatibility.
5. Compare a reproducible route against the current vanilla Metal backend, retaining matched
   resolution/render distance and reporting frame-time tails. Install normally only after the
   compatibility and performance gates pass.

MetalFX and future RT remain native backend responsibilities. Sodium's meshing/culling/arenas
can potentially improve CPU work, but the adapter must preserve geometry/material information
for the planned material and acceleration-structure contracts. The optional adapter now renders the tested release; performance selection still needs the
foreground comparison below.


## Native adapter implementation

`compat/sodium/` builds a separate optional JAR against the official release, with exact Fabric
version dependency and required factory mixins. The factories select `MetalDrawContext` and
`MetalDrawBatch` only for an actual `MetalDevice`; no fake OpenGL/Vulkan backend or indirect
feature flag is used. Direct indexed draws preserve index-element offsets, base vertices and
batch order. Sodium retains its own meshing, compact vertices, culling and arena ownership.

The core recognizes only the verified Sodium shader pair, replaces its scalar/push parameters
with `MetalModSodiumRegion`, and applies the existing point/dynamic/clustered lighting ABI.
The inline native uniform API copies 32 bytes per region into the render encoder and invalidates
cached buffer identities. See [build, ABI and usage](../../compat/sodium/README.md).

Official source inspected: tag `mc26.2-0.9.2`, commit
`6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a`. The first adapter launch failed because ordinary
adapter classes were inside the configured Mixin package; these classes now live outside the
`.mixin` subpackage. That failed run remains archived rather than counted as passing evidence.

## Verification so far

Evidence: `build/reports/sodium-adapter/` and its `verification.json` with artifact SHA-256 hashes.

- Canonical real-client build, including the optional adapter: pass.
- Native smoke: **921 checks**, including copied inline uniform bytes in both stages and restoring
  a previously cached buffer after an inline binding; invalid inputs rejected.
- Shader inventory: **87/87 static + 9/9 post**, no diagnostics.
- Pixel gate: **204 vanilla + 66 Sodium checks**. Twelve real compact-vertex pipelines cover all
  solid/cutout/translucent layers across ordinary, point, flat dynamic and clustered lighting.
  Fixtures exercise nonzero base vertex/index offset, camera-relative region origin, region ID
  and section fade lookup, baked light, added red light and cutout discard. Altered shader
  semantics are rejected before adaptation.
- Standalone Java/FFI tests: pass.
- Copied-world temporal run: **385 checks, zero failures**. Terrain renders through dimension
  changes/reconnect, resize, temporal camera/object motion, native hand/HUD and history resets;
  backend resource/pipeline/unbound/attribute counters remain zero. Native/spatial/temporal terrain
  screenshots are retained. This initial runtime build preceded the strict fingerprint guard;
  the final guarded sources passed all twelve offline shader/light/layer fixtures.
- Initial frame-generation run: interrupted before any stage by the locked screen and retained
  as an interruption. Fresh unlocked run: **186 checks, zero failures**; saved-On fresh JVM restart:
  **15 checks, zero failures**. This uses the final guarded adapter and covers native/spatial/temporal
  composition, camera/entity motion, menu, dimension/reconnect, resize/fullscreen and minimize/restore.
  Broader experimental FG delivery BUG-045 remains open; one passing compatibility run does not close it.
- Matched fresh-JVM vanilla/Sodium/Sodium/vanilla throughput comparison: **eight clean accepted
  captures** with the screen unlocked; results below. Offline and FPS-limited temporal results
  are not used as throughput measurements.

The normal Minecraft installation, configuration, mods and saved worlds remain unchanged.
Compatibility is scoped to this release and tested worlds. Moving chunk/arena stress, resource
packs with custom Sodium shaders and native ray-tracing lifecycle integration are not certified.


## Foreground ABBA results

Four fresh JVMs in vanilla/Sodium/Sodium/vanilla order, each with an initial 60-second warmup,
20 seconds after changing reconstruction mode, and a 30-second accepted capture per mode.
Fixed dense underground waypoint 13; output **5120×2664**, render distance **32**, IMMEDIATE
presentation, VSync/FG/temporal off; dynamic lighting on. Spatial strength 25 uses 75% linear
scene dimensions. Core/adapter/release JARs are hash-pinned in the report. No GPU instrumentation,
sampling or screenshots during the captures. Quarter-to-quarter draw-count drift is below 0.016%
in every capture, so startup loading is not driving the measured difference.

| Mode | Renderer | Render-thread CPU ms | FPS | Mean frame ms | Mean capture p95 ms | Mean capture p99 ms |
|---|---|---:|---:|---:|---:|---:|
| Native | Vanilla Metal | 10.629 | 58.61 | 17.061 | 23.942 | 30.625 |
| Native | Sodium Metal | 3.607 | 59.83 | 16.714 | 19.084 | 31.538 |
| Spatial strength 25 | Vanilla Metal | 10.537 | 58.01 | 17.238 | 25.785 | 30.150 |
| Spatial strength 25 | Sodium Metal | 3.705 | 59.75 | 16.737 | 18.987 | 34.214 |

Native CPU decreases **66.06%**, spatial CPU **64.84%**. FPS increases **2.08% / 2.99%**,
with presentation staying near 60Hz. This establishes scoped CPU headroom, not a 66% FPS gain.
Native calls decrease about **72%** (58,108→16,060/frame); logical draws decrease about **20%**
(19,199→15,384), rather than the much lower transient warmup draw count. The p95 improves,
while p99 worsens in both modes; rare frame stalls are not solved. GPU duration and thermals
were not measured in these uninstrumented captures.

Raw frames, display intervals, CPU samples, hashes, launch/config metadata and terrain images:
`build/reports/sodium-adapter/abba/`. `analyze_renderer.py` rejects interrupted/instrumented,
compiling, recovering, resized, incomplete or mismatched captures. Normal-instance config,
options and mod JAR manifests compare exactly equal before/after.

### Lava difference resolved by controlled setting test

The observed cave lava difference is caused by Sodium's **Fluid Culling = Optimized**, not a
demonstrated Metal adapter defect. The tested release defaults `hiddenFluidCulling` to true;
its CPU fluid mesher applies a flooded-cave exposure heuristic before emitting faces. Its
[official tooltip](https://raw.githubusercontent.com/CaffeineMC/sodium/mc26.2-0.9.2/common/src/main/resources/assets/sodium/lang/en_us.json)
acknowledges surface interruptions at shallow angles. See the release's
[fluid-meshing implementation](https://github.com/CaffeineMC/sodium/blob/mc26.2-0.9.2/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/DefaultFluidRenderer.java).

A fresh controlled launch changed only `quality.hidden_fluid_culling` true→false, audited against
the prior Sodium configuration. Same JAR hashes, copied source world, waypoint, scene, dynamic
lighting and resolution. Both native/spatial captures passed validity checks and restored the
missing patches. Native image ROI (1030,590)–(1240,720) contains **5173 orange pixels in vanilla,
200 in Optimized, 5173 in Default**. Image analysis used R>100, R>1.3G, G>1.5B without modifying
images; animation colours need not be identical, but the lava-covered region returns.

| Fluid Culling = Default control | Render-thread CPU ms | FPS | p95 ms | p99 ms |
|---|---:|---:|---:|---:|
| Native | 3.595 | 59.93 | 18.553 | 30.835 |
| Spatial strength 25 | 3.707 | 59.76 | 18.728 | 31.758 |

This single control per mode supports retaining CPU headroom with the observed surfaces restored;
it is not a new four-launch ABBA or proof that this setting is free under every workload. Native
logical draws rise 15,384→16,843 and calls 16,060→17,527 compared with Optimized. The dramatic CPU
saving is therefore not explained by the omitted lava patches. Average FPS still remains near
60Hz; we have not established a large FPS upgrade or solved the rare frame stalls.

**Recommended adapter profile: Quality → Fluid Culling → Default**, persisted as
`quality.hidden_fluid_culling=false` in `sodium-options.json`. No automatic override of a user's
Sodium settings or normal-installation writes is introduced. BUG-046 is resolved; the adapter
stays experimental pending broader quality/stress and future material/RT lifecycle work.

Evidence: `build/reports/sodium-fluid/` (raw captures, config audit, metadata and images), plus
`docs/bugs/sodium-fluid-default-2026-10-04.png`. Core/adapter JARs are unchanged from the passing
five offline gates and live compatibility tests; this investigation changed test tooling and
documentation only. Normal-instance options/config/mod hashes still match the earlier manifest.
