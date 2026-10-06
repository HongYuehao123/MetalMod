#!/usr/bin/env python3
"""Compare four fresh-JVM vanilla/Sodium/Sodium/vanilla launches in one matched scene."""
import argparse
import json
import statistics
from pathlib import Path
from analyze_submission import summarize

def compare(roots):
    results = [summarize(root / 'debug/metalmod') for root in roots]
    if [r['comparison'].get('renderer') for r in results] != ['vanilla','sodium','sodium','vanilla']:
        raise ValueError('expected fresh-JVM vanilla/Sodium/Sodium/vanilla order')
    metadata = results[0]['comparison']
    for result in results:
        for key in ('jar_sha256','anchor','source_world','static_camera','gpu_stage_timing','native_only','render_distance'):
            if result['comparison'].get(key) != metadata.get(key):
                raise ValueError('mismatched comparison field: '+key)
    if metadata.get('gpu_stage_timing'):
        raise ValueError('instrumented throughput comparison')
    changes = {}
    modes=[('native',0)] if metadata.get('native_only') else [('native',0),('sr25',1)]
    for mode, effective in modes:
        groups = {}
        dimensions = set()
        for renderer in ['vanilla','sodium']:
            rows = [run for result in results for run in result['runs'] if run['name']==renderer+'-'+mode]
            if len(rows)!=2 or any(run['effective_modes'] != [effective] for run in rows):
                raise ValueError('missing capture or incorrect reconstruction mode')
            dimensions.update((r['width'],r['height']) for r in rows)
            mean = statistics.mean(r['mean_ms'] for r in rows)
            groups[renderer] = dict(mean_ms=mean, fps=1000/mean,
                mean_capture_p95_ms=statistics.mean(r['p95_ms'] for r in rows),
                mean_capture_p99_ms=statistics.mean(r['p99_ms'] for r in rows),
                render_thread_cpu_ms=statistics.mean(r['render_thread_cpu_ms'] for r in rows),
                draws=statistics.mean(r['draws'] for r in rows),ffi_calls=statistics.mean(r['ffi_calls'] for r in rows))
        if len(dimensions)!=1: raise ValueError('mismatched output resolution')
        a,b=groups['vanilla'],groups['sodium']
        changes[mode]=dict(**groups,fps_change_percent=100*(b['fps']/a['fps']-1),
            cpu_reduction_percent=100*(1-b['render_thread_cpu_ms']/a['render_thread_cpu_ms']))
    return dict(launches=results,changes=changes,scope='One fixed camera in one copied dense world; '
        'two uninstrumented 30s captures per renderer and mode, in fresh-JVM ABBA order. '
        'Frame intervals include macOS presentation; quality and movement need separate validation.')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('roots',type=Path,nargs=4)
    args=parser.parse_args()
    print(json.dumps(compare(args.roots),indent=2))
