#!/usr/bin/env python3
"""Compare clean internal/external captures at identical actual output dimensions."""
import argparse
import json
from pathlib import Path
import re
from analyze_submission import summarize


def compare(internal, external):
    roots={'internal':internal,'external':external}
    results={name:summarize(root/'debug/metalmod') for name,root in roots.items()}
    baseline=results['internal']['comparison']
    for name,result in results.items():
        metadata=result['comparison']
        if metadata.get('requested_display')!=name:
            raise ValueError('incorrect requested display: '+name)
        for key in ('renderer','jar_sha256','extra_mod_sha256','sodium_options_sha256',
                    'anchor','source_world','static_camera','gpu_stage_timing','native_only','render_distance'):
            if metadata.get(key)!=baseline.get(key):
                raise ValueError('mismatched comparison field: '+key)
        screens=re.findall(r'hosting screen=(.*?) displayID=(\d+) maximumHz=(\d+).*?builtIn=(\d+)',
                           (roots[name]/'console.log').read_text())
        if not screens or len(set(screens))!=1 or any(s[3]!=('1' if name=='internal' else '0') for s in screens):
            raise ValueError('missing or inconsistent hosting-screen snapshots: '+name)
        result['hosting_screen']=dict(name=screens[0][0],display_id=int(screens[0][1]),
                                      maximum_hz=int(screens[0][2]),snapshots=len(screens))
    modes={}
    selected=[('native',0)] if baseline.get('native_only') else [('native',0),('sr25',1)]
    for mode,effective in selected:
        runs={name:next(r for r in result['runs'] if r['name'].endswith('-'+mode))
              for name,result in results.items()}
        if len({(r['width'],r['height']) for r in runs.values()})!=1:
            raise ValueError('mismatched actual output resolution')
        if any(r['effective_modes']!=[effective] for r in runs.values()):
            raise ValueError('incorrect reconstruction mode')
        modes[mode]={'internal':runs['internal'],'external':runs['external'],
                     'rendered_fps_change_percent':100*(runs['internal']['fps']/runs['external']['fps']-1)}
    return {'launches':results,'modes':modes,'scope':
            'One fresh-JVM launch per display, two clean 30s native/spatial captures each. '
            'Same actual output resolution and production artifacts; exploratory pair, not ABBA. '
            'Hosting-screen snapshots are outside captures. Display timestamp coverage must be '
            'reported separately; rendered FPS is not input latency or displayed update rate.'}


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('internal',type=Path);p.add_argument('external',type=Path);a=p.parse_args()
    print(json.dumps(compare(a.internal,a.external),indent=2))
