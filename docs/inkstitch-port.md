# Ink/Stitch 병렬 포팅 계약과 검증

## 고정 원본

- 공통 기준: **Ink/Stitch v3.3.0**, 커밋 **`b0edd96311ece82ee48816dc93466274b12c2a9c`**.
- 공식 안정 릴리스: https://github.com/inkstitch/inkstitch/releases/tag/v3.3.0 .
- 소스: https://github.com/inkstitch/inkstitch/tree/b0edd96311ece82ee48816dc93466274b12c2a9c .
- embro-letter의 현재 신규 포트도 이 커밋을 사용한다. 이전 초안의
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

첫 단계는 **새 계약과 어댑터만** 추가한다. 기존 레터링/편집기/라이터 호출은 바꾸지 않는다.
후속 잠금·최소 길이·명령·Stroke 기능은 개별 커밋/품질 판정 후 연결한다.

| 기능 | 원본 파일 | 테스트/외부 게이트 |
|---|---|---|
| 수동 계약·단위·어댑터 | `lib/stitch_plan/{stitch,stitch_group,color_block,stitch_plan}.py`, `lib/elements/element.py`, `lib/utils/geometry.py`, `lib/svg/units.py` | `StitchPlanContractTest`: 전체 명령·좌표 범위·실 메타데이터·기존 6개 입력→동일 PES 바이트·고정 SVG→PES. 독립 게이트 결과는 아래 검증 기록에 추가 |
| 잠금·공통 후처리 | `lib/stitch_plan/lock_stitch.py`, `stitch_plan.py`, `color_block.py` | 포팅/검증 진행 중 |
| Running/manual/zigzag | `lib/elements/stroke.py`, `lib/stitches/running_stitch.py` | 포팅/검증 진행 중 |
| Ripple | `lib/stitches/ripple_stitch.py` | 포팅/검증 진행 중; Satin/Fill 경로 공유 필요 |

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
| 기존 UI 생성 경로 | 첫 계약 커밋에서는 아직 후처리 연결 없음 | 무동작 계약을 먼저 병합하기 위한 단계적 변경. 잠금 문제 해결 완료로 주장하지 않음 |

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


# SatinColumn 담당 구현·검증 기록

## 담당과 일정

`embro-letter` / `codex/letter`: SatinColumn 기하 코어와 기존 레터링 연결.
최초 예상은 기본 코어·언더레이 4–6시간, split/변형·동등성 검증 추가 6–10시간이다.
자동 경로·선→새틴 도구는 2단계다. 공통 StitchPlan 소유자는 `embro-worker`,
독립 출력 판정은 `embro-codex`다. 로컬 단위 테스트 성공을 릴리스 판정으로 대신하지 않는다.

## 새틴의 공통 계약 연결

공통 계약은 main `cef0156`에서 병합했다. 위의 worker 계약이 우선하며 같은 이름의 모델을 중복 생성하지 않는다. 코어와 공유 모델은 모두 SVG px를 사용한다.

```text
Stitch(x, y, tags: jump/trim/stop/lock/...)
StitchGroup(color, stitches, trimAfter, stopAfter, lockStart, lockEnd,
            minStitchLengthMm, minJumpLengthMm, forceLockStitches)
StitchPlan(colorBlocks)
EmbroideryElement.toStitchGroups(previousStitch)
StitchPlan -> 기존 EmbPattern/PES 어댑터
```

