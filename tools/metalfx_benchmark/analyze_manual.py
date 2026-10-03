#!/usr/bin/env python3
"""Analyze a passive manual capture; align frame/pose samples by matching asynchronous GPU telemetry."""
import argparse,csv,json,statistics,math
from pathlib import Path
def summary(xs):
    if not xs:return {}
    ys=sorted(xs)
    def pct(q):return ys[min(len(ys)-1,math.ceil(len(ys)*q)-1)]
    return dict(mean=statistics.mean(xs),p50=pct(.5),p95=pct(.95),p99=pct(.99),max=max(xs))

def corr(rows,key):
    x=[int(r['frame_ns']) for r in rows];y=[int(r[key]) for r in rows]
    mx,my=statistics.mean(x),statistics.mean(y)
    xx=sum((v-mx)**2 for v in x);yy=sum((v-my)**2 for v in y)
    return sum((v-mx)*(w-my) for v,w in zip(x,y))/math.sqrt(xx*yy) if xx*yy else None

def read(path):
    with path.open() as f:return list(csv.DictReader(f))

def analyze(folder):
    frames=read(folder/'frames.csv');motion=read(folder/'manual-motion.csv')
    candidates=range(max(0,len(motion)-len(frames)-30),len(motion)-len(frames)+1)
    matches,offset=max((sum(f['sr_gpu_ns']==m['helper_gpu_ns'] for f,m in zip(frames,motion[o:])),o) for o in candidates)
    if matches!=len(frames):raise ValueError('pose/frame alignment is not exact')
    pairs=list(zip(frames,motion[offset:offset+len(frames)]))
    focused=[f for f,m in pairs if f['paused']=='0' and f['screen_open']=='0' and f['window_active']=='1']
    origin=int(pairs[0][1]['time_ns'])-int(pairs[0][0]['elapsed_ns']);end=int(pairs[-1][1]['time_ns'])
    shown=sorted(set(int(x['presented_ns']) for x in read(folder/'display.csv') if origin<=int(x['presented_ns'])<=end))
    intervals=[(b-a)/1e6 for a,b in zip(shown,shown[1:])]
    groups={}
    for name,predicate in [('settled-low-draw',lambda f:int(f['elapsed_ns'])>10e9 and int(f['draws'])<4000),
                           ('settled-high-draw',lambda f:int(f['elapsed_ns'])>10e9 and int(f['draws'])>6000)]:
        rows=[f for f in focused if predicate(f)]
        groups[name]={'frames':len(rows),'fps':1000/statistics.mean(int(f['frame_ns'])/1e6 for f in rows)}
        for k in ['frame_ns','sr_gpu_ns','drawable_wait_ns','command_buffer_create_ns','draws','submissions','buffer_upload_bytes']:
            groups[name][k]=statistics.mean(int(f[k]) for f in rows)
        groups[name]['other_cpu_ms']=statistics.mean((int(f['frame_ns'])-int(f['drawable_wait_ns'])-int(f['command_buffer_create_ns']))/1e6 for f in rows)
    resets=[]
    for i,(f,m) in enumerate(pairs):
        prior=motion[offset+i-1]
        if m['resets']!=prior['resets']:
            resets.append({'elapsed_s':int(f['elapsed_ns'])/1e9,'yaw_delta':(float(m['yaw'])-float(prior['yaw'])+180)%360-180,
                           'pitch_delta':float(m['pitch'])-float(prior['pitch']),'frame_ms':int(f['frame_ns'])/1e6})
    ms=[int(f['frame_ns'])/1e6 for f in focused]
    return {'frames':len(frames),'focused_gameplay_frames':len(focused),'alignment_matches':matches,'motion_sample_offset':offset,
            'fps':1000/statistics.mean(ms),'frame_ms':summary(ms),'helper_gpu_ms':summary([int(f['sr_gpu_ns'])/1e6 for f in focused]),
            'display_interval_ms':summary(intervals),'display_intervals':len(intervals),'display_intervals_over25ms':sum(x>25 for x in intervals),
            'display_hz':1000/statistics.mean(intervals),'shader_compiles':sum(int(f['pipeline_compiles']) for f in focused),
            'sr_failures_delta':int(frames[-1]['sr_failures'])-int(frames[0]['sr_failures']),
            'published_lights_max':max(int(f['light_published']) for f in focused),'groups':groups,
            'correlations':{k:corr(focused,k) for k in ['draws','sr_gpu_ns','drawable_wait_ns','command_buffer_create_ns']},
            'history_resets':resets,'helper_gpu_is_not_total_frame_gpu':True}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('folder',type=Path);a=p.parse_args();print(json.dumps(analyze(a.folder),indent=2))
