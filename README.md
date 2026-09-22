# MetalMod: native Apple Silicon Metal backend for Minecraft (Fabric)

MetalMod is building a **native Metal graphics backend** for Minecraft on macOS, with Apple's
**MetalFX** upscaling / frame interpolation and native ray tracing as later goals, plus an Apple
Silicon unified-memory allocator. It replaces the older "run MetalFX on top of MoltenVK" design,
which could never own presentation.

> ## Current status: Phase 1 (Metal first light) is DONE
>
> Minecraft now selects a **Metal backend**, creates the device and a `CAMetalLayer`, and presents
> cleared frames at display rate (verified ~2400 frames in a minute, clean shutdown). The draw path
> is deliberately inert, so the window shows a pulsing first-light colour rather than the game image
> — that is Phase 2/3 work. See `ROADMAP.md` (Phase 1) and `docs/phase1-boot-trace.md`.
>
> The backend is **opt-in and OFF by default**, because it cannot draw the game yet. Normal play is
> untouched (Vulkan/OpenGL). To exercise first light, set `preferMetalBackend=true` in
> `config/metalmod.properties` or launch with `-Dmetalmod.metalBackend=true`; expect a flat
> pulsing clear colour, not the game.
>
> ## ⚠️ The MetalFX frame pipeline is INACTIVE
>
> The old MetalFX layer still works natively, but **no upscaled or interpolated frame reaches the
> display**. MetalFX returns in Phase 8, against the backend's own textures, with no MoltenVK
> interop. Frame generation is **off by default**.

---

## What is implemented

1. **MetalFX upscaling (native side complete)**
   - `MTLFXSpatialScaler` and `MTLFXTemporalScaler` are created and encoded.
   - Quality presets: Native (100%), Ultra Quality (77%), Quality (67%), Balanced (58%),
     Performance (50%), Ultra Performance (33%).
   - Temporal mode injects a low-discrepancy Halton(2,3) subpixel offset into the projection matrix.

2. **MetalFX frame interpolation (encoded, not presented)**
   - `MTLFXFrameInterpolator` (macOS 26+) is configured with colour, depth, motion and UI texture
     formats, and can encode an intermediate frame.
   - It is **not presented**: an intermediate frame must land on a *different* display refresh than
     the real frame, which requires a pacer (`CAMetalDisplayLink`). Without one, encoding it only
     adds cost. Measured: ~0.93 ms → ~3.12 ms CPU encode per frame for temporal + interpolation.

3. **Vulkan ↔ Metal interop (plumbing present, not registered)**
   - `metalmod_register_vulkan_device(VkDevice, PFN_vkExportMetalObjectsEXT)` stores the device and
     `vkExportMetalObjectsEXT` pointer; `metalmod_has_vulkan_interop()` reports whether it is set.
   - `vkExportMetalObjectsEXT` is used to obtain an `id<MTLTexture>` from a `VkImage`, with
     `plane = VK_IMAGE_ASPECT_COLOR_BIT` as the spec requires.
   - Until a real `VkImage` is registered, the pipeline refuses to run instead of interpreting a
     synthetic handle as an Objective-C object (which previously crashed with SIGSEGV in
     `objc_retain`).

4. **Apple Silicon unified memory telemetry**
   - Physical RAM, available/compressed memory, swap, process footprint, Metal allocated bytes and
     working-set cap, and macOS kernel memory-pressure level, shown on the F3 overlay.
   - A `DISPATCH_SOURCE_TYPE_MEMORYPRESSURE` listener performs conservative scratch reclaim.

5. **F3 debug section (works)**
   - MetalMod registers a `DebugScreenEntry`, so its status lines appear in the F3 overlay alongside
     the vanilla and Sodium sections. This is the supported mechanism since 1.21.9+: the overlay
     iterates `DebugScreenEntryList.getCurrentlyEnabled()` and calls `DebugScreenEntry.display(...)`.
     The legacy `DebugScreenOverlay.extractLines` hook point still exists but is dead code.
   - Registration takes three parts, because `DebugScreenEntries.register` is `private static`
     **and** a registered entry stays hidden unless it is given a status (`getStatus` is
     `getOrDefault(id, NEVER)`). See `net.metalmod.debug.DebugScreenRegistration`.

