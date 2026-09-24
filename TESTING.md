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
| `./tools/render_check/run.sh` | `RENDER CHECK PASSED` (173 assertions) |
| `./tools/scaling_check/run.sh` | `SCALING CHECK PASSED` (91 assertions) |
| `./tools/mixin_check/run.sh` | `MIXIN CHECK PASSED` (72 checks) |
| `net.metalmod.StandaloneTestRunner` | `ALL TESTS PASSED SUCCESSFULLY!` |

The standalone runner needs the client classpath; `build_mod.sh` prints the exact command. The
shader tools and render check also accept an instance directory as an argument or via
`METALMOD_MC_INSTANCE`.

The last two were added in Phase 7 and are worth knowing about:

- **`scaling_check`** drives Phase 7's real machinery offscreen: the engine's own `MainTarget` and
  `FrameGraphBuilder`, the redirect's two states, the MetalFX upscale over a real draw, the resize
  path, the release when the scale returns to 1.0, and the jitter sequence's centring. It is the only
  offline gate that would catch a break in the *shape* of the scaling frame. Twenty-six of its assertions
  are Phase 7B: the scene contract, the motion field's exact values and conventions (a still camera
  must produce zero motion; different jitter phases with a still camera must still produce zero; a
  moved camera must produce uniform motion with MetalFX's sign), the per-object stamps (an entity's
  movement and none of its neighbours', a stamp the depth buffer contradicts being rejected, particles
  and pushed blocks), the reset lifecycle, and the effect's offscreen cost against the spatial path
  at the same sizes.
- **`mixin_check`** resolves every mixin's target class, `@Inject`/`@Redirect` method, `@Shadow` member
  and `@At` descriptor against the real client jar, without launching. `defaultRequire: 0` means a hook
  that names a method the client no longer has fails *quietly* - the feature it drives simply does
  nothing - so this is the cheap guard against the class of defect that has cost this project the most
  time. It cannot prove an injection applies, only that everything it names exists.

Useful one-off: print the generated MSL for a shader pair, or all of them, by adding
`-Dmetalmod.dumpMsl=<substring>` (or `=all`).

### 2.1 Phase 6 lighting switches

Every lighting path is a JVM opt-in, so the default Vulkan path and the plain Metal path do no extra
work at all. The lit variants are only built for the three vanilla terrain pipelines after both
preprocessed sources match a recorded hash; anything else keeps its own shaders and is reported once.

| Switch | What it selects |
|---|---|
| `-Dmetalmod.pointLightProof=true` | 6A: one synthetic camera-centred amber light, terrain only |
| `-Dmetalmod.dynamicLights=true` | 6B: the moving-source set plus the static block index |
| `-Dmetalmod.clusteredLights=true` | 6C: with `dynamicLights`, evaluate through the cluster grid |

The same three switches are on the in-game **Lighting** page (**Options -> MetalMod...**, or Mod Menu
-> MetalMod -> Lighting) and persist to `config/metalmod.properties`. They apply at the next frame
boundary with no restart. Precedence is: an in-game choice, then a `-D` launch flag, then the saved
file - so a flag seeds a session but never prevents changing the setting in game.
`MetalMod_Test_26.2` launches with `-Dmetalmod.dynamicLights=true`, which seeds each session as on;
remove it from the instance's JVM options to let the saved file decide instead.

The variants cover the three terrain pipelines and the two particle pipelines; a pipeline outside
those families keeps its own shaders and is reported once as a fallback. The startup log prints the
three switches and every variant applied, so `grep 'lighting' logs/latest.log` says which path a
session ran. Terrain, particles, entities and items are covered; emissive entity passes are excluded,
moving blocks are covered, and inventory previews stay unlit because they are drawn fully lit. Glow
squids are not a light source: vanilla entities emit no light. A source buried in or sealed by opaque
blocks is dropped; unshadowed leakage through walls otherwise remains.

Re-run the inventory **and** the render check with the switches on — the clustered variant is a
different shader pair, and the inventory is what compiles all 87 pipelines against it:

```bash
JDK_JAVA_OPTIONS="-Dmetalmod.dynamicLights=true -Dmetalmod.clusteredLights=true" \
  ./tools/shader_inventory/run.sh
./tools/render_check/run.sh          # covers all three paths without any switch
```

The render check drives each path itself, so it needs no JVM arguments. What each lighting assertion
covers, and what is still unproven, is listed in [docs/phase6-plan.md](docs/phase6-plan.md) §7.

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

### Automated routes (F7 records, F8 replays)

Step 3 above is the weak part of a comparison: two people, or the same person twice, will not walk
the same path at the same speed, and the scene differs by more than the backends do. A **route**
replaces it with a fixed list of places to stand, so a capture differs only in the dimension and the
backend.

1. Go to the dimension, press **F7**, then play the route: stand still where you want a measurement,
   move to the next spot, stand still again. Press **F7** to stop. Standing still is what produces a
   measurement - the recorder collapses each stationary stretch into one waypoint whose dwell is
   however long you stayed. Flying continuously gives one waypoint per sample, which replays
   faithfully but is rarely what you want.
