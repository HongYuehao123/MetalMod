# Frame-generation foundation verification — 2026-10-03

Baseline: `f6586d6`; checkout was clean before this increment. Changes remain uncommitted.
Hardware: Apple M4 Pro, macOS 27.0.1, installed Xcode SDK headers with macOS 26 interpolation API.
Build instance: normal `MetalMod_Test_26.2`, real client JAR, JDK 26 / Java release 22.

| Gate | Result |
|---|---|
| `./scripts/build_mod.sh` | SUCCESS, native + Java + packaged JAR + standalone test compilation |
| `MTL_DEBUG_LAYER=1 ./native/build/metalmod_smoke` | ALL CHECKS PASSED, including 27 real interpolation encodes across three generations |
| `./tools/shader_inventory/run.sh` | static 87/87, post 9/9, no diagnostics |
| `MTL_DEBUG_LAYER=1 ./tools/render_check/run.sh` | RENDER CHECK PASSED, 203 PASS lines |
| `net.metalmod.StandaloneTestRunner` with native access and real instance classpath | ALL TESTS PASSED SUCCESSFULLY, including new optional interpolation FFI |

Native smoke and standalone require host GPU access: sandbox smoke initially failed because no
Metal device was visible. That run is not counted as passing evidence. Host runs passed.
Shader/render gates passed after initial implementation; subsequent edits affected only isolated
interpolation warmup and its native tests. Final build/smoke/standalone were repeated after those
edits. No gameplay shader or presentation code changed.

Three interpolator generations use native 64×48, reduced 32×24 -> 64×48 and moving-object
128×96 -> 256×192 inputs. Each performs nine encodes including reset and release with GPU work
pending. Normal/reversed depth are covered. Eligible static outputs retain linear RGB and quadrant
orientation within 0.04. A moving rectangle advances eight output pixels per real frame: generated
centroids are 83.500, 115.500 and 123.500, exactly matching the expected halfway poses. A repeated
current real frame would be four pixels away and fail the two-pixel tolerance.

The sequence caught real warmup behavior: an initial pair and the first two pairs after an explicit
reset could repeat current colour. The final wrapper conservatively suppresses the reset encode
and the following two pairs. Suppressed outputs are never offered as display eligible. This is a
measured guard, not a guarantee that arbitrary content always interpolates correctly.

Full local logs: `build/reports/frame-generation-foundation/{build,smoke,shaders,render,standalone}.log`.
Built JAR SHA-256: `e5f1a85abc2786cf419c3bb902d74e3df318d7c2910eaf12fabc9429203bcee9`.
No install, game launch, config change or Git commit was performed.

This verifies the offline foundation only. UI compositing, gameplay motion/snapshots, display-link
scheduler, real generated delivery, dropped/deadline behavior, input latency and performance remain
pending; see [contract and next increment](contract.md). Phase 7C is started, not accepted.

API references: [Apple interpolation descriptor](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatordescriptor?language=objc)
and the installed `MetalFX.framework/Headers/MTLFXFrameInterpolator.h`.
