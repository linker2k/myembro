# v0.2.1 one-color silhouette — latency/error fixes

## Scope and reproduction results

Retain silhouette fill. The experiment switching broad areas to running was excluded from the final implementation.
Defer true line art/centerlines and user-selectable preprocessing filters to the next version.

Previously, step 2 performed Wu → Potrace → curve fitting → JTS → tatami/underlay routing → S1 →
PES → sewout checks. Hoop/PES exceptions after long execution were also all displayed as
'Failed to distinguish lines from background'. Reproduced this with the supplied local input.
There was no existing rejection rule based on foreground ratio. The final change adds neither ratio rejection nor running conversion.

| Local JVM, same input/192px | Before fix | After fix (fill retained) |
|---|---:|---:|
| Decode | 123.5ms | 176.1ms |
| Preprocess/start | 2.5ms | 50.0ms |
| Wu 2 colors | 17.5ms | 31.2ms |
| Trace/curve fitting | 56.1ms | 77.1ms |
| Remaining contours | 47 | 12 |
| Tatami/S1/region preparation | 9,665.6ms | 4,538.0ms |
| Generated stitches | 10,280 | 9,527 |
| PES | Hoop overflow exception | 385.2ms, saved normally |
| SewoutCheck | Cannot check because saving failed | 0 blockers |
| CPU path awaited by step 2 | Error after approximately 9.7 seconds to stitch generation | Preprocessing+quantization+tracing approximately 158ms |

Single input/single measurement, not a time guarantee for all hardware. JPEG decoder/downsampling
differences can change JVM/Android contour counts. Neither source nor result images are included in this document/repository.
Private-input logs and all derived images stay only in the permitted local private area.

## Android measurements and UI assertions

Same input, same 1536px decode limit, 192px working resolution, API 35 x86_64 emulator:

| Stage | Before fix | After fix (tatami retained) |
|---|---:|---:|
| Decode | 15.6ms | 10.6ms |
| Preprocess | 1.2ms | 48.6ms |
| Wu | 67.1ms | 21.9ms |
| Trace/curve fitting | 153.5ms | 35.6ms |
| Contour count | 27 | 7 |
| Stitch generation | 38,308ms | 7,883ms |
| Generated stitches | 10,083 | 9,422 |
| PES | Failed due to hoop overflow | 236.7ms, success |

Measured once, running original generation code first and the fixed path afterward. Includes warmup effects;
not a guarantee of repeated distributions/speed on all real machines. UI estimates of approximately 2 seconds/10 seconds
round these measurements and the actual VM 481ms result below, with a note that device/image affect timing.

Called actual `PhotoViewModel.load → next → prepare` on the instrumentation main looper and
asserted in code that within 2 seconds busy=false, outline exists, and render/report are absent:

```
INSTRUMENTATION_RESULT: result=VM_PREVIEW_UNDER_2S ms=481.34921 generated_stitches=false
INSTRUMENTATION_CODE: -1
```

Did not display private input onscreen; deleted temporary emulator input after instrumentation.
Except numeric instrumentation logs held inside the app, no private input/output was placed in the repository or public artifacts.

Asserted the actual system picker → step 2 → step 3 flow for a synthetic 4000×3000 JPEG via UI XML/logcat.
Step 2 has no `stitches_ms` log; step 3 has a pass badge/4,478 stitches/editor and USB buttons.
For a uniform synthetic image, asserted both rejection guidance and 'Two-color separation preview',
exactly one `contrast_retry=1` log, and no stitch-generation log.

Only publishable synthetic-image screenshots:
- `/tmp/myembro-photo-12mp-preview.png`
- `/tmp/myembro-photo-12mp-progress.png`
- `/tmp/myembro-photo-12mp-result.png`
- `/tmp/myembro-photo-uniform-rejected.png`

## Implementation and numeric evidence

- Retain existing decode long-edge ≤1536px and detail 128/192/256. For small hoops,
  additionally apply the user-proposed **0.2mm/px working cap**. Do not quantize/trace
  at original photo resolution. The opt-in CLI directly generates the 12MP synthetic JPEG.
- Step 2 performs only Wu 2-color separation and Potrace contours/curve fitting. Stitches/files/sewout checks run
  at step 3 using the same cached contours. Preserve the Wu black/white separation image even when preview fails.
- Color separation uses the existing validated Wu port. On failure, apply channel-wise min/max linear contrast stretch, then Wu
  **at most 1 more time**. Formula `(v-min)*255/(max-min)`; preserve constant channels.
  This mathematical operation follows the [standard min/max normalization definition](https://docs.opencv.org/4.12.0/d2/de8/group__core__array.html);
  no OpenCV code copied or dependency added.
- Automatic noise suppression is standard **3×3 channel-wise median, BORDER_REPLICATE**.
  Follows the [medianBlur definition](https://docs.opencv.org/4.12.0/d4/d86/group__imgproc__filter.html);
  no custom line-detection/stencil algorithm. Add no filter-selection UI.
- Remove small fragments via existing Potrace `turdsize`. Convert photo preprocessing minimum areas
  of 1/0.5/0.25mm² by detail level into pixel areas. These are photo-simplification presets,
  not claimed as Ink/Stitch defaults/sewing safety criteria. General engine API default remains upstream 2px.
- Photo contour cap **128** is a mobile workload budget (measured on small hoops/representative photo inputs).
  Not a quality-gate threshold; if exceeded, stop with detail-adjustment guidance **before** stitch generation.
  Do not arbitrarily fill a truncated subset of contours. Do not reject by foreground ratio or presence of people.
- Photo SVG flattening tolerance is **0.1mm**, matching PES coordinate resolution. This reduces excessive subpixel subdivision
  compared with existing 0.01 SVGpx (approximately 0.0026mm). Existing general engine API tolerance
  and Ink/Stitch tatami/underlay/lock defaults remain unchanged.
- Fit completed stitches' integer bounds (including locks) to actual hoop 0..width-1/height-1.
  Rounding the centered origin first can expand 999 units to 1000 units, so subtract the minimum point
  before rounding. Add no extra safety margin. Retain actual PES P1–P8 checks.
- Provide progress stage/elapsed/estimated guidance/cancel/lower detail after 10 seconds. Stitch generation provides
  cancellation request/dedicated error after 30 seconds (cooperative interruption). Check interrupts in routing-graph loops;
  retain revisions so cancelled results cannot overwrite a new session.

## Reproduction commands

```
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:photoTiming -PphotoTimingArgs=192,baseline,full
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:photoTiming -PphotoTimingArgs=192,filtered,full
```

Synthetic input: `/tmp/myembro-photo-performance/synthetic-12mp.jpg` (4000×3000, generated directly).
Default CLI does not read private photos. Optional fourth local path does not copy/render/save the input.

Android opt-in runner:
```
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.photo.PhotoTimingInstrumentation
adb shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.photo.PhotoTimingInstrumentation
```

Regression tests: preview does not invoke stitch/PES stages; existing generation API equals separate-stage generation;
constant images immediately reject after a 2-color preview callback; small-fragment removal/early cap check;
fixed median/contrast-stretch calculations; completed fill hoop/sewout checks.

## Final validation

```
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug
BUILD SUCCESSFUL in 56s
86 actionable tasks: 33 executed, 53 up-to-date
```

Engine 640, app debug/release 217 tests each, failures/errors/skips 0.
ko/en photo strings: matching 45 keys. Full log `/tmp/myembro-photo-final-check.log`.
Local automated checks, separate from CI and actual sewout approval.
