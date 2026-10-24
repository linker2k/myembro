#!/usr/bin/env python3
"""Measure <=10 failed font cases from frozen, independently decoded oracle caches.

Reads JSON/gzip only: never invokes Ink/Stitch, its dependencies, or changes the comparator.
"""
import argparse
import gzip
import hashlib
import json
import math
import pathlib

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--run', type=pathlib.Path, default=pathlib.Path('/tmp/myembro-inkstitch-oracle/out/latest-letter-fonts/df7da347cda358092a4ee29c19fe256474f12913'))
parser.add_argument('--oracle-root', type=pathlib.Path, default=pathlib.Path('/tmp/myembro-inkstitch-oracle'))
parser.add_argument('--out', type=pathlib.Path, default=pathlib.Path('/tmp/myembro-port-out/letter/font-residuals'))
args = parser.parse_args()
comparison = json.loads((args.run / 'comparison-distributions.json').read_text())
failed = [r for r in comparison['cases'] if not r['forceBoundaryLocks'] and r['verdict'] != '동등']
assert 0 < len(failed) <= 10
manifest = json.loads((args.run / 'manifest.json').read_text())
ports = {r['id']: r for r in manifest['cases']}

def load(path, sha):
    with gzip.open(path, 'rt') as stream:
        data = json.load(stream)
    assert data['sha256'] == sha
    return data

def controls(data):
    shift = data['normalization_translation_mm']
    return [(i, c[2], c[0] + shift[0], c[1] + shift[1])
            for i, c in enumerate(data['commands']) if c[2] in (2, 3, 5)]

rows = []
for case in failed:
    identity = ports[case['id']]
    record = json.loads((args.oracle_root / 'out/defaults-v2/fonts' / (case['id'] + '.oracle.json')).read_text())
    for path, sha in [(record['path'], record['sha256']), (identity['path'], identity['sha256'])]:
        assert hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest() == sha
    official = load(record['coordinates'], record['sha256'])
    port = load(args.oracle_root / 'out/port-coordinates' / (identity['sha256'] + '.json.gz'), identity['sha256'])
    first_o = next(i for i, c in enumerate(official['commands']) if c[2] == 0)
    first_p = next(i for i, c in enumerate(port['commands']) if c[2] == 0)
    oc, pc = controls(official), controls(port)
    opre = [c for c in oc if c[0] < first_o]
    ppre = [c for c in pc if c[0] < first_p]
    obody = [c for c in oc if c[0] >= first_o]
    pbody = [c for c in pc if c[0] >= first_p]
    order_equal = [c[1] for c in obody] == [c[1] for c in pbody]
    anchor_error = max((math.dist(a[2:], b[2:]) for a, b in zip(obody, pbody)), default=0)
    initial = official['commands'][:first_o]
    # PEC's first command is its viewport prefix. Remaining initial jumps are encoder subdivisions.
    jumps = [c for c in initial if c[2] == 1]
    reposition = math.dist(jumps[0][:2], jumps[-1][:2]) if len(jumps) > 1 else 0
    parts = math.ceil(max(abs(jumps[-1][0] - jumps[0][0]), abs(jumps[-1][1] - jumps[0][1])) / 204.7) if len(jumps) > 1 else 0
    qualified = (len(opre) == 1 and opre[0][1] == 2 and not ppre and order_equal
                 and anchor_error <= .2 + 1e-9 and len(official['needles_mm']) == len(port['needles_mm'])
                 and case['hausdorff_mm'] <= .2 + 1e-9 and parts == 2)
    rows.append(dict(id=case['id'], oracle_sha256=record['sha256'], port_sha256=identity['sha256'],
        needles=[len(official['needles_mm']), len(port['needles_mm'])], hausdorff_mm=case['hausdorff_mm'],
        trims=[sum(c[1] == 2 for c in oc), sum(c[1] == 2 for c in pc)],
        first_needle_command_indices=[first_o, first_p],
        oracle_initial_commands=initial, port_initial_commands=port['commands'][:first_p],
        oracle_initial_reposition_mm=reposition, pec_max_axis_delta_mm=204.7, encoder_parts=parts,
        before_first_needle_controls=[opre, ppre], body_control_codes_match=order_equal,
        body_control_anchor_max_error_mm=anchor_error,
        classification='INITIAL_NEEDLE_UP_TRIM_ONLY' if qualified else 'UNRESOLVED',
        comparator_verdict_unchanged=case['verdict']))
args.out.mkdir(parents=True, exist_ok=True)
report = dict(source_commit=comparison['commit'], criteria_sha256=comparison['criteria_sha256'],
              normalization='bbox-center translation only', cases=rows)
(args.out / 'residuals.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
lines = ['| case | needles official/port | trims official/port | first needle cmd official/port | Hausdorff mm | body controls max mm | cause |',
         '|---|---:|---:|---:|---:|---:|---|']
for r in rows:
    lines.append(f"| {r['id']} | {r['needles'][0]}/{r['needles'][1]} | {r['trims'][0]}/{r['trims'][1]} | {r['first_needle_command_indices'][0]}/{r['first_needle_command_indices'][1]} | {r['hausdorff_mm']:.6f} | {r['body_control_anchor_max_error_mm']:.6f} | {r['classification']} |")
(args.out / 'residuals.md').write_text('\n'.join(lines) + '\n')
print('\n'.join(lines))
assert all(r['classification'] == 'INITIAL_NEEDLE_UP_TRIM_ONLY' for r in rows), 'Unresolved cases require investigation'
