#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Writes synthetic cross stitch cases (one control varied at a time from a base case).

usage: python3 -I tools/cross-stitch/make_cases.py engine/src/test/resources/cross-stitch/cases.json
Shapes are hand-written WKT in SVG pixels; no font or personal data.
"""
import json, math, pathlib, sys

PX = 96 / 25.4
SHAPES = {
    'rect': 'POLYGON ((3.3 4.1, 40.7 4.1, 40.7 30.2, 3.3 30.2, 3.3 4.1))',
    'ring': 'POLYGON ((0 0, 50 0, 50 50, 0 50, 0 0), (15 15, 15 35, 35 35, 35 15, 15 15))',
    'neck': 'POLYGON ((0 0, 20 0, 20 9, 40 9, 40 0, 60 0, 60 20, 40 20, 40 11, 20 11, 20 20, 0 20, 0 0))',
    'negative': 'POLYGON ((-37.2 -21.5, -5 -21.5, -5 8, -37.2 8, -37.2 -21.5))',
    'disc': 'POLYGON ((' + ', '.join(f'{20 + 17 * math.cos(2 * math.pi * i / 48):.6f} {20 + 17 * math.sin(2 * math.pi * i / 48):.6f}'
                                     for i in list(range(48)) + [0]) + '))',
    'islands': 'MULTIPOLYGON (((0 0, 16 0, 16 16, 0 16, 0 0)), ((30 2, 46 2, 46 18, 30 18, 30 2)))',
    'diagonal': 'POLYGON ((0 0, 8 0, 40 32, 40 40, 32 40, 0 8, 0 0))',
}
BASE = dict(shape='rect', method='simple_cross', pattern=[2 * PX, 2 * PX], coverage=50, offset=[0, 0],
            canvas_grid_origin=False, thread_count=4, max_length=11 * PX, start=None, end=None)
VARIANTS = [{}]
VARIANTS += [{'shape': s} for s in SHAPES if s != 'rect']
VARIANTS += [{'method': m} for m in ['simple cross', 'simple_cross_flipped', 'upright_cross', 'upright_cross_flipped',
                                     'dense_upright_cross', 'dense_upright_cross_flipped', 'double_cross',
                                     'double_cross_flipped', 'smyrna_cross', 'smyrna_cross_flipped']]
VARIANTS += [{'thread_count': n} for n in [2, 3, 6, -4]]
VARIANTS += [{'pattern': [3 * PX, 2 * PX]}, {'pattern': [3 * PX, 3 * PX]}, {'coverage': 25}, {'coverage': 90},
             {'canvas_grid_origin': True}, {'offset': [1.5, 2.0]}, {'canvas_grid_origin': True, 'offset': [1.5, 2.0]},
             {'max_length': 5.0}, {'start': [41.0, 31.0]}, {'end': [2.0, 2.0]}, {'start': [41.0, 31.0], 'end': [2.0, 2.0]},
             {'start': [2.0, 5.0], 'end': [39.0, 29.0]}]
# subgraph routing: start/end over shapes that split into several subgraphs
VARIANTS += [{'shape': 'neck', 'start': [61.0, 19.0], 'end': [-1.0, 0.0]}, {'shape': 'neck', 'start': [0.0, 0.0], 'end': [1.0, 19.0]},
             {'shape': 'islands', 'start': [47.0, 18.0]}, {'shape': 'islands', 'start': [0.0, 0.0], 'end': [3.0, 15.0]},
             {'shape': 'diagonal', 'method': 'double_cross', 'thread_count': 2, 'start': [40.0, 40.0]},
             {'shape': 'ring', 'method': 'smyrna_cross', 'start': [25.0, 0.0], 'end': [25.0, 50.0]},
             {'shape': 'negative', 'method': 'simple_cross_flipped', 'start': [-36.0, 7.0], 'end': [-6.0, -20.0]}]

cases = []
for i, v in enumerate(VARIANTS):
    c = dict(BASE, **v)
    name = 'base' if not v else '_'.join(f'{k}={v[k]}' for k in v).replace(' ', '')
    c['id'] = f'{i:02d}-{name}'
    c['wkt'] = SHAPES[c.pop('shape')]
    cases.append(c)
out = pathlib.Path(sys.argv[1]); out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(cases, indent=1) + '\n')
print(len(cases), 'cases')
