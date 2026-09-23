# Testing MetalMod

How to build, verify, and check the Metal backend. `HANDOFF.md` is the current status;
`ROADMAP.md` §7 is what is left; `bug.md` lists defects.

---

## 1. Build and install

```bash
./scripts/build_mod.sh
INST="$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2"
cp build/libs/metalmod-1.0.0.jar "$INST/mods/"
```

`build_mod.sh` builds the native library, compiles the Java against the **real client jar** (so
`javac` checks every Minecraft API call), packages the jar, and compiles the standalone tests.

Config lives at `$INST/config/metalmod.properties`. The current fields are:

```properties
enableUnifiedMemoryPool=false
enableMemoryPressureHandler=true
preferMetalBackend=true
```

`preferMetalBackend=true` (or `-Dmetalmod.metalBackend=true`) makes `PreferredGraphicsApiMixin`
prepend the Metal backend. It is chosen once at startup, so restart after changing it. With it off,
normal play uses Vulkan/OpenGL.

## 2. Offline gates (no game required)

Run all five; they are the cheap, deterministic checks.

| Command | Pass condition |
|---|---|
| `./scripts/build_mod.sh` | `SUCCESS -> build/libs/metalmod-1.0.0.jar` |
| `./native/build/metalmod_smoke` (or `./scripts/run_smoke.sh`) | `ALL CHECKS PASSED` |
| `./tools/shader_inventory/run.sh` | `static 87/87`, `post 9/9`, no diagnostics |
| `./tools/render_check/run.sh` | `RENDER CHECK PASSED` |
| `net.metalmod.StandaloneTestRunner` | `ALL TESTS PASSED SUCCESSFULLY!` |

The standalone runner needs the client classpath; `build_mod.sh` prints the exact command. The
shader tools and render check also accept an instance directory as an argument or via
`METALMOD_MC_INSTANCE`.

Useful one-off: print the generated MSL for a shader pair, or all of them, by adding
`-Dmetalmod.dumpMsl=<substring>` (or `=all`).

## 3. In-game checks

Launch with the backend on, load a world, press **F3**, and let it run at least 30 seconds.

### F3 MetalMod lines

```
[MetalMod] Backend: Metal (active)
[MetalMod] Resolution: <framebuffer width>x<height>
[MetalMod] Frame 17.5 ms avg | GPU wait 2.1 ms avg | 6400 draws (37 passes, 18400 native calls, 40 copies, 6 fences) (CPU-bound)
[MetalMod] unbound/missingAttr/failed: 0 (0/0/0 = bindings, missing vertex attributes, pipeline builds)
```

The **Frame** line is the performance indicator, and both timings are averages over about a second.

`GPU wait` is the time the render thread spent blocked in `nextDrawable()` — waiting for the GPU to
hand back a drawable. The rest of the frame interval is CPU work, so:

- **wait ≈ 0** → **CPU-bound**: the GPU keeps up, and the frame time is CPU work (per-draw encoding,
  binding, submission). The `draws` count is what scales here.
- **wait ≈ Frame** → **GPU-bound**: the CPU finishes early and then waits for the GPU. Optimising
  the CPU side will not move the frame rate.
- **wait in between** → mixed.

Caveat: with vsync on, a *fast* frame waits for the display too, so only read "GPU-bound" when the
frame is also slower than the refresh rate. `passes` is the per-frame command-buffer count for the
engine's render passes (plus the present blit); a high number is submission overhead. `native calls`
is the number of Panama FFI downcalls per frame — every draw makes several, so it is the figure that
says whether the FFI path is load-bearing. `copies` counts buffer uploads/copies and texture copies
(each allocates a staging buffer and commits its own command buffer) and `fences` counts fences (each
allocates a shared event and commits a signal command buffer). Those last two are the ones to watch
when the frame *hitches* while chunk meshes rebuild: if they spike in the same frame as the hitch,
the upload path is the cause.

