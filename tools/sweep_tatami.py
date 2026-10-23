#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Local finite-grid tatami sweep. Never promotes sampled sizes to a continuous safe range."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

REPO = Path(__file__).resolve().parents[1]
SHAPES = ['square', 'circle', 'donut', 'star', 'block_h']


def shape_rule(rows):
    rows = sorted(rows, key=lambda r: r['size_mm'])
    allowed = [r for r in rows if r['verdict'] == 'PASS']
    suffix = []
    for row in reversed(rows):
        if row['verdict'] != 'PASS':
            break
        suffix.append(row)
    return {
        'allowed_sampled_sizes_mm': [r['size_mm'] for r in allowed],
        'excluded_sampled_sizes_mm': [r['size_mm'] for r in rows if r['verdict'] != 'PASS'],
        'minimum_passing_sample_mm': min((r['size_mm'] for r in allowed), default=None),
        'minimum_verified_area_mm2': min((r['area_mm2'] for r in allowed), default=None),
        'minimum_failed_sample_mm': min((r['size_mm'] for r in rows if r['verdict'] != 'PASS'), default=None),
        'all_larger_samples_pass_from_mm': min((r['size_mm'] for r in suffix), default=None),
        'interpolation_allowed': False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=Path('/tmp/myembro-tatami-sweep'))
    parser.add_argument('--sizes', default=','.join(map(str, range(5, 91))))
    parser.add_argument('--gate', type=Path, default=Path('/tmp/myembro-quality-gate/quality-gate'))
    parser.add_argument('--skip-generation', action='store_true')
    args = parser.parse_args()
    sizes = [int(s) for s in args.sizes.split(',')]
    assert len(sizes) == len(set(sizes)) and sizes and all(5 <= s <= 90 for s in sizes)
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    # Fail closed even when a previous run left successful rules in this directory.
    pending = out / 'fill-allowed-regions.json'
    pending.write_text(json.dumps({'status': 'INCOMPLETE', 'default_decision': 'DENY',
                                   'deployment_enabled': False, 'allowed_combinations': []}) + '\n')
    if not args.skip_generation:
        command = ['./gradlew', ':engine:generateTatamiSweep', f'-PtatamiSweepDir={out}',
                   f'-PtatamiSweepSizes={args.sizes}', '--console=plain']
        with (out / 'generation.log').open('w') as log:
            result = subprocess.run(command, cwd=REPO, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            raise SystemExit(f'Generation command failed: see {out}/generation.log; no rules published')
    document = json.loads((out / 'generation.json').read_text())
    expected = {(shape, size) for shape in SHAPES for size in sizes}
    assert len(document['cases']) == len(expected)
    assert {(r['shape'], r['size_mm']) for r in document['cases']} == expected
    gate = args.gate.resolve()
    pinned = {name: hashlib.sha256((gate.parent / name).read_bytes()).hexdigest()
              for name in ['gate.py', 'lock_geometry.py', 'thresholds.json', 'POLICY.md']}
    command = [str(gate), 'check', '--matrix', str(out / 'generation.json'), '--json', str(out / 'gate.json')]
    with (out / 'gate.log').open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    assert result.returncode in [0, 1], f'Gate failed to execute: {out}/gate.log'
    assert all(hashlib.sha256((gate.parent / name).read_bytes()).hexdigest() == value for name, value in pinned.items()), 'Gate changed during sweep'
    gate_report = json.loads((out / 'gate.json').read_text())
    assert gate_report['cases'] == len(expected)
    decisions = {r['path']: r for r in gate_report['results']}
    rows = []
    for source in document['cases']:
        decision = decisions[source['path']]
        ok = source['status'] == 'GENERATED' and decision['verdict'] == 'PASS'
        rows.append(dict(source, verdict='PASS' if ok else 'BLOCK',
                         reasons=([source['error']] if source.get('error') else []) + decision['reasons']))
    rules = {
        'schema_version': 1, 'scope': 'exact finite fixture grid only', 'default_decision': 'DENY',
        'deployment_enabled': False, 'deployment_reason': 'Candidate only; current CI and physical sew-out approval are separate requirements',
        'continuous_range_proven': False, 'arbitrary_polygons_covered': False,
        'angle_deg': 0, 'size_grid_mm': sorted(sizes),
        'shape_definitions': {'square': 'width=height=size', 'circle': 'diameter=size',
                              'donut': 'outer diameter=size; inner=0.4*size',
                              'star': 'five points; outer diameter=size; inner/outer radius=0.45; top vertex up',
                              'block_h': 'height=size; width=0.8*size; bar width=0.2*size'},
        'gate_sources_sha256': pinned,
        'shapes': {shape: shape_rule([r for r in rows if r['shape'] == shape]) for shape in SHAPES},
        'allowed_combinations': [{'shape': r['shape'], 'size_mm': r['size_mm'], 'angle_deg': 0,
                                  'area_mm2': r['area_mm2']} for r in rows if r['verdict'] == 'PASS'],
    }
    write = lambda name, data: (out / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    write('results.json', rows)
    write('failures.json', [r for r in rows if r['verdict'] != 'PASS'])
    write('fill-allowed-regions.json', rules)
    source_files = ['engine/src/main/kotlin/io/github/linker2k/myembro/engine/inkstitch/elements/DefaultTatami.kt',
                    'engine/src/main/kotlin/io/github/linker2k/myembro/engine/inkstitch/stitches/InkAutoFill.kt',
                    'engine/src/main/kotlin/io/github/linker2k/myembro/engine/inkstitch/stitches/InkRunning.kt',
                    'engine/src/main/kotlin/io/github/linker2k/myembro/engine/inkstitch/stitch_plan/StitchPlanBuilder.kt',
                    'engine/src/test/kotlin/io/github/linker2k/myembro/engine/inkstitch/elements/TatamiSweepFixtures.kt',
                    'tools/sweep_tatami.py']
    write('provenance.json', {'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip(),
                             'source_sha256': {s: hashlib.sha256((REPO / s).read_bytes()).hexdigest() for s in source_files},
                             'gate_command': command, 'gate_exit_code': result.returncode,
                             'gate_implementation': 'External frozen v5; worker Kotlin POLICY port not yet merged'})
    lines = ['# Tatami finite-grid sweep', '',
             '| Shape | PASS/total | Allowed sampled mm | First failed mm | Minimum passing area mm² |',
             '|---|---:|---|---:|---:|']
    for shape, rule in rules['shapes'].items():
        allowed = rule['allowed_sampled_sizes_mm']
        lines.append(f"| {shape} | {len(allowed)}/{len(sizes)} | {','.join(map(str, allowed)) or 'none'} | {rule['minimum_failed_sample_mm']} | {rule['minimum_verified_area_mm2']} |")
    lines += ['', 'Only exact sampled shapes/sizes are covered; untested inputs default DENY.',
              'Minimum passing area is an observation, not a general polygon-area threshold.',
              'No CI equivalence or physical sew-out approval is implied. Deployment disabled.', '', '## Failures', '']
    lines += [f"- {r['id']}: {'; '.join(r['reasons'])}" for r in rows if r['verdict'] != 'PASS']
    (out / 'summary.md').write_text('\n'.join(lines) + '\n')
    passed = sum(r['verdict'] == 'PASS' for r in rows)
    print(f'GATE: {passed}/{len(rows)} PASS; {len(rows)-passed} BLOCK; {out}/fill-allowed-regions.json')


if __name__ == '__main__':
    main()
