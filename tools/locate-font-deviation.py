#!/usr/bin/env python3
"""Locate both directed Hausdorff maxima, with decoded needle/command/run indices and SVG elements."""
import argparse,json,pathlib,re,subprocess,xml.etree.ElementTree as ET
import numpy as np
from scipy.spatial import cKDTree
p=argparse.ArgumentParser();p.add_argument('--manifest',default='/tmp/myembro-port-out/letter/font-defaults/manifest.json');p.add_argument('--out',default='/tmp/myembro-port-out/letter/font-defaults/deviation');a=p.parse_args()
out=pathlib.Path(a.out);out.mkdir(parents=True,exist_ok=True);oracle=pathlib.Path('/tmp/myembro-inkstitch-oracle')
python=pathlib.Path('/tmp/myembro-quality-gate/quality-gate').read_text().splitlines()[0][2:]
def decode(path):
 code="import sys,json;sys.path.insert(0,'/tmp/myembro-inkstitch-oracle');from export_coordinates import snapshot;print(json.dumps(snapshot(__import__('pathlib').Path(sys.argv[1]))))"
 return json.loads(subprocess.check_output([python,'-c',code,str(path)],text=True))
def source_paths(record):
 provenance=record.get('layout_provenance',record.get('mapping',{}));svg=pathlib.Path(provenance['path'])
 paths=[]
 def walk(node,glyph=None):
  if node.get('{http://inkstitch.org/namespace}letter-group')=='glyph':glyph=node.get('{http://www.inkscape.org/namespaces/inkscape}label')
  if node.tag.endswith('}path'):
   count=len(re.findall('[Mm]',node.get('d','')))
   paths.extend(dict(glyph=glyph,element_id=node.get('id'),subpath=i,input_svg=str(svg),d=node.get('d')) for i in range(count))
  for child in node:walk(child,glyph)
 walk(ET.parse(svg).getroot());return paths
cases=json.loads(pathlib.Path(a.manifest).read_text())['cases'];assert 0<len(cases)<=10
rows=[];table=['| 사례 | 방향 | 바늘→최근접 인덱스(0부터) | 중심 정규화 좌표 mm → 최근접 mm | 거리 mm | 원본 요소 |','|---|---|---|---|---:|---|']
for c in cases:
 record=json.loads((oracle/'out/defaults-v2/fonts'/(c['id']+'.oracle.json')).read_text())
 ad=decode(record['path']);bd=decode(c['path']);src=source_paths(record)
 an=np.asarray(ad['needles_mm'])+ad['normalization_translation_mm'];bn=np.asarray(bd['needles_mm'])+bd['normalization_translation_mm']
 for direction,x,y,xd,yd in [('oracle→port',an,bn,ad,bd),('port→oracle',bn,an,bd,ad)]:
  distances,nearest=cKDTree(y).query(x);maxdist=float(distances.max())
  indices=np.flatnonzero(np.abs(distances-maxdist)<1e-8)
  for i in indices[:10]:
   j=int(nearest[i]);i=int(i)
   # The official run is the semantic anchor. Never assume collapsed port runs map one-to-one.
   oi=i if direction=='oracle→port' else j
   run=next((k for k,r in enumerate(ad['runs']) if r['start_needle']<=oi<=r['end_needle']),None)
   elem=src[run] if run is not None and len(src)==len(ad['runs']) else {'unresolved':'SVG subpath/run cardinality differs'}
   command_indices=[k for k,e in enumerate(xd['commands']) if e[2]==0]
   row=dict(id=c['id'],direction=direction,needle_index=i,nearest_needle_index=j,command_index=command_indices[i],distance_mm=maxdist,
    raw_mm=xd['needles_mm'][i],normalized_mm=x[i].tolist(),nearest_raw_mm=yd['needles_mm'][j],nearest_normalized_mm=y[j].tolist(),
    official_run_index=run,source_element=elem,source_run_count=len(ad['runs']),source_subpath_count=len(src),
    needle_neighborhood=xd['needles_mm'][max(0,i-2):i+3],nearest_neighborhood=yd['needles_mm'][max(0,j-2):j+3],
    oracle_sha256=ad['sha256'],port_sha256=bd['sha256'])
   rows.append(row);label=f"{elem.get('glyph','?')} / {elem.get('element_id','?')} / subpath {elem.get('subpath','?')}"
   table.append(f"| {c['id']} | {direction} | {i} → {j} | {row['normalized_mm']} → {row['nearest_normalized_mm']} | {maxdist:.6f} | {label} |")
(out/'max-deviations.json').write_text(json.dumps({'normalization':'bbox-center translation only','cases':rows,'commit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()},indent=2)+'\n')
(out/'max-deviations.md').write_text('\n'.join(table)+'\n');print('\n'.join(table))
