#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Apply the authorized v6 repetition-only revision to the independent Python gate.
Preserves a complete v5 executable snapshot before changing it. No threshold recalibration.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--gate-root',type=Path,default=Path('/tmp/myembro-quality-gate'))
a=p.parse_args();root=a.gate_root
code=(root/'gate.py').read_text()
if 'VERSION=5\n' in code:
    assert hashlib.sha256(code.encode()).hexdigest()=='a49e0bd4c8ae485e6ea7d0eb1a05692c47d71c20f389223211c7f7e4a5c4319a'
    backup=root/'versions'/'frozen-v5-before-motion-repeat';backup.mkdir(parents=True,exist_ok=True)
    for name in ('gate.py','lock_geometry.py','thresholds.json','POLICY.md','quality-gate','tool-manifest.json'):
        shutil.copy2(root/name,backup/name)
    code=code.replace('VERSION=5\n','VERSION=6\n',1)
    needle=' events,_=decode(path)\n sections=[]'
    replacement=''' events,_=decode(path)
 from quality_gate_repeat import maximum_consecutive_positions
 m['max_same_point_needle_only']=m['max_same_point_consecutive']
 m['max_same_point_consecutive']=maximum_consecutive_positions(events)
 sections=[]'''
    assert needle in code;code=code.replace(needle,replacement,1)
    (root/'gate.py').write_text(code)
else:
    assert 'VERSION=6\n' in code and 'maximum_consecutive_positions(events)' in code
shutil.copy2(Path(__file__).with_name('quality_gate_repeat.py'),root/'quality_gate_repeat.py')
c=json.loads((root/'thresholds.json').read_text());c['version']=6;c['limits']['max_same_point_consecutive']=2
c['repeat_policy']='Explicit STITCH/JUMP destinations; controls ignored; zero moves included; >2 blocks generated output. Imported repetition is caution.'
c['repeat_corpus_evidence']={'files':334,'needle_only_histogram':{'1':173,'2':158,'3':1,'5':2},'needle_only_p99':2.0,
 'motion_inclusive_histogram':{'1':39,'2':286,'3':7,'5':2},'motion_inclusive_p99':3.0,
 'decision':'Use needle-only p99=2 as the stricter motion-inclusive limit, explicitly requested after SE400 start incident.'}
(root/'thresholds.json').write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n')
policy=root/'POLICY.md';text=policy.read_text()
if not text.startswith('# v6'):
    policy.write_text('# v6 반복 기준 개정 — SE400 실기 시작 사고 후 사용자 지시\n\n'
     '앱 출력: STITCH/JUMP 도착 위치 연속 2회 초과 차단(0길이 바늘·점프 포함). 제어 명령은 집계하지 않는다.\n'
     '가져온 PES는 반복 초과를 주의로 유지한다. 밀집 30회 초과 등 기존 차단 정책은 유지한다.\n'
     '334개 바늘 전용 p99=2; JUMP 포함 p99=3이다. 새 임계값 2는 사용자 지정 강화 정책이며 새 정의의 p99라고 주장하지 않는다.\n\n'+text)
manifest=json.loads((root/'tool-manifest.json').read_text());manifest['tool_version']=6;manifest['parent_version']=5
for name in ('gate.py','quality_gate_repeat.py','thresholds.json','POLICY.md'):
    manifest['sha256'][name]=hashlib.sha256((root/name).read_bytes()).hexdigest()
(root/'tool-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print('Applied v6: STITCH/JUMP same-position limit 2; other thresholds unchanged')
