# Lighting and Sodium temporal fixes — 2026-10-16

Follow-up: the [native footprint correction](bug051-temporal-footprints-20261016.md) completes
BUG-051 and passes the unchanged image gate. The results below describe the earlier artifact.

Follow-up to the [whole-mod audit](whole-mod-test-20261016.md), based on production commit
`2ca817453f23095bc540119d0b13231537abbfe7` plus the working changes. **BUG-049 and BUG-050 are fixed.
BUG-051 has a verified projection correction but remains open because residual static-image
movement exceeds the unchanged 0.25-output-pixel acceptance limit.** No gameplay FPS improvement
is claimed. The unsuccessful native history experiment was reverted; native production sources
and defaults are unchanged.

Evidence is under [`build/reports/bugfix-049-050-051-20261016/`](../../build/reports/bugfix-049-050-051-20261016/).
Use core `metalmod-1.0.0.jar` with optional adapter **0.1.3** and official Sodium **0.9.2+mc26.2**.
The adapter now requires core metadata API marker **3** for `TemporalProjectionAccess`; mismatched
marker 2 is rejected in pre-launch with an actionable message. The Phase 6 light-record ABI remains
v1. No normal installation is performed.

Testing uses the same Apple M4 Pro (14 CPU/20 GPU cores), 24 GB, macOS 27.0.1, Java 26 and
Minecraft 26.2 host as the audit, with Metal API validation. Static registration measures one
512×288 opaque foliage ROI at 3842×2162 output, strength 50, RD8, over all 16 jitter samples.
It measures coherent movement in that ROI, not every kind of shimmer or motion quality.

## BUG-049: redundant publication removed

`MetalDevice.publishLightFrame` already encodes list/grid/texture together when clustering is on.
Its old representation-switch invalidation caused list(false) → grid(true) → data bindings to encode
the same immutable source generation again. Removing that invalidation reuses the data already
built; a changed source generation and a new fenced ring slot still trigger publication.

The real automatic clustered draw changes from **6 logical uploads / 2 grid builds** to **3 / 1**.
Exact-delta regressions cover 0/1/16/64 sources, repeated bindings across new passes, source changes
within a frame, source removal with restored baked pixels, and ring rotation/reuse. These counts
establish eliminated work, not a measured gameplay frame-rate gain.

## BUG-050: work attribution reset and consumed

`LightCollector.suppress()` resets extraction/query/index time and examined/allocated counts for
every suppressed boundary, including an already-empty source set. Nonempty removal publishes once;
continued empty suppression does not change the GPU publication generation. `clear()` resets all
work fields. The present boundary snapshots the sample into the existing immutable F3/capture stats,
then consumes it so a later frame without extraction cannot repeat it.

Standalone tests seed stale work for empty/nonempty suppression, verify generation changes, clear,
and fresh extraction after re-enabling. Real-device tests verify present snapshots once, then zero
work on the following frame with no extraction. Expanded gameplay stages cover nonempty→spectator,
continued empty spectator, active empty, disabled empty, re-enabled empty, empty→spectator, and
restored active sources. They check settled frames and write `lighting-transitions.csv` using the
same column mapping as performance captures/F3. The corrected fixture also requests the real UI's
renderer rebuild, checks disabled uploads/builds are zero and requires no repeated empty-source
generations. Suppressed flat rendering may still bind one empty list per presented ring slot;
zero extraction work does not claim every suppressed renderer performs zero GPU uploads.
Final results appear below.

## BUG-051: missing projection jitter corrected; image acceptance still open

Actual Sodium 0.9.2 bytecode shows `GameRendererMixin.sodium$setProjection` copying the unjittered
matrix before the core's vanilla projection-buffer redirect applies jitter. `LevelRendererMixin`
then reads that separate cached matrix into `ChunkRenderMatrices` and terrain globals. The adapter
now intercepts that getter after Sodium's mixin and applies the same active scene sample through
`TemporalProjectionAccess`. The unjittered source, camera/motion/culling inputs and native hand/UI
remain intact. The bridge bypasses outside the scene interval and on non-Metal devices.

Unit tests cover all 16 phases, identical vanilla/optional-renderer matrices, no source mutation,
sequence advancement, and bypass. Copied-world logs confirm `TemporalProof.rendererProjection` is
active. Raw scene-ROI regression responds approximately one scene pixel per unit of scene jitter;
stationary motion has maximum absolute magnitude **0.000103 scene pixels**, and raster transient
coverage is zero in the measured opaque ROI. These inputs confirm the large missing-jitter error
was corrected, rather than merely changing a diagnostic counter.

| Retained sequence | X range, output px | Y range, output px | Image gate |
|---|---:|---:|---|
| Original whole-mod Sodium | 1.4100 | 1.4447 | FAIL |
| Corrected projection, full runtime run | 0.3410 | 0.3325 | FAIL |
| Corrected projection, short repeated run | 0.2717 | 0.2788 | FAIL |
| Filtered static appearance experiment | 0.3100 | 0.2943 | FAIL; reverted |
| Final corrected artifact, full suite static sequence | 0.2597 | 0.2666 | FAIL |

