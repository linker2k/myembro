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
주입된 LockStitch·min length·force lock 설정을 보존한다. S2는 S1 worker의
`StitchPlanBuilder`/`GeneratedPatternProcessor`에 연결한다. 그룹 DTO 자체는 수동 계약이다.

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
| 렁 | 원본처럼 >2점 교차 렁 무시, 각 레일 cut 정렬 후 zip, 빈 구간 제외 | 이전 crossing-rung 강제 거절 정책 제거. JTS 수치 오차는 별도 비교 대상 |
| 불량 입력 | 점/명령 수 예산 초과·비유한 값은 거절 | 모바일 자원 제한; 원본의 무제한 크기 지원을 주장하지 않음 |
| raw 코어 최소 길이 | 속성 미지정은 null로 유지하고 random split 계산에서만 metadata 기본 0.1mm를 사용; 전체 최소 땀 필터는 아직 적용하지 않음 | 원본도 그룹→StitchPlan 경계에서 전체 필터를 적용. worker 계약 소유권 유지 |
| 공통 타입 연결 | `SatinColumn : EmbroideryElement` → S1 공통 builder. 기본은 source half_stitch; null 주입으로 해제 가능 | worker d4c6776 통합, 임시 Finisher 삭제 |
| raw 코어 잠금/트림 | `lock_start/end`, `trim_after`, `stop_after`는 geometric layer에 삽입하지 않음 | 공통 StitchGroup/Plan에서 적용할 속성. 중복 잠금 방지 |
| center-walk tolerance | 원본 getter/속성은 노출하되 중심선 계산은 원본처럼 `running_stitch_tolerance_mm` 사용 | v3.3.0 `_get_center_line_stitches` 실제 동작과 일치 |
| running tolerance | UI decorator 0.1과 달리 getter 기본 **0.2mm** 사용 | v3.3.0 런타임 기본에 맞춤 |
| 앱 어댑터 | 기존 프로젝트의 legacy 필드만 source DTO로 매핑. 신규 선택은 source 기본값 | 폭 >7mm 강제4mm·short distance0.3·compact0.4 정책 제거 |
| 앱 채우기 초안 | 레거시 FillStitches는 아직 0.3mm 본문 모서리 병합을 사용 | S4 정식 auto-fill로 대체할 영역; 잠금은 S1 소유이며 일괄0.3mm 강제가 아님 |
| 과밀 | 생성기에서 바늘을 지우거나 셀8회 예외로 차단하지 않음 | 독립 gate/sew-out 진단 책임. source 잠금 포함 관찰값을 제출 |
| 앱 외곽선 | TTF SATIN 요청은 사각형 포함 전부 auto-fill+경고. UI에서 신규 선택 제거 | convert/auto-route 검증 전 자동 중심선 기둥 생성 금지 |
| 기존 선→새틴 | `lettering.SatinColumn.rails`의 독립 miter offset은 아직 남아 있음 | 2단계의 원본 convert-to-satin 포팅 대상. 새 SVG 코어 구현으로 오인하지 않음 |
| auto-fill 초안 | 일정 간격/고정 stagger만, bounded exact odd matching, 이동 smoothing 생략 | 이전 안전 초안. fill 담당자가 정식 파라미터/모듈로 이어받을 영역; 상세는 stitch-algorithms.md |

## 이전 검증 기록 — 3bbaaa4, 현재 S2 결과가 아님

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


## S2 개정 적용과 재현 (2026-10-24)

현재 기준은 main 작업트리 `docs/ui-stories.md`의 **실패 없는 디자인** 개정안이다.
이전 US-3/4 상세 설정·고급 탭 작업은 취소했다. S1 사용자 검토 전 S2 main 병합/릴리스는 보류한다.

- S1 `384b259` 갱신판 `d4c6776`은 의존 커밋 `3405b63`으로 가져왔다.
  b798af7 임시 compact 잠금은 삭제했고 S1 공통 후처리를 사용한다.
