# 글자별 median 획 폭 공용 API

`GlyphStrokeWidths.measure(fontOutlines, heightMm)` — 순수 JVM 엔진 API.
Android/AWT 의존성 없이 플랫폼이 추출한 평탄화 윤곽을 받는다.
글꼴 ID·기준 높이·글자별 윤곽 → 요청 높이의 글자별 median(mm) 표를 반환한다.

```kotlin
val font = GlyphStrokeWidths.FontOutlines(
    fontId = "noto-sans-kr@revision:wght700",
    sourceHeight = referenceHeight, // fromContours와 동일한 ‘자’ 등의 기준 높이
    glyphs = listOf(
        GlyphStrokeWidths.Glyph(characterIndex = 0, codePoint = '정'.code, contours = jeongContours),
        GlyphStrokeWidths.Glyph(characterIndex = 1, codePoint = '민'.code, contours = minContours),
    ),
)
val table = GlyphStrokeWidths.measure(font, heightMm = 10.0)
val medianByCharacter = table.rows // characterIndex, codePoint, medianWidthMm, sampleCount
```

윤곽 좌표는 font/pixel 단위이고 구멍은 even-odd 규칙이다. 공백 등 빈 영역은
`medianWidthMm=null, sampleCount=0`이며 같은 글자가 반복돼도 인덱스로 구별한다.
잘못된 입력은 예외로 보고한다. 글자 인덱스는 호출자가 정하며 UTF-16 인덱스와
코드포인트 인덱스를 혼용하지 않는다. 높이는 실제 봉제와 동일한 기준 높이를 써야 한다.

계산은 기존 JTS 경계 표본(0.1mm)의 내부 Voronoi 정점에서 경계까지 거리×2의
**중간값**이다. 검증된 임계값이나 최소 획 폭으로 간주하지 않는다.
새틴/채우기 자동 분기 임계값·UI 연결은 editor 담당이며 이 API는 측정만 한다.
백그라운드에서 호출하고 캐시할 경우 글꼴 파일 리비전·variation·문자·높이·표본 간격을
키에 포함한다. 작은 윤곽에서 내부 표본을 얻지 못하면 측정기가 명시적으로 예외를 반환한다.

## 계산 표

기존 공개 Noto Sans KR을 weight700/400으로 정적 인스턴스화하고,
기존 시험과 같은 ‘자’ 기준·평탄화0.2 font unit·경계 간격0.1mm로 계산했다.
Regular400은 S23 비교 재현값이며 당시 유효 variation과의 동일성은 미확인이다.

| 글꼴 | 높이 mm | 정 median mm | 민 median mm |
|---|---:|---:|---:|
| NotoSansKR-700 | 8 | 0.875166 | 0.914838 |
| NotoSansKR-700 | 10 | 1.093345 | 1.143966 |
| NotoSansKR-700 | 12 | 1.311661 | 1.373139 |
| NotoSansKR-700 | 15 | 1.644304 | 1.716579 |
| NotoSansKR-700 | 20 | 2.184369 | 2.289197 |
| NotoSansKR-700 | 25 | 2.728819 | 2.861577 |
| NotoSansKR-400 | 15 | 1.126003 | 1.125383 |

재현(기존 공개 시험용 fontTools 인스턴스 사용, PES 생성 없음):
```
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:koreanTtfSweepClasspath -PkoreanTtfSweepClasspath=/tmp/myembro-sewtest/kr7/classpath.txt
java -Djava.awt.headless=true -cp "$(cat /tmp/myembro-sewtest/kr7/classpath.txt)" io.github.linker2k.myembro.engine.lettering.KoreanTatamiTrial medians /tmp/myembro-sewtest/kr7/NotoSansKR-700.ttf /tmp/myembro-sewtest/kr7/NotoSansKR-400.ttf /tmp/myembro-sewtest/kr7/median-table.md
```

검증: `GlyphStrokeWidthsTest` 4건(중간값·글자 구분·높이 비례·구멍·공백·입력 검증),
`:engine:check` BUILD SUCCESSFUL. KRSAMP7 봉제 시험은 보류 상태다.
