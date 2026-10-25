#!/usr/bin/env python3
"""Compare read-only diagnostic stages by exact hashes, never tolerances."""
import gzip
import json
from pathlib import Path
import struct
import sys


def load(path):
    raw = Path(path).read_bytes()
    return json.loads(gzip.decompress(raw) if raw[:2] == b'\x1f\x8b' else raw)


def polygon(hex_wkb):
    b = bytes.fromhex(hex_wkb)
    endian = '<' if b[0] else '>'
    assert struct.unpack_from(endian + 'I', b, 1)[0] == 3
    count = struct.unpack_from(endian + 'I', b, 5)[0]
    offset, rings = 9, []
    for _ in range(count):
        n = struct.unpack_from(endian + 'I', b, offset)[0]
        offset += 4
        coords = [struct.unpack_from(endian + 'dd', b, offset + 16 * i) for i in range(n)]
        bits = [b[offset + 16 * i:offset + 16 * (i + 1)] for i in range(n)]
        offset += 16 * n
        rings.append((coords, bits))
    return rings


left = load(sys.argv[1])
right = load(sys.argv[2]) if len(sys.argv) > 2 else left
assert left['object_sha256'] == right['object_sha256'], 'Different prepared objects'
print('OBJECT_JSON_SHA256', left['object_sha256'], 'EXACT')
base = left['runs'][0]['stages']
for label, document in [('JVM', left), ('ANDROID', right)]:
    for run in document['runs']:
        stages = run['stages']
        assert [s['stage'] for s in base] == [s['stage'] for s in stages]
        print(label, 'run', run['iteration'], 'PES', stages[-1])
        differences = [(i, a, b) for i, (a, b) in enumerate(zip(base, stages)) if a['sha256'] != b['sha256']]
        if not differences:
            print('ALL_STAGES_EXACT')
            continue
        index, a, b = differences[0]
        print('FIRST_DIFFERENCE', index, a['stage'], a['sha256'], b['sha256'])
        if a['stage'] == 'polygonized_rings':
            for i, (x, y) in enumerate(zip(a['payload'].split(';'), b['payload'].split(';'))):
                if x != y:
                    for ring, ((p, pbits), (q, qbits)) in enumerate(zip(polygon(x), polygon(y))):
                        print('RING', i, ring, 'first', p[0], q[0], 'vertices', len(p), len(q),
                              'vertex_bits_set_equal', set(pbits) == set(qbits))
                    break
        for stage in ['input_rings', 'noded_lines', 'polygonized_rings', 'polygonized_normalized',
                      'row_segments', 'graph_before_eulerize', 'matching_input', 'matching_pairs',
                      'graph_after_eulerize', 'travel_graph', 'collapsed_euler_path', 'rendered_route']:
            pairs = [(a, b) for a, b in zip(base, stages) if a['stage'] == stage]
            print('STAGE', stage, 'exact', sum(a['sha256'] == b['sha256'] for a, b in pairs), '/', len(pairs))

        for i, (a, b) in enumerate(zip(base, stages)):
            if a['stage'] == 'graph_before_eulerize' and a['sha256'] != b['sha256']:
                def edges(value, key):
                    rows = [entry.split(',') for entry in value['payload'].split(';') if entry.startswith('E:')]
                    return {(tuple(sorted(r[1:3])), r[3], r[4], r[5]) for r in rows if r[3] == key}
                print('FIRST_GRAPH_DIFF', i)
                for key in ['segment', 'outline', 'extra']:
                    l, r = edges(a, key), edges(b, key)
                    print('EDGE_SET', key, 'counts', len(l), len(r), 'exact', l == r)
                break
        for i, (a, b) in enumerate(zip(base, stages)):
            if a['stage'] == 'rendered_route' and a['sha256'] != b['sha256']:
                p, q = a['payload'].split('|'), b['payload'].split('|')
                index = next((n for n, (x, y) in enumerate(zip(p, q)) if x != y), min(len(p), len(q)))
                def coordinate(value):
                    return [struct.unpack('>d', struct.pack('>q', int(t)))[0] for t in value.split(':')[:2]]
                print('FIRST_RENDERED_DIFF', i, 'point', index, 'counts', len(p), len(q),
                      'left', coordinate(p[index]) if index < len(p) else None,
                      'right', coordinate(q[index]) if index < len(q) else None)
                break
