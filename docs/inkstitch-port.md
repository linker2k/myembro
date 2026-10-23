# Ink/Stitch 포팅 계약과 출처

## 고정 원본

- 저장소: https://github.com/inkstitch/inkstitch
- 안정 태그 **v3.3.0**, 커밋 **b0edd96311ece82ee48816dc93466274b12c2a9c**.
  annotated tag object: `edcd8a4db180eced204a584db5ff20ba77b4fc5a`.
- 2026-10-24 `git ls-remote --tags`에서 안정 버전 중 최신임을 확인했다.
  공용 읽기 전용 체크아웃 `/tmp/myembro-inkstitch-v3.3.0`을 사용한다.
- 다른 담당자의 이전 실험에 사용된 main 커밋 `16e1928…`과 혼동하지 않는다.
  이번 안정 버전 포팅은 위 태그를 기준으로 한다.
- 원본 Python/Ink/Stitch/Inkscape 실행·의존성 설치를 하지 않는다. 원본은 읽기만 한다.
- 라이선스 GPL-3.0-or-later. 전사한 속성 이름·기본값도 해당 Kotlin 파일 머리에
  원본 파일/커밋/저작권을 남긴다. 이미지 벡터화는 독자 구현이며 Potrace 코드는 사용하지 않는다.

## 공유 계약 초안 — StitchPlan 소유자 embro-worker

아래는 사용자 지정 초안이다. worker의 main 구현이 들어오면 실제 타입을 우선하며,
다른 담당자가 병렬로 같은 클래스를 정의하지 않는다.

- 패키지 `io.github.linker2k.myembro.engine.inkstitch.*`.
- `stitch_plan.Stitch(x, y, tags)` — trim/stop/jump/lock 등 태그.
- `StitchGroup(color, stitches, trimAfter, stopAfter, lockStart, lockEnd,
  minStitchLengthMm, minJumpLengthMm, forceLockStitches)`.
- `StitchPlan(colorBlocks)`.
- `elements.EmbroideryElement.toStitchGroups(previousStitch)`.
- 기존 `EmbPattern` / PES 쓰기로의 어댑터.
- SVG 길이 파라미터는 이름의 `_mm`대로 mm. worker 작업 중인 계약을 읽어 확인한 공통 Stitch 좌표는 **SVG px (96dpi)**다.
  최종 생성자 시그니처는 worker의 main 구현을 우선한다. 기존 엔진 geometry/model의 좌표는 **0.1mm**이므로
  경계에서만 `native / 10 × 96 / 25.4`로 변환한다. SVG 문서의 mm viewBox를
  읽을 때는 문서 변환을 적용하며 숫자만 px로 해석하면 안 된다. 이미지 파이프라인이 임의의 호환 Stitch 타입을 만들지 않는다.

## 이미지 전처리 넘김

`svg/image/ImagePipeline.trace()`는 `ImageEmbroideryDraft`를 반환한다.
각 `ImageElement`는 fill 영역 또는 running 중심선 또는 satin의 두 레일과
`inkstitch:*` 파라미터를 갖는다. `toElements(factory)`는 실제 elements 생성자로 연결하는
훅이며, `toSvg()`는 mm 단위 viewBox를 갖는 독립 SVG 넘김 경로다.
공유 elements가 main에 들어오기 전에는 자체 스티치 생성기를 새 계약인 것처럼 노출하지 않는다.

| Kotlin | 원본 파일 (위 커밋) | 범위 |
|---|---|---|
| `svg/image/ImageElements.kt` | `lib/elements/fill_stitch.py` | `fill_method=tatami_fill`, `row_spacing_mm=0.25`, `max_stitch_length_mm=4.0`, `staggers=4`, `angle=0` 이름/기본값 |
| 같은 파일 | `lib/elements/stroke.py` | `stroke_method=running_stitch`, `running_stitch_length_mm=2.5` |
| 같은 파일 | `lib/elements/satin_column.py` | `satin_column=true`, `zigzag_spacing_mm=0.4`, `running_stitch_length_mm=2.5` |
| `ImageQuantizer.kt`, `BitmapVectorizer.kt` | 해당 없음 | 원본에 없는 독자 이미지 전처리 |

## 차이점

| 부분 | 차이와 이유 |
|---|---|
| 이미지 → 벡터 | Ink/Stitch에 없는 앞단. Inkscape Trace Bitmap/Potrace의 소스 포팅 또는 출력 동등성을 주장하지 않는다. 결정적 median cut + 픽셀 셀 경계 추적이다. |
| 색 | 5bit/channel 히스토그램에 원래 8bit 채널 합을 저장해 메모리를 제한한다. CIELAB ΔE76로 선택한 실 색표에 매칭한다. 실물 발색/기계 색표의 정확한 색 일치를 보증하지 않는다. |
| 잔 영역 | 실 매칭 후 4방향 연결 성분의 실제 mm² 면적을 기준으로 제거한다. 주변색으로 덮지 않고 투명으로 제거하며 자동 배경 제거는 하지 않는다. |
| 단순화 | Shapely 대신 기존 JTS 1.20.0 CoverageSimplifier. 공유 경계와 구멍/성분 수를 보존하고 검증 실패 시 정확한 셀 경계로 되돌린다. Bezier fitting은 하지 않는다. |
| 자동 satin/running | 구멍 없는 직선형 영역만 자동 승격한다. 최소 회전 사각형 면적 점유율 ≥98%, 종횡비 ≥3, 중심선/두 레일 전체가 영역에 덮여야 한다. 곡선·분기·불명확 영역은 fill + 검토 알림. 잘못된 레일로 봉제 품질을 떨어뜨리지 않기 위한 1차 범위다. |
| 이미지 설정 | 색 수·최소 면적·분류 폭 등은 새 앱 정책이며 `inkstitch:*` 속성인 척하지 않는다. 실제 SVG 자수 속성 이름·기본값·단위는 위 원본 그대로다. |
| PES 검증 fixture | 공유 elements 도착 전 기존 엔진 digitizing으로 시험 출력한다. 이 출력은 전처리 연결 회귀용이며 Ink/Stitch 동등성 또는 새 계약 통합 완료의 증거가 아니다. |

## 의존성과 검증

새 의존성 없음. 기존 JTS core 1.20.0의 **EDL-1.0** 선택과 라이선스 전문은
`THIRD_PARTY_NOTICES.md` 및 `engine/src/main/resources/licenses/jts/`에 있다.
networkx/Potrace/네이티브 바이너리는 추가하지 않는다.

단위 테스트와 고정 SVG 입력 → 래스터 → 벡터 → 시험 PES 생성은
`docs/image-pipeline.md`를 참조한다. 독립 `/tmp/myembro-quality-gate/check-release`
출력과 Codex 판정 전에는 품질 통과 또는 병합 가능을 주장하지 않는다.
