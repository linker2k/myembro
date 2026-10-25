#!/usr/bin/env python3
"""Summarize measured safety results; does not turn failing assertions into success."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('worktree', type=Path)
p.add_argument('--reports', type=Path, default=Path('/tmp/myembro-safety-audit'))
a = p.parse_args()
totals = dict(tests=0, failures=0, errors=0, skipped=0)
files = sorted(a.worktree.glob('**/build/test-results/**/TEST-*Adversarial*xml'))
if not files:
    p.error('No adversarial JUnit XML found; run the documented Gradle command first')
known_high = {
    ('io.github.linker2k.myembro.engine.agent.AdversarialSafetyTest', 'publicPesExportPreparationCannotReturnSewoutBlockedBytes'),
    ('io.github.linker2k.myembro.usb.AdversarialUsbSafetyTest', 'sewoutBlockedPesCannotReachTheRealDirectUsbWriteBoundary'),
}
high_failures = 0
for f in files:
    root = ET.parse(f).getroot()
    for key in totals:
        totals[key] += int(root.get(key, 0))
    for case in root.findall('testcase'):
        if case.find('failure') is not None and (root.get('name'), case.get('name', '').removesuffix('()')) in known_high:
            high_failures += 1
    print(f"{root.get('name')}: tests={root.get('tests')} failures={root.get('failures')} errors={root.get('errors')}")
print('TOTAL ' + ' '.join(f'{k}={v}' for k, v in totals.items()))
print(f"HIGH_FAILURES={high_failures} OTHER_FAILURES={totals['failures'] - high_failures} ERRORS={totals['errors']}")
for name in ('engine-extra-fields', 'mcp-extra-fields', 'phone-extra-fields'):
    rows = json.loads((a.reports / (name + '.json')).read_text())
    rejected = sum(r['ok'] is False and r['writes'] == 0 for r in rows)
    print(f'{name}: rejected={rejected}/{len(rows)}')
for name in ('engine-no-token', 'mcp-no-token', 'phone-no-token', 'usb-blocked', 'phone-item-approvals', 'engine-pes-export'):
    rows = json.loads((a.reports / (name + '.json')).read_text())
    for r in rows:
        if r.get('writes', 0) or r.get('projectChanged') or r.get('usbWriteCalls', 0) or r.get('returnedBytes', 0):
            print(name + ': ' + json.dumps(r, sort_keys=True))
raise SystemExit(1 if totals['failures'] or totals['errors'] else 0)
