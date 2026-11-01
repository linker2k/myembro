# 그림 → 땀: 변환 흐름 참고 문서

그림 변환을 고치거나 리뷰하는 개발자용 문서입니다. `main` `c3b8d4f6`(2026-11-01) 기준 코드를 설명합니다.
줄 번호는 바뀔 수 있으니, 번호가 맞지 않으면 적힌 심볼 이름으로 찾으세요. 영문판:
[picture-to-stitches.md](picture-to-stitches.md).

아래에서 쓰는 경로 줄임말:

| 줄임 | 전체 경로 |
|---|---|
| `photo/` | `app/src/main/kotlin/io/github/linker2k/myembro/ui/photo/` |
| `image/` | `engine/src/main/kotlin/io/github/linker2k/myembro/engine/inkstitch/svg/image/` |
| `svg/` | `engine/src/main/kotlin/io/github/linker2k/myembro/engine/inkstitch/svg/` |
| `block/` | `app/src/main/kotlin/io/github/linker2k/myembro/ui/editor/block/` |
| `edit/` | `engine/src/main/kotlin/io/github/linker2k/myembro/engine/edit/` |
| `sewout/` | `engine/src/main/kotlin/io/github/linker2k/myembro/engine/sewout/` |

'미확인'은 기록된 측정, 원본 기본값, 사용자 결정 중 어느 것도 없는 숫자입니다.

관련된 더 자세한 문서: [thread-assignment.md](thread-assignment.md), [small-regions.md](small-regions.md),
[satin-coverage.md](satin-coverage.md), [photo-filters.md](photo-filters.md),
[qa/photo-silhouette-performance.md](qa/photo-silhouette-performance.md),
[qa/color-fill-outline.md](qa/color-fill-outline.md), [image-pipeline.md](image-pipeline.md)(Wu/Potrace 포팅 이력),
[`tools/color-trace/README.md`](../tools/color-trace/README.md).

## 1. 전체 흐름

```
입력 (선택 / 공유 / 열기 / AI import_picture / SVG)
  ├─ SVG ─────────────────────────────► VectorArt → ShapeObject.fromArt → PES → SewoutCheck → 결과
  └─ 래스터: 긴 변 ≤1536px로 디코드, EXIF 방향 적용
       1/3 자르기 (확대 contain..4, 이동)
       2/3 모드
           컬러(기본) ─────► SampleColorRegions (≤1024px, Lab k-means) ─► 색별 Potrace 마스크
           컬러 + 흑백 ────► Wu (세부값으로 정한 ≤512px) ─► Potrace
           실루엣/외곽선 ──► 보정 필터 ─► Wu 2색 또는 Otsu 마스크 ─► Potrace
           → 실 색 배정 (CIEDE2000, 컬러 모드만)
       3/3 땀 (Ink/Stitch 포트) ─► PES ─► SewoutCheck P1–P8 ─► '다음' → 편집기
```

ViewModel은 `photo/PhotoViewModel.kt`입니다. 단계는 `step = 0, 1, 2`(자르기, 모드/미리보기, 결과).
2단계에서 사용자가 무엇이든 바꾸면 진행 중인 작업을 취소하고(`revision++`) `prepare()`를 다시 돌립니다
(`PhotoViewModel.kt:321`). 윤곽 추적은 땀을 만들지 않습니다. 땀은 2단계에서 '다음'을 눌렀을 때만 만듭니다
(`generateResult`, `PhotoViewModel.kt:420`).

## 2. 입력

| 경로 | 진입점 | 비고 |
|---|---|---|
| 그림 선택 | `PhotoViewModel.load(uri)` `:203` | 내용을 직접 판별합니다(`photo/PictureIntake.kt:11`). MIME은 믿지 않습니다. 그림이 아니면 기존 도안 가져오기로 넘깁니다. |
| 공유(SEND / SEND_MULTIPLE), 열기(VIEW) | `photo/ExternalPictureRouting.kt:7` `externalInputUris`, `:20` `routePictureSources` | 스트림이 `data`보다 우선. 그림이 여러 장이면 **마지막** 한 장을 열고, 나머지 파일은 가져오기로 넘깁니다. `MainActivity.kt:387, :414`에서 호출. |
| AI `import_picture` | `app/.../relay/RelayPicture.kt`; `PictureInbox`(`photo/PictureInbox.kt`); `MainActivity.kt:100–104` → `beginAiPicture(colors)` `:180` | PNG/JPEG base64 1,000,000자 이하(`RelayPicture.kt:19`, 근거: 중계 메시지 1 MiB 한도에서 봉투 크기를 뺌). `colors` 2–6, 기본 4(`:21`, 사진 화면 기본값과 같음). 사용자가 사진 화면에서 다시 확인합니다. 가장 최근 요청만 남깁니다(`LatestPictureInbox`). |
| SVG | `loadSvg` `:248` → `generateSvg` `:268` | 자르기와 모드를 건너뜁니다. 한도는 `svg/SvgPictureInput.kt:16–18`: 4788바이트, 요소 14개, 경로 점 202개 이하(근거 `docs/agent/svg-picture.md`). 첫 폭은 이모지 폭(`EmojiViewModel.WIDTH_MM`)을 크기 범위로 자른 값. `VectorArt`를 쓰며, 자동 새틴은 새틴 빈틈 규칙(6.4)을 받습니다. |

