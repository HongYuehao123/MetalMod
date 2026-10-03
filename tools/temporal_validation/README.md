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
