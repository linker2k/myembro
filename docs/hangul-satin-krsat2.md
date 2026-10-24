# KRSAT1 → KRSAT2 연결 뭉치 라우팅 비교

점프는 도착 뭉치/글자에 귀속, 최초 원점 이동 포함. 이동 길이는 실제 PEC JUMP 길이 합.
원본9경로의 path 인덱스→잉크 뭉치 소유권 및 최종6뭉치 순서를 사용한다.

| 글자 | 이전 점프 | 이후 점프 | 이전 이동(mm) | 이후 이동(mm) |
|---|---:|---:|---:|---:|
|정|6|3|58.928|29.777|
|민|3|3|36.184|37.829|

| 글자·뭉치(봉제순) | bbox(px) | 이전 진입 점프 | 이후 진입 점프 | 이후 내부 점프 |
|---|---|---:|---:|---:|
|정·0|[76, 8, 110, 77]|2|1|0|
|정·2|[15, 17, 83, 76]|3|1|0|
|정·4|[33, 81, 110, 124]|1|1|0|
|민·1|[217, 8, 227, 91]|1|1|0|
|민·3|[138, 18, 192, 72]|1|1|0|
|민·5|[153, 84, 231, 120]|1|1|0|

총 JUMP 9→6, 이동 95.111→67.606mm. 글자 왕복 제거, 뭉치 내부 JUMP 0. 모든 6개 run 시작/끝 잠금 PASS(최초 이동의 출발 원점에는 잠금 대상 실 없음).
게이트 PASS, 0길이0, 원점(0,0), 셀 최대5, 최대 땀2.507987mm.

자모 필순 인식/전통 필순 판정은 하지 않는다. 뭉치는 8방향 잉크 연결 성분, 순서는 글자 인덱스·bbox top·left. 같은 줄 tolerance 없음.
최종 수락은 사용자 봉제. 앱 기본값 연결은 이 실기 샘플 생성 단위에 포함하지 않음.

## 원본 대응·차이와 적용 범위

`AutoSatin.kt`: Ink/Stitch v3.3.0
`lib/stitches/auto_satin.py:SatinSegment.break_up/center_line/to_satin,
build_graph/path_to_operations/collapse_sequential_segments,
RunningStitch.to_element/is_sequential/__add__`,
`lib/stitches/utils/autoroute.py:_add_unordered_jumps/possible_jumps/find_path`,
`lib/elements/satin_column.py:center_line/find_cut_points/split/_cut_rails/
_assign_rungs_to_split_rails/_add_rungs_if_necessary/_add_rung/reverse`.
고정 커밋 `b0edd96311ece82ee48816dc93466274b12c2a9c`.
LICENSE와 파일의 GPL-3.0-or-later 고지 확인, 기존 GPL3 배포 고지 적용.
Ink/Stitch 코드 실행·의존성 설치 없음.

원본1mm center-line 분할 → 최소 component node 간 연결(k=1 MST) →
기존 원본 find_path 포트 → 이전 방문은 underpath/최종 방문만 새틴 → 순차 합치기 →
양쪽 rail cut 지점을 먼저 구한 뒤 분할 → 렁 재배치/추가 → 기존 Stroke/SatinColumn/S1.
새 라우팅용 최소거리 탐욕 순회를 만들지 않았다. 위 수치와 절단/렁0.3px·1.1/3배,
러닝 합치기0.5px·precision0.00001은 원본 literal/default다.

차이/범위:
- 이 API는 **convert-to-satin이 반환한 방향 정렬된 Section** 전용이다. 반대 방향
  raw SVG rails의 범용 reverse/flip·DOM·임의 running stroke 입력·SVG 속성 복제는
  이 단위 범위가 아니다. 기본 파라미터만 KRSAT2에서 사용했다.
- Shapely/GEOS는 기존 JTS1.20으로, NetworkX k=1 최소 연결은 component별 nearest
  node 후보와 insertion-stable Kruskal MST로 대체했다. 같은 거리 후보의 tie 순서 및
  임의 자기교차 레일 전체의 공식 출력 동등성은 **미판정**이다.
- 원본 split이 매우 짧은 rail 조각을 버릴 수 있는 동작은 `discardedSatins`에 기록한다.
  실기 CLI는 하나라도 버려졌으면 파일 생성을 거부한다. KRSAT2는 **0개**다.
- 사용자 확정 순서는 경로 시작점이 아닌 **잉크 연결 성분 bbox**다.
  8방향 연결성을 사용하며 AutoTrace 픽셀 대각 인접과 일치한다.
  픽셀 소속을 font-layout glyph bbox에 명시적으로 대응시킨다. 소유권이 애매하거나
  중심선 없는 잉크 뭉치는 거부한다. 자모 이름·필순·최근접 글자 추정 없음.
