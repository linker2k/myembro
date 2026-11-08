#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Run the unchanged Ink/Stitch v3.3.0 cross stitch code on JSON cases (oracle for CrossStitch.kt).
Coordinates are rounded to 1e-7 px; the port test compares within 1e-6 px.

usage: <venv>/bin/python -I tools/cross-stitch/oracle.py <inkstitch-checkout> <cases.json> <out.json> [--raw]

venv: shapely 2.1.1 (GEOS 3.13.1), networkx 3.5, numpy 2.2.6. Never run from Gradle.
Only lib/stitches/cross_stitch.py and lib/stitches/utils/cross_stitch.py are executed; their
imports of the stitch plan, threading and half-cross modules are stubbed (unused for even crosses).

Default (ordered) mode replaces two orderings upstream leaves to Python hashing, matching the port:
 - `set` of Cross objects (id-hash order) -> insertion-ordered set
 - G.subgraph(c).copy() adjacency (set-ordered view) -> G's node and adjacency order
--raw runs without these overrides; compare it order-independently (compare.py --raw).
"""
import hashlib, importlib.util, json, pathlib, sys, types
import networkx as nx
from shapely import wkt
from shapely.geometry import MultiPolygon, Polygon

assert sys.flags.isolated, 'use python -I'
EXPECTED = {
    'lib/stitches/cross_stitch.py': None,
    'lib/stitches/utils/cross_stitch.py': None,
}


class OrderedSet:
    def __init__(self, items=()):
        self._d = dict.fromkeys(items)
    def add(self, x): self._d[x] = None
    def remove(self, x): del self._d[x]
    def __iter__(self): return iter(list(self._d))
    def __len__(self): return len(self._d)
    def __contains__(self, x): return x in self._d


class OrderedGraph(nx.Graph):
    def subgraph(self, nodes):
        nodes = set(nodes)
        parent = self
        class View:
            def copy(self):
                h = nx.Graph()
                h.add_nodes_from(n for n in parent if n in nodes)
                for n in h:
                    for nbr in parent._adj[n]:
                        h._adj[n][nbr] = {}
                return h
        return View()


def load(root, raw):
    pkg = types.ModuleType('ink'); pkg.__path__ = []
    sp = types.ModuleType('ink.stitch_plan')
    class Stitch:
        def __init__(self, x, y, tags=None): self.x, self.y, self.tags = x, y, tags
    sp.Stitch = Stitch
    utils = types.ModuleType('ink.utils'); utils.__path__ = []
    threading = types.ModuleType('ink.utils.threading'); threading.check_stop_flag = lambda: None
    stitches = types.ModuleType('ink.stitches'); stitches.__path__ = []
    half = types.ModuleType('ink.stitches.cross_stitch_half')
    def half_cross_stitch(*a, **k): raise NotImplementedError('half crosses are not part of this oracle')
    half.half_cross_stitch = half_cross_stitch
    sutils = types.ModuleType('ink.stitches.utils'); sutils.__path__ = []
    for m in (pkg, sp, utils, threading, stitches, half, sutils):
        sys.modules[m.__name__] = m
    mods = {}
    for name, rel in (('ink.stitches.utils.cross_stitch', 'lib/stitches/utils/cross_stitch.py'),
                      ('ink.stitches.cross_stitch', 'lib/stitches/cross_stitch.py')):
        path = root / rel
        EXPECTED[rel] = hashlib.sha256(path.read_bytes()).hexdigest()
        spec = importlib.util.spec_from_file_location(name, path)
        mod = importlib.util.module_from_spec(spec)
        sys.modules[name] = mod
        if not raw:
            mod.set = OrderedSet
        spec.loader.exec_module(mod)
        mods[name] = mod
    cs = mods['ink.stitches.cross_stitch']
    if not raw:
        cs.nx = types.SimpleNamespace(Graph=OrderedGraph, connected_components=nx.connected_components,
                                      shortest_path=nx.shortest_path, NodeNotFound=nx.NodeNotFound)
    return cs


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    raw = '--raw' in sys.argv
    root, cases_path, out_path = pathlib.Path(args[0]), pathlib.Path(args[1]), pathlib.Path(args[2])
    cs = load(root, raw)
    out = {}
    for case in json.loads(cases_path.read_text()):
        fill = types.SimpleNamespace(
            cross_thread_count=case['thread_count'], cross_rotation=0, cross_stitch_method=case['method'],
            pattern_size=tuple(case['pattern']), fill_coverage=case['coverage'], cross_offset=tuple(case['offset']),
            canvas_grid_origin=case['canvas_grid_origin'], max_cross_stitch_length=case['max_length'], node=None)
        if 'polygons' in case:  # exact doubles: [[exterior, hole...], ...]
            shape = MultiPolygon([Polygon(rings[0], rings[1:]) for rings in case['polygons']])
            shape = shape.geoms[0] if len(shape.geoms) == 1 else shape
        else:
            shape = wkt.loads(case['wkt'])
        start = tuple(case['start']) if case.get('start') else None
        end = tuple(case['end']) if case.get('end') else None
        try:
            groups = cs.cross_stitch(fill, shape, start, end)
            cycles = [[[round(s.x, 7), round(s.y, 7)] for s in cycle] for group in groups for cycle in group]
            out[case['id']] = {'cycles': cycles}
        except Exception as e:  # recorded, the port must fail the same way
            out[case['id']] = {'error': type(e).__name__}
    meta = {'mode': 'raw' if raw else 'ordered', 'sources': EXPECTED, 'inkstitch': 'v3.3.0 b0edd96311ece82ee48816dc93466274b12c2a9c',
            'shapely': __import__('shapely').__version__, 'geos': '.'.join(map(str, __import__('shapely').geos_version)),
            'networkx': nx.__version__}
    out_path.write_text(json.dumps({'meta': meta, 'results': out}, separators=(',', ':')) + '\n')
    print(f"{meta['mode']}: {len(out)} cases, {sum(len(c) for r in out.values() for c in r.get('cycles', []))} stitches")


if __name__ == '__main__':
    main()
