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


## FillStitch 포트 — embro-model

### 범위와 호출 경계

v3.3.0에서 이전 `auto_fill.py`는 **`lib/stitches/tatami_fill.py`**로 이름이 바뀌었다.
`stitches.TatamiFill` / `Fill` / `FillRouting`이 이 타타미 경로를 담당한다.
기존 `digitizing.TatamiFill` 및 `lettering.OutlineFill` 호출은 바꾸지 않는다.
새 `elements.FillAdapters`는 승인 후 선택적으로 전환하기 위한 어댑터다.

- 모든 JTS 입력과 `FillPoint`는 **SVG px**. 각 저수준 함수의 `*Px` 인수도 px다.
  `FillParameters`와 방법별 DTO의 `_mm` 속성은 mm, `angle`은 도다.
  `TatamiFill.fill` / `Fill.intersectRegionWithGrating`의 저수준 `angle`은 원본처럼 라디안이다.
- `FillGeometry.makeValid`는 JTS GeometryFixer로 자기교차를 수정하고 다각형 성분을 보존한다.
  `buffer(0)`만 사용해 나비넥타이의 한쪽 면을 버리지 않는다. 구멍은 분리된 외곽선으로 유지한다.
- `FillParameters.fromSvgAttributes`는 공통 `inkstitch:*` 이름/기본값을 읽는다.
  방법별 가이드·그라디언트·타탄 리소스는 변환/색 해석이 끝난 typed 입력을 받는다.
  완전한 SVG 문서/Inkex 객체 트리 파서는 이 채움 엔진의 범위에 포함하지 않는다.
- `elements.FillStitch.toStitchGroups(previousStitch)`는 underlay→top, 색 순서,
  시작/끝, 그룹 속성 및 잠금 정의를 공통 계약으로 넘긴다. 실제 필터/명령 생성은
  embro-worker의 명시적 `StitchPlanBuilder.fromGroups` 호출 책임이다.
  기본 writer나 가져온 기존 도안을 재생성하는 hook은 없다.

### 원본 기본값

| 속성 | 기본값/원본 처리 |
|---|---|
| `fill_method` | `tatami_fill` (`auto_fill`은 호환 별칭) |
| `row_spacing_mm` / `max_stitch_length_mm` | 0.25 / 4.0, 계산 시 각각 최소 0.1mm |
| `angle` / `staggers` / `skip_last` / `expand_mm` | 0° / 4 / false / 0mm |
| `underpath` / `gap_fill_rows` | true / 0 |
| `running_stitch_length_mm` / `running_stitch_tolerance_mm` | 2.5 / 0.2; 원본 getter의 0.2를 사용(UI 표시 0.1과 다름) |
| `fill_underlay` / `underlay_underpath` | true / true |
| `fill_underlay_angle` | 미지정이면 top angle+90°; gradient는 gradient 방향의 음수 |
| `fill_underlay_row_spacing_mm` | 미지정/0이면 top 간격×3 |
| `fill_underlay_max_stitch_length_mm` | 미지정/0이면 top 최대 길이 |
| `fill_underlay_inset_mm` / `fill_underlay_skip_last` | 0 / false |
| `pull_compensation_mm` / `pull_compensation_percent` | 양쪽 0; 한 값은 양쪽에 적용 |
| `min_stitch_length_mm` / `min_jump_stitch_length_mm` | null; 공통 후처리 기본값 0.1mm / 3mm |
| `ties` / `lock_start` / `lock_end` | 0(양끝) / `half_stitch` / `half_stitch` |

### 원본 파일과 테스트 대응