The short diagnostic suggests some edge history is rejected despite stationary geometry, but it
does not prove the cause of all remaining movement. Filtering static appearance before comparison
failed to meet the image gate, so it is **not shipped**. No rejection threshold, MetalFX jitter sign,
sampling amplitude or image-analysis threshold was changed. Remaining work needs an alias/edge
history investigation with matched native/spatial controls and motion/appearance-change checks;
this is not a verified fix for the older vanilla foliage report BUG-036.

The gameplay launcher now automatically runs the calibrated image analyzer. Runtime PASS can no
longer cause launcher success when the independent image gate fails. `--static-check` supplies a
short native/resize/static/quality regression, and `--dump-temporal-inputs` retains ROI motion,
reactivity, depth, linear colour and coverage binary data for diagnosis. The filtered experiment's
failed result and the corrected-projection failures are retained separately.

## Final verification

All five offline gates pass on the final artifacts, including lighting-enabled inventory:
**925 native checks, 87 static + 9 post shaders, 368 combined core/Sodium render assertions,
and all standalone tests.** The final combined render gate contains 225 core + 143 Sodium checks.
Production JAR/add-on separation and embedded native identity are verified.

Gate logs: [`verification-20261016-205214-852306`](../../build/reports/verification-20261016-205214-852306/result.txt).
Additional Sodium pixels: [`sodium-render-runner.log`](../../build/reports/bugfix-049-050-051-20261016/sodium-render-runner.log).
Guard negative test: [`abi2-negative-result.json`](../../build/reports/bugfix-049-050-051-20261016/abi2-negative-result.json).
Final vanilla lifecycle run: **476 checks / 20,085 frames PASS**, calibrated image gate
**0.2013 X / 0.1673 Y PASS**. That run preceded the test-fixture renderer-rebuild correction;
its suppression/extraction assertions pass, but the additional short vanilla lighting run is
required for the corrected disabled-pipeline/no-publication assertions. Sodium uses the corrected
fixture. Both use the exact production artifact hashes below.

Final Sodium lifecycle: **489 checks / 20,074 frames PASS**. Its independent static image gate
is **0.2597 X / 0.2666 Y FAIL**; `launcher-result.json` records runtime pass, image exit 1 and
overall false. The launcher exits 1 as required. Corrected disabled-pipeline stages report zero
uploads/builds and extraction work; all four settled suppression/disabled stages report no new
empty publication generations, and active extraction/restored sources return correctly.

Final Sodium frame generation: **186 checks PASS**, plus **15 saved-On fresh-JVM restart checks
PASS**. This verifies presentation/order/cadence and protected native hand pixels in the fixture;
it does not override BUG-051 or establish general FG visual quality.

Final focused vanilla lighting regression: **113 checks / 2,244 frames PASS** on the corrected
fixture. Disabled rendering has zero extraction/query/index work, zero examined/allocated records,
zero uploads and zero cluster builds. All settled suppression/disabled stages retain the same
empty publication generation; active empty and restored nonempty stages report fresh work.
See both renderers' `validation/lighting-transitions.csv` and `checks.txt`.

All **688 checked normal-instance files are unchanged**, and every final test copy's core/adapter
SHA-256 matches the artifacts below. The embedded native image matches `native/build`; no test
add-on is bundled in production. No normal installation occurred. Machine-readable verdicts:
[`verification-summary.json`](../../build/reports/bugfix-049-050-051-20261016/verification-summary.json),
[`final-status.json`](../../build/reports/bugfix-049-050-051-20261016/final-status.json), and
[`normal-instance-comparison.json`](../../build/reports/bugfix-049-050-051-20261016/normal-instance-comparison.json).

| Final run | Runtime | Independent image gate | Launcher exit |
|---|---|---|---:|
| Vanilla full lifecycle | 476 PASS | PASS, 0.2013/0.1673 px | 0 |
| Sodium full lifecycle | 489 PASS | FAIL, 0.2597/0.2666 px | 1 |
| Sodium FG + saved-On restart | 186 + 15 PASS | Not the temporal static-image gate | 0 |
| Vanilla focused lighting | 113 PASS | Not requested for lighting-only mode | 0 |

The full vanilla run preceded the test-fixture rebuild correction; the focused 113-check run
closes that specific coverage gap. This fixture distinction does not change production artifacts.

Artifact SHA-256:

- Core: `e61e94614ead45baecd4ddc36590967160ff921fa9f0fcbf172092da4ffd67e0`
- Adapter 0.1.3: `ba38972dccfb27ac66e602ed027c6384b1d557e91ef99d7a157e0aab0616ce3b`

The original whole-mod performance tables remain measurements of the earlier artifact. Do not
apply their timings to these fixes or interpret diagnostic readback/API-validation timings as
release performance. End, multiplayer, long-soak and many-light gameplay performance coverage
remain outside this fix verification.
