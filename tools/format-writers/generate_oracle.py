#!/usr/bin/env python3
"""Run with an isolated pyembroidery 1.5.1 venv python -I; never run in Gradle tests."""
import hashlib,io,json,pathlib,sys,importlib.metadata
import pyembroidery
from pyembroidery import DstWriter,ExpWriter,JefWriter,Vp3Writer,EmbThreadJef,EmbEncoder,EmbPattern,EmbThread
assert sys.flags.isolated, 'use python -I'
assert importlib.metadata.version('pyembroidery')=='1.5.1'
root=pathlib.Path(sys.argv[1]).resolve()
fixtures=root/'engine/src/test/resources/format-writers'
checks={}
# Test-only replacements: timestamp, and upstream random filler for the empty VP3 case.
def filler():
    t=EmbThread();t.color=0x112233;return t
EmbPattern.get_random_thread=staticmethod(filler)
for row in json.loads((fixtures/'patterns.json').read_text()):
    p=EmbPattern()
    p.extras.update(row['metadata'])
    for t in row['threads']:
        thread=EmbThread();thread.color=t['color'];thread.description=t.get('description');thread.catalog_number=t.get('catalog');p.add_thread(thread)
    p.stitches=[[x,y,getattr(pyembroidery,c)] for x,y,c in row['stitches']]
    for ext,writer,settings in [('dst',DstWriter,None),('extended.dst',DstWriter,{'version':'extended'}),('exp',ExpWriter,None),('jef',JefWriter,{'date':'20000102030405'}),('vp3',Vp3Writer,None),('trims.jef',JefWriter,{'date':'20000102030405','trims':True})]:
        if ext=='trims.jef' and row['id']!='multicolor':continue
        stream=io.BytesIO()
        try: EmbPattern.write_embroidery(writer,p,stream,settings)
        except IndexError:
            assert row['id']=='empty' and ext=='jef'
            checks['empty.jef']={'error':'IndexError'}
            continue
        name=row['id']+'.'+ext;data=stream.getvalue();(fixtures/name).write_bytes(data)
        checks[name]={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
prior=json.loads((fixtures/'provenance.json').read_text()) if (fixtures/'provenance.json').exists() else {}
checks.update({k:v for k,v in prior.get('outputs',{}).items() if k.startswith('samples/')})
provenance={'package':'pyembroidery','version':'1.5.1','source':'https://github.com/EmbroidePy/pyembroidery','license':'MIT','testOnlyOverrides':{'jefDate':'20000102030405','emptyVp3FillerRgb':'112233'},'sources':{},'outputs':checks}
for module in (DstWriter,ExpWriter,JefWriter,Vp3Writer,EmbThreadJef,EmbEncoder):
    path=pathlib.Path(module.__file__);provenance['sources'][path.name]=hashlib.sha256(path.read_bytes()).hexdigest()
(fixtures/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
print(f'pyembroidery 1.5.1 oracle: {len(checks)} cases, {sum(v.get("bytes",0) for v in checks.values())} bytes')
