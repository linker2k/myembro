# Thread texture embroidery adapter

Implemented on 2026-10-27 in `ThreadTextureAdapter`. This is an application adapter **after** the DrawingBotV3 line algorithm, not a change to that algorithm. The original 10-case, 46,043-event oracle remains a separate regression suite. No UI or agent-tool connection is added in this unit.

## Input and physical scale

`generate(width, height, correctedArgb, widthMm, palette, hoop, detail, ...)` consumes the photo screen's already corrected raster; it neither filters nor resizes it. `widthMm` denotes the **picture/frame width**, not the occupied ink bounding box. Input arrays are not changed. Fully transparent pixels consume no thread (same policy as ColorDrawingPipeline); the only mask is transparency, never a color-specific luminance mask. Partially transparent pixels retain their straight RGB pigment.

The existing policies are used, not redefined:

- Minimum picture width: `geometry.PICTURE_MIN_WIDTH_MM`, as in `agent/AttachmentStore.kt:50–58` and the photo crop UI. Its tracing-size sweep is not a guarantee that texture will sew successfully.
- Maximum width: `PhotoPreparationPolicy.photoMaxWidthMm` (`engine/.../inkstitch/svg/image/PhotoPreparationPolicy.kt:8–9`). The selected hoop's native dimensions and P2 1-unit limit are retained; the input raster's physical height must also fit `SewoutLimits.hoopMaxYUnits0p1mm`.
- Resolution: `PhotoPreparationPolicy.photoRasterSize(detail, widthMm, heightMm)` (:11–13). The existing detail caps are 128/192/256 with `ceil(max(widthMm,heightMm)/0.2)`; 0.2mm here is a **raster-resolution policy**, not a stitching density. The adapter checks this upper bound; the photo caller supplies the actual policy-sized raster. Detail 1 is the existing photo default, producing 192×192 for these square 40/60/90mm tests.
- `mmPerPixel = widthMm / rasterWidth`; `heightMm = widthMm * rasterHeight / rasterWidth`. This is the same frame-width/pixel convention as ColorDrawingPipeline. Centered local coordinates are `((x-width/2)*mmPerPixel, (y-height/2)*mmPerPixel)`, then converted with `Units.mmToPixels` to the existing SVG-coordinate Stroke API. The native adapter rounds into 0.1mm units; the PES writer translates the actual integer bounds to top-left origin. No extra expansion, width fitting, margin or post-hoc rescaling is applied.

Thus the last pixel center is one pixel short of the frame boundary; actual ink/header extent can be smaller than the requested frame width. The 100-pixel horizontal adapter fixture at 40/128 mm per pixel has 31.25mm nominal endpoint separation and 313 native units after independent endpoint rounding, asserted by test.

## Palette, runs and existing stitch generation

`brotherPalette(argb, colors)` delegates to the existing `ImageQuantizer` (Wu) and `VectorArt.nearestThread` (Brother/PEC red-mean matching). The requested color count uses the existing photo range 2–6; a uniform image or Brother matching may yield fewer distinct threads. Callers may supply that already prepared palette instead. Each line's original sampled ARGB is preserved by the plotting stage and assigned to the supplied palette with `PecThreads.distance`. Alpha is ignored by that existing distance function; ties retain palette order. No Premium Colour Match, CMYK separation or per-color mask is implemented.

A run contains consecutive lines that share original squiggle index, assigned thread and touching endpoints. A pen lift, intervening differently colored line, or disconnected endpoint starts another run. Each original line belongs to exactly one run, tested across the nine image/size combinations; the adapter never bridges a removed/intervening color. Runs are then stably grouped by thread, with light-to-dark BT.709 luma order (`Bt709Grayscale.argb`), retaining original order within each thread. Equal-luma thread ties use palette order, ensuring threads remain grouped.

Light→dark is the user's approved adapter policy motivated by the supplied Auto Punch video; its general superiority for texture layering is **unconfirmed**. It is not attributed to DrawingBot Free or claimed as PE-Design equivalence. Physical thread coverage versus the plotter's erase/density values is also **unconfirmed**; no conversion factor is invented.

Each run calls `Stroke(..., StrokeParameters(trim_after=true))`:

- `running_stitch_length_mm = [2.5]`, `running_stitch_tolerance_mm = 0.2`, repeats 1, no bean/random jitter: existing `StrokeParameters.kt:10–16`, attributed there to Ink/Stitch `lib/elements/stroke.py@b0edd96311ece82ee48816dc93466274b12c2a9c`. The adapter does not replace the running sampler or change its defaults.
- `trim_after=true` is the explicit adapter boundary policy for pen lifts/color-separated runs, not an upstream plotting default. Start/end use Stroke's existing `InkLockStitch` defaults (`half_stitch`). No custom locks are generated.
- Groups use `GeneratedPatternProcessor.fromGroups` with the existing app flags `pecJumpTrims=true`, `lockAtThreadBoundaries=true`, `omitZeroLengthMotions=true`, as in VectorArt.render. Default planner minimum/collapse values stay unchanged. Its P4 cleanup can remove a trim/jump when restart is at the same position; that existing rule takes precedence over a redundant pen lift.