6. **Optional UMA allocator (opt-in, default off)**
   - `MetalMemoryAllocator` can replace LWJGL's off-heap allocator with 16 KB-aligned
     `MTLResourceStorageModeShared` buffers.
   - It routes `free`/`realloc` by ownership (`metalmod_uma_owns`) so pointers created before the
     swap are returned to the allocator that produced them, and falls back to the previous
     allocator when the pool cannot satisfy a request.

---

## Known limitations

These are the reasons the mod does not currently improve frame rate. They are architectural, not
tuning issues.

### 1. MetalMod does not own presentation

The `CAMetalLayer` is created by GLFW and belongs to MoltenVK's swapchain. Minecraft presents into
it at the end of every frame — *after* `RenderTarget.blitToScreen`, which is where MetalMod's
pipeline is invoked. A drawable presented by MetalMod is therefore always overwritten by the
swapchain present, and would additionally race MoltenVK for drawable ownership.

**Consequence:** with `METALMOD_OWNS_PRESENTATION 0` (see `native/src/metalmod_internal.h`) the
frame pipeline returns `METALMOD_ERR_NO_PRESENTATION` and does no work, rather than burning CPU/GPU
on an invisible image.

**To fix:** either write the upscaled result back into the render target Minecraft presents (needs
the real Vulkan backend), or take over presentation end-to-end (Minecraft renders offscreen, a
`CAMetalDisplayLink` pacer drives the display and ownership of the layer moves to MetalMod).

### 2. Frame generation needs a pacer

`CAMetalDisplayLink` is available on this SDK but is not used anywhere. Two drawables presented from
one command buffer become available at the same instant and are shown on the same refresh, so only
the last is ever visible. Frame interpolation is gated behind `METALMOD_PACER_AVAILABLE`.

### 3. Vulkan interop is never registered

`metalmod_register_vulkan_device` is called by nothing. Real `VkImage` handles must also be created
with `VkExportMetalObjectCreateInfoEXT`
(`VK_EXPORT_METAL_OBJECT_TYPE_METAL_TEXTURE_BIT_EXT`) in their `pNext` chain, and their pixel formats
and `MTLTextureUsage` bits must match what the scaler requests (`colorTextureUsage`,
`depthTextureUsage`, `motionTextureUsage`). Depth is assumed `Depth32Float` and motion `RG16Float`;
these must be reconciled with Minecraft's actual formats at runtime.

### 4. The mod is compiled against API stubs, not Minecraft

`scripts/build_mod.sh` compiles the Java sources against stubs generated by
`scripts/generate_stubs.py`. There is no refmap and no Gradle wrapper; `build.gradle` is not a
working mod build.

The stubs are no longer guesses: every type referenced by a mixin was read out of the real 26.2
client jar with `javap`, and the mixin targets/method names now match it. What stubs still cannot
check is anything the mod touches indirectly, and any Minecraft version other than the one they were
verified against. Re-verify with:

```bash
javap -cp <client>.jar -p com.mojang.blaze3d.pipeline.RenderTarget
```

Two consequences worth knowing:

- This build runs with **named** mappings (the client jar has zero `class_XXXX` entries), so mixins
  must target named classes. Mixin rejects an *entire* mixin if any single entry in `targets` is
  missing, which is why the previous revision's dual named/intermediary target lists disabled every
  hook at once while only emitting four warnings.
- **Annotation retention in the stubs is load-bearing.** `@Mixin` is `RetentionPolicy.CLASS`, but
  `@Shadow`, `@Inject`, `@ModifyVariable`, `@At` and `@Accessor` are `RetentionPolicy.RUNTIME`.
  Mixin reads those as *visible* annotations; declaring them `CLASS` puts them in
  `RuntimeInvisibleAnnotations`, where Mixin cannot see them — injections are skipped silently
  (every injector uses `require = 0`) and `@Accessor` interfaces are misclassified as interface
  mixins (`@Mixin target type mismatch: ... is not an interface`). Check with:
  `javap -v -cp <sponge-mixin>.jar org.spongepowered.asm.mixin.gen.Accessor`
- `compatibilityLevel` in `metalmod.mixins.json` must match the bytecode the build emits, so
  `build_mod.sh` pins `javac --release 22`.

### 5. Internal resolution scaling is not implemented

`RenderTargetMixin` used to shrink the **main** render target to the scaled size. That is not viable
in this architecture: the GUI lays out against the window size, so a smaller target makes its scissor
rectangles exceed the render area and the click handler throws
(`Scissor ... is out of bounds for render area`), leaving the screen unresponsive. The mixin has been
removed, so `scalingMode` and `preset` currently have **no effect** and are reported as
"not applied" on the F3 overlay.

