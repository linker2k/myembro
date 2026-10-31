#!/usr/bin/env python3
"""Fail closed on missing/duplicate cases, changed errors, or even one changed PES byte hash."""
import json
import sys
from pathlib import Path

def load(folder):
    rows=[json.loads(line) for line in (Path(folder)/'hashes.jsonl').read_text().splitlines()]
    indexed={(row['codepoint'], row['height']):row for row in rows}
    assert len(rows)==len(indexed)==11172*3, 'Missing or duplicate Hangul cases'
    assert set(indexed)=={(c,h) for c in range(0xAC00,0xD7A4) for h in (15,20,30)}
    return indexed
before,after=map(load,sys.argv[1:3])
changed=[key for key in before if before[key]!=after[key]]
pes=sum(row['status']=='PES' for row in before.values())
print(json.dumps({'cases':len(before),'PES_sha256_comparisons':pes,
                  'preexisting_errors':len(before)-pes,'changed':len(changed),
                  'first_differences':changed[:20]},ensure_ascii=False))
sys.exit(bool(changed))
