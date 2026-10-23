# Ink/Stitch 포팅 기록

Ink/Stitch 기능을 병렬로 포팅하는 담당자들이 함께 쓰는 문서입니다. 원칙은 먼저 원본과 가능한 한 동일하게 만들고, 그다음 모바일 장점으로 차별화하는 것입니다. 땀 품질이 UI보다 우선입니다.

## 원본 고정

| 항목 | 값 |
|---|---|
| 저장소 | https://github.com/inkstitch/inkstitch (GPL-3.0-or-later) |
| 사용 커밋 | `16e192849e619399ce8fdefb4c4d249dacdb4db1` (main, 2026-10-19). 공용 읽기 전용 clone `/tmp/myembro-inkstitch-source`와 같은 커밋 |
| 최신 안정 태그 | `v3.3.0` = `b0edd96311ece82ee48816dc93466274b12c2a9c`. 위 커밋은 이 태그 이후의 main입니다. 공통 규칙상 "이미 clone한 커밋을 쓴다"를 따랐습니다. 태그로 되돌릴지는 총괄이 결정합니다. |
| 폰트 데이터 | https://github.com/inkstitch/embroidery-fonts `c7e3a05c3d6f5c77b881c50f257851af9a8e9cb8` (docs/inkstitch-fonts.md) |

- 코드는 읽기만 하고, 실행하거나 의존성을 설치하지 않습니다.
- 포팅한 파일 머리에는 `// Ported from Ink/Stitch <path>@<commit> (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors`를 붙입니다.
- 패키지는 `io.github.linker2k.myembro.engine.inkstitch.{stitch_plan, elements, stitches, lettering, utils, svg}`입니다.
- 파라미터 이름, 기본값, 단위는 `inkstitch:*` 속성과 1:1로 맞춥니다.

## 공통 계약 초안 (소유: embro-worker)

main에 들어오기 전까지 다음 형태를 기준으로 작업하고, 들어오면 실제 계약에 맞춥니다.

```kotlin
// engine.inkstitch.stitch_plan
data class Stitch(val x: Double, val y: Double, val tags: Set<String> = emptySet())   // mm, +y down; tags: trim, stop, jump, lock_stitch …
data class StitchGroup(
    val color: Int, val stitches: List<Stitch>,
    val trimAfter: Boolean = false, val stopAfter: Boolean = false,
    val lockStart: String? = null, val lockEnd: String? = null,         // lock_start / lock_end 이름
    val minStitchLengthMm: Double? = null, val minJumpLengthMm: Double? = null,
    val forceLockStitches: Boolean = false,
)
class StitchPlan(val colorBlocks: List<ColorBlock>)
interface EmbroideryElement { fun toStitchGroups(previousStitch: Stitch?): List<StitchGroup> }
// 어댑터: StitchPlan → EmbPattern(0.1 mm) → 기존 PES 쓰기
```

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
