#!/usr/bin/env python3
"""Validate and summarize the two Stage 0 fixtures, attribution and completion controls."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import zipfile

from analyze_submission import summarize as ordinary_summary
from analyze_offscreen import summarize as completion_summary


def rows(path):
    with path.open() as stream:
        return list(csv.DictReader(stream))


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def validate_frames(capture):
    data = rows(capture / 'frames.csv')
    if not data or (capture / 'REJECTED.txt').exists():
        raise ValueError('empty or rejected capture: ' + str(capture))
    for row in data:
        if (row['window_active'], row['paused'], row['screen_open']) != ('1', '0', '0'):
            raise ValueError('interrupted capture: ' + str(capture))
        if any(int(row[key]) != 0 for key in ('pipeline_compiles', 'census_active', 'sr_requested',
                                             'sr_effective', 'sr_encodes', 'sr_failures', 'sr_recoveries')):
            raise ValueError('not clean native-only gameplay: ' + str(capture))
    if len({(r['width'], r['height']) for r in data}) != 1:
        raise ValueError('resized capture: ' + str(capture))
    seconds = sum(int(r['frame_ns']) for r in data) / 1e9
    if not 29 < seconds < 32:
        raise ValueError('not a complete 30-second capture: ' + str(capture))
    return data


def frame_metrics(data):
    times = [int(r['frame_ns']) for r in data]
    return dict(frames=len(data), rendered_fps=1e9 / statistics.mean(times),
                p95_ms=percentile(times, .95) / 1e6, p99_ms=percentile(times, .99) / 1e6,
                mean_draws=statistics.mean(int(r['draws']) for r in data),
                width=int(data[0]['width']), height=int(data[0]['height']))


def presentation_metrics(capture):
    data = [int(r['presented_ns']) for r in rows(capture / 'display.csv')]
    stamps = sorted({t for t in data if t > 0})
    if len(stamps) < 2:
        return dict(events_per_second=0, unique_events=len(stamps), zero_timestamps=data.count(0), callback_samples=len(data))
    return dict(events_per_second=(len(stamps) - 1) * 1e9 / (stamps[-1] - stamps[0]),
                unique_events=len(stamps), zero_timestamps=data.count(0), callback_samples=len(data),
                scope='Nonzero drawable timestamps; includes countdown/export tail; not physical refresh or latency.')


def payload_hash(path):
    # ZIP metadata timestamps differ when the diagnostic addon is repackaged for each JVM.
    digest = hashlib.sha256()
    with zipfile.ZipFile(path) as jar:
        for name in sorted(jar.namelist()):
            digest.update(name.encode())
            digest.update(hashlib.sha256(jar.read(name)).digest())
    return digest.hexdigest()


def validate_launch(game):
    meta = json.loads((game / 'comparison.json').read_text())
    log = (game / 'console.log').read_text()
    if not (game / 'debug/metalmod/motion-complete.txt').exists():
        raise ValueError('launch did not complete: ' + str(game))
    health_keys = ('privateCpuAccess', 'failures', 'pipelineFailures', 'unboundBindings',
                   'missingVertexAttributes', 'slotCollisions', 'bindingKindMismatches')
    health = {key: [int(v) for v in re.findall(r'\b' + key + r'=(\d+)', log)] for key in health_keys}
    if any(not values or max(values) != 0 for values in health.values()):
        raise ValueError('missing or nonzero native health counters: ' + str(game))
    screens = re.findall(r'hosting screen=(.*?) displayID=(\d+) maximumHz=(\d+).*?builtIn=(\d+)', log)
    if not screens or len(set(screens)) != 1 or screens[0][3] != '0':
        raise ValueError('missing or changed external hosting display: ' + str(game))
    settings = dict(line.split('=', 1) for line in (game / 'config/metalmod.properties').read_text().splitlines()
                    if '=' in line and not line.startswith('#'))
    if any(settings.get(key, '').strip().lower() != 'false' for key in
           ('enableFrameGeneration', 'enableTemporalUpscaling', 'enableSuperResolution')):
        raise ValueError('FG/temporal/SR enabled in copied config: ' + str(game))
    native_health = {key: max(values) for key, values in health.items()}
    mods = {p.name: payload_hash(p) for p in sorted((game / 'mods').glob('*.jar'))}
    return meta, dict(hosting_screen=screens[0], native_health=native_health, mod_payload_sha256=mods)


def summarize(root):
    result = {}
    for prefix, fixture in (('surface', 'surface-rd16'), ('dense', 'dense-rd32')):
        expected = None
        entries = []
        for suffix in ('baseline-1', 'baseline-2', 'attribution', 'completion'):
            game = root / (prefix + '-' + suffix)
            meta, evidence = validate_launch(game)
            if meta['stage0_fixture'] != fixture:
                raise ValueError('incorrect fixture: ' + str(game))
            matching = {key: meta[key] for key in ('jar_sha256', 'source_world', 'source_level_sha256',
                        'anchor_route_sha256', 'anchor', 'renderer', 'render_distance', 'static_camera',
                        'vsync', 'native_only', 'frame_generation', 'temporal_upscaling')}
            matching['mod_payload_sha256'] = evidence['mod_payload_sha256']
            matching['hosting_screen'] = evidence['hosting_screen']
            if expected is None:
                expected = matching
            if matching != expected:
                raise ValueError('unmatched source/settings/artifacts/display: ' + str(game))
            captures = []
            for line in (game / 'debug/metalmod/benchmark-index.tsv').read_text().splitlines():
                name, folder, _ = line.split('\t', 2)
                capture = game / 'debug/metalmod' / folder
                data = validate_frames(capture)
                metrics = frame_metrics(data)
                if (metrics['width'], metrics['height']) != (5120, 2664):
                    raise ValueError('unmatched canonical pixels: ' + str(capture))
                item = dict(name=name, capture=folder, **metrics, presentation=presentation_metrics(capture))
                if suffix == 'attribution':
                    if not meta.get('resource_telemetry') or not (capture / 'INSTRUMENTED.txt').exists():
                        raise ValueError('unmarked resource diagnostic: ' + str(capture))
                    resources = rows(capture / 'resources.csv')
                    if not 0 < len(resources) <= 513:
                        raise ValueError('missing or unbounded resource rows')
                    for row in resources:
                        if int(row['calls']) <= 0 or min(int(row['logical_bytes']), int(row['cpu_api_ns'])) < 0:
                            raise ValueError('invalid resource counters')
                    declared_clears = sum(int(r['calls']) for r in resources if r['operation'].startswith('clear.'))
                    native_clears = sum(int(r['clears']) for r in data)
                    if declared_clears != native_clears:
                        raise ValueError('resource/native clear totals differ')
                    if not any(r['label'] == 'Lightmap' and r['operation'] == 'pass.begin' for r in resources):
                        raise ValueError('lightmap pass attribution missing')
                    if not any('/atlas/' in r['label'] and r['operation'] == 'pass.begin' for r in resources):
                        raise ValueError('sprite atlas attribution missing')
                    item['resources'] = dict(rows=len(resources), native_clear_calls=native_clears,
                        attributed_clear_calls=declared_clears,
                        cloud_upload_bytes=sum(int(r['logical_bytes']) for r in resources
                                               if r['operation'] == 'texture.texel-emulation' and r['format'] == 'R8_SINT'),
                        top_cpu_api_rows=sorted(resources, key=lambda r: int(r['cpu_api_ns']), reverse=True)[:6])
                captures.append(item)
            if suffix.startswith('baseline'):
                ordinary_summary(game / 'debug/metalmod')
                if meta.get('resource_telemetry') or any((game / 'debug/metalmod' / c['capture'] / 'resources.csv').exists() for c in captures):
                    raise ValueError('resource instrumentation in ordinary baseline')
            completion = completion_summary(game, moving_reference=fixture == 'surface-rd16') if suffix == 'completion' else None
            rejected = [p.parent.name for p in (game / 'debug/metalmod').glob('*/REJECTED.txt')]
            entries.append(dict(launch=game.name, evidence=evidence, captures=captures,
                                rejected_attempts=rejected, completion=completion))
        result[fixture] = dict(manifest=expected, launches=entries)
    return dict(fixtures=result, scope='Vanilla Metal 26.2 baseline suite, not an optimization A/B or a Sodium benchmark.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.root), indent=2))
