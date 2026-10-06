# Metal buffer offset updates — 2026-10-04

Ordinary terrain draws frequently bind different offsets in the same uniform arena. This
optimization uses Metal's `setVertexBufferOffset:atIndex:` / `setFragmentBufferOffset:atIndex:`
after the first full bind of an identical buffer in the current encoder. Different buffers,
the first bind and slots outside the cached range keep the full setter path.

`mmm_render_pass_enable_buffer_offsets` opts one encoder into a cleared thread-local table of
buffer pointer identities for each shader stage. It retains no resources; Metal's encoder
retains bound resources. Ending that pass discards identities. Enabling a different encoder
clears the table, and an encoder that no longer matches uses full setters. Pipeline changes
preserve the buffer table, matching Metal's existing encoder state behavior. Any future direct
Metal buffer mutation in an enabled encoder must reset this cache or use the mmm setters.

Only ordinary Java-created render passes opt in. Native MetalFX/temporal/frame-generation passes
keep their existing buffer setup and command ownership. Neither shader/lighting inputs nor
material/geometry ownership changes. Feature flags for indirect drawing remain false.

Native smoke renders matched halves using ordinary and cached paths: a uniform changes from
offset 256 to 512 in the same buffer, then is replaced by another buffer. Vertex and fragment
stages both consume it. Sixteen render passes repeat across staged versions and both index
widths, exercising encoder retirement and reset. Real vanilla Render Check and the other
offline gates run with `-Dmetalmod.bufferOffsets=true`.

Gameplay uses the same copied-world native ABBA and spatial A/B harness as the fused-call
experiment, with `--comparison-property metalmod.bufferOffsets`. Fusion remains off and
command submission batching remains on in both arms. CPU consumption is sampled separately
from F8 frame intervals, including the five-second countdown plus 30-second measurement.
See [benchmark methodology](fused-draws.md). The comparison JAR and property are recorded in
`comparison.json`; results and accepted captures are archived under
`build/reports/buffer-offsets/`.

**Initial selection (superseded below): default off.** Tested with `-Dmetalmod.bufferOffsets=true`. All five offline
gates pass on its enabled path (919 native assertions, 204 Render Check assertions); the final
default pixel and standalone checks also pass. The gameplay comparison was interrupted by
menu/focus changes and the task stopped its disposable client. Its partial captures/logs are
retained in `build/reports/buffer-offsets/interrupted/` with an `UNACCEPTED.txt` marker. No matched
gameplay performance result or temporal/FG gameplay acceptance is established for this option.
These interrupted runs did not support a default change. The subsequent completed verification
below supersedes that pending selection.

A second run accepted its first off/on/on captures: render-thread CPU **6.385 ms off**, then
**6.053/6.060 ms on** (about 5% below that single baseline). These are promising preliminary
measurements, not a completed ABBA result. The remaining control failed three focus checks.
`cua.getState()` then reported that the Mac was locked and could not be automatically unlocked.
Evidence is retained in `build/reports/buffer-offsets/locked/`, including the three accepted
captures and three rejected controls. No matched spatial comparison was reached.

The benchmark runner now uses the validation runner's temporary `caffeinate -d -i -u` assertion,
bounded to 900 seconds and tied to its client PID, to prevent unattended idle lock/display sleep.
It does not alter persistent system settings or unlock an already locked Mac. Manual unlock
was required before resuming; the completed verification below supersedes those pending checks.


## Completed reverification and selection

**Default on** after the 2026-10-04 reverification. `-Dmetalmod.bufferOffsets=false` restores full
buffer setters. Fused draws remain off. Ordinary submission batching remains on. No shader,
lighting, MetalFX presentation or material/RT contract was changed.

All six accepted benchmark captures are focused, unpaused and without open menus; no captures
were rejected in this complete run. Native ABBA pooled render-thread CPU is **6.319 → 6.190 ms**
(**2.04% lower**). The spatial strength 25 single pair gives **6.146 → 6.018 ms** (~2.1%).
Native throughput changes **-0.20%**, with FPS still around 60 and no material tail improvement.
This is a modest CPU saving in one scene, not a universal FPS gain or a whole-frame GPU saving.

| Capture | FPS | p95 ms | Render-thread CPU ms |
|---|---:|---:|---:|
| batch01-native-off | 59.86 | 17.71 | 6.374 |
| batch02-native-on | 59.73 | 17.57 | 6.220 |
| batch03-native-on | 59.56 | 18.07 | 6.161 |
| batch04-native-off | 59.66 | 17.99 | 6.264 |
| batch05-sr25-off | 59.66 | 17.60 | 6.146 |
| batch06-sr25-on | 59.70 | 17.53 | 6.018 |

Final default build: all five offline gates pass (919 native checks, static 87/87, post 9/9,
204 Render Check assertions and standalone tests). With explicit offset updates enabled,
MetalFX temporal gameplay passes **385/385**, and the final FG gameplay run passes **191/191**
plus **15/15** saved-On fresh-JVM restart checks.

The first enabled FG run is retained as unaccepted: **186 checks, 10 failures**, including an
inactive/occluded startup and later presentation order/cadence failures. The disabled control
passes **158/158 + 15/15**; quality protection skips some cadence checks, so it does not prove
causality. The unchanged enabled retry passes all **191 + 15**. The initial failure's exact cause
remains unresolved; no production presentation code, fixture thresholds or assertions were
changed to obtain the passing retry. This is scoped compatibility evidence, not a claim that
all experimental FG delivery risks are resolved.

Evidence: `build/reports/buffer-offsets/reverification/`, including accepted `benchmark/`,
`temporal/`, `fg-initial/`, `fg-control/`, `fg-final/`, final gate logs, `results.json` and
`verification-summary.json`. The comparison metadata records the benchmark JAR; the final
JAR changes only the Java toggle's default from that explicitly enabled verified path.
The normal Minecraft instance/world/config/mod JAR was not replaced.

Final packaged JAR SHA-256: `1699e577bb1294338da95f7f66d1b58e4f4871f038e88e2f70ba3383d7ef9582`.
