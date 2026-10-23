# Ink/Stitch 병렬 포팅 계약과 검증

## 고정 원본

- 공통 기준: **Ink/Stitch v3.3.0**, 커밋 **`b0edd96311ece82ee48816dc93466274b12c2a9c`**.
- 공식 안정 릴리스: https://github.com/inkstitch/inkstitch/releases/tag/v3.3.0 .
- 소스: https://github.com/inkstitch/inkstitch/tree/b0edd96311ece82ee48816dc93466274b12c2a9c .
- embro-letter의 현재 신규 포트도 이 커밋을 사용한다. 이전 `docs/stitch-algorithms.md`의
  개발 커밋 `16e192849e619399ce8fdefb4c4d249dacdb4db1`과 혼동하지 않는다.
- `/tmp/myembro-inkstitch-source`에서 `git show <고정커밋>:<path>`로 **읽기만** 한다.
  Ink/Stitch/Inkscape/Python 모듈 실행, 원본 테스트 실행, 원본 의존성 설치는 하지 않는다.
- 포팅 파일은 SPDX 및 원본 경로·고정 커밋·기여자 저작권을 기록한다. GPL 전문은
  `engine/src/main/resources/licenses/inkstitch/LICENSE`에 번들한다.
- Shapely 대체는 기존 JTS 1.20.0(EDL-1.0 선택). NetworkX/NumPy/Python 런타임 의존성은 추가하지 않는다.

## 공통 계약 — embro-worker 소유

루트 패키지는 `io.github.linker2k.myembro.engine.inkstitch`다. 원본 디렉터리 이름인
`stitch_plan`, `elements`, `stitches`, `lettering`, `utils`, `svg`를 유지한다.

### 좌표와 단위

**`Stitch.x/y`, `utils.Point` 및 생성 알고리즘 내부 좌표는 SVG px(96dpi), +y 아래쪽**이다.
`svg.PIXELS_PER_MM` / `svg.Units.PIXELS_PER_MM = 96 / 25.4`를 사용한다.
기존 `engine.geometry.Point`와 `EmbPattern.Stitch`는 **0.1mm**이므로 섞지 않는다.

공개 SVG 이름의 `_mm` 값과 그룹의 `*Mm` 필드는 **mm**를 유지한다. 알고리즘 비교 전
`Units.mmToPixels`로 한 번 변환한다. 원본 내부 상수 0.01px/0.5px 등을 mm로 오해하지 않는다.
`EmbPatternAdapter`에서만 px→0.1mm로 양자화하며 mm/in UI 단위와 별개다.

### Kotlin API

```kotlin
// stitch_plan
Stitch(x: Double, y: Double, tags: Set<String> = emptySet(),
       jump: Boolean = false, trim: Boolean = false, stop: Boolean = false,
       colorChange: Boolean = false, minStitchLengthMm: Double? = null,
       end: Boolean = false)

fun interface LockStitch {
    fun stitches(stitches: List<Stitch>, position: LockPosition): List<Stitch>
}
enum class LockPosition { START, END }

StitchGroup(color: EmbThread? = null, stitches: List<Stitch> = emptyList(),
            trimAfter: Boolean = false, stopAfter: Boolean = false,
            lockStart: LockStitch? = null, lockEnd: LockStitch? = null,
            minStitchLengthMm: Double? = null, minJumpLengthMm: Double? = null,
            forceLockStitches: Boolean = false)
ColorBlock(color: EmbThread? = null, stitches: List<Stitch> = emptyList())
StitchPlan(colorBlocks: List<ColorBlock> = emptyList(),
           metadata: Map<String, String> = emptyMap(), warnings: List<String> = emptyList(),
           originalThreadPalette: List<EmbThread>? = null)

// elements
fun interface EmbroideryElement {
    fun toStitchGroups(previousStitch: Stitch?): List<StitchGroup>
}

EmbPatternAdapter.fromEmbPattern(pattern: EmbPattern): StitchPlan
EmbPatternAdapter.toEmbPattern(plan: StitchPlan): EmbPattern
```

