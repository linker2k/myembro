#!/usr/bin/env python3
"""Freeze selected public cases and measured source differences after compare_samples.py succeeds."""
import hashlib,json,pathlib,shutil,sys
repo,root=map(pathlib.Path,sys.argv[1:]); dest=repo/'engine/src/test/resources/format-readers';dest.mkdir(exist_ok=True)
rows=json.loads((root/'results.json').read_text());assert len(rows)==444 and all(not any(r['oracle'].values()) for r in rows)
names=json.loads((root/'manifest.json').read_text())
selected=[n for n in names if any(s in n for s in ('flower.v6.pes','long_moves.v6.pes','stops.v6.pes','sample_02_lightning','sample_03_crown','sample_05_rocket'))]
entries=[]
for name in selected:
    for ext in ('jef','exp','vp3'):
        target=f'{name}.{ext}';entries.append(target);shutil.copyfile(root/target,dest/target)
        (dest/f'{target}.json').write_text(json.dumps(json.loads((root/f'{target}.oracle.json').read_text()),separators=(',',':'))+'\n')
(dest/'manifest.json').write_text(json.dumps(entries,indent=2)+'\n')
(dest/'source-differences.json').write_text(json.dumps([{k:r[k] for k in ('name','format','source')} for r in rows],separators=(',',':'))+'\n')
shutil.copyfile(root/'summary.json',dest/'summary.json')
provenance=json.loads((root/'provenance.json').read_text())
provenance['outputs']={n:hashlib.sha256((dest/n).read_bytes()).hexdigest() for n in entries}
(dest/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
print('Frozen',len(entries),'public reader cases and',len(rows),'source comparison rows')
