# 개인 그림 → 도안: 첫 엔진 시험 (2026-10-24)

현재 상태: **사진/그림 화면의 컬러 모드에 연결**. 검증·스크린샷·재현은 [컬러 그림 QA](qa/color-drawing/README.md). 아래는 main `98ac4ac`에서 시작한 초기 시험과 후속 변경 기록이다.
첫 단위 15분: 기존 포트 연결, 직접 만든 PNG 2개, 실행 가능한 측정/게이트.
개인 그림·이모티콘은 사용자 기기 안에서만 처리한다. 개인 입력·결과를 서버로 전송하거나 앱/테스트에 번들하지 않는다. 아래 두 입력은 스크립트가 직접 그린 원본 합성 그림이며 외부 캐릭터·사진을 사용하지 않았다.

## 재사용과 계약

| 단계 | 사용한 기존 코드 / 근거 |
|---|---|
| N색 분리 | `ImageQuantizer.quantize`, Wu 1991 Graphics Gems II `gemsii/quantizer.c@ca4898517a93b6b45444a9cda5fd50132694b864`. 기존 원본 C 골든 3개 유지. 2–6색 인자 지원. |
| 색별 윤곽 | `RedworkPipeline.trace`: 기존 Potrace 추적·다각형 근사·곡선화·최적화. 이번에 알고리즘을 새로 만들지 않았다. 색별 이진 마스크를 넘기므로 내부 Wu 2색 재호출은 중복 작업이며 후속 리팩터링 후보. |
| 영역/구멍 | 기존 `PolygonRegion.fromPaths(..., EVEN_ODD)`와 JTS 연결 성분. 좌표는 모든 색에 공통인 이미지 좌표계를 유지한다. |
| 폭 측정 / 시험 분기 | 기존 `StrokeWidthMeasurement`의 경계 Voronoi 중간값, `TatamiLettering.methodForWidth`: <0.9mm 러닝, 0.9–6mm 새틴, >6mm 채우기. **총괄의 기존 한글 auto-rules 수치 재사용이며, 이미지 영역에 대한 검증된 분기 규칙이라고 주장하지 않는다.** |
| 넓은 면 | Ink/Stitch `DefaultTatami`, 기본 바탕 땀 포함. |
| 좁은 획 / 선 | 기존 AutoTrace thinning→centerline→fit → `StrokeToSatin` + `AutoSatin` / `Stroke` 기본 러닝. AutoTrace 0.31.10 `1a232b27818a46a05cd25edec09e2f77c4e9ac01`의 기존 포트. 새틴은 일정 폭 변환이다. |
| 실 색 | Brother 64색 `PecThreads.SET`에서 기존 `PecThreads.distance` 최소값, 동률은 표 순서. alpha는 색 거리에서 제외. |
| 마감 / 파일 | 색별 그룹을 한 번에 기존 S1 `GeneratedPatternProcessor`로 처리, 영역 사이 trim, 실 경계 잠금, 0길이 제거 → `PesWriter` V6 → `PecViewportCheck` + 앱 생성 출처 `SewoutCheck`. |

Ink/Stitch는 기존 고정 원본 v3.3.0 `b0edd96311ece82ee48816dc93466274b12c2a9c`의 별도 출처 고지된 포트만 호출한다. AutoTrace/Potrace/Wu 라이선스와 원본 출처는 기존 `THIRD_PARTY_NOTICES.md`, `docs/inkstitch-port.md`, 각 포트 헤더를 유지하며 새 의존성은 없다. 이 시험은 원본 Ink/Stitch를 실행하지 않는다.

시험 입력은 128×128 RGBA PNG, 전체 캔버스 30mm, 4색 요청이다. 실제 원본 색이 3개여서 Wu 원본 동작대로 3색에서 끝난다. 투명 픽셀은 양자화에서 제외하여 실 색 예산을 소비하지 않는다. 이번 시험은 완전 불투명/완전 투명만 허용하고 부분 알파는 명시적으로 거절한다. 이미지 외곽 투명 여백을 요구하며 배경색을 추정하지 않는다. 입력 크기는 시험 도구에서 512px 이하로 제한했다. 앱용 디코드/축소/부분 알파 합성 계약은 아직 구현하지 않았다.

## 실행과 코드 판정

```sh
python3 tools/image-to-design/generate-inputs.py
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
./gradlew :engine:test --tests '*ImageToDesignProbeTest' --tests '*ImageQuantizerOracleTest' :engine:imageToDesignProbe
# 선택: -PimageProbeColors=2 .. 6, -PimageProbeInput=... -PimageProbeOutput=...
/tmp/myembro-quality-gate/quality-gate check \
  /tmp/myembro-port-out/editor/image-to-design/face.pes \
  /tmp/myembro-port-out/editor/image-to-design/robot.pes \
  --style mixed --json /tmp/myembro-port-out/editor/image-to-design/quality-gate.json
```