Doing this properly means rendering the world into its **own** target at reduced resolution and
upscaling that, while the GUI target stays at native size (Stage 0 in the plan). That also fixes the
blurry-HUD problem noted below.

### 6. The UMA allocator is a pessimisation for typical workloads

Each pooled allocation becomes its own 16 KB-aligned `MTLBuffer` behind a global mutex and hash-map
lookup, and `MTLResourceCPUCacheModeWriteCombined` is fast to write but slow to read back on the
CPU. This is only a win for large, long-lived buffers, which is why it now defaults to **off** and
must be enabled explicitly (`enableUnifiedMemoryPool=true`).

---

## Building

### Prerequisites
- Apple Silicon Mac, macOS 26+
- CMake 3.28+, JDK 22+ (JDK 26 used here), Python 3

> Testing an existing build? See **[TESTING.md](TESTING.md)** for the install steps, the diagnostics
> to collect, and the CPU-vs-GPU measurement that decides whether upscaling work is worthwhile.

### Build

```bash
./scripts/build_mod.sh
```

This compiles `native/build/libmetalmod.dylib` via CMake, packages
`build/libs/metalmod-1.0.0.jar`, and compiles the standalone verification suite into
`build/test-bin`. `./gradlew` does not exist in this repository; do not rely on `build.gradle`.

### Verify

```bash
JAVA=/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home/bin/java
$JAVA --enable-native-access=ALL-UNNAMED \
    -cp build/mod-bin:build/stubs-bin:build/test-bin \
    net.metalmod.StandaloneTestRunner
```

(`build/stubs-bin` is on the classpath only because `MetalMemoryAllocator` implements LWJGL's
`MemoryUtil.MemoryAllocator`, so that type must resolve. It is never loaded by the mod jar.)

The suite exercises the per-frame native entry point (`processFrame`), the capability queries, and
the UMA ownership routing. It is the regression net for the class of bug that previously made every
single frame throw `WrongMethodTypeException`, and for the bogus-handle path that previously
crashed with SIGSEGV.

---

## Controls & configuration

- **F6 / F7**: cycle upscaling and frame-generation settings.
- **Config screen**: available through Mod Menu; writes `config/metalmod.properties`.
- **F3 overlay**: pipeline mode and *whether it is actually active*, internal vs. display
  resolution, render FPS, presented FPS and pipeline GPU time.

Telemetry is measured, not inferred: presented FPS is counted from frames actually handed to the
display, and GPU time comes from `MTLCommandBuffer.GPUStartTime`/`GPUEndTime`. (Earlier revisions
reported `presentedFPS = 2 × renderFPS` whenever frame generation was enabled, which made the overlay
claim a doubling that never happened.)

---

## Directory structure

```
MetalMod/
├── native/                             # Objective-C++ / Metal library
│   ├── include/metalmod/               # Exported C API + struct definitions
│   ├── include/vulkan/                 # Minimal VK_EXT_metal_objects definitions
│   └── src/
│       ├── metalmod_internal.h         # State, return codes, presentation policy
│       ├── metalmod_bridge.mm          # C API, Vulkan export, capability queries
│       ├── metalmod_spatial.mm         # MTLFXSpatialScaler
│       ├── metalmod_temporal.mm        # MTLFXTemporalScaler
│       ├── metalmod_interpolator.mm    # MTLFXFrameInterpolator
│       ├── metalmod_compositor.mm      # UI composite pipeline + MSL shaders
│       ├── metalmod_pacer.mm           # Per-frame encode, validation, telemetry
│       └── metalmod_memory.mm          # UMA pool, Mach VM telemetry, pressure source
├── scripts/
│   ├── build_mod.sh                    # Authoritative build
│   └── generate_stubs.py               # Compile-time API stubs (see limitation 4)
├── src/main/java/net/metalmod/
│   ├── client/                         # Fabric entrypoint + config GUI
│   ├── config/                         # Presets and persistence
│   ├── ffi/                            # Panama FFI bindings
│   ├── memory/                         # UMA allocator + telemetry manager
│   ├── mixin/                          # Render/projection/resize hooks
│   └── render/                         # Frame manager + Halton jitter
└── src/test/java/net/metalmod/         # Standalone suite + JUnit tests
```
