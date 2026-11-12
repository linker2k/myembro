<!-- SPDX-FileCopyrightText: 2026 HoonyJung -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later OR MIT -->

# Realistic preview: original renderer at a fixed native density

User decision2026-11-06: use **e441a132 unchanged**, render once at16px/mm without parallel tile baking, and resolve the original to the requested viewport. Fixed-grid shading, catalog normalization and analytic physical cross-section work were cancelled. None of those formulas are in this branch.

## Rendering and display path

`FixedRealisticPreview.PIXELS_PER_MM` is the single production density constant. Basis: user decision 2026-11-06, [realistic-speed-experiment.md](realistic-speed-experiment.md), condition B: 16px/mm serial, first-display median 10,470ms on emulator5556. The 32px/mm speed experiment is retained separately and is not merged. The existing `RealisticThreadRenderer`, `ThreadShape`, `ThreadFilterMath`, `InkscapeGaussian`, `ThreadFilterRegion` and `PatternThreadSegments` equations are unchanged from e441a132.

- `renderNativeViewerRealistic` renders the original filter pipeline at the fixed native density. `NativePreviewCache` retains that source across pan/zoom; cancellation and displayed native crops hold leases, so a source is not recycled while a reader/display uses it. Turning realistic mode off or replacing the design releases the cache when its readers finish.
- If output px/mm is **below native**, `FixedRealisticPreview.areaAverage` averages original opaque sRGB channel bytes with exact rectangular overlap weights. Fractional ratios and fractional crop origins are included. This is the approved box operation, not a new gamma/shading/material model. Read buffers are one source row plus output-row accumulators.
- If output px/mm is **at or above native**, `FixedRealisticPreview.output` returns the original object and does not invoke the box backend. The viewer crops the original; above native it may magnify those original pixels with filtering disabled. No extra detail beyond the native source is claimed.
- The viewer resolves the **current visible viewport** from the source at fit×zoom and the current pan. Below native it draws the resolved bitmap1:1 in screen coordinates. At/above native it draws only a clipped native-pixel source rectangle. Crop origins round to native pixels. Requested crop spans never exceed destination spans, so this path does not ask Android to shrink the bitmap.
- `FILTER_BITMAP_FLAG` and `createScaledBitmap` are not used in this path. Output/native bitmaps use `DENSITY_NONE` to prevent implicit density scaling. Sewing/export data are unchanged. The output viewport draws the same rounded hoop cloth as the flat view, including outside the bounded source; box samples outside that source use the viewport cloth/background row rather than a uniform screen background.

## Historical measurement (32px/mm native, before the 16px/mm decision)

The following table and CSV preserve the prior 32px/mm run; they are not measurements of the new 16px/mm default. Only an original synthetic PEC35 patch and local engine-generated original-color skull were measured. The patch has0.25mm top rows, staggered3–7mm stitches and90-degree underlay0.75mm apart. The skull input is `svg-01-skull.pes` (75.4×89.9mm); no source PES or private fixture is committed. Each case renders its native source **once**, and the native render time is repeated in the table to identify the shared cost.

Mean/standard deviation use fixed physical windows, including fractional pixel-area weights: patch x10..34mm,y7..25mm; skull source x34..41mm,y29..40.5mm, plus4mm display margin. No mask chosen by output brightness is used. Times are JVM single-run measurements, not a device benchmark; backend initialization is before timing. Render time excludes box and PNG; box time excludes PNG. For native output, resize time0 explicitly means the resampler was not invoked.

