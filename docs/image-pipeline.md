# 사진 → 자수 S5: 조사와 적용 계획

2026-10-24 개정. 기준: `docs/ui-stories.md`의 **실패 없는 디자인**.
현재는 **S4 통합 검토 브랜치** 단계다. 아래 포팅 단위의 기록은 당시 상태이며, 현재는 단색 레드워크 엔진과 3단계 사진 UI를 연결했다. main 병합은 S2·S3 뒤 총괄이 진행한다.

## 결정

- `codex/editor`의 자체 셀 경계 추적·공유 경계 단순화·직사각형 점유율/폭 기반 분류는
  실험 상태로 동결한다. `e591f3d`, `d2e734b`, `323645f` 전체를 제품 경로로 병합하지 않는다.
- 검증된 공개 구현을 먼저 그대로 적용한다. 함수명만 비슷한 자체 재구현을 원본 포팅으로
  간주하지 않는다. 기존 98% 점유율, 종횡비 3, 0.6/6mm 폭, 0.5mm² 같은 실험값은 재사용하지 않는다.
- 잠금·밀도·바탕 땀·당김 보정·각도·땀 종류 선택 UI를 만들지 않는다. 원본 기본값과
  codex가 근거를 확인한 자동 규칙만 사용한다. 미확인 규칙에 임의 값을 넣지 않는다.
- Ink/Stitch/ImageJ/libimagequant 실행·의존성 설치는 하지 않는다. Potrace는 최신 지시의
  원본 비교를 위해 아래 2차 단위에서 작은 입력 3개만 원본 C로 실행했다. Kotlin 엔진 코드와
  라이선스 전문을 보존하며, 앱에는 연결하지 않는다.

## 사용자 흐름: 3단계만

| 단계 | 사용자 입력 | 자동 처리/결과 |
|---|---|---|
| 1. 고르기·자르기 | 사진 선택, 자르기 | 원본 보존, 방향 반영, 취소 시 기존 편집 작업 보존 |
| 2. 색 수 | 정수 2–6 | 같은 원본/크기/설정은 같은 양자화 결과. 실 색과 도형 미리보기만 제공 |
| 3. 결과 | 편집기로 / 뷰어로 | 실제 땀 수·색 수·통과/차단. 차단 결과는 전송 불가. 위치·크기·색만 편집 가능 |

별도의 "땀 자동 배정" 단계, 디테일 슬라이더, 프리셋, 배경 제거 브러시, 땀 설정은 넣지 않는다.
카메라 보정·배경 제거 등 추가 기능은 이번 S5 최소 범위에 포함하지 않는다.

## 조사한 원본과 라이선스

### 윤곽 추적: Potrace 1.16 우선 후보

