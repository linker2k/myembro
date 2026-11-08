#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Heuristic: is there underlay beneath satin runs in a PES corpus, and which kind, by width?

usage: python3 tools/research/satin_underlay_corpus.py --gate <quality_gate.py> <dir-with-pes> [--json out]
Prints only aggregate numbers (no file names), so it can run on a private corpus.

Satin run: the frozen satin heuristic of tools/experiments/satin_narrow.py (legs >= 0.8mm,
cosine < -0.8, >= 7 consecutive reversing legs). Width = median leg length of the run. Runs continuing within 2 needles of the previous run
(a column broken at a corner) are merged into that column and not counted again.
Underlay candidate: the needle moves sewn just before the run, in the same colour and without a
trim/jump in between, back to the previous satin run or at most 3x the run's own path length,
keeping points within 0.3mm of the run's needle hull. Kind (unverified semantic labels):
  none      fewer than 3 candidate points or candidate path < 30% of the run's centre length
  zigzag    candidate itself contains >= 3 reversing legs longer than 40% of the width
  contour   >= 50% of candidate points lie within 25% of the width from the run's edge needles
  center    otherwise (points mostly away from both edges)
These thresholds are heuristic choices for this survey only, not product rules.
"""
import argparse, json, math, sys
from pathlib import Path


def runs(events, stitch):
    """Yield (start, end) index ranges of satin runs within stitch sequences."""
    seq, out = [], []
    for i, (x, y, c) in enumerate(events):
        if c == stitch:
            seq.append(i)
        else:
            if len(seq) > 1:
                out.append(seq)
            seq = []
    if len(seq) > 1:
        out.append(seq)
    found = []
    for s in out:
        pts = [events[i][:2] for i in s]
        legs = [(pts[k+1][0]-pts[k][0], pts[k+1][1]-pts[k][1]) for k in range(len(pts)-1)]
        rev = []
        for k in range(len(legs)-1):
            a, b = legs[k], legs[k+1]
            la, lb = math.hypot(*a), math.hypot(*b)
            rev.append(la >= 8 and lb >= 8 and (a[0]*b[0]+a[1]*b[1])/(la*lb) < -0.8)
        k = 0
        while k < len(rev):
            if rev[k]:
                j = k
                while j < len(rev) and rev[j]:
                    j += 1
                if j-k >= 6:
                    found.append((s, k, j+1))  # legs k..j inclusive -> points k..j+1
                k = j
            else:
                k += 1
    return found


def cross(o, a, b):
    return (a[0]-o[0])*(b[1]-o[1]) - (a[1]-o[1])*(b[0]-o[0])


def classify(events, stitch):
    out = []
    previous_end = {}
    chain = {}
    for s, a, b in runs(events, stitch):
        pts = [events[i][:2] for i in s]
        # A satin broken at a corner continues within 2 needles: one column, classified once.
        if id(s) in chain and a - chain[id(s)] <= 2:
            chain[id(s)] = b
            previous_end[id(s)] = b
            continue
        chain[id(s)] = b
        satin = pts[a:b+1]
        legs = [math.hypot(satin[k+1][0]-satin[k][0], satin[k+1][1]-satin[k][1]) for k in range(len(satin)-1)]
        width = sorted(legs)[len(legs)//2]
        mids = [((satin[k][0]+satin[k+1][0])/2, (satin[k][1]+satin[k+1][1])/2) for k in range(len(satin)-1)]
        centre = sum(math.hypot(mids[k+1][0]-mids[k][0], mids[k+1][1]-mids[k][1]) for k in range(len(mids)-1))
        budget = 3*sum(legs)
        cand, walked = [], 0.0
        lo = previous_end.get(id(s), 0)
        k = a-1
        while k >= lo and walked < budget:
            walked += math.hypot(pts[k+1][0]-pts[k][0], pts[k+1][1]-pts[k][1])
            p = pts[k]
            near = min(math.hypot(p[0]-q[0], p[1]-q[1]) for q in satin)
            inside = near <= width/2 + 3
            if inside:
                cand.append(p)
            k -= 1
        previous_end[id(s)] = b
        cand.reverse()
        path = sum(math.hypot(cand[i+1][0]-cand[i][0], cand[i+1][1]-cand[i][1]) for i in range(len(cand)-1))
        if len(cand) < 3 or path < 0.3*centre:
            kind = "none"
        else:
            cl = [(cand[i+1][0]-cand[i][0], cand[i+1][1]-cand[i][1]) for i in range(len(cand)-1)]
            zig = sum(1 for i in range(len(cl)-1) if math.hypot(*cl[i]) > 0.4*width and math.hypot(*cl[i+1]) > 0.4*width
                      and (cl[i][0]*cl[i+1][0]+cl[i][1]*cl[i+1][1]) / (math.hypot(*cl[i])*math.hypot(*cl[i+1])) < -0.5)
            edge = sum(1 for p in cand if min(math.hypot(p[0]-q[0], p[1]-q[1]) for q in satin) <= 0.25*width)
            kind = "zigzag" if zig >= 3 else "contour" if edge >= 0.5*len(cand) else "center"
        out.append((width/10, kind))
    return out


BANDS = [(0, 1.5), (1.5, 2.0), (2.0, 3.0), (3.0, 4.0), (4.0, 5.0), (5.0, 7.0), (7.0, 99)]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--gate", type=Path, required=True)
    ap.add_argument("--json", type=Path)
    ap.add_argument("root", type=Path)
    args = ap.parse_args()
    sys.path.insert(0, str(args.gate.resolve().parent))
    import quality_gate as gate
    files = sorted(args.root.rglob("*.pes")) + sorted(args.root.rglob("*.PES"))
    table = {band: {"none": 0, "center": 0, "contour": 0, "zigzag": 0} for band in BANDS}
    with_satin = 0
    for f in files:
        try:
            events = gate.parse_pes(f.read_bytes())[2]
        except Exception:
            continue
        found = classify(events, gate.STITCH)
        with_satin += bool(found)
        for width, kind in found:
            for band in BANDS:
                if band[0] <= width < band[1]:
                    table[band][kind] += 1
    rows = []
    print(f"files {len(files)}, with satin runs {with_satin}")
    print("width mm | runs | none | center | contour | zigzag")
    for band, c in table.items():
        n = sum(c.values())
        rows.append({"width_mm": list(band), "runs": n, **c})
        pct = lambda k: f"{c[k]} ({c[k]/n:.0%})" if n else "0"
        print(f"{band[0]}–{band[1]} | {n} | {pct('none')} | {pct('center')} | {pct('contour')} | {pct('zigzag')}")
    if args.json:
        args.json.write_text(json.dumps({"files": len(files), "files_with_satin": with_satin, "bands": rows}, indent=1) + "\n")


if __name__ == "__main__":
    main()
