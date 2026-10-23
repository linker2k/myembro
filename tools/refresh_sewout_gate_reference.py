#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Refresh CI snapshots from frozen Python gate v5; does not run Ink/Stitch.
First: SEWOUT_PARITY_EXPORT=/tmp/myembro-gate-parity ./gradlew :engine:test \
    --tests '*GateV5ParityTest' --rerun-tasks
Then run this script with the quality gate's existing pyembroidery Python.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--gate-root', type=Path, default=Path('/tmp/myembro-quality-gate'))
p.add_argument('--inputs', type=Path, default=Path('/tmp/myembro-gate-parity'))
p.add_argument('--output', type=Path, default=Path('engine/src/test/resources/sewout/gate-v5/reference.json'))
a = p.parse_args()
sys.path.insert(0, str(a.gate_root))
spec = importlib.util.spec_from_file_location('frozen_gate', a.gate_root / 'gate.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
assert gate.VERSION == 5
thresholds = json.loads((a.gate_root / 'thresholds.json').read_text())
soft = {'CELL_DENSITY', 'SAME_POINT_REPEAT', 'THREAD_START_LOCK_MISSING',
        'START_LOCK_MISSING', 'END_LOCK_MISSING', 'PRE_TRIM_LOCK_MISSING', 'POST_TRIM_LOCK_MISSING'}
result = {'tool_version': 5, 'sources': {name: hashlib.sha256((a.gate_root / name).read_bytes()).hexdigest()
          for name in ('gate.py', 'lock_geometry.py', 'thresholds.json', 'POLICY.md')}, 'cases': {}}
for path in sorted(a.inputs.glob('*.PES')):
    case = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    try:
        m = gate.metric(str(path))
        decision = gate.judge(m, thresholds)
        case.update(verdict=decision['verdict'], reasons=decision['reasons'])
        case['metrics'] = {key: m[key] for key in ('stitch_count', 'cell_peak_05mm',
                          'max_same_point_consecutive', 'start_lock_missing', 'end_lock_missing')}
        case['metrics']['maximum_stitch_mm'] = m['length_mm'].get('max', 0)
        # v5 has no origin option. This explicit adapter implements POLICY's three soft criteria.
        hard = [r for r in decision['reasons'] if r.split(':')[0] not in soft]
        case['imported_policy_verdict'] = ('BLOCK' if hard or m['cell_peak_05mm'] > 30 else
                                           'CAUTION' if decision['reasons'] else 'PASS')
    except Exception as e:
        case.update(verdict='BLOCK', imported_policy_verdict='BLOCK', reasons=[f'UNREADABLE_OR_INVALID_PES: {e}'])
    result['cases'][path.stem] = case
    print(f"{path.stem}: Python={case['verdict']} imported-policy={case['imported_policy_verdict']}")
assert len(result['cases']) == 10
assert result['cases']['sampler']['verdict'] == 'PASS'
a.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
