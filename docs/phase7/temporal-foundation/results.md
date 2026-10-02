# Temporal foundation verification — 2026-10-02

Spatial baseline: `92f90bf`. Prototype changes are the following implementation increment;
this record does not mark 7B accepted. Built JAR SHA-256:
`2dd30792d53150baf3e398520f802a387f4ff29d5893038210bfce428863edd2`.

Hardware: Apple M4 Pro, macOS 27.0.1 (26A434), arm64, Apple clang 21.0.0.
Instance: `MetalMod_Test_26.2`, real Minecraft client JAR, JDK 26 with `--release 22` compilation.
GPU tests ran with host access; sandbox-only attempts without a Metal device were not passes.

| Gate | Command | Result |
|---|---|---|
| Build | `./scripts/build_mod.sh` | SUCCESS; native temporal source, Java and tests compiled, mod packaged |
| Native | `MTL_DEBUG_LAYER=1 ./native/build/metalmod_smoke` | ALL CHECKS PASSED; real temporal encoding active, Metal API validation enabled |
| Shaders | `./tools/shader_inventory/run.sh` | static 87/87, post 9/9, no pipeline diagnostics |
| Pixels | `MTL_DEBUG_LAYER=1 ./tools/render_check/run.sh` | RENDER CHECK PASSED, 194 assertions |
| Standalone | `net.metalmod.StandaloneTestRunner`, canonical instance classpath/native access | ALL TESTS PASSED SUCCESSFULLY; real temporal FFI creation and usage query active |

Full GPU/test logs: [smoke](smoke.txt), [inventory](inventory.txt), [render](render.txt),
[standalone](standalone.txt). Existing LWJGL Unsafe deprecation messages are present in the
Java GPU tools; there are no shader compilation diagnostics or Metal validation errors.

The temporal smoke uses three independent generations and fifteen real encodes. It checks flat
linear colour with nonzero jitter, forward/reversed depth, history reset after an abrupt colour
change, asymmetric quadrant replacement with full reactive rejection and nonzero motion, invalid
dimensions/aspect/jitter/output/queue rejection, and release before the last submission commits.
Panama tests check sampling math and create/release/usage queries; their encode call exercises the
null rejection ABI, while native smoke exercises valid encoding.

No game was launched or test-instance JAR installed for this increment. No temporal performance
claim is made. Gameplay scene colour conversion, jitter projection, object motion, reactive-mask
production, coordinator reset triggers and candidate comparisons remain pending. See the
[contract and next producer work](../temporal-contract.md).
