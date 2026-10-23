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


### 15분 단위: S4 기본 채움 시험 후보 4개

`exportDefaultTatamiSewtest` 태스크가 기존 DefaultTatami + S1 기본 planner를 호출해
`/tmp/myembro-sewtest/s4/`에 PES v6/SVG/manifest를 생성한다. 사각 20×20mm(739땀),
원 지름25mm(1,027땀), 도넛 외경30/내경12mm(1,490땀), 직접 만든 굵은 H 높이20mm(616땀).
생성 중 우리 PesReader 왕복 땀수·100mm 후프 범위·도넛 안쪽 바늘 없음도 확인했다.
디자인은 모두 오리지널 기하 도형(CC0), 개인 파일/폰트 미사용이다.

외부 `/tmp/myembro-quality-gate/quality-gate check` v5 결과 **4/4 PASS**.
최대 밀집은6회/0.5mm 셀, 동일점 연속 최대2회, 최대 땀4.11mm 미만,
실 시작/끝 잠금 및 후프100×100 검사가 통과했다. 짧은 땀 비율 경고는 참고만 한다.
S4 단계 승인은 요청하지 않았으며, 현재 단계 진행/출시/실봉제 승인을 뜻하지 않는다.
`tools/render_tatami_sewtest.py`는 pyembroidery로 읽은 실제 PES를 동일 배율의
`overview.png`/개별 PNG로 렌더링한다. 결과 디렉터리 README에 정확한 재현 명령을 기록했다.
알고리즘·UI·기존 제품 경로 변경은 없다. 여기서 멈추고 총괄 검토를 기다린다.
## 잠금·플랜·명령 포트

`LockStitchParameters`의 `ties`는 0=양쪽(기본), 1=시작, 2=끝, 3=없음이다.
`force_lock_stitches`와 전체 `disable_ties` 우선순위를 원본대로 적용한다.
`InkLockStitch`는 **half_stitch, arrow, back_forth, bowtie, cross, star, simple,
triangle, zigzag, custom**을 지원한다. 절대/상대 스케일, custom 숫자 패턴 및 SVG
endpoint 경로, END 반전, 마지막 두 anchor/direction 노드 제외 규칙을 보존한다.
원본 half_stitch는 최대 15점에서 0.5mm보다 먼 점을 찾고 1.5mm로 제한한다.
찾지 못하면 **0.5px**로 fallback한다(mm가 아니다). back_forth의 음수 연장 분기에
잠금 태그가 빠지는 원본 동작도 포트에서 임의 수정하지 않는다.

`StitchPlanBuilder.fromGroups`는 원본 `stitch_groups_to_stitch_plan`/`filter_duplicate_stitches`를
명시적으로 실행한다. 기본 collapse=3mm, 최소 길이=0.1mm, RGB 블록 결합,
이전 그룹의 최소 점프/force, tie-off→색변경→tie-in, TRIM→STOP 순서를 유지한다.
최소 길이 이하(<=) 점은 이전 보존 점이 JUMP이거나 현재 점이 잠금/제어명령이면 보존한다.
단순한 모든 0길이 점 삭제와 다르다. 최종 정수 격자 중복 제거는 별도 generated 경계에서 한다.

`ElementCommands`는 SVG 커넥터가 해석된 후의 ignore_object/layer, trim_after,
stop_after, starting_point/ending_point와 이전 fill_start/fill_end 별칭을 받는다.
ignore는 생성 자체를 생략하고, trim/stop은 요소의 마지막 비어 있지 않은 그룹에 OR한다.
시작·끝 점은 요소별 라우팅 담당이 사용한다. 이 파사드는 임의 좌표 이동을 만들지 않는다.

| 위치 | 원본과 차이 | 이유/한계 |
|---|---|---|
| Lock 직접 호출 | 2점 미만은 빈 결과 | 원본 StitchGroup 보호 조건을 재사용 가능한 API에서도 적용 |
| SVG lock parser | Inkex 대신 endpoint 전용 Kotlin 파서 | M/L/H/V/C/S/Q/T/A/Z endpoint만 필요; 곡선 control point를 바늘 위치로 사용하지 않음 |
| 유효성 검사 | 비유한 스케일/좌표는 조기에 거부 | 잘못된 파일·무한 확장 방지 |
| lock 타입 표시 순서 | END UI의 cross/bowtie 순서 차이는 단일 ID 목록으로 표현 | ID와 생성 좌표는 동일 |
| 빈 그룹 | 전체 빈 입력은 빈 플랜, 앞뒤 빈 그룹 제외 | UI 종료/빈 블록 인덱스 오류 대신 엔진 반환값 제공, 마지막 실제 그룹 tie-off 보존 |
| 그룹 minimum | per-stitch 값이 있을 때 그룹 값으로 덮어쓰지 않음 | 공유 계약의 세밀한 override 보존; Stroke 파사드는 원본 요소처럼 모든 점에 요소 값을 지정 |
| END | 원본 모델에 없는 END도 필터에서 보존 | 기존 machine API의 종료 명령 보장 |
| 명령 탐색 | Inkex XML·clone·커넥터 탐색 대신 resolved flags/points | SVG 가져오기 담당과의 명시적 통합 경계; 레이어 무시 판정 자체는 호출자 책임 |
| terminal command | 마지막 비어 있지 않은 그룹에 적용 | trailing empty 그룹에서 trim/stop이 사라지는 경우 방지 |

## Stroke·Ripple 포트

`StrokeParameters` / `RippleParameters.fromAttributes`는 `inkstitch:*`, XML 확장 이름,
로컬 속성 이름을 받고 공개 mm/percent 값을 유지한다. `Stroke`는 변환·클립 완료된 SVG px
폴리라인을 받는다. manual은 원래 노드 endpoint, running/zigzag는 평탄화한 곡선을 전달해야 한다.
원본 Inkex DOM 로딩·transform·curve flattening이 자동으로 포함됐다고 주장하지 않는다.

Running은 sleeve/angle interval·원 교차·45도 코너·even/fixed carry, 길이 목록, tolerance,
반복, bean 및 결정적 random length를 포함한다. Zigzag는 Python 짝수 반올림, 짝수 샘플,
평균 접선, 비대칭 pull compensation, 각도·폐곡선·반복 규칙을 따른다. Manual은 최대 길이로
분할하고 force가 없으면 잠금을 만들지 않는다. `Prng`는 공통 lettering 포트와 같은 API/코드로,
BLAKE2s counter stream의 hex-word 순서와 UTF-8 seed를 재현한다(신규 의존성 없음).

Ripple은 target/convex hull centroid, closed spiral, line/satin guide, 수동 배치,
줄 수/최소 간격·parity, skip/stagger/exponent, 축별 scale·회전·rungs, grid/grid-first,
반복·bean과 클립 다중 그래프 라우팅을 포함한다. 원본 getter 기본값을 사용한다:
`staggers=1.0`, `satin_guide_pattern_position=line_count`(UI default와 차이 있음).
클립은 JTS overlay, 경계 그래프, Euler 보정·경계 travel·연결 성분별 그룹으로 처리한다.
멀티패스/satin guide는 `RippleSatinGeometry`의 실제 SatinColumn rail/rung 샘플러가 필요하다.
이 공급자 없이 임의로 두 경로를 rail로 추측하지 않는다.

