#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Independent PEC measurement with explicit renderer component/path provenance. No glyph inference."""
import argparse,json,math,pathlib,sys
p=argparse.ArgumentParser();p.add_argument('before',type=pathlib.Path);p.add_argument('after',type=pathlib.Path);p.add_argument('routing',type=pathlib.Path);p.add_argument('output',type=pathlib.Path);p.add_argument('--before-routing',type=pathlib.Path);p.add_argument('--gate-root',type=pathlib.Path,default=pathlib.Path('/tmp/myembro-quality-gate'));a=p.parse_args()
sys.path.insert(0,str(a.gate_root));import gate
routing=json.loads(a.routing.read_text());components={c['component']:c for c in routing}
assert all(c['discarded_satins']==0 for c in routing)
for c in routing:
 for i,op in enumerate(c['operations']):
  if op['underpath']:
   covered=op['start']
   intervals=sorted((later['start'],later['end']) for later in c['operations'][i+1:] if not later['underpath'] and later['element']==op['element'])
   for start,end in intervals:
    if start<=covered:covered=max(covered,end)
   assert covered>=op['end'], 'Uncovered underpath after union of subsequent split satins'
owner={i:c['component'] for c in routing for i in c['source_path_indices']}
assert sorted(owner)==list(range(len(owner)))
def measure(path,entry_components):
 events,_=gate.decode(path);jumps=[(x,y) for x,y,c in events if c==gate.JUMP];assert len(jumps)==len(entry_components),(len(jumps),len(entry_components))
 rows={id:dict(component=id,character=c['character'],bbox_px=c['bbox_px'],entry_jumps=0,internal_jumps=0,jump_mm=0.) for id,c in components.items()}
 previous=(0,0);prior_component=None;index=0
 for x,y,c in events:
  if c==gate.JUMP:
   component=entry_components[index];index+=1;r=rows[component];r['entry_jumps']+=1;r['jump_mm']+=math.dist(previous,(x,y))/10
   if component==prior_component:r['internal_jumps']+=1
   prior_component=component
  if c in (gate.STITCH,gate.JUMP):previous=(x,y)
 return dict(sha256=gate.sha(path.read_bytes()),rows=list(rows.values()),jump_count=len(jumps),jump_mm=sum(r['jump_mm'] for r in rows.values()),entry_components=entry_components,character_sequence=[components[id]['character'] for id in entry_components])
before_order=[c['component'] for c in json.loads(a.before_routing.read_text())] if a.before_routing else [owner[i] for i in sorted(owner)]
before=measure(a.before,before_order);after=measure(a.after,[c['component'] for c in routing])
assert after['character_sequence']==sorted(after['character_sequence'])
assert all(r['internal_jumps']==0 for r in after['rows'])
g=json.loads(a.after.with_name(a.after.stem+'-gate.json').read_text());assert g['verdict']=='PASS';m=g['results'][0]['metrics']
assert m['zero_motion_count']==m['start_lock_missing']==m['end_lock_missing']==0
assert m['pec_viewport']['min_x']==m['pec_viewport']['min_y']==0
assert len(m['lock_sections'])==len(routing)
assert all(s['start_lock']['present'] and s['end_lock']['present'] for s in m['lock_sections'])
lines=[f'# {a.before.stem} → {a.after.stem} 연결 뭉치 라우팅 비교','','점프는 도착 뭉치/글자에 귀속, 최초 원점 이동 포함. 이동 길이는 실제 PEC JUMP 길이 합.','이전/이후 렌더러의 명시적 뭉치 순서를 사용한다.' if a.before_routing else '원본 path 인덱스→잉크 뭉치 소유권 및 최종 뭉치 순서를 사용한다.','', '| 글자 | 이전 점프 | 이후 점프 | 이전 이동(mm) | 이후 이동(mm) |','|---|---:|---:|---:|---:|']
for index,ch in enumerate('정민'):
 old=[r for r in before['rows'] if r['character']==index];new=[r for r in after['rows'] if r['character']==index]
 lines.append(f"|{ch}|{sum(r['entry_jumps'] for r in old)}|{sum(r['entry_jumps'] for r in new)}|{sum(r['jump_mm'] for r in old):.3f}|{sum(r['jump_mm'] for r in new):.3f}|")
lines+=['', '| 글자·뭉치(봉제순) | bbox(px) | 이전 진입 점프 | 이후 진입 점프 | 이후 내부 점프 |','|---|---|---:|---:|---:|']
for c in routing:
 id=c['component'];old=next(r for r in before['rows'] if r['component']==id);new=next(r for r in after['rows'] if r['component']==id)
 lines.append(f"|{'정민'[c['character']]}·{id}|{c['bbox_px']}|{old['entry_jumps']}|{new['entry_jumps']}|{new['internal_jumps']}|")
lines+=['',f"총 JUMP {before['jump_count']}→{after['jump_count']}, 이동 {before['jump_mm']:.3f}→{after['jump_mm']:.3f}mm. 글자 왕복 제거, 뭉치 내부 JUMP 0. 모든 6개 run 시작/끝 잠금 PASS(최초 이동의 출발 원점에는 잠금 대상 실 없음).",f"게이트 PASS, 0길이0, 원점(0,0), 셀 최대{m['cell_peak_05mm']}, 최대 땀{m['length_mm']['max']:.6f}mm.",'','자모 필순 인식/전통 필순 판정은 하지 않는다. 뭉치는 8방향 잉크 연결 성분이며 줄/순서 규칙은 각 렌더러 커밋 문서를 따른다.','최종 수락은 사용자 봉제. 앱 기본값 연결은 이 실기 샘플 생성 단위에 포함하지 않음.']
a.output.with_suffix('.json').write_text(json.dumps(dict(before=before,after=after,gate_summary={k:m[k] for k in ['start_lock_missing','end_lock_missing','zero_motion_count','cell_peak_05mm']}),ensure_ascii=False,indent=2)+'\n')
a.output.with_suffix('.md').write_text('\n'.join(lines)+'\n');print('\n'.join(lines))
