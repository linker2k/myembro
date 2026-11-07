#!/usr/bin/env python3
"""Run with an isolated pyembroidery 1.5.1 venv python -I; never run in Gradle tests."""
import hashlib,io,json,pathlib,sys,importlib.metadata
import pyembroidery
from pyembroidery import DstWriter,ExpWriter,EmbEncoder,EmbPattern,EmbThread
assert sys.flags.isolated, 'use python -I'
assert importlib.metadata.version('pyembroidery')=='1.5.1'
root=pathlib.Path(sys.argv[1]).resolve()
fixtures=root/'engine/src/test/resources/format-writers'
checks={}
for row in json.loads((fixtures/'patterns.json').read_text()):
    p=EmbPattern()
    p.extras.update(row['metadata'])
    for t in row['threads']:
        thread=EmbThread();thread.color=t['color'];thread.description=t.get('description');thread.catalog_number=t.get('catalog');p.add_thread(thread)
    p.stitches=[[x,y,getattr(pyembroidery,c)] for x,y,c in row['stitches']]
    for ext,writer,settings in [('dst',DstWriter,None),('extended.dst',DstWriter,{'version':'extended'}),('exp',ExpWriter,None)]:
        stream=io.BytesIO();EmbPattern.write_embroidery(writer,p,stream,settings)
        name=row['id']+'.'+ext;data=stream.getvalue();(fixtures/name).write_bytes(data)
        checks[name]={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
provenance={'package':'pyembroidery','version':'1.5.1','source':'https://github.com/EmbroidePy/pyembroidery','license':'MIT','sources':{},'outputs':checks}
for module in (DstWriter,ExpWriter,EmbEncoder):
    path=pathlib.Path(module.__file__);provenance['sources'][path.name]=hashlib.sha256(path.read_bytes()).hexdigest()
(fixtures/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
print(f'pyembroidery 1.5.1 oracle: {len(checks)} files, {sum(v["bytes"] for v in checks.values())} bytes')