- UI 입력은 글자·글꼴·높이·색만이다. 종류/밀도/바탕 땀/실 굵기/빈/곡선/자간/정렬 설정은 노출하지 않는다.
  새틴 코어의 전체 파라미터 API는 포팅 검증과 기존 데이터 해석을 위해 남기지만 신규 생성은 원본 기본값만 쓴다.
- TTF 자체 자동 새틴은 기본에서 제외했다. 새틴은 명시적 SatinColumn과 검증된 글꼴의 레일/렁만 대상이다.
  새 TextObject와 LetteringEngine 기본은 RUNNING이다. FILL/BEAN은 기존 저장 파일의 명시적 요청을 해석할 뿐 신규 UI 기본이 아니다.
- 기본 러닝은 Ink/Stitch stroke getter의 길이 2.5mm·허용오차 0.2mm다. 추가 Douglas–Peucker 단계를 적용하지 않는다.
- FontQuality의 자체 권장 높이는 기본 결정·높이 제한에 사용하지 않는다. 3b6ec12 TTF 분석도 승인 근거가 아니다.

### 자동 규칙의 단일 입력

embro-codex의 `/tmp/myembro-quality-gate/auto-rules.md`를 확인했다. 안전한 글자 높이·새틴 폭의
수치 경계는 **미확인**이다. PES 전체 bbox나 4–150mm 비교 매트릭스를 안전 범위로 일반화하지 않는다.
총괄의 추가 결정에 따라 미확인 글꼴은 S2에서 **새틴을 막고 러닝만 허용**한다. 이는 러닝의 모든 높이가
실기 검증됐다는 뜻이 아니다. 확인되지 않은 5–50mm 등의 범위를 새로 만들지 않는다.

```sh
python3 tools/import-lettering-rules.py /tmp/myembro-quality-gate/auto-rules.md
```

이 도구는 문서의 명시적 fenced JSON `{ "schemaVersion": 1, "profiles": [...] }`만 가져온다.
표/본문의 숫자는 추론하지 않는다. 현재 승인 프로필은 0개다. 런타임의 유일한 규칙 입력은
`engine/src/main/resources/lettering/validated-ranges.json`이며, 원문 이름·SHA256을 기록한 배포 스냅샷이다.
새 수치가 승인되면 이 명령으로 갱신하고 리뷰한다. 별도 하드코딩 범위나 FontQuality fallback은 없다.
프로필은 fontId·파일 내용 SHA256+추출 버전·글리프 코드포인트·방식·높이 범위·근거를 묶는다.
SATIN은 SATIN_COLUMNS 기하와 검증된 폭 범위도 필요하다. TTF를 기둥으로 추정하지 않는다.
S3 자수 글꼴 메타데이터 연결은 파서 담당의 후속 작업이다.

확정 범위가 있으면 높이 슬라이더를 그 범위로 제한한다. 현재 미확인 상태는 안내와 러닝 높이 숫자 입력을
사용한다(총괄 허용). 비유한/0 이하 높이·없는 글리프·후프 밖 결과는 추가할 수 없다.

