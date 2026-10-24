# 컬러 그림 앱 연결 스모크

홈 → 새 프로젝트 → 사진/그림 → 2/3에서 실루엣·외곽선 옆 **컬러 그림**을 선택한다. 컬러 모드는 색 수(2–6) 슬라이더 하나와 기존 자수 크기 슬라이더를 제공한다. 결과에는 Brother 64색 최근접 실의 번호·이름을 중복 없이 표시한다. ko/en 문자열을 제공하며 기존 편집기/USB 흐름을 유지한다.

- [컬러 모드·색 수·기존 크기 슬라이더](preview-ko.png)
- [봉제 점검·결과·필요한 실 목록](result-ko.png)

두 스크린샷의 입력은 `ColorDrawingSmokeInstrumentation`이 Android Canvas로 직접 그린 합성 얼굴이다. 개인 이미지/외부 이모티콘을 사용하거나 번들하지 않았다. 사진 선택기 썸네일 선택은 자동 승인 검토가 개인 이미지 선택 위험으로 거절했으므로, 최종 스모크는 MediaStore를 전혀 읽지 않고 테스트가 만든 파일의 정확한 URI만 로드한다. 사진 선택기에서 임의 이미지 선택을 우회 실행한 결과가 아니다.

## 엔진 경로

`ColorDrawingPipeline.trace`는 Wu N색 분리·색별 Potrace 윤곽까지만 실행한다. `generate`는 결과 단계에서 공용 `StitchTypeRule@1547f5c` → 타타미 / 기존 중심선+convert-to-satin / 러닝 → S1을 실행한다. 기존 시험 도구도 이 API를 호출하므로 앱과 별도 생성 알고리즘이 없다. 모든 색의 배치 좌표계를 유지하고 색 매칭은 `VectorArt.nearestThread` → `PecThreads.distance`를 사용한다.

완전 투명 픽셀은 색 예산에서 제외하며 부분 알파는 흰 배경 위 표준 알파 합성 후 양자화한다. 불투명 입력은 왼쪽 위 양자화 색을 배경으로 처리한다(화면 안내). 색이 줄거나 작은 영역이 제거되어 필요한 실 수는 선택한 색 수보다 적을 수 있다. 실제 필요한 실 목록은 완성 패턴에서 얻는다.

자수 크기 변경 시 이전 미리보기를 폐기하고 물리 폭에 맞춰 다시 추적한다. 앱에서 검증한 크기는 선택 30mm → 재래스터화 뒤 표시 30.2mm, 실제 땀 범위 30×30.2mm다. 크기 스모크의 허용 차이는 이미지 양 끝 각 1픽셀의 물리 폭으로 계산한다. 땀을 사후 배율 변환하여 밀도나 후프 판정을 우회하지 않는다. 앱 출력 검사가 차단하면 USB 버튼도 차단한다.

## 자동 검증

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
./gradlew :engine:test :engine:imageToDesignProbe :app:assembleDebug :app:testDebugUnitTest
# 기본 instrumentation runner를 테스트 클래스에 지정하고 APK 설치 후 실행:
./gradlew -PmyembroTestRunner=io.github.linker2k.myembro.ui.photo.ColorDrawingSmokeInstrumentation :app:assembleDebugAndroidTest
adb shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.photo.ColorDrawingSmokeInstrumentation
```

실제 스모크는 공유 에뮬레이터와 충돌하지 않게 별도 `myembro-s2` AVD(port 5556), 별도 패키지 `io.github.linker2k.myembro.photopreview`를 사용했다. 패키지 변경은 `/tmp/myembro-photo-preview.init.gradle`의 로컬 테스트 설정이며 저장소 앱 ID는 변경하지 않았다.

```text
BUILD SUCCESSFUL in 30s
engine: 726 tests, failures 0
app debug unit tests: 231 tests, failures 0
COLOR_UI_SMOKE_PASS colors=3 ink_width_mm=30.2 stitches=1543
threads=[(23, Dark Gray), (12, Orange), (47, Beige)]
INSTRUMENTATION_CODE: 0
```

외부 판정 명령:

```sh
/tmp/myembro-quality-gate/quality-gate check \
 /tmp/myembro-port-out/editor/image-to-design/face.pes \
 /tmp/myembro-port-out/editor/image-to-design/robot.pes \
 /tmp/myembro-port-out/editor/color-ui/color-face.pes \
 --style mixed --json /tmp/myembro-port-out/editor/color-ui/gate.json
```

실제 명령 출력:

```text
PASS .../face.pes within five-condition checks
PASS .../robot.pes within five-condition checks
PASS .../color-face.pes within five-condition checks
PASS 3/3 cases passed
```

로그: `/tmp/color-drawing-tests.log`, `/tmp/color-drawing-ui-smoke.log`, `/tmp/color-drawing-final-gate.log`. 이 단계는 USB 실물 전송·실물 봉제 시험을 포함하지 않는다.

최종 소스 기준 `./gradlew check :app:assembleDebug` → `BUILD SUCCESSFUL in 35s` (lint 포함). 엔진 726건, 앱 debug/release 각각 231건, 실패 0건. 마지막 변경은 컬러 모드 전용 오류 안내와 접근성 문구이며 스크린샷의 레이아웃·생성 경로는 동일하다.
