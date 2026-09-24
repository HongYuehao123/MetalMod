# MetalMod: native Apple Silicon Metal backend for Minecraft (Fabric)

MetalMod is a **native Metal graphics backend** for Minecraft on macOS. Instead of translating
Vulkan through MoltenVK, it plugs into Minecraft's Blaze3D backend interface (`GpuBackend`) and
drives `MTLDevice` / `CAMetalLayer` directly: real Metal textures, pipelines compiled from
Minecraft's own shaders, real render passes, and presentation of the engine's render target to the
drawable.

Apple's **MetalFX** upscaling / frame interpolation, native material/lighting foundations and hybrid
ray tracing are the core later phases. **GLSL shaderpacks are optional future compatibility work**;
**dynamic lighting** is Phase 6 and is complete — see
[docs/phase6-plan.md](docs/phase6-plan.md) — and **MetalFX spatial upscaling** is Phase 7A, which is
implemented and verified offline — see [docs/phase7-plan.md](docs/phase7-plan.md). Known defects are
parked in [bug.md](bug.md).

> ## Current status: the Metal backend renders; Phase 6 is complete and Phase 7A is verified offline
>
> Minecraft selects the **Metal backend**, creates the device and a `CAMetalLayer`, creates real
> Metal **textures, views, buffers and samplers**, compiles the engine's shaders
> (**GLSL → SPIR-V → MSL**), encodes real render passes, and blits the engine's render target to the
> drawable. Verified in game:
>
> - the Mojang loading screen (logo + progress bar),
> - the main menu (logotype, buttons, sliders, splash, blurred panorama),
> - a loaded world: terrain, entities, particles, sky/weather, clouds, water, HUD, items and text,
>   with every telemetry counter at zero.
>
> All 87 vanilla render pipelines and all 9 post-processing passes compile
> (`tools/shader_inventory/run.sh`), and the pixel harness passes over the mechanisms the Phase 5
> bugs implicated. The block-selection outline (BUG-002) and the flat-black terrain/entities
> (BUG-003) are confirmed fixed; the Select World list scissor (BUG-001) is fixed and awaiting one
> in-game confirmation. See [bug.md](bug.md) and [ROADMAP.md](ROADMAP.md) §7.
>
> The backend is **opt-in and OFF by default**, so normal play keeps using the bundled
> Vulkan/OpenGL path. Enable it from **Mod Menu → MetalMod → "Metal Renderer Backend"**,
> or set `preferMetalBackend=true` in `config/metalmod.properties`, or pass
> `-Dmetalmod.metalBackend=true`. The backend is chosen once at startup, so **restart** after
> changing it.
>
> ## MetalFX status: spatial and temporal upscaling are implemented; frame generation is not
>
> **Spatial upscaling (7A) is implemented.** The world renders into a target of its own at a chosen
> fraction of the window and MetalFX returns it to native, while the interface keeps drawing at
> native resolution. It is verified offline end to end - native smoke test, a harness that drives the
> engine's own `MainTarget` and `FrameGraphBuilder`, mixin-target checks, and pixel assertions in the
> render check - but **no in-game session has confirmed it yet**. The six observations that close it
> are in [docs/phase7-plan.md](docs/phase7-plan.md) §2.4.
>
> **Temporal upscaling (7B) is implemented with a camera motion producer.** A native kernel
> reconstructs each pixel's world position from the level depth and reprojects it through the previous
> frame's view-projection, so the temporal scaler accumulates a moving camera and static geometry
> correctly. The current/previous-transform contract (`metalfx/SceneMotion`) is shared with Phase 8C,
> and history is reset on camera cuts, world changes and resizes. **Geometry that moves independently
> of the camera still ghosts** - a mob, a particle or an animated block reprojects as if it were
> static - because its per-object velocity is Phase 8C's contract. When temporal cannot run, the frame
> falls back to Spatial and says why on F3 and the settings page. **Frame generation (7C) is not
> implemented** - it needs those same per-object motion vectors plus frame-loop pacing. Nothing here
> is a stub that does nothing: the retired MoltenVK-interop scalers are deleted, and every control on
> the Upscaling screen configures the path that runs.

---

## What is implemented

### Metal backend (Phases 1–4)
- **Backend selection.** `PreferredGraphicsApiMixin` prepends `MetalBackend` to
  `PreferredGraphicsApi.getBackendsToTry()`, keeping the vanilla backends as a fallback. A failure
  during backend creation degrades cleanly to Vulkan/OpenGL.
