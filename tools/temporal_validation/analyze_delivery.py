#!/usr/bin/env python3
"""Verify final SDR content, separately from GPU encodes and drawable timestamps.

Run after --frame-generation --delivery-check. Requires NumPy and Pillow.
Readbacks come from the same completed frame boundary; never run in production.
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("validation", type=Path)
args = parser.parse_args()
results = []
for name in ("limited-native-camera", "limited-spatial-camera"):
    root = args.validation
    w, h, iw, ih = map(int, (root / f"{name}-dimensions.txt").read_text().split())
    # Sample input-pixel centres so reduced motion/masks map to exactly the same output region.
    iy, ix = np.mgrid[2:ih:4, 2:iw:4]
    y = np.minimum(((iy + 0.5) * h / ih).astype(int), h - 1)
    x = np.minimum(((ix + 0.5) * w / iw).astype(int), w - 1)

    def diagnostic(role, dtype, width, height, channels=1):
        return np.fromfile(root / f"{name}-diagnostic-{role}.bin", dtype=dtype).reshape(height, width, channels)

    generated = diagnostic(0, np.float16, w, h, 4)[y, x, :3].astype(np.float32)
    current_image = diagnostic(1, np.float16, w, h, 4)
    current = current_image[y, x, :3].astype(np.float32)
    previous = diagnostic(2, np.float16, w, h, 4)[y, x, :3].astype(np.float32)
    hand = diagnostic(4, np.uint8, w, h)[y, x, 0] > 0
    motion = diagnostic(5, np.float16, iw, ih, 2)[iy, ix].astype(np.float32) * [w / iw, h / ih]
    coverage = diagnostic(6, np.uint8, iw, ih)[iy, ix, 0] > 0
    gui = diagnostic(7, np.uint8, w, h)[y, x, 0] > 0
    with Image.open(root / f"{name}-real.png") as image:
        real = np.asarray(image.convert("RGB"))[y, x].astype(np.int16)
    with Image.open(root / f"{name}-generated.png") as image:
        final = np.asarray(image.convert("RGB"))[y, x].astype(np.int16)
    moving = (np.linalg.norm(motion, axis=-1) > 0.75) & (np.max(np.abs(current - previous), axis=-1) > 0.015)
    moving &= ~hand & ~coverage & ~gui
    count = int(moving.sum())
    raw_changed = float((np.max(np.abs(generated - current), axis=-1)[moving] > 0.002).mean()) if count else 0
    final_changed = float((np.max(np.abs(final - real), axis=-1)[moving] > 2).mean()) if count else 0
    # Fit output to current-world reprojection at the two endpoints and the midpoint.
    # A different image alone does not prove an intermediate pose; repeated previous
    # colour, unrelated blur and colour flashes must not establish this check.
    phase_errors = {}
    for phase in (0.0, 0.5, 1.0):
        sx = np.clip(x - motion[..., 0] * phase, 0, w - 2)
        sy = np.clip(y - motion[..., 1] * phase, 0, h - 2)
        bx, by = sx.astype(int), sy.astype(int)
        fx, fy = (sx - bx)[..., None], (sy - by)[..., None]
        expected = (current_image[by, bx, :3] * (1 - fx) * (1 - fy)
                    + current_image[by, bx + 1, :3] * fx * (1 - fy)
                    + current_image[by + 1, bx, :3] * (1 - fx) * fy
                    + current_image[by + 1, bx + 1, :3] * fx * fy)
        phase_errors[phase] = float(np.abs(expected - generated)[moving].mean()) if count else 1
    midpoint_matches = phase_errors[0.5] < 0.8 * min(phase_errors[0.0], phase_errors[1.0])
    result = {"stage": name, "moving_world_samples": count, "raw_changed_fraction": raw_changed,
              "final_changed_fraction": final_changed, "gui_coverage_fraction": float(gui.mean()),
              "world_pose_errors": phase_errors, "midpoint_matches": midpoint_matches,
              "passed": count >= 100 and raw_changed >= 0.70 and final_changed >= 0.70 and midpoint_matches}
    results.append(result)
print(json.dumps(results, indent=2))
(args.validation / "delivery-content.json").write_text(json.dumps(results, indent=2) + "\n")
raise SystemExit(0 if all(result["passed"] for result in results) else 1)
