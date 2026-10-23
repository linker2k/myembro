# 스티치 알고리즘 포팅과 검증

## 원본과 라이선스

- 원본: [Ink/Stitch](https://github.com/inkstitch/inkstitch), 고정 커밋
  `b0edd96311ece82ee48816dc93466274b12c2a9c` (안정 태그 v3.3.0).
- `LICENSE`의 GPL v3 전문과 `lib/stitches/running_stitch.py`,
  `lib/stitch_plan/stitch_plan.py`의 **GNU GPL version 3.0 or later** 표시를 직접 확인했다.
  라이선스 전문 사본은 `engine/src/main/resources/licenses/inkstitch/LICENSE`에 있다.
  원본의 Authors/copyright 표시와 파일·함수·커밋은 각 포팅 파일 주석에도 남겼다.
- 원본 저장소는 `/tmp`에서 **읽기만** 했다. 원본 Python 실행, 의존성 설치,
  Inkscape/Ink/Stitch 실행, 원본으로 골든 생성은 하지 않았다.
- Kotlin은 기존 JTS를 Shapely 대용으로 사용한다. NetworkX 런타임은 포함하지 않는다.
  원본 Python 패키지 전체나 사용자 PES는 번들하지 않는다.

## 범위와 추적표

새틴의 정식 계약·진행 상태·차이점은 [inkstitch-port.md](inkstitch-port.md)가 우선한다.
이 문서는 기존 앱 안전 경계와 결정적 auto-fill 초안을 기록한다. 원본 프로젝트의 모든 옵션과 바이트 단위
출력 동등성을 주장하지 않는다. 임의 난수·jitter, gradient/gap fill, 사용자 SVG 잠금
편집기, 원본의 명시적 시작/끝 지점 UI는 이번 API에 노출하지 않는다.

| Kotlin | 고정 커밋의 원본 파일 | 대상 함수/클래스 |
|---|---|---|
| `inkstitch/stitches/InkRunning.kt` | `lib/stitches/running_stitch.py` | `AngleInterval`, `cut_segment_with_angle`, `cut_segment_with_circle`, `take_stitch`, `path_to_curves`, `stitch_curve_evenly`, `even_running_stitch`, `bean_stitch`, `split_segment_even_n/dist` |
| `inkstitch/elements/SatinColumn.kt` | `lib/elements/satin_column.py`, `lib/utils/geometry.py` | 새틴 전체 추적표는 inkstitch-port.md 참고 |
| `InkAutoFill.kt` | `lib/stitches/fill.py` | `intersect_region_with_grating`, `row_num`, `adjust_stagger`, `stitch_row` |
| `InkAutoFill.kt` | `lib/stitches/tatami_fill.py` | `build_fill_stitch_graph`, `tag_nodes_with_outline_and_projection`, `add_edges_between_outline_nodes`, `graph_make_valid`, `find_stitch_path`, `pick_edge`, `collapse_sequential_outline_edges` |
| `InkAutoFill.kt` | `lib/stitches/tatami_fill.py` | `build_travel_edges`, `process_travel_edges`, `travel`, `path_to_stitches` |
| `OutlineFill.kt` | `lib/elements/fill_stitch.py` | `fill_underlay_angle` / `fill_underlay_row_spacing` (교차 90°, 본 땀 간격의 3배) |
| `LockStitches.kt` (S1 worker) | `lib/stitch_plan/lock_stitch.py` | `RelativeLock`, `AbsoluteLock`, `SVGLock`, `LOCK_DEFAULTS` |
| `StitchPlanBuilder.kt`, `GeneratedPatternProcessor.kt` (S1 worker) | `lib/stitch_plan/stitch_plan.py`, `color_block.py` | `stitch_groups_to_stitch_plan`, `filter_duplicate_stitches`, 기존 0.1mm 패턴 어댑터 |

원본 링크 기준 경로:
`https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/`

## 명시적인 앱 정책과 차이

- **외곽선 기본은 auto-fill + 윤곽 러닝**이다. 이전의 TTF 래스터 중심선 추정 새틴은
  생성 경로에서 제거했다. `OutlineColumns`는 과거 실험 API이며 새틴 생성에 쓰지 않는다.
- TTF에서 SATIN을 요청하면 사각형도 포함해 auto-fill로 전환하고 경고한다.
  별도로 검증된 명시적 레일/렁만 새틴 코어에 전달한다.
- 명시적 두 레일/렁 새틴은 원본처럼 10점 비교로 레일 방향을 맞추고, 렁 사이에서 양쪽
  구간을 함께 보간한다. 간격은 직전 횡단 땀의 법선 방향에서 긴 쪽을 기준으로 최대 두 번
  보정한다. 원본의 sawtooth short-stitch 기본 인셋은 폭의 15%다.
  새틴 기본 근접 기준은 원본과 같은 0.25mm다.
- pull compensation은 새틴 양쪽 레일에 mm로 적용한다. 채우기의 보정은 기존 앱의
  바깥 경계 버퍼 + 원래 홀 보존 정책을 유지한다. 원본의 그레이팅 재구성 기반 fill 보정과
  같다고 주장하지 않는다. center-walk/contour/zigzag 언더레이는 API로 지원한다.
- auto-fill은 원점 정렬 그레이팅, 4단 엇갈림, 외곽/홀 경계 그래프와 역방향 Hierholzer
  경로를 사용한다. 내부 이동은 +45°/−45°/90° 그리드, 경계 가중치 3배, 이미 채운 줄을
  가로지르는 내부 경로 제거를 적용한다. JTS가 교차점을 noding한다.
  원본의 내부 이동 smoothing은 사용하지 않고, 선분이 실제 영역 안에 있는 경로를 유지한다.
- `graph_make_valid`의 `networkx.eulerize`가 푸는 **최소 간선 수 홀수 정점 매칭**은
  정확 부분집합 DP로 푼다. 탐욕 매칭을 대신 쓰지 않는다. 홀수 정점 18개, 노드 100,000개,
  간선 300,000개, 그레이팅 100,000줄을 넘으면 실패시킨다. 원본의 범용 Blossom 구현이나
  무제한 문제 크기를 포팅한 것은 아니다. 목표 함수는
  [NetworkX 공식 eulerize 설명/소스](https://networkx.org/documentation/stable/_modules/networkx/algorithms/euler.html#eulerize)로 대조했다.
- **b798af7의 임시 `InkLock`/`StitchPlanFinisher`는 제거했다.** S1 `384b259`의
  갱신판 `d4c6776`을 의존 커밋으로 가져와 `GeneratedPatternProcessor`를 사용한다.
  별도 compact 0.4mm·본문/잠금 일괄 0.3mm 강제·셀 8회 생성 예외는 없다.
- 명시적 새틴은 source `StitchGroup` → `fromGroups`를 통해 기본 half_stitch,
  그룹 최소 길이·최소 점프·trim/stop을 처리한다. 전역 최소 길이는 0.1mm다.
  S0과 짧은 source 잠금은 PE-Design 바이트 수열에 맞춰 제거하거나 대체하지 않는다.
- 기존 앱 러닝/빈/채우기는 S1의 native `finish` 어댑터를 쓴다. 이미 샘플링한
  모서리를 유지하며 실제 경로를 따라 half-stitch를 생성한다. 이것은 SVG 원본
  잠금의 벡터 fallback과 다를 수 있다(S1 소유 차이). 임의 JUMP도 기존 생성 경계로 유지한다.
- 새틴 UI는 `SatinParameters`의 41개 source 이름·기본값을 저장한다. 기존 프로젝트의
  legacy `SatinOptions`는 max 7mm 등 저장값을 명시적으로 매핑한다. 신규 선택은 source
  기본값(max 없음)을 쓴다. mm→px는 코어에서 한 번, px→0.1mm는 공통 어댑터에서 한 번이다.
  split 길이는 source float 경로 기준으로, 양자화 뒤에는 최대 약 0.15mm 오차가 가능하다.
- 임의 회전/축척은 S1이 기록한 잠금만 제거 후 다시 계획한다. 정수 격자를 보존하는
  변환은 provenance도 함께 변환한다. **가져온 PES와 범용 PES writer는 변경하지 않는다.**

## 과밀·시작부 관찰과 embro-model 공유 기준

실제 판정은 [sewout-check.md](sewout-check.md)와 embro-codex의 고정 게이트가 소유한다.
`PatternStartAnalysis.starts`는 처음/트림·색 변경·STOP 뒤 첫 20개 STITCH의 셀 최대,
같은 좌표 최대, 0.3mm 미만 수를 읽기만 한다. 출력 바늘을 삭제하거나 생성 차단하지 않는다.
S1의 정상 잠금과 본문을 구분해야 하며 S0/0.28mm 고정 수열을 필수로 해서는 안 된다.

0.5mm 셀은 x/y 각각 0·0.25mm 이동한 네 위상에서 비교한다. 사용자 불량 파일은 한 셀
38회였으나 연속 최대는 3회였으므로 누적 검사가 필요하다. 현재 독립 게이트는 정상 코퍼스
파일 최대의 p99를 올림한 15회를 사용한다. 기존 8회/연속6회 생성 차단은 폐기한 실험 기준이다.
짧은 땀 수는 원시 값과 잠금 제외 값을 함께 보고한다. 일반 PES에는 객체 ID가 없으므로
객체 시작 구간을 아는 생성자가 gate matrix에 `object_start_runs`를 선언한다.

## 검증 방식

원본을 실행하지 않는 조건에 따라, 원본 수식과 고정 정의를 읽어 옮긴 **수기/해석적 골든**,
별도 기하 불변식, 전체 회귀, PES v1/v6 왕복을 사용한다. Ink/Stitch 실행 결과와의
자동 차분이나 SE400 실물 봉제 검증을 완료했다고 주장하지 않는다.

- 러닝의 직선/곡선 오차·45° 모서리·최대 길이, 빈의 앞–뒤–앞 좌표.
- 잠금 형태의 고정 좌표, 트림 전후 순서, 최소 땀, 배치/확대 후 잠금 길이.
- 반전 레일·명시적 렁·원본처럼 각 레일별 렁 정렬·새틴 밀도·pull compensation·세 종류 언더레이.
- auto-fill의 홀/분리 영역·다각도·채움 줄·경로 기하·반복 생성 결정성.
- Dancing Script Bold(OFL, 고정 SHA-256) `Jung Min` 34mm, 40wt와 한글/영문 10–30mm.
- 개인 파일·추출 좌표·렌더링·감사 덤프는 `/tmp/myembro-birdnest`에만 둔다.
  저장소에는 개인 PES/PNG/개인 파일로 만든 테스트 픽스처를 넣지 않는다.
