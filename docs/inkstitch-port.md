# Ink/Stitch Kotlin 포팅 계약과 진행 상태

## 고정 원본

- 안정 태그: **v3.3.0**.
- 태그가 가리키는 커밋: **`b0edd96311ece82ee48816dc93466274b12c2a9c`**.
- [고정 소스](https://github.com/inkstitch/inkstitch/tree/b0edd96311ece82ee48816dc93466274b12c2a9c).
- 로컬 읽기용 clone: `/tmp/myembro-inkstitch-v3.3.0`.
  이전 개발 HEAD `16e192849e619399ce8fdefb4c4d249dacdb4db1`로 시작했던 초안을
  안정 버전으로 대조했다. running의 타입 주석 변경 외에는 사용하는 running/tatami/lock
  원본 모듈이 동일했고, 새틴은 안정 버전의 단일 파일을 다시 기준으로 삼았다.
- `LICENSE` 전문과 `lib/elements/satin_column.py` 머리의
  **GNU GPL version 3.0 or later**를 직접 확인했다. GPL 전문 사본:
  `engine/src/main/resources/third_party/inkstitch/LICENSE`.
- Ink/Stitch Python 실행·의존성 설치·원본으로 출력 생성은 하지 않았다.
- 패키지: `io.github.linker2k.myembro.engine.inkstitch.{elements,stitches,stitch_plan,utils}`.
  길이는 **mm**, 최종 EmbPattern 어댑터만 **0.1mm 정수**로 바꾼다.

## 담당과 일정

`embro-letter` / `codex/letter`: SatinColumn 기하 코어와 기존 레터링 연결.
최초 예상은 기본 코어·언더레이 4–6시간, split/변형·동등성 검증 추가 6–10시간이다.
자동 경로·선→새틴 도구는 2단계다. 공통 StitchPlan 소유자는 `embro-worker`,
독립 출력 판정은 `embro-codex`다. 로컬 단위 테스트 성공을 릴리스 판정으로 대신하지 않는다.

## 공통 계약 초안 — 구현 소유권은 embro-worker

main의 공통 구현이 도착하기 전에는 같은 이름의 중복 모델을 만들지 않는다.

```text
Stitch(x, y, tags: jump/trim/stop/lock/...)
StitchGroup(color, stitches, trimAfter, stopAfter, lockStart, lockEnd,
            minStitchLengthMm, minJumpLengthMm, forceLockStitches)
StitchPlan(colorBlocks)
EmbroideryElement.toStitchGroups(previousStitch)
StitchPlan -> 기존 EmbPattern/PES 어댑터
```

연결 위치는 `inkstitch.elements.SatinColumn.generateLayers()`다.
`SatinLayer(points, tags, stitchTags)`는 **기하 중간 결과**이며 공통 StitchGroup을
대체하지 않는다. 바늘별 `satin_column_edge`, `satin_split_stitch`, `peak_a/peak_b` 태그와
레이어별 `satin_center_walk`, `satin_contour_underlay`, `satin_zigzag_underlay`를 유지한다.
공통 어댑터는 같은 색의 언더레이/본 땀을 합치고 필요한 러닝 연결을 만든 뒤,
공통 그룹의 잠금·최소 땀·트림·점프 정책을 한 번 적용해야 한다.
기존 앱 연결용 `elements.InkSatin`과 `stitch_plan.StitchPlanFinisher`는 이전 실기 불량
대응의 임시 호환 경계다. 공통 계약이 main에 들어오면 이 경계를 교체한다.

### 폰트 파서 연결 예

```kotlin
val layers = SatinColumn.generateFromSubpaths(
    subpaths = flattenedPathsInMm,
    parameters = SatinParameters(zigzag_spacing_mm = 0.4),
    nodePaths = originalBezierEndpointsInMm,
)
// layers -> 공통 StitchGroup(color=SVG stroke color, stitches=points+tags, ...)
```

SVG의 path들을 임의 순서로 받을 수 있다. `rail_indices` 방식으로 레일을 찾으며,
렁이 없으면 원래 Bézier 끝점으로 합성한다. **flattening으로 추가한 점을 원래 노드로
전달하지 않는다.** 직접 레일·렁을 알고 있으면 `generateLayers(left, right, ..., rungs)`를 쓴다.
`previousStitch`, `nextStitch`, 명시적 `startPoint/endPoint`도 mm다.
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
max stitch 없음, center/contour/zigzag 언더레이 꺼짐, 각 러닝 길이 3mm,
center repeats 2, contour inset 0.4mm, zigzag spacing 3mm다.
랜덤 split의 jitter는 최대 100%, 위상·폭·간격 난수는 원본의 BLAKE2s 카운터 스트림을 쓴다.
BLAKE2s는 [RFC 7693 §§2–3](https://www.rfc-editor.org/rfc/rfc7693.html)의 독립 Kotlin 구현이며
추가 의존성을 설치하지 않았다. RFC `abc`와 표준 hashlib 다중 블록/UTF-8 벡터로 대조한다.

## 차이점 — 숨기지 않는 원본/앱 경계

| 위치 | 차이 | 이유/영향 |
|---|---|---|
| SVG 입력 | 코어는 변환된 mm 폴리라인과 원래 노드 목록을 받음 | Inkex의 path parsing/flattening은 canvas 담당. Bézier 엔진을 중복 구현하지 않음 |
| 기하 | Shapely 대신 기존 JTS 1.20.0, 1e-5 SVG px precision snap은 아직 없음 | 교차·project·substring·offset의 수치 tie-break가 다를 수 있음. 바이트 동일성 미검증 |
| 렁 | 다중 교차·교차 렁을 무시하지 않고 예외로 거절 | 방향 제약을 몰래 버려 부채꼴 경로를 생성하지 않기 위한 보수적 차이 |
| 불량 입력 | 점/명령 수 예산 초과·비유한 값은 거절 | 모바일 자원 제한; 원본의 무제한 크기 지원을 주장하지 않음 |
| raw 코어 최소 길이 | `min_stitch_length_mm=0.1`은 random split 끝점 필터에 사용; 전체 최소 땀 필터는 아직 적용하지 않음 | 원본도 그룹→StitchPlan 경계에서 전체 필터를 적용. worker 계약 소유권 유지 |
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
- `SatinFixtureTool`: 저장소의 공개 합성 SVG에서 7종의 PES/명시적 축 매트릭스 생성.
  `MYEMBRO_SATIN_FIXTURES=/tmp/... ./gradlew :engine:test --tests '*SatinFixtureTool'`.
- `BirdnestRegressionTest`: 공개 OFL Dancing Script Bold, `Jung Min`, 34mm, 40wt.
  검증되지 않은 SATIN 요청도 auto-fill과 동일한 출력으로 fallback함을 검사한다.
  0.3mm 미만 땀 0, 단일 위상 셀 최대 ≤8, 끝 잠금, PES v1/v6 왕복을 검사한다.
- 전체 자체 회귀: 엔진 **289개**, 앱 **167개** 성공, `:app:assembleDebug` 성공.
- 감사 203개 중 200개 생성, 3개 과밀 차단. 생성된 결과의 0.3mm 미만 땀은 0개,
  단일 위상 셀 최대 8회. `Jung Min`은 5,389땀·셀 최대 7회. 이것은 **자체 회귀 수치**다.
- 비교 PNG: `/tmp/myembro-birdnest/before.png`, `after-final.png`.
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
