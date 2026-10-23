# 이미지 → 자수 엔진 기초

상태: 순수 JVM 전처리 구현. 모바일 UI는 설계만 한다. **봉제 품질 게이트 차단 중이며
릴리스/병합 준비 완료가 아니다.** 초기 예상 범위는 4–6시간, 공통 elements/finisher
통합은 각 포팅 담당자의 main 반영에 의존한다.

## 실행 흐름과 단위

1. 호출자가 디코딩/다운샘플링한 straight ARGB `RasterImage`와 최종 가로 mm를 전달한다.
   EXIF/카메라/파일 디코더를 엔진에 넣지 않는다. 픽셀은 정사각형, 세로 mm는 종횡비로 계산한다.
2. population-weighted median cut으로 최대 64색으로 양자화한다. 5bit 히스토그램이
   메모리를 제한하며 중심색 계산에는 원래 8bit 채널 합을 쓴다. 난수·디더링은 없다.
3. 실 색표가 있으면 D65 CIELAB ΔE76 최근접 매칭한다. 카탈로그 메타데이터를 보존하고
   같은 실로 매칭된 색은 합친다. 표가 없으면 원래 양자화 RGB를 가진 실을 반환한다.
4. alpha < threshold 픽셀은 제외한다. 나머지는 명시적 matte RGB(기본 흰색)에 합성한다.
   흰색/모서리색을 자동으로 배경이라고 삭제하지 않는다.
5. 4방향 연결 성분을 찾고 최종 크기에서 최소 면적 mm² 미만인 잔 영역을 삭제한다.
   정확히 최소 면적이면 유지한다. 삭제 위치는 투명으로 두며 다른 색으로 번지지 않는다.
6. 픽셀 중심이 아닌 셀 외곽을 따라 경계를 추적한다. 대각 접촉은 별개 성분으로,
   내부 구멍은 구멍으로 보존한다. 검증된 JTS polygonization을 사용한다.
7. JTS CoverageSimplifier로 모든 색 경계를 함께 단순화한다. 공유 경계 불일치,
   새 겹침, 구멍/성분 소실을 검사해 실패 시 정확한 셀 경계로 되돌린다.
   tolerance는 JTS coverage의 면적 기반 허용치이며 Hausdorff 거리 보장은 아니다.
8. 넓은 면은 fill. 구멍 없는 거의 직사각형(점유율 ≥98%, 종횡비 ≥3) 영역에서
   폭 ≤0.6mm이면 중심선 running, ≤6mm이면 두 레일 satin을 제안한다.
   중심선/레일 전체가 벡터 영역에 덮여야 한다. 곡선·분기·불명확한 기둥은 fill로 유지하고
   검토 알림을 반환한다. 자동 분류는 사용자 확정 전의 제안이다.
9. `ImageEmbroideryDraft`의 영역/중심선/레일과 `inkstitch:*` 속성을 elements 팩토리로
   넘긴다. `toSvg()`도 제공한다. 공유 plan 생성/잠금/단땀 필터/장땀 분할/PES 쓰기는
   후단 담당자의 기능이며 이미지 전처리에 복제하지 않는다.

```kotlin
val draft = ImagePipeline.trace(
    RasterImage(width, height, straightArgb),
    ImageTraceOptions(widthMm = 80.0, colorCount = 6,
        minimumRegionAreaMm2 = 0.5, simplifyToleranceMm = 0.1,
        threadPalette = availableThreads),
)
val svg = draft.toSvg() // SVG width/height in mm; viewBox makes 1 user unit = 1mm.
```

내부 `PolygonRegion`/`Polyline` 좌표는 기존 엔진 단위 **0.1mm**, 원점은 이미지 왼쪽 위,
+y는 아래다. 공유 Ink/Stitch 계약은 **SVG px (96dpi)**다. 어댑터에서는
`native / 10 × 96 / 25.4`를 한 번만 적용한다. `toSvg()`의 mm viewBox를 읽는 경우 문서
변환까지 적용해야 한다. `ImageElement.region.color.thread`에 실 메타데이터가 있고,
SVG 넘김은 RGB 색상을 보존한다.

파이프라인은 부분 결과를 성공으로 반환하지 않는다. 입력 ≤1,048,576픽셀, 각 축 ≤4096,
물리 치수 각 축 ≤1000mm, 살아남은 영역 ≤4096개, 총 경계 edge ≤200,000개를 허용한다.
초과 시 명시적 예외가 나므로 호출자는 다운샘플링 또는 단순화 옵션을 제안한다.
원본 raster/index는 방어 복사한다. Android/AWT/ImageIO는 production 엔진에 없다.
JTS는 기존 EDL-1.0 선택을 재사용하며 새 의존성/Potrace 코드는 없다.

