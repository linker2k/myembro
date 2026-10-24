# 한글 새틴 순서·라우팅 — 단위1

사용자 KRSAT1 실기 후 새 방향(2026-10-24): 한글 기본 새틴, 글자별 완료,
글자 내부 원본 auto-route underpath, 글자 사이 트림.
총괄 회신으로 자모 필순은 보류한다. 검증된 자모/필순 자료 없이는 구현하지 않는다.
**현재는 라우팅 그래프 핵심 포트·사용자 확정 획 순서 API와 기준값 측정까지다. 앱 기본값을 아직 바꾸지 않았고
KRSAT2.PES·전후 개선 수치·자동 자모 필순 완료를 주장하지 않는다.**

## KRSAT1 실제 PEC 기준값

입력: 공개 OFL Noto Sans KR400 ‘정민’15mm·상수 폭1.13mm의 기존 엔진 시험 파일.
개인 파일을 읽거나 번들하지 않았다. SHA256
`c26ff1bb5fdbee8c46397c2fcaba1800aa27da3b98b9b0d49d13a340fd202e79`.
실제 바늘 글자 구간은 `정→민→정→민→정→민`, 글자 사이 왕복 JUMP5회다.

| 도착 글자 | JUMP 수 | JUMP 이동 길이(mm) | 해당 글자에서 TRIM 수 |
|---|---:|---:|---:|
| 정 |6|58.927649|6|
| 민 |3|36.183745|2|
| 합계 |9|95.111393|8|

최초 원점 이동을 포함하고 JUMP는 도착 글자에, TRIM은 직전 위치에 귀속한다.
이동 길이는 러닝·새틴 바늘 길이가 아니라 JUMP 길이 합이다. 바늘 x값의 가장 큰
빈 구간114..153(PEC 정수 단위)의 중앙133.5로 **이미 두 글자임을 아는 이 사례만**
분류한다. 일반 글자/자모 인식 규칙으로 사용하지 않는다. 외부 게이트v7는 이 파일을
PASS 판정했으나 글자 왕복/이동실 미관은 기존 차단 기준이 아니므로 이 결과와 모순이 없다.

출력 `/tmp/myembro-port-out/letter/krsat1-routing-baseline{.json,-gate.json,-gate.log}`.
재현: 기존 게이트 Python 환경으로
`python tools/hangul_satin/measure-routing.py /tmp/myembro-sewtest/krs/KRSAT1.PES /tmp/myembro-port-out/letter/krsat1-routing-baseline.json`.

## 원본 핵심 포트

`stitches/utils/SatinRouteGraph.kt`는 Ink/Stitch v3.3.0
`lib/stitches/utils/autoroute.py:find_path/_get_shortest_path` 및
`lib/stitches/auto_satin.py:path_to_operations`의 순서를 대응한다.
커밋 `b0edd96311ece82ee48816dc93466274b12c2a9c`, GPL-3.0-or-later,
Copyright (c) Ink/Stitch contributors. 파일 머리 출처 고지를 보존한다.

1. 시작→끝 unweighted shortest path를 backbone으로 정한다.
2. 그 간선을 제거한 그래프의 각 backbone 노드에서 DFS 분기를 왕복한다.
   원본처럼 DFS label 목록을 먼저 만든 뒤 그래프 간선을 제거한다.
3. 새틴 구간을 중복 방문하면 **마지막 방문만 새틴**, 앞선 방문은 러닝 underpath로
   표시한다. JUMP는 러닝으로 바꾸지 않는다.

NetworkX 대신 JVM insertion-ordered adjacency·양방향 BFS·명시 DFS stack을 사용한다.
추가 의존성 없음. directed(preserve_order) 그래프는 backbone을 정한 후 원본처럼
undirected로 복사해 분기 순회한다. 수치 기반 최근접 탐욕 라우팅은 새로 만들지 않았다.
분석적 회귀7건: 직선·T분기·고리/nontree·JUMP 왕복·같은 시작/끝·방향 보존·단절 입력.
**공식 Ink/Stitch 실행 오라클 동등성은 미판정**이며, 분석적 테스트를 원본 실행
일치라고 부르지 않는다. 원본 Python 실행·의존성 설치 없음.

차이/미완료: 이 API는 이미 만들어진 그래프만 받는다. 원본의 `build_graph`
(1mm satin chunk), ordered/unordered jump 연결, `SatinSegment.to_satin`의
양쪽 레일 절단, RunningStitch 변환·collapse, shape 차집합 trim 판정, DOM 조작은
아직 연결하지 않았다. 단절 입력은 자동 가짜 연결을 만들지 않고 명시 실패한다
(호출자가 원본 add_jumps를 완료해야 하는 계약). 중복 구간 ID는 caller가 동일
새틴·정규화 start/end에 동일 ID를 주어야 한다.

