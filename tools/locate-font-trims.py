#!/usr/bin/env python3
"""Decompose observed trim/jump boundaries by line, glyph, SVG element and subpath (<=10 cases)."""
import argparse,json,pathlib,re,subprocess,xml.etree.ElementTree as ET
import numpy as np
p=argparse.ArgumentParser();p.add_argument('--manifest',default='/tmp/myembro-port-out/letter/font-defaults/manifest.json');p.add_argument('--out',default='/tmp/myembro-port-out/letter/font-defaults/trims');a=p.parse_args()
root=pathlib.Path('/tmp/myembro-inkstitch-oracle');out=pathlib.Path(a.out);out.mkdir(parents=True,exist_ok=True)
python=pathlib.Path('/tmp/myembro-quality-gate/quality-gate').read_text().splitlines()[0][2:]
def decode(path):
 code="import sys,json;sys.path.insert(0,'/tmp/myembro-inkstitch-oracle');from export_coordinates import snapshot;print(json.dumps(snapshot(__import__('pathlib').Path(sys.argv[1]))))"
 return json.loads(subprocess.check_output([python,'-c',code,str(path)],text=True))
def svg_subpaths(path):
 tree=ET.parse(path).getroot();box=list(map(float,tree.get('viewBox').split()))
 def px(v):return float(v[:-2])*96/25.4 if v.endswith('mm') else float(v)
 scale=round(min(px(tree.get('width'))/box[2],px(tree.get('height'))/box[3]),6)
 matrix=np.array([[scale,0,-box[0]],[0,scale,-box[1]],[0,0,1.]])
 result=[];line_count=0
 def walk(node,m,glyph=None,line=0):
  nonlocal line_count
  for kind,values in re.findall(r'(translate|scale|matrix)\(([^)]*)\)',node.get('transform','')):
   v=list(map(float,re.split(r'[ ,]+',values.strip())));t=np.eye(3)
   if kind=='translate':t[0,2]=v[0];t[1,2]=v[1] if len(v)>1 else 0
   elif kind=='scale':t[0,0]=v[0];t[1,1]=v[-1]
   else:t=np.array([[v[0],v[2],v[4]],[v[1],v[3],v[5]],[0,0,1]])
   m=m@t
  group=node.get('{http://inkstitch.org/namespace}letter-group')
  if group=='line':line=line_count;line_count+=1
  if group=='glyph':glyph=node.get('{http://www.inkscape.org/namespaces/inkscape}label')
  if node.tag.endswith('}path'):
   d=node.get('d','');assert not re.search('[a-zACHQSTVZ]',d),'Representative SVG must use absolute M/L only'
   for i,part in enumerate(re.split('M',d)[1:]):
    numbers=list(map(float,re.findall(r'[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?',part)))
    points=[(m@np.array([x,y,1]))[:2]*25.4/96 for x,y in zip(numbers[::2],numbers[1::2])]
    result.append(dict(line=line,glyph=glyph,element=node.get('id'),subpath=i,points=points))
  for child in node:walk(child,m,glyph,line)
 walk(tree,matrix)
 pts=np.array([p for s in result for p in s['points']]);center=(pts.min(0)+pts.max(0))/2
 for s in result:s['start']=(s['points'][0]-center).tolist();s['end']=(s['points'][-1]-center).tolist();del s['points']
 return result

def boundaries(data):
 result=[];sewn=False;pending_trim=False;previous=None;n=0
 commands=data['commands'];shift=np.array(data['normalization_translation_mm'])
 for i,(x,y,cmd) in enumerate(commands):
  if cmd==0:sewn=True;previous=np.array([x,y])+shift;n+=1
  elif cmd==2:pending_trim=True
  elif cmd==1 and sewn:
   next_needle=next((np.array(e[:2])+shift for e in commands[i+1:] if e[2]==0),None)
   if next_needle is not None:result.append(dict(needle_index=n,command_index=i,from_mm=previous.tolist(),to_mm=next_needle.tolist(),trim=pending_trim))
   sewn=False;pending_trim=False
  elif cmd in (3,4,5):sewn=False;pending_trim=False
 return result
cases=json.loads(pathlib.Path(a.manifest).read_text())['cases'];assert 0<len(cases)<=10
rows=[];table=['| 사례 | 경계 | 공식 trim / 포트 trim | 공식 바늘 경계 | 정규화 이동 mm |','|---|---|---|---:|---|']
for c in cases:
 record=json.loads((root/'out/defaults-v2/fonts'/(c['id']+'.oracle.json')).read_text());prov=record.get('layout_provenance',record.get('mapping'))
 source=svg_subpaths(prov['path']);official=decode(record['path']);port=decode(c['path']);ob=boundaries(official);pb=boundaries(port)
 for boundary_index,b in enumerate(ob):
  before=min(source,key=lambda s:np.linalg.norm(np.array(s['end'])-b['from_mm']))
  after=min(source,key=lambda s:np.linalg.norm(np.array(s['start'])-b['to_mm']))
  near=min(pb,key=lambda z:np.linalg.norm(np.array(z['from_mm'])-b['from_mm'])+np.linalg.norm(np.array(z['to_mm'])-b['to_mm'])) if pb else None
  matched=near is not None and max(np.linalg.norm(np.array(near['from_mm'])-b['from_mm']),np.linalg.norm(np.array(near['to_mm'])-b['to_mm']))<=.2
  if len(source)==len(ob)+1:
   candidate_before,candidate_after=source[boundary_index:boundary_index+2]
   if max(np.linalg.norm(np.array(candidate_before['end'])-b['from_mm']),np.linalg.norm(np.array(candidate_after['start'])-b['to_mm']))<=.2:
    before,after=candidate_before,candidate_after
  label=lambda e:f"L{e['line']+1} {e['glyph']}/{e['element']}:{e['subpath']}"
  r=dict(id=c['id'],boundary=label(before)+' → '+label(after),official=b,port=near if matched else None,
   from_element=before,to_element=after,oracle_sha256=official['sha256'],port_sha256=port['sha256'])
  rows.append(r);table.append(f"| {c['id']} | {r['boundary']} | {b['trim']} / {near['trim'] if matched else 'unmatched'} | {b['needle_index']} | {b['from_mm']} → {b['to_mm']} |")
(out/'trim-boundaries.json').write_text(json.dumps({'cases':rows,'normalization':'bbox-center translation only','commit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()},indent=2)+'\n')
(out/'trim-boundaries.md').write_text('\n'.join(table)+'\n');print('\n'.join(table))
