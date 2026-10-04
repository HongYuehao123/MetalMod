# Experimental display-link presenter — 2026-10-03

**Superseded for gameplay (BUG-037): gameplay uses the ordinary-owner pair presenter.**
See [gameplay implementation](gameplay.md). The following recovery was an intermediate checkpoint.

**Historical recovery override: gameplay activation was disabled.** Saved On and either launch flag
cannot start this presenter from `MetalSurfaceBackend`. The settings control is disabled and reads
Off/Unavailable; ordinary presentation remains active. The following describes the native foundation
and historical wiring, not currently available gameplay. The user reported unresponsiveness after
activation, recurring on restart. Rendering continued in the log; the native delivery/stall cause is
not established. The five offline gates and detached tests were insufficient for product activation.
Re-enable only after actual on-screen responsiveness, delivery and lifecycle verification.


Implemented at user request. **Options → MetalMod → MetalFX → Frame Generation: ON/OFF**
selects a native `CAMetalDisplayLink` presenter for FIFO/FIFO_RELAXED Metal surfaces. The page
retains the existing Super Resolution and temporal controls. Frame Generation defaults Off and
persists as `enableFrameGeneration`; it applies before the next real-frame acquisition. UI choices
override `-Dmetalmod.frameGeneration`, then the legacy `-Dmetalmod.displayLink` alias, then the saved
preference. Invalid launch values are ignored. It is independent of Super Resolution. The UI caption
and tooltip state that generated gameplay frames are pending; On currently enables pacing only.
IMMEDIATE retains ordinary presentation with an enable-VSync status. Rapid choices coalesce,
and a later explicit choice can retry a failed presenter. Missing native support falls back to
ordinary frames with a status reason.
The packaged game producer currently supplies real, fully composed frames only. The native API
can schedule a prepared generated/real pair; it does not yet produce gameplay generated frames.

## Frame and drawable ownership

`MetalSurfaceBackend.configure` stops the previous presenter before changing the layer. A new
native presenter owns the layer, a dedicated native run loop, and the renderer's existing command
queue. Native ownership guards deny ordinary `mmm_layer_acquire` and `mmm_layer_configure` while
a display link owns that layer. Creation also rejects a second presenter for the same layer.

The render-thread `acquireNextTexture` calls `mmm_utility_end_frame` exactly once per real render
instead of calling `nextDrawable`. `present` still applies lighting settings, fences the lighting
ring and calls `MetalDevice.endFrame` exactly once per real render. It then encodes a native-size
snapshot into a fresh command buffer on the existing queue and commits before the next render.
The source texture view is retained by that submission, and queue ordering prevents the following
render from modifying the pixels before the snapshot copy executes.

The native callback alone receives display-link drawables. It copies a completed immutable snapshot
into the supplied drawable, commits GPU work and calls ordinary `present`, before `targetTimestamp`.
Explicit `presentAtTime` is invalid for display-link drawables. No callback calls Java/game logic,
advances simulation/temporal history, publishes lights, flushes utility uploads or rotates rings.
No callback CPU readback or GPU completion wait is added. The mailbox mutex is released before
commit/present because presented handlers may run synchronously for dropped/occluded drawables.

## Bounded mailbox and output contract

Three slots have states free -> copying -> ready -> active -> free. Each owns a shared BGRA8 real
snapshot; a generated snapshot is allocated lazily for slots that receive a generated input.
Storage is bounded by three real and at most three generated native-size textures. At 5120×2880,
nominal texel storage alone is about 169 MiB real-only or 338 MiB with all generated snapshots;
allocator overhead is additional. The developer presenter adds a full-frame snapshot operation.
Neither memory overhead nor performance is accepted for release.

Inputs are tracked single-sample 2D native-size RGBA8/BGRA8Unorm textures with shader-read usage.
They must already be SDR encoded and include any native hand/screen-effect/UI composition. Raw
linear RGBA16Float interpolation outputs are deliberately rejected; a future producer must convert
and compose before submitting them. A source-less real frame encodes a clear. Snapshots become
ready only at GPU completion; input lifetime is retained independently of producer teardown.

A full mailbox returns 1 immediately and records dropped outputs. No additional pending copy is
allocated. Ready completion keeps the newest pair and discards older ready frames. Once a pair
starts, its generated image is followed by its matching real image before selecting another pair.
A callback that misses its deadline does not commit an output. Frames older than 250 ms are discarded
when the callback runs; they are not replayed after a long gap. This age limit is an initial developer
policy, not an accepted latency budget. Compositor-dropped drawables (zero presentedTime) are counted
as dropped, not displayed. There is no synthetic estimate substituted for an actual presentation.

## Failure and lifecycle