폰트 연결 위치는 `inkstitch.elements.SatinColumn.fromSubpaths()`이며 직접 레일을 아는 호출자는 생성자를 쓴다.
`SatinLayer(points, tags, stitchTags)`는 **기하 중간 결과**이며 공통 StitchGroup을
대체하지 않는다. 바늘별 `satin_column_edge`, `satin_split_stitch`, `peak_a/peak_b` 태그와
레이어별 `satin_center_walk`, `satin_contour_underlay`, `satin_zigzag_underlay`를 유지한다.
공통 어댑터는 같은 색의 언더레이/본 땀을 합치고 필요한 러닝 연결을 만든 뒤,
공통 그룹의 잠금·최소 땀·트림·점프 정책을 한 번 적용해야 한다.
`SatinColumn`은 이제 `EmbroideryElement`를 구현한다. `toStitchGroups(previousStitch)`가
worker의 실제 `StitchGroup`을 반환한다. 레이어 간 러닝을 연결하고 색·태그·trim/stop·
주입된 LockStitch·min length·force lock 설정을 보존한다. 공통 계약은 수동 DTO/어댑터여서
아직 잠금/필터를 실행하지 않는다. 공통 PlanBuilder가 도착하면 앱의 임시
`elements.InkSatin`/`stitch_plan.StitchPlanFinisher` 경계를 교체한다.

### 폰트 파서 연결 예

```kotlin
val groups = SatinColumn.fromSubpaths(
    subpaths = flattenedPathsInSvgPixels,
    parameters = SatinParameters(zigzag_spacing_mm = 0.4),
    nodePaths = originalBezierEndpointsInSvgPixels,
    color = svgStrokeColor,
).toStitchGroups(previousStitch)
```

SVG의 path들을 임의 순서로 받을 수 있다. `rail_indices` 방식으로 레일을 찾으며,
렁이 없으면 원래 Bézier 끝점으로 합성한다. **flattening으로 추가한 점을 원래 노드로
전달하지 않는다.** 직접 레일·렁을 알고 있으면 `generateLayers(left, right, ..., rungs)`를 쓴다.
`previousStitch`, `nextStitch`, 명시적 `startPoint/endPoint`도 SVG px다.
`SatinParameters`의 `_mm` 속성만 mm이며 private pixel getter에서 한 번 변환한다.
`random_seed`가 빈 SVG에서는 호출자가 원본처럼 element ID를 기본 seed로 넣는다.
TTF 외곽선은 이 폰트 API로 가장하지 않으며 기본은 auto-fill + 윤곽 러닝이다.

## SatinColumn 추적표

원본 파일은 별도 표시가 없으면 모두
`lib/elements/satin_column.py@b0edd96311ece82ee48816dc93466274b12c2a9c`다.

| 기능 | 원본 함수 | Kotlin |
|---|---|---|
| 레일 찾기·노드 렁·방향·swap | `rail_indices`, `_synthesize_rungs`, `_get_rails_to_reverse`, `line_string_rails` | `generateFromSubpaths`, `orient`, `sections` |
| 렁 구간 보간·밀도 | `flattened_sections`, `_stitch_distance`, `plot_points_on_rails`, `SatinProcessor` | `Generator.pairs`, `Processor` |
| 비대칭 mm/% pull·push | `_apply_push_comp`, `_extend_line`; `lib/utils/geometry.py:offset_points` | `push`, `offsetPoints` |
| short stitch | `inset_short_stitches_sawtooth`, `_get_offset_px` | `Generator.shorten` |
| 기본/단순/엇갈림 split | `get_split_points`, `_get_split_points_default/simple/staggered` | `Generator.splits` |
| 난수 split·phase | `lib/stitches/running_stitch.py:split_segment_even_n/random_phase/stagger_phase` | `splits`, `evenSplits` |
| satin·E·S·zigzag | `do_satin`, `do_e_stitch`, `do_s_stitch`, `do_zigzag` | `Generator.top` |
| 세 종류 언더레이 | `do_center_walk`, `do_contour_underlay`, `do_zigzag_underlay` | `Generator.layers` |
| 시작·끝·중심 연결 | `start_point`, `end_point`, `find_cut_points`, `_split_top_layer`, `_sort_stitch_groups`, `_connect_stitch_group_with_point` | `generateLayers`, `cut`, `centerConnector` |
| 재현 가능한 난수 | `lib/utils/prng.py:join_args`, `uniform_floats`, `n_uniform_floats`, `iter_uniform_floats` | `utils.Prng` |

