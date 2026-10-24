# Hangul centerline satin app integration — for branch review

Preparatory changes awaiting the user's KRSAT3 sewout results. **Push only to codex/letter;
do not merge into main or release.** Preserves the existing fill implementation and FILL regeneration of saved data.

## Generation and persistence path

- TTF strings consisting solely of NFC Hangul syllables and spaces/newlines default to automatic SATIN.
  Mixed Latin/digit strings, single-line fonts, and Ink/Stitch fonts retain their existing automatic selection paths.
  No satin/fill selection UI was added. Card previews and adding to the editor use the same path.
- Android Paint 128px·monochrome·8px pad (same sampling as KRSAT3) → upstream AutoTrace
  thinning/trace/fit → 8-connected ink components → 50% vertical-overlap line detection →
  glyph/component order → upstream convert-to-satin/auto-route → shared S1 half_stitch.
- Extraction runs in the existing worker coroutine path. Raster limits match existing AutoTrace:
  axis 4096px/1,048,576 pixels. No Android types were added to the engine.
- Preserves source-unit centerlines, glyph/component ownership, and per-glyph measured widths in nullable
  `TextObject.centerlineSatin`. The new payload itself is version 1; existing Project and
  TextObject field signatures/defaults are preserved. Saving/restoring and height/thread/layout changes can
  regenerate without the font. Editing text/font replaces it with new data on Android.
- Existing FILL still uses LetteringEngine.fromContours even when the new field is present.
  Old projects without the field are read as before. Curved centerline glyph layout is
  not yet supported and stops with an explicit error (the current 4-input UI uses straight layout only).

## Evidence for width, weight, and height

Does not copy a fixed 1.13mm to arbitrary fonts. Imported existing model
`geometry/StrokeWidthMeasurement.kt` unchanged and uses the per-glyph interior Voronoi
median. Source model `33a6683`(last change to that file), SHA256
`a7bb5829cb688e3911827b9325ce3d407b8e8fb9b469c25f33a2ce543a3296e4`.
Reuses existing PolygonRegion even-odd hole handling and JTS; there is no new width-estimation algorithm.
At 15mm, boundary sampling 0.1mm matches the preceding public sweep. Results are preserved in source units
and scaled proportionally when height changes. Do not interpret them as minimum-width/quality certification.

Reading the bundled Noto variable TTF directly defaulted to the Thin axis on Android.
Only the new Hangul satin path uses the existing `myembro_sans.xml` explicit 400 family,
matching KRSAT3 Noto 400. Existing fill/outline loading is unchanged.
Measured widths for actual app 'Jeong Min' 15mm: Jeong 1.1245mm·Min 1.1323mm.

The fill sweep's verified height range is not inherited by satin. The user's decision changes the Hangul
default method without creating a new general satin-certified range. Existing app 60mm/
hoop limits remain; the screen uses direct height entry and a connected-component explanation(ko/en).
Actual files must pass P1–P8 of the existing writer/gate.

## Tests and emulator

- Engine: exact stitches after centerline payload save/restore, height changes/PES round trip, invalid data,
  Hangul/mixed-string policy, distinguishing existing measured widths, explicit FILL preservation regressions 4 cases.
- App: Hangul SATIN·centerline=true, non-inheritance of fill-certified range/height-limit regression 1 case.
  Existing fill-range regressions retain Latin samples.
- `HangulSatinAppInstrumentation`: **actual Android renderLettering** passes Noto 400
  'Jeong Min' 15mm automatic satin, exact stitches after offline ProjectJson save/restore,
  height 20mm regeneration, and PES generation. Not simulated AWT glyphs.
- Final Android-generated `APPSATIN400.PES`: **623 stitches·6 jumps·5 trims·6 components**.
  gate v7 PASS, 0-length commands: 0·missing start/end locks 0·maximum per cell 5·origin/header/hoop checks PASS.
  Uses Android Paint glyphs/reference/character spacing, so does not claim byte identity with KRSAT3.
  Existing KRSAT1/2/3 are unchanged.

Output `/tmp/myembro-port-out/letter/hangul-app/`:
`instrumentation400.log`, `result400.txt`, `APPSATIN400.PES`, `saved400.myembro`,
`gate400.json/log`, `final400-check.log`.
Verified actual screens Create→Lettering→automatic satin/preview/Add enabled, adding to the editor→existing text edit
sheet retaining text/automatic satin.
Screenshots `lettering-ready.png`(default Gowun Batang), `editor.png`, `edit-lettering.png`,
`lettering400.png`(final Noto 400 selection). The latter shows the ready final APK screen.

## Reproduction

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.lettering.HangulSatinAppInstrumentation
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
adb install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.lettering.HangulSatinAppInstrumentation
```

Official Ink/Stitch auto-route oracle equivalence and general font/height sewout certification remain separately unassessed.
These automated tests and the gate do not constitute user sewout acceptance or approval to merge into main.

Final `./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest`: BUILD SUCCESSFUL
in 23s, engine 671·app Debug 206/Release 206, failures 0·Lint passed.
Emulator API 35 final instrumentation code-1 PASS; confirmed the screenshot with Noto 400 font-card
preview ready. No main merge or user APK delivery was performed.
