#!/usr/bin/env python3
"""Isolated pyembroidery 1.5.1 reader oracle; consumes JVM writer bytes without re-encoding.
Counts differences independently: positional coordinates, commands, color-boundary positions,
RGB, thread identity. Full per-file counts are retained; oracle differences fail the run.
"""
import hashlib, importlib.metadata, io, json, pathlib, sys
from itertools import zip_longest
import pyembroidery as pe
assert sys.flags.isolated
assert importlib.metadata.version('pyembroidery') == '1.5.1'
root = pathlib.Path(sys.argv[1]); rows = []
def row(p):
    names = {getattr(pe,n): n for n in ('STITCH','JUMP','TRIM','STOP','COLOR_CHANGE','END')}
    return {'stitches': [[int(x) if x==int(x) else x,int(y) if y==int(y) else y,names[c & pe.COMMAND_MASK]] for x,y,c in p.stitches],
            'threads': [{'color': t.color & 0xffffff, 'description': t.description, 'catalog': t.catalog_number, 'brand': t.brand, 'chart': t.chart} for t in p.threadlist]}
def diff(a,b):
    pairs=list(zip_longest(a['stitches'],b['stitches']))
    boundaries=lambda p: [[i,s[0],s[1],s[2]] for i,s in enumerate(p['stitches']) if s[2] in ('STOP','COLOR_CHANGE')]
    return {'coordinates':sum(x is None or y is None or x[:2]!=y[:2] for x,y in pairs),
            'needleCoordinates':sum(x!=y for x,y in zip_longest([s[:2] for s in a['stitches'] if s[2]=='STITCH'],[s[:2] for s in b['stitches'] if s[2]=='STITCH'])),
            'colorBoundaryCoordinates':sum(x!=y for x,y in zip_longest([s for s in a['stitches'] if s[2] in ('STOP','COLOR_CHANGE')],[s for s in b['stitches'] if s[2] in ('STOP','COLOR_CHANGE')])),
            'commands':sum(x is None or y is None or x[2]!=y[2] for x,y in pairs),
            'colorBoundaries':sum(x!=y for x,y in zip_longest(boundaries(a),boundaries(b))),
            'rgb':sum(x!=y for x,y in zip_longest([t['color'] for t in a['threads']],[t['color'] for t in b['threads']])),
            'threadIdentity':sum(x!=y for x,y in zip_longest(a['threads'],b['threads']))}
for name in json.loads((root/'manifest.json').read_text()):
    source=json.loads((root/f'{name}.source.json').read_text())
    for ext in ('jef','exp','vp3'):
        data=(root/f'{name}.{ext}').read_bytes(); p=pe.EmbPattern()
        getattr(pe, ext.capitalize()+'Reader' if ext!='vp3' else 'Vp3Reader').read(io.BytesIO(data),p)
        expected=row(p); actual=json.loads((root/f'{name}.{ext}.json').read_text())
        (root/f'{name}.{ext}.oracle.json').write_text(json.dumps(expected))
        rows.append({'name':name,'format':ext,'source':diff(source,actual),'oracle':diff(expected,actual)})
(root/'results.json').write_text(json.dumps(rows,indent=2)+'\n')
summary={}
for ext in ('jef','exp','vp3'):
    cases=[r for r in rows if r['format']==ext]
    summary[ext]={kind:{key:sum(r[kind][key] for r in cases) for key in cases[0][kind]} for kind in ('source','oracle')}
    summary[ext]['oracleMatches']=sum(not any(r['oracle'].values()) for r in cases)
    summary[ext]['sourceMatches']=sum(not any(r['source'][k] for k in ('coordinates','commands','colorBoundaries')) for r in cases)
    print(ext.upper(),len(cases),json.dumps(summary[ext]))
(root/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
sources={m.__name__:hashlib.sha256(pathlib.Path(m.__file__).read_bytes()).hexdigest() for m in (pe.JefReader,pe.ExpReader,pe.Vp3Reader)}
(root/'provenance.json').write_text(json.dumps({'package':'pyembroidery','version':'1.5.1','license':'MIT','sources':sources},indent=2)+'\n')
sys.exit(0 if all(not any(r['oracle'].values()) for r in rows) else 1)
