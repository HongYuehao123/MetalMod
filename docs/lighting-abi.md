# MetalMod light-record ABI

What Phase 6 publishes to the GPU, in enough detail that a consumer can read it without reading the
Java. Written for the first real consumer, which is the native material and lighting work in Phase 8
and the ray tracing in Phase 9 — not a shaderpack.

**Version: 1.** The version travels in the published data, in `MetalModMeta.y` of the light set. A
consumer must read it and must fall back to its own defaults rather than assume a layout it does not
recognise.

## What is published

Three things, all written once per presented frame from one immutable CPU snapshot:

| Name | Kind | Where |
|---|---|---|
| `MetalModLightSet` | uniform buffer, std140 | bound to the fragment stage of every lit pipeline |
| `MetalModLightGrid` | uniform buffer, std140 | window origin and cell size |
| `MetalModLightData` | RGBA32F texture, 1 row | the cluster table: cell lists and the light records |

The same in-memory layout is described by `LightSnapshot`, `PointLight`, `LightClusterGrid` and
`TerrainLightVariant`, and the offline tests assert the numbers below rather than trusting them.

## `MetalModLightSet` — the flat list

std140. Written in full every publication, so an unread tail is zero rather than stale.

| Offset | Type | Field |
|---:|---|---|
| 0 | `ivec4` | `(count, abiVersion, 0, 0)` — `count ≤ 64`, `abiVersion = 1` |
| 16 | `vec4` | light 0 `positionRadius = (x, y, z, radius)` |
| 32 | `vec4` | light 0 `colorIntensity = (r, g, b, intensity)` |
| 16 + i·32 | | light *i*, same two `vec4`s |

Size **2064 bytes** = 16 + 64 · 32. `radius > 0`; colour channels and `intensity` are in `[0, 1]`.

## `MetalModLightGrid` — the cluster window

std140, 16 bytes: `vec4 MetalModGridBase = (originX, originY, originZ, cellSize)`.

`origin` is the window's lower corner **in the camera-relative frame**, snapped to `cellSize` (16
blocks) and narrowed against the camera last, so it stays exact at world coordinates of order 3 × 10⁷.
The window spans 128 blocks centred on the camera, 8 cells per axis.

A fragment's cell is `clamp(floor((position − origin) / cellSize), 0, 7)` per axis, flattened as
`(z · 8 + y) · 8 + x`.

## `MetalModLightData` — the cluster table

One row of RGBA32F, **8849 texels** (141 584 bytes). Every value is a float; the integers are small and
exact in `float`, and a texel *coordinate* is dynamic-indexable where a `vec4` *component* is not.

| Texels | Content |
|---|---|
| 0 | `(lightCount, clustersPerAxis, entriesPerCell, clusterSize)`. `entriesPerCell` is **negative** when `lightCount == 0` — the shader's "nothing published" marker. |
| `1 + cell·17` | that cell's `(entryCount, 0, 0, 0)` |
| `1 + cell·17 + 1 … + 16` | one light record index per texel, in `.x` |
| `8705 + record·2` | the record's `positionRadius`, identical to the uniform set |
| `8705 + record·2 + 1` | the record's `colorIntensity` |

`record` indexes the *same* order as the flat list: contribution order, strongest first. The record
region holds 72 records, which is the light-set capacity plus slack.

The table is rebuilt from scratch each publication, so no texel is left over from an earlier frame.

## What a consumer may rely on

- **Bounds.** `count ≤ 64`; every published index is in `[0, count − 1]`; a cell's `entryCount` is
  `≤ 16` and never exceeds the entries written after it; unused entries are `0`. These are clamped at
  encode time, so a table that ever disagreed with the records still cannot address outside them. A
  consumer does not need to re-check them, though it may.
- **Frame.** One publication per *presented* frame, and the buffer is fenced before its slot is reused.
  A consumer reading during the frame sees that frame's set. Nothing is published while the feature is
  off, so the last set stands until it is cleared.
- **Frame of reference.** All positions are camera-relative world space, in the same float frame the
  vertex stages build — terrain's `pos`, entity's and item's and particle's `Position`, and the block
  pair's `Position + ModelOffset`. A consumer must not mix in an absolute world coordinate without
  subtracting the same camera origin.
- **Colour.** Linear RGB with an artistic `intensity`, calibrated against a reference torch. This is
  vanilla working-space composition, **not** linear-light/HDR. Phase 8 changes that, and this document
  is where that change must be recorded.

## Reserved, and deliberately not advertised

- **Light type.** Point only. Type and flags fields are reserved so spot and area evaluators can be
  added without a layout break, but no evaluator advertises them and a consumer must not assume one.
- **Shadow flags.** A `castsShadow` request flag is reserved. Nothing evaluates it: lights are
  unshadowed, and sources buried in or sealed by opaque blocks are dropped rather than shadowed.
  Occlusion belongs to Phase 8B, which shadows this contribution with a ray visibility result.
- **Environment.** `EnvironmentRecord` (version 1) exists on the CPU — dimension, time, weather, sky
  darkening — and is **not** in a GPU block yet. Publishing it is Phase 8 work.
- **Ownership.** There is no external-ownership mode. MetalMod always evaluates its own contribution;
  there is no switch that lets a consumer suppress that and light the scene itself.

## How to verify a consumer

`tools/render_check/run.sh` drives every family through the real pipelines and asserts the published
layout, the bounds above, the zero-light path being pixel-identical to vanilla, and the runtime toggle.
`net.metalmod.StandaloneTestRunner` asserts the record offsets, the ABI version, the capacity, the
per-cell bound and the one-row texture limit. A new consumer should extend both rather than add a
third place that believes its own copy of the layout.