래스터 디코드: `decodeUprightPhoto` `:36–59`. 긴 변이 **1536px보다 크면** 2의 거듭제곱 `inSampleSize`로 반씩 줄여,
디코드한 긴 변은 1536px 이하입니다. EXIF 방향 2–8을 적용합니다. 근거: `qa/photo-silhouette-performance.md`에 기록된 기존 디코드 한도.

## 3. 1/3단계 — 자르기

코드: `photo/PhotoCrop.kt`, `photo/PhotoCropDrawing.kt`, `PhotoViewModel.transform` `:301`.

| 항목 | 값 | 경계 | 근거 |
|---|---|---|---|
| 비율 | 기본 후프의 가로 ÷ 세로 | — | `HoopSpec`(한 곳에서만, AGENTS.md P2) |
| 최소 배율 | 원본/후프와 후프/원본 비율 중 작은 값(`PhotoCrop.kt:12–16`) = 'contain': 그림 전체가 들어감 | 포함 | 1보다 작으면 보기 영역이 그림보다 커지고, 여백은 그림 왼쪽 위 픽셀 색으로 채웁니다(`PhotoCropDrawing.kt:11–23`). |
| 최대 배율 | 4배 | 포함 | **미확인**(제품 값, 측정 기록 없음). |
| 이동 | `panX, panY ∈ [-1, 1]`, `|원본 − 보기 영역| / 2`를 곱함(`PhotoCrop.kt:19–27`) | 포함 | contain보다 더 많은 빈 여백이 드러나기 전에 멈춤. |
| 실루엣/외곽선 배경 | 흰색을 먼저 칠한 뒤 모서리 색(`PhotoCropDrawing.kt:15–16`) | — | Redwork는 0번 픽셀을 배경으로 봄. 컬러 모드는 투명도를 유지. |

## 4. 2/3단계 — 모드별 처리

모드 `PhotoMode { SILHOUETTE, OUTLINE, COLOR }`(`PhotoViewModel.kt:61`). `begin()` 후 기본값(`:173–178`):
컬러, 4색, 세부 1, 흑백 끔, 노이즈 끔(컬러)/3(단색), 대비 1.0, 폭 = 후프 최대.

### 4.1 보정 필터 (모든 모드, 추적 전)

`image/PhotoCorrection.kt:11–25` `PhotoCorrections.adjust`; 화면 `photo/PhotoScreen.kt:139–161`.

| 필터 | 값 | 기본 | 비고 |
|---|---|---|---|
| 노이즈(중앙값) | 크기 0, 3, 5, 7 | 컬러 0, 단색 3(`PhotoViewModel.kt:82`) | Pillow 11.3.0 `ImageFilter.MedianFilter` 포트(`image/PillowPhotoFilters.kt`). |
| 대비 | 0.5–2.0배 | 1.0 | Pillow `ImageEnhance.Contrast`. 범위 0.5–2.0은 **미확인**(제품 선택, `PhotoScreen.kt:152` 주석). |
| Otsu(흑백 나눔) | 켬/끔 | 끔 | 실루엣/외곽선만. 컬러에서는 거부(`PhotoViewModel.kt:95`). 흑백 마스크를 바로 추적. |
| 투명도 우회 | — | — | 컬러 입력에 불투명하지 않은 픽셀이 하나라도 있으면 필터를 건너뛰고 안내를 띄움(`PhotoCorrection.kt:16–17`, `PhotoViewModel.kt:349–350`). |

### 4.2 컬러 그림(기본) — 샘플 팩 방식 색 영역

모드 = 컬러, 흑백 끔, 실 질감 끔일 때 씁니다(`PhotoViewModel.kt:339`). 래스터 = 자른 영역을 원본 해상도로,
긴 변이 1024px를 넘을 때만 **줄여서** 1024px 이하로(`photoRegionRaster`, `PhotoCrop.kt:62–66`). 이어서
`ColorDrawingPipeline.traceRegions`(`image/ColorDrawingPipeline.kt:86–92`) → `SampleColorRegions.labels`
(`image/SampleColorRegions.kt:39–86`).

샘플 팩 변환기 `tools/color-trace/png2svg_claude_v2.py`의 `convert()`를 2mm 정리 단계까지 포팅한 것입니다.
변환기 자체의 SVG 다듬기는 앱의 Potrace 경로로 대신합니다.

