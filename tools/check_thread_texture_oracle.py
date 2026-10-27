#!/usr/bin/env python3
"""Compare already-generated text traces. Never downloads or launches upstream software."""
import argparse
import hashlib
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('original', type=Path)
parser.add_argument('port', type=Path)
args = parser.parse_args()
originals = sorted(args.original.glob('*.trace'))
if not originals:
    parser.error('No original *.trace files')
original_names = {p.name for p in originals}
port_names = {p.name for p in args.port.glob('*.trace')}
failed = original_names != port_names
if failed:
    print(f'FILE_SET_MISMATCH missing={sorted(original_names-port_names)} extra={sorted(port_names-original_names)}')
total_events = total_lines = 0
print('case\tevents\tlines\tfirst_difference_0based\tsha256\tresult')
for source in originals:
    target = args.port / source.name
    expected = source.read_bytes()
    actual = target.read_bytes() if target.exists() else b''
    left, right = expected.splitlines(), actual.splitlines()
    first = next((i for i, (a, b) in enumerate(zip(left, right)) if a != b), None)
    if first is None and len(left) != len(right):
        first = min(len(left), len(right))
    same = expected == actual
    total_events += len(left)
    total_lines += sum(s.startswith(b"LINE ") for s in left)
    if not same and first is None:
        first = len(left)  # Different terminal newline/framing after equal events.
    failed |= not same
    print(f'{source.stem}\t{len(left)}\t{sum(s.startswith(b"LINE ") for s in left)}\t{first if first is not None else "none"}\t{hashlib.sha256(expected).hexdigest()}\t{"PASS" if same else "FAIL"}')
    if first is not None:
        print(f'  expected={left[first] if first<len(left) else "EOF"!r}')
        print(f'  actual={right[first] if first<len(right) else "EOF"!r}')
print(f'{"FAIL" if failed else "PASS"}: {len(originals)} cases; {total_events} events; {total_lines} lines')
raise SystemExit(1 if failed else 0)