- **Device and surface.** `MetalDevice`/`MetalSurfaceBackend` create the `MTLDevice`, command
  queue and a `CAMetalLayer` on the GLFW window's `NSView`; presentation commits on the same queue
  as rendering so passes and the blit stay ordered.
- **Resources (Phase 2).** Real `MTLTexture` (2D / array / cube, mips), `MTLTextureView`,
  `MTLBuffer`, `MTLSamplerState`, fences and query pools. All storage is
  `MTLResourceStorageModeShared`, so uploads and readbacks are direct. `MetalFormat` holds the
  single `GpuFormat` → Metal mapping.
- **Pipelines and shaders (Phases 3–4).** `MetalRenderPipeline` builds
  `MTLRenderPipelineState` + `MTLDepthStencilState` (blend, cull, fill, topology, vertex
  layouts) from Minecraft's `RenderPipeline` + `ShaderSource`. Shaders go GLSL → SPIR-V
  (`GlslCompiler.createIntermediary`) → MSL (SPIRV-Cross), with reflection driving name-based
  uniform/texture/sampler binding. Pipelines the engine never precompiles are compiled lazily on
  first use.
- **Render passes.** `MetalRenderPassBackend` encodes indexed, multi and grouped draws, scissor,
  deferred bindings and Metal debug groups.
- **Presentation.** The engine's render-target colour view is blitted into the drawable with a
  built-in full-screen-triangle MSL pipeline.

### MetalFX spatial upscaling (Phase 7A)
- **The level gets its own target.** Minecraft draws the level and the interface into one target, and
  every interface coordinate is in that target's pixels - which is why resizing it breaks the GUI.
  So the level renders into a second target at `round(native × scale)` and MetalFX returns that to
  native, leaving the interface drawing where it always did. `GameRenderer.mainRenderTarget()` answers
  with the scaled target while the level renders and with the engine's own at every other moment.
- **The upscale is a backend pass.** `mmm_fx_spatial_run` creates its own command buffer, encodes the
  MetalFX spatial scaler, commits it and releases it - all on the device queue, so it lands behind the
  passes that wrote the input and ahead of the present. The engine's own encoders are untouched.
- **Off by default, and the off path is untouched.** At scale 1.0 nothing is allocated, no scaler is
  created, and the frame is the pre-Phase-7 frame.
- **Controls**: **Options → MetalMod… → MetalFX Upscaling** (or Mod Menu → MetalMod). The page steps
  the scale, cycles the upscaler through off / spatial / temporal, and reports the live sizes, which
  effect actually ran,
  how many frames it has upscaled, and the reason if one failed. It also shows a toast in world when a
  change lands, so the effect is visible without opening F3. The same settings come from
  `renderScale` / `upscaler` in `config/metalmod.properties` or
  `-Dmetalmod.renderScale=0.5 -Dmetalmod.upscaler=spatial`; an in-game choice beats a launch flag,
  which beats the file.
- **How to check it works**: pick 75%, and the status line and the toast both name
  `world <w>x<h> -> native <w>x<h>` while `Upscaled frames` counts up. Press `Native` and the world
  sharpens again. The one thing to look at hard is the **HUD at 50%** - it must be exactly as sharp
  as at 100%, because the whole design exists to keep it that way. [TESTING.md](TESTING.md) §6.E is
  the five-minute pass.

### F3 debug section
MetalMod registers a `DebugScreenEntry`, so its status lines appear in the F3 overlay alongside
vanilla and Sodium. Registration takes three parts, because `DebugScreenEntries.register` is
`private static` **and** a registered entry stays hidden unless it is given a status — see
`net.metalmod.debug.DebugScreenRegistration`.

### Performance capture
Press **F8** in a world to record 60 seconds after a five-second countdown (press again to stop).
MetalMod saves a readable summary and per-frame CSV under the game's `debug/metalmod/` folder.
It captures hitches, uploads, allocations, submissions and waits on Metal, and basic frame times
on Vulkan/OpenGL for comparison. A capture button is also available in Mod Menu → MetalMod.

Press **F7** first to record a **route**: stand where you want a measurement, move on, stand again,
and press F7 to stop. F8 then teleports along that route and holds each spot for the recorded time, so
two captures differ only in the dimension and the backend, and `summary.txt` breaks the capture down
waypoint by waypoint. See [TESTING.md](TESTING.md) for both procedures.