### defaults-v2 재현

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:satinOracle --console=plain
```

현재 `/tmp/myembro-quality-gate/matrix-inkstitch.json`의 satin_column 56개 SVG를 읽는다.
매트릭스 SHA256 `17f10563b6743539dba46f6db294f6f63b5f2db29b74d251c6521404755a5fe0`.
각 case가 지정한 input과 input_sha256을 그대로 검증한다. 출력은 `/tmp/myembro-port-out/letter/`의
PES 56개·`inputs.txt`·`manifest.json`이며 commit/dirty/branch/stage/입출력 해시를 기록한다.
`-PmatrixFile=… -PportOutput=…`로 입력 문서/출력 위치만 바꾼다. 원본 실행·설치·기준 변경은 하지 않는다.
현재 공식 oracle은 이전 매트릭스에 고정돼 있어 **defaults-v2 공식 동등성 판정은 대기**다.

이전 파라미터 매트릭스의 유효 224/224는 공식 비교 도구의 동등 기준을 통과했다(불일치0).
zero-rails 음성1건은 거절/미판정이었다. 이 기록은
`/tmp/myembro-port-out/letter/legacy-parameter-matrix/`에 보존했다.
현재 defaults-v2나 제품의 안전한 높이/폭 범위를 인증한 결과로 쓰지 않는다.
`-PmatrixFile=`는 이전 frozen oracle SVG 225건을 재생한다.

`svg.ViewBoxTransform`은 원본 `lib/svg/units.py:get_viewbox_transform`의 6자리 %f 축척과
translate @ scale 순서를 보존한다. curve flatten tolerance는 0.1 SVG px다.
일반 SVG viewport 계산으로 대체하면 정확한 split 경계와 봉제 순서가 달라진다.

### Jung Min 및 검증 상태

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 MYEMBRO_START_AUDIT=/tmp/myembro-birdnest/s2-running ./gradlew :engine:test --tests '*LetteringAuditTool' --rerun-tasks
/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-birdnest/s2-running/script-34.pes --text --style running --json /tmp/myembro-birdnest/s2-running/quality-v2.json
```

공개 OFL Dancing Script Bold, Jung Min, 34mm, 40wt의 개정 러닝 결과는 **531땀·트림8·S0 9개**다.
현재 v2 진단은 **BLOCK: UNEXPLAINED_ZERO 9/531**. 셀 집중·시작/끝/트림 잠금 검사는 통과했다.
S1의 시작 앵커를 PE-Design 수열에 맞춰 삭제하지 않았다. 시작20땀 TSV와 생성 잠금 태그 추적 JSON을
함께 제출한다. 추적 JSON은 PES 레코드 인덱스나 독립 승인으로 가장하지 않는다.
PNG는 `/tmp/myembro-birdnest/s2-running.png`. 개인 PES와 개인 렌더링은 커밋하지 않는다.
이전 fill 진단 PASS는 `/tmp/myembro-birdnest/s2/`의 별도 과거 기록이며 개정 S2 통과가 아니다.

자체 회귀는 엔진354개·앱167개 및 APK debug 빌드 성공. 임시 API35 에뮬레이터에서 4입력,
자동 러닝/미확인 안내, 높이0 추가 차단·정상값 복원 후 활성화를 확인했다.
**S2 완료/릴리스 승인 아님:** defaults-v2 공식 비교, 위 S0 판정 검토, S1 사용자 검토·SE400 실제 봉제가 남았다.


### 총괄의 검증 정책 재개정 — 현재 단위 종료

수백 건 동등성 매트릭스 검증은 중단한다. 위 매트릭스와 v2 S0 비율 BLOCK은 과거 진단 기록이다.
S0 비율·짧은 땀 비율·길이 분포·새틴 간격 통계는 차단 조건이 아니다. 현재 새 기준으로 재판정했다고
주장하지 않는다. 이후 검증은 대표 사례 10개 이내에서 땀 수·모양을 확인하고, 실제 실패 조건인
0.5mm 셀 밀집·같은 점 연속 천공·기능적 시작/끝 잠금·최대 땀 길이·파일 유효성/후프 범위만 다룬다.
최종 판정은 사용자 실제 봉제다. 이번 단위는 코드·재현 산출물·간소화 UI를 push한 지점에서 종료하며
다음 검증 단위는 총괄 결정 후 진행한다.

### S2 정리: S1 잠금과 담당 범위

새틴은 worker S1 `InkLockStitch()`의 기본 `half_stitch`를 시작·끝에 전달하고
`GeneratedPatternProcessor.fromGroups` 하나만 거친다. 자체 COMPACT/0.4mm 왕복 잠금은 없다.
회귀 테스트는 양쪽 `lock_id=half_stitch`와 S1 잠금 태그 생성을 확인한다.

