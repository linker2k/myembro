# US-0 뷰어 땀 재생 스모크

2026-10-24, `origin/main`의 UI 스토리 커밋 `4f7d5e8`에서 분기한 `codex/playback`.
사진→자수 엔진은 `codex/editor`에 동결하며 이번 브랜치에 포함하지 않는다.

## 동작

- 뷰어 하단의 첫 카드에 처음/재생·일시정지/탐색/완성 버튼과 1×·4×·16× 선택.
- 1×는 점검 보고서의 예상 SPM(보고서 준비 전 400 SPM)을 기준으로 실제 STITCH 수를
  센다. 화면에 '약 N땀/분 · 작업자 대기 제외'를 표시한다. 작업자 색 교환/트림 대기
  시간을 실제로 재현하는 시뮬레이터는 아니다.
- 슬라이더는 입력 명령 순서다. 색 변경은 마름모, TRIM은 세로 눈금이며 실제 thumb 폭을
  측정해 이동 범위를 맞춘다. 처음 END 뒤의 데이터는 재생·눈금에서 제외한다.
- 슬라이더/색 순서 탭은 재생을 멈추고 탐색한다. 끝에서 재생하면 0땀부터 다시 시작한다.
  다른 화면이나 홈으로 나가면 ON_PAUSE에서 정지하며 자동으로 다시 재생하지 않는다.
- 점검 보고서에서 위치가 있는 BLOCKER만 오류색 원으로 표시한다. 같은 중심점은 한 번만
  그린다. 원을 탭하면 기존 점검 확대를 호출한다. 경고/전체 도안 문제에 임의 위치를 만들지
  않는다. '차단 위치 N곳'을 누르면 기존 문제 목록을 연다.
- 스티치 좌표/명령/순서/PES 쓰기/검증 판정/기계 프로필을 변경하지 않는다.
  모양과 오류색은 기존 MyembroTheme/MaterialTheme 토큰을 쓴다.

## 재현

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

1. 2색 이상이고 TRIM이 있는 PES 또는 .myembro를 라이브러리에서 뷰어로 연다.
2. 처음 버튼에서 0땀, 재생 후 카운트/바늘 이동, 일시정지 후 정지를 확인한다.
3. 1×/4×/16×를 각각 선택한다. 16×에서 완료 후 재생하면 처음부터 시작한다.
4. 재생 중 슬라이더를 끌면 중간으로 이동하고 멈춘다. 완성 버튼에서 전체 도안으로 간다.
5. 밀도/연속 천공 차단이 있는 입력을 연다. 빨간 원과 차단 위치 수를 확인한다.
   원 탭 → 해당 위치 확대 → 화면에 맞추기, 문제 시트 항목 탭 → 확대를 확인한다.
6. 재생 중 홈으로 나갔다 바로 복귀해도 재생 버튼이 '재생' 상태인지 확인한다.
7. USB로 전송 시 기존 차단 확인 창이 한 번 나타나고 취소로 닫히는지 확인한다.
8. 영어 및 가로 폭 600dp 이상에서도 3개 속도·슬라이더·눈금이 보이는지 확인한다.

## 실행 기록

실제 앱 데이터와 구분한 패키지 `io.github.linker2k.myembro.playbackpreview`, emulator-5554.
임시 fixture는 280땀/2색/색 변경 1곳/TRIM 3곳(프로젝트 flatten 결과), 의도적인 연속
천공을 포함한다. 임시 Activity/Gradle init script는 제품 소스에 추가하지 않았다.

| 확인 | 결과 |
|---|---|
| 1초 tick의 1×/4×/16× | 보고서 400 SPM에서 각각 floor(400/60 × 배수), 모두 일치 |
| 한국어 실제 UI 조작 | 재생·일시정지·배속 전환·완료 재시작·슬라이더 탐색·원 탭 확대 통과 |
| 빠른 홈 왕복 | ON_STOP 이전 복귀 경계를 발견해 ON_PAUSE로 수정한 뒤 재검증 통과 |
| 영어 세로 및 가로 600dp 이상 | 재생·세 가지 배속 선택·색 변경/트림 눈금 노출, 영어 가로 실제 재생 통과 |
| USB 차단 확인 | 기존 확인 창 1회, 취소 시 닫힘. 물리 USB 쓰기는 실행하지 않음 |
| 패턴/PES 불변성 | 조작 전후 동일 EmbPattern의 PES v6 바이트 및 원본 프로젝트 파일 바이트 일치 |
| 최종 검증 | 앱 단위 테스트 170개(실패 0), 정상 applicationId의 `:app:assembleDebug` 통과 |
| 단위 회귀 3개 | 명령/바늘 시간 구분, 제어 명령 탐색, 첫 END·빈 입력 경계 통과 |

임시 스모크 자료:

- `/tmp/myembro-playback-smoke.json`, `/tmp/myembro-playback-ui.py`
- `/tmp/myembro-playback-ko.png`, `/tmp/myembro-playback-en.png`
- `/tmp/myembro-playback-focus.png`, `/tmp/myembro-playback-wide-final.png`
- `/tmp/myembro-playback-src/PlaybackPreviewActivity.kt`
- `/tmp/myembro-playback-preview.init.gradle`, `/tmp/myembro-playback-wide-check.py`
- logcat `PlaybackQA`: `TIMING_AND_UNCHANGED_PES_PASS`, `READY`, 이후 상태 로그.

이 변경은 UI 전용 스모크 단계다. 신규 스티치 생성 기능이나 Ink/Stitch 동등성/봉제 품질
게이트 통과를 주장하지 않는다. 엔진 포팅용 oracle 매트릭스/PES 출력은 이 브랜치의 대상이 아니다.