- `tags`는 불투명 원본 태그를 보존한다. `lock_stitch`가 원본 잠금 태그다.
  `jump`, `trim`, `stop`, `color_change`, `end` 태그는 해당 Boolean과 OR로 해석한다.
  `lock`은 호출자 편의를 위한 별칭이며 `isLock`으로 함께 조회한다.
- `StitchGroup` 생성 자체는 땀/잠금을 만들거나 필터를 실행하지 않는다. 기본 잠금은 null이고,
  요소 계층이 SVG `ties`, `lock_start/end` 속성으로 생성해 넣는다.
- `lockStitches(position, disableTies)`는 전체 disable 또는 2땀 미만이면 빈 결과다.
- `ColorBlock` 결합 시 원본처럼 RGB만 비교한다(`EmbThread.sameColor`).
  가져온 실 메타데이터 보존에는 `sameThread`/전체 데이터 동등성을 유지한다.
- `StitchPlan`은 수동 데이터 계약이다. 후처리/잠금/점프/정규화는 별도의 명시적 호출이다.
- 생성된 플랜(`originalThreadPalette == null`)의 어댑터는 색블록 사이 COLOR_CHANGE와 마지막 END를
  보완한다. 이미 존재하는 경계 명령은 중복하지 않는다.
- 가져온 플랜의 `originalThreadPalette`는 빈 배열도 의미가 있다. raw 어댑터 왕복에서는
  모든 원래 명령(END 뒤 레코드 포함), 원래 실 목록, 메타데이터, warnings를 그대로 보존한다.
  기존 PES/DST를 자동 재생성하거나 잠금을 덧붙이는 전역 writer hook은 넣지 않는다.

### SVG 속성 대응

새 요소 파라미터 DTO는 SVG 이름을 그대로 사용한다. 위 공유 데이터 계약만 Kotlin camelCase다.

| SVG 속성 | 공유 그룹 필드 | 단위/기본값 |
|---|---|---|
| `trim_after` | `trimAfter` | false |
| `stop_after` | `stopAfter` | false |
| `lock_start`, `lock_start_scale_mm`, `lock_start_scale_percent`, `lock_custom_start` | `lockStart` 팩토리 입력 | half_stitch / 0.7mm / 100% / 빈 경로 |
| `lock_end`, `lock_end_scale_mm`, `lock_end_scale_percent`, `lock_custom_end` | `lockEnd` 팩토리 입력 | half_stitch / 0.7mm / 100% / 빈 경로 |
| `min_stitch_length_mm` | `minStitchLengthMm` | null, 전역 0.1mm 적용은 후처리 책임 |
| `min_jump_stitch_length_mm` | `minJumpLengthMm` | null, 전역 collapse 3mm 적용은 후처리 책임 |
| `force_lock_stitches` | `forceLockStitches` | false |

## 변경 단계와 검증 상태

첫 커밋 `dc38aac`은 **새 계약과 어댑터만** 추가했다. 기존 생성 결과는 바꾸지 않았다.
후속 잠금·최소 길이·명령·Stroke 기능과 기존 생성 경로 연결은 개별 커밋으로 검토한다.
외부 게이트가 BLOCK인 기능은 테스트 통과와 구분하며, 병합 가능으로 보고하지 않는다.

| 기능 | 원본 파일 | 테스트/외부 게이트 |
|---|---|---|
| 수동 계약·단위·어댑터 | `lib/stitch_plan/{stitch,stitch_group,color_block,stitch_plan}.py`, `lib/elements/element.py`, `lib/utils/geometry.py`, `lib/svg/units.py` | `StitchPlanContractTest`: 전체 명령·좌표 범위·실 메타데이터·기존 6개 입력→동일 PES 바이트·고정 SVG→PES. 독립 게이트 결과는 아래 검증 기록에 추가 |
| 잠금·공통 후처리 | `lib/stitch_plan/lock_stitch.py`, `stitch_plan.py`, `color_block.py`, `lib/elements/element.py` | 10종+기본값 SVG, ties/스케일/명령/minimum/앵커/재생성 회귀; 릴리스 BLOCK |
| Running/manual/zigzag | `lib/elements/stroke.py`, `lib/stitches/running_stitch.py`, `lib/utils/prng.py` | 분석 좌표·반복·최대 길이·결정적 난수・고정 SVG 4개; 릴리스 BLOCK |
| Ripple | `lib/stitches/ripple_stitch.py`, `lib/stitches/tatami_fill.py` | target/guide/grid/clip/hole/graph 분석 회귀・고정 SVG 2개; Satin provider 통합 대기 |

