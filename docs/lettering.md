# Lettering engine

2026-10-22. The engine is pure Kotlin; Android font access exists only in the app adapter.

## Hershey single-line lettering

```kotlin
val text = TextObject(
    id = "label", text = "My embro\n2026", heightMm = 10.0,
    spacing = 1.0, arcDeg = 45.0,
    thread = EmbThread(0xFF000000.toInt(), "Black"),
)
val pattern = text.localPattern()
val pes = PesWriter.write(Project(objects = listOf(text)).flatten())
```

- Font ID: `hershey-futural` (`HersheyFont.DEFAULT_ID`). Supports ASCII space through tilde,
  U+0020–U+007E. The original's 96th diagnostic glyph, U+007F, is also preserved.
- Supports uppercase/lowercase Latin letters, digits, and ASCII symbols. Hangul, Han characters,
  accented characters, and emoji are unsupported: each code point is replaced with `?` and recorded
  in `EmbPattern.warnings`. Supplementary-plane emoji are not incorrectly replaced as two UTF-16 characters.
- James Hurt `.jhf` parser: 5-column ID + 3-column coordinate-pair count + left/right bearings + coordinate pairs.
  The count includes a bearing pair; ` R` is a pen lift. Coordinates are interpreted relative to `R` as the origin.
  Character mapping follows record order, and spaces are not trimmed.
- `heightMm`: converts the capital height of 21 font units to mm. Lowercase x-height is smaller;
  descenders and punctuation may extend outside the capital height.
- `spacing`: additional spacing in mm added to each character's advance (default 1mm). Tabs become four spaces;
  CR/LF/CRLF are line breaks. Each line is centered, and baseline spacing is 1.5 times the height.

Original data, pinned commit, SHA-256, and notice requirements are recorded in
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). Although the original Hershey fonts are in the public-domain
family, this JHF distribution's author notices and NTIS format-conversion restrictions are also preserved.

## Hangul outline lettering

OFL-1.1 **Jua** is bundled in the app. It supplies the outer contours and inner holes of rounded, bold Hangul characters.
The new default for outline fonts is auto-fill + contour running. Selecting outline running sews both edges of each stroke.

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

`자수` is first normalized to NFC. `Typeface` and `Paint.getTextPath` obtain the two characters' outlines;
the `(fraction, x, y)` coordinates from API 26+ `Path.approximate(0.5f)` become polylines.
Contours are separated at moveTo points with the same fraction so no connecting stitch is created between characters
or across inner holes. Extraction defaults to textSize 1000px, the first baseline at y=0, and +y downward.
Line breaks and character spacing are respected, and each line is centered. Coverage depends on the selected Typeface;
Jua provides Hangul. This targets ordinary Hangul/Latin strings, without a complex RTL shaping engine or emoji renderer.

`textObject` stores the actual ink height of `자` as its reference height, keeping character height consistent across
sentences and multiple lines. Spacing in mm is converted to source pixels using that reference height.
It retains `TextObject.fontKind = OUTLINE`, `fontId = "jua"`, the original `text`, and precomputed
`contours`, `contourBaseline`, and `contourSourceHeight`. When text/font/spacing changes, call the app's
`textObject` again to regenerate contours. Height, arc, and placement can be recalculated from existing contours.
Connecting the app editing UI belongs to the UI owner.

Pure engine API accepting polylines directly:

```kotlin
val contours = AndroidGlyphOutlines.extract("자수", AndroidGlyphOutlines.jua(context))
val pattern = LetteringEngine.fromContours(
    contours = contours, heightMm = 15.0,
    stitchLengthMm = 2.5, arcDeg = 45.0,
)
```

By default, `fromContours` normalizes height against the ink-bounds height of all contours.
Supply `sourceHeight` to keep per-character size fixed across sentences/multiple lines.
`baseline` also uses input-coordinate units and defaults to 0. A horizontal line uses its width as the reference extent;
empty contours/single points are ignored. Closed contours must repeat the first point at the end; open lines are not
forcibly closed. Endpoints and sharp corners are preserved, and short collinear sections within a 0.05mm tolerance
are merged (`mergeToleranceMm = 0.0` disables merging).

## Shared stitch and arc contract

- Returned coordinates use **0.1mm**, +y downward, matching the existing model. The actual needle-down bounds
  are centered at (0,0), with at most 0.05mm integer-rounding error.
- The default running-stitch maximum length is **2.5mm**. Segments are split to respect it even after conversion
  to integer coordinates. The minimum setting is 0.2mm; vertices/endpoints may produce shorter stitches.
- Each stroke/contour begins with `JUMP → STITCH`; `TRIM → JUMP` precedes the next stroke.
  `trimBetweenStrokes = false` omits only the trim. The complete output ends with `END`.
  A single thread is used; empty text returns only `END`, with no thread.
- `arcDeg`: -360~360 degrees. 0 is straight; positive values turn the baseline tangent clockwise from left to right.
  For baseline width W and angle a(rad), both APIs share R=W/a,
  θ=x/R, x′=(R−y)sinθ, y′=R(1−cosθ)+y cosθ.
  Bent curves are sampled at intervals of at most 2 degrees before merging/stitch splitting.
  Multiline Hershey applies the same angle to each line; the outline API bends using the entire supplied layout width.
- `TextObject` resides in the same edit package as `DesignObject` and uses the existing
  `Transform`/`placedPattern()`/`Project.flatten()` unchanged. Scaling up through a placement transform also
  scales stitch lengths. The 2.5mm limit applies to localPattern.

## Hangul single-line candidate survey (not bundled)

Hershey Futural has no Hangul. The following are candidates for future **centerline** lettering;
they will not be included in this repository until redistribution rights are confirmed. Survey date: 2026-10-22.

