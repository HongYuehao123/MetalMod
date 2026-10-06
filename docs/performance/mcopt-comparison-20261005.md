# mcopt release comparison — 2026-10-05

The gap deserves investigation. The public post is not a matched benchmark against MetalMod,
but mcopt also contains substantial renderer techniques our current adapter does not implement.
The earlier binding-cache/upload improvements do not reproduce those larger paths.

## What the post actually shows

The [linked post](https://x.com/itsnoahd/status/2106818802000240891) names an M4 Max and roughly
1,000 FPS at 5K/120 Hz. The browser-accessible footage labels **5120×2880, render distance 16,
M4 Max**, spinning above snowy mountains; an inspected frame shows **173 FPS Sodium stock versus
1,040 FPS Sodium + mcopt alpha**. The video is an author's comparison, not an independently
reproduced capture. Complete settings, world/camera data and active flags are not available from
that footage. The displayed 120-Hz capability cannot show 1,000 distinct full frames per second.

The [author's follow-up](https://x.com/itsnoahd/status/2106827691315867730) says upscaling is off
and frame generation unused. The linked release defaults agree: `MetalFx.SCALE=1` and
`FrameGen.MODE=false`. There is no evidence here that generated frames explain the result.
The stock overlay uses Minecraft's ordinary `getFps()` rather than a generated-frame counter.

The release is **v0.2.0-alpha.1**, commit `7fdeeea5845f22081308c17b4aca17eae2b6930e`, targeting
Minecraft **26.3 / Sodium 0.9.3 alpha**. Our tested adapter targets **26.2 / Sodium 0.9.2**.
The earlier mcopt review used `b089444...`; this report rechecks the exact release linked in X.
Its [README](https://github.com/noahdunnagan/mcopt/blob/7fdeeea5845f22081308c17b4aca17eae2b6930e/README.md)
also publishes separate **1920×1080, render-distance-16** results on other Macs. Those numbers
must not be conflated with the post's 5K M4 Max footage.

Our recent scene is a fixed dense underground waypoint at **render distance 32**, about
16,000–16,800 total draws/frame, on an **M4 Pro with 20 GPU cores**. Output was 5120×2664 for
the earlier external capture and 3600×2204 for the matched display test. Geometry visibility,
hardware, versions and aspect/resolution differ. The post has more pixels than our latest
capture, so resolution alone is not an adequate explanation for its much higher counter.

Our copied configuration also keeps dynamic lighting enabled. Even with no active sources,
the Sodium variant adds position/tint/baked-light varyings. mcopt's inspected native terrain
shader uses baked lighting and lean outputs. This is another feature mismatch, not a measured
attribution of the performance gap. A vanilla-lighting comparison would need an explicit control.

## Material renderer differences

| Area | mcopt linked release | Current MetalMod |
|---|---|---|
| Vsync-Off presentation | Renders full frames, selectively acquires/copies/presents near refresh | Acquires a drawable and presents every ordinary frame |
| Opaque/cutout terrain | Optional split-pass Hi-Z quad culling, compact GPU survivor lists, indirect instanced vertex pulling | Sodium CPU meshing/culling retained; section/face ranges become individual direct indexed draws |
| Terrain shaders | Native MSL with half colour/fog outputs and a pulled twin | Verified translated Sodium shaders plus optional lighting adaptation |
| Render passes | Continues compatible passes and chooses discard/store actions | Batches command buffers, but closes each render encoder at pass close |
| Other CPU work | Default hardware-tier profile includes meshing/render-list/cache work and thread QoS | Some binding/FFI/allocation optimizations; QoS remains an unproven opt-in |

[MetalSurface](https://github.com/noahdunnagan/mcopt/blob/7fdeeea5845f22081308c17b4aca17eae2b6930e/metal/src/main/java/mcopt/metal/MetalSurface.java)
defaults pacing on when Vsync is off. It defers drawable acquisition to the finished-frame blit
and skips that blit when `Native.pace()` declines a present. Rendering/submission still occur.
The [native pacer](https://github.com/noahdunnagan/mcopt/blob/7fdeeea5845f22081308c17b4aca17eae2b6930e/metal/src/main/native/mcmetal.m#L987)
uses a display-link refresh estimate and smoothed GPU latency. This avoids asking WindowServer
to present every rendered frame and can remove drawable acquisition as a per-frame limiter.
It is a legitimate throughput strategy, but rendered FPS and delivered updates must be measured
separately. Its display link follows the main display, so that selection should not be copied
blindly into our multi-display/FG design. This behavior is a major difference beyond simply
setting Vsync Off; its exact contribution to the video cannot be quantified from source alone.

[MetalTerrain](https://github.com/noahdunnagan/mcopt/blob/7fdeeea5845f22081308c17b4aca17eae2b6930e/metal/src/main/java/mcopt/metal/MetalTerrain.java)
draws a predicted visible set using current geometry/camera, builds hierarchical depth from
that frame, tests remaining quad bounds, compacts survivors while preserving order, and issues
a few indirect instanced draws whose vertex shader fetches vertices from Sodium arenas.
The design avoids blindly treating previous-frame depth as current visibility. Its conservatism
and complete visual parity are implementation claims we have not independently validated.
Translucent terrain is not handled by this opaque/cutout culling path.

Importantly, [MetalOccPick](https://github.com/noahdunnagan/mcopt/blob/7fdeeea5845f22081308c17b4aca17eae2b6930e/metal/src/main/java/mcopt/metal/MetalOccPick.java)
defaults to alternating trials and choosing the faster path. Its source describes a case where
culling helped at 1080p but lost at higher resolution. We cannot assume the video used culling
throughout or that enabling it will always improve our 5K scene.

The [native vertex shader](https://github.com/noahdunnagan/mcopt/blob/7fdeeea5845f22081308c17b4aca17eae2b6930e/metal/src/main/resources/mcopt/metal/msl/sodium_terrain.vs.metal)
reduces declared output payload from 52 to 36 bytes, including half-precision colour and fog.
It changes fade handling too. The benefit targets tile-GPU vertex/parameter bandwidth, not just
Java/native call count. Precision/fade behavior, dynamic lights and temporal shader hooks would
need explicit parity tests in our implementation.

## What matters for the next work

1. Establish a native-only, Vsync-Off external-display baseline. Add render-distance-16 surface
   routes as well as the existing distance-32 underground stress case. A true mcopt/MetalMod
   comparison requires the same world/camera/settings/hardware and compatible client version.
2. Decouple ordinary offscreen rendering from display refresh with bounded in-flight work and
   selective presentation. Report real rendered FPS and presentation cadence separately. Preserve
   resource, upload, light/history ring retirement on every rendered frame, including skipped
   presents; do not merely bypass `present()` or remove waits. Keep the existing FG presenter intact.
3. Introduce a native terrain batch/arena contract, then vertex pulling and conservative current-frame
   occlusion with real indirect draws. Advertise indirect capabilities only once implemented.
   Preserve full source geometry/material identity for future RT; raster survivors are not the
   acceleration-structure scene. Validate fast motion, edits, translucency, lighting and temporal jitter.
4. Reduce shader output bandwidth and render-pass stores under measured controls. These complement
   batching and GPU terrain work; a blanket private-texture switch already regressed our fixture.

No source comparison establishes that one of these will reproduce 1,000 FPS here. Conversely,
the evidence does not justify explaining away the entire gap as a 60-Hz monitor limitation.

## Updated benchmark policy

`run_manual.py` now defaults to **external display, Vsync Off, native-only captures**, with
spatial/temporal upscaling and frame generation disabled in the disposable configuration.
`--compare-spatial` explicitly restores feature experiments; none run automatically in this baseline.
`--render-distance` records the requested distance (32 retains the existing stress fixture;
16 is explicit for a closer workload category). Actual pixel dimensions remain mandatory evidence.

The real 26.2 client calls its limiter only for values below 260: option 260 is Minecraft's
**Unlimited sentinel**, not a 260-FPS cap. This was already used in the previous captures, which
also already had Vsync Off. The policy change removes automatic spatial captures, not an old
Vsync cap. Upscaling is checked absent in native-only capture analysis. No normal installation,
world or configuration is modified, and no new production renderer optimization is enabled here.

## Verification of the revised baseline

The final copied-instance run completes exactly one accepted 30-second native capture on CB272K,
5120×2664, distance 32, IMMEDIATE. It measures **59.93 rendered FPS, 3.59 ms render-thread CPU,
16,843 draws/frame, p95 21.24 ms and p99 25.68 ms**. Positive presentation timestamps yield
59.94/s, with zero unavailable timestamps in this run (countdown/export tail still included).
All SR requests/encodes are zero; copied FG/SR/temporal settings are false. This is a single
policy validation, not an optimization A/B or improvement claim.

An initial policy check stopped before measurement because neither key nor main AppKit window
was available at startup. The test probe now falls back to the process window containing the
Metal layer, retains a weak reference after identification, and completes the fresh retry.
The failed attempt is archived as interrupted and contributes no performance evidence.

The real-client Java add-on and macOS display probe compile and run. Native-only analysis passes;
the historical matched native/spatial display analysis also still passes. The unchanged core and
adapter hashes retain the previously passing production five-gate validation; production gates
are not rerun for these test-only changes. Raw evidence and verification are retained in
`build/reports/mcopt-comparison-20261005/`.
