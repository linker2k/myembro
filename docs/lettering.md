# 레터링 엔진

2026-10-22. 엔진은 순수 Kotlin이며 Android 폰트 접근은 앱 어댑터에만 있다.

## Hershey 단선 레터링

```kotlin
val text = TextObject(
    id = "label", text = "My embro\n2026", heightMm = 10.0,
    spacing = 1.0, arcDeg = 45.0,
    thread = EmbThread(0xFF000000.toInt(), "Black"),
)
val pattern = text.localPattern()
val pes = PesWriter.write(Project(objects = listOf(text)).flatten())
```

- 폰트 ID: `hershey-futural` (`HersheyFont.DEFAULT_ID`). ASCII 공백~물결표
  U+0020–U+007E를 지원한다. 원본의 96번째 U+007F 진단 글리프도 보존한다.
- 영문 대소문자, 숫자, ASCII 기호를 지원한다. 한글·한자·악센트 문자·이모지는
  지원하지 않으며 코드 포인트마다 `?`로 대체하고 `EmbPattern.warnings`에 남긴다.
  보조 평면 이모지를 UTF-16 두 글자로 잘못 대체하지 않는다.
- James Hurt `.jhf` 파서: 5열 ID + 3열 좌표쌍 개수 + 좌우 베어링 + 좌표쌍.
  개수에는 베어링 한 쌍이 포함되며, ` R`은 pen lift다. 좌표는 `R`을 원점으로
  해석한다. 글자 매핑은 레코드 순서이며 공백을 trim하지 않는다.
- `heightMm`: 대문자 높이 21 font units를 mm로 환산한다. 소문자 x-height는
  더 작고, descender와 구두점은 대문자 높이 밖으로 나올 수 있다.
- `spacing`: 글자 advance에 추가하는 mm 간격(기본 1mm). 탭은 공백 네 개,
  CR/LF/CRLF는 줄바꿈이다. 각 줄은 가운데 정렬하며 기준선 간격은 높이의 1.5배다.

원본 데이터·고정 커밋·SHA-256·고지 조건은
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)에 기록했다. Hershey 원형은
공개 도메인 계열이지만, 이 JHF 배포본의 저자 고지와 NTIS 형식 변환 제한도 보존한다.

## 한글 외곽선 레터링

앱에 OFL-1.1 **Jua**를 포함했다. 둥글고 굵은 한글 글자의 바깥 윤곽과 안쪽 구멍을
각각 러닝 스티치로 따라간다. 단선 중심선이나 새틴/채움 자수가 아니므로 글자 한 획의
양쪽 가장자리가 모두 수놓아진다.

```kotlin
import io.github.linker2k.myembro.lettering.AndroidGlyphOutlines
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.io.PesWriter

// Android Context 필요. 번들 Jua를 사용하며 네트워크에 의존하지 않는다.
val text = AndroidGlyphOutlines.textObject(
    context = context, id = "hangul-label", text = "자수",
    heightMm = 15.0, spacing = 1.5, arcDeg = 45.0,
)
val project = Project().add(text)
val pattern = text.localPattern()
val pesBytes = PesWriter.write(project.flatten())
```

`자수`는 먼저 NFC 정규화한다. `Typeface`와 `Paint.getTextPath`로 두 글자의
외곽선을 얻고, API 26+ `Path.approximate(0.5f)`의 `(fraction, x, y)` 좌표를
폴리라인으로 바꾼다. fraction이 같은 지점의 moveTo에서 윤곽을 분리해 글자 사이와
안쪽 구멍에 연결 스티치가 생기지 않도록 한다. 추출의 기본 textSize는 1000px,
첫 기준선은 y=0, +y는 아래다. 줄바꿈과 자간을 반영하고 줄마다 가운데 정렬한다.
지원 범위는 선택한 Typeface에 따르며 Jua는 한글을 제공한다. 일반적인 한글/라틴
문자열을 대상으로 하며 복잡한 RTL 조판 엔진이나 이모지 렌더러는 포함하지 않는다.

`textObject`는 `자`의 실제 잉크 높이를 기준 높이로 저장하므로 문장·여러 줄에서도
글자 높이가 유지된다. 자간 mm는 이 기준 높이에 맞춰 소스 픽셀로 변환한다.
`TextObject.fontKind = OUTLINE`, `fontId = "jua"`, 원문 `text` 및 미리 계산한
`contours`, `contourBaseline`, `contourSourceHeight`를 유지한다. 텍스트/폰트/자간을
수정할 때는 앱의 `textObject`를 다시 호출해 윤곽도 재생성한다. 높이와 원호·배치는
기존 윤곽으로 다시 계산할 수 있다. 앱 편집 UI 연결은 UI 담당 영역이다.

직접 폴리라인을 전달하는 순수 엔진 API:

```kotlin
val contours = AndroidGlyphOutlines.extract("자수", AndroidGlyphOutlines.jua(context))
val pattern = LetteringEngine.fromContours(
    contours = contours, heightMm = 15.0,
    stitchLengthMm = 2.5, arcDeg = 45.0,
)
```

`fromContours`의 기본 높이는 전체 윤곽의 잉크 경계 높이를 기준으로 정규화한다.
여러 문장·여러 줄에서 글자별 크기를 고정하려면 `sourceHeight`를 제공한다.
`baseline`도 입력 좌표 단위이며 기본 0이다. 수평선은 폭을 기준 크기로 쓰고,
빈 윤곽/한 점은 무시한다. 닫힌 윤곽은 첫 점을 끝에 반복해야 하며 열린 선은
강제로 닫지 않는다. 끝점·급격한 모서리는 보존하고 0.05mm 허용 오차 내의
짧은 공선 구간을 병합한다(`mergeToleranceMm = 0.0`이면 병합 해제).

## 공통 스티치·원호 계약

- 반환 좌표는 기존 모델과 같은 **0.1mm**, +y 아래. 실제 needle-down 경계의
  중심을 (0,0)으로 맞춘다(정수 반올림 오차 최대 0.05mm).
- 러닝 스티치 길이 상한은 기본 **2.5mm**. 정수 좌표로 바꾼 뒤에도 상한을
  넘지 않도록 분할한다. 최소 설정은 0.2mm이며 꼭짓점·끝점에는 더 짧은 땀이 생긴다.
