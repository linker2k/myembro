#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Read-only local rule-5 report. Never copies corpus files or writes their private names."""
import argparse
import collections
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--gate-root',type=Path,default=Path('/tmp/myembro-quality-gate'))
p.add_argument('--corpus',type=Path,required=True)
p.add_argument('--samples',type=Path,default=Path('/tmp/myembro-sewtest'))
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
sys.path.insert(0,str(a.gate_root))
spec=importlib.util.spec_from_file_location('gate',a.gate_root/'gate.py')
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
from quality_gate_viewport import check_viewport,load_hoop
hoop=load_hoop(a.gate_root/'hoop-spec.json')
counts=collections.Counter();failures=[]
for f in sorted(a.corpus.rglob('*')):
    if f.suffix.lower()!='.pes':continue
    data=f.read_bytes();events,_=gate.decode(str(f));r=check_viewport(data,events,hoop)
    counts['files']+=1
    for label,code in [('origin','PEC_ORIGIN:'),('hoop','PEC_HOOP:'),('header','PEC_DIMENSIONS:')]:
        counts[label+'_pass']+=not any(e.startswith(code) for e in r['errors'])
    counts['all_pass']+=r['valid']
    if not r['valid']:failures.append(dict(alias=f'corpus-{counts["files"]:03}',sha256=hashlib.sha256(data).hexdigest(),**r))
paths=['s1/S1SAMPL.PES','s1/S1SAMPL2.PES','s23/S23SAMPL.PES','s23/S23SAM3.PES','s23/S23SAM4.PES',
       's4/S4SAMPL.PES','s4/S4SAMP2.PES','ab/T1PEDS.PES','ab/T2OURS.PES','ab/T3NOZRO.PES']
samples=[]
thresholds=json.loads((a.gate_root/'thresholds.json').read_text())
for rel in paths:
    f=a.samples/rel
    if not f.exists():continue
    events,_=gate.decode(str(f));r=check_viewport(f.read_bytes(),events,hoop)
    m=gate.metric(str(f));decision=gate.judge(m,thresholds)
    samples.append(dict(file=rel,rule5=r,gate_verdict=decision['verdict'],gate_reasons=decision['reasons']))
report=dict(command='report_pec_viewport.py',hoop_units=hoop,corpus=dict(counts),corpus_failures=failures,samples=samples)
a.output.parent.mkdir(parents=True,exist_ok=True)
a.output.with_suffix('.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
rows=['| 대상 | 건수 | 원점 | 후프 범위 | 헤더 일치 | ⑤통과 |',
      '|---|---:|---:|---:|---:|---:|',f'| 로컬 코퍼스 | {counts["files"]} | {counts["origin_pass"]} | {counts["hoop_pass"]} | {counts["header_pass"]} | {counts["all_pass"]} |','',
      '| 시험 파일 | ⑤ | 전체 게이트 | 사유 |','|---|---|---|---|']
for s in samples:
    r=s['rule5'];rows.append(f'| {s["file"]} | {"PASS" if r["valid"] else "BLOCK"} | {s["gate_verdict"]} | {"; ".join(r["errors"]) or "—"} |')
a.output.with_suffix('.md').write_text('\n'.join(rows)+'\n')
print('\n'.join(rows))