| 기능 | 원본 파일 | Kotlin 검사 |
|---|---|---|
| grating·stagger·legacy | `lib/stitches/fill.py` | 원점 기준 행, 음수 modulo/round-even, 끝 간격 변화, 구멍, flip/reverse, raw 시작 중복 태그 |
| tatami·Euler·underpath·start/end | `lib/stitches/tatami_fill.py` | 모든 행 방문, 결정성, 구멍 우회, 분리 영역 jump, 보정, 경계 절단·중복 간선 방향 회귀 |
| running·smooth·clamp | `lib/stitches/running_stitch.py`, `lib/utils/{smoothing,clamp_path}.py` | 코너/길이/허용오차, U형 외부 excursion, 구멍 모서리, 끝점 보존 |
| underlay·요소 파라미터 | `lib/elements/fill_stitch.py` | 여러 각도, inset, spacing/max 상속, collapse fallback, angle tokenizer, top 전 expand 순서 |
| contour | `lib/stitches/contour_fill.py` | inner/single/double, 방향, 구멍, 분기, bean/repeats, smoothing |
| guided | `lib/stitches/guided_fill.py`, `lib/stitches/utils/connect_geometries.py` | copy/offset/buffer, 최소 면적 회전 상자, anchor, flexible/stagger, 구멍, smoothing, fallback |
| circular | `lib/stitches/circular_fill.py` | Fermat, 작은 도형 원본 fallback, 구멍, target/end spacing, start/end, bean/repeats |
| linear gradient | `lib/stitches/linear_gradient_fill.py` | 제곱근 행 분포, color stop, missing gradient, 구멍, 시작/underlay 연결 길이 |
| tartan | `lib/stitches/tartan_fill.py`, `lib/tartan/utils.py` | warp/weft, 색 순서, 폭/scale/rotation, running stripes, herringbone, 나비넥타이 연결 길이 |
| meander | `lib/stitches/meander_fill.py`, `lib/tiles.py`, `lib/utils/prng.py` | 75개 타일 로드, SVG 변환, MST/분기, 연결성, seed, BLAKE2s 알려진 벡터, clip/smooth/zigzag/반복 |

`tests/test_threads_color.py`의 gradient/tartan 입력·색 기대값은 대응 Kotlin 테스트로 옮겼다.
그 외 원본 `tests/`에는 이들 채움의 좌표 골든 단위 테스트가 없다. 수식·분기에서 읽은
고정 기대값과 성질 검사는 원본 실행 결과와 대조한 골든이 아니다.
원본 코드는 읽기만 했으며 실행/의존성 설치를 하지 않았다.

### Fill 차이점

| 위치 | 원본과 차이 | 이유/한계 |
|---|---|---|
| Shapely/GEOS | JTS GeometryFixer, buffer/overlay/offset 사용 | 곡선 근사·작은 topology 조각·순서가 GEOS와 수치적으로 다를 수 있음. 동일 바이트 출력이라고 주장하지 않음 |
| NetworkX 그래프 | Kotlin multigraph/BFS/Dijkstra/Hierholzer + JGraphT 최소 가중 perfect matching | LGPL-2.1-or-later 선택. 동률 matching/이웃 순서가 NetworkX 버전과 다를 수 있음 |
| 입력/자원 제한 | 유한 수 검증, 최대 행·정점·땀·반복·matching 작업량 상한 | 순수 JVM에서 무제한 메모리/CPU 사용 방지. 큰 입력은 명시적 예외로 거절 |
| 그래프 정밀도 | 정점을 1e-6px로 정규화, clamp buffer도 1e-6px | 경계 leg 삭제 및 긴 연결땀 방지. 원본 snap/GEOS 정밀도와 미세 차이 |
| 시작/끝 삽입 | 행이 닿지 않는 독립 링에 가상 채움 간선을 만들지 않음 | 구멍을 가로지르는 인위적 연결 방지 |
| 도달 불가 travel | 직선 fallback 대신 오류 | 구멍/외부를 무표시 봉제하는 결과를 내지 않음 |
| travel clamping | 외부 excursion 전체를 가장 짧은 해당 경계 arc로 연결, 양 끝 보존 | 원본 0.01px 경계 buffer 절단 대신 정확 투영. hole-only 교차 및 말단 점 교차 손실 수정 |
| collinear running sleeve | 거의 0인 분모에서 이전 vertex 사용 | 0 허용오차·거의 평행인 선의 무한/과대 좌표 방지 |
| 분리 도형/명시 jump | 이전 그룹의 강제 잠금으로 공통 planner에 needle-up 경계 요청 | 3mm collapse 기준보다 가까운 분리 영역도 가로질러 봉제하지 않도록 함 |
| 임의 길이 땀 | `enable_random_stitch_length=true`는 명시적 미지원 | deterministic fill과 meander seeded 경로는 구현. 랜덤 running-length 분포는 후속 포트 필요 |
| Guided 최소 회전 상자 | JTS MinimumAreaRectangle 사용, 임의 좌표 perturbation 생략 | 재현 가능한 좌표 유지; 원본 GEOS 회피용 0~0.01px jitter와 다름 |
| Guided self revisit | 선형 project 한 번 대신 traversal segment별 clipping | 같은 점 재방문 경로 누락 방지 |
| staggers=0 | guided의 0 나누기 대신 1로 처리 | 유효 출력/명시 검증을 위한 처리 |
| Tartan/gradient 입력 | typed palette/gradient, 해석된 CSS 색/geometry | SVG 리소스/CSS/JSON 텍스트 해석은 상위 importer 책임 |
| Tartan herringbone | 한 줄짜리 block을 보존 | 원본의 loop 변수 누출로 단일 행이 사라지는 경우 수정 |
| Circular 작은 도형 | spacing이 반경보다 크면 원본처럼 clipping 없는 원 반환 | 원본 동작 보존. 경계 초과 가능성이 있어 게이트/후프 검사가 필요 |
| 어댑터 | native 0.1mm 또는 mm→px 명시 변환. 비균등 row offset/AUTO satin/border는 거절 | 채움의 의미를 조용히 바꾸지 않음. 기존 edge-running underlay를 선택적으로 옮기면 새 tatami underlay와 동작이 다름 |

