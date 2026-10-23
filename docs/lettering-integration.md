# 레터링 통합 요청 — 2026-10-23

후속 2단계: `codex/letter`에 근사 중심선·짝지은 레일 기반 AUTO 채우기를 추가했습니다.
7mm 이하 새틴 / 넓은 기둥 4mm 엇갈린 분할, 작은 글자 자동 0.6mm 간격,
센터·가장자리 언더레이를 사용합니다. 기존 파일은 TATAMI/수동 밀도로 복원합니다.
사용자 참고 자료와 비교 결과는 로컬에만 두었으며 이 브랜치에는 포함하지 않았습니다.

Integrator에게: `codex/letter`의 윤곽 채우기 변경을 main에 병합해 주세요. 푸시는 하지 않았습니다.

- 이전 미푸시 새틴 커밋 `db155ff`는 작업 시작 시 main `02fa8ec`의 조상으로 확인했습니다.
  새틴 커밋 자체는 이미 통합되어 있어 중복 cherry-pick이 필요하지 않습니다.
- 이번 변경은 FILL/FillOptions 저장, 짝수–홀수 홀 처리, 기존 TatamiFill 재사용,
  숨은 러닝 연결, 좁은 윤곽 새틴/태타미 전환, 레터링 및 편집기 글자 속성 UI입니다.
- 담당 외 수정은 `TatamiSettings.zigzag` 기본값 false 추가와 `EditorPanels` 모드 선택,
  `EditorViewModel.applyTextProperties` 백그라운드 계산/히스토리 연결로 한정했습니다.
  기존 Tatami 기본 동작과 `applyProperties(Transform)` 호출은 유지했습니다.
- 공용 `ui/format/Dimensions.kt`가 main에 들어와 이전에 대기했던 높이 인치 병기도 연결했습니다.
- 확인 명령: `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug`.
  실제 Jua 및 SA431 검증은 [lettering.md](lettering.md)의 마지막 절을 참고하세요.
