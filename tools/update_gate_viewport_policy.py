#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Install exact PEC rule 5; HoopSpec JSON must be emitted by :engine:exportGateHoop first."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--gate-root',type=Path,default=Path('/tmp/myembro-quality-gate'))
a=p.parse_args();root=a.gate_root
assert (root/'hoop-spec.json').is_file(), 'Run :engine:exportGateHoop -PgateHoopOutput=<gate>/hoop-spec.json'
shutil.copy2(Path(__file__).with_name('quality_gate_viewport.py'),root/'quality_gate_viewport.py')
code=(root/'gate.py').read_text()
if "m['pec_viewport']=" not in code:
    needle=' events,_=decode(path)\n from quality_gate_repeat'
    assert needle in code
    code=code.replace(needle," events,_=decode(path)\n from quality_gate_viewport import check_viewport,load_hoop\n m['pec_viewport']=check_viewport(pathlib.Path(path).read_bytes(),events,load_hoop(pathlib.Path(__file__).with_name('hoop-spec.json')))\n from quality_gate_repeat",1)
    start=code.index(" bbox=m['needle_bbox_mm'];extent=",code.index('def judge('))
    end=code.index(" return {'verdict':",start)
    code=code[:start]+''' viewport=dict(m['pec_viewport'])
 errors=list(viewport['errors'])
 if not apply_hoop:errors=[e for e in errors if not e.startswith('PEC_HOOP:')]
 if hoop_bounds_mm is not None and apply_hoop:
  from decimal import Decimal
  values=[Decimal(str(v))*10 for v in hoop_bounds_mm]
  if len(values)!=4 or any(not v.is_finite() or v!=v.to_integral_value() for v in values) or values[0]!=0 or values[1]!=0 or min(values[2:])<=0:
   raise ValueError('Exact PEC hoop bounds require 0,0,width,height in 0.1mm units')
  errors=[e for e in errors if not e.startswith('PEC_HOOP:')]
  if viewport['max_x']>=values[2] or viewport['max_y']>=values[3]:errors.append('PEC_HOOP: encoded maximum exceeds selected hoop')
 reasons.extend(errors)
 hoop={'status':'BLOCK' if errors else 'PASS','viewport_native':viewport,'errors':errors,'integer_units':'0.1mm','boundary':'exclusive size bound','source':'HoopSpec'}
''' + code[end:]
    (root/'gate.py').write_text(code)
# Bind the limits used by the independent gate to Kotlin's exported constants too.
code=(root/'gate.py').read_text()
assert 'max_stitch_delta_squared_units' in code, 'Install gate v7/P8 integer-length revision first'
if "policy_limits=load_limits" not in code:
    code=code.replace(" reasons=[];warnings=[];l=c['limits'];n=m['stitch_count']", " from quality_gate_viewport import load_limits\n policy_limits=load_limits(pathlib.Path(__file__).with_name('hoop-spec.json'))\n reasons=[];warnings=[];l=dict(c['limits']);l['cell_peak_05mm']=policy_limits['max_generated_penetrations'];l['max_same_point_consecutive']=policy_limits['max_consecutive_positions'];n=m['stitch_count']")
    code=code.replace("m['max_stitch_delta_squared_units']>120**2", "m['max_stitch_delta_squared_units']>policy_limits['max_stitch_units_0p1mm']**2")
    code=code.replace("{120**2}","{policy_limits['max_stitch_units_0p1mm']**2}")
    (root/'gate.py').write_text(code)
# Share the actual count/length predicate with CI's Python boundary tests.
code=(root/'gate.py').read_text()
if 'integer_safety_findings(' not in code:
    start=code.index(" if m['cell_peak_05mm']>",code.index('def judge('))
    end=code.index(' # Object identity',start)
    code=code[:start]+" from quality_gate_viewport import integer_safety_findings\n hard,soft=integer_safety_findings(m['cell_peak_05mm'],m['max_same_point_consecutive'],m['max_stitch_delta_squared_units'],origin,policy_limits)\n reasons.extend(hard);warnings.extend(soft)\n"+code[end:]
    (root/'gate.py').write_text(code)
assert 'policy_limits=load_limits' in code
code=(root/'gate.py').read_text()
if "m['zero_motion_count']=" not in code:
    code=code.replace(" from quality_gate_viewport import check_viewport,load_hoop", " from quality_gate_viewport import check_viewport,load_hoop,stationary_motion_count\n m['zero_motion_count']=stationary_motion_count(events)")
    code=code.replace("],origin,policy_limits)", "],origin,policy_limits,m['zero_motion_count'])")
    (root/'gate.py').write_text(code)
# Re-evaluate ALL physical-size predicates for an explicitly selected custom hoop.
code=(root/'gate.py').read_text()
start=code.index(" viewport=",code.index('def judge('))
end=code.index(" return {'verdict':",start)
code=code[:start]+""" from quality_gate_viewport import load_hoop,reassess_viewport
 selected_hoop=load_hoop(pathlib.Path(__file__).with_name('hoop-spec.json'))
 if hoop_bounds_mm is not None and apply_hoop:
  from decimal import Decimal
  values=[Decimal(str(v))*10 for v in hoop_bounds_mm]
  if len(values)!=4 or any(not v.is_finite() or v!=v.to_integral_value() for v in values) or values[0]!=0 or values[1]!=0 or min(values[2:])<=0:
   raise ValueError('Exact PEC hoop bounds require 0,0,width,height in 0.1mm units')
  selected_hoop=(int(values[2]),int(values[3]))
 viewport=reassess_viewport(m['pec_viewport'],selected_hoop,policy_limits['hoop_safety_margin_units_0p1mm'])
 errors=list(viewport['errors'])
 if not apply_hoop:errors=[e for e in errors if not e.startswith(('PEC_HOOP:','PEC_SAFETY_MARGIN:'))]
 reasons.extend(errors)
 hoop={'status':'BLOCK' if errors else 'PASS','viewport_native':viewport,'errors':errors,'size_units':selected_hoop,'integer_units':'0.1mm','boundary':'exclusive size bound','source':'HoopSpec'}
"""+code[end:]
(root/'gate.py').write_text(code)
policy=root/'POLICY.md'
text=policy.read_text()
if '# Exact PEC rule 5' not in text:
    policy.write_text('# Exact PEC rule 5\n\nActual encoded STITCH/JUMP destinations only (no implicit origin): min x=y=0; maximum < selected HoopSpec width/height; PEC header dimensions == actual max-min exactly. Native 0.1mm integers; no epsilon. Invalid files block for both origins. Corpus mode omits only hoop-size enforcement, never origin/header checks.\n\n'+text)
text=policy.read_text()
if '# P2a safety margin' not in text:
    margin=json.loads((root/'hoop-spec.json').read_text())['limits']['hoop_safety_margin_units_0p1mm']
    policy.write_text(f'# P2a safety margin\n\nRuntime constant HOOP_SAFETY_MARGIN_UNITS_0P1MM={margin}. Design width/height <= hoop size minus {2*margin} native units; physical placement must keep at least {margin} units from every edge. This is a user safety policy, not a measured machine tolerance.\n\n'+text)
manifest=json.loads((root/'tool-manifest.json').read_text())
for name in ('gate.py','quality_gate_viewport.py','hoop-spec.json','POLICY.md'):
    manifest['sha256'][name]=hashlib.sha256((root/name).read_bytes()).hexdigest()
(root/'tool-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print('Installed exact PEC viewport policy (native integer units, HoopSpec config)')