| 위치 | 원본과 차이 | 이유/한계 |
|---|---|---|
| Stroke 입력 | Inkex node 대신 transformed/clipped 폴리라인과 resolved commands | SVG 요소 파서/클리핑과 생성 알고리즘의 통합 경계 |
| empty running | None 대신 빈 List | Kotlin collection API |
| 잘못된 숫자 목록 | 빈/파싱 불가 목록 기본값 또는 유효성 오류 | NaN 전파·인덱스 오류 방지; 유효 입력의 기본값은 보존 |
| 자원 상한 | 입력·출력 100만 점, interruption 검사 | 모바일 메모리/취소 안전성; 근사 경로로 대체하지 않고 실패 |
| random 0 진행 | 정확히 0 길이면 설명 있는 예외 | 무진행 무한 루프 방지 |
| Ripple satin | centerLine/rungPairs/plotPointsOnRails 공급자 필요 | SatinColumn 담당 포트와 통합 전 임의 rail 근사 금지 |
| Ripple clip 그래프 | NetworkX 대신 순서 있는 Kotlin multigraph·정확 DP matching | GPL 호환 신규 의존성 없이 최소 경로 개수 목적 보존 |
| matching 한계 | 홀수 정점 60개/DP 100만 상태 상한에서 실패 | 원본 blossom보다 자원 상한이 낮음; greedy로 조용히 대체하지 않음 |
| 그래프 동률 | DP matching·단방향 Dijkstra가 같은 최적값의 다른 순서를 선택할 수 있음 | NetworkX blossom/bidirectional 경로와 동률 결정 방식 차이 |
| JTS/GEOS | 퇴화·접촉 도형의 overlay 조각/순서가 달라질 수 있음 | 기하 라이브러리 대체의 수치적 차이; 점 단위 완전 일치 미보증 |

원본의 특이 동작도 보존한다: Ripple 클립 교집합이 빈 경우 원래 stitches를 반환하고,
travel route가 없으면 endpoint fallback 후 경계 <1px 필터를 적용한다. 시작 travel marker는
거리 필터 전 추가된다. 연결 성분 정렬은 terminal travel 제거 전에 수행하므로 제거 후
겉보기 순서가 역전될 수 있다. 이는 새로 안전한 클립 동작을 보증한다는 의미가 아니다.

### 독립 게이트와 검증 범위

고정 SVG는 `engine/src/test/resources/inkstitch/`에 두고 PES는 테스트 실행 중 생성한다.
알고리즘 테스트는 읽은 수식·제어 흐름의 분석 기대값이며 Ink/Stitch 실행 비교는 하지 않았다.
clone trim/stop OR 의미와 결정적 출력 성질은 Kotlin 회귀로 검증한다.

2026-10-24의 Stroke 4개(running/manual/zigzag/bean) `check-release` 결과는 **BLOCK 0/4**다.
`/tmp/myembro-worker-stroke-{matrix,gate}.json`에 독립 도구의 원문 판정을 남겼다.
이 게이트의 매트릭스는 모든 입력을 텍스트로 판정하고 `J→S0→S0.28→S0.28` 시작 및
0.27–0.65mm 잠금 범위를 강제한다. 원본 기본 half_stitch의 통상 0.75mm 반전은 인식하지 못해
TEXT_START_SEQUENCE_MISSING/START_LOCK_MISSING/END_LOCK_MISSING으로 차단됐다.
원본 기본값을 0.28mm로 바꾸거나 게이트를 수정해서 통과시키지 않는다.
일반 도안 검사 결과와 별개로, 담당 Codex의 원본 잠금/비텍스트 정책 판정 전에는 병합 차단이다.
이 매트릭스의 font 이름은 SVG fixture 식별자이며 실제 전체 폰트 릴리스 매트릭스가 아니다.


## 기존 생성 경로 연결 — 원본 포트와 분리된 앱 정책

`GeneratedPatternProcessor.fromGroups`는 원본 플랜 생성 후 어댑터와 최종 정수 중복 제거를
적용한다. `finish(EmbPattern)`는 **기존 앱이 새로 만든** native 패턴에만 호출하는 bridge다.
레터링 running/bean/satin/fill, 직접 SatinColumn, digitizing running/tatami의 생성 끝에서 실행한다.
Bean 확장 전에 잠금을 넣어 잠금까지 삼중화하지 않으며, 내부 fill 결과를 다른 생성기에 넣을
때는 추적 가능한 우리 잠금만 제거한 본문을 사용한다.

편집기에서 가져온 PES/PEC/DST는 raw sewing이다. 객체 합성은 이미 완성된 각 객체의 바늘
순서를 보존하며 가져온 도안 전체에 잠금을 다시 붙이지 않는다. 생성 객체의 배율/자유 회전은
우리 잠금을 제거하고 변환한 본문에 재생성하여 잠금의 실제 mm 길이를 유지한다. 정수 이동·
90도 회전·미러링처럼 격자를 보존하는 변환은 바늘과 추적 좌표를 함께 변환하므로 반올림으로
0.1mm가 어긋나지 않는다. 가져온 파일의 reader/writer raw 왕복 경로는 건드리지 않는다.

| 위치 | 원본과 차이 | 이유/한계 |
|---|---|---|
| native generated bridge | 모든 명시적 JUMP/제어 명령을 경계로 보존 | 이미 needle-up으로 확정된 앱 명령을 3mm collapse로 재봉으로 바꾸지 않음; typed groups는 원본 collapse 유지 |
| native 최소 길이 | S1부터 원본 기본 0.1mm 적용 후 정수 격자의 연속 중복 STITCH 제거 | 이전 min=0 보존 정책은 기본 경로에서 제거 |
| native 잠금 | S1부터 원본 `InkLockStitch()` half-stitch 사용 | 별도 폴리라인 잠금 휴리스틱 제거; 원본 fallback과 방향 규칙 유지 |
| 양자화 중복 | native 격자에서 연속 동일 STITCH 제거 | 0.1mm로 양자화된 바늘의 중복 천공 방지; 제어 명령과 JUMP 뒤 첫 STITCH 보존 |
| native 최종 격자 | 연속 STITCH→동일 STITCH만 삭제, JUMP→첫 STITCH는 보존 | 원본 px에서 다른 점도 0.1mm 양자화 후 0길이가 될 수 있음 |
| native 명시적 최대 길이 | 호출자의 maxStitchLengthMm를 정수 격자에서 재검증/분할 | 0.2mm 등 기존 레터링 API의 상한에 잠금도 맞춤; 0.1mm 대각선은 필요한 축 이동 2개로 표현 |
| provenance | finish/lock indices/run anchors/command anchors/max length 메타데이터 추가 | 임의 잠금 모양으로 본문 좌표를 추측하지 않고, 오직 우리 잠금만 strip/rebuild |
| native 재생성 | 기본 호출은 원본 `SewingOptions()`로 재생성; 명시적 옵션 API는 엔진에만 유지 | 앱에 상세 설정·타입 선택 UI 없음. 원본 본문을 별도로 보존하여 길이 필터를 다시 적용할 수 있음 |
| 합성 결과 | 객체별 잠금 유지, 합성 전체에는 finished 마커를 붙이지 않음 | 서로 다른 raw/생성 패턴을 다시 전역 잠금하지 않음; 합성 파일 자체는 완성 machine pattern |