| Candidate | Single-line data/coverage | License and assessment |
| --- | --- | --- |
| KORDANSUN / KORFAST / KORSIMPLE_1 | SVG/Jamo data recovered from SHX; derivatives with 2,350 precomposed and 11,172 compositional syllables | The archive explicitly notes differing source licenses and unverified redistribution rights. Public download availability alone does not establish permission for OSS redistribution. KORFAST/SIMPLE carries a Choi Samrak copyright notice. Permission from the rights holder is required. |
| KOSDICHS | KOSDIC Hangul/Han single-line SHX and SVG derivatives | The archive cites Korea Institute of Civil Engineering and Building Technology copyright and free-use guidance as 2nd-hand evidence. Official original modification/redistribution terms must be checked. |
| Original Jamo-combination centerlines | Design and combine initial/medial/final consonant/vowel paths directly | Assign an explicit license such as GPL-3.0-or-later to original data that does not copy external font paths. This is an alternative for future implementation, not an existing verified font. |

Source: [Hangeul Monoline Fonts original README and Licensing](https://github.com/golanlevin/p5-single-line-font-resources/tree/main/hangeul_monoline_fonts).
Font-use permission and conversion-tool licenses are separate; data redistribution rights were not inferred from whether
the tools are public. Current Hangul support uses the clearly sourced
[Google Fonts Jua and original OFL](https://github.com/google/fonts/tree/69d2493f8b919292a9c4b530081934877093c038/ofl/jua).

## Arbitrary-font quality analysis API

`FontQuality.analyze(glyphContours, heightMm, outline, detailFloorMm)` analyzes per-glyph outlines/strokes.
The outer list contains independent glyphs, the next contains contours/strokes, and the inner contains points.
Input coordinates are **mm already normalized to the current character height**; they are not rescaled.
Do not pass raw Android-adapter pixels directly; convert them to mm using the reference height.
Distances between glyphs are not measured, so all sample glyphs may be placed at the origin.
`detailFloorMm` is the script-specific detail floor (`FontQuality.detailFloorMm(text)`: Hangul 7mm, others 5mm).

```kotlin
val report = FontQuality.analyze(
    glyphContours = listOf(firstGlyphContoursMm, secondGlyphContoursMm),
    heightMm = 15.0,
    outline = true,
)
// report.grade: Grade.GOOD / CAUTION / UNSUITABLE
// report.reasons: 화면에 표시할 한국어 설명
```

### Measurement method (analysis version 6, 2026-10-23)

The previous analysis used **a single global minimum** across the entire character. A tapered brush tip, serif tip,
ink trap, or overlapping contour in a statically instantiated variable font could therefore determine the result
(IBM Plex Sans KR Bold 181.9mm, Anton 132mm). Stroke thickness and inter-stroke spacing shared the same 1.2mm criterion;
single-line font intersections/end joins became gaps of 0, producing recommended heights of 0 or infinity (empty values).
The current method uses the bottom 10% of local measurements.

- **Outline fonts**
  - First union overlapping contours using the nonzero rule (`PolygonRegion.fromPaths`). Orient each merged boundary
    ring so the ink lies to its left.
  - Sample boundaries at intervals of 1/200 of character height. From each sample, cast an inward-normal ray to the first
    boundary as **local stroke thickness (ink)**, and an outward-normal ray to ink in the same character as **local gap**.
    A gap greater than half the height is open space.
  - Exclude stroke ends: if a ray hits the same ring and the short boundary distance to that point indicates an end
    distance less than 1.5 times the surrounding stroke thickness (the maximum thickness within
    `2×두께 + 높이의 5%` in either direction), exclude it. This catches brush-tip taper, serif ends, and stroke-end corners.
  - Exclude corners similarly: exclude a gap-closing corner if its depth is less than `1.5 × max(획 두께, 간격)`.
    This catches ink traps, wedges, and cusps where strokes meet (between the bowl and stem of Anton a·g).
  - Use the **boundary-length-weighted bottom 10% percentile** separately for thickness and gap. The denominator is
    all non-excluded boundary length; boundaries facing nothing count as “unlimited.” A few narrow places in one
    character therefore do not determine the entire font.
  - Criteria: ink p10 ≥ **1.0mm** (minimum satin, PE-Design measurements 1.0–1.6mm), gap p10 ≥ **0.6mm**.
- **Single-line fonts**
  - Stroke thickness is thread thickness, so it is not limited. Divide strokes at intervals of 1/200 of the height; points within
    2.5% of the height of another stroke (or a distant part of the same stroke) are contacts. A contact cluster with at least
    3 stroke branches is an intersection, T, fork, or retrace junction. Two joined stroke endpoints are not a junction.
  - Contacts are not measured. Points within `1.5 × 간격` along the ink from a junction are also excluded.
  - From each point, cast rays along both normals to measure distance to another stroke. Parallel strokes closer than
    5% of the height are treated as one line drawn bold with multiple pen strokes (Hershey Medium·Bold·Gothic,
    3–4.5% of the height) and excluded.
  - If the facing point is reachable along the ink within `2.6 × 거리`, it is a **loop** (a circle is π/2);
    otherwise it is a **parallel gap**.
  - Criteria: gap p10 ≥ **1.0mm**, small-loop diameter p10 ≥ **1.5mm**. The denominator and “unlimited” treatment match outlines.
- **Recommended height** = the larger of the heights needed to raise each percentile to its criterion and the script-specific
  detail floor (Hangul 7mm, English 5mm), rounded up to 0.5mm increments. The floor applies even with no measurements,
  preventing 0 or infinity. All distances scale with height, so results are independent of measurement height
  (10mm·20mm give the same result).
- `limitedBy` (INK/GAP/LOOP/DETAIL) identifies the limiting factor; `inkP10Mm`·`gapP10Mm`·`loopP10Mm` are percentiles
  at the analyzed height (null if absent). `minStrokeMm` is the smallest of them.
- Grades: GOOD when analyzed height ≥ recommended height; CAUTION when at least half the recommended height;
  UNSUITABLE below that. Font badges use `FontQuality.fontGrade`: GOOD if GOOD at 10mm, CAUTION if not UNSUITABLE
  at 20mm; absence of Hangul or Latin caps the grade at CAUTION. Empty input is UNSUITABLE, with the floor as recommended height.
- `tightSpots` counts boundary length narrower than the criterion in 0.25mm units.
- Measurement samples are fixed by script: Hangul `가나다한글뷁쀍`, Latin `aegsRBQ&@`
  (`FontQuality.sample(hangul)`). Fonts with Hangul use the Hangul sample; others use Latin. Sample characters absent
  from the font are skipped.
- Version: `FontQuality.VERSION` (6). Included in stored `<파일>.quality.json` and `FontQualityKey`, so a version
  change reanalyzes already-downloaded fonts too.
- `stitchCount`, `jumpCount` sum `LetteringEngine.fromContours(..., sourceHeight = heightMm)` over each glyph.
- This is a geometric heuristic, not a guarantee of actual sewing quality for fabric/thread/needle combinations.

`ContourSimplifier.simplify(contoursMm, toleranceMm, minSegmentMm)` applies Douglas–Peucker to mm coordinates,
then merges short segments. It preserves open endpoints and closed contours/holes. Minimum segment length is a target:
short sections remain if removing them would cut a sharp corner beyond tolerance. Even large tolerances do not collapse
closed curves into lines. A value of 0 disables the corresponding pass.

`LetteringEngine.fromContours` adds `simplify: Boolean = true` and `minSegmentMm: Double = 0.0` after the existing
arguments. `mergeToleranceMm` is the DP tolerance, retaining the existing 0.05mm default.
`simplify = false` skips both simplification passes. Integer quantization and resplitting to the maximum stitch length still apply.

## Quality-analysis validation

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:assembleDebug
```

Engine unit tests verify the parser; mm size/bounds/center; spacing/line breaks; Unicode substitution; positive/negative arcs;
stitch length after quantization; contour/hole separation and closure; short-segment merging; baseline/reference height;
TextObject placement; and PES writing/rereading. Actual glyph generation by Android graphics APIs is outside JVM engine tests.
Quality-analysis tests (`FontQualityTest`) use synthetic glyphs to check thickness/gap/hole criteria, overlapping-contour unions,
taper exclusion, a single thin location, independence of measurement height, single-line intersections/Ts/vertices, parallel gaps,
small loops, bundled strokes, the Hangul floor, and empty input. Range regressions read redistributable repository fonts through
java.awt (`AwtGlyphs`, using the same reference characters/samples as the app), checking Jua 9–16mm,
Gowun Batang 15–30mm (ink-limited), and Hershey Simplex 4–12mm. Simplification tests check minimum-segment merging,
corner/closed-curve preservation, and `fromContours(simplify = false)`.

## Warm Craft lettering screen

`ui/lettering/LetteringScreen.kt` uses `MyembroTheme` color, typography, and corner tokens.
Thread colors use fixed tokens from `ui/theme/Thread.kt` (terracotta #C4552D, ivory, sage, gold)
so actual embroidery colors remain unchanged in dark mode.

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

- The hoop shows the current project (optional argument) and **actual generated stitches**. No decorative background
  flowers or filled embroidery are invented. Hoop/text fit can be toggled.
- Provides phrase input; 3 cards for Gowun Batang, rounded Jua, and Noto Sans KR; height/spacing sliders with fine-adjustment
  buttons; left/center/right alignment; curved-placement toggle, straight/arch/circle and curvature; thread-color chips;
  and Done/Add to design. Input and adjustment state survive configuration changes. Extension 2 below changes
  curve controls to upper/lower arc and radius.
- Quality warnings and recommended minimum height are fixed above the Add button. Warnings do not prevent adding;
  unsupported characters, hoop overflow, empty text, and stale results still being calculated do.
- Glyph extraction, font loading, and quality analysis run in the background. Input changes are debounced by 100ms;
  only the latest completed result is delivered to the callback.
- `onAdd` returns an editable `TextObject` with a new UUID, original text, font ID, mm height/spacing/angle, thread,
  and computed polylines. Multiline alignment is encoded in contour positions; left/right placement within the hoop
  is encoded in Transform. Installed single-line SVGs are also stored as open polylines.
- This screen provides host callbacks without modifying MainActivity/editor navigation.

Validation: `:app:assembleDebug :app:testDebugUnitTest` passed. An independent API 35 emulator checked light/dark screens,
font changes, 19mm/1.3mm adjustments, right alignment, curve toggle with a 35% arch, and sage thread selection.
The Add callback's Jua `봄날` result was saved as 329 stitches, PES 1,698 bytes; reread bounds matched.
The validation Activity/Manifest were removed from the final source.

## Lettering extension 2: multiline, radius, per-character thread, quality cache

- Input supports multiple lines; blank lines count toward line spacing. The UI's line-spacing field `줄 간격` means
  **additional mm spacing between character heights**. Baseline spacing is `heightMm + lineSpacingMm`, stored in
  TextObject as multiplier `lineSpacing = 1 + lineSpacingMm / heightMm`. Left/center/right alignment aligns shorter
  lines against the longest line; the entire design remains placed left/center/right within the hoop with a 5mm margin.
- Below the curve toggle are upper arc `위 원호` / lower arc `아래 원호` and an actual mm radius `반경`.
  When radius is specified, only the sign of `arcDeg` is used, not its magnitude (positive above, negative below).
  Each line is bent with the same radius at its own baseline, then moved back to its original line position.
  The UI limits radius to at least character height to prevent inward inversion.
- In thread colors, select all characters `전체 글자` or an individual character before tapping a chip. Applying to all
  clears existing per-character colors. When editing text, unchanged prefix/suffix characters retain colors at their
  moved positions; only replaced middle characters revert to the default.
- `characterThreads` keys are **code-point indices** after NFC, CR/LF, and tab (four spaces) normalization.
  Spaces/line breaks count too; do not confuse them with UTF-16 indices. `contourCharacters` holds each contour's
  character index, and `contourBaselines` its source-unit baseline. All contours of a character use the same thread;
  changes use trim + COLOR_CHANGE + jump. Consecutive same-color characters connect without a color change,
  preserving A→B→A color order too.
- TextObject and the `.myembro` codec add `lineSpacing`, `alignment`, `arcRadiusMm`, `characterThreads`,
  `contourCharacters`, and `contourBaselines` with defaults. Older files use defaults; new files regenerate without
  the font from per-character threads, coordinates, and baselines. Changing outline text/line spacing/alignment requires
  contour regeneration in the app. Hershey objects directly apply line spacing/alignment/radius/per-character threads in the engine.

### Font download screen connection contract

This screen does not implement font download/import UI. `onOpenFonts: (() -> Unit)?` is the only connection for
`글꼴 더 보기`; null disables the button. The selected installed font ID is returned through `initialFontId`.
The existing callback signature is retained.

API available to catalog cards:

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

`LetteringFontQuality.analyze` is suspending; extraction and analysis run on Dispatchers.Default. The three bundled
cards also show analysis through this API. Font SHA-256 (version for bundled fonts), extractor version, normalized
unique-character set, character height, and outline/single-line mode form the cache key. Color/alignment/line-spacing
changes alone do not trigger quality reanalysis.

An engine API independent of Android/the catalog is also provided:

```kotlin
val key = FontQualityKey(fontId, revision = "sha256:...:extractor-v2", sampleText = "자수", heightMm = 18.0, outline = true)
val cache = FontQualityCache.Shared // 또는 FontQualityCache(maxEntries = 64)
val report = cache.getOrAnalyze(key) { normalizedGlyphContoursMm }
val cached = cache.get(key)
cache.invalidate(fontId)
```

The provider runs only on a cache miss. This synchronized LRU retains up to 64 keyed reports; concurrent reads
reuse the same result. `getOrAnalyze` computes on the calling thread, so call it in the background. Exceptions are not
cached. If font bytes change, change the revision or call invalidate. `get`, `put`, `clear`, and `size` are also available.

## Single-line fonts: 1-ply running and 3-ply bean stitches

Single-line fonts are retained because they suit names and redwork. New Hershey text and installed single-line SVG
fonts default to **3-ply bean**, switchable to single running `1겹 러닝` through the lettering screen's
line-weight control `선 굵기`. Outline fonts retain their existing 1-ply running default.

```kotlin
val pattern = LetteringEngine.generate("Name", stitchStyle = StitchStyle.BEAN)
val singleLine = LetteringEngine.fromContours(
    contours, heightMm = 18.0, stitchStyle = StitchStyle.BEAN,
)
```

- `StitchStyle.RUNNING` sews movement A→B once; `BEAN` sews A→B→A→B. Expansion uses coordinates after
  splitting/quantization, so reverse stitches also obey the 2.5mm maximum. Each stroke's initial anchor remains only once.
  For running needle-down count N and stroke count S, bean has `S + 3 × (N − S)` stitches.
  Jumps, trims, color changes, and END are not duplicated.
- Bean traverses the same line three times, retaining bounds/character size. Unlike satin, it does not spread the line
  width threefold; overlapping thread produces a more distinct result.
- `TextObject.singleLine` distinguishes SVG single-line data stored as OUTLINE as well as Hershey. Together with
  `stitchStyle`, it is serialized in `.myembro` so identical stitches can regenerate without the font.
  Older files without a style field read as RUNNING, retaining their original stitch counts.
- Hoop previews, actual-stitch font cards, and the editor canvas use a **0.4mm** thread diameter.
  `ThreadRendering.widthPx(unitsToPixels)` receives the scale for engine 0.1mm coordinates. Width scales with zoom;
  the screen uses a minimum 1dp for visibility when zoomed out. If a native canvas is already scaled, divide the returned
  width by that scale before assigning it to paint.
- Single-line catalog/selection cards show the “Single-line · suitable for names and redwork” badge
  `단선 · 이름 새기기·레드워크에 적합`. `SingleLineFontBadge()` is reusable on other cards. For actual-stitch cards,
  use suspending `LetteringFontPreview.pattern(context, fontId, text, heightMm, thread)` and
  `StitchPatternPreview(pattern, modifier)`. Extraction runs in the background.
- The quality cache's `stitchCount` retains the basic **1-ply running** count for font comparisons.
  The lettering screen's actual stitch count and callback pattern reflect the selected bean/running style.

Validation covers bean forward/back/forward coordinates and stitch counts, arc/multicolor-contour separation and stitch
length, project round trips for both styles, older-file defaults, and PES write/reread coordinates.

## Phase 3b: basic satin columns

`SatinColumn.generate(leftRail, rightRail, options, thread)` is a pure Kotlin API.
Both polylines use **mm**, +y downward; output is a centered 0.1mm `EmbPattern`.
Rails running in the same direction are paired by normalized arc length; opposing open rails are automatically reversed
based on endpoint distances. Rails need not have equal point counts or sampling intervals.

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

- Density is **the distance between stitches on the same rail**. The default is 0.4mm; stitches alternate at half-cycle
  intervals, including the final rail pair. Cycle count uses the longer rail, so the shorter side may be denser.
  A 10mm straight column of 4mm width has 52 top satin stitches.
- Width is the distance between rails; `widthMm` is used for centerline offsets in `strokeToSatin`/lettering.
  When rails are supplied directly, they determine width. Pull compensation is added **to each side** of the top satin
  (3mm width + 0.2mm compensation → 3.4mm).
- Maximum stitch length defaults to 7mm, configurable within 0.2~7mm. Both width and diagonal movement are checked;
  long stitches are split evenly and split again if quantization error exceeds the limit.
  SATIN uses `satinOptions.maxStitchLengthMm` instead of `stitchLengthMm`.
- Center underlay traverses the centerline out and back. Zigzag underlay uses 60% of the original width with default
  spacing 1.5mm, returning to the centerline. Underlay target stitch length is 2.5mm. Both underlays and top satin connect
  within a column without jumps/trims. Existing trim/jump and per-character thread-change contracts between separate
  character strokes remain unchanged.
- Curved rails follow normals to a preflattened centerline. Vertices use miter joins, limited to 2 times the half-width at
  acute angles. Open ends use butt caps; closed paths close each rail's start/end. This basic implementation does not
  include offset-overlap removal at complex intersections/very tight curvature, manual rung direction, short stitches,
  or automatic satin routing.
- Single-line SVGs specify SATIN/options on `TextObject(singleLine = true, fontKind = OUTLINE, contours = ...)`.
  `fromContours(..., stitchStyle = SATIN)` also interprets each path as a **centerline**. This does not fill outline-font interiors.
- The default for new text remains BEAN; satin is explicitly selected. All `TextObject.satinOptions` values are retained
  in `.myembro`; older files receive defaults. This stage is an engine API and adds no satin-adjustment UI.

Conceptual reference: [official Ink/Stitch satin column documentation](https://inkstitch.org/docs/stitches/satin-column/).
Sources, licensing, and the scope of the independent implementation are recorded in `THIRD_PARTY_NOTICES.md`.

### SA431 small-hoop validation

Hoops use actual `HoopSpec` coordinates without hardcoded sizes. SA431's approximately 60×20mm landscape placement
(`HoopSpec("SA431", 600, 200)`) and 20×60mm portrait placement were tested. The dashed preview rectangle is the actual
sewing area and preserves aspect ratio. Text-fit zoom also follows the bounds center of left/right-placed text, preventing
it from moving offscreen. Hoop overflow is checked from actual stitch coordinates, independent of display scale.

A synthetic glyph of height 10mm and thickness 0.4mm still reports UNSUITABLE and recommended height 25mm even
when it fits the small hoop. Recommended height is not reduced to fit hoop dimensions. At 25mm, it exceeds the
landscape hoop's 20mm height but fits the portrait hoop; both were verified. Recommended-height warnings are quality
criteria, displayed separately from actual hoop-overflow guidance.

## Font library integration

`글꼴 더 보기` opens `FontLibraryScreen(onBack, onPick)` in `MainActivity` **over the lettering screen**,
preserving lettering input state. `onPick` returns the ID through `initialFontId`: a catalog ID or user font
`user:<uuid>`. `LetteringRenderer.loadFont` checks `FontRepository.find` then `FontRepository.userFont`.
User-font cache revision uses file size/modification time. Download/deletion/user-font deletion calls
`FontQualityCache.Shared.invalidate(id)`.

- Catalog: `assets/fonts/catalog.json` contains 102 fonts (30 Hangul outlines, 32 Latin outlines, 40 single-line),
  pinned commit URLs, and storage only after downloaded bytes pass SHA-256/size checks. Original licenses are saved
  alongside as `<id>.LICENSE.txt` (https only, 32MB cap). Only OFL-1.1, Apache-2.0, and Hershey licenses are used.
  Variable fonts use upstream static Regular/Bold versions. Noto Serif KR is noto-cjk's KR-subset OTF.
  Bundled Jua, Noto Sans KR, and Gowun Batang are excluded.
  - `scripts`: determined by reading the font cmap directly. Hangul requires at least 2,350 precomposed syllables;
    Latin requires all A–Z·a–z.
  - `style: "script"`: used by the script filter. `thinStroke: true` (Sacramento) adds a “Thin strokes · for running stitch” badge.
  - `recommendedMinHeightMm`·`qualityGrade`: present for every font. Shown on cards before download; after installation,
    replaced by a full report analyzed the same way in the app. Generation: the engine test `CatalogQualityTool` downloads
    from pinned URLs (sha256 checked, cached in `MYEMBRO_FONT_CACHE`), analyzes java.awt outlines, and rewrites only those fields.
    `MYEMBRO_FONT_CACHE=/tmp/fonts ./gradlew :engine:test --tests '*CatalogQualityTool*' --rerun`
  - Outline fonts with recommended height above 25mm receive a “Large lettering only” badge.
- Glyph availability: `Paint.hasGlyph` counts system fallback fonts, so even Latin-only fonts claim Hangul support.
  Analysis therefore measured fallback Hangul, giving all Latin fonts 0mm/unsuitable results. Downloaded and user fonts'
  TTF/OTF `cmap` is now read directly with `FontCmap`. The result is used for quality analysis, the reference character
  (ja/H), and lettering's “unsupported characters” decision.
- User fonts: copy TTF/OTF/SVG through SAF to `filesDir/fonts/user/`; after parsing/glyph checks, store script-sample
  quality results at 10/20mm in `<파일>.quality.json`.
- Quality: the app's `FontQualityGateway` sets only sample selection and script floors, using the engine's
  `FontQuality.analyze` unchanged (see “Measurement method” above).
- Catalog results for 102 fonts (p10 at 10mm, 2026-10-23): bold sans/display Anton 7.5, Bebas Neue 8.5,
  Alfa Slab One 6, Permanent Marker 7.5mm. Regular Hangul sans horizontal strokes are 0.55–0.65mm at 10mm,
  yielding 16–19.5mm (Nanum Gothic 16.5, IBM Plex Sans KR 17, Gowun Dodum 19.5).
  Black Han Sans, Do Hyeon, Gothic A1 Bold, and IBM Plex Sans KR Bold have gaps of 0.4–0.5mm between Jamo,
  making gaps the limit (12–15.5mm). Bold scripts: Pacifico 9, Lobster 16; hairline scripts: Allura 35,
  Great Vibes 49.5, Pinyon Script 56.5mm. Regular serif: 15.5–28.5mm (Hahmlet 15.5, Noto Serif KR 23, Nanum Myeongjo 28.5).
  Single-line: Hershey 5–7.5, EMS 5–13mm.
- PE-Design comparison: measured PE-Design lettering (characters approximately 10mm high) has satin-width p10
  0.9–1.3mm and median 1.5–1.75mm for block/serif/sans fonts. Script/small text reaches p10 0.6–0.75mm.
  The 1.0mm ink criterion suits block lettering and is somewhat conservative for scripts.

## Filling outline-character interiors (2026-10-23)

The lettering screen and editor text properties provide **Outline / Fill (tatami) / Satin**.
Single-line fonts use existing 1-ply running / 3-ply bean / satin with specified width, defaulting to bean.
Outline fonts also retain their existing outline default; fill is explicitly selected.

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

- Applies the even–odd rule to each character's paths, independent of hole winding, preserving nested islands too.
  Reuses JTS's existing `PolygonRegion` and `engine/digitizing/TatamiFill`. Pull compensation expands the outer
  boundary but excludes original hole areas so small holes do not close.
- Default stitch spacing is 0.4mm; tatami target stitch length is 3.5mm. Angles run clockwise with +y downward.
  Contour underlay is enabled by default, sewing 0.3mm inside the original boundary. Underlay that disappears in narrow
  sections uses the original boundary. Border choices are none/running/bean.
- Between characters: trim + jump, also COLOR_CHANGE when thread changes. Within a character, first try a straight
  connection inside the region; if it crosses a hole, run along a short detour on the visible boundary. Jump between
  disconnected regions that cannot be joined. This is not globally optimal routing.
- Local-pattern **actual moving needle-down stitches are 0.3~7mm after quantization**. Short sections merge and long
  ones split. Small corners that cannot safely merge are traversed by jump. Boundary error from integer 0.1mm coordinates
  is checked with a maximum of approximately 0.075mm. As with other stitches, enlarging through the editor Transform
  also enlarges stitch lengths.
- Outline satin checks both `FontQuality` minimum stroke thickness 0.6~4mm and actual crossing distance at the selected
  angle. Suitable glyphs receive alternating zigzags; wide or complex glyphs fall back to tatami with guidance.
  This is not a complete satin digitizer that automatically decomposes a glyph's strokes.
  `fromContours(..., outline = true, stitchStyle = SATIN)` selects this mode; the existing single-line offset-satin API
  with `outline = false` remains.
- `FillOptions` and mode are stored in `.myembro`. Older files use default options and retain existing outline/bean
  choices. Direct API input without character-owner indices is treated as one glyph; supply `contourCharacters`
  if inter-character trims are required.
- Small filled text is warned against using the greater of FontQuality's recommended height and a practical minimum
  of 6mm. The warning does not block adding. Editor properties also analyze stored contours, requiring no original font.
  Recommended height is always finite. Heights, recommended heights, font cards, and hoop dimensions show mm and inches
  together using shared `Dimensions`.
- Editor-property mode/transform changes calculate patterns in the background, then apply them as a single undo step.
  Detailed fill settings lead to the same lettering screen.

Validation tested synthetic `ㅁ`, circular `ㅇ`, two-hole `B`, reversed winding, nested islands, 0/37/90 degrees,
density/underlay/borders, stitch length after quantization, internal connections, multicolor/project/PES round trips.
On an independent API 35 emulator, actual Jua `ㅇㅁB` fit within 60×20mm SA431: 1,025 stitches, 7 jumps,
bounds 33.7×11.2mm. The empty center of `ㅇ` and reread PES bounds/stitch-count agreement were confirmed.
The validation Activity/Manifest were removed from final source.

## Automatic stroke-following fill — stage 2

New `FillOptions()` uses `strategy = FillStrategy.AUTO`, `autoDensity = true`. Instead of the preceding fixed-angle
tatami/narrow-glyph satin decision, it extracts actual stroke centerlines and paired rails for filling.
Choosing `FillStrategy.TATAMI` retains the existing fixed-angle fill.

```kotlin
val options = FillOptions(strategy = FillStrategy.AUTO, autoDensity = true)
val pattern = OutlineFill.generate(glyphContoursMm, options, heightMm = 10.0)
val columns = OutlineColumns.extract(glyphContoursMm) // 중심선·짝지은 양쪽 레일, mm
// TextObject / fromContours도 같은 FillOptions를 사용한다.
```

- `OutlineColumns` samples even–odd regions with at most approximately 384 cells on the long side and a minimum
  0.06mm cell. An independent implementation of Guo–Hall topology-preserving thinning obtains approximate centerlines;
  short branches are cleaned up, then directionally continuous strokes are joined at intersections. X diagonals can overlap.
- Rails pair intersections of lines perpendicular to centerline tangents with actual contours. Boundaries in separate
  regions across a hole are not selected. When crossing lengths suddenly increase at H/X junctions, they are limited to
  the column's representative width, letting another column cover the junction. Both rails are interpolated over the same
  centerline section to keep pairing aligned at rounded ends.
- Widths approximately **7mm or less** use ordinary satin; wider columns use staggered split satin with **maximum 4mm**
  stitches. Split positions shift through four phases to avoid a straight split seam. Stitch direction is perpendicular
  to the stroke; long-stitch/short-corner handling and hole safety checks also apply after quantization.
- Same-side needle spacing defaults to **0.40mm**. For requested character heights **below 7mm**, automatic spacing
  relaxes to **at least 0.60mm**. Height uses TextObject's reference height; direct `OutlineFill.generate` calls without
  height use contour ink height. Manually changing spacing in the UI disables automatic relaxation; the switch reenables it.
- Underlay is **center run** for narrow satin and **edge run + center run** for wide columns.
  Underlay/border toggles and bean-border selection remain available.
- Only areas not covered by extracted columns receive tatami supplementation; guidance is shown when complex contours
  leave a large supplemented area. These are approximate raster centerlines, not professional-digitizer optimization
  of every column in delicate decorations, sharp branches, or complex typefaces.
- Existing projects without `strategy`/`autoDensity` read as **TATAMI / false** to preserve previous sewing appearance.
  New files store both settings. Single-line fonts retain default bean, and the existing general `SatinColumn` two-rail
  API behavior remains unchanged.

Validation includes synthetic straight/rotated strokes, circular holes, X intersections, needle direction perpendicular
to curves, preserved rail pairing, the 7mm width boundary, 0.4/0.6mm height boundary, 4mm splitting/staggering,
underlay, older projects, and PES round trips. All repository test inputs are synthetic shapes.
User-provided reference material was read locally only. The same characters from publicly licensed bundled fonts were
generated at the same actual heights, visually comparing direction and splitting for ordinary/small/large sizes.
Differences in shape/stroke thickness between typefaces were considered separately. Reference files, extracted coordinates,
comparison images, and analysis scripts were not included in the repository, app resources, or test fixtures.

## Thread weight: 40wt / 50wt / 60wt

Selected in lettering and editor text properties. The default is 40wt, applied to automatic spacing for satin/split satin/tatami.
Running/bean paths and bobbin settings are unchanged. Values below are **digitizing presets**, not measured thread diameters;
they vary with thread material, fabric, needle, and tension.

| Top thread | Same-side spacing at height 7mm or above | Below 7mm | Quality-criterion multiplier | Stroke thickness / outline gap / single-line gap / loop (estimated) | Fill-height floor (estimated) |
|---|---:|---:|---:|---:|---:|
| 40wt | 0.40mm | 0.60mm | 1.000 | 1.00 / 0.60 / 1.00 / 1.50mm | 6.00mm |
| 50wt | 0.35mm | 0.50mm | 0.875 | 0.875 / 0.525 / 0.875 / 1.31mm | 5.25mm |
| 60wt | 0.30mm | 0.30mm | 0.750 | 0.75 / 0.45 / 0.75 / 1.125mm | 4.50mm |

Evidence checked: 2026-10-23.

- **Confirmed**: [Madeira's official Digitizing Density Guide](https://www.madeirausa.com/_resources/common/userfiles/file/Digitizing%20Density%20Guide.pdf)
  gives 0.40mm for Classic/Polyneon 40 and 0.35mm for 60. These are approximate guidelines varying with material/effect.
  FS 50 also has 0.35mm guidance, but that value concerns a specific metallic-thread product.
- **unconfirmed**: this app's **0.30mm** for 60wt is the user-requested default, differing from the manufacturer's 0.35mm.
  Universal suitability for all 60wt threads/fabrics and physical sew-out validation are unconfirmed. The 0.35mm setting
  for generic 50wt is an intermediate preset; generalizing FS 50 guidance is unconfirmed. Small-text 40wt 0.60mm/50wt 0.50mm
  and the 7mm cutoff are also app policy; universal manufacturer recommendations are unconfirmed.
- **Confirmed**: [Madeira's 60wt guidance](https://www.madeirausa.com/resources/embroidering-with-60-weight-thread/)
  recommends size 65 needles and denser digitizing, giving block-letter examples of 6–7mm for 40wt and 3mm for 60wt.
  The [Classic product guidance](https://www.madeira.co.uk/embroidery-threads/classic/) gives a 60wt needle range of
  60/8–65/9. The possibility of 3mm text does not guarantee every font (especially complex Hangul), so the app's height
  floor is not lowered directly to 3mm.
- **unconfirmed**: quality criteria and height floors are conservative heuristics multiplying the 40wt reference values
  (stroke thickness 1.0mm, outline gap 0.6mm, single-line gap 1.0mm, loop 1.5mm, fill floor 6mm in the “Local percentiles”
  section above) by `ThreadWeight.featureScale = 일반 간격 / 0.40`. Manufacturer rules or physical sew-out results directly
  validating 50/60wt values are unconfirmed. Measurements (bottom 10% percentile) do not depend on thread weight;
  only recommended height, grade, and tightSpots change. Character-detail floors (Hangul 7mm, Latin 5mm) are not lowered
  for thread weight. (2026-10-23 integration: this section was originally written over the old minimum-value analysis
  with a 1.2mm criterion; during merging, it was changed to scale percentile-analysis v6 reference values.)
- For thin thread, the UI advises “65/9 needle recommended · retain existing bobbin settings.” The 60wt basis for 65/9
  is above; generalizing to all 50wt and a universal sewing basis for leaving the bobbin unchanged are **unconfirmed**.
  This means the app does not automatically change bobbin settings; it does not replace the actual machine/thread
  manufacturer's bobbin guidance.

Changing thread weight enables automatic spacing. Subsequent density-slider adjustments prioritize manual spacing;
the automatic switch restores presets. Default underlay/borders remain. `TextObject.threadWeight` and
`SatinOptions.autoDensity` are saved; older files restore thread weight as 40wt and satin automatic spacing as false,
preserving existing designs' manual spacing. Font-quality cache keys include thread weight, preventing mixed results
across weights. The shared `FontQuality.analyze` API adds an optional argument at the end, defaulting to 40wt.

Validation: 240 engine and 145 app unit tests plus `:app:assembleDebug` passed. Synthetic shapes checked satin/tatami
stitch counts by thread weight, quality grades/heights, cache separation, older-file restoration, manual-spacing retention,
and project/PES round trips. An independent API 35 emulator confirmed `봄날` displays of 60wt 0.30mm/minimum width 0.90mm,
50wt 0.35mm/minimum width 1.05mm, and thin-thread guidance. 50wt persisted when adding/reediting lettering; after applying
60wt in editor text properties and reopening, minimum width 0.90mm and the changed recommended height persisted.
Physical sew-out validation is unconfirmed.

## Physical sew-out defect response and stable-tag satin port (2026-10-24)

TTF satin inferred from raster centerlines was removed from the default generation path. SATIN requests that fail
column validation also fall back to auto-fill + contour running with a warning. Explicit Ink/Stitch font rails/rungs
use a separate satin core. A public OFL Dancing Script Bold `Jung Min`, 34mm regression and PNG comparison were added.

Port sources, exact defaults, differences, shared StitchPlan integration, and current independent-gate blockers follow
the [Ink/Stitch port contract](inkstitch-port.md). Passing our own unit tests does not imply gate passage or successful
physical SE400 sewing. Earlier documentation of “TTF automatic satin/split satin” describes experimental history,
not current default generation behavior.


### S2 revision: text, font, height, and color only (2026-10-24)

New lettering defaults to running. Automatic satin conversion, fill, bean, density, underlay, thread weight, curves,
spacing, and alignment choices were removed from the default screen. Stitch type displays “Automatic · Running”
rather than a selector. Verified original satin columns remain an engine API, without inferring TTF satin by our own method.

embro-codex's `auto-rules.md` confirmed that safe character-height/satin-width boundaries are unconfirmed.
Under the coordinator's decision, satin is currently blocked while running is allowed. No arbitrary slider limits are invented
for unconfirmed ranges; height is currently entered numerically. When actual verified font ranges arrive, the slider will
be restricted to them. Heights of 0 or less, non-finite heights, and results outside the hoop cannot be added.
The previous in-house FontQuality minimum height is not used for this decision. Rule inputs, reproduction commands,
and remaining blockers follow the latest S2 section of [inkstitch-port.md](inkstitch-port.md).

The b798af7 temporary locks were removed in favor of shared S1 postprocessing. Valid source locks' S0/short splits are
preserved, so the earlier claim “all actual stitches at least 0.3mm” is not a guarantee of current postprocessing.
Existing saved projects' explicit fill/bean settings remain readable, but are not exposed as new UI choices.

### Editor lettering edit-sheet UI regression (based on staging/s2s3 be02ff3)

The sheet rejects transitions to Hidden; outside taps/drags do not cancel drafts. System/top Back asks for discard
confirmation when changes exist; Cancel retains the draft. Only Done applies to the existing object.
The font list uses its existing Back behavior to return to the lettering draft. The preview uses the same themed rectangular
frame/10mm grid as the editor and preserves the existing object's position/rotation/scale. Only new text is placed at
the origin. Renderer, generation engine, and stored-placement contracts were unchanged.

Before/after emulator behavior was checked through actual EditorScreen/LetteringScreen in the separate
`io.github.linker2k.myembro.lettersheetqa` package: AB, Hershey,12mm, Transform(230,-180,15°), 100×100mm hoop.
Checks reproduced old drag cancellation, confirmed retention after the same drag+outside tap after the fix,
Back confirmation→Cancel retaining the edited draft, Done→reedit, and the entire object unchanged after explicit discard.
Screenshots/XML/autosave comparisons and test output:
`/tmp/myembro-port-out/letter/sheet-ui/` (`before.png`, `after.png`, `confirm.png`, `output.txt`).

Validation: all of `./gradlew check :app:assembleDebug` passed; engine586 and appDebug/Release190 each, failures0;
Lint passed. LetteringPreviewPlacementTest3 cases check position/rotation/size, new-text centering, and draft-change fields.

### Hangul satin quality display in the font library (2026-10-24)

Hangul TTF cards no longer show older fill-based `fontq` grades/recommended minimum heights. Actual centerline satin
previews/caching, height sweeps for three fonts, and limitations are in the [validation record](qa/font-library-satin-preview.md).
A two-character sample passing the gate does not certify a recommended height for the entire font.

### Single-line font Normal/Bold (v0.2.8)

New single-line text defaults to **Normal (RUNNING)**. **Bold** uses Ink/Stitch bean `repeats=[1]`, totaling 3 passes.
Originally overlapping sections may receive 6 passes; they are preserved because upstream has no automatic option to
reduce visits to already-traversed segments. The selector is hidden for fonts with their own bean stitches and predigitized
embroidery fonts. [Behavior, upstream evidence, and 5556 smoke/gate checks](qa/single-line-weight.md).