| 단계 | 코드 | 상수 | 경계 | 근거 |
|---|---|---|---|---|
| 크기 한도 | `:17`, `:41` | `MAX_EDGE = 1024` | 가로, 세로 1..1024 포함 | 팩 원본 PNG가 1024px(cute-art `final-list.json`). |
| 투명도 | `:88–97`, `:76` | — | 알파 0 → 배경(라벨 −1). 반투명은 색 통계를 낼 때 흰 종이 위에 합성 | 변환기 동작. |
| Lab | `:240–252` | sRGB, D65 2° | — | scikit-image 0.25 `rgb2lab`. |
| 표본 | `:99–108` | `SAMPLE_PIXELS = 40000` | n ≤ 40000이면 전부 | 변환기 값. NumPy 난수 대신 Kotlin `Random(1)`. |
| k-means | `:110–135` | `CLUSTERS = 10`, `ITERATIONS = 30`, 가장 먼 점 초기화 | — | 변환기 값. 첫 씨앗 `Random(0)`. |
| 희소 선합침 | `:137–147` | `SPARSE_FRACTION = 0.005` | 표본의 **0.5% 미만**인 군집을 가장 가까운 중심에 합침(같으면 유지) | 사용자가 승인한 변환기 v2 수정. |
| 색 합침 | `:62–71` | `MERGE_DELTA_E = 18.0` | **색 수 ≤ 슬라이더 + 1이고 가장 가까운 쌍의 ΔE ≥ 18**이 될 때까지 가장 가까운 쌍을 합침. 여기 ΔE는 Lab 유클리드(CIE76) | 변환기 값. +1은 흰 배경 자리. |
| 테두리 흰 배경 | `:74–75`, `:149–161` | — | L*가 가장 높은 군집 중 그림 테두리와 4방향으로 이어진 픽셀 → 배경 | 변환기(SciPy `ndi.label` 기본 연결). |
| 작은 조각 | `:77–78`, `:163–212` | `MIN_DIAMETER_MM = 2.0`, `CLEANUP_PASSES = 3` | 4방향 연결 조각의 픽셀 넓이가 **π·(1mm / 픽셀당 mm)² 미만**이면, 도시 블록 거리 2 이내에서 가장 많은 라벨로 다시 칠함(동률 → 가장 낮은 라벨, 배경이 먼저). 한도와 같으면 유지 | 변환기 값. |
| 팔레트 순서 | `:79–85` | — | 넓이 큰 순 | 변환기. |

일치 검증(`tools/color-trace/README.md`, 2026-11-01, 팩 1024px 입력 3개): NumPy 표본을 쓰면 팔레트가 같고
1,048,576개 라벨 중 0개가 다름. 포트 자체 표본을 쓰면 60픽셀 이하가 다름.

색마다 `ColorDrawingPipeline.preview`(`:94–130`)가 라벨 지도에 투명 픽셀 한 줄을 둘러(자른 가장자리에 닿은 색도
추적되게) 흑백 마스크로 `RedworkPipeline.traceMask(..., preserveSmallContours = true)`를 부릅니다
(Potrace `turdsize = 0`, 즉 색칠을 버리지 않음; `small-regions.md`).

### 4.3 컬러 + 흑백 켬 (Wu 경로)

`ColorDrawingPipeline.trace`(`:66–82`). 래스터는 `photoRasterSize(detail, w, h)`
(`image/PhotoPreparationPolicy.kt:11–13`): `min(128 | 192 | 256, ceil(max(가로, 세로)mm / 0.2))`, 즉 픽셀당 최대 0.2mm.
가로, 세로 512 이하 필요(`:68`). 픽셀을 BT.709 회색으로 바꾸고, 테두리 배경을 고르고(`ColorRegionCleanup`),
색 수만큼 Wu 양자화한 뒤 4.2와 같은 색별 Potrace와 실 배정을 합니다. '내 실'이 켜져 있지 않으면 실 후보는 무채색
묶음(`Bt709Grayscale.threads`)입니다.

참고: 컬러 모드에서는 세부 슬라이더가 숨겨지지만(`PhotoScreen.kt:171–198`), 이 경로는 현재 `detail` 값을 그대로
씁니다(기본 1, 또는 실루엣/외곽선에서 마지막으로 고른 값).

### 4.4 실루엣과 외곽선 (Wu / Potrace 경로)

`PhotoViewModel.kt:378–411`. 래스터는 4.3과 같음(128/192/256px). 입력은 두 가지:

- Otsu 켬 → 흑백 마스크로 `RedworkPipeline.traceMask`(`:393–404`).
- 그 외 → `RedworkPipeline.trace`: Wu 2색, 배경 = 0번 픽셀 색. `PHOTO_TOO_COMPLEX`가 아닌
  `IllegalArgumentException`이 나고 대비가 1.0이면 `photoContrastStretch`(채널별 최소/최대, `PhotoPreparationPolicy.kt:17`)로
  **한 번만** 다시 시도(`:406–411`).