`Result` contains the candidate pattern, original plot result, runs, physical scale and structured SewoutCheck report. Cancellation throws before adapting partial plotting output. Exhausted plotting search is rejected. `Result.pes()` rejects blockers/incomplete analysis, then uses the normal `PesWriter.write`; its lower-level PesOutputSafety/PecViewport gates run again. There is no unchecked writer or imported-density exemption. The generated marker from the existing planner retains the generated limit of 15, not the imported limit of 30.

## Nine-case JVM experiment

Fixtures are generated mathematically in `ThreadTextureAdapterTest`: a full horizontal grayscale ramp (4 requested colors), a filled black disk on white whose diameter is 3/4 of the picture frame (2 requested colors), and a red/Prussian Blue split image (2 requested colors). The disk margin is fixture geometry, not an allowed-region/stitching rule. Widths in the table are **frame widths**; this is not a physical sew-out delivery.

Command:

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ANDROID_HOME=/home/gosh2/Android/Sdk ./gradlew :engine:test --tests '*ThreadTextureAdapterTest' --tests '*SketchOracleTest' --tests '*SketchLinesTest'
```

Generation milliseconds are one JVM pass measured with `System.nanoTime`: Wu palette + line generation + running/lock conversion + candidate SewoutCheck. Fixture creation and PES writing/reading are excluded. First-row class loading/JIT is included; these are not Android latency measurements. Tests write the complete machine-readable report to `engine/build/thread-texture-adapter/results.tsv`; only safely exported PES files are written beside it. The measured snapshot and verdict table follow.

| Image | Frame mm | Plot lines | Runs | Stitches | Peak / 0.5mm | Sewout | PEC | PES round-trip | JVM ms |
|---|---:|---:|---:|---:|---:|---|---|---|---:|
| ramp | 40.0 | 5992 | 231 | 8071 | 19 | BLOCK | NOT_EMITTED | NOT_EMITTED | 124.951 |
| ramp | 60.0 | 5992 | 231 | 9747 | 11 | NO_BLOCK (PASS) | PASS | PASS | 123.038 |
| ramp | 90.0 | 5992 | 231 | 12313 | 7 | NO_BLOCK (PASS) | PASS | PASS | 148.389 |
| circle | 40.0 | 6851 | 111 | 7656 | 11 | NO_BLOCK (PASS) | PASS | PASS | 101.977 |
| circle | 60.0 | 6851 | 111 | 9263 | 9 | NO_BLOCK (PASS) | PASS | PASS | 120.643 |
| circle | 90.0 | 6851 | 111 | 11702 | 6 | NO_BLOCK (PASS) | PASS | PASS | 141.882 |
| two-color | 40.0 | 11478 | 269 | 13779 | 16 | BLOCK | NOT_EMITTED | NOT_EMITTED | 177.001 |
| two-color | 60.0 | 11478 | 269 | 16682 | 16 | BLOCK | NOT_EMITTED | NOT_EMITTED | 209.871 |
| two-color | 90.0 | 11478 | 269 | 21172 | 8 | NO_BLOCK (PASS) | PASS | PASS | 258.329 |

All 3 blockers are `DENSITY_HOTSPOT`: ramp 40mm peaks at 19, two-color 40/60mm at 16, against the unchanged generated maximum 15. Their `Result.pes()` calls are asserted to fail; **no PES bytes are emitted**, so PEC and round-trip are NOT_EMITTED, not PASS. No thresholds, sampling settings or lock policies were relaxed to obtain a better table. The 6 unblocked cases pass actual encoded PecViewportCheck, exact needle-coordinate/command and thread-color round-trip, and SewoutCheck of the exported PES under APP_GENERATED policy.

Regression expectations freeze these three blocked combinations and their code. Additional tests cover Wu/Brother assignment, original-line coverage, discontinuity/color/pen-lift splitting, same-squiggle continuity, exact physical scale, a simple safe-run PES round-trip, photo width/resolution rejection and cancellation. Original DrawingBot oracle tests remain unchanged and passing.

Full `./gradlew :engine:test`: 983 tests, 0 failures/errors, `BUILD SUCCESSFUL in 1m 26s`. Targeted first run: 36 tests (7 adapter + 10 original-oracle + 19 core), 0 failures/errors, `BUILD SUCCESSFUL in 5s`. Logs: `/tmp/myembro-texture-adapter-test.log`, `/tmp/myembro-texture-adapter-engine-test.log`. Machine-readable snapshot: [thread-texture-adapter-results.tsv](thread-texture-adapter-results.tsv).
