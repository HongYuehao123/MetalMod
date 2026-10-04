> **2026-10-04 quality rejection and rebuild:** the prior installation below was rejected by
> the user for stuck motion, trails, broken beams and orb terrain distortion. Those historical
> cadence passes are not visual acceptance. See [quality rebuild](rebuild.md) for the current
> camera-only, exact-raster, adaptive-cadence implementation and its explicit limitations.

# Experimental gameplay interpolation — 2026-10-03

Frame Generation on the MetalFX settings page now requests actual MetalFX frame interpolation.
It defaults Off and is independent of Super Resolution and Temporal Upscaling. It requires the
Metal backend, a supported MetalFX interpolator, VSync (FIFO/FIFO_RELAXED), and a rendered world.
Menus and IMMEDIATE use ordinary presentation. UI changes persist and apply at a real-frame
boundary; the legacy `metalmod.displayLink` flag now requests this same interpolation path.
It does **not** start a display-link thread. Capability/encode/GPU failures retain ordinary frames,
latch the failed generation, and retry only on an explicit setting/resource transition.

## Producer and composition

`FrameGenerationCoordinator` owns an independent `TemporalSceneMotion` history. Projection capture
runs before scene jitter and observes camera cuts, world changes, pause transitions, render gaps,
projection changes, stable entity identities and actual moving-block poses. Temporal reconstruction
and interpolation can both observe those poses without stealing each other's motion registration.
The native motion producer reuses the depth-validated camera/object/pose evaluator without creating
or running a temporal scaler. It runs at the world input resolution in native/spatial/temporal modes.

At the hand-depth clear, reconstruction finishes if needed. The native producer copies the current
world at output size into alternating linear RGBA16F snapshots, preserves world Depth32F and
computes RG16F current-to-previous motion. It captures before the hand, screen effects and HUD.
At surface presentation MetalFX receives world colour only, with no inferred composited UI.
After hand/screen-effect rendering and before the GUI depth clear, the producer snapshots
native SDR colour and hand-depth coverage. Pixels changed by GUI rendering after that boundary,
or covered by hand geometry, copy the final native pixel exactly. The native pre-GUI minus
current-world linear colour contribution also carries subsequent screen effects/postprocessing
into generated world pixels, rather than alternating an untreated world with a treated real frame. This preserves native opacity
and avoids classifying world lighting/postprocessing differences as translucent UI. Translucent
GUI pixels currently retain their final native background as well; UI is not interpolated.

A native regression reproduced current-frame repetition when world background illumination
increased between frames. During SDR output, moving pixels whose MetalFX result still equals
current world colour use a geometric midpoint: sample current world at minus half the
current-to-previous flow and previous world at plus half flow, then average the aligned linear
samples. Foreground protection runs first. This is a bounded fallback for repeated world pixels,
not an RGB blend of unaligned frames. Border samples retain MetalFX output. Approximate flow
and disocclusion can still affect quality and require scene coverage before release acceptance.
An exposure-normalization prototype did not fix this regression and was discarded.

Projection metadata derives physical near/far and vertical FOV from the unjittered projection.
Infinite far uses a finite 65536-block bound for interpolation metadata only. IDs increase once per
captured real world. Reset and two following pairs warm history without displaying generated output.
Gaps above 250 ms reset and warm again. Transient invalid loading-camera/object snapshots are
skipped with history reset rather than permanently disabling the generation; native validation
rejects their scalars before advancing history. Texture identities/IDs obey the interpolation ABI. All
producer, interpolation, snapshot reuse and presentation copies commit in order on the render queue;
next-frame writes execute after the prior frame's input reads. No CPU readback is added. Each interpolation-pair copy completion marks a FIFO entry ready. Only the ready prefix is
submitted on a per-layer serial presentation service, with completed immutable drawables and
a dedicated Metal queue. The service never acquires drawables, accesses Minecraft/GLFW state,
rotates lighting/upload rings, or waits on display callbacks. Configuration retirement uses the existing bounded transition synchronization.

## Presentation and BUG-037