### 통합 담당 메모

- embro-letter의 별도 `StitchPlanFinisher`/`InkLock`와 이 공통 processor를 중복 연결하면 안 된다.
  이 계약과 원본 기본값을 공유하고, 원본과 다른 최소 길이·잠금 정책은 명시적 옵션으로 분리한다.
- Ripple의 실제 SatinColumn provider 연결은 아직 별도 담당 API 확장이 필요하다. 현재
  public plotPointsOnRails는 pull 보상/0.01mm clamp를 적용하므로 raw 0.01px sampler의 대용이 아니다.
  필요한 API는 **spacingPx·offsetPx·offsetProportional·useRandom을 받는 raw rail sampler**,
  방향/정밀도/rung 분할 후 flattened sections, subpath→rail/rung 판별 결과다.
- provider centerLine은 `offsetProportional=(-0.5,-0.5)`인 raw pair의 first 좌표이며 running 재샘플링을
  하지 않는다. rungPairs는 원시 rung endpoint가 아닌 각 flattened section의 끝 rail pair다.
  reverse/swap flags는 centerLine/rungPairs와 plotPointsOnRails에서 일치해야 한다.
- SVG 입력/커넥터 해석과 해당 새틴 provider가 연결되기 전 전체 SVG DOM 기능 완료로 보고하지 않는다.

### 재현

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug
python3 tools/check_inkstitch_worker.py --general
# 기능별: --family locks / --family stroke / --family ripple
```

게이트 도구가 없는 환경에서는 `--gate-dir`로 **독립 담당자가 제공한** 도구를 지정한다.
이 helper는 Ink/Stitch를 실행하지 않고 생성된 PES 경로의 매트릭스를 독립 도구에 제출한다.
일반 모드 PASS가 릴리스 BLOCK을 덮어쓰지 않으며 종료 코드는 릴리스 실패를 그대로 반영한다.
PES/리포트는 build 또는 /tmp에만 생성하고 바이너리를 커밋하지 않는다.


### 2026-10-24 최종 검증 기록 (worker)

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


### 20분 단위: 기본 타타미 공식 좌표 회귀 테스트 (실패를 자동 검출)

main의 S1 planner를 병합한 뒤 DefaultTatami와 기존 3개 smoke 테스트를 일반 source set으로
옮겼다. UI/제품 경로는 연결하지 않았다. `DefaultTatamiOracleTest`는 공식 defaults-v2의
사각·원·구멍 nominal20mm **3건**을 생성→PES v6→PesReader로 읽어 비교한다.
공식 PES/좌표 gzip/입력 SVG/원본 실행 기록을 `engine/src/test/resources/inkstitch-oracle/`
아래에 변경 없이 복사하고 출처·고정 커밋·SHA256을 기록했다. 개인 도안은 없다.
서로의 바늘 bbox 중심을 빼는 평행이동만 적용하며, 전 바늘(잠금 포함)의 점 집합으로
양방향 Hausdorff를 계산한다. 회전·크기 조절·점 제거는 하지 않는다.

| 대표 사례 | 우리/공식 땀 수 | 땀 수 차이 | Hausdorff mm | 결과 |
|---|---:|---:|---:|---|
| 사각 | 557/571 | 2.45% | 0.5831 | FAIL |
| 원 | 722/662 | 9.06% | 0.5315 | FAIL |
| 구멍 | 804/824 | 2.43% | 0.5831 | FAIL |

명령: `./gradlew :engine:test --tests '*DefaultTatami*'`.
기존 smoke3개 PASS, 새 공식 동등성3개 FAIL. 기준은 사용자 지정 땀수±5%/Hausdorff≤0.2mm
그대로이며, 실패 테스트를 무시하거나 허용치를 높이지 않았다. 수치·후보 PES는
`engine/build/tatami-oracle/`에 자동 저장한다. 따라서 **이 커밋은 병합 준비가 아니다**.
이전 S4 외부 게이트4/4 PASS는 기능 안전 조건 결과이며 공식 동등성 통과를 뜻하지 않았다.

worker가 POLICY.md v5의 출처별 게이트 구현을 추가하면 이 생성 PES에 SewoutCheck assert를
연결해야 한다. 현재 구형 SewoutCheck를 해당 정책과 같다고 주장하지 않고, 호출 위치를 명시했다.
CI push 대상은 모든 브랜치, 검증 명령은 `./gradlew check`로 바꿨다. 원격 CI 녹색은 미확인이다.
다음 단위 제안: 시작/끝 연결 및 바탕 땀→top 경로 전달을 원본과 대조해 3건 불일치를 수정.
이번 단위는 자동 검출·실패 수치 기록까지 하고 총괄 결정을 기다린다.

전체 `:engine:check`도 실행했다: 509개 중506개 PASS, 위 공식 동등성3개만 FAIL.


### 20분 단위: 타타미 유한 입력 확장 스윕

기본 각도0° 및 기본 바탕 땀으로5도형×5–90mm(1mm 간격)=430조합을 생성했다.
`python3 tools/sweep_tatami.py --out /tmp/myembro-tatami-sweep-1mm` 출력:
`GATE: 430/430 PASS; 0 BLOCK`. 외부 frozen v5 게이트 판정이며 worker Kotlin 정책은 병합 대기다.
규칙 계산기3개 테스트 출력은 `Ran 3 tests ... OK`. 확장 생성은 CI에서 실행하지 않는다.
자동 허용 JSON은 정확한 표본만 허용하고 실패·미검사 입력은 제외한다.
비단조 실패를 단일 최소 크기 규칙으로 일반화하지 않는다.
[명령/결과/범위 제한](tatami-sweep.md)에 표와 JSON을 기록했다.
공식 동등성3건 실패는 해결하지 않은 상태이며 앱 연결/배포는 비활성이다.
이번 단위는 여기서 멈추고 총괄 검토를 기다린다.


### 20분 단위: 공식 동등성3건 수정

간소화 InkAutoFill 그래프를 기존 TatamiFill/FillRouting 어댑터로 바꾸고,
바탕 땀 마지막점을 top 시작점에 전달했다. 원본 running tolerance0.2mm를 적용했다.
테스트 SVG 경계는 기존 worker의 Inkex CSP 평탄화 포트를 공유한다.
[전후 수치/원본 대조/명령 출력](tatami-equivalence-fix.md)을 기록했다.
`./gradlew :engine:clean :engine:check`: 509 tests, 0 failures, BUILD SUCCESSFUL in21s.
공식3건 모두 땀수100% 일치, Hausdorff0.141422mm 미만. 외부 게이트도 PASS3/3.
이전430스윕은 이전backend 이력이다. 새 코어 재스윕 전 허용규칙 비활성은 유지한다.
원격 CI/실봉제 승인과 혼동하지 않는다. 이번 단위 종료, 총괄 지시 대기.


### 계산 단위: 수정 backend430조합 재스윕

`JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 python3 tools/sweep_tatami.py --out /tmp/myembro-tatami-sweep-source-routing`
출력: `GATE: 430/430 PASS; 0 BLOCK`. 생성은 BUILD SUCCESSFUL in1m12s, 생성 오류0건.
backend e154303, 같은5도형×5–90mm 정수크기×기본각도0°.
[자동 계산 표·실패 목록·허용 JSON](tatami-sweep.md)을 현재 backend 결과로 갱신했다.
엔진/임계값/테스트 변경은 없고 앱 연결/배포 활성화도 하지 않았다. 여기서 마무리하고 지시 대기.
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

### 시작/트림 경계 force와 lettering 기본값 대조

총괄 지시에 따라 `InkStitchLettering.render`는 실제 시작·trim/stop·색 변경 뒤 첫 요소에만
원본 `force_lock_stitches=true`를 적용한다. S1 구현이나 게이트 기준은 바꾸지 않는다.
**중요한 원본 의미:** `lib/elements/element.py:lock_stitches`에서 force는 끝 잠금만 강제한다.
`ties=3`이면 force=true여도 시작 잠금은 null이다. S1도 같은 규칙을 따른다.
따라서 Aventurina J 첫 러닝의 시작 잠금 BLOCK은 force만으로 해소되지 않았다.
첫 요소의 원본 `ties=0`도 함께 적용할지 총괄에게 확인 요청했으며 임의로 켜지 않았다.
Jung Min 시험 범위는 이제 12mm 한 건, 20/30mm는 후프 범위 밖으로 제외한다.

원본 `font.py:render_text`/`batch_lettering.py`의 trim 기본값은 0/off이며 글자마다 트림하는
기본 옵션은 없다. 기본 ties=0/half_stitch, force=false, back_and_forth=true도 대조했다.
공식 대비 부족했던 16–22땀은 이전 canvas native 평탄화/재잠금 경로와 새 S1 group 경로를
구분해야 한다. 새 경로의 대표 AB 4/10/20mm는 각각 **42/42, 65/66, 89/91**땀이다.
첫 차이는 인덱스0에서 각각2/5/5로 이동했다. 4mm Hausdorff0.1mm, 10/20mm1.2mm라
완전 동등 통과로 보고하지 않는다. B의 정확히7.5mm 선분이 포트3분할/공식4분할되는 경계와
원본 glyph SVG 직렬화 정밀도가 남아 있다. 이를 새 휴리스틱으로 보정하지 않았다.

트림 차이(-2/-3/-3)는 lettering trim 옵션을 켜서 맞추면 안 된다. 원본 output.py는
`full_jump=True`, PEC writer의 `pec_encode`는 첫 점프 이후 모든 JUMP에 trimjump 플래그를
쓴다. 반면 우리 `io/PecWriter.kt`는 의도적으로 일반 JUMP를 TRIM으로 바꾸지 않는 계약이다.
writer는 이 단위에서 수정하지 않았으며 담당자와 별도로 정규화 계약을 맞춰야 한다.

초기 newline 공식 SVG는 실제 첫 줄만 들어 있었고, codex가 `font-newline-corrected`로 갱신했다.
갱신 출력은 rtl variant 없는 fixture를 위해 reversible=false인 별도 font.json을 쓴다.
대표 재현도 동일 메타데이터를 읽으며 이전 잘못된 한 줄 기준에 맞춰 포트의 두 번째 줄을 지우지 않는다.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:fontDefaultsAudit
python3 tools/breakdown-fonts-representative.py
```

