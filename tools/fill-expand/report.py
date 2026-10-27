#!/usr/bin/env python3
"""Join the unchanged independent overlap audit and quality gate; select by fixed manager criteria."""
import argparse
import csv
import importlib.util
import json
from pathlib import Path
import sys
csv.field_size_limit(10_000_000)

p = argparse.ArgumentParser()
p.add_argument('output', type=Path)
p.add_argument('--audit', type=Path, required=True)
a = p.parse_args()
# audit.py historically reads sys.argv[1] as its thread footprint. Keep its nominal 0.37 mm default.
sys.argv = [str(a.audit)]
spec = importlib.util.spec_from_file_location('overlap_audit', a.audit)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
gate = json.loads((a.output / 'gate.json').read_text())
checks = {Path(r['path']).name: r for r in gate['results']}
rows = []
with (a.output / 'generation.tsv').open() as f:
    for row in csv.DictReader(f, delimiter='\t'):
        path = a.output / 'pes' / row['file']
        if path.exists():
            metric = audit.audit('expand-sweep', path)
            metric.pop('_pooled', None)
            (a.output / (path.stem + '-audit.json')).write_text(json.dumps(metric, indent=2))
            depth = metric['later_boundary_depth_in_earlier_mm']['p50']
            gap = metric['gap_ratio_proxy']
        else:
            depth = gap = None
        row.update(penetration_p50_mm=depth, gap_ratio=gap,
                   python_gate=checks.get(row['file'], {}).get('verdict', 'BLOCK'))
        row['eligible'] = (row['sewout'] == row['python_gate'] == 'PASS'
                           and depth is not None and depth >= .45
                           and gap is not None and gap <= .0332)
        rows.append(row)
        print(row['file'], row['sewout'], row['python_gate'], depth, gap, row['eligible'], flush=True)
values = sorted({float(r['expand_mm']) for r in rows})
selected = next((x for x in values if all(r['eligible'] for r in rows if float(r['expand_mm']) == x)), None)
(a.output / 'results.json').write_text(json.dumps({'rows': rows, 'selected_expand_mm': selected}, indent=2))
lines = ['# Fill expansion sweep', '',
    'Baseline: 51f0441. Minimum region area remains 3.0 mm². Top fill only; underlay unchanged.',
    'face/robot: ColorCleanupSweep inputs, 192 px bilinear, 30 mm, 4 colors, injected 4×4 white speck. '
    'proxy: local screenshot, aspect-preserving 192 px, 99.8 mm, 4 colors; no additional contrast/noise processing.',
    'Unchanged audit.py: 0.1 mm raster, 0.37 mm thread footprint. Gap is the existing boundary-sample proxy, not measured fabric.',
    'Eligibility: both gates PASS, later boundary penetration p50 ≥0.45 mm and gap ≤3.32% for EVERY input. Missing metrics fail.', '',
    '| Input | expand mm | Stitches | Excluded | Methods | SewoutCheck | Python gate | Penetration p50 mm | Gap % | Eligible |',
    '|---|---:|---:|---:|---|---|---|---:|---:|---|']
for r in rows:
    depth = 'unconfirmed' if r['penetration_p50_mm'] is None else f"{r['penetration_p50_mm']:.3f}"
    gap = 'unconfirmed' if r['gap_ratio'] is None else f"{100*r['gap_ratio']:.2f}"
    lines.append(f"| {r['input']} | {r['expand_mm']} | {r['stitches']} | {r['excluded']} | {r['methods']} | {r['sewout']} | {r['python_gate']} | {depth} | {gap} | {r['eligible']} |")
lines += ['', f'Computed smallest eligible expansion: {selected if selected is not None else "none"}. Default remains 0 mm.',
          '', 'Sources: Ink/Stitch v3.3.0 fill_stitch.py expand_mm (default 0); existing FillGeometry.shrinkOrGrow and FontTatami top-only expansion.',
          'No personal input or generated personal PES is included in the repository.']
(a.output / 'table.md').write_text('\n'.join(lines) + '\n')
print('SELECTED', selected)
