#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Summarize our catalog's observations; never execute Ink/Stitch or change its defaults."""
import collections
import csv
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

output = Path(sys.argv[1])
root = Path(__file__).resolve().parents[2]
rows = json.loads((output / 'results.json').read_text())
assert len(rows) == 160
assert len({r['tile'] for r in rows if r['method'] == 'meander_fill'}) == 75
xml = root / 'engine/build/test-results/test/TEST-io.github.linker2k.myembro.engine.research.TextureCatalogTest.xml'
suite = ET.parse(xml).getroot()
assert suite.attrib['failures'] == suite.attrib['errors'] == '0'
(output / 'case-output.txt').write_text(suite.findtext('system-out', ''))
counts = collections.Counter(r['status'] for r in rows)
summary = [f"JUnit: {suite.attrib['tests']} test; 0 failures/errors; {suite.attrib['time']} seconds",
           f"TEXTURE_CATALOG COMPLETE cases={len(rows)} tiles=75 shapes=2 statuses={dict(counts)}",
           'Peak penetrations / 0.5mm cell: ' + str(max(r['peak_cell_0_5mm'] or 0 for r in rows)),
           'GUIDED: excluded, no guideline input (no silent substitution counted as guided)']
summary.extend(r['id'] + ': ' + r['reason'] for r in rows if r['status'] in ('ERROR', 'BLOCK'))
(output / 'summary.txt').write_text('\n'.join(summary) + '\n')
with (output / 'results.csv').open('w', newline='') as stream:
    writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator='\n')
    writer.writeheader()
    writer.writerows(rows)
print('\n'.join(summary))
print('Artifacts:', output)
