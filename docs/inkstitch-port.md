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

| # | 기능 | 원본 | 상태 |
|---|---|---|---|
| 0 | Ink/Stitch 폰트: 파서, 카탈로그, 다운로드, 레터링 UI 연결 | lib/lettering/{font,font_variant,glyph}.py, lib/svg/guides.py, lib/elements/utils/nodes.py, satin_column 레일·렁 판별 | 완료. 게이트 차단: 잠금(ties)과 새틴 세부 알고리즘이 다른 담당자 포팅을 기다림 |
| 1 | 일반 Ink/Stitch SVG 가져오기(속성, 명령 심볼, 그룹, transform, clone) | lib/elements/utils/nodes.py, lib/commands.py, lib/svg/* | 예정 |
| 2 | Cross stitch 요소 | lib/elements/fill_stitch.py (cross_stitch), lib/stitches/cross_stitch.py | 예정. 완료되면 재배포 가능 폰트 17종이 추가됨 |
| 3 | Clone 요소 | lib/elements/clone.py | 예정 |
| 4 | 레터링 동등화: 여러 색 글꼴(color_sort), 경로 따라 배치, 정렬·커닝, 글꼴 샘플 | lib/lettering/font.py, lib/extensions/lettering*.py, lettering_along_path.py, lettering_font_sample.py | 정렬(왼쪽/가운데/오른쪽)과 커닝은 0번에서 완료. 나머지는 예정 |

땀은 각 담당자의 포팅 결과를 호출해서 만듭니다(새틴=embro-letter, 채움=embro-model, Stroke·ties=embro-worker). 지금은 `InkStitchLettering.runs`가 임시로 기존 엔진 생성기를 부릅니다. 해당 포팅이 main에 들어오면 이 함수만 교체합니다.

### 차이점 (원본과 다르게 만든 곳)

| 위치 | 원본 동작 | 우리 동작 | 이유 |
|---|---|---|---|
| InkStitchFont 파서 | inkex DOM으로 문서 전체를 읽음 | SAX 스트리밍. DOCTYPE과 외부 엔티티 거부 | 휴대폰 메모리(SVG 최대 14.5MB), 내려받은 데이터 보안 |
| 가이드 없는 글꼴의 기준선 | 0 | 'H' 잉크 바닥 | 모든 글리프가 공유하는 값이라 땀에는 영향이 없고, 줄 배치만 자연스러워짐 |
| 글리프 경로 평탄화 | 베지어를 그대로 두고 shapely로 처리 | 0.1px(약 0.026mm) 허용오차로 평탄화 | JTS와 엔진 입력 형식에 맞춤 |
| 단일 경로 새틴(stroke 폭) | convert_path_to_satin(노드마다 렁) | 엔진 `SatinColumn.rails`로 만든 1구간 | 새틴은 embro-letter 담당. 포팅되면 교체 |
| 레거시 콤보 값 "0" | update_inkstitch_document가 이름으로 변환 | 0이면 기본 방식으로 해석 | 문서 업데이트기는 포팅하지 않음 |
| 레터링 높이 | 글꼴 배율(%) | 'H' 잉크 높이(mm). 배율 = 높이 / (H 높이 × 1) | 앱의 모든 글꼴이 같은 높이 기준을 씀 |
| 자간 | letter_spacing(mm, 기본 0) | TextObject.spacing을 그대로 씀. 이 글꼴을 고르면 화면이 0으로 맞춤 | 기존 UI 슬라이더 재사용 |
| 줄 간격 | leading + line_height | TextObject.lineSpacing × 높이(mm). 미지정이면 leading | 기존 UI와 프로젝트 형식 유지 |
| 정렬 | text_align(left/center/right/block) | left, center, right. block은 미지원 | block은 4번 항목에서 처리 |
| auto_satin, 색 정렬(sortable), trim 옵션 | 글꼴 설정에 따라 적용 | 아직 적용 안 함(문서 순서로 봉제, 점프 3mm 초과면 트림) | 4번 항목과 stitch_plan 계약 이후에 처리 |
| 잠금(lock_start/lock_end) | 기본 half_stitch 잠금 | 없음 → 게이트 차단 | ties는 embro-worker 담당 |
| 새틴 땀 배치 | plot_points_on_rails(직각 거리 보정), short_stitch_inset | 렁 구간을 호 길이로 짝지음. 짧은 땀 인셋 없음 | embro-letter 포팅 대기. 게이트 SATIN_SPACING_TIGHT와 짧은 땀 비율 초과의 원인 |
| 프로젝트 저장 | 생성된 SVG 요소를 문서에 넣음 | 글꼴 id와 텍스트만 저장하고 설치된 글꼴로 다시 만듦 | 파일 크기. 글꼴을 지우면 경고와 함께 빈 땀이 됨 |

### 게이트 기록
- 0번 Ink/Stitch 폰트 (2026-10-24): `check-release`로 4개 글꼴(ambigue, caffeine_KOR, venezia, manga_impact) × 15·30mm × satin, 'Hamburg 2024'를 검사했습니다. **0/8 통과(차단)**.
  - 8건 모두: 텍스트 시작 잠금, 시작·끝·트림 앞뒤 잠금이 없음
  - venezia 15mm: 0.3mm 미만 땀 15%
  - manga_impact: 새틴 같은 쪽 간격 p10 0.10mm, 0.5mm 셀 밀도 17–18
  - 원인은 ties와 새틴 세부 알고리즘이며, 포팅 결과를 호출하도록 바꾼 뒤 다시 검사합니다.