이 스크립트는 codex `breakdown_fonts.py`와 같은 땀 수·트림·점프·첫 차이·bbox 중심 정규화
Hausdorff 수치만 대표4건(최대10건 제한)에 다시 계산한다. 원본96건을 재생성하지 않는다.
출력은 `/tmp/myembro-port-out/letter/font-defaults/breakdown/cases.{json,md}`다.
공식 raw 기본값 비교에는 `forceBoundaryLocks=false`를 명시한다. 이는 승인된 제품 경계 force
정책과 원본 기본값을 혼동하지 않기 위한 재현 인자이며 UI 설정을 추가하지 않는다.

#### 총괄 후속 확정: 첫 요소 ties=0 + force 적용

총괄 답변으로 시작·trim/stop·색 변경 뒤 첫 요소에 `ties=0`도 함께 적용했다.
원본 `LockStitches.fromParameters`가 기본 half_stitch를 만들며 S1 후처리는 한 번뿐이다.
내부 요소의 ties는 보존한다. 비교용 raw 기본값 모드(`forceBoundaryLocks=false`)에서는
이 두 제품 정책 override를 모두 생략한다. 출력용 배치 SVG에도 동일한 두 속성을 기록했다.
첫 바늘이 S1 lock 태그를 갖는 회귀 테스트와 전체 엔진360개 테스트가 통과했다.

**Jung Min 12mm: 기존 --text 게이트 PASS**, 공식 오라클 출력은 아직 없어 미판정이다.
20/30mm는 이번 판정 범위에서 제외했다. 대표 기본값 분해는 AB 4/10/20mm 42/42,65/66,89/91,
같은 reversible=false 메타데이터의 두 줄10mm 130/132다. 완전 동등 통과는 아니며
PEC 출력 트림 계약과 glyph 직렬화 정밀도 차이는 별도 후속 작업으로 남는다.

### Hausdorff 1.2mm 원인 추적과 수정

재현: `python3 tools/locate-font-deviation.py`. 최대10건 제한이며 두 방향의 최근접 점을
독립 PES 파서·KDTree로 계산한다. 바늘/명령 인덱스(0부터), raw·bbox 중심 정규화 좌표,
최근접 바늘, 앞뒤2개 바늘, 원본 glyph/path/subpath, 양 PES 해시를 JSON/표로 출력한다.
원본 SVG subpath와 공식 sewn run 개수가 같을 때만 순서로 요소를 대응시키고, collapse로
개수가 다르면 unresolved로 기록한다. 억지로 다른 요소 이름을 붙이지 않는다.

수정 전 10mm AB의 **공식 바늘36** raw(11.3,5.0), 정규화(3.8,0.0)의 최근접은
포트 바늘36 raw(12.5,-5.0), 정규화(5.0,0.0)로 거리1.2mm였다.
해당 요소는 **B / path17694 / subpath0**, 첫 가로획이다. 공식은 그 선분을4구간,
포트는3구간으로 나눴다. 20mm는 A/path17279/subpath0의 공식 바늘6도1.2mm 차이였다.
이는 **실제 샘플링 경로 차이**이므로 PEC 반올림·트림의 허용 차이로 처리하지 않았다.

원인: Ink/Stitch `glyph.py:_process_group/_move_to_origin`은 path를 문자열로 저장한다.
고정 의존성 inkex `EXTENSIONS_AT_INKSCAPE_1.4.1`, `paths/interfaces.py:PathCommand`의
`number_template="{:.6g}"`와 달리 포트는 변환된 좌표를 full precision으로 유지했다.
7.5mm / 2.5mm가 경계의 어느 쪽인지 달라져 원본 `ceil`의 구간 수가 달라졌다.
숫자 epsilon이나 땀 길이를 조정하지 않고, 원본의 **6자리 유효숫자 경로 직렬화**를
변환된 endpoint/Bezier control point에 적용한 뒤 분할하도록 수정했다. SVG viewport도
기존 원본6자리 고정소수 변환 공용 함수를 재사용한다.
`SvgPath.flatten`에 선택적 canonicalize 인자를 추가했으며 기존 호출은 정밀도가 바뀌지 않는다.
원호(A/a) 파라미터 직렬화 포트는 별도이므로 그 경우 기존 경로를 유지한다.