Potrace 작은 점 거름: `photoMinimumArea(detail)` = 세부 0/1/2에서 1.0 / 0.5 / 0.25 mm²(`PhotoPreparationPolicy.kt:15`).
픽셀로 `max(2, ceil(mm² / 픽셀²))` 바꾸고, Potrace는 **넓이 > 한도**인 윤곽만 남김(`image/RedworkPipeline.kt:101–102`).
근거: 사진 단순화 기본값(`qa/photo-silhouette-performance.md`), 봉제 안전 규칙이 아님. Potrace 원본 기본값은 `turdsize = 2`.

### 4.5 윤곽 수 한도 (모든 래스터 모드)

`maxContours = 128`(`PhotoViewModel.kt:381, :397`; `ColorDrawingPipeline.MAX_REGION_CONTOURS`, `:34`).
128을 넘으면 땀을 만들기 전에 `PHOTO_TOO_COMPLEX`로 멈추고(`RedworkPipeline.kt:105`, `ColorDrawingPipeline.kt:123`),
'너무 복잡함'과 '세부 낮추기' 버튼을 보여 줍니다(`PhotoViewModel.lowerDetail` `:314`. 컬러 모드에서는 실 질감 단계만 낮춤).
컬러 모드에서는 `max(2, ceil(3.0 mm² / 픽셀²))` 픽셀보다 큰 윤곽만 한도에 셉니다
(`MINIMUM_REGION_AREA_MM2 = 3.0`, `:25`; 이 셈에만 남은 예전 값).
근거: 휴대폰 작업량 한도(`qa/photo-silhouette-performance.md`). 팩 입력 127개를 99.9mm에서 측정: p50 25, p90 99, 최대 275,
128 초과 7/127. 윤곽 275개 그림은 PC JVM에서 143초 걸림(`tools/color-trace/README.md`).

### 4.6 크기

| 항목 | 코드 | 값 | 경계 | 근거 |
|---|---|---|---|---|
| 최소 폭 | `engine/.../geometry/PictureSizePolicy.kt:8` | 20.0mm | 포함 | `PhotoSizeSweep`(합성 그림 8개 × 스타일 2 × 세부 3, 5mm 간격). 봉제 보장이 아님. |
| 최대 그림 폭 | `PhotoPreparationPolicy.kt:8–9` | `floor(min(W − 1, (H − 1)·W/H)) / 10` mm → 100×100 후프에서 99.9mm | 포함 | AGENTS.md P2/P2a(0..단위−1). |
| 슬라이더 | `photoInkWidthRange` `PhotoCrop.kt:47–50` | 슬라이더는 **실제 자수(잉크)** 폭. 틀 폭 = 잉크 폭 ÷ 잉크 비율 | 포함, 0.1mm 단위 | 잉크 비율은 크기와 무관(`:40–44`). 0.05mm 이상 바뀔 때만 다시 추적(`PhotoViewModel.kt:201`). |
| SVG 범위 | `pictureWidthRange` `PictureSizePolicy.kt:11–14` | 20mm .. floor(min(W − 1, (H − 1)/비율)) / 10 | 포함 | P2. |

## 5. 실 색 배정 (컬러 모드)

`engine/.../color/ThreadAssignment.kt:30–48`, `ColorDrawingPipeline.kt:105–107`에서 호출.
전체 명세와 측정: [thread-assignment.md](thread-assignment.md).

- 원본 색마다 넓이 = 픽셀 수 × 픽셀 넓이(mm²).
- 순서: CIE L* **내림차순**(밝은 색부터), 그다음 넓이 내림차순(`:24–25`).
- 색마다 CIEDE2000(kL = kC = kH = 1)으로 **아직 안 쓴** 가장 가까운 후보를 고름. 그래서 원본 색 수가 후보 수보다
  많지 않으면 서로 다른 색이 같은 실을 쓰지 않음. 많으면 나머지는 가장 가까운 실을 다시 쓰고
  `THREAD_CANDIDATES_EXHAUSTED` 경고를 붙임.
- 후보: `MyThreads.candidates()` — '내 실'이 켜져 있으면 가진 실, 아니면 Brother PEC 실 표
  (`engine/.../io/MyThreads.kt:10–11`). 흑백 경로는 무채색 묶음.
- 근거: 사용자 결정 2026-11-01. SVG 127개에서 평균 ΔE00 5.86, 최대 18.93, 합쳐진 쌍 0.
- 2단계 미리보기는 배정된 실 색으로 그림(`Preview.previewPixels`, `:46–49`). 3단계는 필요한 실과
  '내 실에 없음'을 보여 줌(`PhotoScreen.kt:211–216`).

## 6. 3/3단계 — 땀 생성

### 6.1 컬러 그림

`ColorDrawingPipeline.generate`(`:132–225`). 생성기는 모두 Ink/Stitch v3.3.0 포트
(`b0edd96311ece82ee48816dc93466274b12c2a9c`).

