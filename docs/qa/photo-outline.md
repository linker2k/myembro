# 사진 → 외곽선 (한 색 bean / 3중 러닝)

## 동작·근거

- main `b95f2be` 기준. 2단계에 **실루엣 / 외곽선** 두 칸만 추가한다.
  선택은 기존 Wu 2색·자동 노이즈 억제·Potrace 윤곽을 다시 계산하지 않는다.
  실루엣은 기존 타타미/바탕 땀, 외곽선은 같은 모든 경계(구멍 포함)의 bean이다.
  기존 디테일 외에 땀 길이·반복·잠금·필터 선택 UI는 없다.
- 원본: Ink/Stitch v3.3.0 `b0edd96311ece82ee48816dc93466274b12c2a9c`,
  `lib/elements/stroke.py`, `lib/stitches/running_stitch.py`, `lib/stitch_plan/lock_stitch.py`.
  이미 검증된 `Stroke`/`RunningStitch`/S1 포트를 호출하며 다른 땀 알고리즘은 추가하지 않는다.
- **`bean_stitch_repeats=[1]`**: 원본 `bean_stitch`는 각 기본 땀에 왕복을 한 번 더한다.
  총 통과 횟수는 `1+2*1=3`이다. `[3]`은 7회이므로 사용하지 않는다.
  사용자 레드워크 실물 ‘외곽선 2–3줄’ 요청에 따른 파라미터 선택이다.
  `repeats=1`, 길이 2.5mm, 오차 0.2mm 등 나머지는 `StrokeParameters` 원본 기본값이다.
- 각 분리 윤곽은 원본 `trim_after=true`로 구분하고 원본 기본 `half_stitch` 시작/끝 잠금을
  S1에서 적용한다. 수동 잠금·센터라인·직선 연결 땀을 만들지 않는다.
- 경로 순서는 **기존 의존성 JGraphT 1.5.2의 NearestNeighborHeuristicTSP**를 그대로 쓴다.
  [공식 알고리즘 설명](https://jgrapht.org/javadoc-1.5.2/org.jgrapht.core/org/jgrapht/alg/tour/NearestNeighborHeuristicTSP.html).
  첫 윤곽을 고정하고 닫힌 경계의 시작/끝 앵커 거리를 사용한다. 반환 투어의 마지막
  원점 복귀는 봉제하지 않는다. 실제 열린 순서 비용이 원래보다 줄어들 때만 채택한다.
  새 경로 솔버/자체 휴리스틱이 아니며, 전역 최단 경로나 원본 Ink/Stitch AutoRun 동일성을
  주장하지 않는다. 가까운 점으로 경계를 잘라 시작점을 이동시키는 추가 작업도 하지 않는다.
- SVG 평탄화 0.1mm, 완성 잠금 포함 범위의 후프 맞춤, PES 원점/헤더/0길이 검사와
  SewoutCheck는 기존 실루엣의 공통 경로를 사용한다. 별도의 완화 게이트는 없다.

## 로컬 측정(개인 입력은 수치만)

동일 제공 입력, JVM / 중앙 192×192 / 99.9mm 캔버스 / 기존 자동 중앙값·면적 필터:

| 항목 | 외곽선 |
|---|---:|
| 디코드 | 118.0ms |
| 전처리 | 26.4ms |
| Wu | 15.2ms |
| Potrace/곡선화 | 43.7ms |
| 윤곽 | 12개 |
| bean/S1 | 70.9ms |
| PES | 50.8ms |
| 땀 | 1,167 |
| 윤곽 간 점프 앵커 거리 | 376.6→276.7mm |
| SewoutCheck | 차단 0 |

서로 다른 디코더의 JVM/Android 결과는 달라질 수 있다. 단일 입력·한 회 측정이며
실기 시간이나 전체 사진 품질 인증이 아니다. 개인 원본/파생 PNG/도안은 커밋하지 않고
스크린샷에도 넣지 않는다. 로컬 수치 로그는 `/tmp/myembro-private/portrait-outline-jvm.log`.

## 재현

합성 입력은 기존 CLI의 직접 생성 4000×3000 이미지를 사용한다. 일반 기본 모드는
실루엣을 유지한다. 외곽선 CLI는 쉼표로 네 번째 입력 경로와 다섯 번째 `outline`을 지정한다:

```
./gradlew :engine:photoTiming -PphotoTimingArgs=192,filtered,full,/tmp/myembro-photo-performance/synthetic-12mp.jpg,outline
./gradlew :engine:test --tests '*RedworkPipelineTest'
```

테스트: 원본 단일 러닝 대비 bean 좌표 목록 exact equality·3배 통과 계산,
원본 half_stitch 잠금/trim, 라이브러리 정렬의 완전성·결정성·점프 거리 비증가,
고리 구멍 보존과 실제 PES P1–P8/SewoutCheck.

## Android·화면 확인

API 35 x86_64 에뮬레이터, 동일 로컬 제공 입력(화면에 표시하지 않음):

| 항목 | 외곽선 |
|---|---:|
| 디코드 | 12.6ms |
| 전처리 | 56.4ms |
| Wu | 57.3ms |
| 추적/곡선화 | 110.3ms |
| 윤곽 | 7개 |
| bean/S1 | 107.3ms |
| PES 및 후속 검사 구간 | 210.8ms |
| 땀 | 1,074 |
| 점프 앵커 거리 | 121.9→121.9mm(원래 순서 유지) |
| SewoutCheck | 차단 0 |

`INSTRUMENTATION_CODE: -1`; 로그 `/tmp/myembro-private/portrait-outline-android.log`.
개인 입력 임시 사본은 계측 직후 삭제했다. 개인 결과 이미지/스크린샷은 생성하지 않았다.

합성 이미지의 실제 홈→사진 선택기→2단계 모드 전환→3단계 UI XML/logcat assertion:
```
MODE_SWITCH_REUSES_CONTOURS
OUTLINE_RESULT_PASS (349 stitches, Sewing check · Pass, Edit / Send to USB)
SILHOUETTE_OUTLINE_SWITCH_SMOKE_PASS
```
전환 때 양자화/추적/땀 생성 로그가 없고 같은 윤곽 캐시를 사용함을 확인했다.
스크린샷은 직접 생성한 합성 이미지뿐이다:
- `/tmp/myembro-photo-outline-preview.png`
- `/tmp/myembro-photo-outline-result.png`
- `/tmp/myembro-photo-silhouette-mode-preview.png`

## 최종 검사

```
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.photo.PhotoTimingInstrumentation
BUILD SUCCESSFUL in 1m 30s
113 actionable tasks: 9 executed, 104 up-to-date
```

엔진 643, 앱 debug/release 각각 217 tests / 실패·오류·skip 0.
ko/en 사진 문자열 51키 일치. 로그 `/tmp/myembro-outline-check.log`.

합성 고리(실제 출력 바이트)의 독립 로컬 게이트:
```
/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-port-out/editor/photo-outline/ring.pes --style running --json /tmp/myembro-photo-outline-gate.json
PASS /tmp/myembro-port-out/editor/photo-outline/ring.pes within five-condition checks
PASS 1/1 cases passed
```

위 결과는 코드·로컬 계측 판정이며 실제 봉제/CI 승인과 구분한다.