원본 확인: https://gitlab.com/inkscape/extensions/-/blob/EXTENSIONS_AT_INKSCAPE_1.4.1/inkex/paths/interfaces.py .
소스/의존성 코드는 읽기만 했으며 실행·설치하지 않았다.

수정 후 AB4/10/20mm **42/42·66/66·91/91**, 동일 메타데이터 두 줄10mm **132/132**.
모두 Hausdorff **0.1mm**다. 원본 좌표 직렬화와 최종0.1mm 격자 반올림에 남은
한 격자(0.1mm) 좌표 차이만 이번 대표 사례의 **허용 차이**로 기록한다.
트림 명령 수 차이(-2/-3/-3/-7)는 별도의 PEC writer 계약 차이이며 이 허용에 포함하지 않는다.
전체 제어 명령·전체 글꼴의 완전 동등/봉제 인증으로 확대하지 않는다.

수정 전 자료: `/tmp/myembro-port-out/letter/font-defaults/{deviation,breakdown}-before-precision/`.
수정 후: 같은 폴더 `{deviation,breakdown}/`; 특히 `deviation/max-deviations.{json,md}`.
회귀는 원본 테스트 폰트의 동일 A/B path 입력으로 7.5mm 경계·66땀을 확인한다.

### 트림 경계 분해 및 원본 PEC 동작 보존

재현: `python3 tools/locate-font-trims.py`. 대표4건에 대해 독립 PES decode의
실제 JUMP 이동 전후 바늘 좌표를 bbox 중심 정규화하고, 원본 배치 SVG의 line/glyph/path/subpath
endpoint와 대응시킨다. 공식/포트 경계 위치가0.2mm 이내일 때만 일치로 표시한다. 이 값은
기존 공식 비교 좌표 허용값이며 게이트를 바꾸지 않는다. 양 PES SHA256도 JSON에 기록한다.

10mm AB의3개 누락은 A/path17279 내부 subpath0→1, A→B 글자 경계,
B/path17694 내부 subpath0→1이다. 두 줄의7개 중에는 L1 B→L2 B 줄 경계도 있다.
따라서 글자마다 한 번 trim 옵션을 켜서는 원본과 같아지지 않는다.
원본 `font.py:render_text/_add_trims`, `batch_lettering.py:setup_trim`에서 기본값은
`trim_option=0/off`다. 1=line/2=word/3=glyph 옵션은 선택한 경우에만 trim_after를 추가한다.
이번 기본값은0 그대로 유지했다.

실제 원인은 `lib/output.py`의 full_jump 설정과 **pyembroidery1.5.1
PecWriter.py:pec_encode**다. 원본 PEC는 첫 점프 이후의 JUMP를 trimjump로 기록한다.
일반 JUMP를 유지하는 기존 우리 writer 계약을 전역 변경하지 않았다. 대신
`GeneratedPatternProcessor.fromGroups(..., pecJumpTrims=true)`라는 **자수 글꼴 전용 opt-in
출력 어댑터**로 실제 바늘을 꿰맨 뒤의 점프에 명시 TRIM을 보존한다. 기존 TRIM은 중복하지 않고
처음·연속 점프에는 새 트림을 추가하지 않는다. 바늘 좌표·땀 수는 바뀌지 않는다.
S1 잠금/실행/명령 anchor 인덱스는 추가된 제어 명령에 맞춰 이동시킨다.
원본 `StitchPlanBuilder` 기본값과 일반/가져온 도안의 writer 기본값은 그대로다.

원본 ties=3으로 꺼져 있던 내부 요소에서 PEC 트림 뒤 잠금 누락3곳이 새로 드러났다.
총괄이 확정한 thread-boundary ties=0 정책을 **S1이 실제 need_tie_in을 계산한 위치**에도
적용하는 opt-in `lockAtThreadBoundaries`를 추가했다. 시작/끝이 이미 정의된 커스텀 잠금은
유지하며, 꺼진 경우에만 worker의 기본 InkLockStitch(half_stitch)를 쓴다. 새 잠금 형태나
자체 거리 임계값은 없고, S1 후처리는 한 번이다. `forceBoundaryLocks=false`인 raw 공식 비교에서는
이 제품 override를 쓰지 않는다. 오라클용 이름 배치 SVG는 ties=0을 path에 표시하되
force는 시작/명시 trim 뒤 첫 요소만 설정하므로 source의 실제 경계 결정은 유지된다.

수정 후 AB4/10/20mm 및 두 줄10mm의 **트림 수 2/2·3/3·3/3·7/7**,
계산한 **15개 경계 전부 일치**. 땀 수42/42·66/66·91/91·132/132와 Hausdorff0.1mm 유지.
초기 원점 점프 수 차이(-1)는 남아 있으며 전체 제어 스트림 완전 동등으로 확대하지 않는다.
Jung Min12mm는 새 트림/재시작 잠금을 포함해 **2,076땀, 기존 --text 게이트 PASS**다.
공식 Jung Min 출력은 대기이므로 그 오라클 동등 판정은 미판정이다.
엔진363개 테스트 통과: PES 왕복 트림·바늘 불변·중복 트림 방지·S1 잠금 인덱스·재시작 잠금 검증 포함.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:fontDefaultsAudit :engine:inkFontNames
python3 tools/locate-font-trims.py
python3 tools/breakdown-fonts-representative.py
python3 tools/check-inkfont-names.py
```

수정 전: `/tmp/myembro-port-out/letter/font-defaults/trims-before-compatibility/`.
수정 후: `/tmp/myembro-port-out/letter/font-defaults/trims/trim-boundaries.{md,json}`.
S1 파일 수정은 글꼴 전용 출력·제품 경계 정책을 분리하기 위해 필요한 선택 인자/추적 보존에 한정한다.

### CI 고정 대표 글꼴 오라클 (4건)

`FontEquivalenceTest`를 일반 `engine:test`에 넣었으므로 `./gradlew check`에서 자동 실행한다.
CI JVM 검증 단계도 `./gradlew check`로 연결하고 해당 JUnit XML을 `font-equivalence-results`
아티팩트로 보존한다. 실패·예외가 있으면 일반 check가 실패한다. 별도 opt-in task나
환경 변수, 외부 /tmp 자료, 네트워크, Python, Ink/Stitch 실행은 필요하지 않다.

`engine/src/test/resources/inkstitch/font-equivalence/`는 공식 testfont AB4/10/20mm와
두 줄10mm의 **4건·331개 바늘 좌표**만 포함한다. 출처/공식 릴리스·바이너리/PES/
독립 decode/입력 해시와 newline reversible=false 보정 기록, GPL/OFL 고지를 함께 둔다.
총량 약52KB, 테스트 자체도 10건 이하·전체 묶음1MB 이하를 검사한다.
기대 좌표는 포트가 만든 값이 아니라 codex 공식 출력의 독립 decode에서 고정한 부분집합이다.

검사 항목은 정확한 땀 수, bbox 중심 평행이동만 허용한 대칭 Hausdorff≤0.2mm,
TRIM/STOP/색 변경의 순서·수·anchor≤0.2mm, 실 순서다. 기존 동결 오라클의 수치이며
게이트 기준을 바꾸거나 길이 통계를 차단 조건으로 되살리지 않았다. 초기 위치 JUMP 수 차이는
이미 문서화된 대로 제외한다. 모든 카탈로그 폰트·새틴의 독립 인증이나 실제 봉제를 대체하지 않는다.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check
# 4건만 재실행하려면
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*FontEquivalenceTest'
```

