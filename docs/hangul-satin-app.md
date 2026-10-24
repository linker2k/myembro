# 한글 중심선 새틴 앱 연결 — 브랜치 검토용

사용자 KRSAT3 봉제 결과 대기 중인 준비 변경이다. **codex/letter에만 push하고 main에
병합하거나 배포하지 않는다.** 기존 채우기 구현과 저장 데이터의 FILL 재생성은 유지한다.

## 생성·저장 경로

- TTF 문자열이 NFC 한글 음절과 공백/줄바꿈으로만 구성되면 기본 자동 방식 SATIN.
  혼합 영문/숫자 문자열·단선·Ink/Stitch 글꼴은 기존 자동 결정 경로다.
  새틴/채우기 선택 UI는 추가하지 않았다. 카드 미리보기와 편집기 추가도 같은 경로다.
- Android Paint 128px·흑백·8px pad(KRSAT3와 동일 sampling) → 원본 AutoTrace
  thinning/trace/fit → 8방향 잉크 연결 성분 → 50% 세로 겹침 줄 판정 →
  글자/뭉치 순서 → 원본 convert-to-satin/auto-route → 공통 S1 half_stitch.
- 추출은 기존 worker coroutine 경로에서 수행한다. 래스터 한도는 기존 AutoTrace와
  동일4096px 축/1,048,576픽셀이다. Android 타입은 엔진에 넣지 않았다.
- source-unit 중심선·문자/뭉치 소유권·글자별 실측 폭을 nullable
  `TextObject.centerlineSatin`에 보존한다. 새 payload 자체 version1, 기존 Project 및
  TextObject 필드 시그니처/기본값은 유지했다. 폰트 없이 저장/복원·높이/실/배치 변경을
  재생성할 수 있다. 텍스트·글꼴을 수정하면 Android에서 새 데이터로 교체한다.
- 기존 FILL은 새 field가 있어도 기존 LetteringEngine.fromContours 경로로 동작한다.
  과거 프로젝트에 새 필드가 없으면 이전 방식으로 읽는다. 곡선 중심선 글자 배치는
  아직 지원하지 않으며 명시 오류로 중단한다(현재4입력 UI는 직선만 사용).

## 폭·굵기·높이 근거

고정1.13mm를 임의 글꼴에 복사하지 않는다. 기존 model
`geometry/StrokeWidthMeasurement.kt`를 변경 없이 가져와 글자별 interior Voronoi
중앙값을 사용했다. 출처 model `33a6683`(해당 파일 마지막 변경), SHA256
`a7bb5829cb688e3911827b9325ce3d407b8e8fb9b469c25f33a2ce543a3296e4`.
기존 PolygonRegion even-odd 구멍 처리·JTS를 재사용하며 새 폭 추정 알고리즘은 없다.
15mm 기준0.1mm boundary sampling은 앞선 공개 스윕과 같다. 결과를 source 단위로
보존하고 높이 변경 시 비례 환산한다. 최소 폭/품질 인증으로 해석하지 않는다.

bundled Noto variable TTF를 직접 읽으면 Android에서 Thin 축이 기본이었다.
새 한글 새틴 경로에만 기존 `myembro_sans.xml`의 명시적400 family를 사용해
KRSAT3의 Noto400과 맞췄다. 기존 fill/outline 로딩은 바꾸지 않았다.
앱 실제 정민15mm의 실측 폭: 정1.1245mm·민1.1323mm.

채우기 스윕의 검증 높이 범위는 새틴으로 승계하지 않는다. 사용자 확정으로 한글의
기본 방식을 바꾸되, 새로운 범용 새틴 인증 범위는 만들지 않았다. 기존 앱60mm/
후프 한도는 유지하고 화면은 높이 직접 입력 및 연결 뭉치 설명을 사용한다(ko/en).
실제 파일은 기존 writer/gate의 P1–P8을 통과해야 한다.

## 테스트·에뮬레이터

- 엔진: 중심선 payload 저장/복원 땀 exact, 높이 변경·PES 왕복, 잘못된 data,
  한글/혼합 문자열 정책, 기존 실측 두께 구분, 명시적 FILL 유지 회귀4건.
- 앱: 한글은 SATIN·centerline=true, fill 인증 범위 미승계·높이 한도 회귀1건.
  이전 fill 범위 회귀는 영문 표본으로 유지한다.
- `HangulSatinAppInstrumentation`: **실제 Android renderLettering**에서 Noto400
  ‘정민’15mm 자동 새틴, offline ProjectJson 저장/복원 땀 exact,
  높이20mm 재생성·PES 생성 모두 PASS. 모사 AWT glyph가 아니다.
- 최종 Android 생성 `APPSATIN400.PES`: **623땀·6점프·5트림·6뭉치**.
  gatev7 PASS, 0길이0·시작/끝 잠금 누락0·셀 최대5·원점/헤더/후프 검사 PASS.
  Android Paint glyph/reference/문자 간격을 사용하므로 KRSAT3와 바이트 동일성을
  주장하지 않는다. 기존 KRSAT1/2/3는 변경하지 않았다.

출력 `/tmp/myembro-port-out/letter/hangul-app/`:
`instrumentation400.log`, `result400.txt`, `APPSATIN400.PES`, `saved400.myembro`,
`gate400.json/log`, `final400-check.log`.
실제 화면 Create→레터링→자동 새틴·미리보기·Add 활성, 편집기 추가→기존 글자 수정
시트의 텍스트/자동 새틴 유지까지 확인했다.
스크린샷 `lettering-ready.png`(기본 고운바탕), `editor.png`, `edit-lettering.png`,
`lettering400.png`(최종 Noto400 선택). 후자는 준비 완료된 최종 APK 화면이다.

## 재현

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.lettering.HangulSatinAppInstrumentation
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
adb install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.lettering.HangulSatinAppInstrumentation
```

공식 Ink/Stitch auto-route 오라클 동등성·범용 글꼴/높이 실기 인증은 별도 미판정이다.
이번 자동 테스트와 게이트를 사용자 실기 수락이나 main 병합 승인으로 취급하지 않는다.

최종 `./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest`: BUILD SUCCESSFUL
in23s, 엔진671·앱 Debug206/Release206, 실패0·Lint 통과.
에뮬레이터 API35 최종 instrumentation 코드-1 PASS, Noto400 화면의 폰트 카드
미리보기까지 준비된 스크린샷을 확인했다. main 병합/사용자 APK 전달은 하지 않았다.
