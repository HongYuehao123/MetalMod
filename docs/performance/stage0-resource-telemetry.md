# Stage 0 resource attribution and benchmark fixtures — 2026-10-17

Implementation starts from `349ef76`. The instrumented build is a new artifact: identify it by
JAR SHA-256 and `comparison.json` provenance, rather than calling it an unchanged `349ef76` run.
**Stage 0 complete:** all five offline gates, live Computer/F8 validation, and two canonical
vanilla fixtures with repeated ordinary captures, separate attribution and bounded completion
controls pass. See [live evidence, baseline variability and scope](stage0-live-20261017.md).

## Capture

Launch with `-Dmetalmod.resourceTelemetry=true`, then use F8 normally. Attribution starts after
the countdown, at the first discarded presentation boundary, and ends when capture stops.
The background export writes `resources.csv` alongside `frames.csv` and marks the capture with
`INSTRUMENTED.txt`. The flag defaults Off. Cancelled empty captures export nothing.

Columns: `operation,label,format,calls,logical_bytes,cpu_api_ns`. Rows are capture totals grouped
by operation, engine resource label and format; labels are not unique object IDs. Calls describe
attempted backend operations, not a separate success census; retain existing resource-failure
and native health checks for acceptance. Timings include Java wrapper/native-call CPU wall time,
possibly including synchronous waits, **not GPU time**. Clear/load bytes are logical attachment
footprints, **not actual tile stores, bandwidth or bytes uploaded**. Texture uploads count useful
texels and exclude row padding. Texel emulation counts one logical upload even when it needs two
native row uploads.

Coverage:

- `buffer.initial-write`, `buffer.write`, `buffer.copy`, `buffer.transient-upload` and
  `buffer.light-mapped-write`: initial, ordered buffer writes/copies, transient uploads and
  MetalMod's explicit light-record writes. Buffer suballocations retain parent labels.
- `texture.write`, `texture.buffer-upload`, `texture.texel-emulation`: CPU texture uploads and
  cloud-format texel emulation, including `R8_SINT` without changing its rendering path.
- `clear.full` / `clear.partial`: standalone clears, with combined color/depth labels and formats;
  rectangle APIs covering the entire attachment count as full. Bytes cover the clipped rectangle.
- `pass.begin`: native render-pass creation wall time, attributed to the first color target.
  `pass.attachment-load` / `pass.attachment-clear.full`: each engine color/depth attachment,
  using view dimensions for the footprint; these declaration rows have zero separate API time.
  This exposes rendered lightmap updates and sprite atlas animation passes as pass work.

Not covered: arbitrary writes through ByteBuffers mapped by the engine, native-only MetalFX,
presentation and synthetic temporal coverage attachments. Depth-only pass timing uses `?` as
the target label; its attachment row retains the depth resource label. There is no per-frame
resource table, per-pipeline draw attribution or native GPU traffic measurement.

Storage is bounded at 512 distinct keys plus one overflow row; labels truncate at 160 characters.
Synchronization/key allocation occurs only during enabled captures; disabled operation sites take
a flag check with no timer or map mutation. Snapshot export is detached from the next capture.
Instrumentation overhead is not assumed negligible: collect ordinary throughput separately.

## Canonical fixtures

Use the existing disposable-world launcher; it never installs into the normal instance:

```sh
python3 tools/metalfx_benchmark/run_manual.py \
  --submission-benchmark --stage0-fixture surface-rd16 \
  --renderer-comparison vanilla --source-world /absolute/source/world \
  --world Stage0Surface --anchor-route /absolute/surface-route.json --anchor-waypoint 0 \
  --output /absolute/new/output

python3 tools/metalfx_benchmark/run_manual.py \
  --submission-benchmark --stage0-fixture dense-rd32 \
  --renderer-comparison vanilla --source-world /absolute/source/world \
  --world Stage0Dense --anchor-route /absolute/dense-route.json --anchor-waypoint 13 \
  --static-camera --output /absolute/new/output
```

Surface uses RD16 and the existing repeatable 30-second +/-12-degree yaw, 3x2-block translation
around an explicitly supplied **surface** anchor. This is a spin/short-flight proxy, not a matched
mcopt reproduction or a new long-distance flight route. Dense uses fixed zero-based waypoint 13,
RD32, and rejects actual pixel dimensions other than 5120×2664 before capture. Select a window
size yielding that backing resolution on the hosting screen. Both use the external display by
default, Vsync Off / Unlimited, FG/SR/Temporal Off and a fixed renderer. Surface/dense terrain
identity still requires human selection/review; a route coordinate alone cannot prove it.

Add `--resource-telemetry` for attribution. Repeat without it for ordinary performance evidence.
In a separate launch without resource telemetry, add `--offscreen-comparison` for
present/offscreen/offscreen/present controls with identical
completion fences and at most two pending frames; use `analyze_offscreen.py` for those captures.
For Sodium, select `--renderer-comparison sodium` and supply the official pinned 26.2 Sodium and
separate adapter via `--extra-mod`, with the same options override in every launch.

`comparison.json` records fixture name, anchor route hash, source level.dat hash, git HEAD,
working-tree status, tracked diff hash, JAR hash, requested settings and expected dense pixels.
JAR hash includes compiled untracked source changes; tracked diff hash alone does not.
Preserve the full route and world snapshot with the experiment; level.dat hash is provenance,
not a content hash of every region file. Match actual F8 dimensions and hosting display,
renderer/mods/options, source snapshot and camera across controls. Keep native-only state,
zero errors, compilation, focus, menu/recovery checks from existing analyzers.

For each fixture collect repeated ordinary launches, a separate attribution launch, and bounded
completion controls. Report rendered FPS, completed GPU FPS, presentation events/s and p95/p99
separately. Record the first accepted launch's actual settings, hashes and source/route paths as
the baseline manifest. The completed vanilla baseline manifest and live F8 evidence are in
[the live report](stage0-live-20261017.md). Surface presentation is session-sensitive; retain
individual measurements and use fresh matched controls for later optimization claims.

## Offline verification

All five gates pass on the implementation: canonical build; native smoke (934 checks);
shader inventory (87 static + 9 post, no diagnostics); core render check (236 checks,
including 11 new attribution/pixel checks); standalone tests including telemetry lifecycle,
exact aggregate bytes, stale work rejection, CSV escaping, detached snapshots and bounded overflow.
The benchmark add-on builds, all 11 existing analyzer regression cases pass, five new fixture/diagnostic-isolation guard tests and five Stage 0 analyzer tests pass. GPU gates require host access: the sandbox's initial smoke
attempt reported no Metal device; the host run passed. An initial render-check compile error from
missing foreign-memory imports in the new fixture was corrected before the passing run.

Logs and built artifact hash are retained under `build/reports/stage0-20261017/`. The separate live
report provides gameplay baseline numbers; these core offline checks do not constitute Sodium
runtime validation.

The moving surface completion fixture has different draw averages at different frame cadences.
`analyze_stage0.py` checks its full camera yaw/translation cycle and records per-run completed
reference rates without an aggregate gain ratio. The default `analyze_offscreen.py` and the fixed
underground fixture retain the strict 1% draw-workload gate. The optional benchmark-only
`METALMOD_BENCHMARK_JAVA` executable override supports a uniquely named disposable app for Computer;
it does not select or modify the normal HMCL launcher.