원본 `tests/`에는 해당 잠금/러닝/Ripple 알고리즘의 좌표 기대값 단위 테스트가 없다.
수식에서 직접 읽은 분석 fixture는 원본 실행 결과와 비교한 골든이라고 주장하지 않는다.
`tests/test_clone.py`의 trim/stop OR 속성 테스트, `tests/test_output.py`의 결정적 반복 출력과
trim 포함 시 출력이 달라지는 성질은 해당 후속 계층 테스트로 옮긴다(JEF 대신 지원 PES 사용).

외부 판정은 `/tmp/myembro-quality-gate/check-release --matrix <JSON> --json <report>`를 사용한다.
원본/게이트/임계값을 수정하지 않는다. 어댑터 SVG smoke는 공개 계약의 수동 경로 입출력만
검증하며, 신규 생성 알고리즘 전체나 모바일/실제 봉제 품질의 통과를 의미하지 않는다.
알고리즘별 SVG fixture/PES 매트릭스는 별도로 제출해야 한다.

## 차이점

| 위치 | 원본과 차이 | 이유/한계 |
|---|---|---|
| 공유 DTO | Python 가변 객체 대신 Kotlin 데이터/읽기 전용 List, 원본 필드 이름 일부 camelCase | Kotlin 공개 계약; SVG 파라미터 DTO는 원본 이름 유지 |
| `Stitch.minStitchLengthMm` / 그룹 `*Mm` | 원본 내부 px 대신 공개 값은 mm로 보관 | SVG 속성 단위를 잃지 않고 경계에서 명시 변환 |
| `Stitch.end`, 플랜의 metadata/warnings/originalThreadPalette | 원본 플랜 외 확장 | 기존 EmbPattern raw 왕복에서 END·팔레트·읽기 진단 보존 |
| px→0.1mm 어댑터 | 기존 앱 좌표 격자, Java Math.round(음수 절반은 +∞ 방향) | 기존 PES 정수 API와 연결; px 좌표는 어댑터 전까지 유지 |
| 명령 플래그 결합 | 한 위치의 복수 플래그를 JUMP→TRIM→STOP→COLOR_CHANGE→END 순 명령으로 펼침 | 기존 단일 Command enum의 제약; 일반 STITCH와 제어 명령을 혼동하지 않음 |
| 기존 UI 생성 경로 | 첫 계약 커밋은 연결 없음; 후속 연결은 명시적 generated bridge | 가져온 PES/DST의 원래 잠금·명령을 자동으로 변경하지 않음 |

### 계약 첫 커밋 검증 기록

- `StitchPlanContractTest` **7개 통과**. 6개 기존 PES/PEC/DST 픽스처는 raw 어댑터 왕복 후
  명령·좌표·실 정보 및 기존 `PesWriter` 출력 바이트가 같다.
- 병렬 작성 중인 알고리즘 소스는 `/tmp/myembro-worker-contract-only.gradle`로 제외하고
  첫 커밋의 계약 소스만 포함해 `:engine:test`를 실행했다. 이 임시 필터는 제품/저장소에 넣지 않는다.
- 고정 입력 `engine/src/test/resources/inkstitch/contract.svg` → `engine/build/inkstitch-contract/contract.pes`.
- 독립 도구 `check-release` 판정 **PASS 1/1**, exit 0.
  `/tmp/myembro-worker-contract-matrix.json`, `/tmp/myembro-worker-contract-gate.json`에 기록한다.
- 이 판정은 **수동 SVG 어댑터 smoke**만 대상으로 한다. 아직 미연결된 잠금/Stroke/Ripple이나
  모든 폰트 릴리스가 통과했다고 확대 해석하지 않는다.



## S5 별도 원본: 흑백 윤곽·다각형 부분 포팅