| 단계 | 코드 | 규칙 / 값 | 근거 |
|---|---|---|---|
| 격자 위상 | `:144–147` | 자른 영역 왼쪽 위의 0.1mm 반올림 위상만큼 옮겨, 99.9mm가 100.0mm 폭이 되지 않게 함 | `small-regions.md` 회귀 테스트 `(-500,-500)..(499,499)`. |
| 섬 | `:152–162` | 색 층마다 다각형으로 나누고 `PhotoIslandOrder` 순서로 | `docs/photo-island-order.md`. |
| 폭 | `:171` | `StitchTypeRule.widths`(JTS 최대 내접원) | — |
| 종류 | `:174`, `svg/SmallRegionStitches.kt:16–18` | 최대 폭 **1.0mm 미만** → 러닝, 그 외 타타미. 컬러 사진은 새틴을 고르지 않음 | 1.0mm = Ink/Stitch `min_satin_stroke_width`. 사용자 결정 2026-11-01(`small-regions.md`). |
| 타타미 | `:183–184`, `engine/.../inkstitch/elements/DefaultTatami.kt:21–23` | Ink/Stitch `fill_stitch.py` 기본값 + 밑땀. 영역을 `DEFAULT_EXPAND_MM = 0.2`만큼 넓히고 모든 색 영역의 닫힘(closing)으로 자름(`:148`) | 넓힘: 사용자 승인 2026-10-27, 빈틈 8.96 → 5.78%(`:26–29`). |
| 대체 | `:181–187`, `svg/StitchTypeRule.kt:60` | 타타미 → 러닝. 실패하거나 막힌 영역 → 러닝. 서로 다른 0.1mm 위치의 보이는 바늘 2개를 못 만들거나 P4/P5에 걸리는 러닝만 빼고 개수를 셈 | `small-regions.md`. 뺀 개수는 3단계에 표시(`PhotoScreen.kt:224`). |
| 채움 테두리 | `:198–199`, `image/ColorFillOutline.kt:25–26, :33–44` | 타타미 영역마다 넓히기 전 윤곽을 1.2mm 러닝 두 번으로, 두 번째는 반 땀 엇갈림 | 사용자 결정 2026-10-28 / 2026-10-30, `qa/color-fill-outline.md`. |
| 테두리 선택 | `:210`, `ColorFillOutline.kt:69–99` | 도안 전체를 검사해, 막는 문제에 걸린 테두리만 빼고 다시 검사. 채움은 빼지 않음. 뺀 개수는 3단계(`PhotoScreen.kt:225`)와 블럭 편집에 표시 | — |
| 마무리 | `:216` | `GeneratedPatternProcessor.fromGroups(pecJumpTrims, lockAtThreadBoundaries, omitZeroLengthMotions)` — Ink/Stitch `half_stitch` 고정 | AGENTS.md P4, P6. |
| 블럭 데이터 | `PhotoViewModel.kt:444–446`, `edit/ShapeObjectFactory.kt:18–28` | 결과를 수놓은 영역마다 블럭이 하나인 `ShapeObject`로 만듦. 수놓은 영역이 없거나 영역 경로가 없으면 객체를 만들지 않음(블럭 편집 불가) | — |

### 6.2 실루엣과 외곽선

`RedworkPipeline.generate`(`image/RedworkPipeline.kt:133–168`).

- 실루엣: 짝홀 규칙 다각형 → 섬(`PhotoIslandOrder`) → 섬마다 `DefaultTatami`, 바로 뒤에 두 번 도는 테두리,
  같은 테두리 선택(`:184–208`).
- 외곽선: 추적한 고리마다 Ink/Stitch 빈 러닝 `bean_stitch_repeats = 1`(앞/뒤/앞)과 `trim_after`
  (`image/PhotoOutline.kt:51–57`). 고리 순서는 JGraphT `NearestNeighborHeuristicTSP`, 이동 거리가 줄 때만 씀(`:35–49`).
- 맞춤: 최종 정수 범위를 후프 안에 맞춤(`fitPhotoPattern`, `:170–182`).
- 실 한 가지 `0xB5492A`. `ShapeObject`가 없어 이 모드들은 블럭 편집 불가.

### 6.3 실 질감 (컬러, 선택)

실 질감 스위치(`PhotoScreen.kt:188`)를 켜면 3단계는 `ThreadTexturePreview.generate`를 돌리고
(`PhotoViewModel.kt:433–439`) `ThreadTextureOutputGate`로 검사합니다. 이 문서 범위 밖이며 `docs/thread-texture-ui.md` 참고.

### 6.4 SVG 경로: 새틴 빈틈

`VectorArt`(`svg/VectorArt.kt:298`)의 자동 새틴은 자기 바늘로 덮었는지 검사합니다. 바늘에서
`zigzag_spacing_mm/2 + √2·0.05 ≈ 0.270711mm`(허용 거리, 포함)보다 먼 곳이 조금이라도 남으면 그 **테두리가 아닌** 영역만
타타미로 바꿉니다(`svg/StitchTypeRule.kt:60–108`). 테두리(닫힌 선, 또는 구멍이 있는 다각형)는 빈틈이 있어도 새틴을 유지합니다.
근거와 측정: [satin-coverage.md](satin-coverage.md).

