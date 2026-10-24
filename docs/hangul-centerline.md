# 한글 TTF → 중심선 → 원본 새틴 변환: 착수 전 조사

> **선택 로직만 보류 — 사용자 정정(2026-10-24):** 한글 새틴·공용 중심선 엔진의 원본 포팅은 계획대로 계속한다. Kotlin thinning 이식과 골든 일치 테스트는 `2e2d533`에서 완료했다. 다음 순서는 원본 centerline 경로 추적 → Ink/Stitch convert-to-satin 포트다. 새틴/채우기 자동 선택과 그 폭 측정·분류 로직만 다음 버전으로 미룬다. 앱 연결은 이번 엔진 이식 단위에 포함하지 않는다.

- **원본·라이선스:** 공식 [AutoTrace 0.31.10](https://github.com/autotrace/autotrace/tree/1a232b27818a46a05cd25edec09e2f77c4e9ac01), 커밋 `1a232b27818a46a05cd25edec09e2f77c4e9ac01` 고정; `src/thin-image.c`는 Martin Weber, LGPL-2.1-or-later, `src/{autotrace,fit,curve}.c`는 GPL-2.0-or-later(각 파일 헤더)로 GPL-3 결합 가능하며 프로젝트 전체를 LGPL이라고 표시하지 않는다. README/COPYING/COPYING.LIB와 파일별 고지를 보존하고 헤더 없는 보조 파일의 출처 범위는 구현 전 함께 대조한다.
- **범위·근거:** [Inkscape 연결 코드](https://inkscape.gitlab.io/inkscape/doxygen/inkscape-autotrace_8cpp_source.html)는 `at_splines_new_full` 및 centerline 옵션을 사용한다(동일 라이브러리 계열임을 확인했으며 Inkscape 내장본과 위 태그의 바이트 동일성은 미확인). `src/autotrace.c`의 순서대로 Rosenfeld `thin-image.c` → `pxl-outline.c::find_centerline_pixels` → `fit.c`와 curve/spline/vector 보조 연산을 이식하고, 폭 보존은 원본 `image-proc.c` 거리맵 경로만 조사한다; 관련 8개 C 파일은 `wc -l` 합계 4,225줄, GUI·이미지 코덱·새 알고리즘은 제외한다.
- **원본 비교 계획:** 고정 C 소스를 로컬 작은 호출기로 실행해 동일 흑백 입력 5개(직선·T분기·X교차·고리·배포 허가된 앱 한글 TTF의 굵은 ‘한’ 래스터)를 Kotlin과 공유하고, thinning 마스크·경로/분기/개폐·순서·세그먼트 수와 제어점 float/ULP 차이를 코드로 기록·검사한다; `centerline=true` 외 기본값은 `new_fitting_opts` 그대로이며 `preserve_width=true` 비교는 원본 옵션의 별도 축으로 분리한다. 결과·입력/실행 파일 해시·컴파일 옵션은 `/tmp/myembro-port-out/editor/autotrace/`에 남기고, 이후 PES 단계는 P1–P8(후프 정수 상한 포함)을 실제 바이트 게이트로 검사한다; 이번 단위에서는 원본 실행·의존성 설치·제품 코드 착수 없음.
- **새틴 연결 의존성:** Ink/Stitch v3.3.0 `lib/elements/utils/stroke_to_satin.py::convert_path_to_satin`(GPL-3.0-or-later)을 letter의 원본 포트가 준비되면 재사용한다; 조사한 letter `409769937cff8cff7ff261aa92a05c6e33260205`의 `lettering.SatinColumn.strokeToSatin/rails`는 아직 자체 miter offset이므로 이 경로에 쓰지 않는다. 원본 변환의 입력 `stroke_width`는 상수여서 한글의 가변 획 폭·교차 분기 연결은 미확인 의존성이며, 평균 폭·임의 rail 근사로 메우지 않고 원본 옵션/계약을 먼저 확인한다.
- **예상·다음 단위:** 위 코드 규모 기준 AutoTrace 핵심+원본 비교 6–10시간, letter 원본 변환 준비·연결 확인 추가 2–4시간(합계 8–14시간의 추정, 품질 통과 약속 아님); 다음 20분은 thinning 원본 호출기와 작은 고정 입력 골든 확보를 제안한다. 이후에도 20분마다 명령/테스트 출력과 함께 멈춰 보고하며, 이 문서 단계에서 구현을 시작하지 않는다.

## 단위 1 — 원본 thinning 골든 확보

- 최신 우선순위: 같은 원본 중심선 엔진을 **(A) S4: 굵은 선 bean/가는 선 running**, **(B) 한글: 원본 convert-to-satin** 양쪽에 재사용한다. A는 `bean_stitch_repeats` 등 upstream 파라미터만 쓰며, 굵기 분류 임계값은 실측 전 미확인이다. 이 단위에는 분류·땀 생성 코드를 넣지 않는다.
- `thin_image` 원본을 수정 없이 실행했다. 직선/T/X/고리/공개 Noto Sans KR weight700 ‘한’ 5개 × 1/3채널 = 10개 PGM/PBM과 소스·글꼴·실행 해시를 [테스트 리소스](../engine/src/test/resources/autotrace/thinning/README.md)에 고정했다. 시스템 설치 없이 기존 GLib/GObject 런타임과 동일 버전의 공식 헤더만 `/tmp`에 추출했다.
- 재현: `python3 tools/autotrace/generate-thinning-goldens.py /tmp/myembro-autotrace-reference --glib-prefix /tmp/myembro-autotrace-build/glib --verify` → `COMMITTED_GOLDENS_EXACT 10/10`, `REPEAT 10/10; IDEMPOTENT 10/10; GRAY_RGB 5/5`. 출력 `/tmp/myembro-port-out/editor/autotrace/`; 한글 전경은 1,493→174픽셀. Kotlin/전체 중심선/새틴/PES 품질 통과 주장이 아니다.
- 다음 20분 제안: 원본 thinning Kotlin 이식과 고정 마스크 exact equality 테스트. 이후 분기·경로 추적 원본 이식으로 진행하며, 이번 단위는 골든 확보에서 멈춘다.


## 한글 자동 분기 — 선택 로직만 보류(설계 기록), 2026-10-24

아래 자동 분기는 다음 버전 후보이며 지금 구현하지 않는다. 한글 새틴·중심선 원본 포팅은 계속한다.

한글 전체나 글꼴 전체를 일괄 새틴으로 바꾸지 않는다. **글자 또는 획 영역별로 측정한
국소 폭의 중간값(median, mm)**으로 분기한다. 모서리 때문에 작아지는 최소 폭은
분류 기준으로 쓰지 않는다. 0.9–6.0mm(양 끝 포함)는 기존 `TatamiLettering` 상수와
`/tmp/myembro-quality-gate/auto-rules.md`의 채택 범위를 그대로 사용한다.

| 측정된 영역 | 자동 경로 |
|---|---|
| 선 기반 + median 0.9–6.0mm | 공용 AutoTrace 중심선 → 원본 convert-to-satin → Ink/Stitch 새틴 |
| median >6.0mm 또는 선 기반이 아닌 통통한 형태 | 기존 기본 타타미+바탕 땀 유지 |
| median <0.9mm | 기존 auto-rules의 러닝 경로 유지; 새틴으로 강제하지 않음 |
| 폭 측정/선 기반 판정 미확인 | 새틴 후보로 인증하지 않음; 기존 검증 경로 유지 |

폭 측정은 AutoTrace 원본 거리맵/`preserve_width` 값의 단위·배율과 원래 윤곽을
대조한 뒤 구현한다. 지금은 thinning만 있으므로 median 측정 구현은 아직 없다.
선 기반 판정의 검증 방법도 미확인이며, 자체 종횡비·면적 임계값을 만들지 않는다.
이 median은 **분기 기준**이다. 원본 convert-to-satin의 상수 `stroke_width`와 가변 폭
글자 사이의 입력 계약은 별도 확인하고, median만으로 윤곽을 충실히 복원했다고
주장하지 않는다. 같은 중심선 엔진은 S4 bean/running에도 재사용한다.

## 단위 2 — Kotlin thinning 이식

`AutoTraceThinning.thin(width,height,pixels,planes,backgroundRgb)`를 순수 JVM으로
옮겼다. 512개 원본 삭제표, N/S/W/E 패스 순서, 역순 색 발견, 8비트 scan buffer
절단, 즉시 삭제 순서, 원본 thin1/thin3의 서로 다른 경계 보호를 유지한다.
원본 src/thin-image.c 고정 SHA-256과 기존 골든은 변경하지 않았다.

| 원본과의 경계 차이 | 이유 |
|---|---|
| GLib 메모리·C Pixel 배열 → 호출별 JVM Int/Byte 배열 | 색 비교/바이트 출력은 동일; 외부 런타임 없이 입력 보존 |
| process-global background → 명시적 호출별 background(기본 white) | 원본 골든처럼 매번 배경을 명시하는 계약; 동시 호출/이전 호출 상태 누수 방지 |
| unsigned short/JVM 크기 경계 | 1–4096px 각 변, 최대 1,048,576픽셀, planes 1/3, 길이·RGB 배경 범위 검증; C의 미정의 입력·메모리 오류를 거부 |
| 로그·콜백 | 현재 bitmap thinning API에는 진행률/GLib 오류 콜백이 없고 예외로 잘못된 입력을 거부 |

검증: 기존 C 골든 10개와 **전 픽셀/채널 exact equality**, 입력 보존, 반복·멱등,
배경 반전과 호출 상태 독립성. 추가로 원본 C의 3×3 전체 검정 입력에서 gray 출력
`000/111/000`, RGB 출력 `000/000/110`(1=foreground)을 확보해 경계 분기 회귀로
고정했다. 잘못된 크기/planes/배열 길이/배경도 거부한다. Kotlin 테스트 12개.
재현: `./gradlew :engine:test --tests '*AutoTraceThinningTest'`; 원본 C 추가 출력
`/tmp/myembro-autotrace-edge-p{1,3}.pbm`, 테스트 로그 `/tmp/myembro-autotrace-kotlin-test.log`.

LGPL-2.1-or-later 출처·원문을 엔진 리소스와 APK 고지에 포함했다. 앱·PES에는
미연결이며, 전체 중심선/경로 추적·곡선화·중간값 폭 측정·새틴 변환 완료나
봉제 품질 통과를 주장하지 않는다. 다음 단위는 원본 `find_centerline_pixels`의
작은 입력 호출기/골든과 Kotlin 경로 추적을 분리해 진행할 것을 제안한다.


## 단위 3 — 원본 중심선 픽셀 경로 추적

`src/pxl-outline.c@1a232b27818a46a05cd25edec09e2f77c4e9ac01`의
`find_centerline_pixels`, `find_one_centerline`, 방향 마킹·탐색·인접 수·역방향
경로 결합을 `AutoTraceCenterline.trace`로 이식했다. 시작 방향 E/SE/S/SW,
다음 방향 상대 순서 0/2/6/1/7/3/5, 교차점 보호, 위·왼쪽 목적 픽셀의 strict
`>0` 조건을 원본대로 유지한다. 닫힌 경로는 시작점을 마지막에 중복하지 않는다.
출력은 원본 Cartesian 픽셀 좌표(y 위쪽)이며 PES 좌표가 아니다.

골든은 수정하지 않은 원본 thin-image.c → pxl-outline.c를 C 호출기로 실행했다.
직선/T/X/고리/공개 OFL ‘한’ × gray/RGB = 10개에서 경로 순서·모든 정수 좌표·
색·open/clockwise를 Kotlin 테스트가 exact 비교한다. 입력은 기존 thinning
PGM을 재사용하며 개인 도안이 없다. 원본 고리는 closed 1경로, ‘한’은 6경로다.
GLib는 기존 런타임과 `/tmp` 추출 헤더만 사용하며 시스템 설치는 하지 않는다.

재현: `python3 tools/autotrace/generate-centerline-goldens.py /tmp/myembro-autotrace-reference --glib-prefix /tmp/myembro-autotrace-build/glib --verify`.
결과 `/tmp/myembro-port-out/editor/autotrace/centerline/`; 커밋된
`engine/src/test/resources/autotrace/centerline/manifest.json`에 원본 커밋·소스/호출기/
생성기/입출력 해시·컴파일 명령을 기록했다. `autotrace.c`는 원본 비트맵 함수를
그대로 링크하고, 미사용 I/O/곡선화 함수는 linker gc-sections로 제외했다.

차이: GLib 재할당과 비트맵 대신 호출별 JVM 배열/List를 사용하고 입력을 보존한다.
지원 크기는 기존 thinning과 같은 각 변 1–4096px/총 1,048,576픽셀 이하다.
진행률·취소·로그 콜백은 이 순수 JVM API 범위에서 제공하지 않는다.
헤더에 별도 고지가 없는 pxl-outline.c는 프로젝트 GPL-2.0-or-later 문맥과
Copyright AutoTrace contributors로 고지하고 COPYING 원문을 함께 배포한다.

테스트: `./gradlew :engine:test --tests '*AutoTrace*Test'` (23개, 실패 0).
전체 `./gradlew check` → `BUILD SUCCESSFUL in 41s` (67 actionable tasks),
로그 `/tmp/myembro-autotrace-centerline-check.log`.
앱/PES 미연결; 곡선 근사·폭 측정·새틴 변환·봉제 게이트는 이번 단위가 아니다.
다음 제안: 원본 `fit.c` 중심선 곡선화의 비교 골든부터 작은 단위로 확보하고,
원본 Ink/Stitch convert-to-satin 포트와의 입력 계약을 확인한다.

## letter 인계 단위 1 — 경로 골든 재검증·원본 선→새틴 코어

`codex/hangul-centerline` 최신 **e1d0ce0**에는 thinning뿐 아니라
`find_centerline_pixels` Kotlin 포트도 이미 있었다. 이를 codex/letter에 통합하고
thinning 골든10/10·centerline 골든10/10 exact를 다시 확인했다.
두 테스트 클래스 합계23개(입력 오류/경계 검사 포함) 실패0. 원본 코드를 재실행하지 않았다.

다음으로 Ink/Stitch **v3.3.0 b0edd96311ece82ee48816dc93466274b12c2a9c**의
`lib/elements/utils/stroke_to_satin.py`를 `elements/utils/StrokeToSatin.kt`로 포팅했다:
`convert_path_to_satin/convert_path_to_satins`, `fix_loop`, `path_to_satin`,
`get_scores`, `local_minima`, `generate_rungs`, `_merge`, `set_first_node`.
`lib/extensions/stroke_to_satin.py:filtered_rungs`, `lib/svg/styles.py:get_join_style_args`,
`lib/utils/geometry.py:remove_duplicate_points/roll_linear_ring`도 대응한다.
원본 score101칸·각도제곱/int32·평활 kernel·minima, 4자리 반올림, depth20 이분,
1px endpoint 판정, tangent0.001·렁1.2배·1/2mm 간격은 변경하지 않았다.
자체 miter 오프셋 대신 JTS OffsetCurve를 사용하고 결과 레일·렁을 기존
Ink/Stitch SatinColumn 생성자에 직접 전달한다. 원본 CLI/Inkex UI는 이 API 범위가 아니다.

Shapely LineString.offset_curve 기본 quad_segs16·mitre_limit5를
[공식 API 문서](https://shapely.readthedocs.io/en/2.0.6/reference/shapely.LineString.html#shapely.LineString.offset_curve)와 대조했다.
원본 SVG style에서 miter를 명시한 경우에만 SVG 기본 miter-limit4를 사용하고,
명시하지 않은 경우는 Shapely 기본5를 유지한다. 스타일 코드는 기본 mitre다.

**이번 검증 범위:** StrokeToSatinTest7건(직선 레일/중앙 렁 수치, 90° corner score8100,
miter 좌표, 닫힌 경로 midpoint/분할 병합, node 렁, closed extension 시작점, 렁 교차 필터,
style 기본값; 일부 항목은 같은 테스트 안에 포함). 이는 원본 수식의 분석적 회귀이며
**공식 convert-to-satin 출력 골든과의 동등성은 미판정**이다. 자료 경로를 총괄에 요청했다.
기존 원본 centerline 골든10/10 판정과 이 미판정을 혼동하지 않는다.

차이점/범위:
- Shapely/GEOS는 기존 승인 JTS1.20 OffsetCurve·LengthIndexedLine·intersection으로 대체.
  곡선 join 및 자기교차의 전체 수치 동등성은 공식 출력 비교 전까지 미확인.
- 입력은 SVG px의 명시적 경로와 양수 **상수 strokeWidth**다. 폭 추정·새틴/채우기 선택 없음.
  빈/중복만 있는 경로는 null, 잘못된 폭은 Kotlin 입력 오류, closed-start API는 닫힌 ring을 요구한다.
- 길이1px 미만 경로는 재귀분할해도 모든 자손이 원본 endpoint<1 조건에서 실패하므로
  결과가 항상 비는 재귀를 조기 생략한다. 1px 이상 및 루프의 depth20 규칙은 유지한다.
- JVM List를 반환하며 SVG DOM/CLI 종료·로그·진행률 콜백은 포함하지 않는다.

전체 `./gradlew check`: BUILD SUCCESSFUL in50s. 원본20개 골든·수식7회귀 모두 통과.
JUnit/출력: `/tmp/myembro-port-out/letter/hangul-unit1/output.txt`.
Noto Sans KR400 ‘정민’15mm PES는 아직 만들지 않았다. 원본 `fit.c` 곡선화/
`image-proc.c` 거리맵·preserve_width 계약은 아직 Kotlin에 없다. 단순 SVG 출력
`output-svg.c`는 centerline path에 stroke-width를 따로 기록하지 않으므로, 임의 평균 폭이나
1px 기본폭으로 새틴을 만들지 않는다. 다음 단위는 공식 변환 출력 비교와 원본 곡선·폭 계약을
작게 확인한 뒤 이 명시적 폭 API에 연결하는 것으로 제안한다. 앱/자동 선택은 계속 보류한다.

## letter 인계 단위 2 — Noto400 ‘정민’15mm 연결 준비

곡선 계약을 확인했다. `convert_path_to_satin` 입력은 LineString이므로 기존 원본
픽셀 중심선 경로를 그 순서대로 입력할 수 있다. 새 spline smoothing/자체 곡선 근사는
넣지 않는다. AutoTrace Cartesian y를 SVG y-down으로 바꾸고, 래스터 ink-height를
요청15mm에 대응시키는 좌표 변환만 한다. `fit.c` spline 출력과 동일하다고 주장하지 않는다.

획 폭 계약에는 아직 입력이 하나 필요하다. AutoTrace
`image-proc.c:new_distance_map`/`fit.c:fit_curve_list`
@1a232b27818a46a05cd25edec09e2f77c4e9ac01는 지역 거리와 이웃 최대값을
`z = width * width_weight_factor`로 보존한다. `output-svg.c:out_splines`는
상수 stroke-width를 기록하지 않는다. 반면 Ink/Stitch
`convert_path_to_satin`@b0edd96311ece82ee48816dc93466274b12c2a9c는 상수
stroke_width를 요구한다. 원본에 없는 평균/중간값 폭 매핑은 추가하지 않았고
총괄에게 이번 시험의 상수 폭(mm)을 요청했다. 자동 선택은 계속 보류한다.

`tools/hangul_satin/prepare-raster.py`와 `:engine:hangulSatinSample`을 준비했다.
폰트 기본 weight는100이어서 반드시 variation400을 명시했다. 현재 결과:
- Noto Sans KR400, ‘정민’, ink-height15mm, 원본 font SHA
  `194018e6b2b293a7964f037b25c0249ce1418bc9ab3c971060a03aa57861e252`
- 원본 Kotlin thinning→centerline **9경로**. 입력·정수 경로·PNG는
  `/tmp/myembro-sewtest/krs/input.{json,pgm,png}`, `KRSAT1-centerline.{json,png}`.
- 명시적 폭을 받으면 기존 convert-to-satin→SatinColumn→S1→기본 writer를
  호출하도록 연결했다. 한 색40wt·60×60mm·원점/0길이/헤더 검사 및 P1–P8을
  writer에서 통과한 경우에만 PES를 쓴다. 기존 파일명은 덮어쓰지 않는다.
- **현재 PES 미생성**: 폭 입력 대기이며 `.PES`/decoded-PES `.png`/품질 PASS를
  주장하지 않는다. 기본 trace 모드는 명확히 중심선 PNG만 만든다.

`./gradlew check :engine:hangulSatinSample`: BUILD SUCCESSFUL in44s.
재현 명령은 `tools/hangul_satin/README.md`에 기록했다. 폭 회신 뒤
`-PstrokeWidthMm=<지정값>`으로 실행하면 요청한 `/tmp/myembro-sewtest/krs/KRSAT1.PES`
생성과 실제 바이트 검사·PNG 단계로 이어진다.

## letter 인계 단위 3 — 원본 fit 적용 KRSAT1 생성

총괄이 이번 시험의 상수 획 폭을 **1.13mm**로 확정했다. 근거는 model
GlyphStrokeWidths의 Noto Sans KR400·15mm 실측 median(정1.126, 민1.125mm)이며
자동 선택/일반 글꼴 폭 추정 규칙으로 확장하지 않는다.

시험은 **수정하지 않은 원본 AutoTrace C fit**를 거쳤다. `tools/autotrace/fit_oracle.c`는
thin-image→pxl-outline→`fit.c:fitted_splines`를 호출만 하며 스무딩 알고리즘을 새로
쓰지 않는다. `new_fitting_opts` 기본값에 centerline=TRUE만 설정했다. preserve_width는
기본FALSE 유지(새틴 폭은 별도 확정 상수). curve/spline/vector/epsilon-equal 보조 C도
같은 `1a232b27818a46a05cd25edec09e2f77c4e9ac01`의 원문이다.
**fit의 Kotlin 이식·앱 연결은 아직 완료하지 않았으며**, C 실행은 이 로컬 시험/
향후 비교 골든 생성 전용이다. Ink/Stitch Python은 실행하지 않았고 의존성 설치도 없다.

원본 fit 결과: **9경로·21세그먼트·3차 곡선14개**, 재실행 byte exact.
입력·각 C 파일/호출기/실행파일·결과 SHA와 컴파일 옵션을
`/tmp/myembro-sewtest/krs/fit-provenance.json`에 남겼다.
Kotlin은 원본 line/cubic controls를 읽고 `output-svg.c`의 float y=height-y와
6자리 `%g` 형식 후 기존 SvgPath.flatten의 글꼴 파서 정밀도0.1 SVG px로
평탄화한다. 곡선 subdivision일 뿐 자체 smoothing은 아니다. 원본 fit의 입력/출력
해시를 검사하며, fit.json이 없으면 픽셀 계단 경로로 PES를 만드는 fallback은 금지한다.

**생성물** `/tmp/myembro-sewtest/krs/KRSAT1.PES` (새 이름,2,298bytes),
decoded-PES PNG `KRSAT1.png`, 원본 fit 중심선 `KRSAT1-centerline.png`,
검사 출력 `KRSAT1-check.txt`, gate JSON/text `KRSAT1-gate.{json,txt}`.
폰트 입력 ink-height15mm와 실제 바늘 경계 **26.8×14.4mm**는 별도 보고한다.
627땀·8트림·검정40wt 한 색, 자동 새틴/채우기 분기는 넣지 않았다.

| 실제 바이트 검사 | 결과 |
|---|---|
| P1/P2 원점·정수범위 | min(0,0), max(268,144), HoopSpec100×100mm·시험60×60mm 안 |
| P3 헤더 |268×144, 실제 범위와 정확히 일치 |
| P4 0길이 바늘/JUMP |0개 |
| P5 같은 바늘 연속 |최대1회 |
| P6 시작/끝·트림 전/후 잠금 누락 |모두0개, S1 half_stitch |
| P7 0.5mm 셀 밀집 |최대6회 |
| P8 최대 바늘 길이 |2.4186773245mm |

`quality-gate check --text --style satin` tool v7: **PASS1/1**, 코드/threshold는
변경하지 않았다. 이 자동 코드 판정과 사용자 실기 승인은 별개이며 후자는 대기다.
PES SHA256: `c26ff1bb5fdbee8c46397c2fcaba1800aa27da3b98b9b0d49d13a340fd202e79`.
전체 `./gradlew check` 통과(마지막 run13s). 원본 fit 생성기/재현 명령은
`tools/hangul_satin/README.md` 참조. KRSAT1 재생성으로 덮어쓰지 말고 다음
실기 파일은 새 stem을 사용한다.

## letter 인계 단위 4 — AutoTrace fit Kotlin 이식

`AutoTraceFit.kt`가 같은 고정 AutoTrace0.31.10의 `fit.c` 중심선 기본 경로를 포팅한다.
`split_at_corners/find_corners/find_vectors/remove_adjacent_corners`,
`remove_knee_points`, `filter`, `fit_curve/fit_with_line/fit_with_least_squares`,
`set_initial_parameter_values`, `find_tangent/find_half_tangent`, `fit_one_spline`,
`find_error`, `spline_linear_enough/change_bad_lines`와 `vector.c` float 벡터 연산,
`spline.c:evaluate_spline`을 대응한다. 원본 커밋과 GPL2+FSF 고지는 파일 머리에,
원본 라이선스 전문은 기존 engine 리소스에 보존했다.

기본값: corner_surround4, corner_threshold100°, corner_always_threshold60°,
filter_iterations4, tangent_surround3, error_threshold2px, line_threshold1,
line_reversion_threshold0.01, remove_adjacent_corners=false,
centerline=true, preserve_width=false. 원본 `gfloat` 연산 순서·반올림, 코너 끝 처리,
필터 collapse 취소와 이전 점 상태, 열린 곡선 링크 처리, 비정규화 평균 접선,
재귀 분할 시 공유 접선을 유지한다. 자체 곡선 스무딩이나 폭 추정은 없다.

**골든:** 기존 합성 직선/T/X/고리·OFL Noto ‘한’5입력×1/3채널10건.
`tools/autotrace/generate-fit-goldens.py`는 원본 C를 수정 없이 호출해
[fit 리소스](../engine/src/test/resources/autotrace/fit/README.md)를 생성한다.
경로 수·개폐/방향·세그먼트 순서/차수·모든 제어점 xyz를 float비트 exact로 비교한다.
`AutoTraceFitTest`: **원본 골든10/10 + 빈 입력/잘못된 좌표 검사1건 통과**.
C 골든 재실행도10/10 byte exact. 로컬 ‘정민’15mm는 테스트 픽스처로 넣지 않고
`/tmp/myembro-sewtest/krs/input.pgm`으로 별도 비교: **9경로·21세그먼트·84제어점
float비트 exact** (`/tmp/myembro-port-out/letter/autotrace/fit/local-fit.log`).

시범 CLI는 이제 **Kotlin fit의 결과만** convert-to-satin에 전달한다.
선택적 C fit.json이 있으면 해시/출처 검증과 모든 제어점 비트 일치 검사만 하며
없어도 같은 Kotlin fit을 쓴다. 픽셀 계단 경로 PES fallback은 없다.
`KRFIT1-centerline.png/json`을 로컬로 생성했고 전달된 KRSAT1.PES는 변경하지 않았다.
앱 UI 연결·새틴/채우기 자동 선택·사용자 봉제 승인과는 별개다.

원본 대비 범위/차이:
- 상수 폭 중심선 fit만 지원한다. preserve_width 거리맵/z 폭 피팅과 outline fitting,
  임의 fit 설정·진행/취소 콜백·로그는 이번 API에 포함하지 않는다.
- GLib 할당/링크는 JVM 객체/List로 대체한다. 비정상 빈 경로/음수·ushort 범위 밖
  픽셀은 입력 오류로, 원본에서 무한 반복/분할 실패할 퇴화 접선은 명시 오류로 중단한다.
- 원본10건과 별도 로컬 사례의 exact는 해당 입력에 대한 측정이며 전체 입력 동등성
  또는 선→새틴의 공식 오라클 동등성을 의미하지 않는다.

검증 출력: `/tmp/myembro-port-out/letter/autotrace/fit/verify.log`, `local-fit.log`,
`check.log`. `./gradlew check`: **BUILD SUCCESSFUL in38s**, 엔진650·앱 Debug205/
Release205 테스트 실패0, Lint 통과. 이번 단위는 fit 교체/비교까지이며 새 PES 실기
파일을 추가 생성하지 않았다. 전달된 KRSAT1 SHA256도 이전 기록과 동일하다.