## 글자/자모 순서 계약과 확인할 근거

- 글자는 TTF raster/extract 단계에서 **문자 인덱스를 보존**해 읽는 순서대로 처리한다.
  텍스트 전체의 pixel scan 순서를 그대로 내보내지 않는다.
- [Unicode16 §3.12.2](https://www.unicode.org/versions/Unicode16.0.0/core-spec/chapter-3/)
  는 완성형을 L/V/(T) 자모로 분해하는 근거다. 이것이 TTF 윤곽 각 부분에 자모/필순
  라벨을 부여한다는 뜻은 아니다. bundled Noto Sans KR의 uniC815/uniBBFC는
  composite가 아닌 flat glyf여서 자모 부품 메타데이터가 없음을 fontTools로 확인했다.
- 초성→중성→종성 분리는 초기 요청이었으나 총괄 회신으로 보류됐다.
  최신 결정은 아래의 중심선 시작점 y/x 순서이며 이를 전통 자모 필순이라고 표시하지 않는다.
  교육부 기본 교육과정의 [국어 성취기준 6국어03-01](https://www.nise.go.kr/ebook/src/viewer/download.php?host=main&no=1&site=20240226_094301)
  은 자모 획순 교육을 명시하지만, 글리프를 자모/획으로 분해하는 알고리즘이나
  적용할 좌표표를 제공한 근거로 쓰지 않는다. 자모/필순 자료와 실제 glyph 매핑은 미확인.

다음 단위: 글자 인덱스 분리 계약과 원본 rail 절단·그래프 생성 연결 →
기둥 아래 러닝 경로가 실제 후속 새틴에 덮이는지 검사 →
KRSAT2 새 이름 생성 및 P1–P8/PNG/실제 바이트 전후 비교 → 앱 TextObject 직렬화/
오프스레드 생성 연결. 임의 TTF에 Noto ‘정민’의 상수1.13mm를 일반화하지 않는다.

총괄 추가 결정: SE400은 자동 실 자르기가 없다(사용자 실기 확인). 트림 코드는
PE-Design처럼 유지하되 점프 자체를 줄인다. **모든 JUMP의 양끝 잠금**은 다음
라우팅 연결 검사에 포함한다. 원본 잠금 종류 half_stitch를 유지하며, 이 그래프
포트 단위에서 해당 생성/출력 검사를 완료했다고 주장하지 않는다. 글자당 JUMP 수를
전후 표에 계속 넣는다. 자모 필순 확인을 앱 연결의 선행 조건으로 삼지 않는다.

총괄 최종 필순 결정 반영: 자모 분리 없이 **중심선 획 시작점 y 우선·같으면 x**,
글자는 읽는 순서로 완성한다. `SatinStrokeOrder`가 명시적 문자 인덱스와 +y-down
경로를 받아 이 순서를 만든다(분석적 회귀3건). 동일 y의 임의 tolerance는 만들지
않고 정확한 동률만 x로 비교한다. 원래 시작 방향을 임의로 뒤집어 기준점을 바꾸지
않는다. 이는 사용자 지정 단순 순서이며 모든 자모의 전통 필순 일치를 주장하지 않는다.
정렬 결과는 다음 단위의 `preserve_order=true` 원본 그래프 연결 입력이다.

검증: `./gradlew check` BUILD SUCCESSFUL in27s, 엔진660·앱 Debug205/Release205
실패0·Lint 통과. 신규 라우팅7 + 획 순서3 = 분석적 회귀10건.
출력 `/tmp/myembro-port-out/letter/auto-route-order-check.log`.
이번 단위는 원본 그래프 핵심/정렬 계약까지이며, 실제 새틴 rail 절단·연결·KRSAT2
PES 생성과 앱 한글 기본값 전환은 남아 있다.

## 단위2 — 연결 성분 단위 KRSAT2

최신 사용자 정정에 따라 경로 시작점 순서 대신 **연결 성분 bbox**를 사용한다.
이전 결정용 SatinStrokeOrder API/테스트는 제거했고 KRSAT2는 연결 성분 계약만 사용한다.
원본 rail 절단·underpath 연결과 실제 출력/전후 측정은
[KRSAT2 보고](hangul-satin-krsat2.md)에 기록했다.

## 단위3 — 50% 세로 겹침 줄 판정

사용자 확정: 두 뭉치의 세로 범위가 작은 쪽 높이의50% 이상 겹치면 같은 줄·좌→우,
아니면 위→아래. 정 ㅈ→ㅓ→ㅇ / 민 ㅁ→ㅣ→ㄴ 순서의 KRSAT3를 생성했다.
경계 회귀·원본 유지 범위·전후 바이트 측정은 [단위3 보고](hangul-satin-krsat3.md).
