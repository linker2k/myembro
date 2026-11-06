# Realistic preview: original renderer at a fixed native density

User decision2026-11-06: use **e441a132 unchanged**, render once at32px/mm, and resolve the original to the requested viewport. Fixed-grid shading, catalog normalization and analytic physical cross-section work were cancelled. None of those formulas are in this branch.

## Rendering and display path

`FixedRealisticPreview.PIXELS_PER_MM` is the single production density constant. Basis: the owner's original-engine32→16/8/4.43 box experiment retained texture and had much steadier means than direct low-resolution rendering. The existing `RealisticThreadRenderer`, `ThreadShape`, `ThreadFilterMath`, `InkscapeGaussian`, `ThreadFilterRegion` and `PatternThreadSegments` equations are unchanged from e441a132.

- `renderNativeViewerRealistic` renders the original filter pipeline at the fixed native density. `NativePreviewCache` retains that source across pan/zoom; cancellation and displayed native crops hold leases, so a source is not recycled while a reader/display uses it. Turning realistic mode off or replacing the design releases the cache when its readers finish.
- If output px/mm is **below native**, `FixedRealisticPreview.areaAverage` averages original opaque sRGB channel bytes with exact rectangular overlap weights. Fractional ratios and fractional crop origins are included. This is the approved box operation, not a new gamma/shading/material model. Read buffers are one source row plus output-row accumulators.
- If output px/mm is **at or above native**, `FixedRealisticPreview.output` returns the original object and does not invoke the box backend. The viewer crops the original; above native it may magnify those original pixels with filtering disabled. No extra detail beyond the native source is claimed.
- The viewer resolves the **current visible viewport** from the source at fit×zoom and the current pan. Below native it draws the resolved bitmap1:1 in screen coordinates. At/above native it draws only a clipped native-pixel source rectangle. Crop origins round to native pixels. Requested crop spans never exceed destination spans, so this path does not ask Android to shrink the bitmap.
- `FILTER_BITMAP_FLAG` and `createScaledBitmap` are not used in this path. Output/native bitmaps use `DENSITY_NONE` to prevent implicit density scaling. Existing cloth/background drawing and sewing/export data are unchanged. Off-source crop regions retain the background.

## Requested measurement

Only an original synthetic PEC35 patch and local engine-generated original-color skull were measured. The patch has0.25mm top rows, staggered3–7mm stitches and90-degree underlay0.75mm apart. The skull input is `svg-01-skull.pes` (75.4×89.9mm); no source PES or private fixture is committed. Each case renders its native source **once**, and the native render time is repeated in the table to identify the shared cost.

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

The new JVM checks cover exact1.5-ratio area weights, a native-pixel crop, **32/64px/mm source identity and zero downsample callbacks**, and patch mean spread≤1.0 over4.43/8/16/32 outputs. Observed patch mean spread is0.3048193355. App tests cover source crop at native/above-native, idempotent release, cancellation, jumps/trims and unchanged model/PES bytes. The older opt-in direct-render acceptance task is left intact; its different-density direct-render contract is not this new pipeline and was not relabeled as a passing check.

Run output: `BUILD SUCCESSFUL`. Native output rows have `calls=0`; lower-density rows have `calls=1`. Disposable PNGs: `/tmp/myembro-realistic-band/v5/{patch,skull}-{4.43,8.0,16.0,32.0}pxmm.png` (8 total). Measurement code is in engine tests, and this document/table are persistent in the repository.


## Deployment blockers: retained frame and bounded native source

The owner requested two fixes after06fe5aae. A completed image now remains displayed while a new viewport is pending. Its stored world-space plan is projected through the **current** camera transform, so pan/zoom does not fall through to flat stitch lines. Replacement happens only after the new result is ready; the previous image is then released. Disabled/disposed/recycled images are not kept. The current image still uses the settled1:1/native-crop paths; only the temporary prior frame is transformed during interaction.

Viewport work is delayed until100ms after the last camera change. The single `REALISTIC_GESTURE_DEBOUNCE_MS` constant is explicitly annotated **“미측정, 체감용”**: owner-requested UX settling, not a measured rendering or sewing threshold. LaunchedEffect cancellation coalesces camera changes. Native-source leases remain valid while the previous frame is shown.

The native source region is now **needle-down design bounds intersected with the world-space hoop rectangle**, independent of canvas size/aspect/fit/zoom/pan. The native plan retains its exact world origin, separately from the hoop center, so limiting the bitmap does not recenter the design or fabric. No intersection produces an empty-content background source. Degenerate line/empty allocations use a minimum1pixel dimension. Off-source viewport regions are background. The source-cache key also excludes canvas size and fit, allowing the same bounded source to survive rotation/resizing.

### Native bitmap allocation arithmetic

These are ARGB_8888 source-bitmap bytes (`width*height*4`), **not process RSS/GPU memory or total preview memory**. Output frames, AA scratch and temporary retained frames are additional. The first row is the manager's previous portrait-size example, not a new device measurement. The next rows are computed from native32px/mm and the clipped design/hoop extent.

| Source extent/example | Native dimensions | Bytes | MiB |
|---|---:|---:|---:|
| Prior portrait example | 3700×5800 | 85,840,000 | 81.863403 |
| At most100×100mm hoop | 3200×3200 | 40,960,000 | 39.062500 |
| Clipped50×50mm design | 1600×1600 | 10,240,000 | 9.765625 |
| Clipped10×10mm design | 320×320 | 409,600 | 0.390625 |

`sourcePlanIsDesignIntersectHoopIndependentOfPortraitCanvas` asserts the100mm source cap,40,960,000-byte allocation, exact origin, portrait/landscape independence, smaller design limit and empty-content out-of-hoop case. `completedImageRemainsDisplayableWithAnOlderCameraPlan` checks that a completed bitmap with an older plan is retained and unrecycled until released. Actual gesture animation was not measured on an emulator/device.

```bash
./gradlew --offline --max-workers=4 :engine:test \
  --tests '*FixedRealisticPreviewTest' --tests '*RealisticThreadRendererTest' \
  --tests '*PatternThreadSegmentsTest' \
  :app:testDebugUnitTest --tests '*ViewerRealisticPreviewTest' --continue
```

Selected engine24/24 and app5/5 passed; after excluding canvas/fit from the cache key, the selected app5/5 passed again. `BUILD SUCCESSFUL`. Renderer formulas, native density and PES/model data were not changed. No emulator was used.
