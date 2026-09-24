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

### Simple performance capture (F8)

1. Restart after installing the new jar, load your usual world, and let chunks settle for 30 seconds.
2. Press **F8** (or **Fn+F8** if macOS uses the media keys). There is a five-second countdown,
   followed by a 60-second recording. An on-screen message shows the remaining time.
   Alternatively, use **Mod Menu → MetalMod → Record performance (60 seconds)**; it returns to play.
3. For the transition hitch, spend about 15 seconds above ground, travel underground, stay there
   for about 15 seconds, then return above ground. Keep playing until the recording stops itself.
   Press F8 again to stop early. Closing the world also ends the capture.
4. The chat and game log report the saved folder under **`<game directory>/debug/metalmod/`**.
   Each capture contains **`summary.txt`** and **`frames.csv`**. The summary has average, median,
   p95, p99, worst frame, hitch counts, and the ten slowest gameplay frames with their upload,
   submission, allocation, fence and compilation activity. No spreadsheet or profiler is needed.
5. For a Vulkan baseline, disable the Metal backend, restart, and repeat F8 on the same route with
   the same resolution, render distance, FPS limit and vsync. Metal-specific CSV fields are `-1`
   (unavailable) on other backends, rather than misleading zeroes.

The capture records every interval between surface presentations. It keeps samples in bounded
memory (maximum 36000 frames, about 11 MiB), then formats/writes them on a background thread after
recording stops. There is no per-frame disk I/O. Positions are block coordinates, all data stays on
this machine, and each recording gets a unique folder. Menu, paused and unfocused intervals remain
in the CSV but are excluded from gameplay statistics. A session closed before recording begins is
cancelled without exporting an empty report. Normal game shutdown saves an active capture.

All native durations are **CPU wall time in API calls**, not GPU execution time. `upload_api_ns`
contains the staging memcpy but *not* the submission that later carries the copy, because utility
copies are batched (see below). Those nested durations must not be summed. `gc_reported_ms` is
reported collection time, not an exact pause measurement. `ffi_calls` now includes both typed and
generic wrappers; older F3 counts omitted generic downcalls, so their absolute values are not
directly comparable.

### F3 MetalMod lines

The F3 overlay shows backend, resolution, average frame/drawable-acquisition times, draw count,
render/present command buffers, native calls, copies, fences, capture status and health counters.
The averages update every 60 frames; **use F8 to capture hitches**, which averages can obscure.

**Drawable wait is only one wait site.** Low drawable wait does not prove the frame is CPU-bound:
fence waits, queue backpressure during command-buffer creation, readbacks and display pacing can
also contribute. The capture counts all native submissions, including utility copies, clears and
fence signals; the existing F3 render/present count covers fewer operations. A coincidence between
a copy spike and a hitch is evidence to investigate, not proof of causation.

**`submissions` is far below `buffer_writes` by design.** Utility copies and uploads share one
command buffer and commit only when something that needs its own command buffer is created, so a
frame with 50 chunk-mesh uploads is a handful of submissions, not 50. `staging_allocations` counts
*actual* Metal buffer allocations: a per-frame ring is reused across writes and grown only when a
frame outgrows it, so a healthy run shows a few allocations rather than one per write. If a capture
shows `staging_allocations` tracking `buffer_writes` again, the batching is not being used. Read a
large `buffer_writes` spike as "the engine rebuilt this many meshes", not as that many GPU copies.

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

### Texture storage modes

A texture created with `USAGE_RENDER_ATTACHMENT` gets `MTLStorageModePrivate`; everything else stays
`MTLStorageModeShared`. Shared storage is CPU-coherent memory that the GPU reads and writes through
the same path; private storage lets the GPU keep a render target in tile memory and compress it.
Colour targets and depth buffers are the whole point, and the startup log names the first twelve
textures claimed:

```
[MetalMod] private storage: 'terrain depth' D32_FLOAT 5120x2664x1
```

**The copy flags cannot be used for this.** `RenderTarget` creates *both* its depth and its colour
texture with the constant **15** - `COPY_DST | COPY_SRC | TEXTURE_BINDING | RENDER_ATTACHMENT` - so
every render target in the game declares both copy flags. An earlier version of this rule kept any
texture with a copy flag shared and consequently claimed **nothing at all**: a real session reported
`privateTextures=0` while every render target stayed shared, and the startup log had no
`private storage:` lines. That is the thing to check first if the change appears to do nothing.

What discriminates is that a render target is *rendered into* rather than *uploaded into*, and in
26.2 that line falls where the flags say it does. Bytecode-verified for each case:

| texture | usage | how the engine fills it |
|---|---|---|
| `Main / Color`, `Main / Depth`, `Entity Outline` | 15 | render passes |
| `minecraft:textures/atlas/*.png` | 15 | render passes (`ANIMATE_SPRITE_BLIT` into the atlas mip views) |
| `Lightmap` (16x16) | 13 = `COPY_DST\|BINDING\|RENDER_ATTACHMENT` | a render pass |
| plain textures, dynamic textures, cloud texel buffers | 5 = `COPY_DST\|BINDING` | CPU upload (`writeToTexture`) |

So the atlases and the lightmap really are render targets - Minecraft builds the sprite sheet and the
lightmap on the GPU - and they take no CPU upload. The textures that *are* uploaded stay shared, which
is why `privateCpuAccess` is zero: those are also the ~2 uploads per frame the F8 capture counts.
Seeing `private storage:` lines for atlases is expected, not a fault.

The rule is still a claim about engine behaviour, so it is checked rather than trusted:

