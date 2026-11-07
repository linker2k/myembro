#!/usr/bin/env python3
"""Freeze only three CC0 sample cases after a complete four-format comparison."""
import hashlib,json,pathlib,shutil,sys
repo=pathlib.Path(sys.argv[1]);out=pathlib.Path(sys.argv[2]);summary=json.loads((out/'summary.json').read_text())
assert set(summary)=={'dst','exp','jef','vp3'}
assert all(s['total']==136 and s['match']==136 for s in summary.values())
root=repo/'engine/src/test/resources/format-writers';dest=root/'samples';dest.mkdir(exist_ok=True)
provenance=json.loads((root/'provenance.json').read_text())
for name in ['sample_02_lightning','sample_03_crown','sample_05_rocket']:
    for ext in ['pes','dst','exp','jef','vp3']:
        source=repo/'app/src/main/assets/samples'/f'{name}.pes' if ext=='pes' else out/'python'/f'{name}.{ext}'
        target=dest/f'{name}.{ext}';shutil.copyfile(source,target);b=target.read_bytes()
        provenance['outputs'][f'samples/{name}.{ext}']={'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()}
(root/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
print('Frozen3 CC0 sample sources +12 original format outputs; remaining136-case outputs stay local')
