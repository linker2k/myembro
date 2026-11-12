<!-- SPDX-License-Identifier: GPL-3.0-or-later OR MIT -->

# Realistic preview speed experiment

Archived report copied from experiment commit `e6b768ea` on `agent/realistic-speed-exp`. Experiment code/flags and the reproduction commands below belong to that separate branch; they are not merged into `agent/realistic-fix`. The selected production policy is condition B (16px/mm serial).

This is a debug-only comparison branch based on `a7f344fb`, not a deployment or a change to the production rendering policy. Owner authorization: 2026-11-06. The original stitch path, alpha rasterization, Gaussian blur, lighting, premultiplied bake, ordered source-over composite, and area-weighted box reduction equations are unchanged.

## Conditions and implementation

| Condition | Native resolution (px/mm) | Independent tile baking |
|---|---:|---|
| A | 32 | Serial |
| B | 16 | Serial |
| C | 32 | `Dispatchers.Default`, up to available processor count |
| D | 16 | `Dispatchers.Default`, up to available processor count |

`RealisticSpeedExperiment.configure()` accepts only 16 or 32, requires a debug build, and must run before opening the document. The normal initial configuration remains 32/serial. Parallel workers each own an Android Canvas/mask rasterizer. A batch retains at most one stitch tile per worker. Tiles are composited in the original PES order, after the batch completes; overlapping pixels are never written concurrently. Worker count is a concurrency bound, not a guarantee that all cores are continuously occupied. Coroutine scheduling, rasterization and the serial bitmap composite remain part of the measured cost.

Below native resolution, the existing area-weighted box implementation resolves the viewport. At or above native resolution, the native crop is reused. No renderer equation, thread color, sewing command or PES safety rule is changed. A unit test checks identical serial/parallel pixels for a small crossing-stitch scene at both native resolutions; this is an implementation equivalence test, not a visual quality score.

## Device and procedure

- Existing `emulator-5556`, `sdk_gphone64_x86_64`, Android API 35, 1080×2400, density 420dpi (2.625), 4 online CPUs (`getconf _NPROCESSORS_ONLN`). No emulator start/stop, uninstall or application-data reset.
- Update installation with `adb -s emulator-5556 install -r` for the debug APK and the instrumentation APK.
- Input: `/tmp/myembro-headless-samples/out/svg-01-skull.pes`, 11,100 stitches, original colors, same viewer fit and centered pinch location. No input PES or screenshots are committed.
- Each condition starts an instrumentation process, opens the file through `ViewerViewModel.openFile`, and toggles realistic mode on/off for 3 native-source rebuilds. These are not 3 fresh application launches.
- First-display time is elapsed monotonic time from the accessibility toggle operation to observing `viewer_realistic_image`; it includes toggle lookup, UI publication and accessibility polling overhead. Poll interval is 100ms plus traversal/IPC time. `RealisticPreview` logs separately record native render time; `RealisticSpeed` logs record viewport publication time and the actual camera zoom.
- Zoom time starts at the final pointer-up and ends after the replacement is published and UI becomes idle. The prior image must remain present. The final logged zoom must be 3.0 (floating-point rounding allowed). Touch slop consumes the first pinch step; the final pointer separation accounts for that.
- `dumpsys meminfo io.github.linker2k.myembro` is sampled approximately once per second. Reported PSS is the maximum observed `TOTAL PSS`, including UI, instrumentation and screenshot overhead, not a guaranteed instantaneous peak or renderer-only allocation.
- Screenshots are collected after trial 3 at fit and after the centered 3× pinch. PNG encoding and capture happen outside first-display/zoom timing, but can affect sampled PSS.
- An earlier pilot used an actual 2.25× pinch; its zoom timings and screenshots are excluded. A transient-loading-indicator wait was also replaced with an explicit debug publication counter so that immediate native-crop reuse cannot be missed.

## Results

Final measurements and screenshots: `/tmp/myembro-realistic-speed/final/` (throwaway and reproducible). Results table is filled from the captured instrumentation output, not model estimates.

| Condition | First display 1 / 2 / 3 (ms) | Median (ms) | 3× update (ms) | Max observed PSS (KiB / MiB) | PSS samples |
|---|---|---:|---:|---|---:|
| A (32, serial) | 26620 / 25598 / 25838 | 25838 | 1002 | 333031 / 325.2 | 77 |
| B (16, serial) | 10916 / 9477 / 10470 | 10470 | 209 | 302259 / 295.2 | 36 |
| C (32, parallel) | 26321 / 24382 / 25422 | 25422 | 935 | 320472 / 313.0 | 73 |
| D (16, parallel) | 12902 / 11885 / 12007 | 12007 | 110 | 285250 / 278.6 | 40 |

All 4 completed runs returned `INSTRUMENTATION_CODE: 0`, all 12 first-display observations retained the stitch canvas while loading, and all 4 zoom observations retained the previous realistic image with final `actualZoom=3.0`. PSS sampling yielded 77/36/73/40 readings for A/B/C/D. The first C collection process ended with status 143 before final data persistence; its partial timings are excluded and C was rerun in full before D.

Resolution comparison screenshots (same centered location, 1080×2400):

- 32 fit: `/tmp/myembro-realistic-speed/final/A-32-fit.png`
- 16 fit: `/tmp/myembro-realistic-speed/final/B-16-fit.png`
- 32 at 3×: `/tmp/myembro-realistic-speed/final/A-32-zoom3.png`
- 16 at 3×: `/tmp/myembro-realistic-speed/final/B-16-zoom3.png`

The corresponding C/D screenshots are also in that directory. All 8 PNGs were checked for successful PNG encoding and 1080×2400 dimensions. Raw timing, camera zoom and memory samples are in `{A,B,C,D}-instrumentation.txt`, `{A,B,C,D}-logcat.txt`, and `{A,B,C,D}-meminfo.txt`; `summary.json` and `table.md` contain the computed table.


No visual quality metric, acceptance threshold, or automatic resolution recommendation is defined by this experiment. Emulator host contention, JIT/GC, accessibility polling and memory sampling affect timings; this is one device/session, in A→B→C→D order.

## Reproduction and verification

Use JDK 17 and `--max-workers=4`. Instrumentation runner:

```sh
./gradlew --max-workers=4 \
  -PmyembroTestRunner=io.github.linker2k.myembro.ui.RealisticSpeedInstrumentation \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:testDebugUnitTest --tests '*ViewerRealisticPreviewTest'
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 tools/realistic-speed/measure.py \
  --input /tmp/myembro-headless-samples/out/svg-01-skull.pes \
  --out /tmp/myembro-realistic-speed/final
```

At this base, unrelated `PhotoFilterDifferenceInstrumentation.kt` still calls removed `changeDetail(0)`. For the local instrumentation build only, that single call was temporarily omitted and then restored byte-for-byte. It is not executed by this runner, and no workaround change is committed. The existing main issue must be fixed separately for an unmodified full androidTest build. (2026-11-07: that instrumentation was removed; see docs/qa/photo-filter-difference.md.)

Build output: `BUILD SUCCESSFUL`; `ViewerRealisticPreviewTest`: 6 tests, 0 failures, 0 errors. Raw build log: `/tmp/myembro-realistic-speed-final-build.log`.