- An upload (`writeToTexture`, `copyBufferToTexture`) aimed at a private texture **cannot work** -
  the bytes are in GPU-private memory - so it is refused and counted. `privateCpuAccess` in the
  resource summary and the F3 `private CPU access` line must stay at zero. Any non-zero value means a
  render target is uploaded into from the CPU, and the log names it.
- Readback does not have to be refused: `copyTextureToBuffer` blits the region into a shared staging
  texture first, so reading a private colour or depth target works whether or not `USAGE_COPY_SRC`
  was declared. That is what lets `render_check` read back its private targets.

`-Dmetalmod.privateTextures=false` forces every texture back to shared storage, to A/B the change.

## 4. Comparing performance

The roadmap's exit criterion is a comparable frame rate to Vulkan/MoltenVK. Measure **in normal
play**, not with a menu open (the world must be ticking):

1. Load the same world and stand in the same place on Metal and on Vulkan.
2. Record F3 fps and the frame time at native resolution, then at roughly half the window area.
3. Optionally corroborate with `sudo powermetrics --samplers gpu_power -i 1000 -n 20`: consistently
   GPU busy can corroborate a trace, but it does not by itself identify the cause of a slow frame.

Earlier numbers (taken with a menu open) suggested a CPU floor around 3.1 ms and roughly
0.42 ms/Mpx of GPU cost. Treat those as indicative only.

### What the first paired capture measured

Two 60-second F8 captures of the same world, 5120x2664, render distance 32, vsync off, FPS limit
260 (`20260923-192349` Metal, `20260923-192616` Vulkan):

| | Metal | Vulkan |
|---|---|---|
| gameplay frames | 6224 | 7835 |
| mean frame | 9.43 ms (106 FPS) | 7.51 ms (133 FPS) |
| median | 8.19 ms | 6.85 ms |
| p95 / p99 | 19.55 / 27.75 ms | 17.47 / 20.39 ms |
| frames over 33 ms | 21 | 7 |
| mean above ground (Y>=63) | 8.20 ms | 6.47 ms |
| mean underground (Y<63) | 17.64 ms | 12.06 ms |
| **median underground** | **15.24 ms** | **15.33 ms** |

The two underground medians being equal is the useful part: a typical underground frame is the same
speed on both, so the gap is entirely a tail of Metal-specific hitches, not a slower steady state.
Binning the Metal frames by `buffer_writes` shows where the tail comes from — writes per frame
predict frame time monotonically, and nothing else does:

| `buffer_writes` | frames | mean frame | `upload_api_ns` | `command_buffer_create_ns` |
|---|---|---|---|---|
| 1-2 | 4850 | 8.52 ms | 0.16 ms | 0.45 ms |
| 3-8 | 560 | 9.54 ms | 0.30 ms | 0.46 ms |
| 9-20 | 361 | 9.92 ms | 0.52 ms | 0.79 ms |
| 21-50 | 150 | 12.20 ms | 1.60 ms | 2.86 ms |
| 51-120 | 106 | 18.26 ms | 7.92 ms | 6.75 ms |
| 121-500 | 197 | 23.76 ms | 10.97 ms | 8.36 ms |

Underground the engine issues 53.5 buffer writes per frame against 4.0 above ground, every one of
them a chunk-mesh upload. Summed over the run, `upload_api_ns` is 7.5% of wall time and
`command_buffer_create_ns` 9.4%, against 29.3% for the drawable wait. The upload path is therefore
the thing to attack, and it is what utility submission batching (see the `submissions` note above)
targets; the drawable wait is
the GPU actually being busy, which CPU-side work cannot remove.

The rest of the tail is a second, different signature: frames with ~18k draws, negligible upload
bytes and a 26-36 ms drawable wait. Those are GPU-bound frames, and Vulkan has them too (its worst
frame was 125 ms); the fix there is less GPU work per frame, not less CPU work.

### After utility submission batching

A second Metal capture of the same world, descending to a fixed depth and then holding station there
(`20260923-193845`). Comparing the underground band against the run above, at matching work:

| `0 <= Y < 63` | before | after |
|---|---|---|
| frames | 811 | 1260 |
| mean frame | 17.64 ms | 13.90 ms |
| draws / `ffi_calls` | 17420 / 54087 | 17198 / 52409 |
| `upload_api_ns` | 3.689 ms | 0.219 ms |
| `command_buffer_create_ns` | 3.129 ms | 0.104 ms |
| `upload_api_ns` + `command_buffer_create_ns` | 6.818 ms | 0.323 ms |
| `staging_allocations` | 53.5 | 0.0 |
| `submissions` | 106.0 | 40.6 |
| frames over 33 ms | 18 | 3 |
| ms per 1000 draws | 1.013 | 0.808 |

The upload path cost 95% less and the same workload ran 21% faster. The hitch signature changed
completely: the worst frames before were upload/queue-bound (53.5 ms with a 40.5 ms upload and a
37.5 ms command-buffer creation, 400 submissions in another), and the worst frames after have
`upload_api_ns` at or below 0.33 ms with every one of them dominated by a 16-31 ms drawable wait.
One 30.1 ms `command_buffer_create_ns` stall remains in ten; treating a saturated queue as the GPU
falling behind, that belongs to the same GPU-bound class.

**Cross-backend numbers from this pair are not trustworthy.** Vulkan ran ten minutes later and
degraded monotonically while standing still at a fixed depth (16.2 → 20.4 → 21.7 ms over 30 seconds
with the player not moving), which is heat soak or background load, not workload. The earlier pair
put Vulkan 26% ahead; this one puts Metal ahead in every Y band and by 2-2.7x in the stationary
deep phase. Settle it with an interleaved A/B/A/B capture of one route rather than two runs minutes
apart: same world, same coordinates, let meshes settle, then compare the settled interval.

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