2. The route is saved as `debug/metalmod/routes/<dimension>.json` (for example
   `minecraft.overworld.json`). It is plain JSON: positions, rotation and dwell per waypoint, and it
   is safe to hand-edit.
3. Press **F8** as usual. The capture now normalises the world, teleports to each waypoint and holds
   it for its recorded dwell, then holds at the last one for whatever remains of the 60 seconds.
   Nothing else changes: the same F8 flow, summary and CSV.
4. To compare, run the same F8 in the same world on the other backend, or with
   `-Dmetalmod.privateTextures=false`. Every waypoint is in the same place in both captures.

What the capture adds for a route:

- **`route_stage`** in the CSV: the waypoint index a frame belongs to, `-1` with no route. Frames
  sharing a stage index across two captures are the same place doing the same thing.
- **A per-waypoint table** in `summary.txt`: frames, mean/median/p95/worst frame time and the mean
  player position for each waypoint. The mean position sits next to the waypoint's own coordinates on
  purpose - teleports run with output suppressed, so a bad coordinate or dimension fails quietly, and
  a stage whose mean position is nowhere near its waypoint is the visible symptom.

**World prep** runs before the route, to remove the randomness that spoiled the earlier pairs:
`doDaylightCycle false`, `time set noon`, `weather clear`, `doMobSpawning false`,
`randomTickSpeed 0`, `kill @e[type=!player,type=!ender_dragon,type=!wither]`, `gamemode spectator`.
The dragon and the wither are left alive because killing them changes those scenes; an End or wither
fight is therefore not reproducible and the summary says so. Prep is on by default and switchable off
with `-Dmetalmod.capturePrep=false`, which is worth doing when you want the world left as it is.

Teleports go through the integrated server's command dispatcher, so routes work in a singleplayer
world **without cheats**. On a real server the client command path is used instead, which needs
operator rights. Two settings, both launch-time:

| property | default | meaning |
|---|---|---|
| `-Dmetalmod.routeName=<name>` | the dimension id | use a second route in the same dimension |
| `-Dmetalmod.routeSampleMs=<ms>` | 500 | how often the recorder samples (clamped to 100-5000) |

Keep waypoints within chunks that are already loaded, or within the same X/Z at a different height.
A teleport into unloaded chunks raises the loading screen, and those frames are excluded from
gameplay statistics as menu frames - the summary's `Frames saved` versus `Gameplay frames` line shows
if that happened.

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

**Every texture is created `MTLStorageModeShared`. That is the measured-faster configuration**, and it
is also the default. A switch can make render attachments private instead, and it is off because an
A/B said so.

The switch, when enabled, creates a texture with `USAGE_RENDER_ATTACHMENT` as
`MTLStorageModePrivate` and leaves everything else shared. The reasoning was that private storage lets
the GPU keep a render target in tile memory and compress it. It did not pay for itself:

| Overworld route, same world, Metal both runs | private (run 1) | shared (run 2) |
|---|---|---|
| stage 0, above ground (6.6k draws) | 9.72 ms | **8.91 ms** |
| stage 13, underground (19.2k draws) | 16.40 ms | **15.06 ms** |
| overall average | 13.454 ms (74.3 FPS) | **12.331 ms (81.1 FPS)** |
| p95 | 24.912 ms | **19.230 ms** |

Private storage is about **9% slower on both stages**. The private run took the *earlier*, cooler
slot and the shared run the later one, so drift works against the result rather than for it. Whatever
compression benefit was expected does not appear on this hardware, while the cost of a private render
target that later passes sample does. Enable it with `-Dmetalmod.privateTextures=all` (or `true`) if
you want to re-test on another GPU; any unrecognised value leaves it off.

Captures record which setting was used, because that was otherwise only recoverable from the game
logs: `Render target storage: shared (default; ...)` in `summary.txt`.

The classification rule, kept because it is what the switch uses and what a re-test would need. Note
that the copy flags cannot be used for it: `RenderTarget` creates *both* its depth and its colour
texture with the constant **15** - `COPY_DST | COPY_SRC | TEXTURE_BINDING | RENDER_ATTACHMENT` - so
every render target in the game declares both copy flags. Classifying on them claimed **nothing at
all** in a real session (`privateTextures=0`, no `private storage:` lines). What discriminates is that
a render target is *rendered into* rather than *uploaded into*, and in 26.2 that line falls where the
flags say it does. Bytecode-verified for each case:

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

`-Dmetalmod.privateTextures=all` turns private storage on, to re-test it; the default is off.

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

**§5 is that procedure written down**, including the route recording that makes "the same coordinates"
literally true instead of approximately true, and the visual pass that confirms the fixes still marked
unconfirmed in [bug.md](bug.md).

### What the routed comparison measured

Three captures of the same recorded Overworld route, same world, 5120x2664, render distance 32, vsync
off, in one sitting, minutes apart. This is the comparison the earlier pairs failed to produce: every
frame is labelled with its waypoint, so these are the same two places doing the same thing.