Ink/Stitch에 없는 사진 입력 준비 기능이며, Ink/Stitch 코드를 포팅한 것으로 표시하지 않는다.
원본 Potrace 1.16 / 미러 커밋 `886392d351417d6753aff61de08e294d1506e502`,
`src/decompose.c`, `src/trace.c`, `src/bitmap.h`, `src/auxiliary.h`에서 엔진 내부
`BinaryContourTracer` / `OptimalPolygon`으로 옮겼다. GPL-2.0-or-later 출처와 전문을 보존한다.
상세 해시·재현 명령은 [image-pipeline.md](image-pipeline.md)의 첫 포팅 단위 참조.

| 위치 | 원본과 차이 | 이유/한계 |
|---|---|---|
| 흑백 비트맵 | BooleanArray, XOR 기준 x=0, 입력 복사 | 워드 저장만 대체; 교차 패리티 유지 |
| 경로 면적 | Long 유지, C API의 INT_MAX 포화 생략 | 중간 엔진 API이며 기존 PES API에 미연결 |
| 분해 결과 | 탐색 순서와 양/음 부호, 계층 트리 없음 | 이번 단위는 기본 MINORITY 추적까지만 이식 |
| 다각형 이후 | vertex adjustment·Bezier·곡선 최적화 없음 | 불완전한 중간 결과를 제품 경로에 연결하지 않음 |

대표 Kotlin 기능 테스트 8개와 엔진 전체 테스트 293개 통과. 원본 실행/독립 게이트는
실행하지 않았고 동등성·봉제 품질 통과를 주장하지 않는다. 새 PES/앱 진입점도 없다.


### S5 곡선화 후속 단위

동일 Potrace 커밋의 `pointslope`/`adjust_vertices`/`reverse`/`smooth`를
`CurveFitter.kt`로 포팅했다. 위 표의 vertex adjustment·Bezier 미구현 상태를 갱신한다.
곡선 최적화(stage 5)와 계층 트리는 여전히 미구현이며 앱 미연결이다.
작은 PBM 3개에 대해 수정하지 않은 원본 C를 `opticurve=0`으로 실행해 비교했다:
경계·다각형 정확히 일치, 꼭짓점·제어점·alpha/beta 절대오차 1e-10 이내.
새 비교 테스트 3개 포함 엔진 테스트 296개 통과. 재현 명령/해시는
`engine/src/test/resources/potrace/README.md` 참조. 독립 봉제 품질 통과 주장은 없다.


### S5 곡선 최적화 후속 단위


- **엔진 379개, 앱 167개 테스트 통과**, `:app:assembleDebug` 성공.
  신규 `inkstitch` 테스트 101개 포함. 테스트·빌드 로그:
  `/tmp/myembro-worker-final-attributes-tests.log`.
- `StitchPlanContractTest`의 6개 기존 파일 raw 왕복→PES 바이트 동일 보장은 계속 통과한다.
- SVG fixture 17개: 잠금 10종+실제 기본값 1개, Stroke 4개, Ripple 2개.
  Ripple fixture도 실제 요소처럼 기본 시작·끝 `InkLockStitch`를 명시하고 메타데이터를 검사한다.
- `python3 tools/check_inkstitch_worker.py --general` 실행:
  **일반 도안 PASS 17/17**, **필수 release BLOCK 17/17, exit 1**.
  원문 매트릭스·결과·로그: `/tmp/myembro-worker-inkstitch-quality/`.
  차단 사유는 TEXT_START_SEQUENCE_MISSING 및 일부 START/END_LOCK_MISSING이며,
  이 실행에서 다른 바이트 구조·좌표·길이·밀도 차단 사유는 보고되지 않았다.
- 고정 게이트 threshold SHA-256:
  `313eaf7dd85125d5c0340f9d87b5c14ca6cc9a640faf360214c0d300c2b4bca9`.
  도구·임계값·pin 해시는 수정하지 않았다. 일반 PASS는 release 차단을 대체하지 않는다.
- **병합 보류**: 독립 담당 Codex가 비텍스트/원본 잠금에 맞는 판정 정책을 제공해야 한다.
  Satin provider와 SVG DOM 경계의 통합 대기도 위 메모대로 남아 있다.