### 채움 품질 제출 방법/현재 판정

고정 SVG는 `engine/src/test/resources/inkstitch/fill/{rectangle,hole,concave,bowtie}.svg`다.
공개 자체 fixture만 사용하며 사용자 PES·폰트 자료는 커밋하지 않는다.
`exportFillQualityFixtures`는 14가지 모드×4도형×12/24mm = **112개**를 선언하고,
실제 element→공통 후처리→PES v6로 생성한다. 실패한 생성도 matrix에서 빼지 않는다.
legacy gate schema의 `fonts` 축에는 `방법:도형` ID를 넣는다. 폰트 검증으로 주장하지 않는다.

```sh
# Normal committed engine (independent core):
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test
# Explicit integration candidate; provide the shared planner source root until it is merged:
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew \
  -I tools/inkstitch-fill-preview.gradle \
  -PfillPlannerSourceRoot=/path/to/worker/engine/src/main/kotlin \
  :engine:test :engine:exportFillQualityFixtures
/tmp/myembro-quality-gate/check-release --matrix /tmp/myembro-fill-quality/matrix.json \
  --json /tmp/myembro-fill-quality/release-report.json
```

첫 외부 게이트 판정은 **BLOCK 112/112**였다. 원본 기본 half_stitch 잠금은 게이트의
고정 text 시작/잠금 시퀀스와 맞지 않으며, 일부 도형에서 짧은 땀/밀도 기준도 초과한다.
초기 후보의 생성 실패(odd vertex matching 상한)와 긴 연결땀은 원인을 수정하여 재검증한다.
게이트/기준/원본 기본값을 바꿔 통과시키지 않는다. 최종 결과는 후속 검증 기록에 추가한다.
현재 **기존 fill 교체·main 병합 승인 없음**. JVM 단위 테스트 통과와 독립 품질 승인은 별개다.


### 2026-10-24 채움 후보 2차 검증

- 공통 후처리 3개 파일(`LockStitches`, `StitchPlanBuilder`, `PathEndPoints`)은 아직
  main 미병합이다. `/tmp/myembro-fill-preview.gradle`로 embro-worker의 읽기 전용 WIP를
  compile 입력에 더한 **임시 조합**에서 엔진 테스트 **391개 통과**.
  정식 `:engine:test` 통과와 구분한다. 이 임시 경로/worker WIP는 커밋하지 않는다.
- 원본 기본값으로 **112/112 PES 생성·PesReader 재해석 성공**, 생성 오류 0.
  독립 `check-release`는 **BLOCK 112/112, exit 1**.
- 게이트 사유: text 시작 시퀀스 112, 시작 잠금 112, 끝 잠금 72,
  body<0.3mm 비율 38, body<0.27mm 비율 33, 전체 짧은 땀 비율 11,
  밀도 5, 미설명 zero 1. 여러 사유가 한 파일에 함께 있을 수 있다.
- **LONG_STITCH 0, 읽기 실패 0**. 최대 봉제 이동 약 4.102mm.
  버그 수정으로 긴 연결땀/누락 파일은 없어졌지만 전체 품질 통과가 아니다.
- 결과: `/tmp/myembro-fill-quality/{matrix.json,release-report.json,gate.log}`.
  전체 생성 연결 API/추가 통합 회귀는 공통 후처리 병합 후 별도 커밋한다.
  우선 공통 후처리에 독립적인 채움 코어를 기능별 커밋으로 제공한다.


### 커밋된 코어와 통합 후보의 구분

