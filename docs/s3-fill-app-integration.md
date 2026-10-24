# S3 타타미 앱 연결

S2 기반은 main에 없어서 letter의 커밋111ae77을 model에 통합했다. 모델의 검증된
TatamiFill/FillRouting·InkAutoFill 어댑터와 main S1 계약은 유지했다.

## 적용 경로

- TTF/OTF 닫힌 윤곽(한글 포함)의 자동 선택에 `채우기`를 추가했다. 검증된 새틴 레일이 없는
  TTF를 임의로 새틴 기둥으로 추정하지 않고, 기본 타타미로 보낸다.
- 명시적 `StitchStyle.FILL`은 LetteringEngine.fromContours에서 TatamiLettering을 거쳐
  DefaultTatami의 기본 타타미+바탕 땀 그룹을 사용한다. 기존 밀도/각도/보더/풀 보상 옵션은
  이 제품 기본 경로에 적용하지 않는다. JSON 필드와 레거시 직접 API는 호환성을 위해 남긴다.
- 새틴 레일 API에서 반대 레일까지의 거리로 측정한 최대 폭이6.0mm 초과이면 같은 채우기로 전환한다.
  레일 방향/샘플링 순서나 긴 대각선 땀 자체를 폭으로 오인하지 않는다. 중심선 새틴 요청은
  명시적 widthMm가6.0mm를 초과하면 레일 윤곽을 같은 채우기로 보낸다.
- 경계 규칙은 총괄이 채택한 auto-rules.md의0.9–6.0mm(경계 포함)다.
  methodForWidth의0.9 미만=RUNNING,0.9~6.0=SATIN,6.0 초과=FILL을 테스트한다.
  기존 검증 프로필에 없는 좁은 새틴을 새롭게 승인하지 않는다.
- 자동 레터링 표시에는 `자동 · 채우기`, 높이 슬라이더에는 `채우기 높이`가 표시된다.
  높이는5–90mm이며 양 끝값이 스윕 JSON의 실제 size_grid_mm 최소/최대와 같은지 assert한다.
  90mm는 크기 상한이지 모든 글자/폰트/문장에 대한 통과 증명은 아니다. 기존 후프 초과 검사는 유지한다.
- 각 글자의 색을 유지하고 글자 경계에서 trim한다. S1 그룹 마감 메타데이터에 기본 옵션을 기록해
  앱의 기본 옵션 적용이 이미 생성한 잠금을 다시 생성하지 않게 했다(기존 멱등성 회귀 포함).

## 대표 통합 테스트6건

폭 경계, 자동 채우기/높이 상한, DefaultTatami 그룹과 실제 바늘 좌표 동일성,
한글 컴포넌트·trim, 실제 bundled Jua TTF의 ‘자수’,6.0mm 초과 레일 전환.
실제 TTF 테스트는 저장소의 `app/src/main/res/font/jua_regular.ttf`를 JVM AWT로 읽어
곡선을 평탄화한 뒤 Android에서 추출된 윤곽이 받는 같은 엔진 API를 호출한다.
Android 기기에서 글꼴 추출/화면 조작을 실기 테스트했다고 주장하지 않는다. 개인 파일은 없다.

S2 병합 때 이전 레거시 가정을 가진 테스트를 현재 계약에 맞췄다. main의 filtered-body
검증을 복원했고, source half_stitch의0.5~1.5mm 적응 범위를 사용했다. source-default 채우기는
실 굵기/레거시 밀도 옵션에 따라 땀을 바꾸지 않는지 assert한다. 임계값/게이트는 완화하지 않았다.

## 실행 출력

```text
./gradlew :engine:check :app:assembleDebug :app:testDebugUnitTest --console=plain
BUILD SUCCESSFUL in 19s
engine: 552 tests, 0 failures, 0 errors
app: 176 tests, 0 failures, 0 errors

TatamiLetteringIntegrationTest: 6 tests, 0 failures
JUA 자수: 1230 stitches; 38.6 x 20.0 mm

/tmp/myembro-quality-gate/quality-gate check engine/build/s3-fill --json /tmp/myembro-s3-fill-gate.json
PASS 1/1 cases passed
```

실제 TTF PES의 최대 밀집9회/0.5mm 셀, 연속 동일점1회, 최대 땀4.002mm 미만이다.
짧은 땀 비율은 게이트의 참고 지표로 표시됐으며 차단 사유가 아니다.
[명령·기계 판정 JSON](s3-fill-app-integration.json), 로컬 전체 로그
`/tmp/myembro-s3-fill-final.log`, 후보 `engine/build/s3-fill/jua-korean.pes`.
APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성했다.
원격 CI 녹색/실제 봉제 승인은 별도다. 이번 단위는 여기서 멈추고 총괄 검토를 기다린다.
