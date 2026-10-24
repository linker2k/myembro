# Shared per-glyph median stroke-width API

`GlyphStrokeWidths.measure(fontOutlines, heightMm)` — pure JVM engine API.
Accepts platform-extracted flattened outlines without Android/AWT dependencies.
Font ID/reference height/per-glyph outlines → returns a table of per-glyph medians(mm) at the requested height.

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

Outline coordinates are in font/pixel units; holes follow even-odd rules. Empty regions such as spaces have
`medianWidthMm=null, sampleCount=0`; repeated occurrences of the same glyph are distinguished by index.
Invalid inputs are reported as exceptions. The caller defines glyph indices; do not mix UTF-16 indices
with code-point indices. Height must use the same reference height as actual sewing.

Computation is the **median** of distance to boundary×2 at interior Voronoi vertices from existing JTS
boundary samples(0.1mm). Do not treat it as a verified threshold or minimum stroke width.
The editor owner handles satin/fill automatic-selection thresholds/UI integration; this API only measures.
Call in the background; when caching, include font-file revision/variation/character/height/sample interval
in the key. The measurer explicitly throws if a small outline yields no interior samples.

## Computed table

Statically instantiated the existing public Noto Sans KR at weight 700/400,
using the existing test's 'ja' reference, flattening 0.2 font unit, and boundary spacing 0.1mm.
Regular 400 reproduces the S23 comparison; equivalence to the effective variation at that time is unconfirmed.

| Font | Height mm | Jeong median mm | Min median mm |
|---|---:|---:|---:|
| NotoSansKR-700 | 8 | 0.875166 | 0.914838 |
| NotoSansKR-700 | 10 | 1.093345 | 1.143966 |
| NotoSansKR-700 | 12 | 1.311661 | 1.373139 |
| NotoSansKR-700 | 15 | 1.644304 | 1.716579 |
| NotoSansKR-700 | 20 | 2.184369 | 2.289197 |
| NotoSansKR-700 | 25 | 2.728819 | 2.861577 |
| NotoSansKR-400 | 15 | 1.126003 | 1.125383 |

Reproduction (uses existing public test fontTools instances; no PES generation):
```
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:koreanTtfSweepClasspath -PkoreanTtfSweepClasspath=/tmp/myembro-sewtest/kr7/classpath.txt
java -Djava.awt.headless=true -cp "$(cat /tmp/myembro-sewtest/kr7/classpath.txt)" io.github.linker2k.myembro.engine.lettering.KoreanTatamiTrial medians /tmp/myembro-sewtest/kr7/NotoSansKR-700.ttf /tmp/myembro-sewtest/kr7/NotoSansKR-400.ttf /tmp/myembro-sewtest/kr7/median-table.md
```

Validation: `GlyphStrokeWidthsTest` 4 cases (median/glyph distinction/proportional height/holes/spaces/input validation),
`:engine:check` BUILD SUCCESSFUL. KRSAMP7 sewout trial is on hold.
