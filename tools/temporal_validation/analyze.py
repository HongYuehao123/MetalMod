#!/usr/bin/env python3
"""Analyze copied-world temporal image sequences; requires Pillow and NumPy."""
import argparse
import json
from pathlib import Path
import re
import numpy as np
from PIL import Image, ImageFilter, ImageDraw

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('validation', type=Path)
args = parser.parse_args()
root = args.validation.resolve()
files = sorted(root.glob('10a-static-sequence-*.png'))
if len(files) != 16:
    raise SystemExit('Expected one complete 16-image static jitter cycle')

def gray(path):
    return np.asarray(Image.open(path).convert('L').filter(ImageFilter.GaussianBlur(1.5)), dtype=float)

reference = gray(files[0])
gy, gx = np.gradient(reference)
mask = gx*gx + gy*gy > 1
if mask.sum() < 100:
    raise SystemExit('Static ROI has insufficient edges for registration')
design = np.stack([gx[mask], gy[mask], np.ones(mask.sum())], axis=1)

def fit(image):
    return np.linalg.lstsq(design, (image-reference)[mask], rcond=None)[0][:2]

shifts = [fit(gray(path)).tolist() for path in files]
control_x = fit(np.roll(reference, 1, axis=1)).tolist()
control_y = fit(np.roll(reference, 1, axis=0)).tolist()
samples = []
for path in files:
    text = path.with_suffix('.txt').read_text()
    values = re.findall(r'jitter[XY]=([^,]+)', text)
    samples.append(tuple(float(value) for value in values))
spread = np.ptp(shifts, axis=0).tolist()
calibrated = abs(control_x[0]+1) < .2 and abs(control_y[1]+1) < .2
stable = calibrated and len(set(samples)) == 16 and max(spread) < .25
result = {
    'static': {'frames': len(files), 'unique_jitter_samples': len(set(samples)),
               'registration': 'edge-gradient least squares after 1.5-pixel Gaussian low-pass; additive brightness fitted',
               'shifts_xy_output_pixels': shifts, 'range_xy_output_pixels': spread,
               'one_pixel_x_control': control_x, 'one_pixel_y_control': control_y,
               'pass': stable,
               'limit': 'Measures coherent image movement in this opaque ROI; does not measure all alias shimmer or transient content'},
    'quality': {},
}
quality = [root/f'10{letter}-{mode}-quality-00.png' for letter, mode in [('n','native'),('o','spatial'),('p','temporal')]]
if all(path.exists() for path in quality):
    native = np.asarray(Image.open(quality[0]).convert('RGB'), dtype=float)
    canvas = Image.new('RGB', (512*3, 316))
    draw = ImageDraw.Draw(canvas)
    for index, path in enumerate(quality):
        image = Image.open(path).convert('RGB')
        canvas.paste(image, (index*512, 0))
        draw.text((index*512+4, 290), path.name, fill='white')
        data = np.asarray(image, dtype=float)
        result['quality'][path.name] = {'rmse_rgb8_vs_native': float(np.sqrt(np.mean((data-native)**2))),
                                       'mean_rgb8_bias_vs_native': (data-native).mean(axis=(0,1)).tolist()}
    result['quality']['limit'] = 'One frozen scene at strength 50; reference aliasing means RMSE alone cannot rank perceived quality; readback frames are not performance samples'
    canvas.save(root/'quality-comparison.png')
(root/'image-analysis.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps({'stable': stable, 'range_xy_pixels': spread, 'quality': result['quality']}, indent=2))
raise SystemExit(0 if stable else 1)