JUnit 출력: `engine/build/test-results/test/TEST-io.github.linker2k.myembro.engine.inkstitch.FontEquivalenceTest.xml`.
4건 모두땀 수42/42·66/66·91/91·132/132, 트림2·3·3·7, Hausdorff0.1mm로 통과했다.

검증 실행 결과: 엔진 전체 **367개 통과**, 고정 오라클4건 통과.
전체 `./gradlew check`는 앱 Lint **14개 오류**로 실패했다. 이 오류가 있는 파일은
이번 변경 전 HEAD와 동일하다(API26 리소스의 API27 windowLightNavigationBar 6건,
Compose ProduceStateDoesNotAssignValue 7건, app_name 한국어 번역 누락1건).
Lint를 끄거나 기준선을 추가하지 않았으며, 앱 담당 수정 전에는 전체 CI가 차단된다.

## 잠금·플랜·명령 포트

`LockStitchParameters`의 `ties`는 0=양쪽(기본), 1=시작, 2=끝, 3=없음이다.
`force_lock_stitches`와 전체 `disable_ties` 우선순위를 원본대로 적용한다.
`InkLockStitch`는 **half_stitch, arrow, back_forth, bowtie, cross, star, simple,
triangle, zigzag, custom**을 지원한다. 절대/상대 스케일, custom 숫자 패턴 및 SVG
endpoint 경로, END 반전, 마지막 두 anchor/direction 노드 제외 규칙을 보존한다.
원본 half_stitch는 최대 15점에서 0.5mm보다 먼 점을 찾고 1.5mm로 제한한다.
찾지 못하면 **0.5px**로 fallback한다(mm가 아니다). back_forth의 음수 연장 분기에
잠금 태그가 빠지는 원본 동작도 포트에서 임의 수정하지 않는다.

`StitchPlanBuilder.fromGroups`는 원본 `stitch_groups_to_stitch_plan`/`filter_duplicate_stitches`를
명시적으로 실행한다. 기본 collapse=3mm, 최소 길이=0.1mm, RGB 블록 결합,
이전 그룹의 최소 점프/force, tie-off→색변경→tie-in, TRIM→STOP 순서를 유지한다.
최소 길이 이하(<=) 점은 이전 보존 점이 JUMP이거나 현재 점이 잠금/제어명령이면 보존한다.
단순한 모든 0길이 점 삭제와 다르다. 최종 정수 격자 중복 제거는 별도 generated 경계에서 한다.

`ElementCommands`는 SVG 커넥터가 해석된 후의 ignore_object/layer, trim_after,
stop_after, starting_point/ending_point와 이전 fill_start/fill_end 별칭을 받는다.
ignore는 생성 자체를 생략하고, trim/stop은 요소의 마지막 비어 있지 않은 그룹에 OR한다.
시작·끝 점은 요소별 라우팅 담당이 사용한다. 이 파사드는 임의 좌표 이동을 만들지 않는다.

| 위치 | 원본과 차이 | 이유/한계 |
|---|---|---|
| Lock 직접 호출 | 2점 미만은 빈 결과 | 원본 StitchGroup 보호 조건을 재사용 가능한 API에서도 적용 |
| SVG lock parser | Inkex 대신 endpoint 전용 Kotlin 파서 | M/L/H/V/C/S/Q/T/A/Z endpoint만 필요; 곡선 control point를 바늘 위치로 사용하지 않음 |
| 유효성 검사 | 비유한 스케일/좌표는 조기에 거부 | 잘못된 파일·무한 확장 방지 |
| lock 타입 표시 순서 | END UI의 cross/bowtie 순서 차이는 단일 ID 목록으로 표현 | ID와 생성 좌표는 동일 |
| 빈 그룹 | 전체 빈 입력은 빈 플랜, 앞뒤 빈 그룹 제외 | UI 종료/빈 블록 인덱스 오류 대신 엔진 반환값 제공, 마지막 실제 그룹 tie-off 보존 |
| 그룹 minimum | per-stitch 값이 있을 때 그룹 값으로 덮어쓰지 않음 | 공유 계약의 세밀한 override 보존; Stroke 파사드는 원본 요소처럼 모든 점에 요소 값을 지정 |
| END | 원본 모델에 없는 END도 필터에서 보존 | 기존 machine API의 종료 명령 보장 |
| 명령 탐색 | Inkex XML·clone·커넥터 탐색 대신 resolved flags/points | SVG 가져오기 담당과의 명시적 통합 경계; 레이어 무시 판정 자체는 호출자 책임 |
| terminal command | 마지막 비어 있지 않은 그룹에 적용 | trailing empty 그룹에서 trim/stop이 사라지는 경우 방지 |

## Stroke·Ripple 포트

`StrokeParameters` / `RippleParameters.fromAttributes`는 `inkstitch:*`, XML 확장 이름,
로컬 속성 이름을 받고 공개 mm/percent 값을 유지한다. `Stroke`는 변환·클립 완료된 SVG px
폴리라인을 받는다. manual은 원래 노드 endpoint, running/zigzag는 평탄화한 곡선을 전달해야 한다.
원본 Inkex DOM 로딩·transform·curve flattening이 자동으로 포함됐다고 주장하지 않는다.

Running은 sleeve/angle interval·원 교차·45도 코너·even/fixed carry, 길이 목록, tolerance,
반복, bean 및 결정적 random length를 포함한다. Zigzag는 Python 짝수 반올림, 짝수 샘플,
평균 접선, 비대칭 pull compensation, 각도·폐곡선·반복 규칙을 따른다. Manual은 최대 길이로
분할하고 force가 없으면 잠금을 만들지 않는다. `Prng`는 공통 lettering 포트와 같은 API/코드로,
BLAKE2s counter stream의 hex-word 순서와 UTF-8 seed를 재현한다(신규 의존성 없음).

Ripple은 target/convex hull centroid, closed spiral, line/satin guide, 수동 배치,
줄 수/최소 간격·parity, skip/stagger/exponent, 축별 scale·회전·rungs, grid/grid-first,
반복·bean과 클립 다중 그래프 라우팅을 포함한다. 원본 getter 기본값을 사용한다:
`staggers=1.0`, `satin_guide_pattern_position=line_count`(UI default와 차이 있음).
클립은 JTS overlay, 경계 그래프, Euler 보정·경계 travel·연결 성분별 그룹으로 처리한다.
멀티패스/satin guide는 `RippleSatinGeometry`의 실제 SatinColumn rail/rung 샘플러가 필요하다.
이 공급자 없이 임의로 두 경로를 rail로 추측하지 않는다.

