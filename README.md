# MetalMod: native Apple Silicon Metal backend for Minecraft (Fabric)

MetalMod is a **native Metal graphics backend** for Minecraft on macOS. Instead of translating
Vulkan through MoltenVK, it plugs into Minecraft's Blaze3D backend interface (`GpuBackend`) and
drives `MTLDevice` / `CAMetalLayer` directly: real Metal textures, pipelines compiled from
Minecraft's own shaders, real render passes, and presentation of the engine's render target to the
drawable.

Apple's **MetalFX** upscaling / frame interpolation, **dynamic lighting**, shaderpack support and
native ray tracing are later phases; see [ROADMAP.md](ROADMAP.md). Known defects are parked in
[bug.md](bug.md).

> ## Current status: the Metal backend renders — Phase 5 is in final verification
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
> ## ⚠️ MetalFX is not implemented yet
>
> MetalFX upscaling and frame interpolation return in Phase 8, against the backend's own textures.
> The retired MoltenVK-interop scalers have been deleted, and the config screen no longer exposes
> scaling/frame-generation settings that did nothing.

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

Render targets and depth buffers are created with private storage (they are what the frame spends its
bandwidth on); a texture the engine uploads into is not a render attachment and stays shared. Uploads
and buffer copies share one command buffer per frame instead of committing one each, which is what
the underground chunk-mesh path needed. Both are described under [TESTING.md](TESTING.md).

---

## Known limitations

These are the reasons the mod is not a drop-in replacement yet.

1. **Phase 5 verification: visual parity confirmed, frame-rate parity still owed.** All fourteen
   fixes that were awaiting an in-game look are now confirmed (see [bug.md](bug.md)): menus and the
   inventory, sky and clouds, lighting, terrain and entities, selection and chunk-border lines, depth
   behaviour, and the F3 health counters at zero. The frame-rate half of the exit criterion has not
   been measured cleanly yet - the two existing capture pairs disagree - so what remains is the
   controlled comparison in [TESTING.md](TESTING.md) §5, not more code.
2. **Performance: the CPU-side upload hitches are gone; parity now hinges on GPU work.** A paired
   capture put Metal at 106 FPS against Vulkan's 133, and the gap turned out to be a tail of hitches
   from the chunk-mesh upload path — 53.5 per-frame uploads underground, each creating its own command
   buffer. Utility submission batching cut that path's cost by 95% per frame and the same workload
   ran 21% faster, with the remaining slow frames all GPU-bound (drawable waits). Cross-backend
   numbers still disagree between runs, so a drift-controlled A/B comparison is owed. See
   [TESTING.md](TESTING.md) for the numbers and the procedure.
3. **MetalFX / frame generation are not implemented (Phase 8).** They return against the backend's
   own textures, and frame generation additionally needs a display-link pacer so two drawables land
   on different refreshes.
4. **Internal resolution scaling is not implemented.** Shrinking the main render target breaks the
   GUI layout (scissor rectangles exceed the render area); doing it properly means rendering the
   world into its own target and upscaling that.
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
│   │   └── metalmod_memory.mm          # UMA pool, Mach VM telemetry, pressure source
│   └── tests/metal_smoke.mm            # Native smoke test
├── scripts/
│   ├── build_mod.sh                    # Authoritative build
│   ├── build_classpath.py              # Classpath from the launcher's version JSON
│   └── run_smoke.sh                    # Runs the native smoke test
├── tools/
│   ├── shader_repro/run.sh             # Reproduce one shader pair's Metal pipeline offline
│   ├── shader_inventory/run.sh         # Compile all 87 vanilla pipelines and report pass/fail
│   └── render_check/run.sh             # Render real pipelines offscreen and check the pixels
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
- **F3 overlay**: MetalMod status — the backend the engine selected, the framebuffer resolution, and
  the `unbound/missingAttr/failed` health counters that explain a black or missing object.
- **Config screen**: the Metal backend toggle and the UMA memory option. MetalFX/scaling controls are
  gone until Phase 8 — they configured a pipeline that no longer exists.
