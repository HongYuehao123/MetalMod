# Packaged temporal gameplay validation

This separate client test mod drives native/spatial/temporal stages in a disposable copied world. It is never
included in `build/libs/metalmod-1.0.0.jar`. Build the production mod with `./scripts/build_mod.sh`
first, then run on macOS with host GPU/window access:

```bash
python3 tools/temporal_validation/run.py \
  --source-game /path/to/test-game \
  --world 'World folder name' \
  --output /private/tmp/metalmod-temporal-validation-new
```

The output directory must be new. The launcher copies the selected save, options, config and
companion mods, installs the current packaged production JAR plus the test add-on in the copy,
and launches from that directory so the embedded native library is exercised. The source save
and normal Minecraft instance are untouched. It uses an offline test identity; authentication
and Realms errors are expected. The copy receives a torch/creative mode and noon/clear weather.

Stages cover Off, strength 0 (native), ordinary spatial, temporal at strengths 25/33/50, odd window
dimensions, inventory, pause with GUI scale 4, the Super Resolution settings screen and its actual temporal button (On/Off/native bypass), disabling
temporal, native return and menu-only. UI choices are tested against opposite launch flags and persisted
config is reloaded to verify the switch. Scene dimensions are checked against the selected strength.
Each stage settles for five seconds and 120 frames; an active temporal generation must also have 120 frames
in its current generation, avoiding false failures from late window resizes. Checks inspect
live hook execution, world jitter/native hand counts, rendered world-depth readback, the closed temporal interval,
native bypass, resource/pipeline/binding health and a one-native-pixel output marker. Screenshots
are vertically restored after vanilla screenshot readback to show the native output orientation.

The client exits automatically. Read `validation/result.txt`, `checks.txt`, stage `.txt` records,
screenshots and `console.log` under the output directory. Exit 0 requires a PASS result. Additional stages check history reset after fast turns, teleporting, FOV changes, render gaps and
resource reload; repeated generation retirement is stressed. Static, camera-motion and rainy
entity/item scenes save 16-image sequences. Native GPU smoke separately asserts camera/object
vectors, pose refinement, rejection and static jitter stability. Screenshots/readbacks affect frame
time and these short gameplay sequences are correctness evidence, not release performance samples.

The extended run also minimizes/restores, crosses dimensions, reconnects, pulses a real sticky
piston and asserts the live moving-block producer. A frozen copied scene supplies matched native,
spatial and temporal strength-50 ROI sequences. ROI readback keeps PNG cost outside the main image
and records each jitter sample/time/reset. Run `python3 tools/temporal_validation/analyze.py
/path/to/validation` (Pillow/NumPy) for calibrated static edge registration and a comparison sheet.
The test never installs itself in the normal instance.

Six final uncapped 20-second timing samples (native/spatial/temporal, then reverse order) use a
settled frozen foliage anchor, five-second warmups, identical native output and strength 50. No
screenshots occur during timing. The separate `focus.dylib` only activates the copied test client
at run boundaries; it is excluded from both JARs. Each timing record includes focus-loss count,
mean/p95 CPU frame interval and latest completed scaler-batch GPU samples. Unfocused samples
are not release evidence. Short static samples do not establish motion-route or tail acceptance.

## Frame-generation gameplay and saved-On restart

Add `--frame-generation` to the launcher command for the independent interpolation test. It saves
On in the disposable copy before startup, drives the actual MetalFX page button, verifies native/
spatial/temporal generated and real OS presentation, captures real/generated images, and exercises
UI, resize, 5120-wide output, camera motion, minimize/restore, VSync fallback and menu. It then starts
a fresh JVM in the same copy with saved On and requires actual generated/real delivery again.
Results are `validation/` and `validation-restart/`; source world/config remain unchanged.

The FG suite also measures actual OS display intervals and generated/real order, verifies that
displayed FPS doubles paired-real FPS and meets a 60 Hz cadence, and compares protected hand
interior pixels to their native source composite. Process-scoped caffeinate assertions keep the
display awake only during the test; the suite never unlocks the Mac or changes lock settings.

## Quality-rebuild fixture (2026-10-04)

FG sampling runs at the completed surface boundary. The fixture tests full native-rate rendering
with FG On, a copied-world phantom/beacon/XP-orb scene, exact raster-sized coverage and absence
of object-region motion. A 30-FPS limited stage exercises adaptive activation. Content rejection
is reported as safe native fallback, separately from successful interpolated display checks.
The test-only force flag exercises delivery paths; normal gameplay uses adaptive cadence.
Fresh-JVM restart additionally checks preservation of full-rate real rendering.

## Capped-30 final-image delivery regression

Add `--frame-generation --delivery-check` for native/spatial camera turning with the user's
30-FPS/VSync reproduction. It requires positive, ordered generated/real OS display timestamps
and automatically runs `analyze_delivery.py` (NumPy/Pillow), checking actual final midpoint
pixel preservation and a halfway world pose against both endpoints. Diagnostic texture copies
and readbacks are test-only. Final-vs-pre-GUI colour difference is no longer a HUD mask;
exact GUI fragments exclude full-screen vignette modulation.

The comprehensive fixture isolates its timed settings assertion from additional physical input
only after checking and pressing the real active button. The test-only GUI mixin is not included
in normal MetalMod. Menu return explicitly restores the owned test window so a hidden title
screen cannot be mistaken for an interpolation failure. Visible test windows may join Spaces
alongside the normal full-screen client; no user window preferences are changed.
