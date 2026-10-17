#!/usr/bin/env python3
"""Compare optional Stage 1 paths off/on/on/off in four fresh, matched JVMs."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import statistics
import zipfile
from analyze_submission import summarize

KEYS = ('metalmod.deferredClears', 'metalmod.encoderContinuation', 'metalmod.nativeTexelBuffers')


def addon_content_hash(root):
    digest = hashlib.sha256()
    with zipfile.ZipFile(root / 'mods/movement-diagnostic.jar') as jar:
        for name in sorted(jar.namelist()):
            digest.update(name.encode() + b'\0')
            digest.update(hashlib.sha256(jar.read(name)).digest())
    return digest.hexdigest()


def compare(roots):
    if len(roots) != 4:
        raise ValueError('four fresh-JVM captures required')
    results = [summarize(root / 'debug/metalmod') for root in roots]
    addon_hashes = [addon_content_hash(root) for root in roots]
    if len(set(addon_hashes)) != 1:
        raise ValueError('mismatched benchmark add-on contents')
    baseline = results[0]['comparison']
    for result, enabled in zip(results, (False, True, True, False)):
        metadata = result['comparison']
        for key in ('jar_sha256', 'anchor', 'source_world', 'source_level_sha256', 'anchor_route_sha256',
                    'static_camera', 'gpu_stage_timing', 'native_only', 'render_distance', 'renderer',
                    'requested_display', 'initial_logical_size', 'extra_mod_sha256', 'sodium_options_sha256'):
            if metadata.get(key) != baseline.get(key):
                raise ValueError('mismatched comparison field: ' + key)
        if not metadata.get('static_camera') or not metadata.get('native_only') or not metadata.get('renderer'):
            raise ValueError('requires static, native-only fixed-renderer captures')
        if metadata.get('resource_telemetry') or metadata.get('gpu_stage_timing') or metadata.get('offscreen_comparison'):
            raise ValueError('requires ordinary uninstrumented captures')
        expected = dict(baseline.get('backend_properties', {}))
        expected.update({key: str(enabled).lower() for key in KEYS})
        if metadata.get('backend_properties') != expected:
            raise ValueError('expected only the three Stage 1 switches in off/on/on/off order')
        if len(result['runs']) != 1:
            raise ValueError('one native capture per JVM required')
    runs = [result['runs'][0] for result in results]
    if len({(run['width'], run['height']) for run in runs}) != 1:
        raise ValueError('mismatched actual output dimensions')
    if any(run['render_thread_cpu_ms'] is None or not run['cpu_samples'] or run['cpu_samples'] <= 0 for run in runs):
        raise ValueError('missing render-thread CPU samples')
    draws = [run['draws'] for run in runs]
    if (max(draws) - min(draws)) / statistics.mean(draws) > .01:
        raise ValueError('draw workload differs by more than 1%')
    for root, run in zip(roots, runs):
        controls = json.loads((root / 'debug/metalmod' / run['capture'] / 'benchmark-controls.json').read_text())
        if controls.get('debug_overlay_guard') is not True or controls.get('debug_overlay_frames') != 0:
            raise ValueError('missing or interrupted debug-overlay control')
        with (root / 'debug/metalmod' / run['capture'] / 'frames.csv').open() as stream:
            rows = list(csv.DictReader(stream))
        run['render_passes'] = statistics.mean(int(row['render_passes']) for row in rows)
    groups = {}
    for label, indices in [('off', (0, 3)), ('on', (1, 2))]:
        group = [runs[index] for index in indices]
        groups[label] = {key: statistics.mean(run[key] for run in group)
                         for key in ('mean_ms', 'p95_ms', 'p99_ms', 'render_thread_cpu_ms', 'submissions', 'render_passes', 'draws')}
        groups[label]['fps'] = 1000 / groups[label]['mean_ms']
    return dict(launches=results, groups=groups, benchmark_addon_content_sha256=addon_hashes[0],
                change_percent={key: 100 * (groups['on'][key] / groups['off'][key] - 1)
                                for key in groups['off']},
                scope='Optional-path group, fresh-JVM off/on/on/off; same final jar and fixed copied-world camera. '
                      'Two 30-second captures per setting. Frame intervals include presentation; '
                      'this does not isolate individual switches or measure GPU duration, tile bandwidth or input latency.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('roots', type=Path, nargs=4)
    print(json.dumps(compare(parser.parse_args().roots), indent=2))
