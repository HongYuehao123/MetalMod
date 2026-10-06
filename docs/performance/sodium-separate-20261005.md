# Separate Sodium adapter restored — 2026-10-05

The user requested restoring the previous separate packaging and adding Sodium 0.9.3 support.
Separate packaging is restored and verified. **0.9.3 support is not added yet**: the available
upstream version is 0.9.3-alpha.1 for Minecraft 26.3, while the project and local test instance
use 26.2. The user explicitly declined the Minecraft 26.3 port and deferred 0.9.3 adaptation.
Keep Minecraft 26.2 and Sodium 0.9.2+mc26.2 with separate adapter packaging.

## Current artifacts

- Core: `build/libs/metalmod-1.0.0.jar` (`a4c6df1ad5790360b78d0e0036633cd694ddd94cbe6e1d6845740bab10c6e89c`).
- Optional adapter: `build/libs/metalmod-sodium-0.1.1.jar` (`64bc78cdcbdafb535fd32edc18052c00f2299127e280002b19387dd59b7cffaf`).
- Runtime with Sodium: core + adapter + official Sodium 0.9.2+mc26.2. Runtime without Sodium: core alone.

The core has no adapter classes, optional mixin config, pre-launch gate, Sodium dependency or
mandatory Sodium build dependency. `METALMOD_SODIUM_JAR` opts into building the separate adapter.
Sodium itself is not bundled. The adapter retains the fatal exact-version pre-launch guard from
BUG-047 and selects native draw factories only for a MetalDevice. The adapter version is bumped
from 0.1.0 to 0.1.1 to distinguish the retained guard from the older standalone artifact.
The previous unified-JAR results are historical; do not install that core with this adapter.
Native rendering optimizations, MetalFX and future ray tracing ownership are preserved.

## Verification

Canonical core+adapter build, native smoke (**921** checks), shader inventory (**87+9**), pixel
check (**204 vanilla + 66 Sodium**) and standalone tests all pass. Both fresh copied-world
native-only captures pass strict analysis: **1771 vanilla / 1793 Sodium frames**, exit 0, external
5120×2664, RD32, fixed waypoint 13, Vsync Off/Unlimited, FG/spatial/temporal reconstruction Off.
Startup telemetry reports zero binding, attribute, collision, resource and pipeline failures.
Core/adapter package contents are inspected to confirm the separation. Normal instance
options/config/mod hashes remain unchanged; nothing was installed normally.

## Official 0.9.3 review

[Official release](https://github.com/CaffeineMC/sodium/releases/tag/mc26.3-0.9.3-alpha.1)
and the official project's Modrinth version list identify **0.9.3-alpha.1 for Fabric 26.3**,
version ID `v4PSXean`. The actual artifact was downloaded and its published SHA-512 verified.
Its own `fabric.mod.json` declares `minecraft: 26.3.x` and Fabric Loader >=0.16.0.
The checked 26.2 Fabric release list has 0.9.2 as its newest release.

API dumps of the actual artifacts show additional changes:

- `DrawContext` switches RenderPass/RenderPipeline types from Blaze3D to RenderPearl.
- It drops `updateData(RenderRegion, CameraTransform)`, camera-translation helper and push-range constant.
- `MultiDrawBatch` adds `prepare(DrawContext)`.

Consequently, accepting this version by widening a guard would not implement compatibility.
A corresponding Minecraft 26.3 backend/API and adapter port is needed, followed by real-client
compilation, shader/pixel/lighting checks and copied-world runtime validation. No 26.3 test instance
is presently installed. No client-version upgrade, world upgrade or Sodium fork was performed.

A negative copied-instance launch with the **actual** official 0.9.3 artifact exits 1 at Fabric
mod resolution, identifying both the Minecraft 26.3 requirement and current adapter's 0.9.2
requirement; it never enters world rendering. The error GUI is disabled only for this automated
negative check. This rejection is not claimed as 0.9.3 support.

Evidence: `build/reports/sodium-separate-20261005/verification.json`, gate logs, both live captures,
`actual-093-rejection/`, release JSON, actual JAR metadata, and both versions' API dumps.
See [installation](../../compat/sodium/README.md).