- component 내부 graph상 bridge와 실제 PES JUMP는 구분한다. 원본 planner 기본
  collapse3mm 이하는 running 연결로 처리되므로 graph bridge가 있어도 실제 JUMP가
  없는 경우가 있다. **0회 검사는 PEC 바이트**로 한다.
- 그룹마다 force_lock_stitches를 켜면 원본 planner가 매번 JUMP를 만들기 때문에
  사용하지 않는다. S1 기본 경계/거리 기준과 half_stitch로 실제 JUMP의 양끝을 잠근다.
- 글자/뭉치 경계에서 소스 trim 명령과 S1 PEC jump/trim 규칙을 유지했다(최종5TRIM).
  SE400 자동 절사 기능을 가정하지 않는다.

## 검증과 생성물

신규 분석적 회귀6건(8방향 연결·문자 우선/bbox 순서·소유권 거부3,
직선·분기 underpath 피복 순서·3뭉치 PES 왕복3). 기존 graph7건 및
AutoTrace C 골든10건도 전체 검사에 포함된다. 공식 Ink/Stitch 실행 오라클과의
auto-route 동등성은 미판정이며, 이 테스트들을 공식 실행 일치라고 보고하지 않는다.

최종 파일 `/tmp/myembro-sewtest/krs/KRSAT2.PES`: **624땀·5TRIM·한 색 Black40wt**,
실제26.9×14.4mm. SHA256
`5ec454ec5c9abb8782999dac6d71231dac2694af67041987c96d492efb4fc597`.
기존 KRSAT1 SHA는 변경하지 않았다. 개발 중 비교 후보는 별도
`/tmp/myembro-sewtest/krs2/`에 보존했고 전달 대상은 위 **krs/KRSAT2.PES**다.

동일 stem의 `.png`는 decoded-PES 바늘 렌더링,
`-centerline.png/json`은 입력 중심선, `-routing.json`은 glyph/component/path/underpath
출처, `-comparison.md/json`은 전후 표, `-gate.json/log`는 외부 v7 게이트 판정이다.
게이트 수치/기준은 바꾸지 않았다. max269/144, min0/0, header269/144 정확히 일치,
0길이 바늘/JUMP0, 셀 최대5, 시작·끝/트림 전후 잠금 누락0.

## 재현

새 파일명으로 실행한다. 입력은 공개 OFL Noto Sans KR400 ‘정민’15mm이며 개인
도안/라이브러리를 읽거나 테스트 픽스처에 넣지 않는다.

```sh
python3 tools/hangul_satin/prepare-raster.py --out /tmp/myembro-sewtest/krs2
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:hangulSatinSample -PhangulInput=/tmp/myembro-sewtest/krs2 -PhangulOutput=/tmp/myembro-sewtest/krs/NEWNAME -PstrokeWidthMm=1.13
/tmp/myembro-quality-gate/quality-gate check --text --style satin --json /tmp/myembro-sewtest/krs/NEWNAME-gate.json /tmp/myembro-sewtest/krs/NEWNAME.PES
# 게이트의 기존 pyembroidery Python 환경으로 실행:
python tools/hangul_satin/compare-components.py /tmp/myembro-sewtest/krs/KRSAT1.PES /tmp/myembro-sewtest/krs/NEWNAME.PES /tmp/myembro-sewtest/krs/NEWNAME-routing.json /tmp/myembro-sewtest/krs/NEWNAME-comparison
```

이번 단위는 KRSAT2 실기 샘플/엔진 연결까지다. 앱 기본 한글 경로와 TextObject
저장·수정 시 동일 재생성 연결은 다음 단위로 남아 있다.

최종 검증(기능 변경 후): `./gradlew check` BUILD SUCCESSFUL in28s, 엔진666·앱 Debug205/Release205
실패0·Lint 통과. 이후 이전 결정용 획 순서 API/테스트3건은 제거했다. `/tmp/myembro-port-out/letter/krsat2-final-check.log`.
실제7개 underpath 구간은 뒤에 봉제되는 동일 원본 새틴 구간들의 **합집합**으로
모두 덮임을 자동 검사했다(여러 분할 새틴으로 나뉜 피복도 포함).
`compare-components.py`가 미피복 underpath·버려진 새틴·글자 순서 이탈·뭉치 내부
JUMP·잠금 누락·0길이/원점 오류를 assert한다. 자동 판정과 사용자 실기 승인은 별개다.

폐기 API 제거 후 현재 소스도 `./gradlew check` BUILD SUCCESSFUL in28s, 엔진663·앱 각205
실패0·Lint 통과 (`/tmp/myembro-port-out/letter/krsat2-check-current.log`).