## 테스트와 재현

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*image.*'
```

- 색 양자화/알파/메모리 경계/실 정보 보존 테스트.
- mm² 임계값, 구멍, 대각 접촉, 인접색 공유 경계, 고정 seed 75개 래스터의
  모든 픽셀 중심 포함 관계와 면적을 검사한다.
- 직선/기둥/면의 분류, 잘못된 레일 거부, mm/px 경계, SVG XML/locale 검증.
- `engine/src/test/resources/image-pipeline/`의 원본 SVG 7개를 test-only Java2D로
  래스터화해 production 전처리에 넣는다. engine production은 Java2D를 참조하지 않는다.
- 기존 main의 RunningStitch/TatamiFill/SatinColumn을 **시험용**으로 연결해
  `engine/build/image-pipeline/*.pes`, `.traced.svg`, `.matrix.json`을 생성한다.
  PES v6 읽기/쓰기의 바늘 수·좌표 범위를 대조한다. 이 브리지는 공유 finisher를 대체하지 않는다.
- Ink/Stitch에는 이 이미지 전처리 모듈/대응 tests가 없으므로 원본 실행 골든을 만들지 않는다.
  위 도형/기하 기대값은 독자 회귀 입력이며 Ink/Stitch 동등성 증거가 아니다.

각 매트릭스는 1개 입력/크기/스타일의 전체 조합이다. 게이트의 `fonts` 필드는 SVG fixture ID,
`heights_mm`는 원본 캔버스 높이로 사용하며 글꼴 시험이라고 주장하지 않는다.

```sh
/tmp/myembro-quality-gate/check-release \
  --matrix "$PWD/engine/build/image-pipeline/fill-donut.matrix.json" \
  --json /tmp/myembro-image-gate/fill-donut.report.json
```

2026-10-24 최초 독립 게이트 결과: **0/7 통과, 7/7 BLOCK** (종료 코드 모두 1).
공통으로 시작/끝 잠금 누락. donut fill은 추가로 0.3mm 미만 11.375%, 본문 단땀
11.375%, 0길이 43회로 차단됐다. 원본 로그는 `/tmp/myembro-image-gate/summary.json`에 있다.
검사 도구/임계값을 수정하지 않았다. 단위 테스트 성공을 이 결과 대신 사용하지 않는다.
공유 elements/finisher로 연결한 후 **동일 입력·동일 기본 파라미터로 재검사**하고,
독립 Codex 판정을 받아야 병합할 수 있다. 게이트를 맞추려고 출력을 테스트에서만 고치지 않는다.

## 모바일 차별화 설계 — 구현하지 않음

아래 기능은 엔진 기초와 실제 후단 PES가 게이트를 통과한 뒤 별도 작업으로 진행한다.

| 기능 | 설계 | 품질/동의 기준 |
|---|---|---|
| 카메라 입력 | 시스템 사진 선택/촬영, EXIF 회전 반영, 원근 보정 후 명시적 최종 mm 지정. 큰 사진은 작업용 해상도로 축소하고 원본은 보존. | 자·동전/후프 같은 기준 없이 실제 크기를 추측하지 않는다. 개인 사진을 외부 서버로 보내지 않는다. |
| 자동 배경 제거 | 모서리 seed/색 거리로 연결된 배경 후보 마스크를 제안. 투명도/브러시 복구와 전후 비교 제공. 의미 기반 segmentation은 별도 선택 기능. | 흰색 꽃잎/글자 구멍 등 전경 삭제를 사용자가 확인한다. 확정 전 원본/마스크 모두 유지. |
| 원클릭 단순화 단계 | 섬세/균형/간단의 세 preset을 색 수·최소 면적·경계 허용치 묶음으로 적용. 각 결과에 삭제 면적·색 수·예상 땀 수를 표시. | 같은 사진·크기·설정은 같은 결과. 밀도/잠금/점프 검증을 통과하지 못하면 전송 완료처럼 표시하지 않는다. |
| 후프/실 미리보기 | 선택 기계의 봉제 영역과 실 색표에 맞춰 최종 mm를 정한 후 영역 타입을 조정. 사진/벡터/실 경로 겹쳐 보기. | resize하면 mm² 제거와 폭 분류부터 재실행한다. 이미 생성한 땀을 무조건 확대/축소하지 않는다. |
| 온디바이스 작업 | 취소 가능한 백그라운드 작업과 단계별 진행률, 해상도 예산, 원본과 설정의 초안 저장. | OOM/취소 시 반쪽 프로젝트로 기존 편집 세션을 교체하지 않는다. |