S4 타타미 구현은 `digitizing/OutlineTextFill.kt`로 분리했으며 model 담당이다.
기존 저장 객체 호환을 위한 `lettering/OutlineFill` 계약·위임은 유지한다. S2에서는 확정되지 않은
TTF 새틴 요청을 러닝으로 바꾸며, 새틴 요청을 타타미로 대체하지 않는다.

생성기에 자체 0.5mm 셀당 8회 거부 조건을 두지 않는다. 봉제 점검 기본값은 게이트와 동일하게
15회까지 허용하고 16회부터 차단한다. 잠금 판정 기준③은 바꾸지 않았다.
대표 검증은 satin_column 7개 형상의 20mm 기본값만 재생성한다. 릴리스 게이트 판정은
`check-release` 결과로 따로 보고하며 독립 승인이나 실제 봉제 통과로 주장하지 않는다.

대표 7건 재판정(생성 커밋 `0ce60ae0e820985911bf43ba5b47a6a28bc52a56`):
사각형·원·홀·자기 교차·좁은 끝·급한 꺾임·교차 획, 높이 20mm, 원본 기본값이다.
출력과 해시는 `/tmp/myembro-port-out/letter/representative-7/manifest.json`,
재현 입력은 같은 폴더 `inputs.json`이다.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:satinOracle -PmatrixFile=/tmp/myembro-port-out/letter/representative-7/inputs.json -PportOutput=/tmp/myembro-port-out/letter/representative-7
/tmp/myembro-quality-gate/check-release --stage S2 --matrix /tmp/myembro-port-out/letter/representative-7/release-matrix.json --json /tmp/myembro-port-out/letter/representative-7/release-report.json
/tmp/myembro-quality-gate/quality-gate check --matrix /tmp/myembro-port-out/letter/representative-7/release-matrix.json --json /tmp/myembro-port-out/letter/representative-7/quality-report.json
```

`check-release`: **BLOCK — previous stage gate/release/user sewout confirmation pending**.
S1 승인 대기이며 게이트 도구·단계 상태·잠금 기준은 바꾸지 않았다.
별도 기능 진단은 기존 게이트의 5개 조건 **7/7 PASS**. 릴리스 통과나 독립 최종 승인으로
대체하지 않는다. 다음 단계는 총괄의 S1 검토 결과에 따른다.

### S2 앱 경로 이름 7건 재현

AWT 대체 경로가 아니라 Android `automaticLetteringState` → `renderLettering` →
`TextObject` → `PesWriter`를 호출하는 opt-in instrumentation이다. 4입력 UI와 같은 설정
(간격0, 가운데, 곡선off, 40wt, 원본 새틴 파라미터 기본값)을 쓴다.
테스트 APK만 기존 OFL Dancing Script fixture를 포함한다.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.lettering.LetteringSewtestInstrumentation
adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5560 shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.lettering.LetteringSewtestInstrumentation
mkdir -p /tmp/myembro-sewtest/s2
adb -s emulator-5560 exec-out run-as io.github.linker2k.myembro tar -C files/sewtest-s2 -cf - . | tar -xf - -C /tmp/myembro-sewtest/s2
# 각 파일에 실행. 별도 후프 크기나 게이트 기준 override 없음.
/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-sewtest/s2/jung-min-12mm.pes --text --style running --json /tmp/myembro-sewtest/s2/jung-min-12mm-quality.json
```

현재 출력은 승인 범위가 없는 TTF에 대한 기존 자동 러닝 기준선이다. 결과 표 `results.md`,
각 파일 게이트 JSON/log, `manifest.json`, 독립 PES 파서 왕복 좌표와 `overview.png`는
`/tmp/myembro-sewtest/s2/`에 둔다. 7건 중 6 PASS, Jung Min 30mm만 폭133.8mm로
SE400 100mm 후프 초과 BLOCK. 앱 렌더러도 동일하게 outsideHoop=true를 반환한다.

