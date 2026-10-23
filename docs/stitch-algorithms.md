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
| `InkLock.kt` | `lib/stitch_plan/lock_stitch.py` | `RelativeLock`, `AbsoluteLock`, `SVGLock`, `half_stitch`, `back_forth`, `LOCK_DEFAULTS`의 고정 SVG 끝점 |
| `StitchPlanFinisher.kt` | `lib/stitch_plan/stitch_plan.py`, `color_block.py` | `stitch_groups_to_stitch_plan`, `filter_duplicate_stitches` |

원본 링크 기준 경로:
`https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/`

## 명시적인 앱 정책과 차이

- **외곽선 기본은 auto-fill + 윤곽 러닝**이다. 이전의 TTF 래스터 중심선 추정 새틴은
  생성 경로에서 제거했다. `OutlineColumns`는 과거 실험 API이며 새틴 생성에 쓰지 않는다.
- 외곽선에서 SATIN을 요청해도 구멍이 없고, 회전 최소 사각형과 면적 차이가 0.5% 이내이며,
  폭 ≥0.6mm, 길이 ≥폭의 1.5배인 기둥만 허용한다. 나머지는 경고와 함께 auto-fill이다.
  이 판정은 앱의 보수적 허용 정책이며, Ink/Stitch의 TTF 자동 새틴 기능을 포팅한 것이 아니다.
- 명시적 두 레일/렁 새틴은 원본처럼 10점 비교로 레일 방향을 맞추고, 렁 사이에서 양쪽
  구간을 함께 보간한다. 간격은 직전 횡단 땀의 법선 방향에서 긴 쪽을 기준으로 최대 두 번
  보정한다. 원본의 sawtooth short-stitch 기본 인셋은 폭의 15%다.
  앱은 원본 0.25mm 대신 0.30mm 근접 기준을 사용한다.
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
- 잠금 종류는 HALF_STITCH, BACK_FORTH, ARROW/BOWTIE/CROSS/STAR/SIMPLE/TRIANGLE/ZIGZAG와
  앱의 COMPACT를 제공한다. 원본 기본은 `half_stitch = "0 0.5 1 0.5 0"`이며, 방향 길이는
  0.5–1.5mm로 제한한다. 앱 기본은 원본 Custom/AbsoluteLock 경로 **`"1 -1"`**, 0.40mm다.
  시작과 끝에서 앞뒤 **2땀**으로 잠그고, 처음의 무이동 바늘 명령은 제거한다.
  다른 잠금 형태에도 동일한 최종 최소 길이·과밀 검사가 적용된다.
- 원본 최소 땀 기본 0.10mm 대신 **정수 양자화 뒤 0.30mm**를 사용하며 잠금 땀과 JUMP
  직후에도 예외를 두지 않는다. 짧은 모서리의 바늘 명령은 제거하되 위치는 needle-up으로
  유지한다. 좌표 자체까지 없애 홀을 가로지르는 새 대각선을 만들지 않기 위한 안전 차이다.
- 원본처럼 최초 시작, TRIM/색 변경/STOP 뒤, 3mm를 넘는 분리 이동에서 잠금을 적용한다.
  tie-off가 TRIM/색 변경보다 먼저다. 일반 JUMP에 자동 TRIM을 추정하지 않는다.
  SE400에서 TRIM 요청이 실제 절단되는지는 기계 기능/설정의 별도 문제다.
- 모든 machine-pattern 생성은 공통 후처리를 거친다. 좌표 변환/배치 후에는 잠금 길이를
  다시 생성한다. **가져온 PES/StitchObject와 범용 PES writer는 변경하지 않는다.**

## 과밀 기준과 embro-model 공유 기록

main의 최신 sew-out 동작은 [sewout-check.md](sewout-check.md)가 우선한다.
아래는 레터링 생성 경계에서 사용하는 보수적 기준과 공유 제안 이력이다.
이 기준은 제조사 보증값이 아닌 앱의 보수적 **휴리스틱**이다. 실물 봉제 유효성은 미확인이다.

1. 좌표 단위는 0.1mm, 셀은 0.5mm. 셀 키는 `(floor(x/5), floor(y/5))`다.
   STITCH만 바늘 찌름으로 세며 JUMP/TRIM은 제외한다. 음수도 floor를 사용한다.
2. 한 생성 객체의 **전체 누적 셀 8회 초과** 또는 같은 셀 **연속 6회 초과**면 생성 차단한다.
   바늘 명령을 임의로 지워 구멍 수만 줄이는 방식으로 통과시키지 않는다.
3. 최초 시작과 TRIM/색 변경/STOP 이후 각각 첫 **20개 STITCH**를 따로 분석한다.
   `StitchPlanFinisher.starts(pattern)`은 시작/끝 command index, 실제 바늘 수,
   셀 최대, 동일 좌표 최대, 0.3mm 미만 개수를 반환한다. JUMP는 20개에 포함하지 않는다.
4. sew-out check에는 `START_CONCENTRATION` 경고를 추가하는 것을 권장한다.
   첫 20개 중 셀 8회 초과 또는 동일 좌표 4회 초과, 0길이/초단 땀 발생을 highlight한다.
   정상 잠금 2–4회가 있으므로 “같은 점 2회”만으로 새둥지라고 단정하면 안 된다.
5. 객체·글자 경계가 없는 PES에서는 재시작 창과 **전체 도안 누적 셀 검사**를 함께 쓴다.
   최초 사용자 파일은 연속 셀 최대 3회였지만 누적 셀 38회였다. 연속 검사만으로 놓친다.
6. 셀 위상에 따라 숫자가 달라지므로 결과/보고서에 기준 원점과 셀 크기를 남긴다.
   프로젝트의 객체 겹침은 개별 생성 검사를 통과해도 누적이 늘 수 있다. 범용 sew-out
   엔진이 최종 PES/Project에서 별도로 검사해야 한다.
7. 이 API는 안전 확정 판정이 아니다. 장력·윗실 끼우기·바늘·원단·심지 등 기계 조건은
   파일만으로 배제할 수 없다. 원점의 needle-up 이동을 실제 바늘 찌름으로 세지 않는다.

## 검증 방식

원본을 실행하지 않는 조건에 따라, 원본 수식과 고정 정의를 읽어 옮긴 **수기/해석적 골든**,
별도 기하 불변식, 전체 회귀, PES v1/v6 왕복을 사용한다. Ink/Stitch 실행 결과와의
자동 차분이나 SE400 실물 봉제 검증을 완료했다고 주장하지 않는다.

- 러닝의 직선/곡선 오차·45° 모서리·최대 길이, 빈의 앞–뒤–앞 좌표.
- 잠금 형태의 고정 좌표, 트림 전후 순서, 최소 땀, 배치/확대 후 잠금 길이.
- 반전 레일·명시적 렁·교차 렁 거절·새틴 밀도·pull compensation·세 종류 언더레이.
- auto-fill의 홀/분리 영역·다각도·채움 줄·경로 기하·반복 생성 결정성.
- Dancing Script Bold(OFL, 고정 SHA-256) `Jung Min` 34mm, 40wt와 한글/영문 10–30mm.
- 개인 파일·추출 좌표·렌더링·감사 덤프는 `/tmp/myembro-birdnest`에만 둔다.
  저장소에는 개인 PES/PNG/개인 파일로 만든 테스트 픽스처를 넣지 않는다.