### Apple Silicon unified-memory telemetry
Physical RAM, available/compressed memory, swap, process footprint, Metal allocated bytes and the
working-set cap, plus the macOS kernel memory-pressure level, shown on the F3 overlay. A
`DISPATCH_SOURCE_TYPE_MEMORYPRESSURE` listener performs conservative scratch reclaim.

> The LWJGL allocator interception that once sat behind `enableUnifiedMemoryPool` was **removed**:
> LWJGL 3.4 needs native function pointers for its fast allocation path, a Java pool cannot supply
> them honestly, and mixing libc- and pool-allocated pointers behind one `free()` risks corruption.
> The native UMA pool remains for telemetry, and `enableUnifiedMemoryPool` now only gates the F3
> memory line.

### Native substrate
`libmetalmod.dylib` is a thin Objective-C++ layer over Metal/MetalFX/QuartzCore; the Java side
reaches it through Panama FFI (`java.lang.foreign`). A standalone native smoke test
(`native/tests/metal_smoke.mm`) exercises device, clear, resources, pipelines and a triangle draw.

Render targets and depth buffers are created with shared storage, which is the measured-faster
configuration: private storage for them came out about 9% slower in an A/B on one route, so it sits
behind `-Dmetalmod.privateTextures=all` and is off. Uploads and buffer copies share one command buffer
per frame instead of committing one each, which is what the underground chunk-mesh path needed. Both
are described under [TESTING.md](TESTING.md).

---

## Known limitations

These are the reasons the mod is not a drop-in replacement yet.

1. **Phase 5 verification: visual parity confirmed, frame-rate parity still owed.** All fourteen
   fixes that were awaiting an in-game look are now confirmed (see [bug.md](bug.md)): menus and the
   inventory, sky and clouds, lighting, terrain and entities, selection and chunk-border lines, depth
   behaviour, and the F3 health counters at zero. The frame-rate half of the exit criterion has not
   been measured cleanly yet - the two existing capture pairs disagree - so what remains is the
   controlled comparison in [TESTING.md](TESTING.md) §5, not more code.
2. **Performance: phase 5's criterion is met on the routed comparison.** Three captures of one recorded
   Overworld route in one sitting put Metal at 81.1 FPS average against Vulkan's 69.2, with a better
   tail (p95 19.2 ms against 25.0 ms): 3% behind on the light above-ground stage and 29% ahead on the
   heavy underground one. Before that, utility submission batching cut the chunk-mesh upload path's
   cost by 95% per frame, and private storage for render targets measured 9% *slower* and is off by
   default. See [TESTING.md](TESTING.md) for the numbers and the procedure.
3. **MetalFX frame generation is not implemented (Phase 7C), and temporal upscaling has no motion for
   independently moving geometry.** Temporal upscaling (7B) is implemented against a native camera
   reprojection producer, so a moving camera and static geometry accumulate correctly, but a mob, a
   particle or an animated block carries the terrain's velocity and ghosts; that per-object velocity
   is Phase 8C's contract, which frame generation also needs. Frame generation additionally needs a
   display-link pacer so two drawables land on different refreshes. See
   [docs/phase7-plan.md](docs/phase7-plan.md).
4. **Render-resolution scaling is not confirmed in game, and its quality/performance comparison is
   not measured.** It is implemented against the level's own target - which is what keeps the GUI
   intact, the failure that reverted the pre-Phase-5 attempt - and verified offline, but a session
   has not looked at it, and the native-versus-scaled frame-time comparison in
   [TESTING.md](TESTING.md) §6.D has not been run.
5. **Indirect draws are no-ops.** `drawIndirect` and `drawIndexedIndirect` are unimplemented, and the
   matching `DeviceFeatures` are reported `false` so the engine never takes those paths. Vanilla is
   unaffected; this is the gap to close before batching mods (e.g. Sodium).
6. **The UMA pool is telemetry-only** (see above).

---

## Retired architecture

The original **"MetalFX on top of MoltenVK / `VK_EXT_metal_objects`"** design could not own
presentation and could not express MetalFX or ray tracing, so the Metal backend replaced it. Its
code is now **deleted**, not merely unused:

- native: `metalmod_bridge.mm`, `metalmod_pacer.mm`, `metalmod_compositor.mm`,
  `metalmod_spatial.mm`, `metalmod_temporal.mm`, `metalmod_interpolator.mm`,
  `metalmod_internal.h`, and the bundled `vulkan/` headers;
