# Color-drawing app-integration smoke

Fallback for small-eye/mouth generation failures follows the [per-region fallback regressions](region-fallback.md).

For the latest background/antialiasing fixes and current screenshots, see
[v0.2.5 candidate verification](background-cleanup.md). Numbers below record the initial integration.

Home → New project → Photo/drawing → select **Color drawing** beside Silhouette/Outline at
2/3. Color mode provides a single color-count (2–6) slider and the existing embroidery-size
slider. Results list the numbers/names of nearest Brother 64-color threads without duplicates.
ko/en strings are provided, and existing editor/USB flows are retained.

- [Color mode, color count, and existing size slider](preview-ko.png)
- [Sew-out check, result, and required-thread list](result-ko.png)

Both screenshots use a synthetic face drawn directly with Android Canvas by
`ColorDrawingSmokeInstrumentation`. No personal images/external emoticons were used or bundled.
Automatic approval review rejected photo-picker thumbnail selection due to the risk of selecting
personal images, so the final smoke reads no MediaStore and loads only the exact URI of a
test-created file. It is not the result of bypassing that rejection to select an arbitrary picker image.

## Engine path

`ColorDrawingPipeline.trace` runs only Wu N-color separation and per-color Potrace contours.
At the result stage, `generate` runs shared `StitchTypeRule@1547f5c` → tatami / existing
centerline+convert-to-satin / running → S1. The existing trial tool also calls this API, so the
app has no separate generation algorithm. Placement coordinates are retained across all colors;
color matching uses `VectorArt.nearestThread` → `PecThreads.distance`.

Fully transparent pixels are excluded from the color budget; partial alpha is quantized after
standard alpha compositing over white. Opaque inputs use the top-left quantized color as
background (explained on screen). Color reduction/removal of small regions may leave fewer
required threads than selected colors. The actual required-thread list comes from the finished pattern.

Changing embroidery size discards the old preview and retraces at the physical width. The
app-verified size is selected 30mm → displayed 30.2mm after rerasterization, with actual stitch
bounds of 30×30.2mm. The size-smoke tolerance is calculated from the physical width of 1 pixel
at each image end. Finished stitches are not rescaled to bypass density or hoop decisions.
If the app output check blocks, the USB button is also blocked.

## Automated verification

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
./gradlew :engine:test :engine:imageToDesignProbe :app:assembleDebug :app:testDebugUnitTest
# 기본 instrumentation runner를 테스트 클래스에 지정하고 APK 설치 후 실행:
./gradlew -PmyembroTestRunner=io.github.linker2k.myembro.ui.photo.ColorDrawingSmokeInstrumentation :app:assembleDebugAndroidTest
adb shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.photo.ColorDrawingSmokeInstrumentation
```

The actual smoke used a separate `myembro-s2` AVD (port 5556) and package
`io.github.linker2k.myembro.photopreview` to avoid shared-emulator conflicts. The package change
is local test configuration in `/tmp/myembro-photo-preview.init.gradle`; the repository app ID
was not changed.

```text
BUILD SUCCESSFUL in 30s
engine: 726 tests, failures 0
app debug unit tests: 231 tests, failures 0
COLOR_UI_SMOKE_PASS colors=3 ink_width_mm=30.2 stitches=1543
threads=[(23, Dark Gray), (12, Orange), (47, Beige)]
INSTRUMENTATION_CODE: 0
```

External decision command:

```sh
/tmp/myembro-quality-gate/quality-gate check \
 /tmp/myembro-port-out/editor/image-to-design/face.pes \
 /tmp/myembro-port-out/editor/image-to-design/robot.pes \
 /tmp/myembro-port-out/editor/color-ui/color-face.pes \
 --style mixed --json /tmp/myembro-port-out/editor/color-ui/gate.json
```

Actual command output:

```text
PASS .../face.pes within five-condition checks
PASS .../robot.pes within five-condition checks
PASS .../color-face.pes within five-condition checks
PASS 3/3 cases passed
```

Logs: `/tmp/color-drawing-tests.log`, `/tmp/color-drawing-ui-smoke.log`,
`/tmp/color-drawing-final-gate.log`. This stage does not include physical USB transfer or actual sewout testing.

Final-source `./gradlew check :app:assembleDebug` → `BUILD SUCCESSFUL in 35s` (including lint).
Engine: 726 tests; app debug/release: 231 each; 0 failures. The last change was color-mode-only
error guidance and accessibility wording; screenshot layout/generation paths are unchanged.
