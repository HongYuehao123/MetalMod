#!/usr/bin/env python3
"""Summarize accepted native ABBA / spatial A/B captures from --submission-benchmark."""
import argparse
import csv
import json
import math
from pathlib import Path
import statistics


def summarize(root):
    comparison_path = root.parents[1] / 'comparison.json'
    comparison = json.loads(comparison_path.read_text()) if comparison_path.exists() else {}
    if comparison.get('diagnostic_only') or comparison.get('gpu_stage_timing'):
        raise ValueError('diagnostic GPU profiling is not a throughput comparison')
    runs = []
    excluded = []
    for line in (root / 'benchmark-index.tsv').read_text().splitlines():
        name, folder, _ = line.split('\t', 2)
        capture = root / folder
        if (capture / 'INSTRUMENTED.txt').exists():
            excluded.append(dict(name=name, capture=folder,
                                 reason=(capture / 'INSTRUMENTED.txt').read_text().strip()))
            continue
        if (capture / 'REJECTED.txt').exists():
            raise ValueError(f'rejected capture indexed: {capture}')
        with (capture / 'frames.csv').open() as stream:
            all_rows = list(csv.DictReader(stream))
        if any(row['window_active'] != '1' or row['paused'] != '0' or row['screen_open'] != '0'
               for row in all_rows):
            raise ValueError(f'paused/menu/unfocused capture: {capture}')
        rows = [row for row in all_rows if row['paused'] == '0' and row['screen_open'] == '0']
        if not rows:
            raise ValueError(f'no gameplay frames: {capture}')
        if any(int(row[key]) > 0 for row in rows
               for key in ('pipeline_compiles', 'census_active', 'sr_failures', 'sr_recoveries')):
            raise ValueError(f'compilation/census/MetalFX failure or recovery in capture: {capture}')
        if comparison.get('native_only') and any(int(row['sr_requested'])!=0 or int(row['sr_encodes'])!=0 for row in rows):
            raise ValueError(f'upscaling active in native-only performance capture: {capture}')
        if len({(row['width'], row['height']) for row in rows}) != 1:
            raise ValueError(f'resized during capture: {capture}')
        cpu_path = capture / 'render-thread-cpu.json'
        cpu = json.loads(cpu_path.read_text()) if cpu_path.exists() else {}
        times = sorted(int(row['frame_ns']) for row in rows)
        def average(key):
            return statistics.mean(int(row[key]) for row in rows)
        def percentile(p):
            return times[min(len(times)-1, max(0, math.ceil(p*len(times))-1))] / 1e6
        runs.append(dict(name=name, capture=folder, frames=len(rows), mean_ms=statistics.mean(times)/1e6,
                         fps=1e9/statistics.mean(times), p95_ms=percentile(.95), p99_ms=percentile(.99),
                         submissions=average('submissions'), create_ms=average('command_buffer_create_ns')/1e6,
                         commit_ms=average('commit_ns')/1e6, draws=average('draws'), ffi_calls=average('ffi_calls'),
                         render_thread_cpu_ms=cpu.get('mean_ms'), cpu_samples=cpu.get('samples'),
                         width=int(rows[0]['width']), height=int(rows[0]['height']),
                         effective_modes=sorted({int(row['sr_effective']) for row in rows}),
                         max_sr_failures=max(int(row['sr_failures']) for row in rows)))
    if comparison.get('renderer'):
        renderer = comparison['renderer']
        expected={renderer+'-native'} if comparison.get('native_only') else {renderer+'-native', renderer+'-sr25'}
        if excluded or {run['name'] for run in runs} != expected or len(runs) != len(expected):
            raise ValueError('renderer comparison requires its declared clean native/spatial captures')
        if comparison.get('native_only') and any(run['effective_modes']!=[0] for run in runs):
            raise ValueError('native-only capture used reconstruction')
        return dict(comparison=comparison, runs=runs)
    expected_count=4 if comparison.get('native_only') else 6
    if len(runs) + len(excluded) != expected_count:
        raise ValueError(f'expected {expected_count} completed captures, got {len(runs) + len(excluded)}')
    native_off = statistics.mean(run['mean_ms'] for run in runs if '-native-off' in run['name'])
    native_on = statistics.mean(run['mean_ms'] for run in runs if '-native-on' in run['name'])
    native_cpu_off = [run['render_thread_cpu_ms'] for run in runs
                      if '-native-off' in run['name'] and run['render_thread_cpu_ms'] is not None]
    native_cpu_on = [run['render_thread_cpu_ms'] for run in runs
                     if '-native-on' in run['name'] and run['render_thread_cpu_ms'] is not None]
    cpu_reduction = (100 * (1 - statistics.mean(native_cpu_on)/statistics.mean(native_cpu_off))
                     if native_cpu_off and native_cpu_on else None)
    return dict(comparison=comparison, runs=runs, excluded=excluded,
                native_off_captures=len(native_cpu_off), native_on_captures=len(native_cpu_on),
                native_render_thread_cpu_reduction_percent=cpu_reduction,
                native_mean_time_reduction_percent=100*(1-native_on/native_off),
                native_throughput_increase_percent=100*(native_off/native_on-1),
                scope='One copied-world camera scene; inspect comparison metadata for fixed versus moving camera. '
                      'Native ABBA only when both on and off have two accepted captures; otherwise an incomplete comparison. '
                      'Spatial A/B single pair. '
                      'Frame intervals include macOS presentation pacing; GPU frame duration is unmeasured.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path, help='debug/metalmod directory')
    args = parser.parse_args()
    print(json.dumps(summarize(args.root), indent=2))
