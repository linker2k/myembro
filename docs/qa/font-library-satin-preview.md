# Font screen: satin preview/input support/favorites (2026-10-24)

Baseline: origin/main `0f5251b`. Used only public OFL catalog TTFs, no private designs.

## Fixes

- Separated installation state from preview state. Missing previews show generating/failed for installed fonts; download guidance appears only for uninstalled fonts.
- Previous initialization completed preview → fontq analysis for one font before handling the next. Installation checks displayed first, so later fonts could appear with download guidance. Separated preview work from sequential quality analysis; failure in one font no longer blocks the next.
- Catalog TTFs generate actual stitches through `LetteringFontPreview`. Korean `자수` uses centerline+satin+clump order; non-Korean `Abc` uses existing automatic running. Stored in Android cacheDir keyed by SHA-256/backend version/phrase/height/sewing defaults. Korean card height 15mm is a preview sample, not a recommended minimum height.
- Removed existing fill/fontq grades/recommended heights/thin-stroke badges from Korean TTF cards. The two-character trial below does not certify minimum heights/grades across whole fonts. Thus no new unsupported recommendation is displayed.
- English embroidery fonts were disabled because the caller checked `covers(text)` against the current phrase. Cards now explain unsupported Korean/some characters. Dim only the preview, not descriptions/hearts. Calls without input default to `text=""`; every font passes support checks. Home/settings callers must not pass the previous lettering phrase.
- Store hearts in `myembro_settings.favorite_font_ids`. Show current-tab favorites first, then others in existing order. Quick selection retains the current font and prioritizes two compatible favorites over recent/similar fonts. Observe settings changes so updates require neither reopening the sheet nor selecting a font. Included in existing settings backup/restore; verified restore round trip on actual Android.

## Korean satin recalculation

Phrase `자수`, Black 40wt, 100mm hoop, current app `automaticLetteringState` → `renderLettering` → `PesWriter` path. Width is the actual constant-column width applied to centerline satin using model's existing per-glyph `StrokeWidthMeasurement` median. It is not minimum/maximum thickness at every point of the original outline.
Width condition **1.0–6.0mm** follows this coordinator instruction and `/tmp/myembro-quality-gate/auto-rules.md` policy (lower bound Ink/Stitch `min_satin_stroke_width=1mm`, upper bound based on corpus). Gate PASS and width condition are reported separately. Sewing safety across continuous heights/other characters/whole fonts is unconfirmed. Final decision is user actual sewout.

| Font | Height mm | Column width min–max mm | Width condition | Quality gate |
|---|---:|---:|---|---|
| gowun-dodum | 10 | 0.635–0.700 | Below minimum | PASS |
| gowun-dodum | 15 | 0.953–1.050 | Below minimum | PASS |
| gowun-dodum | 20 | 1.271–1.401 | Pass | PASS |
| gowun-dodum | 25 | 1.589–1.751 | Pass | PASS |
| do-hyeon | 10 | 1.387–1.400 | Pass | PASS |
| do-hyeon | 15 | 2.080–2.100 | Pass | PASS |
| do-hyeon | 20 | 2.774–2.800 | Pass | PASS |
| do-hyeon | 25 | 3.467–3.500 | Pass | PASS |
| nanum-gothic | 10 | 0.668–0.704 | Below minimum | PASS |
| nanum-gothic | 15 | 1.002–1.057 | Pass | PASS |
| nanum-gothic | 20 | 1.335–1.409 | Pass | PASS |
| nanum-gothic | 25 | 1.669–1.761 | Pass | PASS |

Gate v7, thresholds SHA-256 `18bf3e7cc70e872680368efdb3319e8d1794587337eefdb4e9c7cc97a116b95d`: **12/12 PASS**, **9/12** also meet width conditions. Gate code/limits unchanged.

## Reproduction and output

On a development emulator (`emulator-5556`) with 3 catalog TTFs/LICENSE downloaded and installed:

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest \
  -PmyembroTestRunner=io.github.linker2k.myembro.ui.lettering.FontSatinPreviewInstrumentation
adb -s emulator-5556 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w \
  io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.lettering.FontSatinPreviewInstrumentation
adb -s emulator-5556 exec-out run-as io.github.linker2k.myembro \
  tar -C files -cf - font-satin-preview-qa > /tmp/font-satin-preview-qa.tar
# 압축 해제 후: quality-gate check --text --style satin --json gate.json font-satin-preview-qa/
```

`./gradlew check`: PASS. `FavoriteQuickFontsTest`: 2/2,`InkStitchCatalogTest`: 7/7。Android instrumentation: three fonts' actual satin previews/disk-cache stitch equality/settings-favorites backup and restore PASS. PES per height and sweep.tsv generated in app `files/font-satin-preview-qa/`.

Work output: `build.log`, `instrumentation-5556.log`, `gate.txt`, `gate.json`, `font-satin-preview-qa/sweep.tsv`, and 12 PES in `/tmp/myembro-port-out/letter/font-library-fix/`.

Before/after screens: `before-korean.png` (previous Typeface preview/old recommendations), `before-latin.png` (dim cards without explanation), `after-korean.png` (actual satin for three installed fonts/no old grades), `after-latin-incompatible.png` (reason displayed), `after-favorite.png` (Nanum Gothic first), `after-quick-fonts.png` (Nanum Gothic second quick selection). Before captures used the existing APK before emulator-rule changes; after captures/reinstrumentation used 5556.
