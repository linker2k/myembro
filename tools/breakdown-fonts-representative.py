#!/usr/bin/env python3
"""Same metrics as oracle/breakdown_fonts.py, bounded to <= 10 explicitly listed cases."""
import argparse,collections,json,pathlib,subprocess
import numpy as np
from scipy.spatial import cKDTree
p=argparse.ArgumentParser();p.add_argument('--manifest',default='/tmp/myembro-port-out/letter/font-defaults/manifest.json');p.add_argument('--out',default='/tmp/myembro-port-out/letter/font-defaults/breakdown');a=p.parse_args()
root=pathlib.Path('/tmp/myembro-inkstitch-oracle');out=pathlib.Path(a.out);out.mkdir(parents=True,exist_ok=True)
cases=json.loads(pathlib.Path(a.manifest).read_text())['cases'];assert 0<len(cases)<=10
python=pathlib.Path('/tmp/myembro-quality-gate/quality-gate').read_text().splitlines()[0][2:]
def coordinates(path):
 code="import sys,json;sys.path.insert(0,'/tmp/myembro-inkstitch-oracle');from export_coordinates import snapshot;print(json.dumps(snapshot(__import__('pathlib').Path(sys.argv[1]))))"
 return json.loads(subprocess.check_output([python,'-c',code,str(path)],text=True))
rows=[]
commit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
for c in cases:
 record=json.loads((root/'out/defaults-v2/fonts'/(c['id']+'.oracle.json')).read_text())
 a=coordinates(record['path']);b=coordinates(c['path'])
 an=np.array(a['needles_mm'])+a['normalization_translation_mm'];bn=np.array(b['needles_mm'])+b['normalization_translation_mm']
 different=np.flatnonzero(np.linalg.norm(an[:min(len(an),len(bn))]-bn[:min(len(an),len(bn))],axis=1)>1e-7)
 ac=collections.Counter(c[2] for c in a['commands']);bc=collections.Counter(c[2] for c in b['commands'])
 first=int(different[0]) if len(different) else min(len(an),len(bn)) if len(an)!=len(bn) else None
 rows.append(dict(id=c['id'],oracle_stitches=len(an),port_stitches=len(bn),stitch_ratio_port_over_oracle=len(bn)/len(an),stitch_delta_port_minus_oracle=len(bn)-len(an),
  hausdorff_mm=max(float(cKDTree(an).query(bn)[0].max()),float(cKDTree(bn).query(an)[0].max())),oracle_trims=ac[2],port_trims=bc[2],trim_delta_port_minus_oracle=bc[2]-ac[2],
  oracle_jumps=ac[1],port_jumps=bc[1],jump_delta_port_minus_oracle=bc[1]-ac[1],first_different_needle_index_0based=first,port_sha256=b['sha256'],oracle_sha256=a['sha256']))
(out/'cases.json').write_text(json.dumps({'cases':rows,'normalization':'bbox-center translation only','source_metric_definition':str(root/'breakdown_fonts.py'),'commit':commit},indent=2)+'\n')
lines=['| 사례 | 땀 수(포트/공식) | Hausdorff mm | 트림 Δ | 점프 Δ | 첫 차이 바늘 |','|---|---:|---:|---:|---:|---:|']
for r in rows:lines.append(f"| {r['id']} | {r['port_stitches']}/{r['oracle_stitches']} | {r['hausdorff_mm']:.6f} | {r['trim_delta_port_minus_oracle']} | {r['jump_delta_port_minus_oracle']} | {r['first_different_needle_index_0based']} |")
(out/'cases.md').write_text('\n'.join(lines)+'\n');print('\n'.join(lines))
