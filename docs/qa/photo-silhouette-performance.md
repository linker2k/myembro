# v0.2.1 한 색 실루엣 — 지연/오류 수정

## 범위와 재현 결과

실루엣 채우기를 유지한다. 넓은 면의 러닝 전환 실험은 최종 구현에서 제외했다.
진짜 선화·중심선 및 사용자가 고르는 전처리 필터는 다음 버전으로 미룬다.

기존 2단계는 Wu → Potrace → 곡선화 → JTS → 타타미/바탕땀 경로 연결 → S1 →
PES → 봉제 검사까지 수행했다. 긴 실행 뒤 발생한 후프/PES 예외도 모두
‘선과 배경 구분 실패’로 표시했다. 제공된 로컬 입력에서 이를 재현했다.
전경 비율로 거절하는 기존 규칙은 없었다. 최종 변경에도 비율 거절/러닝 전환을 넣지 않는다.

| 로컬 JVM, 같은 입력/192px | 수정 전 | 수정 후(채우기 유지) |
|---|---:|---:|
| 디코드 | 123.5ms | 176.1ms |
| 전처리/시작 | 2.5ms | 50.0ms |
| Wu 2색 | 17.5ms | 31.2ms |
| 추적/곡선화 | 56.1ms | 77.1ms |
| 남은 윤곽 | 47 | 12 |
| 타타미/S1/영역 준비 | 9,665.6ms | 4,538.0ms |
| 생성 땀 | 10,280 | 9,527 |
| PES | 후프 초과 예외 | 385.2ms, 정상 저장 |
| SewoutCheck | 저장 실패로 검사 불가 | 차단 0 |
| 2단계가 기다리는 CPU 경로 | 땀 생성까지 약 9.7초 후 오류 | 전처리+양자화+추적 약 158ms |

단일 입력·단일 측정이며 하드웨어 전체의 보장 시간이 아니다. JPEG 디코더/축소
차이로 JVM과 Android 윤곽 수는 다를 수 있다. 원본이나 결과 이미지를 이 문서/저장소에
포함하지 않았다. 개인 입력 로그와 모든 파생 이미지는 허용된 로컬 private 영역에만 둔다.

## Android 실측과 UI assertion

동일 입력, 같은 1536px 디코드 제한, 192px 작업 해상도, API 35 x86_64 에뮬레이터:

| 단계 | 수정 전 | 수정 후(타타미 유지) |
|---|---:|---:|
| 디코드 | 15.6ms | 10.6ms |
| 전처리 | 1.2ms | 48.6ms |
| Wu | 67.1ms | 21.9ms |
| 추적/곡선화 | 153.5ms | 35.6ms |
| 윤곽 수 | 27 | 7 |
| 땀 생성 | 38,308ms | 7,883ms |
| 생성 땀 | 10,083 | 9,422 |
| PES | 후프 초과로 실패 | 236.7ms, 성공 |

원본 생성 코드를 먼저 실행하고 수정 경로를 실행한 한 회 측정이다. 워밍업 영향도
포함되어 있으므로 반복 분포/실기 전체에 대한 속도 보장은 아니다. UI 예상 약 2초/10초는
위 측정과 다음 실제 VM 481ms 결과를 반올림한 안내이며 기기/이미지에 따라 달라진다고 표시한다.

실제 `PhotoViewModel.load → next → prepare`를 instrumentation main looper에서 호출하고
2초 안에 busy=false, outline 존재, render/report 부재를 코드로 assert했다:

```
INSTRUMENTATION_RESULT: result=VM_PREVIEW_UNDER_2S ms=481.34921 generated_stitches=false
INSTRUMENTATION_CODE: -1
```

개인 입력은 화면에 표시하지 않았고 에뮬레이터 임시 입력은 계측 뒤 삭제했다.
앱 내부에 보관된 숫자 계측 로그를 제외하고 개인 입력/출력을 저장소나 공개 산출물에 넣지 않았다.

합성 4000×3000 JPEG의 실제 시스템 선택기 → 2단계 → 3단계는 UI XML/logcat으로 assert했다.
2단계에는 `stitches_ms` 로그가 없고, 3단계에는 통과 배지·4,478땀·편집기/USB 버튼이 있다.
균일 합성 이미지에서는 거절 안내와 ‘Two-color separation preview’가 동시에 존재하며,
`contrast_retry=1` 로그 정확히 한 번, 땀 생성 로그 없음도 assert했다.

공개 가능한 합성 이미지 스크린샷만:
- `/tmp/myembro-photo-12mp-preview.png`
- `/tmp/myembro-photo-12mp-progress.png`
- `/tmp/myembro-photo-12mp-result.png`
- `/tmp/myembro-photo-uniform-rejected.png`

## 구현과 숫자 근거

- 기존 디코드 긴 변 ≤1536px 및 디테일 128/192/256을 유지한다. 작은 후프에서는
  사용자 제안의 **0.2mm/px 작업 상한**을 추가로 적용한다. 원본 사진 해상도로
  양자화/추적하지 않는다. 12MP 합성 JPEG는 opt-in CLI가 직접 생성한다.