측정: 이 PC JVM 단일 실행, 얼굴 먼저/로봇 다음; 워밍업·환경 영향이 있으므로 성능 보장 수치가 아니다. `total_ms`는 PNG 디코드부터 PES 게이트까지, 세부 단계 외 S1·인코딩·검사도 포함한다.

| 합성 PNG | 색 / 영역 | 땀 수 | 양자화+디코드 ms | 윤곽 ms | 폭 측정 ms | 땀 그룹 ms | 전체 ms | 엔진 / 외부 게이트 |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| face.png | 3 / 4 | 1,050 | 63.92 | 89.64 | 140.97 | 591.95 | 988.68 | PASS / PASS |
| robot.png | 3 / 5 | 600 | 7.06 | 21.58 | 52.10 | 98.90 | 204.67 | PASS / PASS |

실제 출력:

```text
BUILD SUCCESSFUL in 5s
PASS .../face.pes within five-condition checks
PASS .../robot.pes within five-condition checks
PASS 2/2 cases passed
```

P1–P3은 인코딩된 PEC 원점/후프/헤더를 `PecViewportCheck`로 검사했다. 나머지는 실제 PES를 `SewoutCheck`의 `APP_GENERATED`로 검사했다. 고정 테스트는 두 그림의 차단 없음·유효 범위·3색·러닝/새틴/타타미 경로 존재를 assert한다. 별도 테스트는 2–6색 예산/인덱스/반복 결정성을 검사한다. 기존 Wu 원본 일치 테스트도 함께 실행했다. 생성물/세부 JSON은 `/tmp/myembro-port-out/editor/image-to-design/`, 로그는 `/tmp/image-to-design-{test,gate}.log`이다. 게이트 통과는 원형 보존이나 실물 봉제 승인이 아니다.

## 발견한 한계 — 제품 적용 전 해결 필요

**로봇의 넓은 몸통이 새틴으로 잘못 분류된다.** 눈 구멍이 있는 몸통의 Voronoi 폭 중간값은 4.121mm로, 기존 0.9–6mm 분기에 들어간다. 따라서 일정 폭 중심선 새틴은 원래 넓은 몸통의 면을 재현하지 못한다. 얼굴 면은 6.177mm라 타타미가 선택됐다. 이 실패 사례를 테스트에 명시하여 통과하는 PES가 올바른 그림이라는 오해를 방지했다. 임의 면적/길쭉함 임계값을 만들어 보정하지 않는다.

또한 중심선 교차/가지, 가변 폭, 작은 영역 소실(Potrace 기본 turdsize=2), 이웃 영역 경계의 땀 겹침, 실 색 매칭 후 같은 색 중복 순서 문제는 두 합성 그림만으로 검증되지 않았다. 변환 불가능한 중심선/새틴 그룹은 조용히 버리지 않고 시험을 실패시킨다. 색이나 영역 간 새 경로 최적화는 도입하지 않았으며 기존 각 타입 라우팅과 trim만 사용한다.

## 다음 단위 제안과 결정

1. **20분:** 넓은 구멍 영역을 포함한 이 두 입력의 면 보존을 수치로 비교하고, 재사용 가능한 원본 Ink/Stitch/AutoTrace의 검증된 영역→새틴 적용 조건을 조사한다. 아직 이미지용 자동 새틴 선택을 기본 경로로 승인하지 않는다.
2. **20분:** 총괄이 정한 지원 입력/자동 선택 범위로 엔진 API를 분리하고 투명/부분 알파·작은 영역 처리 계약을 고정한다. 근거 없는 임계값을 추가하지 않는다.
3. **20분:** 대표 사례 ≤10개, 색별 원형/누락 검증과 실제 PES 게이트를 테스트로 고정한 후 앱 연결 여부를 결정한다. UI는 이후 단위다.

결정 필요: 첫 제품 경로를 검증된 타타미 중심으로 제한할지, 원형 보존 조건이 확인될 때까지 이미지 자동 새틴 전체를 시험으로 둘지. 이번 커밋은 테스트 전용이므로 기존 실루엣/외곽선 및 앱 출력에는 영향이 없다.

엔진 전체 자동 테스트 결과: `./gradlew :engine:test` → `BUILD SUCCESSFUL in 17s`; tests=714, failures=0, errors=0.

## 후속 단위: model/canvas 공용 경로 통일

