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

## 검증

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:assembleDebug
```

엔진 단위 테스트는 파서, mm 크기/경계/중심, 자간/줄바꿈, Unicode 대체,
양·음 원호, 정수화 후 땀 길이, 윤곽·구멍의 분리와 폐쇄, 짧은 구간 병합,
기준선/기준 높이, TextObject 배치와 PES 저장·재읽기를 검증한다.
Android 그래픽 API의 실제 글리프 생성은 JVM 엔진 테스트의 대상이 아니다.
