# 글꼴 화면: 새틴 미리보기·입력 지원·즐겨찾기 (2026-10-24)

기준: origin/main `0f5251b`. 공개 OFL 카탈로그 TTF만 사용했으며 개인 도안은 사용하지 않았다.

## 수정

- 설치 상태와 미리보기 상태를 분리했다. 미리보기 없음은 설치된 경우 생성 중/실패로 표시하고, 미설치인 경우에만 다운로드 안내를 표시한다.
- 이전 초기화는 글꼴 하나의 preview → fontq 분석을 끝내야 다음 글꼴을 처리했다. 설치 체크는 먼저 표시되므로 뒤에 있는 글꼴은 다운로드 안내와 함께 보일 수 있었다. 미리보기 작업과 순차 품질 분석을 분리하고, 한 글꼴의 실패가 다음 글꼴을 막지 않게 했다.
- 카탈로그 TTF는 `LetteringFontPreview`로 실제 땀을 생성한다. 한글 `자수`는 중심선+새틴+뭉치 순서, 비한글 `Abc`는 기존 자동 러닝 경로다. SHA-256·백엔드 버전·문구·높이·봉제 기본값을 키로 Android cacheDir에 저장한다. 한글 카드 높이 15mm는 미리보기 표본이며 권장 최소 높이라는 뜻이 아니다.
- 한글 TTF 카드에서 기존 fill/fontq 등급·권장 높이·가는 획 배지를 제거했다. 아래 두 글자 시험만으로 전체 글꼴의 최소 높이/등급을 인증하지 않는다. 따라서 근거 없는 새 권장값을 표시하지 않는다.
- 영문 자수 글꼴 비활성 원인은 호출자의 현재 문구에 대한 `covers(text)` 검사였다. 한글 미지원/일부 문자 미지원 이유를 카드에 표시한다. 설명과 하트는 흐리게 하지 않고 미리보기만 흐리게 표시한다. 입력 없는 호출은 기본 `text=""`이며 모든 글꼴이 지원 검사를 통과한다. 홈·설정 호출자는 레터링의 이전 문구를 전달하지 않아야 한다.
- 하트는 `myembro_settings.favorite_font_ids`에 저장한다. 현재 탭에 속하는 즐겨찾기를 최상단에, 나머지는 기존 순서로 표시한다. 빠른 선택은 현재 글꼴을 유지하고 호환되는 즐겨찾기 두 개를 최근/비슷한 글꼴보다 우선한다. 설정 변경 관찰로 시트를 다시 열거나 글꼴을 선택하지 않아도 갱신된다. 기존 설정 백업/복원 경로에 포함되며 복원 왕복을 실제 Android에서 확인했다.

## 한글 새틴 재계산

문구 `자수`, Black 40wt, 100mm 후프, 현재 앱 `automaticLetteringState` → `renderLettering` → `PesWriter` 경로. 폭은 모델의 기존 `StrokeWidthMeasurement` 글리프별 median을 중심선 새틴에 적용한 실제 상수 기둥 폭이다. 원래 외곽선의 모든 지점에서의 최소/최대 두께라는 뜻은 아니다.
폭 조건 **1.0–6.0mm**는 이번 총괄 지시와 `/tmp/myembro-quality-gate/auto-rules.md` 정책(하한 Ink/Stitch `min_satin_stroke_width=1mm`, 상한 코퍼스 근거)을 따른다. 게이트 PASS와 폭 조건은 별도 표기한다. 연속 높이 범위/다른 글자/전체 글꼴의 봉제 안전성은 미확인이다. 최종 판정은 사용자 실제 봉제다.

| 글꼴 | 높이 mm | 기둥 폭 min–max mm | 폭 조건 | 품질 게이트 |
|---|---:|---:|---|---|
| gowun-dodum | 10 | 0.635–0.700 | 미달 | PASS |
| gowun-dodum | 15 | 0.953–1.050 | 미달 | PASS |
| gowun-dodum | 20 | 1.271–1.401 | 통과 | PASS |
| gowun-dodum | 25 | 1.589–1.751 | 통과 | PASS |
| do-hyeon | 10 | 1.387–1.400 | 통과 | PASS |
| do-hyeon | 15 | 2.080–2.100 | 통과 | PASS |
| do-hyeon | 20 | 2.774–2.800 | 통과 | PASS |
| do-hyeon | 25 | 3.467–3.500 | 통과 | PASS |
| nanum-gothic | 10 | 0.668–0.704 | 미달 | PASS |
| nanum-gothic | 15 | 1.002–1.057 | 통과 | PASS |
| nanum-gothic | 20 | 1.335–1.409 | 통과 | PASS |
| nanum-gothic | 25 | 1.669–1.761 | 통과 | PASS |

게이트 v7, thresholds SHA-256 `18bf3e7cc70e872680368efdb3319e8d1794587337eefdb4e9c7cc97a116b95d`: **12/12 PASS**, 폭 조건 동시 만족 **9/12**. 게이트 코드·상한은 수정하지 않았다.

## 재현과 출력

카탈로그 3개 TTF·LICENSE를 다운로드해 설치한 개발 에뮬레이터(`emulator-5556`)에서:

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest \
  -PmyembroTestRunner=io.github.linker2k.myembro.ui.lettering.FontSatinPreviewInstrumentation
adb -s emulator-5556 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w \
  io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.lettering.FontSatinPreviewInstrumentation
adb -s emulator-5556 exec-out run-as io.github.linker2k.myembro \
  tar -C files -cf - font-satin-preview-qa > /tmp/font-satin-preview-qa.tar
# 압축 해제 후: quality-gate check --text --style satin --json gate.json font-satin-preview-qa/
```

`./gradlew check`: PASS. `FavoriteQuickFontsTest`: 2/2,`InkStitchCatalogTest`: 7/7。Android 계측: 세 글꼴 실제 새틴 미리보기·디스크 캐시 땀 일치·설정 즐겨찾기 백업/복원 PASS. 높이별 PES와 sweep.tsv는 앱 `files/font-satin-preview-qa/`에 생성된다.

작업 출력: `/tmp/myembro-port-out/letter/font-library-fix/`의 `build.log`, `instrumentation-5556.log`, `gate.txt`, `gate.json`, `font-satin-preview-qa/sweep.tsv` 및 PES 12건.

화면 전후: `before-korean.png`(이전 Typeface 미리보기·옛 권장값), `before-latin.png`(설명 없는 흐린 카드), `after-korean.png`(설치 세 글꼴 실제 새틴·옛 등급 없음), `after-latin-incompatible.png`(이유 표시), `after-favorite.png`(나눔고딕 최상단), `after-quick-fonts.png`(나눔고딕 두 번째 빠른 선택). 전 캡처는 에뮬레이터 규칙 변경 전 기존 APK에서, 후 캡처와 재계측은 5556에서 했다.
