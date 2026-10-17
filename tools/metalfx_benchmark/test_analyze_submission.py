#!/usr/bin/env python3
"""Accept valid capture modes and reject contaminated or recovered temporal samples."""
from pathlib import Path
import csv,json,tempfile
from analyze_submission import summarize
base={key:'0' for key in ('paused','screen_open','pipeline_compiles','census_active',
    'sr_failures','sr_recoveries','sr_requested','sr_strength','sr_effective','sr_encodes',
    'sr_reason','submissions','command_buffer_create_ns','commit_ns','draws','ffi_calls')}
base.update(window_active='1',frame_ns='16000000',width='5120',height='2664')
checks=0

def fixture(home,temporal=True,native_only=False):
    global checks
    meta={'renderer':'sodium','compare_temporal':temporal,'native_only':native_only}
    (home/'comparison.json').write_text(json.dumps(meta));root=home/'debug/metalmod';root.mkdir(parents=True)
    modes=[('native',0,0)]+([] if native_only else [('sr25',1,25)])+([('temporal25',2,25)] if temporal else [])
    index=[]
    for mode,effective,strength in modes:
        name='sodium-'+mode;folder=root/name;folder.mkdir()
        row=base.copy();row.update(window_active='1',paused='0',screen_open='0',pipeline_compiles='0',census_active='0',sr_failures='0',sr_recoveries='0',sr_requested=str(int(strength>0)),sr_strength=str(strength),sr_effective=str(effective),sr_encodes=str(int(strength>0)),sr_reason='0')
        with (folder/'frames.csv').open('w') as f:
            w=csv.DictWriter(f,fieldnames=row);w.writeheader();w.writerows([row,row])
        index.append(name+'\t'+name+'\tfixture')
    (root/'benchmark-index.tsv').write_text('\n'.join(index)+'\n');return root

def mutate(root,mode,key,value):
    file=root/('sodium-'+mode)/'frames.csv';rows=list(csv.DictReader(file.open()));rows[0][key]=value
    with file.open('w') as f:
        w=csv.DictWriter(f,fieldnames=rows[0]);w.writeheader();w.writerows(rows)

for temporal,native_only in [(False,True),(False,False),(True,False)]:
    with tempfile.TemporaryDirectory() as tmp:
        root=fixture(Path(tmp),temporal,native_only);assert len(summarize(root)['runs'])==(3 if temporal else 1 if native_only else 2);checks+=1
for key,value in [('sr_recoveries','1'),('sr_failures','1'),('sr_effective','1'),('sr_reason','3'),('sr_requested','0'),('sr_strength','50'),('window_active','0'),('pipeline_compiles','1')]:
    with tempfile.TemporaryDirectory() as tmp:
        root=fixture(Path(tmp));mutate(root,'temporal25',key,value)
        try:summarize(root)
        except ValueError:checks+=1
        else:raise AssertionError('accepted invalid temporal row '+key)
print('ANALYZER CHECKS PASSED:',checks)
