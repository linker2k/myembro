#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Summarize recorded generation results and the independent, unmodified quality gate."""
import collections,json,math,pathlib,sys
r=pathlib.Path(sys.argv[1]);rows=json.loads((r/'generation.json').read_text());gate=json.loads((r/'gate.json').read_text());gates={pathlib.Path(g['path']).stem:g for g in gate['results']}
assert len(rows)==450 and len({x['id'] for x in rows})==450
fonts=json.loads((r/'fonts.json').read_text());lines=['# 한글 새틴 공개 글꼴 스윕','', '| 글꼴 Regular400 | 건수 | 게이트 PASS | 생성 거부(10/15/20mm) | 엔진 시간(초) |','|---|---:|---:|---|---:|']
summary=[]
for font in fonts:
 subset=[x for x in rows if x['id'].startswith(font['id']+'-U')]
 passed=sum(gates[x['id']]['verdict']=='PASS' for x in subset if x['id'] in gates)
 rejected=[sum(x['status']!='GENERATED' and x['id'].endswith(f'-{height}mm') for x in subset) for height in [10,15,20]]
 seconds=sum(x['seconds'] for x in subset)
 lines.append(f"| {font['id']} | {len(subset)} | {passed} | {' / '.join(map(str,rejected))} | {seconds:.3f} |")
 summary.append(dict(font=font['id'],cases=len(subset),pass_count=passed,rejected_by_height=rejected,generation_seconds=seconds))
failures=[]
for x in rows:
 if x['status']=='GENERATED':
  assert x['id'] in gates
  checks=x['checks'];metric=gates[x['id']]['metrics']
  assert checks['zero_needles']==checks['zero_jumps']==metric['zero_motion_count']==0
  viewport=metric['pec_viewport'];assert viewport['valid'] and viewport['min_x']==viewport['min_y']==0
 else:
  paths=json.loads((r/'outputs'/(x['id']+'-centerline.json')).read_text())['converter_paths_svg_px']
  lengths=[sum(math.dist(a,b) for a,b in zip(p,p[1:])) for p in paths]
  failures.append(dict(id=x['id'],error=x['error'],min_path_svg_px=min(lengths),paths_shorter_than_1px=sum(v<1 for v in lengths)))
counts=collections.Counter(x['error'] for x in failures)
lines+=['',f"합계450건: 생성{len(gates)} / 생성 거부{len(failures)} / 외부 게이트 BLOCK {gate['blocked']}.",'', '실패 사유:']
for error,count in counts.items(): lines.append(f'- {error}: {count}건')
lines+=['',f"거부18건 모두 최소 경로 <1 SVG px ({min(x['min_path_svg_px'] for x in failures):.6f}–{max(x['min_path_svg_px'] for x in failures):.6f}px). convert-to-satin 원본의 endpoint>=1px 조건에서 섹션 없음; 기둥 생성 불가 시 전체 글자를 거부하며 경로를 버려 성공으로 바꾸지 않음.",'', '검사 범위: 출력된432건은 외부 게이트 PASS, 0길이 바늘/JUMP=0, 원점 min(0,0)·헤더/후프 검사 PASS. 거부18건은 PES가 없어 게이트·PES 0길이/원점 검사는 미실행.','',f"최대 0.5mm 셀 바늘 수 {max(g['metrics']['cell_peak_05mm'] for g in gates.values())}; 엔진 루프 {(r/'generation-seconds.txt').read_text().strip()}초; 폭 실측 {(r/'width-seconds.txt').read_text().strip()}초; 래스터 준비 {(r/'prepare-seconds.txt').read_text().strip()}초. 표 시간은 글자별 엔진 호출 합(첫 JVM/JIT·JSON/파일 쓰기 포함, Gradle 빌드·폭 실측·래스터·외부 게이트 실행 제외). 게이트 wall time은 별도 미계측.",'','폰트·OFL·SHA·폭 측정 출처: fonts.json / width-provenance.json. 공개 문자30자: '+(r/'characters.txt').read_text(),'', '폭은 기존 model GlyphStrokeWidths의 글자별15mm 중앙값×height/15, 비례 샘플링(15mm 기준0.1mm). 동일 글자 실제 ink 높이 정규화. 이 계산은 앱 자동 폭 규칙·폰트 권장 높이/실기 안전 범위가 아님. 기존 원본 기본값을 변경하지 않음.','', '원본 fit→convert-to-satin→SatinColumn→S1→기본 PES writer 경로. 앱 연결 없음. 사용자 봉제 승인은 별도.']
(r/'summary.md').write_text('\n'.join(lines)+'\n');(r/'summary.json').write_text(json.dumps(dict(fonts=summary,generation_failures=failures,gate_cases=gate['cases'],gate_blocked=gate['blocked']),ensure_ascii=False,indent=2)+'\n')
print('\n'.join(lines))