[공식 프로젝트](https://potrace.sourceforge.net/)와
[공식 매뉴얼](https://potrace.sourceforge.net/potrace.1.html)은 **GPL-2.0-or-later**를 명시한다.
Peter Selinger 및 원본 기여자 출처를 보존하고 GPL-3.0-or-later 프로젝트에 맞는 배포 조건으로
반입하는 계획이다. 독점판 Potrace Professional은 대상이 아니다.

- 공식 소스: <https://potrace.sourceforge.net/download/1.16/potrace-1.16.tar.gz>
- 읽기 전용 사본: `/tmp/myembro-potrace-1.16-source.tar.gz`
- 다운로드 소스 SHA-256: `be8248a17dedd6ccbaab2fcc45835bb0502d062e40fbded3bc56028ce5eb7acc`
- 중심 파일: `src/decompose.c`(경로 분해), `src/trace.c`(곡선),
  `src/curve.c`, `src/potracelib.c`(기본 파라미터), 관련 헤더.
- `potracelib.c`에서 확인한 기본값:

| 파라미터 | 원본 기본값 | 해석 |
|---|---|---|
| `turdsize` | 2 | 픽셀 수 기준 작은 점 제거. mm²가 아니다 |
| `turnpolicy` | MINORITY | 대각 접촉의 모호성 처리 |
| `alphamax` | 1.0 | 모서리 처리 |
| `opticurve` | 1 | 곡선 최적화 사용 |
| `opttolerance` | 0.2 | 원본 곡선 최적화 허용치. mm가 아니다 |

Potrace 입력은 흑백 비트맵이다. RGB 사진을 그대로 전달하는 컬러 자수 알고리즘은 아니다.
색 양자화 후 색별 마스크를 만들어 추적하는 앞뒤 연결을 별도로 검증해야 한다.
기본값의 픽셀 단위를 임의 mm²/모바일 해상도 값으로 바꾸지 않는다.
원점·y축 변환은 원본 SVG backend와 비교하고, 내부 자수 좌표는 공유 SVG px 계약으로 연결한다.

### 색 양자화 후보 비교

| 후보 | 공식 근거/라이선스 | 판단 |
|---|---|---|
| [libimagequant](https://pngquant.org/lib/) | RGBA→색인 팔레트. 공식 페이지에 GPL v3-or-later 및 추가 저작권 고지, 현재 Rust/C API 안내 | 라이선스가 명시된 우선 비교 후보. 순수 JVM 계약을 유지하려면 코어를 충실히 포팅하고 원본 팔레트/색인과 비교해야 한다. JNI를 임의 도입하지 않는다 |
| [ImageJ 1.x](https://imagej.net/software/imagej/) `ij/process/MedianCut.java` | 프로젝트는 public domain으로 소개됨. 파일은 Heckbert median-cut 및 Anton Kruger의 기존 구현 계보와 AWT 의존을 명시 | JVM 이식 비용 비교용 후보. 파일 계보/고지와 AWT 경계를 추가 확인하기 전 채택하지 않는다. 프로젝트 전체 설명만으로 파일별 조건을 단정하지 않는다 |
| 기존 `ImageQuantizer.kt` | 자체 작성한 median cut/CIELAB 매칭 | 표준 이름만으로 출력 동등성이 입증되지 않는다. 기본 경로에서 제외 |

ImageJ 조사 소스 고정:
[태그 v1.54p / 03382be1a0b650010b077102b5e5b20eaa6a8967](https://github.com/imagej/ImageJ/tree/03382be1a0b650010b077102b5e5b20eaa6a8967),
`ij/process/MedianCut.java` SHA-256
`ec26c65f82e60c9e711bfeefeb2ae2141c4a831fa5d4ea63865623043ddbaf94`.
참조 파일은 `/tmp/myembro-imagej-MedianCut.java`이며 실행하지 않았다.
libimagequant의 채택 버전/커밋은 **아직 미확정**이고, 실제 포팅 착수 전 고정한다.

포팅 시 각 파일에 원본 경로·버전/커밋·저작권을 남기고 `THIRD_PARTY_NOTICES.md`와
배포 라이선스 전문을 함께 갱신한다. 이번 엔진 부분 포팅에도 새 의존성은 추가하지 않는다.

## 구현 경계와 미확인 규칙

1. `:engine`은 순수 JVM 계약을 유지한다. Android 디코딩·카메라·EXIF·UI와 원본 알고리즘을
   분리한다. C/Rust 원본은 참조이며, Kotlin 포팅은 계산/분기/기본값을 그대로 옮긴다.
2. 색 양자화의 팔레트와 색인 맵을 보존한다. 원본의 디더링/알파 규칙도 확인해 고정한다.
   기존 `alpha=128` 임계값·흰색 합성·임의 디더링 제거를 검증 없이 복원하지 않는다.
3. 색별 Potrace 경로에서 구멍·대각 접촉·위상·Bezier를 보존한다. 색별 곡선의 새 겹침/빈틈은
   비교 대상으로 남긴다. 자체 JTS 보정으로 원본 결과를 숨겨서 통과시키지 않는다.
4. 실 색 매칭은 이미 출처가 있는 `PecThreads.distance`(pyembroidery red-mean) 재사용을
   먼저 검토한다. 자체 색 거리/임의 임계값을 추가하지 않는다. 원색·실색·실제 색 수를 비교한다.
5. S2/S4가 검증한 원본 변환과 기본 땀 생성에 넘긴다. 사진 영역의 새틴/러닝 판단에는 글꼴의
   높이 규칙을 무조건 전용하지 않는다. 원본 변환·규칙·게이트가 확보되지 않은 영역을 임의
   레일로 생성하지 않는다. 타타미도 검증된 입력만 허용한다.
6. 너무 작은 조각의 최소 mm², 사진 영역별 타입 결정, 검증된 해상도/크기 범위는 **미확인**.
   codex의 근거/검증 전에는 제품 기본값으로 사용하지 않는다. `turdsize=2px`만으로
   실제 봉제 가능한 최소 면적이 증명되었다고 주장하지 않는다.
7. 편집 결과는 원본 경로·자르기·색 수·실 팔레트·물리 크기·알고리즘 버전을 보존하는 일반
   편집 객체로 넘긴다(S4/model 계약 확정 필요). 크기 변경 때 땀을 다시 생성한다.
   단순 `StitchObject`만 저장해 경로를 잃거나, 가져온 PES처럼 무조건 땀 좌표를 확대하지 않는다.

## 적용 전에 필요한 비교와 단계

- 원본 팔레트/색인 맵과 포팅 결과를 동일 RGB·색 수 2–6으로 비교한다.
- 흑백 사각형·곡선·구멍·대각 접촉·작은 섬·투명 경계 입력을 고정하고 원본 Potrace 경로와
  위상/곡선을 비교한다. 차이 허용 수치는 독립 판정 담당자가 확정한다.
- 이용 조건이 명시된 자체 로고/도형·사용자 허용 사진 2–3개로 end-to-end를 검사한다.
- 땀 생성은 Ink/Stitch v3.3.0 `b0edd96311ece82ee48816dc93466274b12c2a9c`의 기본값을 쓴다.
  기능별 10개 이내 대표 입력과 실제 봉제 실패 기준 게이트로 비교한다. 예전 PE-Design 잠금 바이트
  패턴에 출력을 맞추지 않는다. 과거 실험 PES 7개 BLOCK도 교정 후 판정 근거로 재사용하지 않는다.
- 구현 단계에서 동일 입력 목록·원본 해시·설정·출력 해시를 기록하는 재현 명령을 제공하고
  `/tmp/myembro-port-out/editor/`에 PNG/마스크/경로/PES를 함께 출력한다.
  **현재는 실행 가능한 S5 재현 태스크나 새 PES를 만들지 않았다.**
- 원본 비교 → codex 기능 게이트 → 3단계 UI 스모크 → 알파 → 사용자 실제 봉제 확인 순으로 적용한다.
  검증 전 사진 UI 진입점이나 숨은 기본 경로를 추가하지 않는다.

## 시간 재추정

현재 재생 UI 단순화 + 본 조사/계획 + 스모크: **1–2시간**.
S5 구현은 S2/S4 및 비교용 oracle 준비 뒤의 별도 작업이며, 현재 가정은 다음과 같다.

| 향후 작업 | 순수 JVM 포팅 기준 대략 |
|---|---|
| 양자화 원본 고정·코어 포팅·원본 비교 | 8–16시간 |
| Potrace 원본 포팅·경로/위상 비교 | 16–24시간 |
| 검증된 자수 생성 연결·독립 품질 검증 | 8–16시간 |
| 사진 3단계 최소 UI·모바일 스모크 | 6–8시간 |
| 합계 | **38–64시간**, 외부 검토·실제 봉제 대기 제외 |

이전 4–6시간 예상은 자체 실험 기초 범위였다. 지금 요구하는 원본 알고리즘 동등성·파일별
라이선스·독립 검증을 포함하지 않으므로 S5 일정으로 사용하지 않는다. 검증된 JVM 구현을
재사용할 수 있으면 줄어들 수 있으나, 현재 미확인인 재사용을 전제로 시간을 확정하지 않는다.


## 첫 포팅 단위 — 2026-10-24, 엔진만

- 브랜치 `codex/potrace`, main 기준. 앱에 연결하지 않은 `internal` API만 추가했다.
- 원본 고정: 위 1.16 배포본 + 읽기 전용 미러 커밋
  `886392d351417d6753aff61de08e294d1506e502` (`Merge with v1.16`).
  포팅 대상 두 C 파일은 배포본과 SHA-256이 동일하다:
  - `src/decompose.c`: `a54e9a5af575ecdf974ed8686e0045628c03beda9d0869759963fefdb7efdf0f`
  - `src/trace.c`: `f70964c69d8fec779e4f3968ddb5fcdc6c45a2666f9d96195fafd82543e195fe`
- `BinaryContourTracer`: `findpath`, `majority`, `xor_path`, 기본 MINORITY 정책,
  원본 `turdsize=2`의 **area > 2** 픽셀 판정을 이식했다.
- `OptimalPolygon`: `calc_sums`, `calc_lon`, `penalty3`, `bestpolygon`을 이식했다.
  결과는 원본 경계 점의 인덱스 목록이며 완성된 곡선/SVG가 아니다.
- 좌표는 원본대로 **왼쪽 아래 원점·y 위쪽·픽셀 모서리**다.
  공유 SVG 계약(y 아래쪽)으로 변환하는 어댑터는 아직 없다.
- GPL-2.0-or-later 출처·변경자 표시와 GPL 전문을 엔진 리소스에 포함했다.
  GPL v3 조건을 선택해 앱과 결합 가능하며 새 라이브러리/네이티브 바이너리는 없다.
- 재현: `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*BinaryContourTracerTest'`.
  대표 기능 8개만 확인한다(빈 입력, 작은 점, 사각형, 구멍/섬, 대각 접촉, 계단,
  입력 보존/분리 영역, 잘못된 입력). 원본 실행 비교나 독립 품질 게이트 통과 주장은 하지 않는다.
- 다음 단위 제안: 원본의 vertex adjustment → smoothing 이식.
  `pathlist_to_tree`, Bezier 및 곡선 최적화, SVG 변환, 실제 자수 연결은 아직 없다.

### 부분 포팅의 차이점

| 항목 | 현재 차이와 이유 |
|---|---|
| 비트맵 저장 | C의 워드 패딩 대신 BooleanArray. XOR 기준을 x=0으로 두어 동일 행의 짝수 교차 패리티를 보존하며, 입력은 복사한다 |
| 영역 면적 | 중간 면적은 Long으로 유지한다. C 공개 path.area의 INT_MAX 포화는 아직 하지 않는다 |
| 경로 계층 | `pathlist_to_tree`의 부모/자식 재정렬은 미구현. 현재는 분해 탐색 순서와 원본 양/음 부호만 반환한다 |
| 정책/API | 기본 MINORITY만 노출. 다른 turnpolicy와 진행률 콜백·C 메모리 오류 반환은 이 단위 범위 밖 |
| 다각형 | 원본과 같이 0번 경계 점을 포함하는 non-cyclic bestpolygon. 최종 곡선/벡터라고 표시하지 않는다 |


## 2차 포팅 단위 — 꼭짓점 보정·곡선화

- `CurveFitter.kt`: 동일 고정 커밋의 `pointslope`, `adjust_vertices`, `reverse`, `smooth`를
  이식했다. 공분산 고유벡터, 특이 행렬의 직교 축 추가, 1픽셀 정사각형 경계 최소화,
  음수 경로 반전, 원본 `alphamax=1.0`의 코너/Bezier 분기를 유지한다.
- 원본 비교 3개: 6×4 사각형, 5×5 계단, 7×7 구멍 영역. 원본 C의 수정 없는 소스를
  임시 API 호출기로 실행했다. 소스 실행은 이번 명시적 원본 비교 요청 범위에 한정하며,
  Ink/Stitch 실행이나 의존성 설치는 없다.
- 경계/다각형/양·음 부호/면적 정확히 일치. 보정 꼭짓점·Bézier 제어점·alpha/alpha0/beta는
  절대오차 `1e-10` 안에서 일치한다. 비교 테스트 3개 포함 엔진 테스트 **296개 통과**.
- 원본은 `opticurve=0`으로 **구현된 1–4단계**만 비교했다. 기본값 `opticurve=1`의
  최종 최적 곡선과 동등하다는 뜻이 아니다. 5단계 최적화와 경로 계층은 아직 미구현이다.
- 재현: `tools/potrace/generate-stage4-goldens.sh /path/to/potrace-1.16`;
  출력 `/tmp/myembro-port-out/editor/potrace-stage4/`. 입력·원본 골든·해시는
  `engine/src/test/resources/potrace/README.md` 참조. 일반 테스트에는 C 실행이 필요 없다.
- 새 땀/PES/앱 진입점 없음. 독립 봉제 품질 게이트 대상도 아니며 통과를 주장하지 않는다.
- 다음 20분 단위 제안: 원본 `opti_penalty`/`opticurve`의 첫 부분 포팅.


## 3차 포팅 단위 — stage 5 곡선 최적화

- `CurveOptimizer.kt`: 동일 고정 원본의 `opti_penalty`, `opticurve`, `tangent`,
  곡선/내적/면적 계산을 이식했다. 원본의 볼록성·코너·179도 굽힘 제한, 접선 오차,
  동적 계획법, beta 복원을 그대로 사용한다. 기본 `opttolerance=0.2px`이며 임의 보정은 없다.
- 동일 사각형·계단·구멍 입력에 **원본 기본 `opticurve=1` 최종 경로** 비교를 추가했다.
  실제 병합 검증을 위해 작은 10×10 둥근 마스크 한 개도 추가했다(원본/Kotlin 모두 7→3개).
- 4개 대표 입력의 stage 4/5 테스트 8개: 경계·다각형·부호·면적·태그·곡선 개수는 동일,
  보정/최적화 꼭짓점·제어점·alpha/alpha0/beta 절대오차 `1e-10` 이내 일치.
  최적화가 원본 곡선을 바꾸지 않는 것도 코드로 assert한다.
- 재현: `tools/potrace/generate-stage5-goldens.sh /path/to/potrace-1.16`;
  출력 `/tmp/myembro-port-out/editor/potrace-stage5/`. 일반 Gradle 테스트는 커밋한 원본 골든을
  읽어 자동 비교하므로 C 컴파일러·다운로드·외부 프로세스가 필요 없다.
- 원본 전체 경로 계층/정렬, SVG 좌표 변환, 앱/자수 생성 연결은 여전히 별도 작업이다.
  4개 사례 비교를 모든 입력의 동등성이나 실제 봉제 품질 통과로 확대하지 않는다.
- 자동 검증 정책에 따라 후속 자수 생성 통합은 `SewoutCheck`의 실제 봉제 실패 조건과
  공식 땀 수/Hausdorff 비교를 코드로 검증해야 한다. 현재 출력은 곡선이므로 새 PES는 없다.


### 3차 단위 자동 검증 결과와 공통 check 차단

- 원본 비교 8개 포함 엔진 **301개**, 앱 debug/release 각 **172개** 테스트 실패 0.
- `./gradlew check` 실행: `:engine:check` 및 앱 단위 테스트는 통과했으나
  `:app:lintDebug`의 기존 오류 14건으로 실패했다. 오류가 난 파일은 포팅 시작 당시의 main 기반(`c0b5f94^`)과
  동일하며 이번 포팅에서 수정하지 않았다. 공유 `origin/main` 참조는 병렬 작업 중 이동할 수 있다. lint baseline/억제는 추가하지 않았다.
- 14건: 테마 리소스 minSdk26/API27 속성 6건, 기존 produceState lint 7건,
  `app_name` 한국어 번역 1건. 상세 보고서는 `app/build/reports/lint-results-debug.txt`.
- 현재 브랜치 CI는 main push/PR만 트리거하고 JVM 테스트와 APK 빌드를 실행한다.
  전체 브랜치 push + 공통 정책 check 전환은 총괄의 CI/앱 정리와 함께 반영해야 한다.
  로컬 단위 테스트 통과를 CI 녹색 또는 병합 승인으로 표현하지 않는다.


## Wu 양자화 교체 단위 — 2026-10-24

- 원본은 Xiaolin Wu의 Graphics Gems II(1991) 알고리즘 **C v2**, `gemsii/quantizer.c`.
  고정 커밋 `ca4898517a93b6b45444a9cda5fd50132694b864`; 원본 SHA-256
  `38b0963722bc3346b18ba58695c87bb7beeb375278f1abd0af05e46933d2a8f6`.
- custom Graphics Gems 재사용 허가와 원본의 “Free to distribute” 고지를 보존했다.
  MIT/public domain으로 표시하지 않는다. 전문·저자 고지는 엔진 리소스와 배포 고지에 포함한다.
- main에는 동결된 자체 ImageQuantizer가 없으므로, `ImageQuantizer.kt`를 Wu 기반 엔진
  진입점으로 새로 추가한다. `codex/editor`의 median cut/CIELAB/알파·영역 휴리스틱 전체를
  가져오지 않는다. 이후 통합에서 실험 파일을 이 새 구현으로 대체해야 한다.
- 알고리즘 근거는 원본 그대로: 33³ 누적 모멘트, 5비트 RGB 버킷, float32 제곱합·점수·분산,
  최대 분산 상자 선택, 빨강→초록→파랑 동률 우선순위, 첫 최대 분할, 정수 나눗셈 팔레트,
  박스 소속 픽셀 라벨. 최근접 색 재배정/팔레트 정렬/디더링은 추가하지 않는다.
- RGB24만 받는다. 알파 합성/투명 픽셀/실 색표 매칭은 원본 Wu 코어 범위 밖이므로
  임의 기본값을 적용하지 않는다. 요청 색 수는 원본 1–256을 지원하며 모바일 색 수 UI와 분리한다.
- 원본 비교는 대표 3개만: 원색, 8×8 그라데이션, 5비트 경계 색. C main의 기존 I/O 빈칸만
  채워 실제 LUT/Qadd를 추출한다. 팔레트 순서·모든 픽셀 인덱스를 정수 단위로 assert한다.
  재현 명령/출처/해시는 `engine/src/test/resources/wu/README.md` 참조.
- 출력 `/tmp/myembro-port-out/editor/wu/`. 앱 호출/새 땀/PES는 없으며 봉제 품질 판정 대상이 아니다.

### Wu 포팅의 명시적 차이

| 항목 | 차이와 근거 |
|---|---|
| 실행/메모리 | C 전역 배열 대신 호출별 JVM 배열; 입력 불변, 반환 인덱스 방어 복사 |
| 통계 정수 | LP64 C long을 Kotlin Long으로 보존. 입력 1,048,576픽셀 제한은 JVM 자원 한계이며 색 선택 규칙이 아님 |
| Bottom/Top | 같은 하위 박스의 8항 Vol로 계산. 제한된 Long 정수합은 정확하므로 동일 통계, float 점수 순서는 유지 |
| 경계 검사 | 빈 입력·RGB24 범위 밖·1–256 외 색 수 거부. 원본의 미정의 입력/메모리 오류를 JVM 예외로 차단 |
| 분류·알파 | 기존 자체 휴리스틱은 미이식. RGB 양자화와 다른 단계로 분리 |

### Wu 단위 실행 결과

- `./gradlew :engine:test --tests '*ImageQuantizerOracleTest'`: 4 tests, 0 failures, 0 errors.
  원본 C 3개 입력의 팔레트 순서와 전체 픽셀 인덱스 exact equality assert 포함.
- `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:check`:
  엔진 397 tests, 0 failures, 0 errors.

```text
BUILD SUCCESSFUL in 9s
6 actionable tasks: 4 executed, 2 up-to-date
```

이는 로컬 엔진 검사 결과이며 CI 녹색이나 새 자수/PES 봉제 품질 통과를 의미하지 않는다.


## 단색 레드워크 통합 1차

사용자 결정의 첫 경로를 `RedworkPipeline.generate`로 연결했다. RGB24 → Wu 2색 →
호출자가 지정한 배경 픽셀 라벨 제외 → Potrace 기본 최적 윤곽 → 기존 SVG 평탄화 →
Ink/Stitch 러닝 기본값 → S1 → PES v6. 앱 호출은 없다.
직접 만든 사각형/원형 PBM 두 개, 코드 assertion, 외부 quality-gate 원본 출력과
내부 연속 반복 집계 회귀 수정은 [qa/redwork-engine.md](qa/redwork-engine.md)에 기록한다.
재현: `tools/redwork/check-fixtures.sh`; 출력 `/tmp/myembro-port-out/editor/redwork/`.


## 사용자 결정 갱신: 검은 선 영역을 채움

2026-10-24: Potrace는 띠의 양쪽 경계를 내므로 윤곽 러닝을 사진 선화 기본값으로
사용하지 않는다. 검은 영역을 구멍 포함 EVEN_ODD로 구성하고 한글 TTF와 같은 model
DefaultTatami(기본 타타미+바탕 땀)로 채운다. 상세 파라미터 UI는 추가하지 않는다.
검사에서 차단된 디테일/선 굵기 쌍은 스윕 JSON 허용 목록에서 자동 제외한다.
자동화 범위는 측정한 합성 입력이며 일반 사진 전체의 안전성을 주장하지 않는다.

센터라인(Autotrace 등 공개 알고리즘)은 다음 버전 후보로 조사만 남긴다. 도입 전
고정 원본/라이선스 GPL 호환성/얇은 선·교차·끝점의 대표 비교 검증이 필요하며,
이번에는 센터라인 추정·윤곽 평균·스켈레톤 자체 휴리스틱을 구현하지 않는다.
