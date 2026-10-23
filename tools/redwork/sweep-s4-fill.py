#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Measured 30mm synthetic corpus only; excludes failed detail/line-width pairs, no interpolation."""
from pathlib import Path
import json
import os
import sys
import subprocess
from collections import Counter
from PIL import Image
from s4_drawings import drawing

root=Path(__file__).resolve().parents[2]
out=Path('/tmp/myembro-sewtest/s4/fill-sweep');out.mkdir(parents=True,exist_ok=True)
edges=[128,160,192,224,256];widths=[1,2,4,8,10]
if '--aggregate-only' not in sys.argv:
    inputs=[]
    for name in ['FLOWER','HOUSE']:
        for width in widths:
            source=drawing(name,width)
            for edge in edges:
                image=source.resize((edge,edge),Image.Resampling.BILINEAR)
                id=f'{name}-{width}-{edge}'
                image.save(out/(id+'.png'));(out/(id+'.rgb')).write_bytes(image.tobytes())
                box=image.convert('L').point(lambda v:255 if v<128 else 0).getbbox()
                assert box
                inputs.append({'id':id,'motif':name,'sourceStrokePx':width,'edgePx':edge,
                               'bitmapWidthMm':30*edge/max(box[2]-box[0],box[3]-box[1])})
    (out/'inputs.json').write_text(json.dumps(inputs,indent=2)+'\n')
    env=dict(os.environ);env.setdefault('JAVA_HOME','/home/gosh2/.jdks/jdk-17.0.20.1+1')
    subprocess.run([str(root/'gradlew'),':engine:sweepS4Fill','--offline','--console=plain'],cwd=root,env=env,check=True)
rows=json.loads((out/'results.json').read_text())
# Check exported bytes independently too. Failures from either checker are excluded.
paths=[str(out/(r['id']+'.pes')) for r in rows if r['status']!='generation_error']
check=subprocess.run(['/tmp/myembro-quality-gate/quality-gate','check',*paths,'--style','fill',
                      '--json',str(out/'quality-gate.json')],capture_output=True,text=True)
(out/'quality-gate.txt').write_text(check.stdout+check.stderr)
report=json.loads((out/'quality-gate.json').read_text())
# Gate keys are the submitted absolute paths.
for row in rows:
    if row['status']=='generation_error':continue
    case=next((r for r in report['results'] if r['path']==str(out/(row['id']+'.pes'))),None)
    if case is None:raise RuntimeError('Missing independent verdict: '+row['id'])
    row['gateVerdict']=case['verdict']
    if case['verdict']!='PASS':row['status']='blocked'
(out/'results.json').write_text(json.dumps(rows,indent=2)+'\n')
allowed=[];excluded=[]
for edge in edges:
    for width in widths:
        pair={'edgePx':edge,'sourceStrokePx':width}
        selected=[r for r in rows if r['edgePx']==edge and r['sourceStrokePx']==width]
        assert len(selected)==2
        (allowed if all(r['status']=='pass' for r in selected) else excluded).append(pair)
policy={'schemaVersion':1,'scope':'Two synthetic motifs at approximately 30mm; not general photo certification',
        'appliedToApp':False,
        'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
        'workingTreeDirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip()),
        'rule':'Both motifs must generate, complete SewoutCheck with zero blockers, and PASS independent gate',
        'allowedPairs':allowed,'excludedPairs':excluded,'interpolate':False,'counts':dict(Counter(r['status'] for r in rows))}
(out/'detail-stroke-policy.json').write_text(json.dumps(policy,indent=2)+'\n')
print(json.dumps(policy,indent=2))
print('FILL_SWEEP_COMPLETE',len(rows),'cases')