| Scene | Output px/mm | Mean Y′ | Std Y′ | Native render ms | Box ms | Box calls |
|---|---:|---:|---:|---:|---:|---:|
| patch | 4.43 | 184.968116 | 8.087208 | 477.616 | 42.181 | 1 |
| patch | 8.0 | 185.104793 | 11.226932 | 477.616 | 14.857 | 1 |
| patch | 16.0 | 185.193877 | 23.357128 | 477.616 | 38.123 | 1 |
| patch | 32.0 | 184.889058 | 26.456381 | 477.616 | 0.000 | 0 |
| skull | 4.43 | 181.435452 | 9.789669 | 2511.214 | 70.764 | 1 |
| skull | 8.0 | 181.508565 | 12.849083 | 2511.214 | 65.744 | 1 |
| skull | 16.0 | 181.704703 | 22.584084 | 2511.214 | 88.119 | 1 |
| skull | 32.0 | 181.503331 | 29.837951 | 2511.214 | 0.000 | 0 |

Full numeric table: [realistic-fixed-preview-measurements.csv](realistic-fixed-preview-measurements.csv). These are original-renderer brightness values; no catalog-color normalization is applied. Texture still reduces naturally with pixel averaging, without recomputing blur/lighting at a different density.

## Checks and reproduction

```bash
./gradlew --offline --max-workers=4 :engine:test \
  --tests '*FixedRealisticPreviewTest' --tests '*RealisticThreadRendererTest' \
  --tests '*PatternThreadSegmentsTest' :engine:measureFixedRealisticPreview \
  -PfixedPreviewOutput=/tmp/myembro-realistic-band/v5 \
  -PfixedPreviewPes=/tmp/myembro-headless-samples/out/svg-01-skull.pes \
  :app:testDebugUnitTest --tests '*ViewerRealisticPreviewTest' --continue
```

Results: the selected engine tests24/24 passed (original renderer19, segment/streaming2, new box3). After the final display-density/lifecycle changes, new box tests3/3 and selected app tests3/3 passed again. The full engine/app suites were not run. No emulator was used; JDK17 and Gradle max-workers4.

The new JVM checks cover exact1.5-ratio area weights, a native-pixel crop, **16/32px/mm source identity and zero downsample callbacks**, and patch mean spread≤1.0 over4.43/8/16/32 outputs. Observed patch mean spread is0.3048193355. App tests cover source crop at native/above-native, idempotent release, cancellation, jumps/trims and unchanged model/PES bytes. The older opt-in direct-render acceptance task is left intact; its different-density direct-render contract is not this new pipeline and was not relabeled as a passing check.

Run output: `BUILD SUCCESSFUL`. Native output rows have `calls=0`; lower-density rows have `calls=1`. Disposable PNGs: `/tmp/myembro-realistic-band/v5/{patch,skull}-{4.43,8.0,16.0,32.0}pxmm.png` (8 total). Measurement code is in engine tests, and this document/table are persistent in the repository.


## Deployment blockers: retained frame and bounded native source

The owner requested two fixes after06fe5aae. A completed image now remains displayed while a new viewport is pending. Its stored world-space plan is projected through the **current** camera transform, so pan/zoom does not fall through to flat stitch lines. Replacement happens only after the new result is ready; the previous image is then released. Disabled/disposed/recycled images are not kept. The current image still uses the settled1:1/native-crop paths; only the temporary prior frame is transformed during interaction.

Viewport work is delayed until100ms after the last camera change. The single `REALISTIC_GESTURE_DEBOUNCE_MS` constant is explicitly annotated **“미측정, 체감용”**: owner-requested UX settling, not a measured rendering or sewing threshold. LaunchedEffect cancellation coalesces camera changes. Native-source leases remain valid while the previous frame is shown.

The native source region is now **needle-down design bounds intersected with the world-space hoop rectangle**, independent of canvas size/aspect/fit/zoom/pan. The native plan retains its exact world origin, separately from the hoop center, so limiting the bitmap does not recenter the design or fabric. No intersection produces an empty-content background source. Degenerate line/empty allocations use a minimum1pixel dimension. Off-source viewport regions retain the same cloth inside the hoop and the screen background outside it. The source-cache key also excludes canvas size and fit, allowing the same bounded source to survive rotation/resizing.

### Native bitmap allocation arithmetic

