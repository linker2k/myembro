#!/usr/bin/env python3
"""Exact PES/stitch-count delta for two runs of the same finite fixture grid."""
import json
from pathlib import Path
import sys

before, after, output = map(Path, sys.argv[1:4])
a = json.loads((before / 'generation.json').read_text())['cases']
b = json.loads((after / 'generation.json').read_text())['cases']
x, y = ({r['id']: r for r in rows} for rows in (a, b))
assert x.keys() == y.keys() and len(x) == 430
assert all(r['status'] == 'GENERATED' for r in a + b)
gates = [json.loads((p / 'gate.json').read_text()) for p in (before, after)]
assert gates[0]['threshold_sha256'] == gates[1]['threshold_sha256']
assert all(g['cases'] == 430 and g['blocked'] == 0 and g['verdict'] == 'PASS' for g in gates)
changes = []
for name in x:
    old, new = x[name], y[name]
    if old['sha256'] != new['sha256']:
        changes.append({'file': name + '.pes', 'before_stitches': old['stitches'],
                        'after_stitches': new['stitches'], 'delta': new['stitches'] - old['stitches'],
                        'before_sha256': old['sha256'], 'after_sha256': new['sha256']})
result = {'baseline': '810715d geometry; same corrected writer-count harness in both runs',
          'cases': 430, 'before_gate': 'PASS 430/430', 'after_gate': 'PASS 430/430',
          'threshold_sha256': gates[0]['threshold_sha256'], 'changed_files': len(changes),
          'changed_stitch_counts': sum(r['delta'] != 0 for r in changes), 'changes': changes}
output.write_text(json.dumps(result, indent=2) + '\n')
print(f"SWEEP_DELTA {result['changed_files']}/430 files; {result['changed_stitch_counts']} stitch counts changed; PASS 430/430 -> PASS 430/430")
