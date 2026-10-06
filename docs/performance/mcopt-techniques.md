# mcopt-inspired performance work — 2026-10-04

This is an independent implementation of selected techniques, preserving MetalMod's vanilla
26.2 backend and lighting ABI. It does not import Sodium code or introduce a Sodium dependency.
Reference project inspected: [mcopt](https://github.com/noahdunnagan/mcopt), revision
`b089444e464462e136b1d3a4ee81f4f6c935a1df`.

## Default draw-path changes

`MetalRenderPassBackend` skips rebinding a uniform when its buffer object, offset and length
match the previous binding. A fresh slice describing the same range counts as unchanged; a
different sub-buffer does not. A pipeline change still marks all named bindings dirty.
Texel-buffer uniforms continue their existing texture-refresh path, since unchanged buffer
identity does not imply unchanged texture contents. Repeated texture-view/sampler bindings
are also skipped; a null sampler continues to retain the previous sampler.

Terrain draws reuse the uniform uploader callback and bind full vertex buffers without
constructing a slice per section. Multi-draw methods whose state cannot change inside their
loop apply bindings once. `drawMultipleIndexed` still calls each draw's uploader and applies
its per-draw uniforms, index overrides and sub-buffer offsets individually.

`-Dmetalmod.drawStateCache=false` disables the new uniform/texture caching for A/B diagnosis.
It does not disable the callback/slice allocation removal or loop hoisting.

The real-pipeline regression repeats identical bindings after a draw and applies them with an
empty indexed draw, so it cannot alter blending or covered pixels. It measures **5 native
binding calls with caching disabled versus 0 with caching enabled**. Both modes pass all
204 render checks. This establishes an API-call reduction, not a whole-game FPS improvement.

## Optional render-stage GPU timing

Start Minecraft with `-Dmetalmod.gpuStageTiming=true`, then use F8 as usual. Sampling runs only
while F8 capture is active. Five columns are appended after the existing SR columns:

| Column | Meaning |
| --- | --- |
| `gpu_completed_render_passes` | Completed passes with valid vertex and fragment samples |
| `gpu_completed_vertex_ns` | Sum of vertex-stage durations of those passes |
| `gpu_completed_fragment_ns` | Sum of fragment-stage durations of those passes |
| `gpu_profile_dropped_passes` | Passes that could not be sampled (capacity, support or allocation) |
| `gpu_profile_invalid_passes` | Sampled passes with failed or missing results |

These are **completed stage workload per polling interval**, not whole-frame GPU execution
time. Completions can lag the current CPU frame and overlap route transitions. Stages/passes
can overlap; adding their durations cannot establish total frame GPU time. Compute, blit and
encoders created by separate MetalFX components are not included. Durations remain `-1` until
a valid sample completes. All five columns are `-1` when the feature is disabled or its native
API is missing. Existing CPU capture ABI and CSV column positions remain unchanged.

Timestamp support is checked before allocation. GPU timestamps are calibrated to CPU
nanoseconds using device timestamp pairs around submission/completion. Counter storage is
bounded to 16 outstanding command buffers, each with 64 render-pass slots. Overflow is
reported, not silently presented as complete coverage. Read/reset never waits for the GPU.
Completion callbacks publish native results only; epoch checks discard previous captures'
late completions. Aborted command buffers release their counter storage.

This supplies stage measurements for bottleneck investigation. The whole-frame GPU timing
and CPU-frame association gap remains open.

## Optional thread QoS experiment

`-Dmetalmod.threadQos=true` requests user-interactive QoS on the thread executing
`GameRenderer.render`, and user-initiated QoS on the integrated server's `runServer` thread.
Each platform thread attempts the setting once and logs its native return code. Dedicated
servers, mesh workers, IO workers and virtual threads are excluded. The default is **off**.

The dedicated `ThreadQosTest` verifies both native classes on disposable platform threads:

```bash
/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home/bin/java \
  --enable-native-access=ALL-UNNAMED -Dmetalmod.threadQos=true \
  -cp build/classes:build/test-classes net.metalmod.performance.ThreadQosTest
```

API success does not establish a performance gain. Before changing the default, compare
repeated F7/F8 routes with QoS off/on, including p95/p99 frame time, server tick time,
chunk-loading behavior, focus changes and sustained thermal load. Profile counters themselves
add overhead, so keep GPU stage timing off for final throughput comparisons.

## Verification and remaining work

All five offline gates pass. Native smoke and render checks ran with `MTL_DEBUG_LAYER=1`.
Shader inventory: 87/87 static and 9/9 post-processing. Standalone tests also pass with
`metalmod.gpuStageTiming=true`. Native tests verify real stage timestamps, bounded outstanding
storage, aborted-resource retirement, per-buffer overflow, reset and stale-capture rejection.
Logs are under `build/reports/mcopt-techniques/`. No gameplay FPS or latency gain is claimed;
the rebuilt JAR has not been installed into the normal Minecraft instance.

Pass continuation/store elimination needs a separate ownership design: 26.2 can submit on a
different Java encoder object, so the present backend commits at pass close. Removing that
boundary without coordinating uploads, copies, fences, readback and presentation would violate
ordering. Sodium's GPU terrain lists and vertex pulling also require an independent vanilla
geometry contract; merely implementing unused indirect-draw methods cannot reproduce them.

## Sources

- [mcopt GPU profiling and encoder implementation](https://github.com/noahdunnagan/mcopt/blob/b089444e464462e136b1d3a4ee81f4f6c935a1df/metal/src/main/native/mcmetal.m)
- [mcopt QoS experiment](https://github.com/noahdunnagan/mcopt/blob/b089444e464462e136b1d3a4ee81f4f6c935a1df/metal/src/main/java/mcopt/metal/Qos.java)
- [Apple: Sampling GPU data into counter sample buffers](https://developer.apple.com/documentation/metal/sampling-gpu-data-into-counter-sample-buffers?language=objc)
- [Apple: Prioritize Work with Quality of Service Classes](https://developer.apple.com/library/archive/documentation/Performance/Conceptual/EnergyGuide-iOS/PrioritizeWorkWithQoS.html)
