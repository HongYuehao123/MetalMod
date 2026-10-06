# One-JAR optional Sodium selection — 2026-10-05

**Historical:** superseded later the same day when the user requested separate core/adapter
packaging again. Use the current build and `compat/sodium/README.md`; the artifact hash below
records the former unified implementation.

The user requested one MetalMod JAR that detects installed Sodium and chooses the compatible path.
The canonical artifact is now `build/libs/metalmod-1.0.0.jar`, containing only MetalMod's integration
code in addition to the existing core and native library. Sodium itself is not bundled and is not a
mandatory runtime dependency. This changes packaging/startup selection, not rendering algorithms.

## Startup behavior

| Installed mods | Result |
|---|---|
| MetalMod, no Sodium | Vanilla rendering; no Sodium-targeted mixins registered |
| MetalMod + official Fabric Sodium 0.9.2+mc26.2 | Native adapter factories enabled automatically for `MetalDevice` |
| MetalMod + another Sodium version | Fatal Fabric pre-launch error with the supported release and removal instructions |
| MetalMod + old `metalmod-sodium-0.1.0.jar` | Fabric rejects the superseded adapter before mixin initialization |

The plugin and pre-launch gate reference Fabric APIs only. Sodium-dependent subclasses and
factory mixins are loaded only on the supported path. Existing GL/Vulkan devices retain Sodium's
own contexts. The known shader fingerprint and region ABI guards are preserved.
Installed-mod detection happens at startup; restart after adding/removing Sodium.

Upgrade by using the new single MetalMod JAR and removing the old separate adapter JAR.
Sodium remains an optional separate mod. Use **Fluid Culling = Default** for the demonstrated cave
lava parity; user options are not overwritten. Broader Sodium quality/stress and frame tails keep
this integration experimental. MetalFX and future native ray tracing ownership remain unchanged.

## Build

Use `./scripts/build_mod.sh`. It compiles the adapter against the actual official Sodium release;
`METALMOD_SODIUM_JAR` overrides the build dependency, whose current default is the verified
workspace artifact under `build/reports/sodium-compatibility/`. Only adapter classes/config are
copied into the core output. Build metadata validates the release ID/version. No Sodium classes
or separate adapter mod metadata are packaged. No downloads or normal-instance writes occur.

## Verification

Final core SHA-256: `ed650cf732aff82553fd8f3d4bfef22fb8f265c7f0b13fe6c40f445d704f7423`.

All five offline gates pass on the final code: canonical build, **921 native checks**, **87 static
+ 9 post pipelines**, **204 vanilla + 66 Sodium pixel checks**, and all standalone tests.
Logs and the structured record are in `build/reports/sodium-autodetect-20261005/`.

Two fresh copied-world launches use this exact same JAR, external screen 5120×2664, RD32,
fixed saved waypoint 13, Vsync Off/Unlimited, all frame generation and upscaling Off:

| Path | Clean native captured frames | Outcome |
|---|---:|---|
| No Sodium | 1750 | Exit 0; vanilla terrain and frame lifecycle verified |
| Official Sodium only (no separate adapter) | 1793 | Exit 0; native adapter terrain and frame lifecycle verified |

Strict analyzers accept both captures: focused, unpaused, no open menu, no resize, no timed shader
compilation/census and native effective mode 0 with no SR requests/encodes/recovery. Startup telemetry
reports zero resource/pipeline failures, unbound bindings, missing attributes, collisions and
binding-kind mismatches. These are installation/selection checks, not a new ABBA performance claim.
Earlier temporal/FG validations establish the unchanged draw adapter's scoped compatibility; these
features stay Off in the new live checks.

Negative checks use copied instances:

- A synthetic fixture changes only the tested Sodium JAR's declared version to `0.9.3+mc26.2`.
  It is not the real 0.9.3 release. The final guard exits 1 in Fabric pre-launch, with the actionable
  version message and no render-thread/device/world rendering. `fabric.noGui=true` is set only for
  negative automation to avoid waiting on Fabric's error dialog.
- The actual prior separate adapter JAR plus official Sodium exits 1 through Fabric's incompatible-
  mod metadata, identifying `metalmod-sodium 0.1.0` as the conflicting mod.
- The first plugin-only version check was inadequate: Mixin swallowed its `onLoad` exception and
  Sodium subsequently hit a GL cast. It is archived as failed evidence, not a passing guard. The
  fatal check was moved to `PreLaunchEntrypoint` and both live cases rerun afterward. See BUG-047.

Normal-instance options/config/mod hashes match both the fresh pre-check snapshot and the earlier
original manifest. Nothing was installed into the normal instance. The packaged artifact is ready
for review/use; the normal instance retains its previous JAR.

See [installation and region ABI](../../compat/sodium/README.md) and
[prior compatibility/performance evidence](sodium-compatibility.md).
