# MetalMod: native Apple Silicon Metal backend for Minecraft (Fabric)

MetalMod is a **native Metal graphics backend** for Minecraft on macOS. Instead of translating
Vulkan through MoltenVK, it plugs into Minecraft's Blaze3D backend interface (`GpuBackend`) and
drives `MTLDevice` / `CAMetalLayer` directly: real Metal textures, pipelines compiled from
Minecraft's own shaders, real render passes, and presentation of the engine's render target to the
drawable.

Apple's **MetalFX** upscaling / frame interpolation, **dynamic lighting**, shaderpack support and
native ray tracing are later phases; see [ROADMAP.md](ROADMAP.md). Known defects are parked in
[bug.md](bug.md).

> ## Current status: Phases 0–4 are DONE — the game renders on Metal, visual parity is unfinished
>
> Minecraft selects the **Metal backend**, creates the device and a `CAMetalLayer`, creates real
> Metal **textures, views, buffers and samplers**, compiles the engine's shaders
> (**GLSL → SPIR-V → MSL**), encodes real render passes, and blits the engine's render target to the
> drawable. Verified in-game:
>
> - the Mojang loading screen (logo + progress bar),
> - the main menu (logotype, buttons, sliders, splash, blurred panorama),
> - in-world geometry (sky, hotbar, terrain silhouettes).
>
> It is **not yet visually correct**: terrain is unlit/flat, the block-selection outline is wrong,
> and some screens have missing sprites. That is Phase 5 (vanilla render parity) work; the current
> bugs are listed in [bug.md](bug.md). Phase 4 (shaders) is **done** — see
> [docs/phase4-plan.md](docs/phase4-plan.md).
>
> The backend is **opt-in and OFF by default** while parity is unfinished, so normal play keeps using
> the bundled Vulkan/OpenGL path. Enable it from **Mod Menu → MetalMod → "Metal Renderer Backend"**,
> or set `preferMetalBackend=true` in `config/metalmod.properties`, or pass
> `-Dmetalmod.metalBackend=true`. The backend is chosen once at startup, so **restart** after
> changing it.
>
> ## ⚠️ MetalFX is not wired to the Metal backend yet
>
> MetalFX scalers/interpolators exist in the native library but are **not driven by the Metal
> backend** and no upscaled or interpolated frame reaches the display. MetalFX returns in Phase 8,
> against the backend's own textures. Frame generation is **off by default**.

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

### Apple Silicon unified-memory telemetry
Physical RAM, available/compressed memory, swap, process footprint, Metal allocated bytes and the
working-set cap, plus the macOS kernel memory-pressure level, shown on the F3 overlay. A
`DISPATCH_SOURCE_TYPE_MEMORYPRESSURE` listener performs conservative scratch reclaim.

### Optional UMA allocator (opt-in, default off)
`MetalMemoryAllocator` can replace LWJGL's off-heap allocator with 16 KB-aligned shared
`MTLBuffer`s, routing `free`/`realloc` by ownership and falling back when the pool cannot
satisfy a request. It is a pessimisation for typical workloads, so it stays off unless
`enableUnifiedMemoryPool=true`.

### Native substrate
`libmetalmod.dylib` is a thin Objective-C++ layer over Metal/MetalFX/QuartzCore; the Java side
reaches it through Panama FFI (`java.lang.foreign`). A standalone native smoke test
(`native/tests/metal_smoke.mm`) exercises device, clear, resources, pipelines and a triangle draw.

---

## Known limitations

These are the reasons the mod is not a drop-in replacement yet.

1. **Visual parity (Phase 5).** World rendering is geometry with textures/lighting incomplete:
   terrain and entities render as flat black silhouettes, the block-selection outline is a huge
   wireframe box, and some GUI screens are missing sprites. See [bug.md](bug.md).
2. **Multi-draw passes lose their uniforms (Phase 5).** `drawMultipleIndexed` never invokes each
   draw's `uniformUploaderConsumer()`, and vanilla's chunk terrain path uses it. See BUG-004 in
   [bug.md](bug.md). Shader *compilation* is complete: all 87 vanilla pipelines compile, verified by
   `tools/shader_inventory/run.sh`.
3. **MetalFX / frame generation are not presented (Phase 8).** The native scalers and interpolator
   are not driven by the Metal backend, and frame generation additionally needs a
   `CAMetalDisplayLink` pacer so two drawables land on different refreshes.
4. **Internal resolution scaling is not implemented.** Shrinking the main render target breaks the
   GUI layout (scissor rectangles exceed the render area), so `scalingMode`/`preset` currently have
   no effect. Doing it properly means rendering the world into its own target and upscaling that.
5. **The UMA allocator is off by default** (see above).

---

## Legacy code still in the tree

The old **"MetalFX on top of MoltenVK / `VK_EXT_metal_objects`"** design is still present but is
**not used by the Metal backend** and should be deleted:

- `native/src/metalmod_bridge.mm`, `metalmod_spatial.mm`, `metalmod_temporal.mm`,
  `metalmod_interpolator.mm`, `metalmod_compositor.mm`, `metalmod_pacer.mm` and
  `native/include/vulkan/` — the interop + MetalFX frame pipeline.
- `src/main/java/net/metalmod/render/VulkanFrameManager.java`, `ffi/MetalBridge.java` and the
  parts of `render/JitterHelper.java` that feed them.

The F3 overlay still prints `pipeline: inactive (Vulkan interop not registered)` from this code;
that line is a leftover, not the Metal backend's status.

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
#  atlas, mip selection, blending, every blend state vanilla uses, index width/offsets, colour-target limit, lightmap, post-processing, alpha cutout, topologies, texel buffers; 74 assertions)
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
│   │   ├── metalmod_memory.mm          # UMA pool, Mach VM telemetry, pressure source
│   │   └── ...                         # legacy MetalFX/MoltenVK path (see "Legacy code")
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
│   ├── backend/                        # Metal GpuBackend implementation (Phases 1–4)
│   ├── client/                         # Fabric entrypoint + Mod Menu config screen
│   ├── config/                         # Presets and persistence
│   ├── debug/                          # F3 debug entry
│   ├── memory/                         # UMA allocator + telemetry
│   ├── mixin/                          # Backend selection and render hooks
│   └── render/                         # Legacy frame manager + Halton jitter
└── src/test/java/net/metalmod/         # Standalone suite + JUnit tests
```

---

## Controls & configuration

- **Metal Renderer Backend**: Mod Menu → MetalMod (restart required), or
  `config/metalmod.properties`, or `-Dmetalmod.metalBackend=true`.
- **F3 overlay**: MetalMod status — backend/pipeline state, internal vs display resolution, render
  FPS, presented FPS and pipeline GPU time.
- **Config screen**: also exposes the MetalFX/scaling and UMA options (currently not applied — see
  limitations 3–5).
