#!/usr/bin/env python3
"""Cross-check exactly the JVM regression PES artifacts with the independent Python quality gate.

Run :engine:runAgentRegression first. This script does not generate/update golden expectations,
install dependencies, run Ink/Stitch, or grant release approval. Rejected-script audit files are
explicit diagnostics of the unchanged live state; no blocked PES is released by the dispatcher.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifacts', type=Path)
    parser.add_argument('--gate', type=Path, default=Path('/tmp/myembro-quality-gate/quality-gate'))
    args = parser.parse_args()
    rows = json.loads((args.artifacts / 'observed.json').read_text())
    assert len(rows) == 12 and len({r['id'] for r in rows}) == 12, 'Expected 10 scenarios / 12 variants'
    results = []
    for row in rows:
        pes = args.artifacts / (row['id'] + '.audit.pes')
        assert hashlib.sha256(pes.read_bytes()).hexdigest() == row['pesSha256'], row['id'] + ' PES changed'
        output = args.artifacts / (row['id'] + '.python-gate.json')
        command = [str(args.gate.resolve()), 'check', str(pes.resolve()), '--json', str(output.resolve()),
                   '--hoop-bounds-mm', '0', '0', str(row['widthMm']), str(row['heightMm'])]
        run = subprocess.run(command, capture_output=True, text=True)
        if not output.is_file():
            raise RuntimeError('Python gate did not write a report: ' + run.stderr[-2000:])
        report = json.loads(output.read_text())
        case = report['results'][0]
        actual = case['verdict']
        expected = 'WARNING' if row['sewout'] == 'CAUTION' else row['sewout']
        matches = actual == expected and report['all_required_checks_complete']
        if row['id'] == 'hoop-1000':
            matches = matches and any('PEC_HOOP:' in r for r in case['reasons'])
        matches = matches and run.returncode == (1 if actual == 'BLOCK' else 0)
        results.append({'id': row['id'], 'expected': expected, 'actual': actual, 'match': matches,
                        'sha256': row['pesSha256'], 'released_by_dispatcher': row['released'],
                        'reasons': case.get('reasons', [])})
        print(f"PYTHON_GATE {'MATCH' if matches else 'MISMATCH'} {row['id']}: expected={expected} actual={actual}")
    summary = {'matched': sum(r['match'] for r in results), 'cases': len(results), 'results': results,
               'release_approval': False}
    (args.artifacts / 'python-crosscheck.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(f"PYTHON_CROSSCHECK {summary['matched']}/{len(results)} matched")
    return 0 if all(r['match'] for r in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
