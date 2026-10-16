# Sodium empty-light terrain specialization — 2026-10-16

The optional native adapter now uses the verified baked-light-only terrain shader when dynamic
lighting is enabled but the published source snapshot is empty. Dynamic lighting stays enabled.
Nonempty snapshots restore the original lit shader before the next region's uniforms are copied.
This removes three `vec3` lighting varyings and unused lighting operations from the empty path;
geometry, index offsets, region layout, textures, fog, baked lighting and alpha rules are unchanged.

The specialization defaults **On**. `-Dmetalmod.sodiumEmptyLights=false` retains the original lit
shader path. Point-light proof and caller-supplied light uniforms keep the lit representation.
This is scoped to the exact verified Sodium 0.9.2 shader pair; no version whitelist is widened.

## Controlled results

Same static underground waypoint 13, M4 Pro, external CB272K, actual 5120×2664, render distance 32,
Sodium 0.9.2 / Default fluid culling, native resolution, Vsync Off / Unlimited. Frame generation,
spatial and temporal reconstruction stay Off. Dynamic-light configuration remains On and every
accepted captured row has zero published sources; the test does not turn lighting off to get a gain.
The normal saved world is copied read-only into each disposable instance.

### Final exact-artifact completed GPU ABBA

All four off/on/on/off runs render fully offscreen with the same completion marker per frame and
at most two pending frames, retired before beginning more rendering. Every counted frame reaches
its GPU completion fence; final drain time is included. These rates cannot be inflated by queuing
unbounded unfinished work. They include the renderer/driver/CPU pipeline and are **not isolated
GPU execution durations or displayed FPS**.

| Run | Completed frames | Completed FPS | Render-thread CPU ms |
|---|---:|---:|---:|
| batch01-native-off | 2938 | 98.25 | 3.01 |
| batch02-native-on | 4162 | 138.27 | 2.78 |
| batch03-native-on | 4136 | 138.32 | 2.80 |
| batch04-native-off | 2905 | 97.12 | 3.09 |

Mean **97.69→138.30 completed FPS**, or
**+41.57%** throughput in this scene. The corresponding
completed-frame interval falls from 10.24 to 7.23 ms.
An earlier candidate ABBA independently measured 97.78→138.27 (+41.40%). The final repeat above
uses the exact shipping artifacts, after binding fixes, default selection and cache warmup changes.

### Ordinary presentation comparison

The earlier candidate's clean presented ABBA measured **59.20→59.28 FPS
(+0.14%)**. Its two on captures were 59.30/59.26 FPS and controls
59.33/59.06. Thus it did not demonstrate higher displayed-game throughput in this session.
The final default-on build also passes a fresh ordinary presented capture (58.83 FPS),
which is an acceptance check, not another controlled comparison. Presenting versus offscreen
results are kept separate. No input latency, power saving or universal gameplay FPS gain is claimed.

## Pipeline and binding safety

- Both representations warm when the normal Sodium pipeline is compiled, so removing a held
  light later does not trigger first-time shader compilation. Pair caches are distinct by source
  and variant. Lighting invalidation, source recompilation, resource reload and close retire the
  baked-only cache too. Failed specialization compilation retains the original lit path.
- The adapter selects before copying each region's 32-byte inline block. Actual variant changes
  invalidate slot bindings, and the region data is then rebound. Re-selecting the identical native
  pipeline preserves inline state.
- Automatic light uniforms track ownership and publication generation. Source appearance refreshes
  an earlier empty binding. Explicit buffer or inline light uniforms take precedence and keep the
  lit pipeline; the collector alone cannot override caller data. BUG-048 records the two reproduced
  candidate failures and final red/green regression pixels.
- There is no geometry reduction or source/material discard. Future RT arena hooks and the vanilla
  foundation remain available. Light-record ABI stays v1 and its byte layouts do not change.

## Packaging

Use matching **core `metalmod-1.0.0.jar` + adapter `metalmod-sodium-0.1.2.jar`** with official Sodium
**0.9.2+mc26.2**. The separate packaging remains; Sodium is not bundled or mandatory for the core.
Update both MetalMod JARs together and replace the previous adapter. Adapter 0.1.2 requires core
custom metadata `metalmod:sodium_adapter_abi=2`. This is a Java adapter API marker, distinct from
light-record ABI v1. A copied negative launch with an older core exits in Fabric pre-launch with
an actionable message, before any game rendering. No Minecraft 26.3 / Sodium 0.9.3 port occurred.

Final core SHA-256: `13cfa90e5624b81507b9fe43d3e06d5a81ee39d88ebbe66bcb2420b1ae80e559`.
Final adapter SHA-256: `8f33e207a8cb9f33f6d91af28d64bb035678cb4b79db1ab4027f2259c53e103a`.

## Verification and limits

All five gates pass on the final source: canonical core/adapter build, **925 native checks**,
**87 static + 9 post** pipelines, **347 pixel checks (204 vanilla + 143 Sodium)** and standalone tests.
Pixel cases cover empty/lit/empty switches, exact baked output, real-source appearance, explicit
buffer/inline light overrides, same-encoder state changes, region offsets, fade/fog, cutout,
translucency, cache reuse/invalidation and resource reload. Baked-only temporal/FG coverage
variants compile for every terrain layer. New live temporal/FG gameplay validation is not claimed;
those features stay Off in these captures, as requested.

Presented/offscreen captures pass the relevant strict analyzers: foreground, no menu/pause,
fixed output, no timed pipeline compilation/census, no upscaling work/failure/recovery. Draw
workloads match within 1%. All counted offscreen submissions complete. Default startup logs confirm
cached solid/cutout/translucent variants with zero backend health errors. A post-capture image
retains the expected cave geometry and lava surfaces; it is not an exhaustive moving-camera stress test.
Normal-instance options/config/mod hashes remain unchanged. Nothing was installed normally.

Evidence: `build/reports/sodium-empty-lights-20261016/` retains final gate logs, failed-before binding
regression log, candidate/final frame CSVs and completion counts, default capture/image, ABI-negative
log, metadata, hashes and `verification.json`.

Reproduce ordinary property ABBA with `--submission-benchmark --comparison-property
metalmod.sodiumEmptyLights` and the fixed Sodium fixture; omit fixed-renderer selection. Add
`--offscreen-comparison --offscreen-property-comparison` for the all-offscreen completed-GPU ABBA.
Analyze those marked captures with `analyze_offscreen.py <copied-game>`; general throughput
analyzers deliberately exclude them. The original present/offscreen diagnostic is still supported.