- 획/윤곽마다 `JUMP → STITCH`로 시작하고 다음 획 앞에 `TRIM → JUMP`를 넣는다.
  `trimBetweenStrokes = false`는 trim만 생략한다. 전체 끝은 `END`다.
  사용 실은 하나이며 빈 텍스트는 실 없이 `END`만 반환한다.
- `arcDeg`: -360~360도. 0은 직선, 양수는 왼쪽에서 오른쪽으로 기준선 접선이
  시계 방향으로 돈다. 기준선 폭 W와 각도 a(rad)에 대해 R=W/a,
  θ=x/R, x′=(R−y)sinθ, y′=R(1−cosθ)+y cosθ를 두 API에서 공유한다.
  곡선으로 휜 선은 2도 이하 간격으로 샘플링한 뒤 병합·스티치 분할한다.
  여러 줄 Hershey는 각 줄에 같은 각도를 적용하며 외곽선 API는 전달된 전체
  레이아웃 폭을 기준으로 휜다.
- `TextObject`는 `DesignObject`와 같은 edit 패키지에 있고 기존
  `Transform`/`placedPattern()`/`Project.flatten()`을 그대로 사용한다.
  배치 변환으로 확대하면 땀 길이도 확대된다. 2.5mm 상한은 localPattern 기준이다.

## 한글 단선 후보 조사 (미포함)

한글은 Hershey Futural에 없다. 아래는 향후 **중심선** 레터링용 후보이며,
재배포 권한 확인 전에는 이 저장소에 포함하지 않는다. 조사일: 2026-10-22.

| 후보 | 단선 데이터/범위 | 라이선스·판단 |
| --- | --- | --- |
| KORDANSUN / KORFAST / KORSIMPLE_1 | SHX에서 복원한 SVG/Jamo 데이터; 완성형 2,350자와 조합형 11,172자 파생본 | 아카이브가 원본별 라이선스 차이와 재배포 권한 미검증을 명시한다. 공개 다운로드만으로 OSS 재배포를 허용한다고 판단할 수 없다. KORFAST/SIMPLE에는 Choi Samrak 저작권 표기가 있다. 저작권자의 허락 필요. |
| KOSDICHS | KOSDIC 한글·한자 단선 SHX 및 SVG 파생본 | 아카이브는 한국건설기술연구원 저작권과 자유 이용 안내를 2차 출처로 인용한다. 수정·재배포 조건의 공식 원문을 확인해야 한다. |
| 자체 제작 자모 조합 중심선 | 초성/중성/종성 경로를 직접 설계하고 조합 | 외부 폰트 경로를 복제하지 않는 자체 데이터에 GPL-3.0-or-later 등 명시적 라이선스를 부여하는 방식. 기존 검증된 폰트가 아니라 후속 구현 대안이다. |

