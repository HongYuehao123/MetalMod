# Testing MetalMod

## What changed in this build (and why the last one did nothing)

Your log from the previous run reported:

```
[main/WARN]: @Mixin target net.minecraft.class_757 was not found metalmod.mixins.json:GameRendererMixin
[main/WARN]: @Mixin target net.minecraft.class_1041 was not found metalmod.mixins.json:WindowMixin
[main/WARN]: @Mixin target net.minecraft.class_276 was not found metalmod.mixins.json:RenderTargetMixin
[main/WARN]: @Mixin target net.minecraft.class_310 was not found metalmod.mixins.json:MinecraftMixin
[MetalMod] hook summary after 30s: -GameRenderer.render -GameRenderer.getBasicProjectionMatrix ...
```

Three separate problems were found by reading the real 26.2 client jar with `javap`:

1. **Intermediary `class_XXXX` targets do not exist in this build.** It runs with *named* mappings
   (the jar contains `net/minecraft/client/renderer/GameRenderer.class` and **zero** `class_`
   entries). Mixin rejects an *entire* mixin if **any** entry in `targets` is missing, so the
   dual-target lists killed every hook at once.
2. **The F3 hook point was dead code.** `DebugScreenOverlayMixin` did apply — it got no warning —
   but `DebugScreenOverlay.extractLines` is never called any more. Since 1.21.9+ the overlay
   iterates a registry and calls `DebugScreenEntry.display(...)`. The bytecode of
   `extractRenderState` confirms this.
3. **Several methods in the mod simply do not exist**: `RenderTarget.blitToScreen`,
   `RenderTarget.resize(int,int,boolean)` (it takes two arguments), `Minecraft.getMainRenderTarget`,
   `Minecraft.resizeDisplay`, `Window.getFramebufferWidth`, `Window.onFramebufferSizeChanged`,
   `GameRenderer.getBasicProjectionMatrix`.

4. **The compile stubs declared the wrong `@Retention` on Mixin's annotations.** This was the
   reason nothing injected even once the targets were correct. Verified from sponge-mixin:

   | Annotation | Real retention | Stub had |
   |---|---|---|
   | `@Mixin` | `CLASS` | `CLASS` ok |
   | `@Shadow`, `@Inject`, `@ModifyVariable`, `@At`, `@Accessor` | **`RUNTIME`** | `CLASS` wrong |

   Mixin reads those as *visible* annotations. With `CLASS` they landed in
   `RuntimeInvisibleAnnotations`, Mixin could not see them at all, so injections were skipped
   silently (every injector uses `require = 0`) and the `@Accessor` interfaces were misclassified
   as *interface mixins* - producing `@Mixin target type mismatch: ... is not an interface`.
   The accessor class now byte-for-byte matches Sodium's placement: `@Accessor` in
   `RuntimeVisibleAnnotations`, `@Mixin` in `RuntimeInvisibleAnnotations`.

5. **Scaling the main render target is not viable in this architecture.** It produced
   `Scissor ... is out of bounds for render area` on click and left the screen unresponsive. That
   mixin is gone. See "Why scaling was removed" below.

After fix 4, all four hooks applied on the next run:

```
[MetalMod] F3 debug entry: registered=true verified=true id=metalmod:status
[MetalMod] hook summary after 30s: +GameRenderer.render +GameRenderer.resize +RenderTarget.resize(main) +Window.onFramebufferResize
```