| | Metal, shared (default) | Metal, private | Vulkan |
|---|---|---|---|
| stage 0, above ground, 6.6k draws | 8.91 ms | 9.72 ms | **8.65 ms** |
| stage 13, underground, 19.2k draws | **15.06 ms** | 16.40 ms | 21.09 ms |
| overall average | **12.331 ms (81.1 FPS)** | 13.454 ms (74.3 FPS) | 14.449 ms (69.2 FPS) |
| p95 | **19.230 ms** | 24.912 ms | 25.036 ms |
| frames over 33 ms | **81** | 105 | 81 |

Three conclusions:

1. **Private storage for render targets is a 9% regression**, on both stages, with the private run in
   the earlier and cooler slot. It is now off by default; see the storage-mode section.
2. **Parity is met and exceeded.** Metal is 3% behind Vulkan on the light above-ground stage and 29%
   ahead on the heavy underground one, and its tail is better (p95 19.2 against 25.0 ms). Phase 5's
   "comparable frame rate" is no longer the open question.
3. **The underground stage is the heavy one**, 19.2k draws against 6.6k, which is why the route puts
   the long dwell there. The draw count column is what confirms that; without it a waypoint teleported
   inside solid rock would look like a fast frame time instead of an empty scene.

The Metal Nether capture from the same session (`20260923-210444`, private storage on) is a separate
scene - 11.3k draws in its settled stage, 119.7 FPS average - and has no matching pair, so it says
nothing about the backends. It is a recorded route ready to run if a third dimension is wanted.

## 5. Phase 5 sign-off procedure

Phase 5's exit criterion is *"a normal session is visually indistinguishable from Vulkan/MoltenVK, at
comparable frame rate"*. Both are now complete for the tested scope: the routed comparison in §4 and visual confirmation in
§5.5 supersede the earlier contradictory pairs and pending confirmations. The procedure below is
retained for regression testing. The final offline recheck and limitations are in
[docs/phase6-plan.md](docs/phase6-plan.md).

Budget about an hour. Do it in one session: drift is what spoiled the previous comparisons, and the
closer together two captures are, the less of it there is.

### 5.1 Freeze the settings

Every one of these has to be identical across the captures or the numbers are not comparable. Two of
them already differ between the existing captures, which is part of why they disagree.

- Window size, maximised or fullscreen the same way each run. **Write it down.** An earlier pair ran
  at 1708x960 and an earlier one at 5120x2664.
- Render distance. The captures so far were taken at 32.
- Vsync **off**, FPS limit **260**.
- The same world, and the same mods. No performance mods on either backend.
- Do not touch the window, the mouse or the keyboard during a capture. Interacting changes the scene
  and the capture will label those frames as such.

### 5.2 Record the routes (once each)

Two waypoints is enough per dimension, and the shape that matters is one settled spot above ground and
one settled spot below it **at the same X and Z** - moving straight down keeps the teleport inside
loaded chunks, so the measurement is the scene and not the chunk load.

1. Overworld: stand in the open, press **F7**, stand still for ~20 s, then descend in one step to a depth
   you care about (say Y 70 to Y -20, same X and Z) and stand still for 25 s, then press **F7**. Descend
   in one jump if you can - fly down fast, or `/tp` yourself if the world has cheats - because the
   recorder turns slow travel into **one waypoint per sample**, and each of those replays as a teleport
   every half second.
2. Nether: same idea. This is the heaviest vanilla scene and it puts the lightmap at its extremes -
   block light only, no sky light.
3. End (optional): the dragon and the wither are deliberately not killed by prep, so that scene is not
   reproducible and its captures will not be comparable run to run. Record it only if you want to look
   at the rendering, not to compare frames.

Confirm each recording: the log says `Route saved: ...` and
`debug/metalmod/routes/minecraft.overworld.json` (the dimension id with `:` as `.`) has the waypoints
you expect. Two long waypoints - one above ground, one below - is the shape to aim for. A few short
ones in between are the descent; a dozen or more means the recorder saw continuous movement, which
still replays faithfully but measures less of what you wanted.

### 5.3 Take three captures, back to back

Reload the world before each one, let it settle 30 s, then **F8** and wait for it to finish.

| run | backend | extra JVM argument |
|---|---|---|
| 1 | Metal | *(none - shared storage, the default)* |
| 2 | Metal | `-Dmetalmod.privateTextures=all` |
| 3 | Vulkan | *(none)* |

Put the argument in the launcher's JVM arguments, not the game directory. Run 2 is the private-storage
variant; it has already been measured once and came out about 9% slower (see the storage-mode section
above), so it is only worth repeating on different hardware. The `Render target storage:` line in each
`summary.txt` says which setting a capture used.

### 5.4 Read the result

Each `summary.txt` now has a per-waypoint table. Compare them waypoint by waypoint, never the overall
average: two captures of the same route have the same waypoints in the same order, so waypoint 1 of
run 1 and waypoint 1 of run 3 are the same place doing the same thing.

