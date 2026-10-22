#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Cross-check generated PES with pyembroidery 1.5.1 and independent polygon coverage.

Run :engine:test, then /path/to/pyembroidery-venv/bin/python tools/check_digitizing.py.
No network or additional geometry package is used. Ring data is an oracle input, not
our Kotlin reader's output; check_writer independently verifies commands and file layout.
"""
import argparse
import json
import math
import sys
from importlib.metadata import version
from pathlib import Path

sys.dont_write_bytecode = True
from check_writer import check


def edge_distance(p, a, b):
    dx, dy = b[0]-a[0], b[1]-a[1]
    length = dx*dx+dy*dy
    t = max(0, min(1, ((p[0]-a[0])*dx+(p[1]-a[1])*dy)/length)) if length else 0
    return math.hypot(p[0]-a[0]-t*dx, p[1]-a[1]-t*dy)


def coverage(path):
    manifest = json.loads(path.read_text())
    geometry = manifest['geometry']
    stitches = manifest['source']['stitches']
    edges = [(a, b) for ring in geometry['rings'] for a, b in zip(ring, ring[1:])]

    def allowed(p):
        parity = False
        for a, b in edges:
            if (a[1] > p[1]) != (b[1] > p[1]):
                cross_x = a[0]+(p[1]-a[1])*(b[0]-a[0])/(b[1]-a[1])
                if p[0] < cross_x:
                    parity = not parity
        # sqrt(0.5^2+0.5^2) is the output quantization displacement bound.
        return parity or min(edge_distance(p, a, b) for a, b in edges) <= 0.72

    fill = geometry['kind'].startswith('fill')
    limit = 41.5 if fill else 26.5
    sewn = 0
    for index, (x, y, command) in enumerate(stitches):
        if command == 'JUMP' and index:
            assert stitches[index-1][2] == 'TRIM', f'{path.name}: untrimmed disconnected path'
        if command != 'STITCH':
            continue
        sewn += 1
        if fill:
            assert allowed((x, y)), f'{path.name}: needle outside filled set: {(x, y)}'
        if not index or stitches[index-1][2] != 'STITCH':
            continue
        ax, ay, _ = stitches[index-1]
        assert math.hypot(x-ax, y-ay) <= limit, f'{path.name}: excessive stitch length'
        if fill:
            # Test every open interval separated by a boundary intersection, rather than
            # relying on midpoint-only sampling (which could miss a narrow hole).
            parameters = [0.0, 1.0]
            dx, dy = x-ax, y-ay
            for a, b in edges:
                ex, ey = b[0]-a[0], b[1]-a[1]
                denominator = dx*ey-dy*ex
                if abs(denominator) < 1e-12:
                    continue
                qx, qy = a[0]-ax, a[1]-ay
                t = (qx*ey-qy*ex)/denominator
                u = (qx*dy-qy*dx)/denominator
                if 0 < t < 1 and 0 <= u <= 1:
                    parameters.append(t)
            parameters.sort()
            for start, end in zip(parameters, parameters[1:]):
                t = (start+end)/2
                assert allowed((ax+t*dx, ay+t*dy)), f'{path.name}: sewn segment crosses a hole/outside'
    assert sewn > 0
    return sewn


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', nargs='?', type=Path,
                        default=Path(__file__).resolve().parents[1] / 'engine/build/digitizing-check')
    args = parser.parse_args()
    if version('pyembroidery') != '1.5.1':
        parser.error('oracle must be pyembroidery==1.5.1')
    paths = sorted(args.directory.glob('*.json'))
    if not paths:
        parser.error('no digitizing outputs; run ./gradlew :engine:test first')
    for path in paths:
        check(path)
        coverage(path)
    print(f'PASS: {len(paths)} generated PES files; pyembroidery semantics/layout and independent stitch coverage')


if __name__ == '__main__':
    main()