| 기능 커밋 | 원본 | 확인 |
|---|---|---|
| `dc38aac` 계약·어댑터 | stitch_plan, elements/element, geometry, units | 계약 7개, 수동 SVG 어댑터 smoke 외부 PASS 1/1 |
| `d4c6776` 잠금·최소 길이·명령·generated bridge | lock_stitch, stitch_plan, color_block, element | 전용 37개, 잠금 SVG 11개; release BLOCK |
| `63eb068` running/bean/zigzag 샘플러·PRNG | running_stitch, utils/prng | 전용 12개; 실제 요소 SVG 검증은 Stroke와 함께 |
| `ec620c1` Ripple·클립 그래프 | ripple_stitch, tatami_fill | 전용 29개, SVG 2개; release BLOCK |
| `26aa2d3` Stroke 파사드 | elements/stroke, elements/element | 전용 13개, SVG 4개; release BLOCK |
| `a77f591` 기존 생성 경로 연결 | 앱 고유 연결, 위 공통 후처리 | 통합 3개 및 기존 전체 회귀 통과; raw/본문/변환 보존 |


## S1 개정 — 자동 마무리만 적용 (2026-10-24)

개정한 main 작업트리 `docs/ui-stories.md`의 **실패 없는 디자인** 원칙을 따른다.
봉제 설정 섹션, 잠금 타입 선택/미리보기, 고급 파라미터 및 점검 요약 줄은 배포하지 않는다.
10종 잠금과 고급 파라미터 API는 원본 검증·후속 개발용 엔진 기능이며 화면에 노출하지 않는다.
Ripple/Satin provider 연결, Bean 등 추가 생성 기능 활성화는 이번 단계에서 제외한다.

- 기본 잠금: 원본 `half_stitch`, `ties=0`, `force_lock_stitches=false`.
- 최소 땀: 0.1mm. 그룹 최소 점프: 3mm. `trim_after=false`는 원본 기본값이며,
  기존 명시적 TRIM 및 객체 사이 TRIM은 보존한다. 근거 없는 자동 트림 거리 상수는 추가하지 않는다.
- 기존 native 생성 bridge의 자체 폴리라인 half-lock과 min=0 기본 정책을 제거한다.
  typed `StitchPlanBuilder`는 원본 그룹 collapse·색·trim/stop 규칙 그대로 사용한다.
- native 패턴의 이미 명시된 JUMP는 needle-up 명령으로 보존한다. SVG 그룹 provenance가 없는
  바늘 명령에서 빈 공간을 재봉으로 바꾸지 않으며, 이 호환 경계는 원본 그룹 collapse와 구분한다.
- 기존 레터링/편집기의 생성 패턴은 같은 기본 후처리를 사용한다. 이전 `.myembro`의 생성
  객체도 다시 계산한다. raw PES/PEC/DST에는 잠금/최소 길이 정리를 적용하지 않는다.
  원본 PES 저장·공유·USB 전송의 바이트 전달은 유지한다.
- 앱 기본값은 고정값이며 `sewing_options` 저장소나 사용자 설정을 읽지 않는다.
  미리보기·봉제 점검·PES 내보내기는 같은 기본값을 사용한다.