The `draw 'pipeline' -> target` census and the unbound-binding report run on every draw, so they
stop themselves after about ten seconds (the log says so). `-Dmetalmod.census=on` keeps them for a
dedicated debugging run.

> A true per-frame GPU *execution* time is not shown. Summing each command buffer's
> `GPUStartTime`/`GPUEndTime` looks like it works and does not: command buffers on one queue may
> overlap execution, so the sum over-counts (measured ~3× in one scene and ~8× in another, and it
> fell while the scene got heavier). Doing it properly needs `MTLCounterSampleBuffer` timestamps.

plus `UMA pool ...` when `enableUnifiedMemoryPool=true`, and a `hooks:` line only if a hook failed to
apply. `Backend: Metal (active)` is read from the engine's own `DeviceInfo`, so it answers whether
Metal is actually drawing.

### The 30 s telemetry line

```bash
grep -E "MetalMod|metal pipeline FAILED" "$INST/logs/latest.log"
```

Look for:

```
[MetalMod] hook summary after 30s: +GameRenderer.render +GameRenderer.resize +Window.onFramebufferResize
[MetalMod] Metal resources created: textures=... views=... buffers=... samplers=... failures=0 pipelineFailures=0 unboundBindings=0 missingVertexAttributes=0 slotCollisions=0 bindingKindMismatches=0 indexedFans=0
```

Every counter must stay at zero. There must be no `@Mixin target ... was not found` warnings for
`metalmod`, and no `metal pipeline FAILED` lines.

### Visual checklist

- Loading screen (logo + bar), main menu (logotype, buttons, sliders, splash, blurred panorama).
- In a world: terrain with textures and lighting, sky/clouds/weather, water, entities, particles,
  the HUD, hotbar items, legible text, the debug axes.
- **Select World** (Singleplayer → Select World): the first entry must have its background panel and
  an unsquashed world-name line. This is BUG-001; it was a vertically mirrored scissor and is the
  one fix still awaiting a look.
- Look at a nearby block: the selection outline is a thin box.
- Open the survival inventory: item icons are present and the right way up, and the player preview
  at the top-left is the right way up (BUG-024, BUG-025).
- Open the config screen from Mod Menu: it shows the Metal backend and UMA toggles.

## 4. Performance parity (still to be measured)

The roadmap's exit criterion is a comparable frame rate to Vulkan/MoltenVK. Measure **in normal
play**, not with a menu open (the world must be ticking):

1. Load the same world and stand in the same place on Metal and on Vulkan.
2. Record F3 fps and the frame time at native resolution, then at roughly half the window area.
3. Optionally corroborate with `sudo powermetrics --samplers gpu_power -i 1000 -n 20`: consistently
   below ~70% GPU busy means the frame is CPU-bound, so resolution changes will not move it much.

Earlier numbers (taken with a menu open) suggested a CPU floor around 3.1 ms and roughly
0.42 ms/Mpx of GPU cost. Treat those as indicative only.

## 5. Troubleshooting

- **`libmetalmod.dylib` fails to load.** The Java bindings resolve native symbols by name and throw
  if one is missing, so a stale dylib is reported explicitly. Rebuild with `./scripts/build_mod.sh`
  and make sure the game was restarted after installing the new jar (Java loads the mod jar at
  launch; a running session keeps the old one).
- **Metal not selected.** Check the log for `Using graphics backend Metal`. If absent, the engine
  fell back — look for `Metal backend ... disabled` / a `BackendCreationException`.
- **A black or missing object.** Check the F3 `unbound/missingAttr/failed` counters; a non-zero value
  names the class of problem (a shader sampling an unbound resource, a missing vertex attribute, or a
  pipeline that failed to build and is skipping its draws).
- **No `[MetalMod]` F3 lines** while the log says `verified=true`: the visibility mixin
  (`DebugScreenEntryListMixin`) did not take effect. Report it.
- **`@Mixin target ... was not found`.** Mixin rejects an entire mixin if any target is missing.
  Report the exact text.
