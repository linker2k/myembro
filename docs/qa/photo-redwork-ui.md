# S4 사진 레드워크 UI — 1차 스모크

## 사용자 경로

홈 → 새 프로젝트 시작/만들기 → 사진으로(빠른 시작도 연결).
1. 시스템 사진 선택기(OpenDocument), EXIF 방향 반영, 기본 후프 비율로 핀치/팬 자르기.
2. 실제 생성된 단색 러닝 미리보기. 조절은 디테일 하나이며 입력 샘플링 128/192/256만 변경.
3. SewoutCheck 통과/차단 배지, 바늘 수, 예상 시간 → 편집기 / 보내기.

- 디테일은 제품의 입력 샘플링 선택이며 Ink/Stitch 파라미터라고 표시하지 않는다.
  Wu 2색, Potrace와 러닝/S1 설정은 엔진 기본값 그대로다. 밀도·잠금·땀 종류 UI는 없다.
- 사진의 왼쪽 위 표시점을 배경에 놓도록 안내한다. 새 자동 배경 추정 규칙은 추가하지 않는다.
- 모든 UI 색은 MaterialTheme 토큰. 고정 RGB는 실제 실 색 및 RGB 입력용 흰 종이 합성에만 사용.
- 기본 후프·기종·PES 버전은 앱 설정. mm/in 공용 포맷터를 사용한다.
- 생성은 Default, 사진 디코딩·저장은 IO에서 수행한다. 작업 버전과 취소로 오래된 결과를 폐기하고,
  변경/오류/검사 중에는 보내기를 막는다. 차단된 결과도 편집기로 가져가 수정할 수 있다.
- 편집기에는 생성된 StitchObject를 원래 후프 위치로 넘기고 기존 dirty 교체 확인을 재사용한다.
  현재 원본 사진/크롭은 ViewModel 세션에만 있으며, .myembro의 재생성 가능한 사진 객체 저장은 없다.
- 보내기는 새 ASCII 숫자 이름(`photo_linework_2.pes`, 충돌 시 다음 번호)으로 원자 저장한 후
  기존 라이브러리 사전 검증·USB 흐름을 호출한다. 기존 사용자 파일은 덮어쓰지 않는다.
- ko/en 사진 문자열 30개 키 일치를 코드로 확인했다.

## 실제 UI 스모크

격리 패키지 `io.github.linker2k.myembro.photopreview`, emulator-5554.
기존 설치 앱/사용자 파일을 덮어쓰지 않았다. 직접 만든 흰 바탕 검정 원 PNG만 사용했다.

실행: `/tmp/myembro-photo-smoke.py`, `/tmp/myembro-photo-smoke-steps.py`,
`/tmp/myembro-photo-send-smoke.py`의 최종 후속 확인, `/tmp/myembro-photo-en-smoke.py`.
ADB uiautomator XML의 텍스트/버튼으로 assert하며 모델의 화면 추측으로 PASS를 기록하지 않는다.

```text
PHOTO_PICK_CROP_DETAIL_PASS_CHECK_EDITOR_USB_SMOKE_PASS
PHOTO_EN_RESULT_BADGE_STATS_ACTIONS_SMOKE_PASS
```

확인: 홈 시트, 실제 시스템 사진 선택, 크롭 팬, 디테일 변경, 3/3 결과,
76땀/약1분, 통과 배지와 확인 창, 편집기의 사진선화 객체 1개, 보내기의 라이브러리 USB 연결.
영어 결과는 스크롤 후 Edit/Send to USB와 바늘 수·시간을 확인한다.
에뮬레이터에는 OTG가 없으므로 USB 전송 실패/저장장치 연결 안내를 기대 결과로 확인했다.
실제 USB 쓰기 성공을 주장하지 않는다.

스모크는 첫 빌드에서 수행했다. 결과 페이지 스크롤 초기화와 자동 파일명 한글 경고 제거를
마지막에 반영하고 전체 check/APK 빌드를 다시 통과시켰다. 최종 APK는 정식 applicationId로 복원했다.

산출물:
- `/tmp/myembro-photo-smoke.json` — 코드 assertion 요약.
- `/tmp/myembro-photo-{choose,crop,preview,result,editor,usb}-ko.png`, `/tmp/myembro-photo-result-en.png`.
- `/tmp/myembro-photo-smoke-result.pes` — UI가 설정 기본 PES v1로 실제 생성한 파일.
- `/tmp/myembro-photo-quality-gate.json` — 이 실제 바이트의 동결 외부 검사 결과.

```text
/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-photo-smoke-result.pes --style running --json /tmp/myembro-photo-quality-gate.json
PASS /tmp/myembro-photo-smoke-result.pes within five-condition checks
PASS 1/1 cases passed
```

## 최종 코드 검증

```text
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug
BUILD SUCCESSFUL in 1m 36s
85 actionable tasks: 33 executed, 52 up-to-date
```

엔진 416, 앱 debug/release 각 179 tests / 0 failures / 0 errors.
추가 PhotoCropTest 3개: 중앙 크롭, 팬 극단값 경계, 좁은 후프 종횡비.
전체 로그 `/tmp/myembro-photo-final-check.log`. CI 녹색/실제 봉제 승인과 구분한다.
