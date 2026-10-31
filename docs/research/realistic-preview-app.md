# Static realistic viewer — Android integration

Date: 2026-10-31. Branch `agent/realistic-preview`, based on `9117f789`. This stage connects the
CPU thread renderer to the existing viewer. No changes to embroidery generation, source bytes,
PES output, hoop rules, or sewing-check decisions.

## Behavior

- One localized **Realistic / 실사** chip, default OFF for each document. Turning it on pauses
  playback and prepares the **entire** design, preserving the current needle/playhead overlay.
  Starting playback switches back to the ordinary stitch view. No new advanced controls.
- `StitchCanvas` retains its existing fit, pan, pinch and double-tap camera. The completed raster
  is drawn once through that camera transform. Pan/zoom are absent from the render request keys;
  they transform the bitmap instead of rebuilding stitches or re-running the filters.
- Needle position, jump travel (when explicitly enabled, still dashed), hoop ring, blocking circles,
  highlight areas and the check badge remain live overlays. JUMPs/TRIMs/STOPs/color changes are
  never rendered as sewn lines. Segment extraction uses the same STITCH→STITCH rule as RenderData.
- Generation runs in `Dispatchers.Default`. An indeterminate spinner is shown. Pattern, viewport,
  fabric/background or toggle changes cancel the old request and hide its result. A generation
  token prevents stale publication; `AtomicReference` ownership closes the withContext cancellation
  return race. A mutex holds through render completion/pending cleanup so replacements do not
  allocate a second output while a cancelled worker still owns the first. Displayed images are
  recycled on the main thread when replaced/disabled/disposed. Errors fall back to the normal view
  with a short localized message.

Implementation: `ui/ViewerRealisticPreview.kt`, `ui/ViewerScreen.kt`, `ui/StitchCanvas.kt`.
The Android backend uses only API26-compatible Bitmap/Canvas/Path operations. Native Android
Canvas coverage has not been claimed pixel-identical to Cairo; the original numeric lighting,
blur and composite remain unchanged.

## Engine additions and compatibility

`RealisticThreadRenderer.renderTiles` streams premultiplied tiles to a caller-owned output,
with a cooperative cancellation hook. The previous full-array `render` API now wraps that stream;
all **16 prior JVM reference PNGs remain byte-identical**. The app never allocates a full-page
ARGB IntArray. `PatternThreadSegments` lazily yields needle segments and implements the original
Ink/Stitch `visible_on_white.darker` display-color policy without changing catalog threads.

For sub-3×3 lighting buffers the original resvg `lighting.rs:222–224` empty-lighting behavior
is used instead of inventing tiny-image normals; source-alpha/arithmetic color composition still
runs. This handles screen-resolution short stitches and changes no stitch command or safety rule.
No dependencies added.

## Explicit memory budget

- **One retained software output Bitmap**, exactly the canvas pixel dimensions. At the measured
  viewport: 1016×862 ARGB8888 = **3,503,168 bytes (3.34MiB pixel payload)**.
- **Retained tile-cache budget: 0 bytes.** The engine's exact cache keys have additional object
  overhead beyond their pixel allowance. This first UI version deliberately uses bounded filter
  regions only, avoiding an unmeasured persistent metadata/cache budget. This is not a new
  embroidery parameter or application setting.
- One reusable temporary alpha Bitmap (reconfigured/recycled as needed), native Path/Paint, a
  tile-sized read/modify/write IntArray, and the engine's tile-sized alpha/blur/light/color arrays.
  Their live dimensions are bounded by a stitch's device-space filter region, not design stitch
  count. The benchmark's largest region was **35 pixels**; the bundled flower reached 3660 pixels.
  Temporary allocation/GC, native Canvas internals and GPU upload copies are additional to the
  single output's pixel payload and are included in the measured process memory below. This is
  not an OS-wide RSS cap or a claim that total app memory equals the Bitmap payload.
- Requests are serialized and old output ownership is released before a replacement is rendered.
  No preview file or duplicate encoded PNG is retained by the normal app flow. PNG screenshots
  exist only in the opt-in instrumentation output.

## Measured on emulator-5556

AVD `myembro-c`, Android API35 x86_64, 1080×2400, emulator 37.2.12, SwiftShader. **5554 was not
installed to or controlled.** API26 behavior is covered separately by Robolectric SDK26 native
Bitmap tests; physical API26 performance has not been measured.

