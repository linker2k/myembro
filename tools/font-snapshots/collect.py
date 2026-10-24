#!/usr/bin/env python3
"""Collect only our instrumentation's flat files and pin their byte sizes/checksums."""
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tarfile

archive, output = Path(sys.argv[1]), Path(sys.argv[2])
output.mkdir(parents=True, exist_ok=True)
with tarfile.open(archive) as tar:
    assert 'CAPTURE_OK' in {m.name.removeprefix('./') for m in tar.getmembers()}, 'Android capture did not complete'
    for member in tar.getmembers():
        name = member.name.removeprefix('./')
        if not member.isfile():
            continue
        if '/' in name or name.startswith('.'):
            raise ValueError(f'Unexpected capture path: {name}')
        if name.endswith(('.json.gz', '.pes', '.myembro')) or name in ('cases.json', 'CAPTURE_OK'):
            data = tar.extractfile(member).read()
            if name.endswith('.myembro'):
                name += '.gz'
                data = gzip.compress(data, mtime=0)
            (output / name).write_bytes(data)
assert (output / 'CAPTURE_OK').read_text().startswith('PASS ')
licenses = {
    'jua-OFL.txt': 'app/src/main/assets/lettering/jua/OFL.txt',
    'noto-sans-kr-OFL.txt': 'app/src/main/assets/licenses/noto_sans_kr_OFL.txt',
    'gowun-batang-OFL.txt': 'app/src/main/assets/licenses/gowun_batang_OFL.txt',
}
for name, source in licenses.items():
    shutil.copyfile(source, output / name)
files = {}
for path in sorted(output.iterdir()):
    if path.name in ('manifest.json', 'README.md') or not path.is_file():
        continue
    data = path.read_bytes()
    entry = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
    if path.name.endswith('.json.gz'):
        raw = gzip.decompress(data)
        document = json.loads(raw)
        entry.update(json_bytes=len(raw), glyphs=len(document['glyphs']), provenance=document['provenance'])
    files[path.name] = entry
manifest = {'schemaVersion': 1, 'source': 'Android emulator-5556; FontSnapshotInstrumentation',
            'files': files, 'total_bytes': sum(f['bytes'] for f in files.values()),
            'snapshot_gzip_bytes': sum(f['bytes'] for n, f in files.items() if n.endswith('.json.gz')),
            'snapshot_json_bytes': sum(f['json_bytes'] for f in files.values() if 'json_bytes' in f)}
(output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print((output / 'CAPTURE_OK').read_text().strip())
print(f"SNAPSHOT_SIZE gzip={manifest['snapshot_gzip_bytes']} JSON={manifest['snapshot_json_bytes']} total={manifest['total_bytes']} bytes")