- 2단계는 Wu 2색 및 Potrace 윤곽/곡선화만 수행한다. 땀·파일·봉제 검사는 3단계에서
  수행하며 캐시한 같은 윤곽을 사용한다. 미리보기 실패에도 Wu 흑백 분리 이미지를 남긴다.
- 색 분리는 기존 검증된 Wu 포트다. 실패하면 채널별 min/max 선형 대비 확장 후 Wu를
  **최대 1회** 더 적용한다. 식 `(v-min)*255/(max-min)`, 상수 채널은 보존한다.
  [표준 min/max 정규화 정의](https://docs.opencv.org/4.12.0/d2/de8/group__core__array.html)를
  따른 수학적 연산이며 OpenCV 코드를 복사하거나 의존성을 추가하지 않았다.
- 자동 노이즈 억제는 표준 **3×3 채널별 중앙값, BORDER_REPLICATE**다.
  [medianBlur 정의](https://docs.opencv.org/4.12.0/d4/d86/group__imgproc__filter.html)를
  따르며 자체 선 검출/스텐실 알고리즘은 없다. 필터 선택 UI는 추가하지 않는다.
- 작은 조각은 기존 Potrace `turdsize`로 제거한다. 사진 전처리 최소 면적은
  디테일별 1/0.5/0.25mm²로 픽셀 면적에 환산한다. 이는 사진 단순화 프리셋이며
  Ink/Stitch 기본값/봉제 안전 기준이라고 주장하지 않는다. 엔진 일반 API 기본값은 원본 2px다.
- 사진의 윤곽 상한 **128개**는 모바일 작업 예산이다(작은 후프/사진 대표 입력 측정 대상).
  품질 게이트 임계값이 아니며 넘으면 땀 생성 **전에** 디테일 조절 안내로 종료한다.
  윤곽 일부만 잘라서 임의로 채우지 않는다. 전경 비율이나 인물 여부로 거절하지 않는다.
- 사진의 SVG 평탄화 허용오차는 PES 좌표 해상도와 같은 **0.1mm**다. 기존 0.01 SVGpx
  (약 0.0026mm)보다 과도한 서브픽셀 세분화를 줄인다. 일반 엔진 API의 기존 오차와
  Ink/Stitch 타타미·바탕땀·잠금 기본값은 그대로다.
- 완성된 땀(잠금 포함)의 정수 범위를 실제 후프 0..width-1/height-1에 맞춘다.
  가운데 원점을 먼저 반올림하면 999단위가 1000단위로 벌어질 수 있으므로, 최소점을
  뺀 후 반올림한다. 추가 안전 여백은 넣지 않는다. 실제 PES의 P1–P8 검사를 유지한다.
- 진행 단계/경과/예상 안내·취소·10초 후 디테일 낮추기를 제공한다. 땀 생성은 30초 후
  취소 요청/전용 오류를 제공한다(협력적 중단). 경로 그래프 루프에 interrupt 확인을 넣고
  취소한 작업의 결과가 새 세션을 덮어쓰지 않게 revision을 유지한다.

## 재현 명령

```
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:photoTiming -PphotoTimingArgs=192,baseline,full
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:photoTiming -PphotoTimingArgs=192,filtered,full
```

합성 입력: `/tmp/myembro-photo-performance/synthetic-12mp.jpg` (4000×3000, 직접 생성).
기본 CLI는 개인 사진을 읽지 않는다. 선택적 네 번째 로컬 경로는 입력을 복사/렌더/저장하지 않는다.

Android opt-in 러너:
```
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.photo.PhotoTimingInstrumentation
adb shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.photo.PhotoTimingInstrumentation
```

회귀 테스트: 미리보기에서 땀/PES 단계 미호출, 기존 생성 API와 분리 생성 동일,
상수 이미지도 2색 미리보기 콜백 후 즉시 거절, 작은 조각 제거/상한 선판정,
중앙값과 대비 확장의 고정 계산, 완성 채우기의 후프/봉제 검사.

## 최종 검증

```
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug
BUILD SUCCESSFUL in 56s
86 actionable tasks: 33 executed, 53 up-to-date
```

엔진 640, 앱 debug/release 각각 217 tests, 실패/오류/skip 0.
ko/en 사진 문자열 45개 키 일치. 전체 로그 `/tmp/myembro-photo-final-check.log`.
CI 및 실물 봉제 승인과 별개의 로컬 자동 검사 결과다.

## 다음 버전 후보 — 작업 대기

총괄 지시(2026-10-24): 에뮬레이터에서 측정한 땀 생성 7.9초의 추가 단축은
다음 버전 후보로만 기록한다. 현재는 추가 최적화·구현·검증을 진행하지 않고
후속 지시를 기다린다. 재개 시 위 측정 입력과 타타미 채우기 결과를 유지한
상태에서 병목을 다시 계측한다.
