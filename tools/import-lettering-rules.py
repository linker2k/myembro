#!/usr/bin/env python3
"""Import ONLY explicit machine-readable approvals from embro-codex's one rule source.

No numbers are inferred from prose, tables, bounding boxes or font-quality scores.
Usage: python3 tools/import-lettering-rules.py [path/to/auto-rules.md]
The source can contain a fenced json block: {"schemaVersion":1,"profiles":[...]}.
Without that approved block the snapshot stays empty: S2 permits running only.
"""
import hashlib
import json
from pathlib import Path
import re
import sys

source = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/myembro-quality-gate/auto-rules.md")
raw = source.read_bytes()
blocks = [json.loads(block) for block in re.findall(r"```json\s*\n(.*?)\n```", raw.decode(), re.S)]
approvals = [block for block in blocks if isinstance(block, dict) and "profiles" in block]
if len(approvals) > 1:
    raise ValueError("Expected a single approved lettering-rules block")
rules = approvals[0] if approvals else {"schemaVersion": 1, "profiles": []}
if rules.get("schemaVersion") != 1 or not isinstance(rules.get("profiles"), list):
    raise ValueError("Unsupported approved rule schema")
# Preserve approved values verbatim; engine policy validation rejects invalid/unsupported methods.
rules = dict(rules, source=source.name, sourceSha256=hashlib.sha256(raw).hexdigest())
target = Path(__file__).resolve().parents[1] / "engine/src/main/resources/lettering/validated-ranges.json"
target.write_text(json.dumps(rules, ensure_ascii=False, indent=2) + "\n")
print(f"{target}: {len(rules['profiles'])} approved profiles; source SHA256 {rules['sourceSha256']}")