- Java: `net.metalmod.render.VulkanFrameManager`, `net.metalmod.render.JitterHelper`, and the
  per-frame hook they had in `GameRendererMixin`.

`MetalBridge` now binds only the UMA/memory surface. The dylib exports the Metal backend (`mmm_*`)
and the memory pool (`metalmod_uma_*`, `metalmod_get_memory_telemetry`,
`metalmod_memory_pressure_init`).

---

## Building

### Prerequisites
- Apple Silicon Mac, macOS 26+
- CMake 3.28+, JDK 22+ (JDK 26 used here), Python 3

### Build

```bash
./scripts/build_mod.sh
```

This compiles `native/build/libmetalmod.dylib` via CMake, then compiles the Java sources against
the **real Minecraft client jar** (not API stubs) and packages `build/libs/metalmod-1.0.0.jar`
with the dylib embedded under `natives/`. `javac` therefore verifies every Minecraft API call.
`./gradlew` does not exist; `build.gradle` is not a working mod build.

Compile against a different instance with
`METALMOD_MC_INSTANCE=<dir> ./scripts/build_mod.sh`.

### Verify

```bash
# native substrate
./native/build/metalmod_smoke          # prints ALL CHECKS PASSED

# Java-side standalone suite
JAVA=/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home/bin/java
$JAVA --enable-native-access=ALL-UNNAMED \
    -cp build/classes:build/test-classes:<client-classpath> net.metalmod.StandaloneTestRunner

# one shader pair through the real pipeline, outside the game
# (prints the SPIR-V interfaces, both MSL sources and Metal's verdict; non-zero exit on rejection)
./tools/shader_repro/run.sh \
    assets/minecraft/shaders/core/animate_sprite.vsh \
    assets/minecraft/shaders/core/animate_sprite_interpolate.fsh

# every vanilla pipeline and every post-processing pass: exits 0 only when all compile
./tools/shader_inventory/run.sh        # static 87/87, post 9/9

# every mixin's injection point, resolved against the real client jar (no game launch)
./tools/mixin_check/run.sh             # MIXIN CHECK PASSED

# Phase 7A end to end, offscreen: the scaled level target, the engine's own frame graph, the upscale
./tools/scaling_check/run.sh           # SCALING CHECK PASSED

# render real vanilla pipelines offscreen and check the pixels
# (gui, gui_textured, solid_terrain, entity_cutout, lines, sky fan, blits, multi-draw, scissor,
#  atlas, mip selection, blending, every blend state vanilla uses, index width/offsets, colour-target limit, lightmap, post-processing, alpha cutout, topologies, texel buffers, write masks, depth copy; 80 assertions)
./tools/render_check/run.sh            # RENDER CHECK PASSED

# print the generated MSL for the shader pairs whose name contains the substring, or for all of them
# (add to any of the commands above)
-Dmetalmod.dumpMsl=entity     # or =all
```

---

## Running

1. Copy `build/libs/metalmod-1.0.0.jar` into the instance's `mods/` folder.
2. Enable the backend: **Mod Menu → MetalMod → Metal Renderer Backend: ON**, or
   `preferMetalBackend=true` in `config/metalmod.properties`, or add
   `-Dmetalmod.metalBackend=true` to the profile's JVM arguments.
3. Restart the game. With the backend on, the window title starts with `Minecraft [MetalMod: ...]`
   and F3 has a MetalMod section.

---

## Directory structure

```
MetalMod/
├── native/                             # Objective-C++ / Metal library
│   ├── include/metalmod/               # Exported C API + struct definitions
│   ├── src/
│   │   ├── metalmod_metal.mm           # The Metal backend's native substrate (device, layer,
│   │   │                               #  textures, buffers, pipelines, render passes, blit)
│   │   ├── metalmod_metalfx.mm         # MetalFX spatial/temporal scalers, frame pacing
│   │   └── metalmod_memory.mm          # UMA pool, Mach VM telemetry, pressure source
│   └── tests/metal_smoke.mm            # Native smoke test
├── scripts/
│   ├── build_mod.sh                    # Authoritative build
│   ├── build_classpath.py              # Classpath from the launcher's version JSON
│   └── run_smoke.sh                    # Runs the native smoke test
├── tools/
│   ├── shader_repro/run.sh             # Reproduce one shader pair's Metal pipeline offline
│   ├── shader_inventory/run.sh         # Compile all 87 vanilla pipelines and report pass/fail
│   ├── render_check/run.sh             # Render real pipelines offscreen and check the pixels
│   ├── scaling_check/run.sh            # Phase 7A: scaled level target + MetalFX upscale, offscreen
│   └── mixin_check/run.sh              # Resolve every mixin injection point against the client jar
├── src/main/java/net/metalmod/
│   ├── backend/                        # Metal GpuBackend implementation
│   ├── client/                         # Fabric entrypoint + Mod Menu config screen
│   ├── config/                         # Persistence (backend toggle, memory options)
│   ├── debug/                          # F3 debug entry
│   ├── ffi/                            # Panama bindings to the UMA/memory native surface
│   ├── memory/                         # Native UMA telemetry + pressure handling
│   └── mixin/                          # Backend selection and hook diagnostics
└── src/test/java/net/metalmod/         # Standalone suite + JUnit tests
```

