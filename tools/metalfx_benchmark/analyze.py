#!/usr/bin/env python3
"""Summarize exported F8 runs, rejecting contaminated frames and changed output dimensions."""
import argparse
import csv
import json
import math
import statistics
from pathlib import Path


def summarize(rows):
    values = sorted(int(row['frame_ns']) / 1e6 for row in rows)
    if not values:
        return {}
    gpu = [int(row['sr_gpu_ns']) / 1e6 for row in rows if int(row['sr_gpu_ns']) >= 0]
    return {
        'n': len(values), 'mean_ms': statistics.mean(values),
        'median_ms': statistics.median(values),
        'p95_ms': values[math.ceil(len(values) * .95) - 1],
        'p99_ms': values[math.ceil(len(values) * .99) - 1],
        'frames_over_33ms': sum(value > 33.3 for value in values),
        'frames_over_50ms': sum(value > 50 for value in values),
        'fps': 1000 / statistics.mean(values),
        # Completed samples may repeat across CPU frames; this is not frame GPU time.
        'gpu_batch_median_ms': statistics.median(gpu) if gpu else None,
        'draws_median': statistics.median(int(row['draws']) for row in rows),
        # F8 records floored integer positions, not precise route coordinates.
        'position_floor_mean': [statistics.mean(int(row[key]) for row in rows)
                                for key in ('player_x', 'player_y', 'player_z')],
    }


def analyze(root):
    records = []
    output_size = None
    for line in (root / 'benchmark-index.tsv').read_text().splitlines():
        name, directory, configuration = line.split('\t', 2)
        folder = root / directory
        with (folder / 'frames.csv').open() as file:
            rows = list(csv.DictReader(file))
        if not rows:
            raise ValueError(f'{name}: empty capture')
        if output_size is None:
            output_size = (rows[0]['width'], rows[0]['height'])
        valid = [row for row in rows if row['paused'] == '0' and row['screen_open'] == '0'
                 and row['window_active'] == '1' and row['census_active'] == '0'
                 and (row['width'], row['height']) == output_size]
        first = {}
        for row in rows:
            first.setdefault(row['route_stage'], int(row['elapsed_ns']))
        settled = [row for row in valid if
                   int(row['elapsed_ns']) - first[row['route_stage']] >= 2_000_000_000]
        stages = {stage: summarize([row for row in settled if int(row['route_stage']) == stage])
                  for stage in sorted({int(row['route_stage']) for row in settled})}
        record = {
            'name': name, 'folder': directory, 'configuration': configuration,
            'total': len(rows), 'valid': len(valid),
            'focus_bad': sum(row['window_active'] != '1' for row in rows),
            'pipeline_compiles': sum(int(row['pipeline_compiles']) for row in rows),
            'whole': summarize(valid), 'settled_stages': stages,
            'output_size': output_size,
            'effective': sorted({row['sr_effective'] for row in rows}),
            'scene_sizes': sorted({(row['sr_scene_width'], row['sr_scene_height']) for row in rows}),
            'failures': sorted({int(row['sr_failures']) for row in rows}),
            'recoveries': sorted({int(row['sr_recoveries']) for row in rows}),
        }
        record['accepted'] = (len(valid) == len(rows) and record['pipeline_compiles'] == 0
                              and record['failures'] == [0] and record['recoveries'] == [0])
        records.append(record)
        whole = record['whole']
        print(name, 'accepted=', record['accepted'], 'frames=', len(valid), '/', len(rows),
              'mean_ms=', round(whole.get('mean_ms', 0), 3),
              'fps=', round(whole.get('fps', 0), 1),
              'batch_ms=', whole.get('gpu_batch_median_ms'))
    return records


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path, help='isolated instance debug/metalmod directory')
    args = parser.parse_args()
    (args.root / 'analysis.json').write_text(json.dumps(analyze(args.root), indent=2) + '\n')