The experimental CAMetalDisplayLink path caused the user-reported unresponsiveness and restart
recurrence. Its precise native stall/delivery cause was not established. Gameplay no longer uses it.
With FG requested in FIFO mode, the ordinary render-thread owner closes its utility frame
boundary but defers the first drawable reservation. World rendering and interpolation enter
the GPU queue before display-only acquisition. This avoids blocking offscreen world rendering
behind the two drawable slots used by the previous generated pair. The render owner still
acquires each drawable; menus/warmup/fallback acquire late and present their real frame. Eligible output
presents on that drawable, then an additional display-only acquisition presents the matching real
image. This second acquisition does not rotate staging, publish lighting, tick Minecraft or advance
scene history. Ordinary drawable-pool backpressure bounds outstanding display work. GPU copy completion
callbacks mark entries ready even if completion notifications arrive out of order; a FIFO drain
preserves pair order. A per-layer serial service submits one ready image per display interval.
Its timer is capped at 50 ms, and the render owner continues rendering without waiting for GPU
completion or display notification. Presentation-only command buffers use an independent Metal
queue after the drawable copy has completed, so they cannot sit behind the next frame's world
rendering. The service retains each drawable through submission; Metal retains it through display.
The layer's normal drawable timeout remains enabled. This changes submission timing only;
the Minecraft render thread remains the sole owner of acquisition and game rendering.

GLFW's fullscreen monitor (or primary monitor for windowed mode) supplies refresh rate,
clamped to 30–240 Hz with a 60 Hz fallback. Rendered deltaTime is used only for interpolation.
Minimum-duration requests together reversed some images; scheduling waits alone did not
fix that. Distinct absolute targets dropped many generated images in windowed mode
(roughly 34–47 displayed FPS). Waiting for actual display callbacks preserved every image
but serialized notification latency, lowering output to roughly 29–40 displayed FPS. Those
prototypes were not installed. The final ready-copy FIFO service plus deferred first-drawable acquisition passed visible verification.
OS refresh rate and GPU readiness can lengthen presentation intervals. First/reset/warmup/no-world/failed
frames take the ordinary real path. The additional acquisition uses the layer's ordinary drawable
timeout; if it fails, the generation latches failed and subsequent frames use ordinary presentation.

F3 and MetalFX settings show measured display FPS plus separate
captured/eligible/generated-actually-displayed/real-actually-displayed counters. Minecraft vanilla
FPS counts real game renders: 30 real FPS with 30 generated FPS fills a 60 Hz VSync display.
VSync Off currently disables generation and uses ordinary presentation. Display FPS is a one-second
rate derived from OS timestamps, not a doubled render counter. Additional diagnostics track
interval count/sum/min/max, sub-half-refresh intervals, missed refreshes and presentation ordering. Only positive OS `presentedTime` callbacks count as displays; zero timestamps
count as dropped. Counters belong to the current resource generation. This prototype adds render
and display work and can add latency or lower real-render FPS. It is ready for user gameplay tests,
not release performance/latency acceptance or a guarantee of doubled visible FPS.

## Verification

All five mandatory gates pass. Native smoke now exercises the complete independent motion/world/
world/explicit-foreground producer: eight real inputs, three warmup outputs, five eligible moving-object outputs.
Their centroids equal the midpoint (83.5 through 115.5 pixels), while a native cyan HUD marker keeps
its exact colour and position. Detached Java surfaces verify that requested On retains ordinary
ownership, including repeated choices, FIFO/IMMEDIATE, odd resize and close.

A separate packaged test add-on runs only in a disposable copied world, never the user's save:

```sh
python3 tools/temporal_validation/run.py --frame-generation \
  --source-game /path/to/test-instance --world 'World folder' --output /private/tmp/new-fg-check
```

The test checks actual generated/real OS presentation in native, spatial and temporal modes;
actual Frame Generation button clicks, persistence, inventory/pause, odd resize, 5120-wide output,
camera motion, minimize/restore, VSync Off/return, FG Off/return and menu with saved On. It then
launches a fresh JVM using that same saved-On copy. Off/VSync transitions allow already-submitted
drawables to drain for one second before checking there is no new generated-display activity.
Test-only screenshots/readbacks inspect real and generated gameplay/UI pixels; none enters the
production path. Evidence and exact results are retained in `build/reports/frame-generation-gameplay/`.

