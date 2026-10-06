#!/usr/bin/env python3
"""Compare completed GPU frames in a bounded present/offscreen ABBA experiment."""
import argparse
import csv
import json
from pathlib import Path
import statistics


def summarize(game):
    metadata=json.loads((game/'comparison.json').read_text())
    if not metadata.get('offscreen_comparison') or not metadata.get('native_only'):
        raise ValueError('requires explicit native-only offscreen experiment')
    root=game/'debug/metalmod'
    index=[line.split('\t',2) for line in (root/'benchmark-index.tsv').read_text().splitlines()]
    if [line[0] for line in index]!=['present01','offscreen02','offscreen03','present04']:
        raise ValueError('requires complete ordered ABBA captures')
    runs=[]
    for name,folder,_ in index:
        capture=root/folder
        if (capture/'REJECTED.txt').exists(): raise ValueError('rejected capture')
        with (capture/'frames.csv').open() as stream: rows=list(csv.DictReader(stream))
        if not rows: raise ValueError('empty capture')
        for row in rows:
            if (row['window_active'],row['paused'],row['screen_open'])!=('1','0','0'):
                raise ValueError('interrupted capture')
            if any(int(row[key]) for key in ('pipeline_compiles','census_active','sr_failures',
                                             'sr_recoveries','sr_requested','sr_encodes','sr_effective')):
                raise ValueError('non-native, compilation or diagnostic work in capture')
        dimensions={(int(row['width']),int(row['height'])) for row in rows}
        if len(dimensions)!=1: raise ValueError('resized capture')
        completion=json.loads((capture/'gpu-completion.json').read_text())
        if completion['submitted']!=completion['completed'] or completion['max_in_flight']!=2:
            raise ValueError('uncompleted GPU work or wrong bound')
        if completion['offscreen']!=name.startswith('offscreen'): raise ValueError('mode mismatch')
        seconds=completion['elapsed_ns']/1e9
        if not 29<seconds<32: raise ValueError('unexpected measurement duration')
        cpu=json.loads((capture/'render-thread-cpu.json').read_text())
        average=lambda key:statistics.mean(int(row[key]) for row in rows)
        runs.append(dict(name=name,capture=folder,**completion,width=next(iter(dimensions))[0],
                         height=next(iter(dimensions))[1],cpu_ms=cpu['mean_ms'],draws=average('draws'),
                         frame_loop_fps=1e9/average('frame_ns'),drawable_wait_ms=average('drawable_wait_ns')/1e6))
    if len({(r['width'],r['height']) for r in runs})!=1: raise ValueError('unmatched output resolution')
    if max(r['draws'] for r in runs)/min(r['draws'] for r in runs)>1.01:
        raise ValueError('draw workload differs by more than 1%')
    present=statistics.mean(r['completed_fps'] for r in runs if not r['offscreen'])
    offscreen=statistics.mean(r['completed_fps'] for r in runs if r['offscreen'])
    return dict(metadata=metadata,runs=runs,present_completed_fps=present,
                offscreen_completed_fps=offscreen,throughput_increase_percent=100*(offscreen/present-1),
                scope='Same fixed scene; two-frame GPU bound and one completion fence per frame in both modes. '
                      'Offscreen omits drawable acquisition, final display blit and presentation. '
                      'Completed FPS uses a separate ~30s clock including final drain; F8 boundaries differ slightly. '
                      'This is not displayed FPS or a production optimization.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('game',type=Path)
    print(json.dumps(summarize(parser.parse_args().game),indent=2))