| waypoint | run 1 mean | run 2 mean | run 3 mean |
|---|---|---|---|
| 0 (above ground) | | | |
| 1 (underground) | | | |

Then check the four things that make the numbers trustworthy before believing any of them:

- **`privateCpuAccess` is 0** in the resource summary, and `refused` does not appear in the log. A
  non-zero value means a texture the CPU uploads into was given private storage.
- **`Frames saved` is close to `Gameplay frames`.** A large gap means teleports raised the loading
  screen, and those frames were dropped from the statistics as menu frames.
- **Each waypoint's mean position matches its coordinates** in the same table. A stage whose mean
  position is somewhere else means the teleport failed - most likely a coordinate outside the world or
  a dimension id that does not resolve - and that stage measured the wrong place.
- **Each stage's mean draw count is what you expect for the scene.** This is the one that is easy to
  miss: a waypoint teleported *inside* solid rock draws almost nothing, so its frame time compares
  consistently between runs but does not mean anything. If the underground stage shows a few hundred
  draws where the above-ground stage shows thousands, the camera is buried rather than in a cave, and
  that waypoint should be re-recorded somewhere with something to look at.

Conclusions to write down: whether run 2 differs from run 1 at all (private storage), and how far run 3
is from run 1 (parity). If run 3 is within roughly 10% the criterion is met; if it is not, the
per-waypoint split says *where* it is not, which is the useful part.

### 5.5 Visual confirmation pass

**Status: done (2026-09-23).** All fourteen were confirmed in game - menus and the inventory, the sky
and clouds, lighting, terrain and entities, selection and chunk-border lines, depth behaviour, and the
F3 health counters at zero. [bug.md](bug.md) records the confirmation on each entry. The checklist is
kept because it is also the regression list to walk after any change to the shader, pipeline or
attachment paths; those are the symptoms that a backend change tends to reintroduce.

One play session, no captures needed. Each line is what to look at and what "correct" looks like.

**Menus, no world needed**

- **BUG-001** - Singleplayer, Select World. Each list entry has its background panel behind the
  thumbnail and text, and the name line is one normal line. The bug was a missing panel plus a
  compressed, garbled strip of the name above the real one.
- **BUG-025 / BUG-024** - open the survival inventory. The player model in the top-left is **upright**,
  the item icons are **present in their slots** and the right way up. All three of those have been wrong
  at different points, so check all three.

**On entering a world**

- **BUG-020** - the world loads at all. This one aborted loading outright when texel buffers were one
  row wide.
- **BUG-013** - vanilla clouds are visible overhead and drift normally.
- **BUG-015** - look at the horizon and straight up: the sky is a full dome, not a disc truncated at an
  edge.
- **BUG-022** - light levels read correctly: walk from a torch-lit cave into daylight and back. The bug
  was the lightmap stored mirrored, so dark and bright were swapped.
- **BUG-003** - terrain and entities are shaded, not flat black. Water is the quickest check (squids
  and fish were the reported case).
- **BUG-002 / BUG-014** - look at a block: the selection outline is a thin box hugging its edges, not a
  huge wireframe box. Press **F3+G** for chunk borders, which are also `LINES` geometry, and confirm
  they are clean lines.
- **BUG-004** - fly around a chunk-dense area: no section renders with the wrong offset, and terrain does
  not snap between positions as you move.
- **BUG-016 / BUG-017** - no z-fighting on coplanar surfaces, and geometry does not vanish depending on
  what was drawn before it. The offline cases are covered by `render_check`; in game, water edges, item
  frames and the hotbar are the quickest places to notice.
- **BUG-005** - press **F3** and confirm the health line reads
  `unbound/missingAttr/failed: 0/0/0`, and that the log has no `[MetalMod] unbound` lines. This one is a
  diagnostic, not a symptom: silence is the pass.

**In the Nether** (with the route recorded in 5.2)

- Nether fog is red-orange and transitions as you move, rather than the Overworld's colour.
- Both portal types render their animated surface.
- The lightmap reddens/saturates as expected, with no sky-light contribution.

### 5.6 What to hand back

The three `summary.txt` files and the two route JSON files are enough to do the analysis; `frames.csv`
is only needed if something looks wrong and the per-waypoint table is not enough to explain it.

## 5.6 Phase 6 final test

