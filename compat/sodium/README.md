# Optional Sodium Metal adapter

Experimental, version-scoped integration for **Fabric Sodium 0.9.2+mc26.2**, Minecraft 26.2,
and MetalMod 1.0.0. The user restored separate packaging on 2026-10-05 after trying a unified JAR.

Use the core MetalMod JAR alone for vanilla rendering. For Sodium, install **all three JARs**:

1. `metalmod-1.0.0.jar` — core native Metal backend.
2. `metalmod-sodium-0.1.1.jar` — optional compatibility adapter (including the fatal version gate).
3. Official `sodium-fabric-0.9.2+mc26.2.jar` — upstream Sodium itself.

No Sodium classes are bundled into either MetalMod artifact. The core has no adapter classes,
Sodium-targeted mixin configuration or Sodium runtime/build dependency. The adapter's Fabric
metadata requires the supported version; its pre-launch guard retains exact version-string
checking (BUG-047). Remove both Sodium and the adapter to return to vanilla rendering. Restart
after changing installed mods. Do not use the previous unified core with this separate adapter.

Core-only build:

```sh
./scripts/build_mod.sh
```

Build the core and optional adapter against the actual tested release JAR:

```sh
METALMOD_SODIUM_JAR=/path/to/sodium-fabric-0.9.2+mc26.2.jar ./scripts/build_mod.sh
```

Outputs: `build/libs/metalmod-1.0.0.jar` and `build/libs/metalmod-sodium-0.1.1.jar`.
Nothing is installed into the normal Minecraft instance automatically.

Official Sodium **0.9.3-alpha.1** is for Minecraft **26.3** (`minecraft: 26.3.x`), not 26.2.
Supporting it requires the corresponding Minecraft/API port and verification, rather than a
version whitelist edit. Release/artifact metadata was checked on 2026-10-05 and retained under
`build/reports/sodium-separate-20261005/`.

The adapter intercepts Sodium's draw-context and batch factories only for an actual `MetalDevice`.
OpenGL/Vulkan devices keep Sodium's own contexts. It preserves Sodium's compact 20-byte meshes,
region arenas, culling and batch order, and issues direct indexed Metal draws. It does not
advertise unsupported indirect-draw features or introduce a Vulkan translation layer.

Sodium region uniforms use a copied 32-byte std140 block named `MetalModSodiumRegion`:

| Offset | Type | Value |
|---|---|---|
| 0, 4, 8 | float | Camera-relative region origin xyz |
| 12 | signed int | Milliseconds since region creation, as in Sodium |
| 16 | unsigned int | Region ID indexing section fade times |
| 20–31 | padding | Zero |

Each update uses Metal `setVertexBytes`/`setFragmentBytes`; Metal owns the encoded copy before
the reusable Java buffer changes. The native API rejects invalid sizes/slots and invalidates
cached buffer identities so a later ordinary buffer binding remains correct. Each pipeline
change requires a fresh inline binding. Draw offsets are **index elements**, not byte addresses;
base vertex, instance count and translucent ordering retain their original meanings.

Recommended quality profile: **Quality → Fluid Culling → Default**. The tested Sodium release
otherwise defaults to Optimized, which can omit visible cave lava surfaces at shallow angles.
Default is persisted as `quality.hidden_fluid_culling=false` in Sodium's own configuration.
A controlled run restored the observed surfaces while retaining CPU headroom. The adapter does
not automatically overwrite the user's Sodium settings. See BUG-046 and the integration evidence.

The adapter checks the exact tested shader pair before replacing GL scalar/Vulkan push uniforms.
Custom Sodium terrain shader replacements fail visibly until explicitly adapted. Solid, cutout
and translucent shaders retain fog, fade, texture filtering, vertex tint, alpha and baked light.
MetalMod point, flat dynamic and clustered dynamic lighting use the existing light ABI.

MetalFX, temporal inputs, history, native hand/HUD composition and frame generation remain owned
by MetalMod. Future ray tracing still requires explicit Sodium arena/rebuild/material lifecycle
hooks; this adapter does not implement them or replace the core vanilla RT foundation.

Verification and measured performance: [integration evidence](../../docs/performance/sodium-compatibility.md).
Offline Sodium pixels can be rerun without launching the game:

```sh
JAVA_TOOL_OPTIONS='-Dmetalmod.sodiumJar=/path/to/sodium-fabric-0.9.2+mc26.2.jar' ./tools/render_check/run.sh
```
