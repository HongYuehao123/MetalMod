# Final-image delivery correction — 2026-10-04

The user's reproducible case is a 30-FPS render cap, VSync On and an active FG setting, with
movement still appearing to update at 30 FPS. The native transport does present both images:
`mmm_fg_present_paced` copies generated SDR and then the native real texture into distinct
retained drawables; the FIFO service waits for GPU copy completion before presenting immutable
copies one per refresh. Generated/real counters increment only for positive OS presentation
callbacks. The capped camera reproduction measured separate timestamps at about 60 display FPS.

## Root cause and correction

The previous compositor used final-vs-pre-GUI colour difference as a HUD mask. A full-screen
vignette altered those colours, so much of the generated world was overwritten with current
real pixels. Matched raw/final/real/motion/mask readbacks showed 95–97% changed moving-world
raw samples but only 19–24% changed final SDR samples. Display-count success was insufficient.

`mmm_fg_gui_coverage` publishes a separate output-sized R8 attachment, cleared at the native
hand/pre-GUI boundary. GUI fragment variants retain original discard, depth and alpha semantics.
Ordinary GUI draws mark coverage; the vignette marks zero, preserving prior coverage through
maximum blending. Composition preserves exact native hand/HUD pixels using actual coverage.
The uncovered world receives the same-frame multiplicative vignette attenuation after SDR
conversion. It does not use inferred foreground alpha or additive world residuals.

World R8 protection and camera-only motion remain independent. GUI/world coverage owners switch
at the capture boundaries and are cleared before presentation/new world rendering. All vanilla
pipelines compile both world and GUI coverage variants. Native smoke checks a moving eight-pixel
real advance with a four-pixel vignette-modulated midpoint, exact native low-contrast hand, GUI
marker and moving thin-beam current/prior footprints.

The adaptive cadence policy now accepts `probeHeadroom=false` for an explicit render cap at or
below half monitor refresh. It still samples 24 real frames before activating; once active it
retains generation rather than dropping to native-only every 120 calls under an impossible
60-FPS probe. Uncapped scenes still probe/recover native headroom normally.

## Reproducible acceptance

```
python3 tools/temporal_validation/run.py \
  --source-game "$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2" \
  --world '新的世界' --output /private/tmp/NEW-DISPOSABLE-DIRECTORY \
  --frame-generation --delivery-check
```

This fixture uses an explicit 30 cap with VSync and scripted camera turning, with native and
50% spatial inputs. It uses normal cadence selection, not forced generation. The automatic
`analyze_delivery.py` requires at least 100 changing unprotected world samples, 70% changing raw
and final SDR samples and a midpoint pose error below 80% of both endpoint errors. It operates
on matching completed-frame readbacks. Positive separate OS display timestamps, alternating
roles and approximately doubled displayed-to-rendered rate must also pass. Occluded/locked
runs cannot establish screen delivery even if their offscreen content matches.

The GUI-vignette correction improved observed final moving-world changes to about 80–85%.
Pixels near low contrast, dilation or unsupported foreground can still remain native. This
is not a claim of animated entity deformation interpolation; protected content remains at the
real-render rate. The user reports the installed build looks usable for now (2026-10-04).
Final logs, package hash, backup and
installation metadata are stored in `build/reports/frame-generation-delivery/`.

## Installed results

Installed SHA-256: `dcca5566b605ebdf49811281d66895ebe97f1e0c7c3db7a58747622338be221d`.
All five mandatory offline gates pass, including world/GUI fragment variants for 87 static and
nine post pipelines and the vignette-modulated halfway centroid/native HUD/hand smoke regression.

`/private/tmp/metalmod-fg-delivery-20261004-g` passes 22/22 visible capped-30 checks and both
automatic content/pose gates. Native/spatial final moving-world changed fractions are 0.8481
and 0.8280. Midpoint errors are 0.00185 and 0.00341, compared with endpoint errors of
0.01349/0.01112 and 0.01012/0.00731. These compare sampled linear world colours under camera
reprojection, separately from displayed positive timestamps. Generated/real presentation order
and refresh separation pass, with approximately 60 actual display images from 30 real FPS.

The broad copied-world sequence passes 188/189; its final menu-window visibility failure is
retained as unaccepted. The targeted fresh-JVM/menu return repeats with an explicitly restored
owned test window and passes 15/15. Earlier settings assertions were disturbed by a second
toggle during the timed stage; the test-only GUI mixin now disables further physical button
input after exercising the real button. The normal screen remains interactive and this add-on
is never installed in the normal instance. Occluded prototype runs remain archived as failures.

The prior `754ca5ed…` package is backed up; normal configuration hashes match before/after
installation. Source world is unchanged. Diagnostic raw texture readbacks remain test-only.
User gameplay acceptance is provisional; broader release quality and articulated-entity
interpolation remain open.