Creation/resource/queue/encoding failures revert the Java surface to ordinary presentation and latch
failure for that surface rather than retrying every frame. A submission failure drops the affected
real frame and restores ordinary acquisition on the following frame. Stop waits at most two seconds
for callback-thread exit and link invalidation, without waiting for GPU completion. Ordinary
acquisition cannot resume if stop fails to relinquish ownership. Completion handlers retain resources
still used by pending copy/presentation buffers. Resize/mode change/close stop the link first;
new generation counters reset, while Java real-render IDs continue increasing for the surface.
Already committed display submissions may finish during retirement.

When the window is occluded/minimized or the desktop is locked, the OS may suspend callbacks or drop
presentations. The bounded mailbox remains bounded; future callbacks discard expired pending images.
Full monitor/refresh/minimize/unlock and packaged-game lifecycle acceptance remains pending.

## Diagnostics ABI

`metalmod_display_link.h` defines the C ABI. `MetalNative.displayLink*` uses optional bindings;
missing symbols keep ordinary rendering available. `MetalSurfaceBackend.displayLinkStats()` exposes
14 unsigned 64-bit values for the current (or last stopped) generation:

| Index | Meaning |
|---|---|
| 0 | Display-link callbacks |
| 1 | Accepted real-render snapshots |
| 2 | Actual displayed drawables |
| 3 | Actual displayed real frames |
| 4 | Actual displayed prepared generated frames |
| 5 | Dropped outputs (mailbox, superseded, stale or compositor dropped) |
| 6 | Missed callback deadlines |
| 7 | Mailbox-full submissions |
| 8 | Last accepted rendered ID |
| 9 | Rendered ID of latest actual display |
| 10 | Latest actual display sequence ID |
| 11 | Latest actual presentedTime in nanoseconds, Core Animation host clock |
| 12 | Occupied snapshot slots, maximum 3 |
| 13 | Generation failure flag |

Counts are per native presenter, not simulation ticks. Existing F3/F8 FPS remains real-render FPS;
new presentation metrics are available through the API but have not been added to F3/F8 UI/CSV.
The existing global capture instrumentation does not include callback-only native submissions.
Do not infer displayed FPS or latency from existing render-loop telemetry.

## Verification and remaining work

All five required offline gates pass. Native smoke now exercises three detached-layer presenter
generations, duplicate ownership, ordinary acquisition/resize rejection, wrong queue, three pending
GPU snapshots, nonblocking overflow, stale ID rejection, stats capacity, stop/release while copies
are uncommitted, actual GPU completion after release and IMMEDIATE exclusion. Standalone tests
check the packaged optional Panama ABI. Existing shaders remain 87/87 + 9/9; render check passes.

`./tools/display_link_check/run.sh` is an additional real-window gate, launched as a temporary local
app bundle with Metal validation. It verifies an ordinary actual-presentation baseline before
checking sustained prepared generated/real delivery, actual timestamps, stale backlog dropping,
shutdown with work pending, repeated ownership transfer/odd resize and ordinary fallback. A missing
ordinary baseline reports BLOCKED and exits 77; it is never a passing display result.

The final real-window gate is currently blocked because the Mac is locked (confirmed by the desktop
UI tool). Earlier exploratory runs recorded real/generated timestamps but did not finish all checks.
A rerun on the unlocked visible desktop is required before declaring display delivery verified.
No game launch, installed-JAR replacement, persisted config change or Git commit was performed.

Next: independent gameplay world/motion snapshots, native hand/UI composition into real and
generated SDR images, connect only eligible interpolation outputs, add display telemetry to captures,
then validate deadline/load behavior, monitor changes, lifecycle and measured input-to-display latency.
Phase 7C and the full Phase 7 remain unaccepted.

API references: [Apple display link](https://developer.apple.com/documentation/quartzcore/cametaldisplaylink?language=objc),
[callback deadline](https://developer.apple.com/documentation/quartzcore/cametaldisplaylink/update/targettimestamp),
[actual presented time](https://developer.apple.com/documentation/metal/mtldrawable/presentedtime).

Final build SHA-256: `a9e23a994b2c6bdbbd2c6b772a036cdd5afe1554b5dcb196b8885d88124d0d4c`.
Full local logs: `build/reports/display-link/{build,smoke,shaders,render,standalone,display}.log`.
The final display log explicitly reports BLOCKED at the ordinary baseline, not PASS.

## MetalFX settings verification — 2026-10-03

All five required offline gates pass after wiring the saved control. Standalone tests cover
precedence, invalid flags, isolated config persistence (On and Off), preservation of reconstruction
preferences, FIFO live enable/disable, coalesced toggles, IMMEDIATE fallback, odd-sized surface
reconfiguration and close. The surface tests use real Metal/native ownership and snapshot submission;
they do not claim on-screen delivery. The locked-screen delivery gate above remains unaccepted.
Logs are retained under `build/reports/metalfx-settings/`. No installed JAR or instance config changed.
