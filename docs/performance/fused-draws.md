# Fused indexed uniform draws — 2026-10-04

The ordinary submission batching improvement remains enabled. This additional optimization
targets Java-to-native overhead in the per-section terrain loop.

## Mechanism

After the normal opaque per-draw uniform uploader runs, an indexed draw with exactly one changed
uniform and no changed textures can send the uniform binding and draw in one Panama call.
`mmm_render_pass_draw_indexed_uniform` calls the existing vertex/fragment setters and indexed
draw helper, in that order. It changes neither GPU geometry nor shader work, and retains index
buffer offsets/types, first index, signed base vertex and first instance. Other binding cases,
empty draws and absent optional native symbols retain the existing path.

The toggle is sampled once per render pass so property lookup is outside the section loop.
Attachment actions, lighting ABI, MetalFX ownership/presentation and future material/RT contracts
are untouched. No draw commands or Java callbacks are deferred/reordered.

## Verification and measurement

Native smoke compares ordinary and fused indexed draws across eight staged uniform versions,
both index widths, index buffer plus first-index offsets, and a nonzero uniform offset. Both
shader stages consume the changed uniform. Render Check primes ordinary bindings with an empty
draw, replaces the GUI transform uniform with an offset slice, asserts one native call and
checks the resulting real vanilla shader colour.

Gameplay benchmark command:

```
python3 tools/metalfx_benchmark/run_manual.py --world '新的世界' \
  --output /private/tmp/metalmod-fused-final-abba-20261004 \
  --submission-benchmark --comparison-property metalmod.fusedDraws
```

This runs native off/on/on/off and spatial strength 25 off/on against command batching enabled
in both cases. Each capture has 20 seconds warmup, 5 seconds countdown and 30 seconds measurement,
after an initial 60-second scene warmup. The camera repeats the same sinusoid at the saved anchor;
render distance is 32, resolution 5120x2664, shared textures, VSync off, 260 FPS limit. Unfocused
captures are rejected. No concurrent GPU tests run during accepted measurement windows.

The diagnostic addon additionally samples the current render thread's consumed CPU time between
camera extractions during F8 recording. `render-thread-cpu.json` reports sample count and average
CPU milliseconds per extraction interval, excluding blocked presentation/GPU wait time. Sampling
includes the five-second F8 countdown plus the 30-second capture (about 2100 intervals/run). This is
overall render-thread CPU consumption, including game logic, rather than isolated draw encoding
or GPU duration. CPU and F8 wall-clock samples have different boundaries; F8 excludes countdown.
The call-count reduction alone must not be interpreted as a proportional speedup.

`analyze_submission.py` reports F8 frame intervals, tails, native crossings and the separate CPU
metric. `comparison.json` identifies the toggled property and exact benchmark JAR SHA-256.
Archived build, GPU gates and gameplay captures belong under `build/reports/fused-draws/`.

The preliminary prototype already reduced native calls from approximately 22,800 to 8,300/frame
while native throughput stayed near 60 FPS (ABBA difference -0.03%). That prototype queried the
toggle per draw and did not sample thread CPU time. Its result is retained as preliminary evidence,
not the final performance selection. High-resolution FPS includes macOS presentation pacing.


## Final fused-call selection

The pass-sampled toggle implementation's native ABBA result is **59.86/59.96 FPS off** versus
**59.83/59.80 FPS on** (-0.17% throughput difference). Render-thread CPU is **6.269/6.162 ms off**
versus **6.206/6.221 ms on**, a pooled difference of just **0.03%**. Native crossings fall from
about **22,783 to 8,255/frame** (63.8%), but that count reduction does not establish a meaningful
CPU or FPS gain. Spatial strength 25's single pair gives 59.76→59.90 FPS, CPU 5.943→5.783 ms;
this lone pair is insufficient to overturn the repeated native result.

**Default remains off.** `-Dmetalmod.fusedDraws=true` enables the experiment. All five offline
gates passed on its enabled path, including 919 native assertions and 207 Render Check assertions.
Both preliminary and final captures are retained in `build/reports/fused-draws/{prototype,final}/`;
`results.json` summarizes the final comparison. Further optimization is evaluated separately.
