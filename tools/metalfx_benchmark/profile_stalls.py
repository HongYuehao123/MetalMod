#!/usr/bin/env python3
"""Attribute recorded slow-frame CPU intervals; do not infer total GPU time from batch samples."""
import argparse,csv,json,math,statistics
from pathlib import Path


def correlation(rows,a,b):
    x=[int(r[a]) for r in rows];y=[int(r[b]) for r in rows]
    mx=statistics.mean(x);my=statistics.mean(y)
    xx=sum((v-mx)**2 for v in x);yy=sum((v-my)**2 for v in y)
    return sum((v-mx)*(w-my) for v,w in zip(x,y))/math.sqrt(xx*yy) if xx*yy else None


def analyze(folder,stage=None):
    with (folder/'frames.csv').open() as file:rows=list(csv.DictReader(file))
    rows=[r for r in rows if r['paused']=='0' and r['screen_open']=='0' and r['window_active']=='1'
          and (stage is None or int(r['route_stage'])==stage)]
    cut=sorted(int(r['frame_ns']) for r in rows)[math.ceil(len(rows)*.95)-1]
    groups={'remaining95':[r for r in rows if int(r['frame_ns'])<cut],
            'slowest5':[r for r in rows if int(r['frame_ns'])>=cut]}
    columns=('frame_ns','command_buffer_create_ns','commit_ns','drawable_wait_ns','queue_wait_ns',
             'fence_wait_ns','sr_gpu_ns','draws','submissions','gc_reported_ms')
    return {'frames':len(rows),'stage':stage,'threshold_ns':cut,
            'groups':{name:{c:statistics.mean(int(r[c]) for r in samples) for c in columns}
                      for name,samples in groups.items()},
            'frame_create_correlation':correlation(rows,'frame_ns','command_buffer_create_ns')}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('root',type=Path)
    parser.add_argument('--stage',type=int);args=parser.parse_args();result={}
    comparison=args.root.parents[1]/'comparison.json'
    if comparison.exists():
        metadata=json.loads(comparison.read_text())
        if metadata.get('diagnostic_only') or metadata.get('gpu_stage_timing'):
            raise ValueError('diagnostic GPU profiling is not a clean CPU stall comparison')
    for line in (args.root/'benchmark-index.tsv').read_text().splitlines():
        name,folder,_=line.split('\t',2)
        capture=args.root/folder
        if (capture/'REJECTED.txt').exists() or (capture/'INSTRUMENTED.txt').exists():continue
        result[name]=analyze(capture,args.stage)
    print(json.dumps(result,indent=2))
