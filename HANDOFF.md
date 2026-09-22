# MetalMod — current state

Short, factual status. See `ROADMAP.md` for where this is going and `TESTING.md` for how to test.

## What works today

- **Build:** compiles against the **real Minecraft client jar** (no API stubs).
  `./scripts/build_mod.sh` → `build/libs/metalmod-1.0.0.jar`.
- **Integration:** all mixins apply. Verified in-game:
  `+GameRenderer.render +GameRenderer.resize +Window.onFramebufferResize`,
  `F3 debug entry: registered=true verified=true`.
- **F3 section:** MetalMod status lines render in the debug overlay.
- **Config GUI:** opens from Mod Menu; buttons work.
- **Native library:** `libmetalmod.dylib` loads; MetalFX capability queries and Mach VM telemetry work.

## Phase 1 status (Metal backend)

- **Native Metal substrate: done and verified** (`native/src/metalmod_metal.mm`).
  `./native/build/metalmod_smoke` passes on Apple M4 Pro: device info, exact clear-colour
  round-trip through CPU readback, and `CAMetalLayer` acquire/clear/present.
- **Not yet written:** the Java backend classes (`MetalBackend`, `MetalDeviceBackend`,
  `MetalSurfaceBackend`, encoder/render-pass backends, resource types) and the mixin that prefers
  Metal in `PreferredGraphicsApi.getBackendsToTry()`. Until those exist, Minecraft still runs on
  Vulkan/MoltenVK.
- The exact interface contract to implement is generated into `docs/backend-api.md`
  (`./scripts/dump_backend_api.sh`).

## What does not work

- **Upscaling / frame generation:** inactive. `MoltenVK owns presentation` — anything the mod
  produces is overwritten before it reaches the screen, and `Vulkan interop` was never registered.
  See ROADMAP §3.
- **Internal resolution scaling:** removed. Shrinking the main render target made the GUI's scissor
  rectangles exceed the render area, killing input:
  `Scissor at 0, 179 with size 2520x2150 is out of bounds for render area ... 3942x2052`.
  Fixing it needs a separate world render target (ROADMAP Phase 5).
- **Shaderpacks, Metal backend, ray tracing:** not started.

So: the mod currently costs nothing and does nothing except F3 telemetry. That is deliberate — it is
better than the previous state, which was inert *and* crashed when enabled.

## The performance question

Measured by resizing the window (both at 100% render scale, so only resolution differs):

| window | pixels | frame time | fps |
|---|---|---|---|
| 5120×2664 | 13.64 Mpx | 8.85 ms | 113 |
| 1064×536 | 0.57 Mpx | 3.38 ms | 296 |

Pixels fell 23.9×, fps rose only 2.6×. Fitting `time = CPU + k × megapixels`:

- **CPU floor ≈ 3.14 ms (~319 fps ceiling)**, independent of resolution
- **GPU ≈ 0.42 ms per megapixel → ~65% of frame time at 5K**

Interpretation: at native 5K the GPU is the dominant cost, so resolution-based upscaling has real
headroom (+36% at 77% scale, +55% at 67%, +94% at 50%, by that linear model). At a small window the
game becomes CPU-bound, which is why the gain is not proportional.

Caveat: both measurements had a menu open, so the world was not ticking — the CPU floor is
optimistic and the GPU share is therefore a lower bound. Worth re-measuring in normal play.

## Environment specifics that matter

- Minecraft **26.2** with **named mappings** — the client jar contains **zero** `class_XXXX` entries,
  so mixins must target named classes. Mixin rejects an *entire* mixin if any one entry in `targets`
  is missing.
- Fabric Loader 0.19.2, Java 26, Apple M4 Pro, macOS 27.
- Graphics: **Vulkan 1.2.334 via MoltenVK 1.4.2**.
- `compatibilityLevel` in `metalmod.mixins.json` must match the emitted bytecode, so
  `build_mod.sh` pins `javac --release 22`.
- **Annotation retention is load-bearing:** `@Mixin` is `CLASS`; `@Shadow`, `@Inject`,
  `@ModifyVariable`, `@At`, `@Accessor` are `RUNTIME`. Declaring the latter as `CLASS` compiles fine
  and then does nothing.

## Instance under test

```
$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2
```

Mods present: Fabric API 0.161.0, Mod Menu 20.0.2, Sodium 0.9.2, Placeholder API 3.1.0.

Override the build target with `METALMOD_MC_INSTANCE`.

## Decisions already made (and why)

| Decision | Reason |
|---|---|
| Compile against the real client jar, not stubs | Stubs caused 3 of 4 integration failures; `javac` now verifies the API |
| Remove main-render-target scaling | It broke the GUI and froze input |
| Remove the LWJGL allocator interception | LWJGL 3.4 needs native function pointers for its fast path; a Java pool cannot supply them, and mixing allocator ownership risks corruption. Also a measured pessimisation. |
| Retire the MoltenVK-interop architecture | Cannot own presentation; cannot express MetalFX or ray tracing. Superseded by the backend plan. |