- `99087da`: tatami/legacy 행·연결 그래프·언더레이·공통 파라미터.
- `d373677`: contour/guided/meander 및 GPL 타일 데이터.
- `629d162`: circular/linear gradient/tartan.
- 위 3커밋은 깨끗한 별도 체크아웃에서 **일반 `./gradlew :engine:test` 381개 통과**.
  공통 planner WIP나 임시 제외 필터를 쓰지 않았다.
- `FillStitch`/`FillAdapters`와 element/PES 통합 테스트는
  `engine/src/inkstitchPreview{,Test}/kotlin/`에 **통합 후보**로 보관한다.
  아직 없는 공통 후처리 API를 참조하므로 일반 엔진 빌드/배포 소스에는 포함하지 않는다.
  저수준 fill API와 `FillParameters`/`FillUnderlay`는 일반 엔진에서 바로 사용할 수 있다.
- 후보 검증은 저장소의 `tools/inkstitch-fill-preview.gradle`에 **명시적으로** source root를
  주입해 실행한다. 이 스크립트는 worker 파일을 복사/수정하지 않으며 main에 공통 3파일이
  있으면 외부 source root가 필요 없다. 고정 개발자 PC 경로를 제품 코드에 넣지 않는다.
- 공통 planner 병합·독립 품질 승인 후 후보를 정상 source set으로 옮긴다.
  이 후보 보관/381개 통과를 공개 element 통합이나 봉제 품질 승인 완료로 해석하지 않는다.


### 랜덤 running helper (후속 연결용)

`stitches.FillRandomRunning`은 원본 `running_stitch.py`의
`random_running_stitch` / `stitch_curve_randomly`와 `utils/prng.py`를 별도로 포팅한다.
스칼라·목록 길이, curve별 seed, BLAKE2s counter, 첫 땀의 무작위 phase,
허용오차에 의해 짧아진 뒤 phase 보정을 유지한다. 좌표/길이 인수는 px이며
`stitchLengthSigma`는 10%=0.1의 비율이다. 알려진 해시/직선 수식·코너·목록 순환·seed·끝점
테스트 12개를 추가했다. 원본을 실행해 얻은 좌표 골든이라고 주장하지 않는다.

이 helper는 **개별 fill의 랜덤 옵션에 아직 연결하지 않았다**. 위 표의
`enable_random_stitch_length=true` 명시적 거절은 유지한다. 랜덤 분포만 구현한 것을
randomized fill 전체/게이트 통과로 확대하지 않는다. 유한 수·진행성·자원 상한은 추가 검증한다.

최종 코어 검증: 일반 `JAVA_HOME=… ./gradlew :engine:test` **393개 통과**.
통합 후보는 별도 preview 실행에서 **391개 통과·112/112 PES 생성**했고,
최종 외부 판정은 여전히 **BLOCK 112/112**다. 저장한 report의 PES SHA-256가 현재
112개 후보 파일과 모두 일치하는 것도 확인했다(`docs/inkstitch-fill-gate.json`).


## 단계 적용 전환 — S4 공식 fill 매트릭스

`docs/ui-stories.md`의 S1→S2→S3→S4 순서를 따른다. FillStitch는 preview를 유지하고
US-6/기존 fill 교체를 시작하지 않는다. 앞의 BLOCK 112/112는 초기 PE-Design 패턴 기반
게이트의 **역사적 기록**이다. 총괄이 공식 Ink/Stitch 출력에도 과도한 차단이 발생한다고
전달하여 Codex가 기능 기준으로 재보정 중이다. 그 잠금 시퀀스를 맞추기 위해 원본 동작을 바꾸지 않는다.

고정 공식 `fill_stitch` SVG 225건의 재현 CLI·매핑·코드/외부 S1 소스 provenance와
수치/차단 원인은 [fill-matrix-reproduction.md](fill-matrix-reproduction.md)에 기록한다.
`3412ebb`의 두 실행에서 225/225 PES 생성·바이트 일치, 일반 엔진394/preview410 테스트 통과.
원본 NetworkX graph copy의 node→neighbor→key 삽입 순서를 맞춘 뒤 count/Hausdorff/순서가
개선됐지만 전체 동등성 판정은 아직 없다. 외부 S1 WIP는 hash로 고정한 별도 입력이며
model 커밋에 포함된 것처럼 표시하지 않는다. 독립 gate/criteria는 수정하지 않았다.


## 개정 S4 범위 — 실패 없는 디자인 (2026-10-24)