Repository-owned synthetic display fixtures contain exactly 5,000/50,000 STITCH commands:
0.6mm snake-grid spacing, 0.7mm row spacing, repeating 12,500-point layers with trim/jump between
layers. Size: 74.4×27.3mm / 74.4×69.3mm. They are **display workloads**, not certified sewing plans;
no PES is generated/exported for these fixtures. Test-only reflection supplies RenderData to the
real MainActivity/ViewerViewModel/ViewerScreen; there is no production test hook.

Each size starts in a separate force-stopped/new process. The timer starts immediately before
the accessibility toggle click and ends after the ready canvas is present and the main queue is
idle (first-image readiness, approximately 50ms polling resolution). Screenshot encoding is
outside the timed/memory window. Java heap and total process PSS are sampled every 50ms; these
are sampled peaks, not proof that a shorter spike could not occur. PSS includes Compose, Skia,
JIT, GPU/native caches and the loaded model. Baseline PSS is recorded before toggling.

| STITCH commands | First image | Peak sampled Java heap | Baseline process PSS | Peak sampled process PSS |
|---|---:|---:|---:|---:|
| 5,000 | 1.079s | 43.14MiB | 217.11MiB | 248.32MiB |
| 50,000 | 4.760s | 51.26MiB | 236.97MiB | 308.02MiB |

Both runs: canvas 1016×862, effective display density about 201dpi, retained cache 0 bytes.
The phase-2 JVM 600dpi/large-segment results are not directly comparable to this screen-sized
Android short-running-stitch workload. Do not generalize these timings to every photo/satin design.

## Automated results

- `:app:testDebugUnitTest`: **490 tests, 0 failures, 0 errors, 0 skipped**, including SDK26 native
  render/PES SHA/model immutability and jump exclusion/cancellation tests.
- Focused engine renderer/segment tests: **21 tests, 0 failures/errors**, including streaming/full
  array equality, cancellation and original color-policy vectors.
- `:app:lintDebug`: **0 errors**, 177 warnings and 1 information item across the project.
- `RealisticViewerInstrumentation`: **2/2 runs INSTRUMENTATION_CODE: 0**; default OFF, loading
  observed, full image, pan, pinch, toggle-off/cancel, document replacement/reset, model SHA and
  source-PES bytes preserved. Canvas-crop hashes differ for initial/pan/zoom on both sizes; the
  status clock/footer are excluded from that crop. Tests do not infer success from appearance.
- Normal bundled-PES path: `vm.openSample()` loads the repository flower. Original source SHA-256
  remains `202142391a7b6020c9455305c988aeda03fe02006d7c2e095e8b806ac6be105f`.
  **Observed existing asset issue:** flower is BLOCKED by the existing sewing check before the
  realistic toggle and remains blocked afterwards. This renderer does not repair/rewrite it or
  bypass save/send checks. The display-only injected fixtures also have no valid export source;
  their blocked badges are expected. This work does not claim a sewing gate PASS for them.

Commands used (all adb commands explicitly target 5556):

```sh
ANDROID_HOME=/home/gosh2/Android/Sdk JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :engine:test --tests '*RealisticThreadRendererTest*' --tests '*PatternThreadSegmentsTest*'
ANDROID_HOME=/home/gosh2/Android/Sdk JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.RealisticViewerInstrumentation
adb -s emulator-5556 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am force-stop io.github.linker2k.myembro
adb -s emulator-5556 shell am instrument -w -e count 5000 io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.RealisticViewerInstrumentation
adb -s emulator-5556 shell am force-stop io.github.linker2k.myembro
adb -s emulator-5556 shell am instrument -w -e count 50000 io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.RealisticViewerInstrumentation
```

## Artifacts and remaining work

Local report/data/logs: `/tmp/myembro-realistic-app/`.
Screenshots (repository-owned fixtures only):

- `device/realistic-5000.png`, `device/realistic-5000-pan.png`, `device/realistic-5000-zoom.png`
- `device/realistic-50000.png`, `device/realistic-50000-pan.png`, `device/realistic-50000-zoom.png`
- `device/realistic-50000-loading.png`, `device/realistic-bundled-flower.png`

This requested static-viewer scope is implemented. Remaining validation: physical device/API26
performance, broader long-satin/photo workloads and Android-Skia versus Inkscape coverage fidelity.
Playback uses the existing flat renderer; this is intentionally a completed still, with a fixed
screen-resolution raster that can pixelate when greatly enlarged. No playback-realism integration,
background cache setting, source repair or safety-threshold change is part of this commit.
