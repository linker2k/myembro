# S3 tatami app integration

S2 was not on main, so letter commit111ae77 was integrated into model. Preserved model's verified
TatamiFill/FillRouting·InkAutoFill adapter and the main S1 contract.

## Applied paths

- Added `채우기` to automatic selection for closed TTF/OTF outlines(including Hangul). TTF fonts
  without verified satin rails are sent to default tatami, rather than guessing satin columns.
- Explicit `StitchStyle.FILL` goes through TatamiLettering in LetteringEngine.fromContours and uses
  DefaultTatami's default tatami+underlay stitch groups. Existing density/angle/border/pull-compensation options
  do not apply to this product-default path. JSON fields and legacy direct APIs remain for compatibility.
- If the satin rail API's maximum width, measured as distance to the opposite rail, exceeds6.0mm, switches to the same fill.
  Does not mistake rail direction/sampling order or long diagonal stitches themselves for width. For centerline satin requests,
  if explicit widthMm exceeds6.0mm, sends the rail outline to the same fill.
- Boundary rule is the coordinator-adopted auto-rules.md range0.9–6.0mm(inclusive).
  Tests methodForWidth: below0.9=RUNNING,0.9~6.0=SATIN,above 6.0=FILL.
  Does not newly approve narrow satin outside existing verified profiles.
- Automatic lettering displays `자동 · 채우기`; the height slider displays `채우기 높이`.
  Height is5–90mm; asserts both endpoints equal the actual size_grid_mm minimum/maximum in the sweep JSON.
  90mm is a size ceiling, not proof that every glyph/font/sentence passes. Existing hoop-overflow checks remain.
- Preserves each glyph's color and trims at glyph boundaries. Records default options in S1 group-finalization metadata
  so applying app defaults does not regenerate existing locks(including existing idempotency regressions).

## Representative integration tests6cases

Width boundaries, automatic fill/height ceiling, exact needle-coordinate equality with DefaultTatami groups,
Hangul components/trim, actual bundled Jua TTF 'embroidery', and rail switching above 6.0mm.
The actual TTF test reads repository `app/src/main/res/font/jua_regular.ttf` with JVM AWT,
flattens curves, and calls the same engine API that receives Android-extracted outlines.
Does not claim device testing of font extraction/screen interaction on Android hardware. No private files.

When merging S2, adjusted tests with old legacy assumptions to the current contract. Restored main's filtered-body
validation and used source half_stitch's0.5~1.5mm adaptive range. Asserts source-default fill does not change
stitches based on thread thickness/legacy density options. Thresholds/gates were not relaxed.

## Execution output

```text
./gradlew :engine:check :app:assembleDebug :app:testDebugUnitTest --console=plain
BUILD SUCCESSFUL in 19s
engine: 552 tests, 0 failures, 0 errors
app: 176 tests, 0 failures, 0 errors

TatamiLetteringIntegrationTest: 6 tests, 0 failures
JUA 자수: 1230 stitches; 38.6 x 20.0 mm

/tmp/myembro-quality-gate/quality-gate check engine/build/s3-fill --json /tmp/myembro-s3-fill-gate.json
PASS 1/1 cases passed
```

The actual TTF PES has maximum density9penetrations/0.5mm cell, consecutive identical points1, and maximum stitch below4.002mm.
Short-stitch ratio was displayed as a gate reference metric, not a blocking reason.
[Commands/machine verdict JSON](s3-fill-app-integration.json), full local log
`/tmp/myembro-s3-fill-final.log`, candidate `engine/build/s3-fill/jua-korean.pes`.
APK generated at `app/build/outputs/apk/debug/app-debug.apk`.
Remote CI green/actual sewout approval are separate. This unit stops here awaiting coordinator review.