공용 API는 아직 origin/main `98ac4ac`에 없어서 model `4126735161bea2cde2168fb2c9273a527aa0b5a6`의 `GlyphStrokeWidths.kt`, 테스트, 문서를 그대로 가져왔다. canvas 로고 경로 역시 아직 main에 없어 `652d3cafd6d61133f5104538034e29030a236bc5`의 엔진 `VectorArt.kt`만 가져왔다. 앱/번들 PES는 가져오거나 변경하지 않았다. 통합 시 이 원본 브랜치들과의 파일 중복을 별도 구현으로 취급하지 말고 아래 공용 함수 변경만 유지한다.

- `VectorArt.medianWidthMm(geometryMm)`가 model `GlyphStrokeWidths.measure`를 호출한다. 이미지 영역과 SVG 채움 면 모두 이 함수로 mm 단위 중간값을 측정한다. canvas의 기존 최대 내접원 계산은 제거했다.
- `VectorArt.classifyWidth(widthMm, rules)`만 PNG/로고의 폭 기준 비교를 수행한다. <0.9mm 러닝, 0.9–6mm 새틴(양 끝 포함), >6mm 타타미. 기본 경계 상수는 기존 `TatamiLettering`에서 가져온다. SVG에 명시된 일정 폭 stroke는 해당 물리 폭을 이 함수에 전달한다.
- SVG fill은 원본 canvas 경로처럼 면으로 봉제한다(러닝보다 넓으면 타타미). 새틴은 명시적 SVG stroke에서 사용한다. PNG 영역에는 아직 이 SVG의 면/획 의미 정보가 없으므로 중간값만으로 분기한다. 따라서 **함수 통일과 자동 선택의 정확성은 별도 문제**다.
- 측정값·경계값·로고 stroke 렌더·실제 launcher 로고 PES를 회귀 테스트로 검사한다. 0.9mm 바로 아래/정확히 0.9/정확히 6/6mm 바로 위도 코드로 판정한다.

동일 입력을 공용 API로 다시 측정한 결과:

| 입력 | 영역 | 땀 | 이전 전체 ms | 공용 API 전체 ms | 공용 중간값 및 선택 | 엔진/외부 게이트 |
|---|---:|---:|---:|---:|---|---|
| face.png | 4 | 1,050 | 988.68 | 1,018.17 | 노란 면 6.177mm → 타타미 | PASS/PASS |
| robot.png | 5 | 600 | 204.67 | 208.32 | 파란 몸통 4.121mm → 새틴 | PASS/PASS |

측정 로그 `/tmp/image-to-design-shared.log`, 결과 JSON은 기존 출력 디렉터리 `results.json`, 외부 게이트 JSON `quality-gate-shared.json`. 단일 실행 차이이므로 속도 개선/악화를 주장하지 않는다.

**오분류 수정은 미완료다.** `GlyphStrokeWidths`는 기존 시험이 직접 호출하던 `StrokeWidthMeasurement`를 내부에서 호출하므로, 구멍이 있는 넓은 몸통의 중간값은 양쪽 모두 정확히 4.121185770351488mm다. API를 바꾸는 것만으로는 타타미로 전환되지 않는다는 점을 실측했다. 이 수치를 6mm 이상으로 임의 보정하거나, 최대 내접원·면적 등의 새 휴리스틱을 섞지 않았다. 총괄에 SVG fill처럼 면은 타타미로 유지할지, 지정한 중간값 분기와 미해결 한계를 유지할지 결정을 요청했다.

최종 검증: `./gradlew :engine:test` → `BUILD SUCCESSFUL in 18s`; tests=722, failures=0, errors=0. 외부 게이트 명령은 앞 절과 같고 `--json` 출력만 `quality-gate-shared.json`으로 바꿨으며 `PASS 2/2 cases passed`였다. 실제 로고는 같은 분류 경로로 60mm 렌더 후 PEC 원점/후프/헤더 및 앱 생성 출처 봉제 점검을 통과했다. 이 검증은 미해결 로봇 면 보존 문제를 통과 판정하지 않는다.


## 최종 적용: 최대 내접원 조건 추가 (이 절이 이전 미결정을 대체함)

총괄 결정 2026-10-24: 최대 폭 >6.0mm면 타타미를 우선한다. 최대 폭 ≤6.0mm이면서 중간값 ≥0.9mm일 때만 새틴이며, 나머지는 러닝이다. 최대 폭은 JTS `MaximumInscribedCircle`의 지름이다. 6mm 근거는 총괄이 제시한 코퍼스 새틴 교차 p95 6.0mm이며, 0.9mm 하한은 총괄 지정 정책이다.