---

## Controls & configuration

- **Metal Renderer Backend**: Mod Menu → MetalMod (restart required), or
  `config/metalmod.properties`, or `-Dmetalmod.metalBackend=true`.
- **Dynamic light preview**: add `-Dmetalmod.dynamicLights=true` to JVM arguments with the Metal
  backend enabled. This experimental path lights terrain and particles from held/dropped
  light-emitting block items, up to 64 unshadowed sources, and only through the
  recognized vanilla terrain, particle, entity, item and moving-block shaders. Emissive entity passes
  are excluded, and the inventory is left alone by the same rule that fills only vanilla's remaining
  lightmap headroom. A source buried in or sealed by opaque blocks is dropped, so it cannot light
  through the wall around it; lights are otherwise unshadowed.
  Add `-Dmetalmod.clusteredLights=true` as well to evaluate them through a 16-block cluster grid
  instead of the flat list; without it the shader loops over every published source per fragment.
  Both are off by default, and both are on the in-game **Lighting** page (**Options -> MetalMod...**
  -> Lighting, or Mod Menu -> MetalMod) alongside the diagnostic point-light proof. The lighting
  switches apply while the game is running; an in-game choice beats a `-D` launch flag, which beats
  the saved file.
- **F3 overlay**: MetalMod status — the backend the engine selected, the framebuffer resolution, the
  active dynamic-light snapshot count, the static block-source index counters, the environment
  summary when enabled, and the `unbound/missingAttr/failed` health counters.
- **A render-scale change requests a resource reload**, the same one F3+T performs, because the engine
  caches pipelines and other resources against the frame's shape. It runs between frames.
- **Anti-aliasing is not implemented.** MetalFX spatial scaling reconstructs edges but does not
  anti-alias them, and Minecraft has no AA of its own. Three routes and a recommendation are in
  [docs/antialiasing-plan.md](docs/antialiasing-plan.md); the short version is that MetalFX temporal
  mode is the right answer and it is waiting on the same motion-vector pass Phase 8C owes.
- **F10**: flip the render scale between native and your last scaled setting, in place, to compare
  the F3 `frame` line without moving. The only fair way to judge whether the upscaler costs frames.
- **F9**: measure what the GPU actually costs — a full-target fill at the render resolution and at
  native, plus the MetalFX upscale — reported as a toast and in the log. Frame rate cannot answer
  whether render scaling is worth it on a display-paced frame; this can.
  See [TESTING.md](TESTING.md) §6.D2.

  Measured in a real session at 5120x2664: **50% runs 13.5 ms / 74 fps against native's 16.9 ms /
  61 fps** at the same spot. Render scaling saves about 20% of a full-scene frame here, and the
  upscale itself is inside the noise. Press **F10** to take that comparison yourself - it flips the
  scale in place so the F3 `frame` line is the only thing that changes.
- **Config screen**: the Metal backend toggle, the UMA memory option, and two live sub-pages —
  **Lighting** and **Upscaling**.
- **Render scale / upscaler**: `-Dmetalmod.renderScale=0.5` and
  `-Dmetalmod.upscaler=spatial|temporal|off`, or the Upscaling page, or `renderScale` / `upscaler` in
  `config/metalmod.properties`. The scale is the fraction of the window the world renders at (100%,
  85%, 75%, 67%, 50%); the upscaler is MetalFX spatial, MetalFX temporal, or off (which renders at
  native resolution). Temporal produces its motion vectors with a camera reprojection pass, so it
  resolves detail across frames for a moving camera and static geometry; independently moving
  geometry is not in the field yet. If the machine or the dylib cannot run the requested effect, the
  frame falls back to Spatial and F3 and the settings page state the reason.
