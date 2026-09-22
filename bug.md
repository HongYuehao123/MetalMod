# Known bugs

Open defects found while bringing up the native Metal backend. Newest first. This is a parking
lot, not a work queue — items move into `ROADMAP.md` when they become scheduled work.

Each entry records: where it was seen, what it looks like, how to reproduce it, and the current
best guess at the cause. Add a screenshot under `docs/bugs/` when one exists.

---

## BUG-001 — "Select World" list entry has no background panel (and a garbled name line)

**Status:** open, unfixed. Cosmetic — the screen still works.
**Severity:** low. The world is selectable and the screen is usable; it just looks wrong.
**Seen on:** Metal backend enabled, build `ab30f94` (phase 3) + `15c0133`, window
`5120x2880 -> 5120x2880 (Native)`, 60 fps cap, no resource packs.
**Screenshot:** [`docs/bugs/select-world-2026-09-22.png`](bugs/select-world-2026-09-22.png)

### Symptoms

On the Singleplayer → **Select World** ("选择世界") screen:

- The world-list entry's **background panel is missing**. The world thumbnail and the two text
  lines (world name + last-played date, then game mode / cheats / version) float directly on the
  blurred panorama; there is no dark rounded entry behind them.
- A **compressed / garbled line of the world name** appears just above the normal name line.
  It reads like the same CJK glyphs squeezed to roughly half width and clipped.
- Everything else on the screen is correct: the title and its background, the world thumbnail,
  the normal name/date line, the game-mode line, and all six bottom buttons.
- The blurred menu background also looks unusually coarse/blocky (may or may not be the same bug).

### Reproduce

1. Launch with the Metal backend enabled (`-Dmetalmod.metalBackend=true` or
   `preferMetalBackend=true`) into an instance with at least one saved world.
2. Open **Singleplayer → Select World**.
3. Compare against the same screen on the default backend — there the entry has its dark panel.

### Suspected cause (unconfirmed)

The entry background is a sprite from the GUI atlas, and the garble looks like a text/quad drawn
with a wrong sub-rect or transform, so this is probably a leftover of the Phase 3 atlas work
(`ab30f94`, which fixed the atlas Y-flip and the negative-viewport winding):

- the entry background may use a sprite/pipeline variant that is not bound (a name we do not map,
  or a pipeline the engine never precompiles), or
- the world-list widget may rely on a clip/scissor or per-entry transform we do not reproduce, or
- the garbled line may be a second text draw with a stale `ModelViewMat`/`TextureMat`.

### Workaround

None needed. Use the default (Vulkan/OpenGL) backend if the list is hard to read; select worlds
by their position, which still works.

---

## BUG-002 — Block selection outline is drawn as a huge wireframe box

**Status:** open, unfixed.
**Severity:** low / cosmetic. Nothing breaks; it just looks wrong and is distracting.
**Seen on:** Metal backend enabled, in-world, build `ab30f94`+, `5120x2880` native, 111 fps.
**Screenshot:** [`docs/bugs/inworld-2026-09-22.png`](bugs/inworld-2026-09-22.png)

### Symptoms

The block the player is looking at is outlined by a **giant white/light wireframe quad** covering
a large part of the screen, instead of a thin black line hugging one block. It looks like two nested
wireframe rectangles with a small green/red axis marker near one corner. Depth testing does not hide
it, so it is drawn on top of everything, including the sky.

### Reproduce

1. Metal backend enabled, load a world, look at a nearby block with the crosshair.
2. Compare with the default backend, where the outline is a thin black box around the block.

### Suspected cause (unconfirmed)

The outline goes through a line-rendering pipeline (`lines` / `debug_line` with a wireframe fill or
a line primitive). Likely candidates: the wrong primitive topology for the outline pipeline, a
mis-scaled `ModelViewMat`/`DynamicTransforms` bound to the outline draw, or a `fillMode`/
`lines` mapping bug in `MetalFormat`. Part of the Phase 5 parity work.

### Workaround

Turn the selection outline off in Options (if the pack allows) or ignore it; it does not affect play.

---

## BUG-003 — Entities (squids/fish) and terrain render as flat black silhouettes

> **Not a mystery artifact:** the small black shapes in the sky are **squids and fish** — real
> entities that are being drawn, but without their textures/lighting. They only look unrecognisable
> because entity rendering is unfinished. Filed so the missing entity/world shading is tracked.

**Status:** open, unfixed.
**Severity:** low / cosmetic, but it is the most visible sign that Phase 4/5 shader work is unfinished.
**Seen on:** Metal backend enabled, in-world, build `ab30f94`+, `5120x2880` native.
**Screenshot:** [`docs/bugs/inworld-2026-09-22.png`](bugs/inworld-2026-09-22.png)

### Symptoms

Squids and fish (and other entities) render as **solid black silhouettes**, and terrain renders the
same way — geometry is there, but flat black with no textures or lightmap. GUI, text and the hotbar
render correctly, and the sky colour is right.

### Suspected cause (unconfirmed)

Entity and terrain shaders are not fully bound yet: they are likely sampling an unbound
lightmap/texture or using a shader variant whose inputs are not all mapped. This is the Phase 4
(shaders) / Phase 5 (vanilla parity) work, not a Phase 3 regression.

### Workaround

None. Use the default backend for normal play until Phase 5.