**중복 구현 없음:** `claude/stitch-rule` 커밋 `1547f5c14c5c49334fb5551955abeee6e6762072`의 `StitchTypeRule.kt`, `VectorArt.kt`, `StitchTypeRuleTest.kt`를 그대로 사용한다. editor의 PNG 시험은 `StitchTypeRule.widths(region, 10.0)`와 `classify(widths)`를 호출한다. 로고와 PNG 양쪽에서 최대/중간값을 별도로 계산하거나 임계값을 비교하지 않는다. 이전 `VectorArt.classifyWidth`/`medianWidthMm` 래퍼는 제거했다.

공용 브랜치의 중간값은 이전 model Voronoi 표본 중간값과 달리 **면적 가중 local thickness 표본 중간값**이다. 따라서 이전 표의 4.121mm와 아래 5.611mm를 같은 정의의 값이라고 비교하지 않는다. 이 정의와 JTS 탐색 정밀도도 editor에서 별도로 변경하지 않았다. 원본 공용 파일과 바이트 일치 여부를 확인했다. `GlyphStrokeWidths` 자체는 수정하지 않았다.

| 대상 | 크기 기준 | 공용 중간값 mm | 공용 최대 폭 mm | 선택 |
|---|---|---:|---:|---|
| 실제 합성 로봇의 파란 몸통 | 30mm PNG 캔버스 | 5.610841 | 7.762527 | 타타미 |
| 실제 앱 로고 M | 전체 로고 폭 60mm | 10.696521 | 11.911968 | 타타미 |
| 실제 앱 로고 후프 링 | 전체 로고 폭 60mm | 5.257371 | 5.271908 | 새틴 |

몸통 중간값은 여전히 6mm 미만이지만 최대 폭 조건으로 타타미가 선택되는 것을 `ImageToDesignProbeTest`에서 assert한다. 경계 0.9/6mm, 최대 폭 우선(중간값이 0.9mm 미만이어도 최대 폭 >6mm), 실제 로고 경로도 테스트한다. PNG 새틴은 기존 AutoTrace 중심선/fit → Ink/Stitch `convertPathToSatin`/`AutoSatin`을 사용하고 타타미·러닝·S1 기본값을 바꾸지 않았다.

| 합성 입력 | 영역 | 이전 땀 수 → 최종 | 이전 전체 ms → 최종 | 최종 엔진/외부 게이트 |
|---|---:|---:|---:|---|
| face.png | 4 | 1,050 → 1,050 | 1,018.17 → 1,013.66 | PASS/PASS |
| robot.png | 5 | 600 → 831 | 208.32 → 384.85 | PASS/PASS |

로봇은 잘못 선택했던 새틴 대신 실제 면을 채우므로 땀과 시간이 늘었다. 단일 실행 시간은 워밍업 영향을 포함한다. 로고도 실제 PES를 다시 생성해 동일 외부 게이트로 검사했다. 실물 봉제 승인은 별도이며 앱/개인 입력에는 연결하지 않았다.

재현:

```sh
python3 tools/image-to-design/generate-inputs.py
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
IMAGE_PROBE_LOGO_OUT=/tmp/myembro-port-out/editor/image-to-design ./gradlew :engine:test :engine:imageToDesignProbe
/tmp/myembro-quality-gate/quality-gate check \
 /tmp/myembro-port-out/editor/image-to-design/face.pes \
 /tmp/myembro-port-out/editor/image-to-design/robot.pes \
 /tmp/myembro-port-out/editor/image-to-design/logo.pes \
 --style mixed --json /tmp/myembro-port-out/editor/image-to-design/quality-gate-max.json
```

실행 출력: `BUILD SUCCESSFUL in 19s`; 엔진 tests=724, failures=0, errors=0. 외부 도구: `PASS 3/3 cases passed`. 최종 로그 `/tmp/image-to-design-canvas-rule.log`, `/tmp/image-to-design-max-gate.log`; 측정 원본 `results.json`, `logo-widths.csv`는 출력 디렉터리에 있다. 앞 절의 모델 API 단독 교체 시험과 중간 측정은 이 최종 결과를 대체하지 않는다.


## 앱 연결 단위

이후 `ColorDrawingPipeline`으로 시험의 생성 경로를 프로덕션 JVM API로 옮기고, 홈 사진/그림의 컬러 모드에 연결했다. 앞 절의 앱 미연결 상태/부분 알파 거절은 당시 시험 상태이며 현재 상태는 [컬러 그림 QA](qa/color-drawing/README.md)를 따른다. 개인 이미지 로컬 처리 원칙은 유지한다.
