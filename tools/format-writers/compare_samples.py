#!/usr/bin/env python3
"""Compare JVM exports to original writers, using exactly the PesReader-exported JSON.
First run :engine:exportFormatWriterSamples -PformatWriterOutput=<out>.
Then isolated pyembroidery1.5.1 venv python -I this_script <out>.
"""
import hashlib,io,json,pathlib,sys,importlib.metadata
import pyembroidery
from pyembroidery import EmbPattern,EmbThread,DstWriter,ExpWriter,JefWriter,Vp3Writer
assert sys.flags.isolated
assert importlib.metadata.version('pyembroidery')=='1.5.1'
root=pathlib.Path(sys.argv[1]);dest=root/'python';dest.mkdir(exist_ok=True)
rows=[];summary={ext:{'match':0,'total':0,'mismatches':[]} for ext in ('dst','exp','jef','vp3')}
for name in json.loads((root/'manifest.json').read_text()):
    row=json.loads((root/'inputs'/f'{name}.json').read_text());p=EmbPattern();p.extras.update(row['metadata'])
    for t in row['threads']:
        thread=EmbThread();thread.color=t['color'];thread.description=t.get('description');thread.catalog_number=t.get('catalog');thread.brand=t.get('brand');thread.chart=t.get('chart');p.add_thread(thread)
    p.stitches=[[x,y,getattr(pyembroidery,c)] for x,y,c in row['stitches']]
    for ext,writer in [('dst',DstWriter),('exp',ExpWriter),('jef',JefWriter),('vp3',Vp3Writer)]:
        f=io.BytesIO();EmbPattern.write_embroidery(writer,p,f,{'date':'20000102030405'} if ext=='jef' else None)
        expected=f.getvalue();(dest/f'{name}.{ext}').write_bytes(expected);actual=(root/'kotlin'/f'{name}.{ext}').read_bytes()
        first=next((i for i,(a,b) in enumerate(zip(expected,actual)) if a!=b),-1)
        if first<0 and len(actual)!=len(expected):first=min(len(actual),len(expected))
        result={'name':name,'format':ext,'match':first<0,'firstDifferentByteOffset':first,'expectedBytes':len(expected),'actualBytes':len(actual),'sha256':hashlib.sha256(expected).hexdigest()}
        rows.append(result);s=summary[ext];s['total']+=1
        if first<0:s['match']+=1
        else:s['mismatches'].append(result)
(root/'results.json').write_text(json.dumps(rows,indent=2)+'\n');(root/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
for ext,s in summary.items():print(f'{ext.upper()} {s["match"]}/{s["total"]} '+('firstDifferentByteOffset=-1' if not s['mismatches'] else str(s['mismatches'])))
sys.exit(0 if all(not s['mismatches'] for s in summary.values()) else 1)
