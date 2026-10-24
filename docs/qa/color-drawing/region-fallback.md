# 영역별 생성 대체: 작은 눈·입 재현 수정

공용 `StitchTypeRule.generateRegion`에 대체 정책을 넣었다. canvas `VectorArt`와 컬러 그림 `ColorDrawingPipeline`이 같은 함수를 호출한다. 기존 0.9/6mm 분류 조건은 변경하지 않았으며, canvas의 예정된 1.0mm/중심선 길이 조건과 분리했다.

- 새틴 생성 실패/빈 결과/게이트 차단 → 타타미 → 러닝 **윤곽** → 그 영역만 제외한다.
- 타타미 분류는 타타미부터, 러닝 분류는 러닝부터 시도한다.
- 후보를 기존 유지 영역과 합쳐 실제 S1·PES·`SewoutCheck(APP_GENERATED)`로 판정한다. 컬러 경로는 실제 영역 간 trim과 선택 후프를 포함한다. 후보 자체가 후처리에서 사라져도 성공으로 간주하지 않는다.
- 인터럽트·취소는 대체/제외로 삼키지 않고 전파한다.
- 결과의 `excludedCount`, 패턴 `myembro.excluded_regions`로 제외 개수를 제공한다. 앱 결과에 ko/en “제외 N개”를 표시한다. 모두 제외된 경우도 개수가 있는 빈 결과를 반환하고 저장/전송/편집은 비활성화한다.

공용 부분은 editor의 `ac4a780`을 공용 브랜치 부모 위에 cherry-pick하여 **`claude/stitch-rule: 6b5d7a3`**으로 먼저 push했다. 이 커밋은 공용 함수·canvas 연결·공용 회귀 테스트만 포함한다. canvas 직접 메시지 채널은 사용할 수 없어 총괄 대화에 선 push 해시와 후속 작업 시작점을 전달했다.

## 재현과 코드 판정

integrator가 제공한 `ColorSampleCli.fail.kt`의 192px·40mm 합성 sun face 정수 픽셀 생성식을 그대로 `ColorSampleRegressionTest`에 옮겼다. 개인 도안이나 사진이 아니다. 원본 위치:

`/tmp/claude-1000/-home-gosh2-workspace-hoonyjung-myembro/cc38832e-2ca4-4237-b6c9-177cab9b3c94/scratchpad/v025-pes/ColorSampleCli.fail.kt`

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
./gradlew check :app:assembleDebug
IMAGE_PROBE_LOGO_OUT=/tmp/myembro-port-out/editor/region-fallback \
REGION_FALLBACK_OUT=/tmp/myembro-port-out/editor/region-fallback \
./gradlew :engine:test --tests '*ColorSampleRegressionTest' --tests '*VectorArtClassificationTest'
/tmp/myembro-quality-gate/quality-gate check \
 /tmp/myembro-port-out/editor/region-fallback/sun-face.pes \
 /tmp/myembro-port-out/editor/region-fallback/logo.pes \
 --style mixed --json /tmp/myembro-port-out/editor/region-fallback/gate.json
```

```text
INTEGRATOR_REPRO stitches=1846 excluded=0 types=[TATAMI, TATAMI, TATAMI, SATIN] blockers=false
BUILD SUCCESSFUL in 40s
PASS .../sun-face.pes within five-condition checks
PASS .../logo.pes within five-condition checks
PASS 2/2 cases passed
```

회귀 테스트 목록:

- 원본 재현: 작은 둥근 눈 두 개를 타타미로 대체하며 전체 4영역 유지, 제외 0·파일 차단 없음 assert.
- 새틴 null + 타타미 게이트 거절 → 러닝 순서 assert.
- 모든 생성기 실패 → 해당 영역 제외, 다음 정상 영역은 생성 assert.
- 아주 작은 시험 후프로 모든 후보 차단 → 빈 결과와 정확한 제외 개수, 예외 없음 assert.
- 취소·인터럽트 전파, 기존 canvas 로고 분류/게이트도 확인.

로그 `/tmp/region-fallback-final-check.log`, `/tmp/region-fallback-artifacts.log`, `/tmp/region-fallback-gate.log`. 최종 판정은 자동 테스트/게이트 결과이며 실물 봉제 승인은 포함하지 않는다.