6. **Two reporting inconsistencies**, visible side by side on screen, are fixed:
   - The window title measured the window with `convertSizeToBacking` on the content view, which
     *includes* the macOS title bar, so it read `5120x2880` while F3 (GLFW's framebuffer) read
     `5120x2664`. Both now use the renderer's size.
   - F3 said `Vulkan interop not registered` while the title said `does not own presentation`,
     because the overlay read a status field that stopped being updated. Both now call one shared
     computation.
   - The render FPS was an exponential average that a single multi-second stall dragged down for
     tens of frames (it read `11.5 fps` next to vanilla's `113 fps`). It is now counted over a
     rolling 0.5 s window.
   - The title no longer reports a "requested" resolution that nothing renders at.

Everything is now retargeted against signatures read out of your jar, and the compile stubs were
rewritten to match (retentions included), so the build actually checks the API it calls.

---

## Artifact

```
build/libs/metalmod-1.0.0.jar
sha256 26608091ad4524edb17b0a41c7b07bed272e09511af18a9d2a089c9f88734037
```

Embeds `natives/libmetalmod.dylib` (arm64, sha256
`381521bb93ed38bc5bcdb40b6cc2d2db517fb8ce183ebf8645efbf48a6c8f3e1`). Rebuilding produces a different
jar hash because zip entries carry timestamps.

### Install

```bash
INST="$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2"
cp build/libs/metalmod-1.0.0.jar "$INST/mods/"
rm -f "$INST/mods/metalmod-1.0.0.jar.old"
```

### Reset the config first (important)

Your existing `config/metalmod.properties` has `scalingMode=SPATIAL` and
`enableUnifiedMemoryPool=true`. Now that the resize hook actually applies, `SPATIAL` +
`ULTRA_PERF` would immediately render the world at 33% in a fresh install, and the UMA pool is the
pessimising allocator path. Write a clean, safe config for this test:

```bash
cat > "$INST/config/metalmod.properties" <<'EOF'
scalingMode=OFF
preset=NATIVE
frameGeneration=false
enableUnifiedMemoryPool=false
enableMemoryPressureHandler=true
enableHDR=false
enableUIOverlay=true
sharpness=0.5
targetDisplayFPS=120
EOF
```

`scalingMode=OFF` is also the new default: the mod leaves rendering completely untouched until you
opt in.

---

## Stage 1 — does everything load and hook?

Launch the game, load a world, **press F3**, and let it run a minute.

### Expected: an F3 section

Look in the F3 overlay for lines beginning `[MetalMod]`:

```
[MetalMod] MetalFX: Off / Native (100%) | pipeline: inactive (MetalMod does not own presentation)
[MetalMod] Resolution: internal 2560x1440 -> display 2560x1440
[MetalMod] Render 61.3 fps | Presented 0.0 fps | Pipeline GPU 0.00 ms
```

Note `Presented 0.0 fps` and `Pipeline GPU 0.00 ms` are *correct*: nothing is being presented by the
pipeline and it is not encoding anything. Previous builds printed a fabricated `presentedFPS = 2 ×
renderFPS`; that is gone.

If a hook failed to apply, a `[MetalMod] hooks: -...` line is added to F3 so the failure is visible
in-game rather than only in the log.

### Expected: log lines

```bash
INST="$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2"
grep -E "MetalMod|mixin" "$INST/logs/latest.log" | head -40
```

Look for:

```
[MetalMod] F3 debug entry: registered=true verified=true id=metalmod:status
[MetalMod] HOOK ACTIVE: GameRenderer.render
[MetalMod] HOOK ACTIVE: RenderTarget.resize(main)
[MetalMod] HOOK ACTIVE: Window.onFramebufferResize
[MetalMod] hook summary after 30s: +GameRenderer.render +GameRenderer.resize +RenderTarget.resize(main) +Window.onFramebufferResize
```

`registered=true verified=true` is a read-back through Minecraft's own API — it proves the accessor
mixin applied, independently of whether anything appeared on screen.

**There must be no `@Mixin target ... was not found` warnings for `metalmod` in the log.** If there
are, the target list is still wrong and I need the exact text.

### Then report the hook summary

The `+`/`-` checklist is the key datum. Please paste it verbatim.

---

## Stage 2 — CPU-bound or GPU-bound?

Internal-resolution scaling has been **removed** (see "Why scaling was removed" below), so the mod
cannot shrink the render target to measure GPU headroom. Use these instead; both need no config
edits and neither is affected by the mod.

### Method A — window size (most direct)

Shrink the game window to roughly a quarter of its area and watch the F3 FPS.

- FPS rises a lot -> **GPU-bound**. Upscaling work is worth doing.
- FPS barely moves -> **CPU-bound**. No upscaling will raise the frame rate; we should redirect.

### Method B — GPU utilisation

```bash
sudo powermetrics --samplers gpu_power -i 1000 -n 20
```

Consistently below ~70% GPU busy while playing means the GPU is waiting on the CPU.

### Method C — render distance

Drop render distance by half. If FPS is unchanged, the bottleneck is not rasterisation.

---

## Why scaling was removed

Setting `scalingMode=SPATIAL` shrank the main render target to 3942x2052 (5120x2880 x 0.77) and the
GUI then threw:

```
net.minecraft.ReportedException: mouseClicked event handler
Caused by: java.lang.IllegalArgumentException: Scissor at 0, 179 with size 2520x2150 is out of
bounds for render area RenderArea[x=0, y=0, width=3942, height=2052]
```

The GUI lays out against the *window* size, so shrinking the render target pushes its scissor
rectangles outside the render area. The click handler dies and the screen stops responding to input
(the window also showed a duplicated, torn image). `RenderTargetMixin` has been deleted; the mod no
longer touches rendering at all.

Rendering the world at a lower internal resolution and upscaling it needs the world to go to its
**own** target, leaving the GUI target at native size. That is the "Stage 0" work described in the
README - not something that can be bolted onto the main target.

## Reporting back

1. The `+`/`-` hook summary.
2. Whether the `[MetalMod]` F3 lines appeared (and what they said).
3. `grep -E "MetalMod" logs/latest.log` output.
4. Stage 2 FPS numbers.
5. Any `@Mixin target ... not found` warnings, verbatim.

---

## If something breaks

**Crash on startup.** Remove the jar from `mods/` and confirm the game recovers, then send
`logs/latest.log` and the `crash-reports/` file. The most likely candidate would be a mixin
signature, but every target and descriptor in this build was verified against your jar with `javap`.

**No `[MetalMod]` lines on F3, but the log says `verified=true`.** The entry is registered but the
visibility mixin (`DebugScreenEntryListMixin`) did not take effect. Report it — the fix would be to
also hook `resetToProfile`, which is what Sodium does.

**World looks blurry.** `scalingMode` is doing its job — set it to `OFF`.

**Config screen crashes.** The config GUI was only partly verified (`Minecraft.setScreenAndShow`
and `Button.builder` exist; `Screen` internals were not confirmed). Report it and use
`config/metalmod.properties` in the meantime.
