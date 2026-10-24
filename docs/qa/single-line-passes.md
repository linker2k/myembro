# 단선 레터링 통과 횟수: Yeonwoo (2026-10-24)

1차 계산 단위. 기준 main `9b37790`(v0.2.7 이후). **생성 코드·UI·기본값은 바꾸지 않았다.** 실기 글꼴과 높이는 총괄이 사용자에게 확인 중이므로 실제 파일의 3회 통과 원인을 확정하지 않는다.

## 조건과 측정 정의

- 카탈로그 `single-line-svg` 전체 **40종 × 10/15mm**. 공개 로컬 SVG의 SHA-256을 현재 카탈로그와 모두 대조했다. 문구 `Yeonwoo`, 자간 1mm, 기본 2.5mm 땀, 직선 배치.
- 앱의 `LoadedFont.glyph/advance/reference`·`layoutGlyphs`와 동일한 SVG 배치 데이터를 `TextObject(fontKind=OUTLINE, singleLine=true)`에 넣어 `localPattern()`을 생성했다. 현재 자동 단선 경로는 RUNNING이다. 별도로 같은 경로에 BEAN을 지정한 비교 출력도 계산했다.
- 앱 기본 제공 Ink/Stitch 글꼴 중 이번 문구의 모든 요소가 러닝인 **Sacramarif**도 원본 설정 그대로 비교했다. 새틴을 포함하는 다른 Ink/Stitch 글꼴은 단선 SVG 40종과 혼합하지 않았다.
- S1 `GeneratedPatternProcessor.stripGeneratedLocks()`로 생성 잠금을 제외했다. 점프·트림도 제외하고 바늘→바늘 선분만 세었다. **표의 비율은 동일한 두 끝점을 가진 무방향 선분의 고유 개수 기준**이다. 역방향도 같은 선분이다. 4회 이상도 누락하지 않았다.
- 부분 겹침은 끝점이 달라 위 비율에서 빠질 수 있다. 별도 `summary.json`에 원본 경로와 출력 경로의 공선 겹침 길이 분포를 함께 기록했다. 곡선의 0.1mm 양자화로 가까우나 정확히 공선이 아닌 선분은 같은 선으로 합치지 않는다. 따라서 바늘 구멍/실의 폭까지 고려한 시각적 중첩률로 해석하면 안 된다. 공선 계산의 소수점 정밀도는 진단용 수치 표현이며 봉제 정책값이 아니다.

## 결과와 원인

- 두 높이 모두 RUNNING: 40종 중 **35종은 동일 끝점 본문 선분 100% 1회**, 5종은 일부 2회. **3회 이상은 0종**이다.
- Hershey Sans 1-stroke·Hershey Script 1-stroke는 두 높이 모두 RUNNING **1/2/3회 = 100/0/0%**, BEAN **0/0/100%**다. 현재 기본 자동 RUNNING 경로에는 bean이 적용되지 않는다.
- 원본에 겹친 구간이 있으면 BEAN은 해당 구간도 다시 늘린다. 예: EMS Readability 10mm는 RUNNING 1회 98.21%, 2회 1.79%이고 BEAN은 3회 98.21%, **6회 1.79%**다. ‘굵게’의 3회는 원래부터 단선인 각 입력 선분에 대한 설명이며 원본 중복 전체를 3회로 제한한다는 뜻이 아니다.
- Sacramarif: 10mm 동일 끝점 1회 99.26%, 2회 0.74%; 15mm 1회 98.95%, 2회 1.05%. 출력 공선 길이 기준 2회는 각각 **1.48%·1.58%**다. 원본 러닝 경로에는 공선 길이 기준 **13.37%가 2회** 포함된다. 원본 `repeats=1`/미지정이며 bean 반복 속성은 없다. 서로 다른 재샘플링 끝점·양자화 때문에 원본 길이 분포와 출력 동일 끝점 분포는 다르다.
- SVG 단선 경로는 원본 stroke 순서를 그대로 실행하며 별도 분기 탐색/자동 되돌아가기 알고리즘을 호출하지 않는다. 위 2회는 원본 SVG 경로의 겹침/되돌아감에서 나온다. 잠금도 본문 중복의 원인에서 제외했다. 기존 저장 객체가 BEAN이면 현재 UI 기본과 달리 3중 설정이 유지될 수 있다.