These are ARGB_8888 source-bitmap bytes (`width*height*4`), **not process RSS/GPU memory or total preview memory**. Output frames, AA scratch and temporary retained frames are additional. The first row is the manager's previous portrait-size example, not a new device measurement. The next rows are computed from native16px/mm and the clipped design/hoop extent.

| Source extent/example | Native dimensions | Bytes | MiB |
|---|---:|---:|---:|
| Prior portrait example | 3700×5800 | 85,840,000 | 81.863403 |
| At most100×100mm hoop | 1600×1600 | 10,240,000 | 9.765625 |
| Clipped50×50mm design | 800×800 | 2,560,000 | 2.441406 |
| Clipped10×10mm design | 160×160 | 102,400 | 0.097656 |

`sourcePlanIsDesignIntersectHoopIndependentOfPortraitCanvas` asserts the100mm source cap,10,240,000-byte allocation, exact origin, portrait/landscape independence, smaller design limit and empty-content out-of-hoop case. `completedImageRemainsDisplayableWithAnOlderCameraPlan` checks that a completed bitmap with an older plan is retained and unrecycled until released. Actual gesture animation was not measured on an emulator/device.

```bash
./gradlew --offline --max-workers=4 :engine:test \
  --tests '*FixedRealisticPreviewTest' --tests '*RealisticThreadRendererTest' \
  --tests '*PatternThreadSegmentsTest' \
  :app:testDebugUnitTest --tests '*ViewerRealisticPreviewTest' --continue
```

Selected engine24/24 and app5/5 passed; after excluding canvas/fit from the cache key, the selected app5/5 passed again. `BUILD SUCCESSFUL`. Renderer formulas, native density and PES/model data were not changed. No emulator was used.


## Final 16px/mm serial selection and cloth continuity (2026-11-06)

The user selected experiment B. Only `FixedRealisticPreview.PIXELS_PER_MM` changes to 16; no parallel implementation or experiment flags are imported. Identity/no-downsample tests now exercise 16/32px/mm. Source caps and allocation assertions are updated to 1600×1600 / 10,240,000bytes for a 100mm hoop.

The white design-sized rectangle was a viewport-background mismatch: the bounded source correctly contained `plan.fabric`, but area-average samples outside it used `output.background` everywhere, including inside the hoop. The resolved output now first draws the same rounded hoop fabric as the native/flat path. `backgroundRow` supplies that already-rendered viewport row for off-source samples. The source remains bounded; this does not allocate a full-hoop native bitmap or change the stitch shader. At/above native density, cropping leaves the existing flat canvas cloth visible outside the source as before.

Regression tests cover row-specific backdrop preservation, a distinct cloth/screen color inside and outside bounded source pixels, native/above-native identity, memory bounds, and unchanged PES/model data. Existing original-renderer and segment tests remain in the selected run.

Selected verification: `./gradlew --max-workers=4 :engine:test --tests '*FixedRealisticPreviewTest' --tests '*RealisticThreadRendererTest' --tests '*PatternThreadSegmentsTest' :app:testDebugUnitTest --tests '*ViewerRealisticPreviewTest' :app:assembleDebug` returned `BUILD SUCCESSFUL`. XML totals: engine25/25, app6/6, failures/errors0. The original renderer19 and segment2 remain unchanged; box/backdrop checks4 and viewer checks6 passed.

Existing emulator5556 was update-installed, without emulator lifecycle or application-data reset. One original-color skull first-display observation was **7557ms**, with the stitch canvas visible while loading; the existing native-render log recorded5770ms. This is one UI observation, not a new median or a controlled comparison against experiment B. The same centered3× pinch used for the experiment was applied; this final capture waits for settling and is not a zoom-latency benchmark. Screenshots: `/tmp/myembro-realistic-final/skull-16-fit.png` and `/tmp/myembro-realistic-final/skull-16-zoom3.png`, both1080×2400. Instrumentation returned `INSTRUMENTATION_CODE: 0`; log and output are in the same directory. A disposable measurement runner and the known unrelated `changeDetail` compile workaround were used only for building the test APK and were removed/restored before commit.
