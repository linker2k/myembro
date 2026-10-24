# 배경·안티앨리어싱 색 정리 (v0.2.5 후보)

컬러 전용 변경. 개인 입력 없이 기존 원본 합성 얼굴·로봇 PNG와 테스트가 그린 얼굴만 사용했다.

## 원인과 처리

- PNG의 부분 알파 픽셀을 흰색과 합성한 뒤 Wu에 넣어 가장자리 색이 새 실 색을 만들었다. 이제 완전 투명 픽셀은 제외하고, 부분 알파는 ARGB의 straight RGB 원색으로 양자화한다. 새 알파 임계값은 넣지 않았다.
- 불투명 입력은 Wu 사전 분리(N+1색)의 **테두리 픽셀 최빈 색**을 배경으로 제외한다. 왼쪽 위 한 픽셀로 결정하지 않는다. 배경 제외 후 다시 원본 Wu N색을 실행하여 배경이 사용자가 고른 2–6색 예산을 차지하지 않게 한다. 명시적 투명 배경이 있으면 그 알파 배경을 우선한다. 밝기 임계값을 새로 고안하지 않았다.
- 표준 4연결 성분 면적을 세어 작은 색 조각을 인접한 유지 영역 중 가장 큰 영역의 색으로 합친다. 배경도 합칠 대상이 될 수 있다. 비인접 색을 추정하지 않고, 투명 영역 자체를 새로 채우지 않는다. 기존 Potrace 면적 필터도 같은 면적 기준을 사용한다.
- 면적 기준은 아래 **30mm, 2개 원본 × 후보 5개 = 10건**의 스윕이 선택한 `3.0mm²`다. 더 작은 후보는 얼굴에 Khaki가 남았다. 색 개수만 맞추지 않고 원래 그림의 Brother 실 번호 집합까지 일치하는지 assert했다. 실루엣/외곽선의 기존 면적·디테일은 변경하지 않았다.

## 측정 표

입력은 bilinear 192px 재샘플링 후, 넓은 본체 내부에 4×4px 밝은 잡티(30mm에서 0.390625mm²)를 추가했다. 색 수 예산은 4이며 실제 원본 그림은 3색이다. 면적 후보는 기존 0.25/0.5/1.0에서 부족해 2.0/3.0까지 측정했다.

| 합성 입력 | 최소 면적 mm² | 실제 그림 색 | 필요한 실 | 땀 | 엔진 게이트 |
|---|---:|---:|---:|---:|---|
| face | 0.25 | 3 | 4 | 1144 | PASS |
| face | 0.5 | 3 | 4 | 1144 | PASS |
| face | 1.0 | 3 | 4 | 1107 | PASS |
| face | 2.0 | 3 | 4 | 1107 | PASS |
| face | 3.0 | 3 | 3 | 1089 | PASS |
| robot | 0.25 | 3 | 4 | 846 | PASS |
| robot | 0.5 | 3 | 3 | 836 | PASS |
| robot | 1.0 | 3 | 3 | 836 | PASS |
| robot | 2.0 | 3 | 3 | 836 | PASS |
| robot | 3.0 | 3 | 3 | 836 | PASS |

선택한 3.0mm²에서 얼굴은 `23 Dark Gray / 49 Amber Red / 12 Orange`, 로봇은 `23 Dark Gray / 32 Sky Blue / 49 Amber Red`를 사용한다. **이 두 그림/크기에서 검증한 기준이며, 임의의 그림의 의도된 색 수를 알아낸다고 주장하지 않는다.** 3mm² 미만의 실제 디테일도 단순화 대상이므로 더 작거나 세밀한 그림의 보존을 보장하지 않는다.

실제 Android 크롭·보간 경로(색 수 3, 표시 가로 약 30mm)도 확인했다:

| 결과 | 필요한 실 | 땀 |
|---|---|---:|
| 수정 전 | 23 Dark Gray / 12 Orange / **47 Beige** | 1,543 |
| 수정 후 | 23 Dark Gray / 12 Orange / **49 Amber Red** | 1,502 |

[갱신된 결과 스크린샷](result-ko.png), [갱신된 미리보기](preview-ko.png). 기본 Android instrumentation이 직접 그린 PNG만 사용했고 MediaStore/개인 파일은 읽지 않았다.

## 재현과 판정

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
COLOR_CLEANUP_OUT=/tmp/myembro-port-out/editor/color-cleanup ./gradlew check :app:assembleDebug :engine:imageToDesignProbe
# 같은 코드의 실제 ART 경로는 ColorDrawingSmokeInstrumentation 실행.
/tmp/myembro-quality-gate/quality-gate check \
 /tmp/myembro-port-out/editor/color-cleanup/face-3.0.pes \
 /tmp/myembro-port-out/editor/color-cleanup/robot-3.0.pes \
 /tmp/myembro-port-out/editor/color-cleanup/android-face.pes \
 --style mixed --json /tmp/myembro-port-out/editor/color-cleanup/gate.json
```

```text
BUILD SUCCESSFUL in 51s
engine tests=730 failures=0; app debug/release tests=231 each, failures=0
COLOR_UI_SMOKE_PASS colors=3 ink_width_mm=30.2 stitches=1502
threads=[(23, Dark Gray), (12, Orange), (49, Amber Red)]
INSTRUMENTATION_CODE: 0
PASS 3/3 cases passed
```

코드 판정: `ColorCleanupSweep`은 10건의 게이트, 3색 및 원래 실 번호 보존의 최소 후보를 검사한다. 별도 회귀는 모서리 한 픽셀이 다른 배경, 배경이 색 예산을 소비하지 않음, 작은 영역의 인접 병합, 투명 구멍 보존, 부분 알파 원색 보존을 검사한다. Android 스모크는 catalog `47`이 없다는 것도 assert한다.

원시 표 `/tmp/myembro-port-out/editor/color-cleanup/sweep.tsv`, 로그 `/tmp/color-cleanup-final-check.log`, `/tmp/color-cleanup-android-smoke.log`, `/tmp/color-cleanup-gate.log`. 개인 도안/이모티콘 번들 없음. 통합 담당 검토용이며 실물 봉제 검증은 별도다.