| 위치 | 원본과 차이 | 이유/한계 |
|---|---|---|
| Stroke 입력 | Inkex node 대신 transformed/clipped 폴리라인과 resolved commands | SVG 요소 파서/클리핑과 생성 알고리즘의 통합 경계 |
| empty running | None 대신 빈 List | Kotlin collection API |
| 잘못된 숫자 목록 | 빈/파싱 불가 목록 기본값 또는 유효성 오류 | NaN 전파·인덱스 오류 방지; 유효 입력의 기본값은 보존 |
| 자원 상한 | 입력·출력 100만 점, interruption 검사 | 모바일 메모리/취소 안전성; 근사 경로로 대체하지 않고 실패 |
| random 0 진행 | 정확히 0 길이면 설명 있는 예외 | 무진행 무한 루프 방지 |
| Ripple satin | centerLine/rungPairs/plotPointsOnRails 공급자 필요 | SatinColumn 담당 포트와 통합 전 임의 rail 근사 금지 |
| Ripple clip 그래프 | NetworkX 대신 순서 있는 Kotlin multigraph·정확 DP matching | GPL 호환 신규 의존성 없이 최소 경로 개수 목적 보존 |
| matching 한계 | 홀수 정점 60개/DP 100만 상태 상한에서 실패 | 원본 blossom보다 자원 상한이 낮음; greedy로 조용히 대체하지 않음 |
| 그래프 동률 | DP matching·단방향 Dijkstra가 같은 최적값의 다른 순서를 선택할 수 있음 | NetworkX blossom/bidirectional 경로와 동률 결정 방식 차이 |
| JTS/GEOS | 퇴화·접촉 도형의 overlay 조각/순서가 달라질 수 있음 | 기하 라이브러리 대체의 수치적 차이; 점 단위 완전 일치 미보증 |

원본의 특이 동작도 보존한다: Ripple 클립 교집합이 빈 경우 원래 stitches를 반환하고,
travel route가 없으면 endpoint fallback 후 경계 <1px 필터를 적용한다. 시작 travel marker는
거리 필터 전 추가된다. 연결 성분 정렬은 terminal travel 제거 전에 수행하므로 제거 후
겉보기 순서가 역전될 수 있다. 이는 새로 안전한 클립 동작을 보증한다는 의미가 아니다.

### 독립 게이트와 검증 범위

고정 SVG는 `engine/src/test/resources/inkstitch/`에 두고 PES는 테스트 실행 중 생성한다.
알고리즘 테스트는 읽은 수식·제어 흐름의 분석 기대값이며 Ink/Stitch 실행 비교는 하지 않았다.
clone trim/stop OR 의미와 결정적 출력 성질은 Kotlin 회귀로 검증한다.

2026-10-24의 Stroke 4개(running/manual/zigzag/bean) `check-release` 결과는 **BLOCK 0/4**다.
`/tmp/myembro-worker-stroke-{matrix,gate}.json`에 독립 도구의 원문 판정을 남겼다.
이 게이트의 매트릭스는 모든 입력을 텍스트로 판정하고 `J→S0→S0.28→S0.28` 시작 및
0.27–0.65mm 잠금 범위를 강제한다. 원본 기본 half_stitch의 통상 0.75mm 반전은 인식하지 못해
TEXT_START_SEQUENCE_MISSING/START_LOCK_MISSING/END_LOCK_MISSING으로 차단됐다.
원본 기본값을 0.28mm로 바꾸거나 게이트를 수정해서 통과시키지 않는다.
일반 도안 검사 결과와 별개로, 담당 Codex의 원본 잠금/비텍스트 정책 판정 전에는 병합 차단이다.
이 매트릭스의 font 이름은 SVG fixture 식별자이며 실제 전체 폰트 릴리스 매트릭스가 아니다.


### S3 앱 연결: 기본 타타미+바탕 땀

letter S2기반111ae77을 통합하고 TTF 채우기·6.0mm 초과 레일/센터라인 새틴을
DefaultTatami→S1그룹으로 연결했다. 자동 표시에 채우기를 추가하고 높이 상한은 스윕90mm다.
[구현 범위·근거·테스트 출력](s3-fill-app-integration.md)을 기록했다.
`./gradlew :engine:check :app:assembleDebug :app:testDebugUnitTest`: BUILD SUCCESSFUL in19s,
engine552/app176 failures0. 통합6건, bundled Jua TTF ‘자수’1230땀의 외부 게이트PASS1/1.
원격CI/실기승인은 별도이며 여기서 멈추고 총괄 검토를 기다린다.


### S1 생성 후처리 통합 기록

`GeneratedPatternProcessor.fromGroups`는 원본 플랜 생성 후 어댑터와 최종 정수 중복 제거를
적용한다. `finish(EmbPattern)`는 **기존 앱이 새로 만든** native 패턴에만 호출하는 bridge다.
레터링 running/bean/satin/fill, 직접 SatinColumn, digitizing running/tatami의 생성 끝에서 실행한다.
Bean 확장 전에 잠금을 넣어 잠금까지 삼중화하지 않으며, 내부 fill 결과를 다른 생성기에 넣을
때는 추적 가능한 우리 잠금만 제거한 본문을 사용한다.

편집기에서 가져온 PES/PEC/DST는 raw sewing이다. 객체 합성은 이미 완성된 각 객체의 바늘
순서를 보존하며 가져온 도안 전체에 잠금을 다시 붙이지 않는다. 생성 객체의 배율/자유 회전은
우리 잠금을 제거하고 변환한 본문에 재생성하여 잠금의 실제 mm 길이를 유지한다. 정수 이동·
90도 회전·미러링처럼 격자를 보존하는 변환은 바늘과 추적 좌표를 함께 변환하므로 반올림으로
0.1mm가 어긋나지 않는다. 가져온 파일의 reader/writer raw 왕복 경로는 건드리지 않는다.

| 위치 | 원본과 차이 | 이유/한계 |
|---|---|---|
| native generated bridge | 모든 명시적 JUMP/제어 명령을 경계로 보존 | 이미 needle-up으로 확정된 앱 명령을 3mm collapse로 재봉으로 바꾸지 않음; typed groups는 원본 collapse 유지 |
| native 최소 길이 | S1부터 원본 기본 0.1mm 적용 후 정수 격자의 연속 중복 STITCH 제거 | 이전 min=0 보존 정책은 기본 경로에서 제거 |
| native 잠금 | S1부터 원본 `InkLockStitch()` half-stitch 사용 | 별도 폴리라인 잠금 휴리스틱 제거; 원본 fallback과 방향 규칙 유지 |
| 양자화 중복 | native 격자에서 연속 동일 STITCH 제거 | 0.1mm로 양자화된 바늘의 중복 천공 방지; 제어 명령과 JUMP 뒤 첫 STITCH 보존 |
| native 최종 격자 | 연속 STITCH→동일 STITCH만 삭제, JUMP→첫 STITCH는 보존 | 원본 px에서 다른 점도 0.1mm 양자화 후 0길이가 될 수 있음 |
| native 명시적 최대 길이 | 호출자의 maxStitchLengthMm를 정수 격자에서 재검증/분할 | 0.2mm 등 기존 레터링 API의 상한에 잠금도 맞춤; 0.1mm 대각선은 필요한 축 이동 2개로 표현 |
| provenance | finish/lock indices/run anchors/command anchors/max length 메타데이터 추가 | 임의 잠금 모양으로 본문 좌표를 추측하지 않고, 오직 우리 잠금만 strip/rebuild |
| native 재생성 | 기본 호출은 원본 `SewingOptions()`로 재생성; 명시적 옵션 API는 엔진에만 유지 | 앱에 상세 설정·타입 선택 UI 없음. 원본 본문을 별도로 보존하여 길이 필터를 다시 적용할 수 있음 |
| 합성 결과 | 객체별 잠금 유지, 합성 전체에는 finished 마커를 붙이지 않음 | 서로 다른 raw/생성 패턴을 다시 전역 잠금하지 않음; 합성 파일 자체는 완성 machine pattern |