### 6.5 진행 표시와 기다리기

- 진행 지점 `regions=i/n;stitches=k`(`ColorDrawingPipeline.kt:166, :207`)가 `PhotoGenerationProgress`로 들어감
  (`photo/PhotoGenerationWait.kt:12–22`).
- 60초(`PHOTO_GENERATION_TIMEOUT_MS = 60_000`, `:5`, `≥` 포함)가 지나면 '1분 더 기다리기 / 그만두기'를 물음
  (`PhotoScreen.kt:103–108`). 기다리면 허용 시간을 `max(경과, 허용) + 60초`로(`:9–10`), 반복 가능.
  근거: 사용자 요청('1분마다 결정').

## 7. 봉제 검사와 넘기기

- 컬러/실루엣/외곽선: `PesWriter.write` → `SewoutCheck.checkPes(..., origin = APP_GENERATED)`(`PhotoViewModel.kt:461–463`).
  SVG: `:278–280`.
- 한도(`sewout/SewoutLimits.kt`): 여유 1단위(`:9`, P2a), 최소 좌표 0(`:12`, P1), 헤더 오차 0(`:13`, P3),
  땀 길이 ≤ 120단위 = 12.0mm(`:14`, P8), 밀도 칸 5단위 = 0.5mm(`:15`), 경고 16 이상(`:17`), 30 초과 막음(`:18`, P7),
  같은 위치 ≤ 2(`:21`, P5), 길이 0 동작 0(`:22`, P4). 규칙과 근거는 AGENTS.md.
- 결과: 통과 / 주의(경고만) / 막힘 칩(`PhotoScreen.kt:204–205`). 검사가 끝나고 통과한 경우에만 PES 바이트를 남김
  (`PhotoViewModel.kt:285`). '다음'(`PhotoScreen.kt:227–233`)은 `ThreadTextureOutputGate.canEdit`일 때 켜지고 편집기를 엽니다.

## 8. 사용자가 바꿀 수 있는 것

### 8.1 그림 화면

| 조작 | 위치 | 범위 | 기본값 | 막히거나 숨는 조건 | 코드 |
|---|---|---|---|---|---|
| 자르기 확대 | 1단계 | contain .. 4배 | 1(cover) | — | `PhotoCrop.kt:12–27` |
| 자르기 이동 | 1단계 | 축마다 −1..1 | 가운데 | — | `PhotoViewModel.kt:304` |
| 모드 | 2단계 | 실루엣 / 외곽선 / 컬러 | 컬러 | — | `PhotoScreen.kt:131–137` |
| 색 수 | 2단계, 컬러 | 2–6, 1씩 | 4(AI: 요청 값) | — | `PhotoScreen.kt:184–186`, `PhotoViewModel.kt:115–118` |
| 흑백 | 2단계, 컬러 | 켬/끔 | 끔 | — | `PhotoScreen.kt:180` |
| 세부 | 2단계, 실루엣/외곽선 | 0 / 1 / 2(128 / 192 / 256px) | 1 | 컬러 모드에서 숨김 | `PhotoScreen.kt:193–198` |
| 노이즈 | 2단계 | 끔 / 약 / 중 / 강(0/3/5/7) | 컬러 끔, 단색 3 | 투명도 우회 시 꺼짐 | `PhotoScreen.kt:141–149` |
| 대비 | 2단계 | 0.5–2.0 | 1.0 | 투명도 우회 시 꺼짐 | `PhotoScreen.kt:150–155` |
| Otsu | 2단계, 단색 | 켬/끔 | 끔 | 컬러에는 없음 | `PhotoScreen.kt:156–160` |
| 실 질감 | 2단계, 컬러 | 켬/끔, 세부 | 끔 | — | `PhotoScreen.kt:188` |
| 자수 폭 | 2단계(래스터), 3단계(SVG) | 20mm .. 후프 최대(100×100에서 99.9mm) | 최대 | 후프가 20mm보다 작으면 범위가 최대값 하나로 줄어듦 | `PhotoScreen.kt:199–201`, 4.6절 |
| 1분 더 기다리기 | 3단계 | 반복 가능 | — | 60초 뒤 나타남 | `PhotoScreen.kt:103–104` |

### 8.2 편집기: 블럭 편집 (컬러 그림, SVG, 이모지)

`block/BlockEditScreen.kt`, `block/BlockApply.kt`, `edit/BlockStitchCapabilities.kt`.
적용할 때 항상 전체 봉제 검사를 돌리고, 막히면 이전 설정을 그대로 둡니다.

