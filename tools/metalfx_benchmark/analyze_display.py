#!/usr/bin/env python3
"""Summarize actual drawable presentedTime callbacks; unrelated to CPU render-throughput FPS."""
import csv,json,statistics,math,argparse
from pathlib import Path
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('root',type=Path);a=p.parse_args();result={}
    for folder in sorted(a.root.glob('*')):
        if not (folder/'display.csv').exists() or (folder/'REJECTED.txt').exists():continue
        with (folder/'display.csv').open() as file:values=[int(r['presented_ns']) for r in csv.DictReader(file)]
        stamps=sorted({v for v in values if v>0});intervals=[(y-x)/1e6 for x,y in zip(stamps,stamps[1:])]
        if not intervals:raise ValueError('No displayed timestamps: '+str(folder))
        ordered=sorted(intervals)
        result[folder.name]={
            'callback_samples':len(values),'zero_timestamps':sum(v==0 for v in values),
            'duplicate_nonzero_timestamps':len([v for v in values if v>0])-len(stamps),
            'unique_displayed_times':len(stamps),'mean_interval_ms':statistics.mean(intervals),
            'median_interval_ms':statistics.median(intervals),'p95_interval_ms':ordered[math.ceil(len(ordered)*.95)-1],
            'displayed_rate_hz':(len(stamps)-1)*1e9/(stamps[-1]-stamps[0]),
            'counts':(folder/'display-counts.txt').read_text(),
        }
    (a.root/'display-analysis.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