**Run 2026-09-25, and Phase 6 is closed on the result.** Sections A and B were exercised across the
testing sessions and C in part; the readings and the reasoning are in
[docs/phase6-plan.md](docs/phase6-plan.md#final-verdict-against-those-gates-2026-09-25). What was
**not** captured is named there too rather than left to be rediscovered: a photographed wall scene, a
dimension change and a disconnect/reconnect, and the deterministic scaling and performance records.
The checklist below is kept as written, because those three items are still what a future session
should run - the last one belongs with Phase 8, which is where true GPU timing arrives.

The implementation is complete; this pass produces the evidence the remaining Phase 6 gates ask for.
Everything it needs is already on F3 and in the F8 capture - no code changes are expected, and a
failure here is a finding rather than a missing step.

**Setup.** Launch with the Metal backend, `-Dmetalmod.dynamicLights=true`, and
`-Dmetalmod.clusteredLights=true` (or set all three on the in-game Lighting page). Open F3 and keep the
MetalMod block visible: every check below is read from it.

### A. Wiring, once at the start

- The log shows `lighting: pointLightProof=false dynamicLights=true clusteredLights=true`.
- The log shows `lighting variant terrain-clustered-lights-v1 applied to minecraft:pipeline/...` for
  the terrain pipelines, and the same for particle, entity, item and block pairs as they appear.
- After 30 s the hook summary contains `+LevelExtractor.extract +LevelExtractor.setLevel
  +OptionsScreen.init`, and the resource line reads `failures=0 pipelineFailures=0 unboundBindings=0
  missingVertexAttributes=0`.
- F3 shows **no** `hooks missing ...` line. If one appears it names only hooks whose code runs on any
  session that draws a frame; the two screen hooks (`OptionsScreen.init`,
  `MetalModLightingConfigScreen.init`) are not part of that set, because they report when their screen
  is opened and a session that never opens the settings would otherwise carry a permanent red line
  meaning "you have not opened a menu yet". Their proof is the `HOOK ACTIVE` line in the log.
- **No** `point-light proof skipped for ...` line. If one appears, a shader pair did not match its
  recorded fingerprints and is running vanilla.

### B. Image correctness, per family

Hold a torch and confirm each of these brightens with the terrain and keeps its own shading and alpha:

- terrain, including cutout leaves and translucent water;
- particles (dust, flame) — these were a separate gap and were dark;
- a mob or dropped item — the entity pair;
- the item in your **main** hand, then the **offhand**; then press F5 for third person;
- put a block on a piston and push it — the moving-block pair.

Then: walk into fog (illumination must be applied *before* fog), and stand in water.

### C. Lifecycle — the gate with no evidence yet

| Action | Expected |
|---|---|
| Throw a light-emitting item down | light appears at it |
| Walk away until it despawns | light goes with it |
| Pick it up | light disappears |
| Nether → Overworld | `environment` line changes; no light left behind |
| Disconnect and reconnect | `block sources` resets; no stale emitters |
| `F3+T` (resource reload) | lit variants come back, not silently vanilla |
| Resize the window | no crash, no leak, counters unchanged |
| Toggle lighting off and on in the Lighting page | world goes vanilla and returns, no stale glow |
| Enter spectator mode (`/gamemode spectator`) | lighting goes off; leaving it comes back |
| In spectator, open the Lighting page | the toggle still shows what you set, not that it was suppressed |

### D. Scope honesty

- **The inventory must not change** near a torch. Items there are drawn fully lit, so the dynamic term
  has no headroom; if an inventory item brightens, that is a bug worth reporting immediately.
- Emissive passes (entity eyes, energy swirl) stay exactly as they were.
- A **placed** torch must not be brighter than vanilla - it is baked, and MetalMod does not add to it.
- A glow squid, sealed or in the open, lights **nothing**.
- A source buried in or sealed by opaque blocks is dark; F3's `buried` shows a non-zero count.
- Standing next to a wall, light **does** still leak through it. That is the known limitation and
  Phase 8B's job, not a defect to report.

### E. Scaling record

Walk one dense scene and read F3, then the capture:

- `dynamic lights N/64 dropped D` — D non-zero means more than 64 sources were within 32 blocks.
- `clusters … occupancy X max/Y mean, overflowed O, evicted E, unreachable U` — O or E non-zero means a
  cell wanted more than its 16 entries.
- `light cost X ms extract | Y KiB up | Z cluster builds`.

**Read the extraction cost with its breakdown.** Past 0.1 ms the same line adds the largest of the three
parts, so `flat 7.4 ms (ent 7.3)` says the entity query owns the time, `(idx …)` the static block index,
and `(oth …)` occlusion, sorting and publishing. The distinction matters because they have different
fixes and different expected values: the entity query scales with what is loaded around the player and
runs every frame, while the index is meant to be a bounded number of map lookups once its sections are
cached. A large `(ent …)` on a scene with almost nothing in it is the interesting failure.

Then run an F8 capture in scenes with roughly 0, 1, 16 and 64 nearby sources. The CSV now carries
`light_published`, `light_dropped`, `light_buried`, `light_examined`, `light_allocated`,
`light_extract_ns`, `light_cluster_builds`, `light_cluster_build_ns`, `light_uploads`,
`light_upload_bytes`, `light_occupancy_max`, `light_overflowed`, `light_evicted`,
`light_unreachable`, `light_entity_query_ns` and `light_block_index_ns`, which is what the scaling gate
asks to record. `light_entity_query_ns + light_block_index_ns` subtracted from `light_extract_ns` is
the remainder F3 names as `oth`.

### F. Performance, and one open decision

Capture the same route **interleaved** — off, on, off, on — at least three paired repetitions, after a
warm-up, and record settings, build and thermal state. Report median and p95 frame time; these are
wall-time figures and must be labelled as such, because there is still no true GPU timing.

The decision this settles: **flat versus clustered as the default.** The flat path loops over all 64
published lights per fragment; the clustered path loops over its cell's entries, at most 16 and usually
one or two. Capture both (`-Dmetalmod.clusteredLights=false` for flat) and if clustered is no slower
and looks the same, it becomes the default and the two switches collapse into one.

### What to send back

`logs/latest.log` for the session, one F3 screenshot per section above, and the F8 capture summaries
from `debug/metalmod` for E and F. Anything that fails is worth a screenshot of F3 as well - the
counters usually say which part disagreed.

---

## 6. Phase 7 render scaling and upscaling

Phase 7 has no in-game confirmation yet - neither 7A nor 7B. This section is what closes it: the
wiring check, the visual checks, the temporal checks the motion producer makes possible, and the
native-versus-scaled measurement the roadmap's exit criterion asks for. Nothing here needs code
changes.

### The entry point

**Options → MetalMod… → MetalFX Upscaling**, or Mod Menu → MetalMod → *MetalFX Upscaling: …*.

The page is a control panel rather than a list of switches, because the questions a render-scale
setting raises are about the frame, not about the setting:

| Control | What it does |
|---|---|
| **Render scale `-` / `+` / Native** | Steps through 100% / 85% / 75% / 67% / 50%. `-` renders fewer pixels (faster, softer), `+` more (sharper, slower), `Native` turns scaling off |
| **Upscaler** | Cycles **Off (native) → MetalFX spatial → MetalFX temporal**. Off renders at native resolution; spatial reconstructs from one frame; temporal accumulates across frames using the motion producer, which is the mode that resolves detail beyond the render resolution - and the one whose motion field covers the camera and static geometry only |
| **Show change notice** | An in-world toast naming the resolution the next frame will use. On by default: without it the only confirmation is F3, which does not say *when* a change landed |
| **Live status** | What is actually happening, refreshed every frame, including which effect ran and - for temporal - the motion producer's own counters |

The live status is the part worth reading, because every number comes from the running frame rather
than from a setting:

```
Renderer: Metal   Scale: 50%   World: 1280x666 -> native 2560x1332   Effect: MetalFX temporal
Upscaled frames: 412   Temporal: motion 1280x666 camera-only, dispatched 412, resets 3
```

If it says **`FAILED: N`**, the reason is on the page. If temporal was selected but cannot run, the
page says why and that spatial is running instead. A page that restated the settings would look
identical whether the feature worked or not, which is the failure this display exists to make
impossible.

### A. Wiring, once

Launch with `-Dmetalmod.renderScale=0.5` (or set it on **Options → MetalMod… → MetalFX Upscaling**),
with the Metal backend on. The log should name the target it built:

```
[MetalMod] render scale 0.50: world renders at <w>x<h>, upscaled to <w>x<h>
[MetalMod] MetalFX spatial scaler: <w>x<h> -> <w>x<h> (colour mode 0)
```

F3's `[MetalMod] upscale` line reports the sizes, which effect actually ran, and a failure count with
its reason. When temporal is running there is a second `motion` line naming the producer, the frames
dispatched and the resets with the last reset's reason; when temporal was asked for but cannot run,
that line is replaced by `temporal not running` and the fallback reason.

### B. The observation that matters most

**The interface must not move.** This is the whole reason the level has its own target, and it is the
failure that reverted the pre-Phase-5 attempt at scaling. At 50%, with the world visible:

1. Open the inventory and the pause menu. Every panel, tooltip and text position must be identical to
   what 100% produces - same place, same size, same crispness.
2. Move the mouse across the hotbar and read the F3 line: text must be as sharp as at 100%.
3. Resize the window, then toggle fullscreen. Nothing may shift, and no `Scissor ... out of bounds`
   may appear in the log.

If any of that fails, the redirect is leaking into the interface path and the log line to look for is
a `render target ... yFlip` or a scissor complaint.

### C. The world

At 50% the terrain is genuinely softer - that is the trade, not a defect. What is a defect:

- **Misalignment**: a block edge or the horizon offset from where the crosshair says it is.
- **Stretching**: the aspect ratio wrong after a resize.
- **A frozen or missing world** with the interface drawn over it - the case the conditional colour
  split exists for. Open and close a menu over the world and check the world is still there.
- **The world replaced by an older frame** on a resize. That was [BUG-031](../bug.md), fixed by making
  the upscale consult the frame's own "am I scaling" decision; a regression shows as the world lagging
  one frame behind the interface during a drag.

### D. Temporal (Phase 7B)

Select `MetalFX temporal` at 50% and work through the checks that separate a motion source from a
scaler that is merely running. The motion field covers the camera and static geometry, so the
expected results are specific:

1. **A still camera must be stable.** Stand still and look at a hard edge (a block corner against the
   sky). It must not shimmer or crawl. Temporal accumulates, so it should *converge* - the edge
   steadying over a few frames rather than vibrating.
2. **A moving camera must not smear the world.** Walk forward, then strafe, then look around. Terrain
   edges must stay attached to their surfaces. Camera motion is the case the producer is exact for,
   so any whole-screen dragging means the matrices or the conventions are wrong, not that the mode is
   young.
3. **Turning on the spot must not ghost the terrain.** Rotate 180° slowly, then quickly. A quick turn
   is a camera cut by the reset heuristic; a slow one has to expand across the screen without leaving
   duplicate edges.
4. **A mob must not trail.** Watch an animal walk past, then a fast one - an arrow, a thrown item, a
   minecart. Each is stamped with its own velocity inside its bounding box, and the depth test keeps
   the stamp off the terrain in front of and behind it. What to look for is the *box*: if the mob's
   own pixels are crisp but a rectangle of terrain around it loses its antialiasing, the box is too
   generous; if the mob's silhouette has a residual fringe, it is too tight. Record the mob type and
   distance either way.
5. **Particles must not trail.** Break a block, light a fire, stand in rain. A particle writes no
   depth, so its pixels are stamped with the particle's own small quad - a rain shower is the case
   that reaches the stamp budget, and F3's motion line reports a dropped count if it does. A short
   trail is a regression here, not the expected result.
6. **A firing piston must not smear its block.** Push a block with a piston and watch it travel. The
   pushed block is stamped for the two ticks the animation lasts; a one-block trail behind it is the
   failure this covers.
7. **A dimension change must not blend the two worlds.** Use a portal or `/execute in`. The frames
   either side have the same camera coordinates, so only the explicit reset stops the two being
   blended; a visible cross-fade through the old dimension means the reset did not reach the encode.
8. **The HUD stays native and sharp**, exactly as at 100% - the same check as §6.B.
9. **Resize at 50% temporal.** The scaler and the motion resource both have to be rebuilt for the new
   size. The world must not stretch, freeze, or show the pre-resize frame.
10. **Compare the three modes at one spot.** Press **F6** to cycle off / spatial / temporal in place -
    the same camera, the same scene, a second apart - and note edge quality, texture detail, stability,
    and the F3 `frame` line's cost. The motion pass is a full-resolution read of the depth buffer plus one instanced draw
    per moving object, and neither has been timed in a scene; the number is the point of the
    comparison.

### D. The measured comparison

The roadmap asks for image quality and performance against native-resolution rendering. Take it as a
pair of routed captures, F8 on one route, the same scene and settings, with only the render scale
differing:

| Run | Scale | Upscaler |
|---|---|---|
| 1 (baseline) | 1.0 | n/a |
| 2 | 0.75 | spatial |
| 3 | 0.5 | spatial |

Use the F7/F8 route procedure in §3 so the two captures are comparable, and record: mean frame time,
p95, the F3 `pacing` line's last interval and p95, and the `upscale` line's `fx` count. What the
comparison has to answer is whether the frame time falls by roughly the fraction of pixels removed -
0.75 should be in the region of a quarter faster, 0.5 in the region of twice as fast - and if it does
not, whether the world is GPU-bound at all. A capture that shows no gain at 0.5 says the frame is not
limited by the world's fragment work, which is itself the answer.

Expect the interface to become a *larger* share of the frame as the scale drops, since it never
shrinks - at 0.5 the HUD can plausibly be the most expensive thing on screen.

### D2. Why a frame rate cannot answer "is scaling worth it"

A display paces the frame at its refresh rate. Once the loop is display-paced, the frame rate stops
moving long before the GPU is saturated, so *"the frame rate did not improve"* is equally consistent
with **the GPU is idle** and **the GPU is maxed out** - and those two want opposite decisions about
render scaling. This is not a hypothesis; it is what the reference machine did: at 100%, 75% and 50%
scale it held 60-64 fps with a quarter of the pixels, because the display offers only 30 and 60 Hz.

**Press F9 in game** to measure the work instead of the rate. It times, with the GPU synchronised:

- a full-target fill at the render resolution and at native resolution - the cheapest write there is,
  so a floor on what a pass over that many pixels costs;
- the MetalFX upscale itself.

It reports both as a toast and in the log, with a verdict. Measured offscreen on the reference machine
(Apple M4 Pro, 5120x2664), which is what `tools/scaling_check` prints:

| Scale | Target | Full-target fill | MetalFX upscale |
|---|---|---:|---:|
| 100% | 5120x2664 | 2.03 ms (cold) | - |
| 75% | 3840x1998 | 0.35 ms | 1.64 ms |
| 50% | 2560x1332 | 0.13 ms | 1.49 ms |

**How to read it.** A fill is the *floor* on a pass's cost, not a prediction - real terrain shades far
more than it fills, so these numbers bound the effect but do not measure it. They are also measured
with the engine's own target and no scene: a real frame is where the question actually gets settled.

### Measured in a real session

The same spot in one world, **F10** toggled between the two, F3 open. This is the measurement the
feature should be judged on:

| Setting | Frame time | FPS | Drawable wait | Draws |
|---|---:|---:|---:|---:|
| 50% + MetalFX spatial | **13.5 ms** | 74.4 | 4.7 ms | 6718 |
| 100% native | **16.9 ms** | 61.2 | 8.5 ms | 6597 |

**Render scaling saves 3.4 ms of a 16.9 ms frame - about 20%, and 74 fps against 61.** The drawable
wait falls by 3.8 ms, which is more than the frame time does: the extra 3.4 ms is real rendering work
that was being hidden behind the display's pacing, and the upscale itself is inside the noise.

Two things follow, and they are the opposite of what an earlier reading of the offscreen numbers
suggested:

1. **The GPU is the limit at native resolution in a full scene.** At 50% the frame becomes
   CPU-limited instead - the next gain has to come from draw and pass cost, not from pixels.
2. **The cost probe's floor was misleading on its own.** A clear is not a terrain pass; a scene heavy
   in overdraw costs far more per pixel than a fill does, which is why the in-scene comparison and not
   the offscreen one is the number to quote.

The earlier offscreen table stays in this document because it is the reason the probe exists, and
because it is a caution: a cheap proxy for a pass's cost can point the wrong way.

### D3. The comparison that settles it: F10

The frame time depends far more on where you are standing than on anything render scaling does, so two
sessions - or two positions - cannot be compared. **F10 flips the render scale between native and the
last scaled setting, in place**, and says which way it went on the action bar. With F3 open, the `frame`
line is then two readings a second apart in one spot:

| What you see | What it means |
|---|---|
| native frame time **lower** than scaled | the scaler costs more than the pixels it saves; scaling is a pessimisation here |
| about **equal** | the frame is not limited by the world's pixels at all; scaling trades sharpness for nothing |
| scaled clearly **lower** | the feature is doing its job |

F10 twice returns to exactly the configuration you started in, so it is a comparison rather than a
reset. The reference numbers from the plans: Phase 6 at native was **10.9 ms** on this world, and
Phase 7 at 50% with MetalFX spatial measured **9.7 ms** — which is the "about equal" row, and is why
the cost probe's verdict on this machine is that the frame is not pixel-bound.

### D4. The grey sky (BUG-029)

At a render scale below 100% the sky comes out brighter and less saturated than at native - green and
blue rise while red does not move, which is why it reads as grey. It is an open defect; the scaler has
been cleared of causing it.

**F10 logs two things**, and both are needed:

```
[MetalMod] frame sample 5120x2664 | 50% 2560x1332 -> native, MetalFX spatial (upscaled 4180) | sky-top 183 190 203 | sky-left ... | sky-right ... | ground ...
[MetalMod] sky strip mean | world (before upscale) 143.2 171.0 230.8 | main (after) 152.1 178.4 236.7 | difference 8.9 7.4 5.9
```

The first line is the finished frame, at named points. The second compares the **same region of the
picture before and after the upscale**, in one frame:

| Second line | Conclusion |
|---|---|
| world and main agree | the upscale introduces the difference |
| they already disagree | the scaled pass renders the sky differently, and the cause is in what the engine sets for that pass - fog distances and the projection among them |

Press F10 once at native and once at 50% and send both pairs of lines. That is the measurement that
closes this.

### E. The five-minute pass

If nothing else gets done, this does:

1. Load a world on the Metal backend. Stand still and note the FPS.
2. **Options → MetalMod… → MetalFX Upscaling**.
3. Press `-` twice (to 75%, then 67%). A toast names the new size. The frame rate should rise.
4. Check the status line: `Upscaled frames` counting up, no `FAILED`, and the effect named as
   `MetalFX spatial`.
5. Press `Native`. The toast says native, the frame rate drops back, and the world sharpens.
6. Press `-` four times (to 50%). Open the inventory. **The HUD must be as sharp as at 100%.**
   This is the single observation that decides whether 7A works.
7. Cycle the upscaler to `MetalFX temporal` and repeat step 5 of §6.D: walk, strafe and look around,
   then stand still. The world must stay attached while the camera moves, and steady once it stops.
   Moving mobs are expected to trail - that is the named gap, not a failure.
8. Resize the window once, and check the log for `Scissor`.

Anything that fails: screenshot the page (it carries the evidence) and F3 (the log line).

### F. What to send back

`logs/latest.log`, one F3 screenshot per scale, and the F8 capture summaries from `debug/metalmod`
for D. A screenshot of the inventory at 50% and at 100% side by side is the single most useful thing
for B; a screenshot of the MetalFX Upscaling page with the status line visible is the most useful
thing for A and C together, since it carries the sizes, the path and the failure reason at once.

---

## 7. Troubleshooting

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
- **Render scale seems to do nothing.** Check F3's `upscale` line: if it is absent, the scaled target
  was never created (look for a `could not create the scaled world target` line in the log), and if the
  line names `spatial` while you selected temporal, the `temporal not running` line under it carries
  the fallback reason.
- **The interface is blurry at a render scale below 100%.** That is the defect this design exists to
  prevent - the redirect is leaking. Report it with the scale and an F3 screenshot; do not work around
  it by raising the scale.
