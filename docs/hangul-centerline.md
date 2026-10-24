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