원본 근거: Ink/Stitch v3.3.0 `lib/stitches/running_stitch.py:bean_stitch` @ `b0edd96311ece82ee48816dc93466274b12c2a9c`: repeats `[1]`은 선분마다 앞→뒤→앞(총 3회), `[0]`은 반복 없음. 현재 `StitchStyle.kt:withStitchStyle`의 명시적 BEAN도 이 3중 동작이다. 다음 UI 단위에서는 worker의 기존 upstream `RunningStitch.beanStitch`/Stroke 포트를 호출하며 자체 되돌아가기 최적화를 새로 만들지 않는다.

## 재현

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:measureSingleLinePasses \
  -PpassHeight=10 -PpassOutput=/tmp/myembro-port-out/letter/single-line-passes/10mm
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:measureSingleLinePasses \
  -PpassHeight=15 -PpassOutput=/tmp/myembro-port-out/letter/single-line-passes/15mm
python3 tools/summarize_single_line_passes.py /tmp/myembro-port-out/letter/single-line-passes
```

기본 입력 SVG 폴더 `/tmp/myembro-fontq-independent/fonts`, 다른 경로는 `-PpassFonts=...`. 그 폴더의 현재 카탈로그 SVG 40개가 모두 있어야 같은 결과다. 누락된 파일은 건너뛰며 실행 시 측정 글꼴 수를 출력한다. `passText`로 문구도 바꿀 수 있다. PES/개인 파일은 입력·생성하지 않았다.

출력: 위 `/tmp` 폴더의 `10mm/measurements.json`, `15mm/measurements.json`, `*/embroidery.json`, 본문 선분 CSV, `summary.json`, `table.md`. 실행 10mm 2초·15mm 2초(증분 빌드 포함). `./gradlew :engine:test` **PASS**. 진단 태스크는 opt-in이고 CI/앱 생성 경로는 변경하지 않는다.

## 결정 대기

실기 글꼴·높이 확인과 보통/굵게의 기본값은 총괄 결정 대기다. UI 구현 범위는 단선 글꼴의 두 칸 선택만이며, 원본에 이미 있는 되돌아감을 제거하는 새 알고리즘은 이번 단위에 포함하지 않는다.

## 전체 표

| 글꼴 | 높이 mm | 모드 | 1회 % | 2회 % | 3회 % | 4회 이상 % | 원인 |
|---|---:|---|---:|---:|---:|---:|---|
| hershey-sans-1 | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-sans-1 | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-script-1 | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-script-1 | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-serif-med | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-serif-med | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-gothic-english | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-gothic-english | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-readability | 10 | RUNNING | 98.21 | 1.79 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
| ems-readability | 10 | BEAN | 0.00 | 0.00 | 98.21 | 1.79 | bean 1회 왕복(각 선분 3회); 원본 겹침/되돌아감 있음 |
| ems-allure | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-allure | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-tech | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-tech | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-casual-hand | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-casual-hand | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-delight | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
| ems-delight | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 겹침/되돌아감 있음 |
| relief-singleline | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| relief-singleline | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-sans-med | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-sans-med | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-script-med | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-script-med | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-serif-bold | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-serif-bold | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-serif-bold-italic | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-serif-bold-italic | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-serif-med-italic | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-serif-med-italic | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-gothic-german | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-gothic-german | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-gothic-italian | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-gothic-italian | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-bird | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-bird | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-bird-swash-caps | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-bird-swash-caps | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-brush | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-brush | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-capitol | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-capitol | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-decorous-script | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-decorous-script | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-delight-swash-caps | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-delight-swash-caps | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-elfin | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-elfin | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-felix | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-felix | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-herculean | 10 | RUNNING | 97.96 | 2.04 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
| ems-herculean | 10 | BEAN | 0.00 | 0.00 | 97.96 | 2.04 | bean 1회 왕복(각 선분 3회); 원본 겹침/되돌아감 있음 |
| ems-invite | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-invite | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-league | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-league | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-little-princess | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-little-princess | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-misty-night | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-misty-night | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-neato | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-neato | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-nixish | 10 | RUNNING | 98.70 | 1.30 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
| ems-nixish | 10 | BEAN | 0.00 | 0.00 | 98.70 | 1.30 | bean 1회 왕복(각 선분 3회); 원본 겹침/되돌아감 있음 |
| ems-nixish-italic | 10 | RUNNING | 98.72 | 1.28 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
| ems-nixish-italic | 10 | BEAN | 0.00 | 0.00 | 98.72 | 1.28 | bean 1회 왕복(각 선분 3회); 원본 겹침/되돌아감 있음 |
| ems-osmotron | 10 | RUNNING | 98.61 | 1.39 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
| ems-osmotron | 10 | BEAN | 0.00 | 0.00 | 98.61 | 1.39 | bean 1회 왕복(각 선분 3회); 원본 겹침/되돌아감 있음 |
| ems-pancakes | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-pancakes | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-pepita | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-pepita | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-qwandry | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-qwandry | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-readability-italic | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-readability-italic | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-society | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-society | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-swiss | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-swiss | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| inkstitch-sacramarif | 10 | FONT_ORIGINAL | 99.26 | 0.74 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
| hershey-sans-1 | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-sans-1 | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-script-1 | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-script-1 | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-serif-med | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-serif-med | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-gothic-english | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-gothic-english | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-readability | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
| ems-readability | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 겹침/되돌아감 있음 |
| ems-allure | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-allure | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-tech | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-tech | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-casual-hand | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-casual-hand | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-delight | 15 | RUNNING | 97.47 | 2.53 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
| ems-delight | 15 | BEAN | 0.00 | 0.00 | 97.47 | 2.53 | bean 1회 왕복(각 선분 3회); 원본 겹침/되돌아감 있음 |
| relief-singleline | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| relief-singleline | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-sans-med | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-sans-med | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-script-med | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-script-med | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-serif-bold | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-serif-bold | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-serif-bold-italic | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-serif-bold-italic | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-serif-med-italic | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-serif-med-italic | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-gothic-german | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-gothic-german | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| hershey-gothic-italian | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| hershey-gothic-italian | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-bird | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-bird | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-bird-swash-caps | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-bird-swash-caps | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-brush | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-brush | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-capitol | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-capitol | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-decorous-script | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-decorous-script | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-delight-swash-caps | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-delight-swash-caps | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-elfin | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-elfin | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-felix | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-felix | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-herculean | 15 | RUNNING | 98.51 | 1.49 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
| ems-herculean | 15 | BEAN | 0.00 | 0.00 | 98.51 | 1.49 | bean 1회 왕복(각 선분 3회); 원본 겹침/되돌아감 있음 |
| ems-invite | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-invite | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-league | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-league | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-little-princess | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-little-princess | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-misty-night | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-misty-night | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-neato | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-neato | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-nixish | 15 | RUNNING | 96.04 | 3.96 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
| ems-nixish | 15 | BEAN | 0.00 | 0.00 | 96.04 | 3.96 | bean 1회 왕복(각 선분 3회); 원본 겹침/되돌아감 있음 |
| ems-nixish-italic | 15 | RUNNING | 95.00 | 5.00 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
| ems-nixish-italic | 15 | BEAN | 0.00 | 0.00 | 95.00 | 5.00 | bean 1회 왕복(각 선분 3회); 원본 겹침/되돌아감 있음 |
| ems-osmotron | 15 | RUNNING | 98.99 | 1.01 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
| ems-osmotron | 15 | BEAN | 0.00 | 0.00 | 98.99 | 1.01 | bean 1회 왕복(각 선분 3회); 원본 겹침/되돌아감 있음 |
| ems-pancakes | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-pancakes | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-pepita | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-pepita | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-qwandry | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-qwandry | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-readability-italic | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-readability-italic | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-society | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-society | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| ems-swiss | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | bean 없음; 원본 동일 직선 겹침 없음 |
| ems-swiss | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1회 왕복(각 선분 3회); 원본 동일 직선 겹침 없음 |
| inkstitch-sacramarif | 15 | FONT_ORIGINAL | 98.95 | 1.05 | 0.00 | 0.00 | bean 없음; 원본 겹침/되돌아감 있음 |