### 고정 SVG 매트릭스 재현

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:verifyInkstitchWorkerBoundary :engine:runInkstitchWorkerMatrix
# 동등한 CLI (입력/출력 경로 옵션은 --help):
python3 tools/generate_inkstitch_worker_matrix.py
```

- 입력 목록: `/tmp/myembro-quality-gate/matrix-inkstitch.json`의 `stitch_plan/`, `ties/`, `stroke/`.
- 실제 입력: `/tmp/myembro-inkstitch-oracle/inputs/`의 materialized SVG. 원래 입력과 SVG의
  SHA-256을 검증하며 `.oracle.json`에서는 식별·출처 필드만 읽는다.
- 출력: `/tmp/myembro-port-out/worker/{stitch_plan,ties,stroke}/<id>.pes`.
- 목록·입력 해시·실행 커밋·dirty 여부: 같은 폴더 `manifest.json`, `inputs.tsv`.
- 활성 `defaults-v2`: 168건(각 기능 56건) 전부 생성. 이 SVG에는 기본값을 덮어쓰는 숫자 속성을 주입하지 않는다.
  이전 탐색 매트릭스 507건/505개 생성 기록은 과거 기록이며 새 기본값 판정에 섞지 않는다. GENERATED는 동등 판정이 아니다.
- Runner는 Inkex CSP 제어점→transform→0.1px subdivision, 전체 명령의 bbox 중심,
  pystitch translate/scale 평가 순서와 Python 짝수 반올림을 원본처럼 적용한다.
  공개 adapter와 편집기 배치 좌표 계약은 변경하지 않는다.
- 원본 출력 비교용 `PesWriteSettings(inkstitchCommands=true)`는 pystitch PEC의 viewport prefix,
  첫 JUMP 이후 trim/jump, trailing STOP duplicate 색 이벤트를 재현한다.
  이 opt-in 프로필은 원점을 바꾸므로 앱의 기존 배치·가져오기 경로에는 적용하지 않는다.
  일반 writer의 기본 바이트는 유지한다. 공식 PES의 모든 바이트 동일성을 주장하지 않는다.
- 원본 Ink/Stitch/Inkex/pystitch는 읽기만 했으며 실행·의존성 설치를 하지 않았다.

독립 판정 제출 커맨드(원본 실행이 아닌 embro-codex 비교 도구):

```sh
python3 /tmp/myembro-inkstitch-oracle/compare_matrix.py --ports /tmp/myembro-port-out/worker/manifest.json --report /tmp/myembro-port-out/worker/independent-comparison.json
```

PE-Design 고정 잠금 바이트에 맞추려고 출력이나 임계값을 바꾸지 않는다. 병합 판정은
독립 담당 Codex의 비교·재보정 게이트가 맡으며, 실제 SE400 봉제 검토는 별도 완료 조건이다.

### 원본 기본 잠금의 경계 사례 — 안전 승인과 구분

`half_stitch`는 15개 후보 중 시작점에서 0.5mm보다 먼 점이 없으면 방향을 **+x**, 길이를
**0.5 SVG px**로 사용한다. 따라서 `(200,198)→(198,198)` native 경로에서는 잠금 바늘이
`(201,198)`까지 나올 수 있다(도형 경계보다 0.1mm 바깥). 원본 그대로의 동작이며 임의로
도형 안으로 잘라 내거나 별도 폴리라인 잠금으로 대체하지 않는다.

`DigitizingTest`는 실제 후처리 출력에서 추적된 기본 잠금 인덱스만 제외하고 **필터된 본문**의
구멍·오목부·분리 영역 불변식을 검사한다. 삭제 전 본문을 복구하는 `stripGeneratedLocks`로
검사하지 않는다. 별도 회귀는 위 fallback 좌표와 전체 잠금의 영역 포함 보장이 없음을 명시한다.
본문 검증 통과나 oracle 동등이 잠금까지 항상 도형 안에 있다는 안전 승인을 뜻하지 않는다.
이 경계 사례와 native bridge의 명시적 JUMP 보존은 독립 Codex 검토 항목이며, 실제 봉제는 미검증이다.

### 이전 탐색 매트릭스 독립 비교 기록 (matrix-v1, 이력)

`compare_matrix.py`(criteria SHA-256
`fbbf03ab6872d767b2c996f46f3c1cf3055b0b9f8f54b4c0a35fccdc095d6b74`) 원문 결과:

| 제출 기능 | 동등 | 불일치 | 미판정 |
|---|---:|---:|---:|
| stitch_plan | 168 | 0 | 1 |
| ties | 168 | 0 | 1 |
| stroke | 169 | 0 | 0 |

미판정 2건은 `ORACLE_UPSTREAM_ERROR`로 공식 기준 출력의 오류다. 제출하지 않은 S2 이후
기능은 이 기록에 포함하지 않는다. 이는 **PES-output comparison**이며 mechanical release gate,
원본 소스 전체 감사, SE400 실제 봉제 통과와 별개의 판정이다. 최종 커밋에서 매트릭스를 재생성해
`manifest.json`의 커밋과 clean 여부를 고정한 다음 Codex에 제출한다.

### S1 자동 기본값 빌드 검증

- `:engine:test` **393개 통과**, `:app:testDebugUnitTest` **171개 통과**(실패/skip 0).
- `:app:assembleDebug` 성공. 로그: `/tmp/myembro-worker-s1-automatic-tests.log`.
- 원본 adapter의 6개 fixture 바이트 보존 및 writer 기본 프로필의 기존 바이트 회귀 유지.
- 앱 회귀: 고정 기본값/이전 프로젝트 재계산, 잠금 10종 옵션에도 raw PES의 바이트·땀 불변,
  viewer/USB의 프로젝트 PES 생성 일치. 고급 타입 옵션은 테스트용 엔진 입력일 뿐 사용자 UI가 아니다.
- 변경된 기본 잠금 때문에 이전의 전체 도형 포함 불변식은 더 이상 성립하지 않는다. 이를
  테스트를 느슨하게 해 숨기지 않고, 실제 필터된 본문 검증과 명시적인 원본 fallback 반례로 나눴다.
- SE400 실물 봉제는 수행하지 못했다. 최종 병합·배포 가능 판정은 독립 Codex와 사용자 실봉제 검토가 필요하다.

### 활성 기본값 매트릭스 defaults-v2 제출

작업 중 독립 Codex가 매트릭스를 개정했다. Runner는 새 scope의 독립 동결 파일
`/tmp/myembro-inkstitch-oracle/out/defaults-v2/oracle-freeze.json`을 선택하고, 각 identity record의
동결 SHA-256·scope·id 및 원본 SVG와 materialized SVG의 **바이트 동일성**을 검사한다.
원본/좌표/PES 출력은 생성 입력으로 사용하지 않는다. 이전 scope도 명시적으로 지원하되 자료를 섞지 않는다.

- 활성 matrix SHA-256: `17f10563b6743539dba46f6db294f6f63b5f2db29b74d251c6521404755a5fe0`.
- 입력: `inputs/defaults-v2--<id>.svg`. 출력과 재현 커맨드는 위와 동일하다.
- **stitch_plan 56/56, ties 56/56, stroke 56/56 동등**, 불일치·미판정 0.
- 기계적 릴리스 게이트 v2 (`check-release --stage S1`): **144 PASS / 24 BLOCK**.
  threshold SHA-256: `cb9c68f379056ec8215c9e7673cedb5a5d166b47484923ef710c9b870bb5aff1`.
- 같은 독립 검사기에 **공식 Ink/Stitch 출력**을 제출해도 같은 24개 `crossing_strokes`가 BLOCK이다.
  세 구간의 시작 S0가 `UNEXPLAINED_ZERO`로 집계되고 작은 4–10mm 입력에서는 본문 짧은 땀 비율도 차단된다.
  functional 시작/끝 잠금은 검출된다. 이 결과를 임의 예외로 통과시키거나 원본 기본값을 변경하지 않는다.
- `independent-comparison.json`, `release-matrix.json`, `release-gate.json`,
  `reference-release-matrix.json`, `reference-mechanical-gate.json`은 `/tmp/myembro-port-out/worker/`에 둔다.
  릴리스 매트릭스의 font 축은 `feature/shape` fixture ID이며 제품 폰트·안전 크기 범위가 아니다.
- **S1 병합/배포 승인 대기**: Codex의 남은 게이트 검토와 사용자 실제 봉제. S2 이후로 자동 진전하지 않는다.
  코드·단위 테스트·빌드는 준비됐으며 외부 검토/봉제 완료 시점은 미정이다.


## 별도 원본: Wu 이미지 색 양자화

Ink/Stitch 자체 기능으로 표시하지 않는다. `ImageQuantizer.kt`는 Xiaolin Wu의
Graphics Gems `gemsii/quantizer.c@ca4898517a93b6b45444a9cda5fd50132694b864`를
포팅한다. custom Graphics Gems 허가/원본 배포 고지는 `licenses/graphics-gems/`에 보존.
차이점(호출별 배열·Long/Float 유지·하위 박스 Vol·입력 경계)은
`docs/image-pipeline.md`의 Wu 표에 기록한다. 대표 RGB 3개에 대해 원본 C LUT/픽셀
인덱스를 자동 비교하며, 앱·땀 생성에는 연결하지 않는다.