Apple API references: [composited UI](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatorbase/isuitexturecomposited),
[minimum display duration](https://developer.apple.com/documentation/metal/mtlcommandbuffer/present(_:afterminimumduration:)).

Final packaged run: **107/107 gameplay checks and 7/7 fresh-JVM saved-On restart checks passed**.
The test explicitly records active/visible/onscreen-window flags and actual OS presentation times.
Fullscreen output is 5120×2880; real/generated screenshots include native hand, hotbar, inventory,
pause and MetalFX settings. Earlier locked/occluded runs remain archived and are not accepted as
delivery evidence. The exact final JAR/hash and installed backup are in `installation.json`.

## User-reported half-rate counter and horizontal motion bands (BUG-038)

The cadence regression test adds distinct VSync timestamps, generated/real order, displayed-to-real
rate and refresh-rate assertions after a one-second transition drain. The first measured run
confirmed approximately 59.5 displayed FPS / 29.75 paired real FPS at 60 Hz, but exposed actual
generated/real ordering errors. Merely waiting for scheduled handlers did not remove those errors.
Distinct absolute targets also proved inadequate in windowed Core Animation: they dropped
generated images rather than delivering every refresh. Clock-paced request separation now replaces the unaccepted prototypes; waiting for
actual callbacks preserved images but reduced displayed FPS. The exact user-reported band
has not been captured as a pixel sequence; arbitrary-scene visual acceptance remains pending.

The independent interpolation motion producer now retains geometric camera/object flow for
colour-mismatched or depth-rejected pixels and rotational camera flow for reversed-Z sky. The
temporal scaler still uses its reactive rejection policy. Interpolation has no reactive texture;
zeroing motion at those boundaries manufactured motion discontinuities. Its jitter metadata now
uses the same negated sample-offset convention as the temporal reconstruction input. Native smoke
checks continuous eight-pixel flow across sky, changed-depth/colour terrain and the viewport edge.

The copied-world runner holds a process-scoped idle-display assertion during its visible test;
it never unlocks the Mac or changes lock settings. Locked/occluded runs are not accepted.

## Native hand opacity (BUG-039)

The user reports unexpectedly transparent held items. The old colour-only UI decomposition
could not guarantee opaque foreground over arbitrary moving backgrounds. Native hand-depth
coverage now guarantees exact native pixels on generated images. Smoke verifies low-contrast
opaque foreground byte-for-byte while preserving moving world midpoint pixels. The packaged
add-on compares protected hand interiors against the linear native composite used by that exact
generation (readback is test-only), including camera movement. It requires at least 100 protected
interior samples and 98% colour agreement within two SDR code values. Real rendering and
Minecraft blend/depth conventions remain unchanged. Final results are recorded below after
onscreen acceptance; arbitrary-scene interpolation quality remains experimental.

Latest test build is installed with prior-JAR backup and all normal preferences preserved.
All five offline gates pass; native opaque hand pixels are byte-exact and packaged protected
hand interiors pass in native/temporal/camera-motion stages. **Latest clock-paced visible
cadence and fresh-JVM restart remain unaccepted:** the Mac was locked before its final run.
Earlier 107/107 plus 7/7 results above describe the original installation, not this pacing fix.
Exact hash, backup and acceptance limits are in
`build/reports/frame-generation-hand-pacing/installation.json`.

## Continued motion/dawn report (2026-10-03)

The user reported continued uneven motion and dawn flashing in the prior installed build.
The changing-illumination native regression now verifies both brightness bounds and moving
midpoints, native screen-colour contributions, exact low-contrast hand and a depth-free GUI marker. The packaged test includes
an accelerated dawn transition while rotating the camera in a disposable world. A repeated
absolute-deadline experiment still dropped generated frames in windowed mode and was removed.
Current production pacing preserves monotonic phase through lateness below one whole interval.
Final visible results and installation are recorded in HANDOFF.md; offline pixels do not alone
establish smooth displayed gameplay or prove the reported dawn flash has been eliminated.

The synchronous copy-completion prototype eliminated inversions at smaller outputs but lowered
5K delivery to roughly 40 FPS. Splitting its copy/present buffers still serialized world work.
Neither synchronous version is selected; the final service releases the render owner immediately
and submits completed copies asynchronously. Visible acceptance requires an unlocked desktop.

## Final installation and acceptance

Installed SHA-256 `edbba8cb71a3fdbbd3e59025f6445ff78f7e8ab1d44408b81116d778e71a107b`.
All five mandatory offline gates pass. `/private/tmp/metalmod-fg-dawn-20261003-h` passed
173/173 gameplay checks and 11/11 saved-On fresh-JVM restart checks. Windowed 5K, fullscreen
5K and fullscreen camera-motion measured 59.997 actual displayed FPS, with alternating
positive OS timestamps and no order/short-interval failures. Dawn-motion and native/temporal
hand comparisons passed. Native pixels verify illumination bounds, midpoint motion, depth-free
HUD, exact opaque foreground and native screen-colour contributions simultaneously. Normal
settings are preserved and the previous installed JAR is backed up. Archived evidence resides
in `build/reports/frame-generation-dawn/`. The user's subjective dawn/movement retest and
broader release-quality acceptance remain pending.