총괄이 이후 채택한 0.9–6.0mm 폭 정책은 `/tmp/myembro-quality-gate/auto-rules.md`의
‘총괄 채택’ 절이 출처다. 이 출력은 그 정책을 적용한 새틴 결과가 아니다. TTF는 레일이 없고
기존 자체 OutlineColumns 중축 추출기는 기본 경로 금지 상태이므로, 검증된 기둥 변환 없이
FontQuality의 추정 폭만으로 새틴 승인이나 슬라이더 상한을 만들지 않는다.

### S2+S3: 사람이 디지타이즈한 글꼴의 새틴 경로

TTF는 러닝(현재)·S4 이후 채우기만 제공한다. 새틴은 Ink/Stitch 자수 글꼴 SVG의
레일·렁만 사용한다. `inkstitch/lettering/InkStitchFont.kt`는 canvas `a8acced`의 커밋된 파서를
재사용한다. `InkStitchLettering.render`는 모든 새틴 속성을 `SatinParametersCodec`으로 읽고
각 `SatinColumn.toStitchGroups(previousStitch)` 결과를 S1 `fromGroups`에 한 번만 전달한다.
레이어를 native 좌표로 평탄화했다가 재잠금하지 않는다. 러닝 연결도 ties/trim/stop/최소 땀·점프
속성을 보존한다. 채우기·반복 러닝·bean은 이 경로에서 지원하지 않으며 조용히 바꾸지 않고 거절한다.
원본 SVG의 `True`/`False` 때문에 codec이 실패한 문제도 원본 `get_boolean_param` 규칙으로 수정했다.

이번 3건은 **로컬 시험용 Aventurina** 필기체다. 데이터는 공식 v3.3.0 배포물의
`fonts/src/aventurina/{ltr.svg.xz,font.json.xz,LICENSE}`를 읽었으며 원본 코드·공식 바이너리를 실행하지 않았다.
라이선스 파일은 CC-BY-SA 4.0, 어댑터 Sandrine Ratkoff Rojnoff, 원본 작가 HalfAsleepArtist의
사용 허락 기록과 underlying font 추가 제약 가능성을 명시한다. 따라서 **폰트 데이터는 저장소·앱에
번들하지 않고** `/tmp/myembro-port-out/letter/jungmin/inputs/`에만 둔다. 카탈로그 공개 승인과 다르다.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:inkFontNames
python3 tools/check-inkfont-names.py
```

`inputs/request.json`에 font id·텍스트·높이·배치 SVG·입력 SHA256을 기록한다. SVG는 원본 글리프의
곡선·속성·문서 순서를 보존하고 배치 transform만 적용한다. 반복 글리프의 ID는 충돌 방지를 위해
접두사를 붙인다. 이 fixture의 root viewport는 identity이며 원본 glyph가 root 직속인지 검증한다.
레이아웃은 포트가 만든 것으로, 공식 batch lettering 배치와 동등하다고 주장하지 않는다.

`tools/check-inkfont-names.py`는 3건만 기존 `quality-gate check --text --style satin`으로 검사하고,
공식 `/tmp/myembro-inkstitch-oracle/out/letter-jungmin/<id>.pes`가 있으면 땀 수·형상만 자동 비교한다.
기존 oracle 기준의 땀 수 5%·대칭 Hausdorff 0.2mm를 읽고 bbox 중심 평행이동만 허용한다.
기준 파일이 없으면 **미판정**으로 기록하고 성공 종료하지 않는다. 출처·파일 해시는 validation.json에 남긴다.

현재 세 건 모두 **BLOCK: 시작 잠금 없음**. 원본 J의 첫 러닝 요소가 `ties=3`으로 잠금을 끈 상태다.
이를 임의로 덮어쓰지 않았으며 게이트③도 변경하지 않았다. 20/30mm는 각각 폭124.6/186.8mm로
SE400 후프도 초과한다. 기준 H 높이로 환산한 글꼴 기록 범위는 약11.09–27.73mm이므로
30mm는 UI 허용 샘플이 아니라 범위 밖 진단 샘플이다. 공식 비교는 총괄이 지정한 codex 출력 대기다.
