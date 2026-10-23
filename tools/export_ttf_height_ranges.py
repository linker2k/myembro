#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Project the coordinator-approved sampled TTF ranges into app slider bounds."""
import hashlib
import json
from pathlib import Path

repo=Path(__file__).resolve().parents[1]
source=repo/'docs/s3-ttf-fill-sweep/summary.json'
report=json.loads(source.read_text())
assert report['status']=='COMPLETE' and report['pending_cases']==0
profiles=[]
for p in report['profiles']:
    assert p['complete']
    bounds=p['longest_sampled_passing_interval']
    if bounds is None or bounds['min_mm']>60: continue
    profiles.append(dict(fontId=p['font_id'],fontSha256=p['font_sha256'],
        minMm=bounds['min_mm'],maxMm=min(60.0,bounds['max_mm'])))
out=repo/'app/src/main/assets/lettering/ttf-fill-height-ranges.json'
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(dict(schemaVersion=1,appMaxHeightMm=60.0,
    source='docs/s3-ttf-fill-sweep/summary.json',sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
    basis='Coordinator-approved slider intervals from 30-glyph sampled sweep; not exhaustive continuous/text certification',
    profiles=profiles),ensure_ascii=False,indent=2)+'\n')
print(f'EXPORTED {len(profiles)} pinned font ranges; max60mm -> {out.relative_to(repo)}')
