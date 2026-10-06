#!/usr/bin/env python3
"""Analyze exported Instruments GPU intervals, without summing overlapping stages.

Export metal-gpu-intervals from xctrace first. This measures instrumented work associated
with Instruments frame IDs, not CPU/F8 frame IDs, throughput or input-to-display latency.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import statistics
import xml.etree.ElementTree as ET


def union_ns(intervals):
    ordered = sorted(intervals)
    if not ordered:
        return 0
    start, end = ordered[0]
    total = 0
    for next_start, next_end in ordered[1:]:
        if next_start > end:
            total += end - start
            start, end = next_start, next_end
        else:
            end = max(end, next_end)
    return total + end - start


def analyze(path, pid):
    root = ET.parse(path).getroot()
    references = {element.attrib['id']: element for element in root.iter()
                  if 'id' in element.attrib}

    def dereference(element):
        while 'ref' in element.attrib:
            element = references[element.attrib['ref']]
        return element

    def value(element):
        return dereference(element).text or ''

    grouped = defaultdict(list)
    unassigned = 0
    for node in root.findall('node'):
        for row in node.findall('row'):
            columns = list(row)
            # Match the exported type signature, rather than table numbers that vary by template.
            if len(columns) < 18 or dereference(columns[2]).tag != 'gpu-channel-name':
                continue
            process = dereference(columns[10])
            process_pid = process.find('pid')
            if process_pid is None or value(process_pid) != str(pid) or value(columns[5]) != '0':
                continue
            frame = value(columns[3])
            if not frame:
                unassigned += 1
                continue
            start, duration = int(value(columns[0])), int(value(columns[1]))
            if duration < 0:
                raise ValueError('negative GPU interval')
            grouped[int(frame)].append((start, start + duration, value(columns[2])))
    if len(grouped) < 3:
        raise ValueError('insufficient associated GPU frames for selected process')
    boundary = {min(grouped), max(grouped)}
    frames = []
    for frame, intervals in sorted(grouped.items()):
        if frame in boundary:
            continue
        frames.append(dict(frame=frame,
                           span_ms=(max(end for _, end, _ in intervals)
                                    - min(start for start, _, _ in intervals))/1e6,
                           union_ms=union_ns([(start, end) for start, end, _ in intervals])/1e6,
                           vertex_sum_ms=sum(end-start for start, end, channel in intervals
                                             if channel == 'Vertex')/1e6,
                           fragment_sum_ms=sum(end-start for start, end, channel in intervals
                                               if channel == 'Fragment')/1e6))
    summary = {}
    for key in ('span_ms', 'union_ms', 'vertex_sum_ms', 'fragment_sum_ms'):
        values = sorted(frame[key] for frame in frames)
        summary[key] = dict(mean=statistics.mean(values), median=statistics.median(values),
                            p95=values[min(len(values)-1, (95*len(values)+99)//100-1)])
    return dict(pid=pid, frame_count=len(frames), unassigned_intervals=unassigned,
                omitted_boundary_frames=sorted(boundary), summary=summary, frames=frames,
                scope='Instrumented top-level GPU intervals for one process. Union merges overlapping '
                      'channels within each Instruments frame; span includes scheduling gaps. '
                      'Unassigned work and boundary frames are omitted. Frames can overlap each other. '
                      'These are not F8 CPU frame IDs, uninstrumented throughput or input latency.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('export', type=Path)
    parser.add_argument('--pid', type=int, required=True)
    args = parser.parse_args()
    print(json.dumps(analyze(args.export, args.pid), indent=2))