| 조작 | 범위 | 기본값 | 막히는 조건 | 코드 |
|---|---|---|---|---|
| 종류 | 채움 / 새틴 / 러닝 | 생성 결과 | 새틴: `SATIN_GEOMETRY_UNSUPPORTED`(새틴 불가 모양), `SATIN_GENERATION_FAILED`, `SATIN_STITCH_TOO_LONG`(현재 크기에서 12.0mm 초과, P8). 러닝: `NO_RUNNING_PATH`. 채움: `EMPTY_FILL_REGION`. 못 쓰는 선택지도 이유와 함께 보임 | `BlockStitchCapabilities.kt:36–46, :77`; `BlockEditScreen.kt:336` |
| 텍스처(채움) | 기본(`tatami_fill`), 무늬(`meander_fill`, 타일 선택), 윤곽(`contour_fill`), 원형(`circular_fill`) | 기본 | 그러데이션은 없음(색 정지점 데이터 필요) | `BlockApply.kt:25–26`; `BlockStitchCapabilities.kt:31` |
| 방향 | 자동 / 수동 각도 0–179°(정수) | 자동 | 러닝에는 없음 | `BlockEditState.kt:40–44`; `BlockApply.kt:51–52` |
| 수동 각도 빗금 | 고른 블럭 위에 그 각도의 얇은 선, 화면 표시만 | — | 자동이나 러닝이면 안 보임 | `BlockEditScreen.kt:116, :270`(`drawHatch`: 선 1dp, 간격 6dp, 실 명도 < 0.5면 흰 선, 아니면 어두운 선) |
| 밀도 | 보통만(줄 간격 0.25mm, Ink/Stitch 기본값) | 보통 | 촘촘/성김 꺼짐: 원본 기본값 없음(**미확인**), 다음 버전 예정 | `BlockApply.kt:45–46` |
| 순서 | 고른 블럭을 앞/뒤로 | 바느질 순서 | — | `BlockEditScreen.kt:124`; `EditorViewModel.applyBlockOrder` `:580` |

블럭 편집을 못 쓰는 경우: 땀만 있는 도안(연 PES), 실루엣/외곽선 결과(`ShapeObject` 없음).

### 8.3 편집기: 그 밖

| 조작 | 하는 일 | 코드 |
|---|---|---|
| 색상 탭 | 쓰인 실을 다른 실로 바꿈(실 표/내 실/hex), 천 색 바꿈. 실행 취소 한 단계 | `ui/editor/EditorColorsPanel.kt:43` |
| 자석 스냅(도구 막대) | 기본 켬. 선택한 것을 **옮길 때만**(크기/회전 제외) 가장자리·가운데가 후프 가운데(0)와 보이는 다른 객체의 최소/최대/가운데에 화면 8dp 안에서 붙음 | `EditorViewModel.kt:245, :614, :672`; `EditorSelection.kt:53–60`; `EditorCanvas.kt:80, :139` |

## 9. 바꿀 수 없는 것과 이유

| 고정값 | 값 | 이유 |
|---|---|---|
| 윤곽 수 한도 | 128 | 휴대폰 작업량. 넘는 그림은 생성에 몇 분이 걸리고 땀 수 한도도 넘었음(4.5절). 넘으면 윤곽을 버리지 않고 땀 만들기 전에 멈춤. |
| 컬러 래스터 | 긴 변 1024px 이하 | 팩 변환기 입력 크기와 같고 일치 검증도 1024px에서 함. 큰 입력은 줄이기만 하고 키우지 않음. |
| 단색 래스터 | 128 / 192 / 256px, 픽셀당 0.2mm 이하 | `qa/photo-silhouette-performance.md`. 사진 원래 해상도로 작업하지 않음. |
| 디코드 | 긴 변 1536px 이하 | 기존 메모리/시간 한도. |
| k-means 10, ΔE 18, 0.5%, 2mm | — | 변환기 값을 그대로 포팅. 일치가 합격 기준이라 바꾸면 일치가 깨짐. |
| 땀 생성기 값 | Ink/Stitch v3.3.0 기본값 | AGENTS.md: 검증된 원본을 기본값 그대로 포팅, 조정 화면 없음. |
| P1–P8 | AGENTS.md | 실제 SE400 실패 사례. 검사가 만들기·저장·보내기를 막음. |
| 최대 폭 | 100mm 후프에서 99.9mm | P2/P2a. |

## 10. 알려진 한계와 미결

| 항목 | 상태 |
|---|---|
| 경계 색의 실 배정 | 밝은 색부터 고르는 방식이라, 더 밝은 색이 가장 가까운 실을 먼저 가져가면 중간 색은 두 번째로 가까운 실을 받을 수 있음. 보고된 사례: 해골 그림에서 남색 실 **21과 23**이 예상과 다르게 골라짐. 입력과 ΔE 값은 저장소에 기록되어 있지 않음(세부는 **미확인**). 규칙을 바꾸기 전에 재현 입력이 필요. |
| 오목 모서리 새틴 틈(왕관) | 왕관의 검은 테두리는 구멍이 있는 다각형이라 빈틈 대체에서 빠지고, 오목 모서리에 작은 틈이 있어도 새틴을 유지함(`satin-coverage.md`). 테두리는 의도된 동작. 수정 결정 없음. |
| 컬러 모드에 세부 슬라이더 없음 | 컬러 영역은 자른 원본(≤1024px)으로 돌림. 숨겨진 `detail`은 흑백 경로에 여전히 영향(4.3절). 보여 줄지 미결. |
| 휴대폰 시간 | 1024px 컬러 영역은 PC JVM에서만(`tools/color-trace/README.md`), 예전 경로는 에뮬레이터에서만 측정. 실제 휴대폰 시간은 **미측정**. |
| 난수 | Kotlin `Random` 표본은 NumPy와 다름: 검증 입력에서 60픽셀 이하, 팔레트 채널 ±1 차이. |
| 최대 배율 4배, 대비 0.5–2.0 | **미확인** 제품 값. |
| 촘촘/성김 밀도 | 근거가 생길 때까지 꺼 둠. |
| 큰 그림 | 팩 그림 127개 중 7개는 99.9mm에서 윤곽 128개를 넘어 '너무 복잡함'으로 거부됨. |

