# Do photo corrections change the actual app output?

2026-10-25, `feature/photo-filters`. This unit changes tests/documentation only; no production filter, default, stitch parameter, or routing behavior was changed to obtain differences.

## Input and comparison

The new instrumentation creates its own opaque 128×128 PNG: a smooth integer RGB gradient with R=128..152, G=128..140, B=128..136. A 4×4 top-left patch uses the gradient's existing bright endpoint (152,140,136), satisfying the current known-background-pixel contract without introducing an out-of-range bright border. There are no personal photos or external illustrations.

The same loaded source, crop, low-detail raster, OUTLINE mode, and physical frame width are held constant across Original / Contrast / Otsu. The size control is set once before comparisons. A filter can change the ink extent, so the displayed ink width can change even though the physical picture-frame width does not; instrumentation asserts that frame width is unchanged.

`PhotoFilterDifferenceInstrumentation` drives the real `PhotoViewModel.load/prepare/next` flow and checks:

- Source decoding retains every synthetic input pixel; all preview rasters are 128×128.
- The actual `separatedPreview` equals the engine's expected corrected/quantized pixels, and `outlineSvg` equals the expected engine contours. A missing or stale filter connection fails these comparisons.
- Preview finishes without errors, stitches, or a sewing report.
- Step 3 produces complete sewing analysis with no blockers. The actual cached PES bytes (read by test-only reflection) equal the writer output for the app's rendered pattern.
- All three app separation-mask hashes and cached PES hashes differ. Engine-adapter raster hashes also differ. SHA-256 is over pixel/PES payloads, not screenshots whose chip selection would differ anyway.

## Observed actual-app result on emulator-5556

| Correction | Ink pixels | Contours | Stitches | Blockers | App mask SHA-256 prefix | Cached PES SHA-256 prefix |
|---|---:|---:|---:|---:|---|---|
| Original | 10,865 | 1 | 198 | 0 | `92c0464367b7` | `c4c2e7d7c54d` |
| Contrast | 8,113 | 1 | 198 | 0 | `903c4c7f6a38` | `ea483166b1b6` |
| Otsu | 7,935 | 1 | 228 | 0 | `2b0898915961` | `b9a785e55ece` |

Full hashes and numeric output: `/tmp/myembro-filter-shots/low-contrast/result.txt`.
The runner's generation timing includes its UI settling/wait interval and is not a backend performance benchmark.

```text
PHOTO_FILTER_DIFFERENCE_PASS rasters=3 masks=3 pes=3 ui_engine_exact=true screenshots=3
INSTRUMENTATION_CODE: 0
```

Korean screenshots, captured only on development emulator-5556 and visually inspected:
`/tmp/myembro-filter-shots/low-contrast/original.png`,
`/tmp/myembro-filter-shots/low-contrast/contrast.png`,
`/tmp/myembro-filter-shots/low-contrast/otsu.png`.
The outlines are visibly different: mostly rectangular, diagonal, and stair-stepped respectively. The app locale was temporarily Korean and restored to system afterward. No screenshots/PES outputs are bundled with the app or committed.

## Why simple inputs can legitimately look identical

`PhotoCorrectionDifferenceTest` includes a black/white geometric control. All three corrections produce exactly one unique separation mask for this control, as asserted in code. Autocontrast is identity when each channel already spans black to white; upstream Otsu and Wu 2-color separation recover the same binary regions. A processed RGB raster can also change while subsequent 2-color quantization still assigns the same spatial labels. The existing Original fallback may itself retry contrast on eligible failures.

An initial low-contrast flat-region probe also yielded one mask and one PES across all corrections. Low contrast alone therefore does not imply a different sew-out. The final gradient exposes both the original Wu 5-bit bucket resolution and the different color/luminance partitions. No threshold or filter default was adjusted in response to identical results.

For this branch, the executed app comparisons establish that the correction path is connected and changes output on a distinguishing input. The earlier face result alone was inconclusive; it is not evidence of a missing connection. This does not claim every photo must change or that different output is necessarily better.

## Reproduce

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*PhotoCorrection*Test' --console=plain
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.photo.PhotoFilterDifferenceInstrumentation --console=plain
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.photo.PhotoFilterDifferenceInstrumentation
```

JVM output: `BUILD SUCCESSFUL in 1s`, 8 tests, no failures; low-contrast witness asserts three distinct masks/PES, high-contrast control asserts one identical mask, plus six existing adapter/legacy regressions. APK/test APK build: `BUILD SUCCESSFUL in 1s` on the final incremental run. Logs: `/tmp/myembro-filter-difference-tests.log`, `/tmp/myembro-filter-difference-build.log`, `/tmp/myembro-filter-difference-smoke.log`.