총괄의 개정 `docs/ui-stories.md`(main 작업트리)를 읽었다. 이전의 전체 기능/UI 목록보다
이 지시를 우선한다. S4 기본 제품 경로는 **타타미 + 기본 바탕 땀**만 사용한다.
원본 기본값 전용 진입점은 geometry/thread를 받고 사용자가 조절하는 파라미터를 받지 않는다.

- 각도 0°, row_spacing 0.25mm, max_stitch_length 4mm, staggers 4.
- fill_underlay=true; 기본 각도 top+90°=90°, row_spacing=top×3=0.75mm,
  max_stitch_length는 top의 4mm 상속, inset 0mm, underlay_underpath=true.
- 잠금/최소 길이/점프는 S1의 원본 기본 후처리를 사용한다.
- 새틴 폭 초과 영역만 S2의 검증된 폭 판정 결과를 받아 자동 채운다. 기존 OutlineFill.AUTO,
  자체 column 추출, height 기반 밀도 변경, 임의 폭 상수를 기본 경로에 재사용하지 않는다.
  폭 한계는 Codex가 보정하고 S2가 사용하는 값이 확정되기 전 새로 정하지 않는다.
- 기존 포트의 분리 영역 강제 잠금 같은 자체 안전 분기도 기본 경로의 원본 동작과 대조해
  분리한다. PE-Design의 잠금 바이트/통계에 맞추려고 원본 땀을 변형하지 않는다.
- contour/guided/meander/circular/gradient/tartan 및 random/bean helper는 엔진 코드가
  있더라도 미노출·다음 버전이다. 상세 파라미터 UI, 잠금 선택, 봉제 설정, 객체 땀 고급 탭,
  채우기 시트·각도 핸들은 만들지 않는다.
- S1→S2→S3 검토 뒤 S4 차례에 기존 경로를 전환한다. 지금은 preview 준비/검증 단계다.

기존 공식 225건은 여러 프로필의 **회귀 기준선**이다. 제품 기본 조합만의 검증으로 확대하지
않는다. 실제 기본 조합(angle0,row0.25,underlay=true)을 고정한 별도 oracle이 필요하다.
기존 매트릭스/기준은 수정하지 않고 Codex와 추가 기본값 묶음을 확인한다.

엔진 준비 예상 **4–6시간**:

| 작업 | 예상 |
|---|---:|
| 기본값 전용 API·기존 휴리스틱/변형 경로 분리 | 약 1시간 |
| S2의 검증된 폭 초과 영역을 받는 자동 전환·경계 계약 | 1–2시간 |
| 기본 조합 회귀·PES·공식 출력 비교/차이 확인 | 2–3시간 |

Codex의 폭 기준/공식 기준 출력 확정, 단계별 병합 승인, 실제 봉제 검토 대기 시간은 별도다.
이번 지시 반영은 범위/일정 기록이며 UI 노출·기존 채움 교체를 시작한 것이 아니다.


## 최신 검증·작업 정책 — 소단위 검토 후 대기 (2026-10-24)

이 정책은 앞의 대규모 매트릭스/엄격한 통계 동등성 절차보다 우선한다.

- 실제 봉제 실패에 해당하는 차단만 유지한다: 0.5mm 셀 바늘 밀집, 같은 점 연속 바늘,
  실 시작·끝 잠금 존재(모양 불문), 최대 땀 길이 초과, 파일 유효성과 후프 범위.
- PE-Design 시작 바이트 패턴, S0/짧은 땀 비율, 새틴 간격 통계, 길이 분포는 참고 지표다.
  Ink/Stitch 기본 최소 길이 후처리를 바꾸거나 통계에 맞추려고 땀을 변형하지 않는다.
- 원본 비교는 기능별 대표 사례 **10개 이내**, 땀 수·모양 중심으로 한다.
  기존 112/225건 결과와 스크립트는 역사적 기록이며 대형 매트릭스 재실행을 중단한다.
  과거 Hausdorff/순서/분포 수치를 새 릴리스의 필수 차단으로 사용하지 않는다.
- 최종 판정은 사용자 실제 봉제다. 단위 테스트/자동 게이트를 실제 봉제 확인으로 주장하지 않는다.
- 한 작업은 **30–60분 이내**로 끊는다. 끝나면 커밋·push하고
  '한 것 / 결과 / 다음 할 일 제안 / 결정 필요 사항' 3–5줄을 보고한 뒤 총괄 지시를 기다린다.
  이후 단위를 자동으로 시작하지 않는다.

현재 실행 중인 model 검증은 없다. 이번 마무리는 정책 기록만 변경하며 엔진/UI에는 손대지 않는다.
다음 후보 단위는 기본값 전용 타타미+바탕 땀 API 준비다. 착수는 총괄의 다음 결정에 따른다.