## 11. 바꾼 뒤 확인 방법

단위 테스트(바꿀 때마다 관련 테스트만, 병합할 때 전체 `test lint`):

| 영역 | 테스트 |
|---|---|
| 컬러 영역 | `engine/src/test/.../inkstitch/svg/image/SampleColorRegionsTest.kt` |
| 컬러 파이프라인, 땀 | `.../image/ColorDrawingPipelineTest.kt`, `ColorFillOutlineTest.kt`, `PhotoGenerationProgressTest.kt` |
| 단색 경로 | `.../image/RedworkPipelineTest.kt`, `PhotoCorrectionTest.kt`, `PhotoCorrectionDifferenceTest.kt` |
| 작은 영역, 종류 규칙, 새틴 | `.../inkstitch/svg/SmallRegionStitchesTest.kt`, `StitchTypeRuleTest.kt`, `SatinCoverageTest.kt` |
| 실 | `engine/src/test/.../color/ThreadAssignmentTest.kt` |
| 블럭 편집 | `engine/src/test/.../edit/BlockStitchCapabilitiesTest.kt`, `SmallRegionProjectCompatibilityTest.kt` |
| 봉제 검사 | `engine/src/test/.../sewout/SewoutCheckTest.kt` |
| SVG 입력 | `engine/src/test/.../agent/SvgPictureInputTest.kt`, `SvgPictureToolTest.kt` |
| 앱 쪽 | `app/src/test/.../ui/photo/PhotoCropTest.kt`, `PhotoCropDrawingTest.kt`, `PhotoSizeSweep.kt`, `PhotoGenerationTimeoutTest.kt`, `PhotoGenerationCancellationTest.kt`, `PictureIntakeTest.kt`; `app/src/test/.../relay/RelayPictureTest.kt`, `RelayPictureHandoffTest.kt` |

```sh
./gradlew :engine:test --tests '*SampleColorRegionsTest' --tests '*ColorDrawingPipelineTest'
./gradlew :app:testDebugUnitTest --tests '*Photo*'
./gradlew test lint          # 병합할 때
```

저장소 안 도구:

| 도구 | 용도 |
|---|---|
| `tools/color-trace/`(`reference_labels.py`, `Parity.kt`) | 팩 변환기와 라벨/팔레트 일치 검사. `SampleColorRegions`를 바꾸면 다시 실행. |
| `tools/fill-expand/` | 실제 생성 경로로 채움 넓힘/빈틈 측정. |
| `tools/potrace/`, `tools/wu/` | Potrace, Wu 포트의 원본 기준값 다시 만들기. |
| `./gradlew :engine:photoTiming -PphotoTimingArgs=192,baseline,full` | 단색 경로 시간 측정(`engine/build.gradle.kts:156`). |
| `tools/safety-tests/`, `tools/quality_gate_*.py` | 봉제 검사 회귀 보조. |

리뷰 체크리스트:

1. 새로 넣거나 바꾼 숫자마다 근거(원본 기본값, 측정, 날짜가 있는 사용자 결정)가 코드 주석과 이 문서에 있음.
   없으면 '미확인'으로 적고 규칙으로 쓰지 않음.
2. 경계는 단위와 포함/제외를 적고, 테스트가 n−1 / n / n+1을 확인함.
3. 포팅한 단계가 여전히 원본과 일치함(일치 도구나 원본 기준값을 다시 돌리고 출력을 첨부).
4. 건드린 모든 모드의 PES가 `SewoutCheck`(P1–P8)를 통과함. 나타낼 수 있는 색칠을 지워서 막는 문제를 숨기지 않음.
5. 의도하지 않았다면 실 배정이 그대로임. 바꿨다면 말뭉치에서 ΔE00을 다시 측정.
6. 개인 그림은 테스트나 저장소에 넣지 않음. 로컬 분석은 `/tmp`에서만.
7. 취소: 새 단계가 checkpoint를 불러 `revision`이 바뀌거나 인터럽트되면 멈춤.
8. 규칙이 바뀌면 같은 커밋에서 이 문서(두 언어)를 고침.
