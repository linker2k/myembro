#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compares port output embedded in a cases file ('port' per case) with oracle.py output.

usage: python3 -I tools/cross-stitch/compare.py <cases.json> <oracle.json> [--raw]
Exact mode: same cycles, same stitch count, every coordinate within 1e-6 px.
--raw (oracle run without ordering overrides): per case, same multiset of stitch segments.
"""
import collections, json, sys

args = [a for a in sys.argv[1:] if not a.startswith('--')]
raw = '--raw' in sys.argv
cases = json.load(open(args[0]))
oracle = json.load(open(args[1]))['results']

def segments(cycles):
    out = collections.Counter()
    for c in cycles:
        pts = [(round(x, 5), round(y, 5)) for x, y in c]
        for a, b in zip(pts, pts[1:]):
            if a != b:
                out[tuple(sorted((a, b)))] += 1
    return out

same = 0; worst = 0.0; bad = []; stitches = 0
for case in cases:
    want = oracle[case['id']].get('cycles'); got = case['port']
    if want is None:
        bad.append((case['id'], oracle[case['id']])); continue
    if raw:
        ok = segments(want) == segments(got)
    else:
        ok = len(want) == len(got) and all(len(w) == len(g) for w, g in zip(want, got))
        if ok:
            d = max((max(abs(a[0] - b[0]), abs(a[1] - b[1])) for w, g in zip(want, got) for a, b in zip(w, g)), default=0.0)
            worst = max(worst, d); ok = d <= 1e-6
    stitches += sum(map(len, got))
    same += ok
    if not ok:
        bad.append(case['id'])
print(f"{'raw segment multiset' if raw else 'exact'}: {same}/{len(cases)} calls match, {stitches} port stitches, max deviation {worst:.2e} px")
for b in bad[:20]:
    print('  differs:', b)