### 20분 단위: 기본 타타미 API 준비

`elements.DefaultTatami.stitchGroups(region, thread)`를 **preview**에 추가했다.
입력 PolygonRegion은 기존 엔진 단위(0.1mm), 반환 StitchGroup 좌표는 공통 SVG px다.
사용자 설정 인수·폭 판정·UI·기존 경로 연결은 없다. 호출자가 객체 그룹을 모은 뒤 S1을 한 번 적용한다.

letter의 `stitches.InkAutoFill`을 확인하고 **그 생성기를 그대로 호출**한다. 새 scanline/graph
알고리즘은 추가하지 않았고 model의 별도 TatamiFill/FillRouting도 이 기본 진입점에서 호출하지 않는다.
letter 파일의 기존 Settings 기본값(0.4mm/3.5mm)을 그대로 쓰는 대신 원본 fill 기본값인
0°/0.25mm/4mm/4 stagger/underpath=true를 명시한다. 바탕 땀은90°/0.75mm/4mm다.
각 성분마다 바탕 땀→본 채움 순서이며 S1 기본 잠금 정의를 붙인다.

검증은 **대표 3개**만 실행했다: 사각형(기본 간격·단위·잠금), 구멍(PES 읽기·내부 바늘 없음),
분리 성분/빈 입력(레이어 순서·빈 결과). `DefaultTatamiTest` 3개 통과. 전체 매트릭스는 실행하지 않았다.
letter의 커밋 `b7106ec`에서 InkAutoFill/InkRunning을 읽기 전용으로 추출해 컴파일했다
(InkAutoFill 도입 커밋 `1cbdd569747032a48075ca16e732629b15555d6c`).
당시 preview에는 `fillTatamiSourceRoot`가 필요했으나, 아래 소유권 이전으로 제거했다.

이 API 준비는 letter backend의 공식 동등성/실제 봉제 통과나 S4 적용을 뜻하지 않는다.
특히 현재 backend는 시작점 지정 인수가 없어, 바탕 땀 마지막점을 top 경로 생성에 전달하는
계약 보강은 별도 단위로 검토할 수 있다. 이번 단위는 여기서 멈추고 총괄 결정을 기다린다.


### 15분 단위: InkAutoFill model 소유권 이전

`stitches/InkAutoFill.kt`를 model의 일반 `src/main`으로 가져왔다. letter의
커밋 `b7106ec361eb5a75eaac54b0cba450cf1041a554`에 저장된 파일을 사용했으며,
원래 도입 커밋은 `1cbdd569747032a48075ca16e732629b15555d6c`다.
직접 의존하는 공용 러닝 유틸 `InkRunning.kt`도 같은 경로에 포함했다
(도입: `c8d2f4f0b66376f3d0a70f313ba3717b4559dcdd`). 각 파일 머리에 이전 출처와
원본 Ink/Stitch 경로·고정 커밋·GPL 표기를 남겼다. 알고리즘/기본값은 변경하지 않았다.
앞으로 InkAutoFill 수정은 model 담당이며, letter S2는 해당 생성기를 중복 추가하지 않는다.
InkRunning은 공유 의존 유틸로 동일 FQCN/파일을 사용한다.

기존 letter의 `AutoFillPortTest`도 출처를 기록하고 옮겼다. 일반 `:engine:test`는
letter 작업트리나 외부 소스 없이 컴파일·실행한다. `DefaultTatami`는 여전히 preview로,
S1 미병합 소스 입력만 필요하다. `fillTatamiSourceRoot` 옵션과 외부 letter 입력은 제거했다.
UI/기존 제품 경로 연결 및 시작점 계약 변경은 이번 단위에 포함하지 않는다.

재현 명령(JDK 17):

```sh
./gradlew :engine:test --console=plain
./gradlew -I tools/inkstitch-fill-preview.gradle \
  -PfillPlannerSourceRoot=/tmp/myembro-port-out/model/dependencies/source \
  :engine:test --tests '*DefaultTatamiTest*' --console=plain
```

검증 결과: 외부 소스를 지정하지 않은 일반 엔진 테스트 **395개 통과**, S1 소스만 지정한
preview `DefaultTatamiTest` **3개 통과**. 두 생성/유틸 파일은 출처 주석을 제외하고
letter의 기록된 커밋과 byte-for-byte 동일함도 확인했다. 전체 매트릭스는 실행하지 않았다.
