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

## claude-canvas 담당 (브랜치 `claude/inkfonts`)

원본 고정은 위 공통 기준 **v3.3.0 `b0edd96`** 입니다. 이전에 쓰던 `16e1928`과 비교해 이 담당 범위의 파일(lib/lettering, lib/svg, lib/elements/clone.py, utils/nodes.py)은 같고, satin_column만 v3.3.0의 단일 파일로 출처를 바꿨습니다.

2026-10-24 개정 지시(docs/ui-stories.md "실패 없는 디자인")에 따라 이번 버전 범위는 **S3 자수 글꼴**뿐입니다.
SVG 가져오기, 복제, 십자수는 다음 버전으로 미뤘고, 진행분은 `claude/svg-import-archive` 브랜치에 보관합니다.
엔진의 SVG 트리와 요소 분류(nodes.py·clone.py 포트)는 글꼴 읽기에 쓰므로 남깁니다(글리프 안의 clone 포함).

| 기능 | 원본 | 상태 |
|---|---|---|
| Ink/Stitch 자수 글꼴 읽기·배치 | lib/lettering/{font,font_variant,glyph}.py, lib/svg/{guides,path,units}.py, lib/elements/utils/nodes.py, clone.py | 완료. back_and_forth, 줄별 bbox 정렬, _set_style 포함 |
| US-3 화면 | – | 글꼴 목록 맨 앞 "검증된 자수 글꼴"(이름 + 실제 땀 미리보기), 입력 글자가 없는 글꼴은 흐리게·선택 불가, 높이 슬라이더는 글꼴 기록 범위(min_scale–max_scale)로 제한 |
| 레터링 나머지(auto_satin, color_sort, trim 옵션, block 정렬, 경로 배치, 글꼴 샘플) | lib/lettering/font.py 외 | 다음 버전. auto_satin은 글꼴 기본값이라 S3 품질 판정 후 필요하면 포팅 |

### 땀 생성 연결

이 담당은 요소 분류까지만 하고, 땀은 담당자 포트를 호출합니다(새틴=embro-letter, 채우기=embro-model, Stroke·ties=embro-worker).
그 포트들이 main에 들어오기 전까지 `elements/InkElementSewing.kt`가 기존 엔진 생성기를 임시로 부릅니다. 이 파일만 교체하면 됩니다.

- 새틴: `InkSatin`은 v3.3.0 SatinColumn이 보는 형태 그대로 레일 2개(하위 경로 순서), 명시적 렁, 레일 노드를 넘깁니다. 방향(reverse/swap)·렁 절단·땀 배치는 letter의 `inkstitch.elements.SatinColumn(leftRail, rightRail, parameters, rungs)`가 합니다. 노드 기반 암묵 렁(_synthesize_rungs)은 아직 어느 포트에도 없어, letter와 정할 일로 남깁니다.
- 기존 `engine/lettering/SatinColumn.kt`에 넣었던 구간 새틴(sectionPoints)은 되돌렸습니다. letter 브랜치와 겹치지 않습니다.

### 재현 커맨드 (embro-codex 비교용)

```
tools/inkstitch_port_out.sh            # fonts
```

- 입력: `/tmp/myembro-quality-gate/matrix-inkstitch.json`의 해당 기능 케이스(입력 sha256 확인). `.xz` 폰트 픽스처는 `xz -dc`로 풀어 데이터로만 씁니다.
- 출력: `/tmp/myembro-port-out/claude-canvas/<feature>/<output_basename>`, 목록은 `results.json`(matrix_sha256, commit, 케이스별 status·path·sha256·code). compare_matrix.py `--ports`가 읽는 형식입니다.

### 차이점 (원본과 다르게 만든 곳)

| 위치 | 원본 동작 | 우리 동작 | 이유 |
|---|---|---|---|
| SVG 읽기 | inkex/lxml DOM, CSS `<style>` 시트 적용 | SAX로 만든 가벼운 트리. DOCTYPE·외부 참조 거부. `<style>` 시트는 무시 | 휴대폰 메모리, 내려받은 데이터 보안. 시트 스타일은 Ink/Stitch 파일에서 드묾 |
| 경로 평탄화 | 베지어를 shapely로 처리 | 0.1px(약 0.026mm) 허용오차로 평탄화 | JTS와 엔진 입력 형식 |
| 그라데이션 색 | 단일 정지점 스와치만 그 색 | 첫 정지점 색 | 실 색 하나를 골라야 함 |
| 순환 clone | 파이썬 재귀 오류로 실패 | CYCLIC_REFERENCE로 거부 | 매트릭스 음성 케이스 계약 |
| 가이드 없는 글꼴의 기준선 | 0 | 'H' 잉크 바닥 | 모든 글리프가 공유하는 값이라 땀에는 영향 없음 |
| 레거시 콤보 값 "0" | update_inkstitch_document가 이름으로 변환 | 0이면 기본 방식 | 문서 업데이트기는 포팅하지 않음 |
| 레터링 높이 | 글꼴 배율(%) | 'H'(없으면 가장 큰 글리프) 잉크 높이(mm) | 앱의 모든 글꼴이 같은 높이 기준 |
| 자간·줄 간격 | letter_spacing·line_height(mm, 배율 전) | 실제 mm(배율 후). 줄 간격 미지정이면 leading | 기존 UI와 프로젝트 형식 |
| 없는 글자 | default_glyph로 대체하거나 건너뜀 | 목록에서 그 글꼴을 고를 수 없게 함(US-3) | 실패 없는 디자인 |
| 시작·끝 위치 명령, satin_cut_point | 땀 시작·끝 위치에 반영 | 무시하고 목록에 표시 | 각 땀 포트의 시작/끝점 인자가 들어오면 연결 |
| 단일 경로 새틴 | convert_path_to_satin | 임시로 엔진 `SatinColumn.rails` | 새틴 담당 포트 대기 |
| 잠금, collapse, 최소 땀 | stitch_plan 후처리 | 임시 브리지(잠금 없음, 메타데이터의 collapse_len·min_stitch_len 적용) | ties·후처리는 embro-worker |
| 프로젝트 저장 | 생성된 SVG 요소를 문서에 넣음 | 글꼴 id와 텍스트만 저장하고 설치된 글꼴로 다시 만듦 | 파일 크기. 글꼴을 지우면 경고와 함께 빈 땀 |

### 검증 기록

- 원본 `tests/test_clone.py`의 각도·숨김·스타일·변환·중첩 clone 사례를 같은 입력과 기대값으로 `elements/CloneTest.kt`에 옮겼습니다(18개 통과).
- 매트릭스 재현(2026-10-24, 커밋은 results.json): fonts 96건 생성. svg_import·clone 결과(434 생성, 4 거부)는 보관 브랜치 커밋 `e7cbcf0` 기준입니다.
- 자체 간이 대조(판정 아님): svg_import·clone은 bbox가 원본과 0.2mm 이내인 경우가 각각 161/168, 149/170으로 가져오기 기하는 맞습니다. 땀 수와 좌표 차이는 임시 생성기와 잠금 부재 때문입니다. 폰트 1줄 케이스는 bbox와 러닝 땀 길이 분포가 같고, 차이는 잠금 땀입니다.
- oracle의 `fonts--*--AB-newline-BA` 배치 SVG에는 첫 줄 "AB"만 들어 있습니다(글리프 2개). 2줄 배치를 비교할 수 없어 총괄 보고에 적었습니다(embro-codex 확인 필요).
- 0번 폰트의 이전 게이트 기록: 4개 글꼴 × 15·30mm × satin, 0/8 통과. 주원인은 잠금 검사였고, 게이트는 재보정 중입니다.
