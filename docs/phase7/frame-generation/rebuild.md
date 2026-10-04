> The final GUI colour-inference flaw and capped-30 interruptions are superseded by
> [the final-image delivery correction](delivery.md), installed 2026-10-04. The notes below
> describe the preceding conservative rebuild checkpoint.

# Frame-generation quality rebuild — 2026-10-04

The user rejected the prior installed pipeline despite its cadence checks. The rebuild removes
entity influence-box motion, forced colour warping and additive inferred screen effects.

## Producer and composition

Before world rendering, allocate/clear an input-sized R8 attachment. Fragment variants preserve
original depth testing/discard and write visible unstable coverage for entities, particles,
beams, clouds and translucent/effect pipelines. A separate variant leaves temporal scaler
classification unchanged. FG motion uses only exact camera/depth reprojection; no entity boxes,
light-radius regions or object patch matches enter its motion producer.

MetalFX receives separate linear SDR world snapshots and reversed-Z world depth. Frame
Generation uses spatial input while enabled, preserving the saved temporal preference for
FG Off. Hand and actual post-boundary GUI writes stay native. Output composition protects
current and previous raster footprints (one input-pixel dilation), erasing prior silhouettes
with current native pixels. Unreconstructible post-hand colour differences stay native instead
of adding a misaligned residual. No synthetic half-flow fallback remains.

## Cadence and content validation

The default path samples 24 real-only render intervals. A median slower than 1.5 refresh
intervals activates interpolation; transient loading spikes do not. After 120 interpolation
iterations it probes native headroom again. Real 60-FPS rendering is preserved, and no generated
image is counted merely because FG preference is On. The explicit force override is fixture-only.

A GPU compute pass samples unprotected changing world pixels with meaningful camera flow.
If at least 12 samples exist and more than 70% repeat current colour within 0.002 linear units,
that pair outputs native pixels. An asynchronous two-scalar completion read latches content
rejection; later frames use ordinary real presentation until a preference/resource retry.
This does not wait for the GPU or read back whole textures during gameplay. Health flag bit 0
means GPU failure; bit 1 means content rejection, which is separately reported in settings.

The existing ready-copy FIFO presentation service and real-render owner boundaries remain.
No presentation callback acquires drawables, renders world content or advances upload/light rings.

## Limits and verification

Entities, particles, beams and unsupported effects retain their real-render cadence; this does
not claim articulated phantom deformation interpolation. Heavy scenes can therefore retain
base-rate dynamic content. World interpolation that fails content validation is deliberately
unavailable for that resource generation. Arbitrary-scene/user visual acceptance remains open.

Native tests require real stable-world midpoints, surrounding-world motion with a thin moving
beam/sprite, exact current and prior raster-footprint output, exact hand/HUD and safe rejection
of changing-illumination repeats. All 87 static and nine post shaders compile both temporal and
FG fragment variants. Cadence tests cover native 60, transient spikes, sustained 30, headroom
recovery and invalid timing. Copied-world tests include phantom motion, beacon and approaching
experience orbs, native 60 preservation, limited real 30, dawn/camera motion, UI and display
transitions and a fresh-JVM saved-On restart. GPU readback and force-mode are test-only.

Final logs, exact package hash and installation backup live in
`build/reports/frame-generation-rebuild/`. Reported safe fallback is distinguished from successful
interpolated presentation; previous cadence-only passes are not used to claim visual acceptance.

## Installed verification

Installed SHA-256: `754ca5edcbdaa8c903d50e13f1ffb2a52f28433090c3ba070372f3ecef32d736`.
All five offline gates pass: build; native smoke with Metal validation; static 87/87 and post 9/9
shader compilation without diagnostics; pixel render check; standalone Java/FFI/cadence tests.

`/private/tmp/metalmod-fg-rebuild-20261004-b` passed 171/171 gameplay checks. Native full-rate
and phantom/beacon/orb scenes measured 60.06 and 59.91 real FPS with FG enabled and zero
generated activity. A sustained 30-real-FPS limit exercised adaptive interpolation activation.
Dawn/camera tests exercised safe native fallback when content validation rejected repeats;
these are not claimed as generated-quality acceptance. Images confirm the fixture actually
contains the phantom, visible continuous beam and approaching orb.

The fresh-JVM visible retry passed 11/11, including 60.02 real FPS with saved On. Its first
attempt lost foreground visibility during the final stage and remains archived as unaccepted.
The old installed `edbba8cb…` JAR is backed up, normal config hashes before/after match, and
the source world is unchanged. The user's broader visual retest remains pending.