`SatinParameters` 필드명·단위는 SVG 속성에 대응한다. 쌍 값은 첫/둘째 레일 순서,
`short_stitch_inset`은 **% 목록**, `pull_compensation_percent`도 **%**다.
원본 기본: spacing 0.4mm, short distance 0.25mm, inset 15%, split `default`,
max stitch 없음, center/contour/zigzag 언더레이 꺼짐, 언더레이 러닝 길이 3mm,
center repeats 2, contour inset 0.4mm, zigzag spacing 3mm다.
랜덤 split의 jitter는 최대 100%, 위상·폭·간격 난수는 원본의 BLAKE2s 카운터 스트림을 쓴다.
BLAKE2s는 [RFC 7693 §§2–3](https://www.rfc-editor.org/rfc/rfc7693.html)의 독립 Kotlin 구현이며
추가 의존성을 설치하지 않았다. RFC `abc`와 표준 hashlib 다중 블록/UTF-8 벡터로 대조한다.

## 차이점 — 숨기지 않는 원본/앱 경계

| 위치 | 차이 | 이유/영향 |
|---|---|---|
| SVG 입력 | 코어는 SVG px 폴리라인과 원래 노드 목록을 받음 | Inkex의 path parsing/flattening은 canvas 담당. Bézier 엔진을 중복 구현하지 않음 |
| 기하 | Shapely 대신 기존 JTS 1.20.0. 레일은 동일한 1e-5 SVG px precision grid 적용 | 교차·project·substring·offset의 수치 tie-break가 다를 수 있음. 바이트 동일성 미검증 |
| 렁 | 다중 교차·교차 렁을 무시하지 않고 예외로 거절 | 방향 제약을 몰래 버려 부채꼴 경로를 생성하지 않기 위한 보수적 차이 |
| 불량 입력 | 점/명령 수 예산 초과·비유한 값은 거절 | 모바일 자원 제한; 원본의 무제한 크기 지원을 주장하지 않음 |
| raw 코어 최소 길이 | 속성 미지정은 null로 유지하고 random split 계산에서만 metadata 기본 0.1mm를 사용; 전체 최소 땀 필터는 아직 적용하지 않음 | 원본도 그룹→StitchPlan 경계에서 전체 필터를 적용. worker 계약 소유권 유지 |
| 공통 타입 연결 | `SatinColumn : EmbroideryElement`, 실제 공유 StitchGroup 반환. lockStart/End는 팩토리가 만든 객체를 주입받음 | worker의 수동 계약 병합 완료; 기본 잠금 팩토리/전체 PlanBuilder 알고리즘은 아직 미연결 |
| raw 코어 잠금/트림 | `lock_start/end`, `trim_after`, `stop_after`는 geometric layer에 삽입하지 않음 | 공통 StitchGroup/Plan에서 적용할 속성. 중복 잠금 방지 |
| center-walk tolerance | 원본 getter/속성은 노출하되 중심선 계산은 원본처럼 `running_stitch_tolerance_mm` 사용 | v3.3.0 `_get_center_line_stitches` 실제 동작과 일치 |
| running tolerance | UI decorator 0.1과 달리 getter 기본 **0.2mm** 사용 | v3.3.0 런타임 기본에 맞춤 |
| 앱 어댑터 | max 7mm, 폭 >7mm이면 max 4mm split, short distance 0.3mm, compact lock 0.4mm | 이전 사용자 요구에 따른 앱 정책. SVG 코어 기본과 분리 |
| 앱 최소 땀 | 정수화 후 <0.3mm는 needle-up 이동으로 유지, 0길이 바늘 제거 | 좌표를 없애 새 대각선이 홀을 가로지르는 일을 방지. **원본 duplicate filter와 다름** |
| 앱 과밀 | 한 객체 0.5mm 셀 누적 8회/연속 6회 초과 시 예외 | 기존 보수적 정책. 독립 게이트의 4위상/15회와 다름. 통과 증명으로 대신하지 않음 |
| 앱 외곽선 | TTF 자동 중심선 새틴을 기본에서 제거. 사각 기둥 검증 실패 시 auto-fill | 이전 교차·끝점 부채꼴 실기 불량 차단. TTF 기둥 분해 포팅을 주장하지 않음 |
| 기존 선→새틴 | `lettering.SatinColumn.rails`의 독립 miter offset은 아직 남아 있음 | 2단계의 원본 convert-to-satin 포팅 대상. 새 SVG 코어 구현으로 오인하지 않음 |
| auto-fill 초안 | 일정 간격/고정 stagger만, bounded exact odd matching, 이동 smoothing 생략 | 이전 안전 초안. fill 담당자가 정식 파라미터/모듈로 이어받을 영역; 상세는 stitch-algorithms.md |

## 테스트·독립 게이트 상태

- v3.3.0 `tests/` 21개 파일을 확인했다. SatinColumn 대응 테스트가 없어 복제할 원본
  입출력 fixture는 없다. `SatinPortTest`는 수기 좌표, 수식, 순서, 난수 벡터 10개다.
- `SatinElementContractTest` 3개: SVG px·태그·색·주입 잠금·trim/stop·null 기본값과 실제 공통 패턴 어댑터 연결.
- `SatinFixtureTool`: 저장소의 공개 합성 SVG → SatinColumn 요소 → 실제 공유 그룹/어댑터 → 7종의 PES/명시적 축 매트릭스 생성.
  `MYEMBRO_SATIN_FIXTURES=/tmp/... ./gradlew :engine:test --tests '*SatinFixtureTool'`.
- `BirdnestRegressionTest`: 공개 OFL Dancing Script Bold, `Jung Min`, 34mm, 40wt.
  검증되지 않은 SATIN 요청도 auto-fill과 동일한 출력으로 fallback함을 검사한다.
  0.3mm 미만 땀 0, 단일 위상 셀 최대 ≤8, 끝 잠금, PES v1/v6 왕복을 검사한다.
- 전체 자체 회귀: 엔진 **309개**, 앱 **167개** 성공, `:app:assembleDebug` 성공.
- 감사 203개 중 200개 생성, 3개 과밀 차단. 생성된 결과의 0.3mm 미만 땀은 0개,
  단일 위상 셀 최대 8회. `Jung Min`은 5,389땀·단일 위상 셀 최대 7회.
  독립 게이트의 4위상 셀 최대는 8회다. 시작 형식 차단을 해소한 것은 아니다.
- 비교 PNG: `/tmp/myembro-birdnest/before.png`, `after.png`.
  개인 파일·추출 좌표·개인 렌더링은 커밋하지 않는다.
- 독립 실행: `/tmp/myembro-quality-gate/check-release --matrix … --json …`.
  결과는 **BLOCK**. 합성 새틴 0/7, `Jung Min` auto-fill 0/1이다.
  공통 차단: `TEXT_START_SEQUENCE_MISSING` — 게이트는 J→S0→0.28mm 반전 2땀,
  현재 임시 앱 어댑터는 S0 제거·0.4mm 반전이다. split random은 `START_LOCK_MISSING`도
  관측되어 연결부 보완이 필요하다. split/E/S 일부는 `SATIN_NOT_VERIFIABLE`이다.
  판정 표본이 부족한 것을 통과로 바꾸지 않는다.
- 보고서: `/tmp/myembro-birdnest/satin-fixtures/gate-report.json`,
  `/tmp/myembro-birdnest/release-jung-min-final.json`.
  **릴리스/병합 가능 판정 아님. 원본 실행 차분·SE400 실물 검증 미완료.**

공통 소유자에게 필요한 결정: “모든 <0.3mm/0길이 바늘 제거”와 게이트의 명시적 S0/0.28mm
시작 형식은 동시에 만족할 수 없다. 잠금 예외와 공통 Plan의 기본값을 합의하고,
새틴 변형에 적용할 독립 게이트 분류를 확정한 뒤 출력 매트릭스를 다시 판정해야 한다.
게이트나 임계값은 이 브랜치에서 수정하지 않았다.
