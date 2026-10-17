# Lighting CPU microbenchmark

This harness measures the existing lighting preparation APIs in isolation. It does not start
Minecraft, create a Metal device, upload to the GPU, or measure gameplay FPS. Run it after the
canonical mod build, with no competing test/game workload:

```bash
./scripts/build_mod.sh
./tools/lighting_benchmark/run.sh
```

`JAVA_HOME` and `METALMOD_MC_INSTANCE` have the same defaults as the canonical build. The script
compiles only the harness against `build/classes` and the real instance classpath. Use
`./tools/lighting_benchmark/run.sh --compile-only` to compile without running, or `--verify-only`
to run the deterministic assertions without timing. Runtime output includes CSV measurements.

The cases cover empty, 1/16/64 scattered lights, 1/16/64 co-located lights of radius 8, and a
**synthetic** 64-light/radius-128 case that touches all 512 cells. The last case exceeds normal
collector radii and is a stress bound, not a representative world. Scattered lights cover a
48×20×48-block area. All cases use 16 deterministic fractional camera variants near world
coordinates ±30 million. Input snapshots are prepared outside the measured region.

Three operations are reported separately:

- `snapshot_encode`: `LightSnapshot.encode()`, including its output and per-light direct buffers.
- `grid_build`: rebuilding a reused `LightClusterGrid`; no encoding, telemetry scan, or upload.
- `cluster_pack_cpu`: texel/record encoding, their merge, copying into a direct byte buffer, and
  grid-uniform encoding. This mirrors the CPU portion of `MetalDevice.uploadClusterTexture` and
  excludes the backend/GPU upload. The grid and input are fixed during this operation.

Each operation/case warms for at least 300 ms and measures seven batches of at least 75 ms.
Results escape through a volatile sink on every iteration and a checksum consumes final output.
Clock checks are amortized over 32 operations. Reported median/p95/min/max describe **batch mean
CPU wall time per operation**. In particular, `p95_batch_us_per_op` is not a frame-time tail or
the 95th percentile of individual calls. With seven batches, p95 is the largest batch mean.
Allocation, GC and direct-buffer cleanup can influence the results. The script fixes the heap
at 256–512 MiB and direct-memory limit at 256 MiB so those effects remain bounded. Repeat in
fresh JVMs before comparing a code change; this is a lightweight diagnostic, not JMH.

Before timing, a separate axis-aligned box/cell intersection oracle checks all cell memberships,
index ordering, bounds, overflow counts, unused entries, large-coordinate alignment, ABI fields,
camera-relative record bytes, and deterministic rebuilding for every camera variant. These
assertions do not validate the light collector, shaders, upload synchronization, or visual quality.

`direct_payload_bytes_per_op` is the known requested payload size, excluding allocator overhead
and heap objects. It is not a measurement of live memory, GPU bandwidth, or allocation rate.
The current encoders request 2064 + 32×light-count direct bytes for snapshot encoding and
141600 direct bytes for clustered packing, regardless of how many cells are occupied.

The first whole-mod run and its two fresh-JVM measurement sets are documented in
[the 2026-10-16 report](../../docs/performance/whole-mod-test-20261016.md).