출처: [Hangeul Monoline Fonts 원본 README와 Licensing](https://github.com/golanlevin/p5-single-line-font-resources/tree/main/hangeul_monoline_fonts).
폰트 사용 허가와 변환 도구의 라이선스는 별개이므로 도구의 공개 여부로 데이터의
재배포 권한을 추정하지 않았다. 현재 한글 지원은 출처가 명확한
[Google Fonts Jua와 OFL 원문](https://github.com/google/fonts/tree/69d2493f8b919292a9c4b530081934877093c038/ofl/jua)을 사용한다.

## 임의 폰트 품질 분석 API

`FontQuality.analyze(glyphContours, heightMm, outline, detailFloorMm)`는 글리프별 외곽선/획을
분석한다. 바깥 리스트는 독립 글리프, 다음 리스트는 윤곽/획, 안쪽은 점이다.
입력 좌표는 **이미 현재 글자 높이로 정규화한 mm**이며 재스케일하지 않는다.
Android 어댑터의 원시 픽셀을 바로 넘기지 말고 기준 높이에 맞춰 mm로 변환한다.
글리프 사이의 거리는 측정하지 않으므로 샘플 글자를 모두 원점에 놓아도 된다.
`detailFloorMm`은 스크립트별 세부 표현 하한이다(`FontQuality.detailFloorMm(text)`: 한글 7mm, 그 밖 5mm).

```kotlin
val report = FontQuality.analyze(
    glyphContours = listOf(firstGlyphContoursMm, secondGlyphContoursMm),
    heightMm = 15.0,
    outline = true,
)
// report.grade: Grade.GOOD / CAUTION / UNSUITABLE
// report.reasons: 화면에 표시할 한국어 설명
```

### 측정 방법 (분석 버전 6, 2026-10-23)

예전 분석은 글자 전체에서 **단 한 곳의 최소값**을 썼다. 그래서 붓 끝처럼 가늘어지는 획 끝, 세리프 끝,
잉크 트랩, 가변 폰트를 정적으로 만든 폰트의 겹친 윤곽 하나가 결과를 정했다(IBM Plex Sans KR Bold
181.9mm, Anton 132mm). 획 두께와 획 사이 간격을 같은 1.2mm로 봤고, 단선 폰트의 교차점·끝점 결합은
간격 0이 되어 권장 높이가 0이나 무한대(빈 값)가 됐다. 지금은 국소 측정값의 하위 10%를 쓴다.

- **외곽선 폰트**
  - 먼저 nonzero 규칙으로 겹친 윤곽을 합친다(`PolygonRegion.fromPaths`). 합친 경계의 고리마다
    잉크가 왼쪽에 오도록 방향을 맞춘다.
  - 경계를 글자 높이의 1/200 간격으로 샘플링한다. 샘플마다 안쪽 법선으로 레이를 쏴 처음 만나는
    경계까지를 **국소 획 두께(잉크)**로, 바깥 법선으로 쏴 같은 글자의 잉크까지를 **국소 간격**으로 본다.
    간격이 높이의 절반을 넘으면 열린 공간이다.
  - 획 끝 제외: 레이가 같은 고리에 닿고, 경계를 따라 그 점까지 가는 짧은 쪽 거리로 본 획 끝까지의
    거리가 주변 획 두께(앞뒤 `2×두께 + 높이의 5%` 안의 최대 두께)의 1.5배보다 작으면 뺀다.
    붓 끝 테이퍼, 세리프 끝, 획 끝의 모서리가 여기 걸린다.
  - 모서리 제외: 같은 방식으로, 간격을 닫는 모서리까지의 깊이가 `1.5 × max(획 두께, 간격)`보다 얕으면
    뺀다. 잉크 트랩과 획이 만나는 쐐기·첨점(Anton a·g의 보울과 기둥 사이)이 여기 걸린다.
  - 획 두께와 간격 각각 **경계 길이 가중 하위 10% 백분위수**를 쓴다. 분모는 제외되지 않은 모든 경계
    길이이고, 아무것도 마주 보지 않는 경계는 "제한 없음"으로 센다. 그래서 한 글자의 좁은 곳 몇 군데가
    폰트 전체를 정하지 않는다.
  - 기준: 잉크 p10 ≥ **1.0mm**(새틴 최소, PE-Design 실측 1.0–1.6mm), 간격 p10 ≥ **0.6mm**.
- **단선 폰트**
  - 획 두께는 실 굵기이므로 제한하지 않는다. 획을 높이의 1/200 간격으로 나누고, 다른 획(또는 같은
    획의 먼 부분)과 높이의 2.5% 안으로 닿는 점을 접점으로 본다. 접점 묶음에 획 가지가 3개 이상 모이면
    교차·T·갈래·되짚기 결합부다. 두 획 끝이 이어지는 곳은 결합부가 아니다.
  - 접점은 측정하지 않는다. 결합부에서 잉크를 따라 `1.5 × 간격` 안에 있는 점도 뺀다.
  - 점마다 양쪽 법선으로 레이를 쏴 다른 획까지의 거리를 잰다. 높이의 5%보다 가까운 평행 획은 한 선을
    여러 펜 획으로 굵게 그린 것(Hershey Medium·Bold·Gothic, 높이의 3–4.5%)으로 보고 뺀다.
  - 마주 보는 점까지 잉크를 따라 `2.6 × 거리` 안에 닿으면 **고리**(원은 π/2), 아니면 **평행 간격**이다.
  - 기준: 간격 p10 ≥ **1.0mm**, 작은 고리 지름 p10 ≥ **1.5mm**. 분모와 "제한 없음"은 외곽선과 같다.
- **권장 높이** = 각 백분위수를 기준까지 올리는 높이와 스크립트별 세부 표현 하한(한글 7mm, 영문 5mm) 중
  가장 큰 값을 0.5mm 단위로 올린 값이다. 측정값이 없어도 하한을 쓰므로 0이나 무한대가 나오지 않는다.
  모든 거리가 높이에 비례하므로 측정 높이와 무관하다(10mm·20mm 결과가 같다).
- `limitedBy`(INK/GAP/LOOP/DETAIL)가 결정 요인이고, `inkP10Mm`·`gapP10Mm`·`loopP10Mm`는 분석 높이에서의
  백분위수(없으면 null)다. `minStrokeMm`은 그중 가장 작은 값이다.
- 등급: 분석 높이 ≥ 권장 높이면 GOOD, 권장 높이의 절반 이상이면 CAUTION, 그보다 작으면 UNSUITABLE.
  폰트 배지는 `FontQuality.fontGrade`: 10mm GOOD이면 GOOD, 20mm에서 UNSUITABLE이 아니면 CAUTION,
  한글이나 영문이 없으면 최대 CAUTION이다. 빈 입력은 UNSUITABLE, 권장 높이는 하한이다.
- `tightSpots`는 기준보다 좁은 경계 길이를 0.25mm 단위로 센 값이다.
- 측정 샘플은 스크립트별로 고정한다: 한글 `가나다한글뷁쀍`, 영문 `aegsRBQ&@`
  (`FontQuality.sample(hangul)`). 한글이 있는 폰트는 한글 샘플, 없으면 영문 샘플이다.
  폰트에 없는 샘플 글자는 건너뛴다.
- 버전: `FontQuality.VERSION`(6). 저장된 `<파일>.quality.json`과 `FontQualityKey`에 들어가므로
  버전이 바뀌면 이미 내려받은 폰트도 다시 분석한다.
- `stitchCount`, `jumpCount`는 각 글리프에
  `LetteringEngine.fromContours(..., sourceHeight = heightMm)`를 적용해 합산한다.
- 이는 기하 휴리스틱이며 원단·실·바늘에 대한 실제 봉제 품질 보증은 아니다.

`ContourSimplifier.simplify(contoursMm, toleranceMm, minSegmentMm)`는 mm 좌표에
Douglas-Peucker를 적용한 뒤 짧은 구간을 병합한다. 열린 끝점과 닫힌 윤곽/구멍을
보존한다. 최소 구간 길이는 목표값이며, 허용 오차 밖으로 급한 모서리를 깎아야
하는 경우에는 짧은 구간을 남긴다. 큰 허용 오차로도 폐곡선을 선으로 축소하지 않는다.
각 패스의 값을 0으로 주면 해당 패스를 끌 수 있다.

`LetteringEngine.fromContours`에는 기존 인자 뒤에 `simplify: Boolean = true`,
`minSegmentMm: Double = 0.0`이 추가되었다. `mergeToleranceMm`가 DP 허용 오차이며,
기본값은 기존과 같은 0.05mm다. `simplify = false`면 두 단순화 패스를 모두 건너뛴다.
좌표 정수화·땀 길이 상한에 따른 재분할은 그대로 적용한다.

## 품질 분석 검증

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:assembleDebug
```

엔진 단위 테스트는 파서, mm 크기/경계/중심, 자간/줄바꿈, Unicode 대체,
양·음 원호, 정수화 후 땀 길이, 윤곽·구멍의 분리와 폐쇄, 짧은 구간 병합,
기준선/기준 높이, TextObject 배치와 PES 저장·재읽기를 검증한다.
Android 그래픽 API의 실제 글리프 생성은 JVM 엔진 테스트의 대상이 아니다.
품질 분석 테스트(`FontQualityTest`)는 합성 글리프로 획 두께·틈·구멍 기준, 겹친 윤곽 합치기,
테이퍼 제외, 한 곳의 얇은 곳, 측정 높이 무관, 단선 교차·T·꼭짓점, 평행 간격·작은 고리, 묶음 획,
한글 하한, 빈 입력을 검증한다. 범위 회귀로 저장소의 재배포 가능한 폰트를 java.awt로 읽어
(`AwtGlyphs`, 앱과 같은 기준 글자·샘플) 주아 9–16mm, 고운바탕 15–30mm(잉크 결정),
Hershey Simplex 4–12mm를 확인한다. 단순화 테스트는 최소 구간 병합, 모서리·폐곡선 보존과
`fromContours(simplify = false)`를 검증한다.

## 따뜻한 크래프트 레터링 화면

`ui/lettering/LetteringScreen.kt`는 `MyembroTheme`의 색·서체·모서리 토큰을 사용한다.
실 색은 다크 모드에서도 실제 자수 색이 달라지지 않도록 `ui/theme/Thread.kt`의
고정 실 색 토큰(테라코타 #C4552D·아이보리·세이지·골드)을 사용한다.

```kotlin
LetteringScreen(
    onBack = { /* 레터링 경로 닫기 */ },
    onAdd = { textObject ->
        // 호스트 편집기의 기존 변경/히스토리 경로에서 project.add(textObject)를 적용한다.
        // 성공 후 레터링 경로를 닫는다.
    },
    hoop = currentProject.hoop,
    backgroundPattern = currentProject.flatten(),
    // 선택한 설치 폰트 ID를 initialFontId로 돌려줄 수 있다.
    onOpenFonts = { /* 별도 폰트 다운로드/선택 경로 열기 */ },
)
```

- 후프에는 현재 프로젝트(선택 인자)와 **실제 생성 스티치**를 표시한다. 배경용 꽃이나
  채움 자수를 임의로 추가하지 않는다. 후프/글자 맞춤을 전환할 수 있다.
- 문구 입력, 고운바탕·Jua 둥근고딕·Noto Sans KR 카드 3개, 높이·자간 슬라이더와
  미세 조절 버튼, 왼쪽/가운데/오른쪽 정렬, 곡선 배치 토글·직선/아치/원형·곡률,
  실 색 칩, 완료/도안에 추가 버튼을 제공한다. 입력과 조절 상태는 구성 변경 시 보존한다. 아래 2차 확장에서는 원호 위/아래·반경으로 제어를 변경했다.
- 품질 경고와 권장 최소 높이는 추가 버튼 위에 고정한다. 품질 경고는 사용자의 추가를
  막지 않으며, 지원하지 않는 글자·후프 초과·빈 문구·계산 중인 이전 결과의 추가는 막는다.
- 글리프 추출·폰트 로딩·품질 분석은 백그라운드에서 수행한다. 입력 변경은 100ms로
  모으며, 최신 계산이 완료된 결과만 콜백에 전달한다.
- `onAdd` 결과는 새 UUID, 원문, 폰트 ID, mm 높이·자간·각도, 실, 계산된 폴리라인을
  가진 편집 가능한 `TextObject`다. 여러 줄 정렬은 윤곽 위치에 반영하고 후프 내 좌우
  배치는 Transform에 반영한다. 설치 SVG 단선도 열린 폴리라인으로 저장한다.
- 이 화면은 호스트 콜백을 제공하며 MainActivity/편집기 내비게이션은 수정하지 않는다.

검증: `:app:assembleDebug :app:testDebugUnitTest` 통과. API 35 독립 에뮬레이터에서
라이트·다크 화면, 폰트 전환, 19mm/1.3mm 조절, 오른쪽 정렬, 곡선 토글과 35% 아치,
세이지 실 선택을 확인했다. 추가 콜백의 `봄날` Jua 결과는 329땀, PES 1,698바이트로
저장·재읽기 경계 일치를 확인했다. 검증용 Activity/Manifest는 최종 소스에서 제거했다.

## 레터링 2차: 여러 줄·반경·글자별 실·품질 캐시

- 입력은 여러 줄을 지원하며 빈 줄도 줄 간격에 포함한다. UI의 `줄 간격`은
  **글자 높이 사이의 추가 mm 간격**이다. 기준선 간격은 `heightMm + lineSpacingMm`이며
  TextObject에는 배수 `lineSpacing = 1 + lineSpacingMm / heightMm`로 저장한다.
  좌/중/우 정렬은 가장 긴 줄을 기준으로 짧은 줄을 정렬하고, 전체 도안은 기존처럼
  후프에서 5mm 여백을 두고 좌/중/우로 배치한다.
- 곡선 토글 아래 `위 원호` / `아래 원호`와 실제 mm `반경`을 제공한다.
  반경을 지정하면 `arcDeg`의 크기 대신 그 부호만 사용한다(양수 위, 음수 아래).
  여러 줄은 각자의 기준선에서 같은 반경으로 구부린 뒤 원래 줄 위치로 이동한다.
  UI는 안쪽 반전이 생기지 않도록 반경을 글자 높이 이상으로 제한한다.
- 실 색 영역에서 `전체 글자` 또는 개별 글자를 고른 뒤 칩을 누른다. 전체 적용은
  기존 개별 색을 지운다. 문구 편집 시 바뀌지 않은 앞/뒤 글자의 색은 이동한 위치로
  보존하고 교체한 중간 글자의 색만 기본값으로 되돌린다.
- `characterThreads` 키는 NFC, CR/LF, 탭(공백 네 개) 정규화 후 **코드 포인트 인덱스**다.
  공백·줄바꿈도 인덱스에 포함하므로 UTF-16 인덱스와 혼동하지 않는다.
  `contourCharacters`는 각 윤곽의 글자 인덱스, `contourBaselines`는 소스 단위 기준선이다.
  글자의 모든 윤곽에 같은 실을 적용하고 색이 바뀔 때 trim + COLOR_CHANGE + jump를 쓴다.
  색이 같은 연속 글자는 색 변경 없이 잇고, A색→B색→A색 순서도 유지한다.
- TextObject와 `.myembro` 코덱에 `lineSpacing`, `alignment`, `arcRadiusMm`,
  `characterThreads`, `contourCharacters`, `contourBaselines`를 기본값과 함께 추가했다.
  과거 파일은 기본값으로 읽고, 새 파일은 개별 실·좌표·기준선으로 폰트 없이 재생성한다.
  외곽선의 텍스트/줄 간격/정렬 변경 시에는 앱에서 윤곽을 다시 생성해야 한다.
  Hershey 객체는 엔진에서 줄 간격·정렬·반경·글자별 실을 직접 반영한다.

### 폰트 다운로드 화면 연결 계약

이 화면은 폰트 다운로드/가져오기 UI를 구현하지 않는다. `onOpenFonts: (() -> Unit)?`가
`글꼴 더 보기`의 유일한 연결부이며 null이면 버튼은 비활성화한다. 선택한 설치 폰트 ID는
`initialFontId`로 돌려준다. 콜백의 기존 시그니처는 유지했다.

카탈로그 카드가 활용할 수 있는 API:

```kotlin
// 다운로드가 끝나 FontRepository에서 로드할 수 있는 폰트에 사용한다.
val report = LetteringFontQuality.analyze(
    context, fontId = catalogFont.id, text = "자수봄날", heightMm = 18.0,
)
// Composable, MyembroTheme 내에서 호출:
FontQualityBadge(report)  // 등급 + 유한한 권장 최소 높이
// 폰트를 교체/삭제했을 때:
LetteringFontQuality.invalidate(catalogFont.id)
```

`LetteringFontQuality.analyze`는 suspend 함수이며 추출과 분석을 Dispatchers.Default에서
수행한다. 세 번들 카드도 같은 API로 분석 결과를 표시한다. 폰트 SHA-256(번들은 버전),
추출기 버전, 정규화한 고유 문자 집합, 글자 높이와 외곽선/단선 모드가 캐시 키에 들어간다.
색·정렬·줄 간격만 바꿔도 품질을 다시 분석하지 않는다.

Android/카탈로그에 의존하지 않는 엔진 API도 제공한다:

```kotlin
val key = FontQualityKey(fontId, revision = "sha256:...:extractor-v2", sampleText = "자수", heightMm = 18.0, outline = true)
val cache = FontQualityCache.Shared // 또는 FontQualityCache(maxEntries = 64)
val report = cache.getOrAnalyze(key) { normalizedGlyphContoursMm }
val cached = cache.get(key)
cache.invalidate(fontId)
```

공급자 경로는 캐시 미스에서만 실행된다. 키별 보고서를 최대 64개 유지하는 동기화 LRU이며
동시 조회는 같은 결과를 사용한다. `getOrAnalyze`는 호출 스레드에서 계산하므로
백그라운드에서 호출한다. 예외는 캐시하지 않는다. 폰트 바이트가 바뀌면 revision을 바꾸거나
invalidate를 호출해야 한다. `get`, `put`, `clear`, `size`도 제공한다.

## 단선 폰트: 1겹 러닝과 3겹 콩땀

단선 폰트는 이름 새기기·레드워크에 적합하므로 유지한다. 새 Hershey 텍스트와
설치 SVG 단선 폰트는 **3겹 콩땀**을 기본으로 사용하며, 레터링 화면의 `선 굵기`에서
`1겹 러닝`으로 바꿀 수 있다. 외곽선 폰트는 기존 1겹 러닝을 기본으로 유지한다.

```kotlin
val pattern = LetteringEngine.generate("Name", stitchStyle = StitchStyle.BEAN)
val singleLine = LetteringEngine.fromContours(
    contours, heightMm = 18.0, stitchStyle = StitchStyle.BEAN,
)
```

- `StitchStyle.RUNNING`은 이동 A→B를 한 번 수놓고 `BEAN`은 A→B→A→B로 수놓는다.
  분할·정수화가 끝난 좌표에서 확장하므로 되돌아가는 땀도 2.5mm 상한을 지킨다.
  각 획의 첫 고정점은 한 번만 남긴다. 러닝의 needle-down 수 N, 획 수 S라면
  콩땀은 `S + 3 × (N − S)`개다. 점프·트림·색 변경·END는 복제하지 않는다.
- 콩땀은 같은 선을 세 번 지나가므로 경계·글자 크기는 그대로다. 선 너비가 세 배로
  벌어지는 새틴 스티치가 아니며, 실의 겹침으로 더 또렷한 결과를 만든다.
- `TextObject.singleLine`은 Hershey뿐 아니라 OUTLINE으로 저장된 SVG 단선도 구분한다.
  `stitchStyle`과 함께 `.myembro`에 직렬화하므로 폰트 없이 같은 땀을 재생성한다.
  스타일 필드가 없는 과거 파일은 RUNNING으로 읽어 원래 땀 수를 유지한다.
- 후프 미리보기·실제 스티치 폰트 카드·편집기 캔버스는 **0.4mm** 실 지름을 사용한다.
  `ThreadRendering.widthPx(unitsToPixels)`는 엔진 0.1mm 좌표의 배율을 받는다.
  확대 시 너비도 비례하며, 축소 시 보이도록 화면에서는 최소 1dp를 적용한다.
  네이티브 캔버스가 이미 확대된 경우 반환 너비를 배율로 나눠 paint에 지정한다.
- 단선 카탈로그/선택 카드에는 `단선 · 이름 새기기·레드워크에 적합` 배지를 표시한다.
  `SingleLineFontBadge()`를 다른 카드에서도 사용할 수 있다. 실제 스티치 카드가 필요하면
  suspend `LetteringFontPreview.pattern(context, fontId, text, heightMm, thread)`와
  `StitchPatternPreview(pattern, modifier)`를 사용한다. 추출은 백그라운드에서 수행한다.
- 품질 캐시의 `stitchCount`는 폰트 간 비교를 위한 기본 **1겹 러닝** 수를 유지한다.
  레터링 화면의 실제 땀 수와 콜백 패턴에는 선택한 콩땀/러닝 스타일이 반영된다.

검증은 콩땀의 앞·뒤·앞 좌표와 땀 수, 원호·다색 윤곽의 분리 및 땀 길이,
양 스타일의 프로젝트 왕복, 이전 파일 기본값, PES 저장·재읽기 좌표를 포함한다.

## Phase 3b: 기본 새틴 컬럼

`SatinColumn.generate(leftRail, rightRail, options, thread)`는 순수 Kotlin API다.
두 폴리라인은 **mm**, +y 아래 좌표이며 출력은 중심 정렬한 0.1mm `EmbPattern`이다.
같은 방향의 레일을 정규화된 호 길이로 짝짓고, 열린 레일의 방향이 반대면 끝점 거리로
자동 반전한다. 레일의 점 수나 샘플링 간격이 같을 필요는 없다.

```kotlin
val options = SatinOptions(
    widthMm = 3.0,                 // 단선 → 새틴 변환의 폭
    densityMm = 0.4,
    maxStitchLengthMm = 7.0,
    pullCompensationMm = 0.2,
    centerUnderlay = true,
    zigzagUnderlay = true,
)
val column = SatinColumn.generate(
    leftRail = listOf(0.0 to 0.0, 0.0 to 10.0),
    rightRail = listOf(3.0 to 0.0, 3.0 to 10.0),
    options = options,
)
val curvedStroke = SatinColumn.strokeToSatin(centerlineMm, widthMm = 3.0, options = options)
val lettering = LetteringEngine.generate("Name", stitchStyle = StitchStyle.SATIN, satinOptions = options)
val editable = TextObject("label", text = "Name", stitchStyle = StitchStyle.SATIN, satinOptions = options)
```

- 밀도는 **같은 쪽 레일의 땀 사이 간격**이다. 기본 0.4mm이며 반 주기씩 번갈아
  찍고 마지막 레일 쌍을 포함한다. 두 레일 중 긴 쪽의 길이로 주기 수를 정하므로
  짧은 쪽은 더 촘촘해질 수 있다. 10mm 직선·4mm 폭은 본 새틴 52땀이다.
- 폭은 두 레일 사이 거리이며 `widthMm`은 `strokeToSatin`/레터링의 중심선 오프셋에
  사용한다. 직접 두 레일을 전달할 때는 그 레일이 폭을 결정한다.
  당김 보정은 본 새틴의 **양쪽에 각각** 더한다(3mm 폭 + 0.2mm 보정 → 3.4mm).
- 최대 땀 길이는 기본 7mm, 설정 범위 0.2~7mm다. 폭뿐 아니라 대각 이동까지
  검사해 긴 땀을 균등 분할하고, 정수화 오차가 상한을 넘으면 다시 나눈다.
  SATIN에서는 `stitchLengthMm` 대신 `satinOptions.maxStitchLengthMm`를 사용한다.
- 센터 언더레이는 중심선을 왕복한다. 지그재그 언더레이는 원래 폭의 60%, 기본
  1.5mm 간격으로 수놓고 중심선으로 복귀한다. 언더레이의 목표 땀 길이는 2.5mm다.
  두 언더레이와 본 새틴은 한 컬럼 안에서 점프·트림 없이 연결한다.
  서로 다른 글자 획 사이의 trim/jump 및 글자별 실 변경은 기존 계약을 유지한다.
- 곡선은 미리 평탄화된 중심선의 법선을 따라 레일을 만든다. 꼭짓점은 miter로
  연결하고 급각에서 반폭의 2배로 제한한다. 열린 끝은 butt 형태, 닫힌 선은
  레일의 시작/끝을 닫는다. 복잡한 교차·아주 작은 곡률의 오프셋 겹침 제거,
  수동 rung 방향 제어·단축 땀·자동 새틴 라우팅은 이 기본 구현에 포함하지 않는다.
- 단선 SVG는 `TextObject(singleLine = true, fontKind = OUTLINE, contours = ...)`에
  SATIN과 옵션을 지정한다. `fromContours(..., stitchStyle = SATIN)`도 각 경로를
  **중심선**으로 해석한다. 외곽선 글꼴의 내부를 채우는 기능은 아니다.
- 새 텍스트의 기본값은 여전히 BEAN이며 새틴은 명시적으로 선택한다.
  `TextObject.satinOptions`의 모든 값은 `.myembro`에 보존되고, 이전 파일에는
  기본 옵션을 적용한다. 이 단계는 엔진 API이며 새틴 조절 UI는 추가하지 않았다.

개념 참고: [Ink/Stitch 공식 새틴 컬럼 문서](https://inkstitch.org/docs/stitches/satin-column/).
출처·라이선스 및 독립 구현 범위는 `THIRD_PARTY_NOTICES.md`에 기록했다.

### SA431 소형 후프 검증

후프는 크기를 하드코딩하지 않고 `HoopSpec`의 실제 좌표를 사용한다. SA431의
약 60×20mm 가로 배치(`HoopSpec("SA431", 600, 200)`)와 20×60mm 세로 배치를
테스트했다. 미리보기의 점선 사각형은 실제 봉제 영역이며 비율을 유지한다.
글자 맞춤 확대는 좌/우 배치된 글자의 경계 중심도 따라가므로 화면 밖으로 밀리지 않는다.
후프 초과 판단은 표시 배율과 무관하게 실제 스티치 좌표로 수행한다.

높이 10mm, 두께 0.4mm 합성 글리프는 소형 후프 안에 들어가도 품질 UNSUITABLE과
권장 높이 25mm를 그대로 보고한다. 권장 높이를 후프 크기에 맞춰 낮추지 않는다.
25mm로 키우면 가로 배치의 20mm 높이를 초과하며, 세로 배치에서는 들어가는 것도
검증했다. 권장 높이 경고는 품질 기준이며 실제 후프 초과 안내와 별도로 표시한다.

## 폰트 라이브러리 연결

`글꼴 더 보기`는 `MainActivity`에서 `FontLibraryScreen(onBack, onPick)`을 레터링 화면 **위에 겹쳐**
연다(레터링 입력 상태 유지). `onPick`의 ID는 `initialFontId`로 돌아간다. 카탈로그 ID 또는
사용자 폰트 `user:<uuid>`이며, `LetteringRenderer.loadFont`는 `FontRepository.find` 다음에
`FontRepository.userFont`를 찾는다. 사용자 폰트의 캐시 revision은 파일 크기·수정 시각이다.
다운로드·삭제·사용자 폰트 삭제 시 `FontQualityCache.Shared.invalidate(id)`를 호출한다.

- 카탈로그: `assets/fonts/catalog.json` 102종(한글 외곽선 30, 영문 외곽선 32, 단선 40), 고정 커밋 URL,
  다운로드 바이트의 SHA-256·크기를 검증한 뒤에만 저장하고 라이선스 원문을 `<id>.LICENSE.txt`로
  함께 저장한다(https만, 32MB 상한). 라이선스는 OFL-1.1·Apache-2.0·Hershey만 쓴다.
  가변 폰트는 업스트림의 정적 Regular/Bold를 쓴다. Noto Serif KR은 noto-cjk의 KR 서브셋 OTF다.
  번들된 주아·Noto Sans KR·고운바탕은 넣지 않는다.
  - `scripts`: 폰트 cmap을 직접 읽어 정한다. 한글은 완성형 2,350자 이상, 영문은 A–Z·a–z가 모두 있을 때다.
  - `style: "script"`: 필기체 필터에 쓴다. `thinStroke: true`(Sacramento)는 "가는 선 · 러닝 스티치용" 배지를 단다.
  - `recommendedMinHeightMm`·`qualityGrade`: 모든 폰트에 있다. 다운로드 전 카드에 보이고, 설치 후에는
    앱이 같은 방식으로 분석한 전체 보고서로 바뀐다. 생성: 엔진 테스트 `CatalogQualityTool`이
    고정 URL에서 내려받아(sha256 검증, `MYEMBRO_FONT_CACHE`에 캐시) java.awt 윤곽으로 분석하고
    두 필드만 다시 쓴다.
    `MYEMBRO_FONT_CACHE=/tmp/fonts ./gradlew :engine:test --tests '*CatalogQualityTool*' --rerun`
  - 외곽선 폰트의 권장 높이가 25mm를 넘으면 "큰 글자 전용" 배지를 단다.
- 글리프 유무: `Paint.hasGlyph`는 시스템 대체 글꼴까지 세므로, 영문 전용 폰트도 한글이 있다고
  답한다. 그래서 분석이 대체 글꼴의 한글로 측정되었고, 영문 폰트가 모두 0mm·부적합으로 나왔다.
  지금은 내려받은 폰트와 내 폰트의 TTF/OTF `cmap`을 `FontCmap`으로 직접 읽는다. 이 결과를
  품질 분석, 기준 글자(자/H), 레터링의 "지원하지 않는 글자" 판정에 쓴다.
- 내 폰트: SAF로 TTF/OTF/SVG를 `filesDir/fonts/user/`에 복사하고 파싱·글리프 검사 후
  스크립트 샘플의 10/20mm 품질 결과를 `<파일>.quality.json`에 저장한다.
- 품질: 앱의 `FontQualityGateway`는 샘플 선택과 스크립트 하한만 정하고 엔진 `FontQuality.analyze`를
  그대로 쓴다(위 "측정 방법").
- 카탈로그 102종 결과(10mm 기준 p10, 2026-10-23): 굵은 고딕·디스플레이 Anton 7.5, Bebas Neue 8.5,
  Alfa Slab One 6, Permanent Marker 7.5mm. 한글 Regular 고딕은 가로획이 10mm에서 0.55–0.65mm라
  16–19.5mm(나눔고딕 16.5, IBM Plex Sans KR 17, 고운돋움 19.5)다. 검은고딕·도현·Gothic A1 Bold·IBM Plex Sans KR Bold는
  자모 사이가 0.4–0.5mm로 붙어 간격이 정한다(12–15.5mm). 굵은 필기체 Pacifico 9, Lobster 16,
  헤어라인 필기체 Allura 35, Great Vibes 49.5, Pinyon Script 56.5mm. 명조 Regular 15.5–28.5mm(함렛 15.5, Noto Serif KR 23, 나눔명조 28.5).
  단선은 Hershey 5–7.5, EMS 5–13mm다.
- PE-Design 비교: PE-Design 레터링 실측(약 10mm 글자) 새틴 폭은 블록·세리프·고딕 p10 0.9–1.3mm,
  중앙값 1.5–1.75mm다. 필기체·작은 글자는 p10 0.6–0.75mm까지 내려간다. 잉크 기준 1.0mm는 블록체에
  맞고, 필기체에는 다소 보수적이다.

## 윤곽 글자 내부 채우기 (2026-10-23)

레터링 화면과 편집기 글자 속성에 **외곽선 / 채우기(태타미) / 새틴**을 제공한다.
단선 글꼴은 기존 1겹 러닝 / 3겹 콩땀 / 폭을 지정하는 새틴을 사용하며 기본값은 콩땀이다.
윤곽 글꼴도 기본값은 기존 외곽선이며 채우기는 명시적으로 선택한다.

```kotlin
val text = TextObject(
    id = "filled-label", text = "ㅇㅁB", fontId = "jua", fontKind = FontKind.OUTLINE,
    contours = precomputedContours, contourSourceHeight = sourceCapHeight,
    contourCharacters = contourCodePointOwners, // 바깥 윤곽과 홀은 같은 글자 인덱스
    heightMm = 12.0, stitchStyle = StitchStyle.FILL,
    fillOptions = FillOptions(
        densityMm = 0.4, angleDeg = 0.0, pullCompensationMm = 0.2,
        underlay = true, border = FillBorder.RUNNING, // NONE / RUNNING / BEAN
    ),
)
// 직접 mm 윤곽을 전달하는 독립 API. 외곽선과 모든 홀을 같은 리스트에 전달한다.
val filled = OutlineFill.generate(glyphContoursMm, FillOptions())
```

- 글자별 경로에 짝수–홀수 규칙을 적용한다. 홀의 감김 방향과 무관하며 중첩된 섬도 보존한다.
  JTS의 기존 `PolygonRegion`과 `engine/digitizing/TatamiFill`을 재사용한다.
  당김 보정은 바깥 경계를 확장하며 원래 홀 영역은 제외해 작은 홀이 닫히지 않도록 한다.
- 기본 땀 간격은 0.4mm, 태타미 목표 땀 길이는 3.5mm다. 각도는 +y 아래 좌표에서
  시계 방향이다. 윤곽 언더레이는 기본 켜짐이며 원래 경계에서 0.3mm 안쪽을 수놓는다.
  좁아져 사라지는 언더레이는 원래 경계를 사용한다. 테두리는 없음·러닝·콩땀을 선택한다.
- 글자 사이는 trim + jump, 실이 바뀌면 COLOR_CHANGE도 넣는다. 글자 내부에서는
  먼저 영역 안의 직선 연결을 시도하고, 홀을 가로지르면 보이는 경계의 짧은 우회 경로로
  러닝한다. 연결할 수 없는 분리 영역에서는 jump한다. 전역 최적 라우팅은 아니다.
- 로컬 패턴의 **실제로 이동하는 needle-down 땀은 정수화 후 0.3~7mm**다.
  짧은 구간은 합치고 긴 구간은 나눈다. 안전하게 합칠 수 없는 작은 모서리는 jump로
  이동한다. 0.1mm 좌표 정수화에 따른 경계 오차는 최대 약 0.075mm로 검사한다.
  다른 스티치와 마찬가지로 편집기 Transform으로 확대하면 땀 길이도 확대된다.
- 윤곽 새틴은 `FontQuality`의 최소 획 두께 0.6~4mm와 선택 각도에서의 실제 가로지를
  거리를 함께 검사한다. 가능한 글리프는 교대 지그재그로 채우고, 넓거나 복잡한 글리프는
  태타미로 전환해 안내한다. 한 글리프의 획을 자동 분할하는 완전한 새틴 디지타이저는 아니다.
  `fromContours(..., outline = true, stitchStyle = SATIN)`이 이 모드이며,
  `outline = false`의 기존 단선 오프셋 새틴 API는 유지한다.
- `FillOptions`와 모드는 `.myembro`에 저장된다. 이전 파일은 기본 옵션으로 읽으며
  기존 외곽선·콩땀 선택을 유지한다. 글자 소유 인덱스가 없는 직접 API 입력은 한 글리프로
  취급하므로 글자 사이 trim을 원하면 `contourCharacters`를 전달한다.
- 작은 채우기 글자는 FontQuality의 권장 높이와 실용 최소 높이 6mm 중 큰 값으로 경고한다.
  경고는 추가를 막지 않는다. 편집기 속성에서도 저장된 윤곽으로 분석하므로 원본 폰트가
  필요 없다. 권장 높이는 항상 유한하다.
  높이·권장 높이·폰트 카드·후프 치수는 공용 `Dimensions`로 mm와 인치를 병기한다.
- 편집기 속성의 모드·변환 변경은 백그라운드에서 패턴을 계산한 뒤 한 번의 실행 취소
  단계로 적용한다. 세부 채우기 설정은 동일한 레터링 화면으로 연결한다.

검증: 합성 `ㅁ`·원형 `ㅇ`·두 홀 `B`, 반전 방향과 중첩 섬, 0/37/90도,
밀도·언더레이·테두리, 양자화 후 땀 길이, 내부 연결, 다색·프로젝트·PES 왕복을 테스트했다.
독립 API 35 에뮬레이터에서 실제 Jua `ㅇㅁB`는 60×20mm SA431 안에 들어갔으며
1,025땀·7점프, 경계 33.7×11.2mm였다. `ㅇ` 중앙의 빈 공간과 PES 재읽기 경계/땀 수
일치를 확인했다. 검증용 Activity/Manifest는 최종 소스에서 제거했다.

## 획 방향 자동 채우기 — 2단계

새 `FillOptions()`는 `strategy = FillStrategy.AUTO`, `autoDensity = true`다.
앞 절의 고정 각도 태타미/좁은 글리프 새틴 판정 대신 실제 획의 중심선과 양쪽 레일을
추출해 채운다. `FillStrategy.TATAMI`를 고르면 기존 고정 각도 채우기를 사용할 수 있다.

```kotlin
val options = FillOptions(strategy = FillStrategy.AUTO, autoDensity = true)
val pattern = OutlineFill.generate(glyphContoursMm, options, heightMm = 10.0)
val columns = OutlineColumns.extract(glyphContoursMm) // 중심선·짝지은 양쪽 레일, mm
// TextObject / fromContours도 같은 FillOptions를 사용한다.
```

- `OutlineColumns`는 짝수–홀수 영역을 긴 변 최대 약 384셀, 최소 0.06mm 셀로 샘플링한다.
  Guo–Hall의 연결 보존 세선화 수식을 독립 구현해 근사 중심선을 얻고, 짧은 가지를
  정리한 뒤 교차점에서 방향이 이어지는 획을 연결한다. X의 대각 획은 서로 겹칠 수 있다.
- 중심선 접선에 수직인 선과 실제 윤곽의 교차점을 짝지어 레일을 얻는다. 홀을 건너뛴
  다른 영역의 경계는 선택하지 않는다. H/X 접합점의 가로지를 길이가 갑자기 커지는
  경우 해당 기둥의 대표 폭으로 제한하고 다른 기둥이 접합부를 덮도록 한다.
  두 레일은 같은 중심선 구간으로 보간해 둥근 끝에서 짝이 어긋나지 않게 한다.
- 폭 약 **7mm 이하**는 일반 새틴, 넓은 기둥은 **최대 4mm**의 엇갈린 분할 새틴이다.
  분할 위치를 네 단계로 이동해 일직선 분할 자국을 피한다. 땀 방향은 획에 수직이며,
  긴 땀·짧은 모서리 처리와 홀 안전 검사는 양자화 후에도 적용한다.
- 같은 쪽 바늘 간격은 기본 **0.40mm**다. 요청한 글자 높이가 **7mm 미만**이면
  자동 간격을 **0.60mm 이상**으로 완화한다. 높이는 TextObject의 기준 높이를 사용한다.
  직접 `OutlineFill.generate`에서 높이를 생략하면 윤곽의 잉크 높이를 사용한다.
  UI에서 간격을 직접 바꾸면 자동 완화를 끄며, 스위치로 다시 켤 수 있다.
- 언더레이는 좁은 새틴의 **센터런**, 넓은 기둥의 **가장자리 런 + 센터런**이다.
  언더레이와 테두리 켜기/끄기·콩땀 테두리 선택은 유지한다.
- 추출된 기둥이 덮지 못한 부분만 태타미로 보완하며, 복잡한 윤곽에서 보완 영역이
  크면 안내한다. 근사 래스터 중심선이므로 가는 장식·급한 분기·복잡한 서체의 모든
  기둥을 전문 디지타이저처럼 최적화하는 기능은 아니다.
- 기존 프로젝트에서 `strategy`/`autoDensity`가 없으면 **TATAMI / false**로 읽어
  기존 봉제 모양을 유지한다. 새 파일은 두 설정을 저장한다. 단선 폰트의 기본 콩땀은
  그대로이며, 일반 `SatinColumn` 두 레일 API의 기존 동작도 유지한다.

검증은 합성 직선·회전 획·원형 홀·X 교차, 곡선에 수직인 바늘 방향, 레일 짝 보존,
7mm 폭 경계, 0.4/0.6mm 높이 경계, 4mm 분할과 엇갈림, 언더레이, 과거 프로젝트 및
PES 왕복을 포함한다. 모든 저장소 테스트 입력은 합성 도형이다.
사용자가 제공한 참고 자료는 로컬에서만 읽고, 공개 라이선스 번들 폰트의 같은 글자를
동일한 실제 높이로 생성해 일반·소형·대형의 방향과 분할을 시각 비교했다. 서로 다른
서체의 모양·획 두께 차이는 구분해 판단했다. 참고 파일·추출 좌표·비교 이미지·분석
스크립트는 저장소, 앱 리소스, 테스트 픽스처에 포함하지 않았다.
