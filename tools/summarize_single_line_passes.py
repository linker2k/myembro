#!/usr/bin/env python3
"""Diagnostic: source collinear overlap; no stitch generation or routing changes."""
import csv
import collections
import json
import math
import pathlib
import sys


def source_lengths(paths):
    groups = collections.defaultdict(list)
    for path in paths:
        for (x, y), (xx, yy) in zip(path, path[1:]):
            dx, dy = xx-x, yy-y
            length = math.hypot(dx, dy)
            if length < 1e-9:
                continue
            ux, uy = dx/length, dy/length
            if ux < -1e-12 or (abs(ux) <= 1e-12 and uy < 0):
                ux, uy = -ux, -uy
            # Numerical representation tolerance, 10^-8 mm; not a sewing policy threshold.
            key = round(ux, 9), round(uy, 9), round(-uy*x+ux*y, 8)
            a, b = sorted((round(ux*x+uy*y, 8), round(ux*xx+uy*yy, 8)))
            groups[key].append((a, b))
    totals = collections.defaultdict(float)
    for intervals in groups.values():
        events = collections.defaultdict(int)
        for a, b in intervals:
            events[a] += 1
            events[b] -= 1
        count = 0
        previous = None
        for at, change in sorted(events.items()):
            if previous is not None and count:
                totals[count] += at-previous
            count += change
            previous = at
    return dict(totals)


def main(root):
    root = pathlib.Path(root)
    records = []
    lines = ['| 글꼴 | 높이 mm | 모드 | 1회 % | 2회 % | 3회 % | 4회 이상 % | 원인 |',
             '|---|---:|---|---:|---:|---:|---:|---|']
    for height in (10, 15):
        for filename in ('measurements.json', 'embroidery.json'):
            for row in json.loads((root/f'{height}mm'/filename).read_text()):
                hist = row['edgeCountHistogram']
                total = row['uniqueEdges']
                percent = [100*hist.get(str(n), 0)/total for n in (1, 2, 3)]
                extra = 100*sum(v for k, v in hist.items() if int(k)>3)/total
                source = source_lengths(row['sourceMm'])
                source_sum = sum(source.values())
                row['sourceCollinearLengthMm'] = source
                row['sourceCollinearLengthPercent'] = {k:100*v/source_sum for k,v in source.items()}
                cause = 'bean 1회 왕복(각 선분 3회)' if row['style']=='BEAN' else 'bean 없음'
                if any(int(k)>1 and v>1e-7 for k,v in source.items()):
                    cause += '; 원본 겹침/되돌아감 있음'
                else:
                    cause += '; 원본 동일 직선 겹침 없음'
                with (root/f'{height}mm'/f"{row['font']}-{row['style']}.csv").open() as stream:
                    machine = [[(int(e['x1'])/10,int(e['y1'])/10), (int(e['x2'])/10,int(e['y2'])/10)] for e in csv.DictReader(stream)]
                lengths = source_lengths(machine)
                row['machineCollinearLengthPercent'] = {k:100*v/sum(lengths.values()) for k,v in lengths.items()}
                row.pop('sourceMm')
                records.append(row)
                lines.append(f"| {row['font']} | {height} | {row['style']} | "+' | '.join(f'{v:.2f}' for v in percent+[extra])+f' | {cause} |')
    (root/'summary.json').write_text(json.dumps(records, ensure_ascii=False, indent=2))
    (root/'table.md').write_text('\n'.join(lines)+'\n')
    for row in records:
        if row['font'] in ['hershey-sans-1','hershey-script-1','ems-readability','inkstitch-sacramarif']:
            print(row['font'],row['heightMm'],row['style'],row['edgeCountHistogram'],row['sourceCollinearLengthPercent'])


if __name__ == '__main__':
    main(sys.argv[1])
