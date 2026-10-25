# Photo correction selector

2026-10-25, `feature/photo-filters`. Independent oracle branch `0ee0f56` is merged.

- Step 2 adds localized Original / Clean / Contrast / Black-and-white separation chips. Original is the default for each new session; no preference silently changes it. Black-and-white separation is absent in Color drawing, and switching there resets an Otsu selection to Original. The existing color-count slider remains the only posterization control.
- Monochrome Original and Clean share the exact existing single median pass and legacy contrast retry. Clean does not add a second pass. Contrast calls upstream autocontrast after that baseline median and does not add a hidden contrast retry. Otsu thresholds the same baseline's BT.709 grayscale and sends its explicit dark-ink mask to Potrace, without Wu or top-left background inference.
- Opaque color Original is byte-preserving; Clean uses the existing median helper once, and Contrast uses autocontrast. Any transparent/partial-alpha color pixel bypasses correction for the entire raster, preserves original ARGB bytes, selects Original, and shows a localized explanation. Otsu is unavailable in color mode.
- Changing a correction uses the existing debounce/revision/cancel path and clears stale contours/color preview/PES. Step 2 still performs no stitch generation. Rejected Otsu masks are published before reporting the error; its mask polarity is not silently reversed.
- New adapters contain no new image or embroidery algorithms. Ink/Stitch parameters, small-area policy, physical sizing, color cleanup and downstream sewing checks retain their previous behavior.

## Automated checks

`PhotoCorrectionTest` compares each raster adapter to its engine function, checks transparent color bypass, and checks a non-idempotent noise raster to distinguish one median from two. `LegacyPhotoPipelineV029` freezes the released `v0.2.9` integration for a synthetic input: default raster/contours and both silhouette/outline PES outputs must match byte for byte. The frozen code is used only in tests.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :engine:check :app:testDebugUnitTest :app:lintDebug -PmyembroTestRunner=io.github.linker2k.myembro.ui.photo.PhotoFilterSmokeInstrumentation --console=plain
```

Independent `PhotoFilterGoldenIntegrityTest` verifies codex's original fixture corpus. Port comparisons still assert exact upstream thresholds and bytes, without requiring Python in CI.

## Development-device smoke

Only emulator-5556 is installed. The opt-in runner opens the repository launcher artwork from the independent fixture, visits each correction, and requires a preview/outline without errors or generated stitches. It also checks the Original default and reset when switching from Otsu to Color drawing.

```sh
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.photo.PhotoFilterSmokeInstrumentation
```

Observed output: `PHOTO_FILTER_UI_PASS screenshots=4 default=ORIGINAL no_stitches=true color_resets_otsu=true`.

Korean screenshots, visually inspected after capture:
`/tmp/myembro-filter-shots/original.png`, `/tmp/myembro-filter-shots/clean.png`,
`/tmp/myembro-filter-shots/contrast.png`, `/tmp/myembro-filter-shots/otsu.png`.
Each is 1080×2400. No personal input or screenshot is used; screenshots are not bundled.
The temporary per-app Korean locale was restored to its original empty/system setting.

The combined APK/engine/app-test/lint command completed with `BUILD SUCCESSFUL in 3m 15s`.
The 5556 smoke completed with `INSTRUMENTATION_CODE: 0`; its result string above is the
explicit success assertion. Logs are `/tmp/myembro-filter-ui-check.log` and
`/tmp/myembro-filter-ui-smoke.log`.

Final engine validation used the merged corpus directly:
`MYEMBRO_PHOTO_FILTER_ORACLE="$PWD/engine/src/test/resources/photo-filters" JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:check --console=plain`
→ `BUILD SUCCESSFUL in 56s`, including all 6 raster regressions and `ORACLE_EXACT_TOTAL 39`.