### 통합 담당 메모

- embro-letter의 별도 `StitchPlanFinisher`/`InkLock`와 이 공통 processor를 중복 연결하면 안 된다.
  이 계약과 원본 기본값을 공유하고, 원본과 다른 최소 길이·잠금 정책은 명시적 옵션으로 분리한다.
- Ripple의 실제 SatinColumn provider 연결은 아직 별도 담당 API 확장이 필요하다. 현재
  public plotPointsOnRails는 pull 보상/0.01mm clamp를 적용하므로 raw 0.01px sampler의 대용이 아니다.
  필요한 API는 **spacingPx·offsetPx·offsetProportional·useRandom을 받는 raw rail sampler**,
  방향/정밀도/rung 분할 후 flattened sections, subpath→rail/rung 판별 결과다.
- provider centerLine은 `offsetProportional=(-0.5,-0.5)`인 raw pair의 first 좌표이며 running 재샘플링을
  하지 않는다. rungPairs는 원시 rung endpoint가 아닌 각 flattened section의 끝 rail pair다.
  reverse/swap flags는 centerLine/rungPairs와 plotPointsOnRails에서 일치해야 한다.
- SVG 입력/커넥터 해석과 해당 새틴 provider가 연결되기 전 전체 SVG DOM 기능 완료로 보고하지 않는다.

### 재현

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug
python3 tools/check_inkstitch_worker.py --general
# 기능별: --family locks / --family stroke / --family ripple
```

게이트 도구가 없는 환경에서는 `--gate-dir`로 **독립 담당자가 제공한** 도구를 지정한다.
이 helper는 Ink/Stitch를 실행하지 않고 생성된 PES 경로의 매트릭스를 독립 도구에 제출한다.
일반 모드 PASS가 릴리스 BLOCK을 덮어쓰지 않으며 종료 코드는 릴리스 실패를 그대로 반영한다.
PES/리포트는 build 또는 /tmp에만 생성하고 바이너리를 커밋하지 않는다.


### 2026-10-24 최종 검증 기록 (worker)

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

### main 95a7447 통합 후 전체 check

main의 Lint 수정·S1 처리 통계/옵션을 병합했다. S2 PEC 트림과 경계 잠금 옵션은
최신 S1 처리 통계를 보존하며, fromGroups 완료 결과에 기본 sewing options 메타데이터를
기록해 앱에서 같은 기본 설정으로 재호출해도 원본 글꼴 잠금/트림이 재처리되지 않게 했다.
크기 변환 회귀 검사는 이전 고정 잠금 길이 대신 변환된 본 땀에 현재 S1 기본값을 적용한 좌표와 비교한다.

`./gradlew check`: **BUILD SUCCESSFUL**. 엔진450개, 앱 Debug177개·Release177개 실패0,
Android Lint 통과. 공식 고정 오라클4건도 땀 수·트림·좌표 검사를 유지한다.
CI는 main의 모든 브랜치 push 트리거와 전체 engine-test-results 아티팩트를 따른다.

### codex/model + staging/s2s3 통합 (2026-10-24)

- model `3170545`에 staging `df7da34`를 병합했다. 실제 충돌은 8개였고
  `ci.yml`은 자동 병합됐다. 모든 브랜치 push CI와 model의 Python 집계 테스트 단계를 유지했다.
- `GeneratedPatternProcessor`와 `StitchPlanBuilder`는 staging의 letter/S1 파일과
  바이트 동일하다. PEC 점프 트림·실 경계 잠금·처리 통계를 보존한다. model의
  기본 sewing-options 메타데이터 기록은 staging에도 이미 포함돼 재정의하지 않았다.
- Gradle의 letter 재현 태스크 3개는 model에도 이미 같은 정의가 있어 중복을 만들지 않았다.
  타타미 스윕·오라클 경계 source set·TTF 스윕 classpath 태스크와 JGraphT 의존성을 유지했다.
- 양쪽 고지·문서의 모든 섹션을 보존했다. 테스트는 실제 필터 후 본문에 타타미 행 위상을
  검사하고, 변환된 글자의 잠금 좌표를 S1 재생성 결과와 비교하면서 model의 길이 검사를 유지한다.
- `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check --console=plain`:
  **BUILD SUCCESSFUL in 1m 10s**, 67 tasks (58 executed / 9 up-to-date).
  JUnit XML: 엔진 **571**, 앱 Debug **177**·Release **177**, 실패·오류·skip **0**.
  Android Lint 통과. [명령 출력](checks/model-s2s3-check.log)을 함께 기록했다.
- `python3 -m unittest discover -s tools/tests -p 'test_*sweep.py' -v`:
  **Ran 11 tests; OK**. 양쪽 문서 제목 보존 및 staging 공통 처리 파일 동일성도 코드로 확인했다.
- [TTF 표본 스윕 결과](s3-ttf-fill-sweep.md)는 병합 전 고정 엔진의 기록이다.
  병합 후 전체 check의 타타미 공식 동등성·TTF 통합 테스트도 통과했다.
  원격 CI와 사용자 실제 봉제는 위 로컬 검사 결과와 구분한다.

## claude-canvas S3: Ink/Stitch 자수 글꼴 카탈로그·목록 (브랜치 `claude/inkfonts-s3`)

`staging/s2s3` 위에 올렸습니다. 글꼴 읽기·배치·봉제(`engine/.../inkstitch/lettering/InkStitchFont.kt`, `InkStitchLettering.kt`)는 embro-letter 구현을 그대로 씁니다. 이 브랜치가 더하는 것은 다음뿐입니다.

- 카탈로그 `app/src/main/assets/fonts/inkstitch-catalog.json`: 재배포 가능한 글꼴의 고정 URL·sha256, 글리프 이름, 허용 높이 범위. `tools/inkstitch_catalog.py`가 만듭니다.
- 허용 높이: `InkStitchFontSweep`(opt-in 엔진 테스트)가 글꼴 기록 범위를 1mm 간격으로, 텍스트 3종을 `SewoutCheck`로 판정하고, 가장 긴 연속 통과 구간을 씁니다. 결과는 `docs/inkstitch-font-sweep.json`에 둡니다. 후프 초과는 범위를 줄이지 않고 따로 셉니다(총괄 확인).
- 목록 UI: "검증된 자수 글꼴"을 맨 앞에 두고(이름과 땀 미리보기), 입력 글자가 없는 글꼴은 흐리게·선택 불가로 둡니다. 높이 슬라이더는 허용 범위로 제한합니다.
