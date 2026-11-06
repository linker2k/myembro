# Picture → stitches: pipeline reference

For developers changing or reviewing the picture conversion. It describes beta.7: `main` `13511d13` plus the
`agent/clamp-prepared`, `agent/detail-split` and `agent/block-empty` branches as merged in
`integrate/beta7-test` `224bbf23` (2026-11-01). Line numbers drift: search for the named symbol when a number no longer
matches. Korean version: [picture-to-stitches.ko.md](picture-to-stitches.ko.md).

Path abbreviations used below:

| Short | Full path |
|---|---|
| `photo/` | `app/src/main/kotlin/io/github/linker2k/myembro/ui/photo/` |
| `image/` | `engine/src/main/kotlin/io/github/linker2k/myembro/engine/inkstitch/svg/image/` |
| `svg/` | `engine/src/main/kotlin/io/github/linker2k/myembro/engine/inkstitch/svg/` |
| `block/` | `app/src/main/kotlin/io/github/linker2k/myembro/ui/editor/block/` |
| `edit/` | `engine/src/main/kotlin/io/github/linker2k/myembro/engine/edit/` |
| `sewout/` | `engine/src/main/kotlin/io/github/linker2k/myembro/engine/sewout/` |

"Unverified" marks a number with no recorded measurement, upstream default or user decision.

Related, more detailed documents: [thread-assignment.md](thread-assignment.md),
[small-regions.md](small-regions.md), [satin-coverage.md](satin-coverage.md),
[photo-filters.md](photo-filters.md), [qa/photo-silhouette-performance.md](qa/photo-silhouette-performance.md),
[qa/color-fill-outline.md](qa/color-fill-outline.md), [image-pipeline.md](image-pipeline.md) (history of the
Wu/Potrace ports), [`tools/color-trace/README.md`](../tools/color-trace/README.md).

## 1. Flow overview

```
input (picker / share / VIEW / AI import_picture / SVG)
  ├─ SVG ─────────────────────────────► VectorArt → ShapeObject.fromArt → PES → SewoutCheck → result
  └─ raster: decode ≤1536px, EXIF upright
       1/3 crop (zoom contain..4, pan)
       2/3 mode
           Color (default) ─► SampleColorRegions (≤1024px, Lab k-means) ─► per-color Potrace mask
           Color + B&W ─────► Wu (≤512px raster from detail) ─► Potrace
           Silhouette/Outline ► filters ─► Wu 2 colors or Otsu mask ─► Potrace
           → thread assignment (CIEDE2000, color modes only)
       3/3 stitches (Ink/Stitch ports) ─► PES ─► SewoutCheck P1–P8 ─► "Next" → editor
```

The ViewModel is `photo/PhotoViewModel.kt`. Steps are `step = 0, 1, 2` (crop, mode/preview, result).
Every user change in step 2 cancels the running job (`revision++`) and re-runs `prepare()`
(`PhotoViewModel.kt:321`). Tracing never generates stitches; stitches are generated only on "Next"
from step 2 (`generateResult`, `PhotoViewModel.kt:420`).

## 2. Input

| Source | Entry | Notes |
|---|---|---|
| Picker | `PhotoViewModel.load(uri)` `:203` | Content is sniffed (`photo/PictureIntake.kt:11`), MIME is not trusted. Non-pictures go to the existing design importer. |
| Share (SEND / SEND_MULTIPLE) and VIEW | `photo/ExternalPictureRouting.kt:7` `externalInputUris`, `:20` `routePictureSources` | Streams win over `data`; with several pictures the **last** one is opened, the rest of the files go to the importer. Called from `MainActivity.kt:387, :414`. |
| AI `import_picture` | `app/.../relay/RelayPicture.kt`; `PictureInbox` (`photo/PictureInbox.kt`); `MainActivity.kt:100–104` → `beginAiPicture(colors)` `:180` | PNG/JPEG base64 ≤ 1,000,000 characters (`RelayPicture.kt:19`, basis: 1 MiB relay message limit minus envelope). `colors` 2–6, default 4 (`:21`, same as the photo screen default). The user still confirms in the photo screen. Only the latest request is kept (`LatestPictureInbox`). |
| SVG | `loadSvg` `:248` → `generateSvg` `:268` | Skips crop and mode. Limits in `svg/SvgPictureInput.kt:17–19`: ≤200,000 bytes, ≤14 elements, ≤2,000 path points (basis in `docs/agent/svg-picture.md`). Initial width = emoji width (`EmojiViewModel.WIDTH_MM`) clamped to the size range. Uses `VectorArt`; automatic satin there is subject to the satin coverage rule (section 6.4). |

Raster decode: `decodeUprightPhoto` `:36–59`. Long edge is halved (power-of-two `inSampleSize`) while it
is **> 1536px**, so the decoded long edge is ≤ 1536px. EXIF orientations 2–8 are applied. Basis:
existing decode limit recorded in `qa/photo-silhouette-performance.md`.

## 3. Step 1/3 — crop

Code: `photo/PhotoCrop.kt`, `photo/PhotoCropDrawing.kt`, `PhotoViewModel.transform` `:301`.

| Item | Value | Boundary | Basis |
|---|---|---|---|
| Aspect | hoop width ÷ height of the default frame | — | `HoopSpec` (single source, AGENTS.md P2) |
| Minimum zoom | `min(src/hoop, hoop/src)` aspect ratio (`PhotoCrop.kt:12–16`) = "contain": the whole picture fits | inclusive | Below 1 the viewport is larger than the picture; the margin is filled with the picture's top-left pixel colour (`PhotoCropDrawing.kt:11–23`). |
| Maximum zoom | 4× | inclusive | **Unverified** (product value, no measurement recorded). |
| Pan | `panX, panY ∈ [-1, 1]`, scaled by `|source − viewport| / 2` (`PhotoCrop.kt:19–27`) | inclusive | Stops before exposing more blank margin than the contain fit allows. |
| Background for silhouette/outline | white is drawn first, then the corner colour (`PhotoCropDrawing.kt:15–16`) | — | Redwork treats pixel 0 as background. Colour mode keeps alpha. |

## 4. Step 2/3 — per-mode processing

Mode enum `PhotoMode { SILHOUETTE, OUTLINE, COLOR }` (`PhotoViewModel.kt:61`). Default after `begin()`
(`:173–178`): COLOR, 4 colours, detail 1, B&W off, filters off (colour) / median 3 (mono), contrast 1.0,
width = maximum for the hoop.

### 4.1 Filters (all modes, before tracing)

`image/PhotoCorrection.kt:11–25` `PhotoCorrections.adjust`; UI `photo/PhotoScreen.kt:139–161`.

| Filter | Values | Default | Notes |
|---|---|---|---|
| Noise (median) | size 0, 3, 5, 7 | colour 0, mono 3 (`PhotoViewModel.kt:82`) | Pillow 11.3.0 `ImageFilter.MedianFilter` port (`image/PillowPhotoFilters.kt`). |
| Contrast | factor 0.5–2.0 | 1.0 | Pillow `ImageEnhance.Contrast`. Range 0.5–2.0 is **unverified** (product choice, comment at `PhotoScreen.kt:152`). |
| Otsu (B&W split) | on/off | off | Silhouette/outline only; refused in colour (`PhotoViewModel.kt:95`). Produces a binary mask traced directly. |
| Alpha bypass | — | — | Colour input with any non-opaque pixel skips filters and shows a hint (`PhotoCorrection.kt:16–17`, `PhotoViewModel.kt:349–350`). |

### 4.2 Colour drawing (default) — sample-pack regions

Used when mode = COLOR, B&W off and thread texture off (`PhotoViewModel.kt:339`).
Raster = crop at source resolution, long side scaled **down only** to the chosen resolution step
(default 1024px, section 4.7; `photoRegionRaster`, `PhotoCrop.kt:70`). Then `ColorDrawingPipeline.traceRegions` (`image/ColorDrawingPipeline.kt:86–92`) →
`SampleColorRegions.labels` (`image/SampleColorRegions.kt:39–86`).

This is a port of the sample-pack converter `tools/color-trace/png2svg_claude_v2.py` `convert()` up to its
2mm cleanup; the converter's own SVG smoothing is replaced by the app's Potrace route.

| Step | Code | Constant | Boundary | Basis |
|---|---|---|---|---|
| Size limit | `:17`, `:41` | `MAX_EDGE = 1024` | width, height ∈ 1..1024 inclusive; also the top of the resolution slider | Pack source PNGs are 1024px (cute-art `final-list.json`). |
| Alpha | `:88–97`, `:76` | — | alpha 0 → background (label −1); partial alpha composited on white for colour statistics | Converter behaviour. |
| Lab | `:240–252` | sRGB, D65 2° | — | scikit-image 0.25 `rgb2lab`. |
| Sample | `:99–108` | `SAMPLE_PIXELS = 40000` | all pixels if n ≤ 40000 | Converter value. Kotlin `Random(1)` replaces NumPy's generator. |
| k-means | `:110–135` | `CLUSTERS = 10`, `ITERATIONS = 30`, farthest-point init | — | Converter value. First seed `Random(0)`. |
| Sparse fold | `:137–147` | `SPARSE_FRACTION = 0.005` | cluster count **< 0.5 %** of the sample is folded into its nearest centre (equal is kept) | User-approved v2 fix of the converter. |
| Merge | `:62–71` | `MERGE_DELTA_E = 18.0` | merge closest pair until **colours ≤ slider + 1 and closest pair ΔE ≥ 18**; ΔE here is Lab Euclidean (CIE76) | Converter value. The +1 is the white background slot. |
| Border white | `:74–75`, `:149–161` | — | cluster with highest L*, 4-connected to the image border → background | Converter (SciPy `ndi.label` default connectivity). |
| Small pieces | `:83–85`, cleanup `:163–212` | chosen piece area (default `DEFAULT_PIECE_AREA_MM2` = π mm², `:26`), `CLEANUP_PASSES = 3` | 4-connected piece with pixel area **< area / (mm-per-px)²** is repainted with the most common label within city-block distance ≤ 2 (ties → lowest label, background first); area equal to the limit is kept. The default uses the converter's own expression `π·(1mm / mm-per-px)²`, so its threshold is bit-identical to before | Converter value (2mm diameter); other steps: section 4.7. |
| Palette order | `:79–85` | — | largest area first | Converter. |

Parity (`tools/color-trace/README.md`, 2026-11-01, three 1024px pack inputs): with NumPy's sample the
palettes are equal and 0 of 1,048,576 labels differ; with the port's own sample ≤ 60 pixels differ.

Per colour, `ColorDrawingPipeline.preview` (`:94–130`) pads the label map by one transparent pixel
(so ink touching the crop edge is traced) and traces each colour as a binary mask with
`RedworkPipeline.traceMask(..., preserveSmallContours = true)` (Potrace `turdsize = 0`, i.e. no paint is
dropped; `small-regions.md`).

### 4.3 Colour drawing with B&W on (Wu route)

`ColorDrawingPipeline.trace` (`:66–82`). Raster long side = the chosen resolution step; the default 192px
is still limited by `photoRasterEdge` (`image/PhotoPreparationPolicy.kt:15`):
`min(192, ceil(max(w, h)mm / 0.2))`, so the default result is unchanged. A chosen step is used as chosen
(`PhotoViewModel.kt:358`). Requires width, height ≤ `WU_MAX_EDGE = 1024` and ≤ 1,048,576 pixels
(`ColorDrawingPipeline.kt:36, :71`; Wu's limit `ImageQuantizer.MAX_PIXELS`, `ImageQuantizer.kt:20`). The former 512
side limit came from the first oracle probe without a recorded basis. Pixels converted to BT.709 grey, border
background selected (`ColorRegionCleanup`), Wu quantisation to the colour count, then the same per-colour
Potrace and thread assignment as 4.2. Thread candidates are the neutral grey set
(`Bt709Grayscale.threads`) unless My Threads is active.

This route has no small-piece step, so the piece-area slider is disabled here with a short note
(`PhotoViewModel.kt:91`).

### 4.4 Silhouette and outline (Wu / Potrace route)

Raster as in 4.3 (default 192 px, section 4.7). Two inputs:

- Otsu on → `RedworkPipeline.traceMask` on the binary mask (`:393–404`).
- otherwise → `RedworkPipeline.trace`: Wu 2 colours, background = colour of pixel 0. On an
  `IllegalArgumentException` other than `PHOTO_TOO_COMPLEX`, with contrast 1.0, retry **once** with
  `photoContrastStretch` (min/max per channel, `PhotoPreparationPolicy.kt:17`) (`:406–411`).

Potrace small-speck filter: the chosen piece area (default 0.5 mm², the former detail 1;
0.25 and 1.0, the former detail 2 and 0, are also steps), converted to pixels as `max(2, ceil(mm² / px²))`;
Potrace keeps contours with **area > limit** (`image/RedworkPipeline.kt:101–102`). "Off" (0) therefore still
drops specks of ≤ 2 pixels, the Potrace default. Basis: photo simplification presets
(`qa/photo-silhouette-performance.md`), not a sewing-safety rule. Upstream Potrace default `turdsize = 2`.

### 4.5 Contour budget (all raster modes)

`maxContours = 128` (`PhotoViewModel.kt:381, :397`; `ColorDrawingPipeline.MAX_REGION_CONTOURS`, `:34`).
More than 128 → `PHOTO_TOO_COMPLEX` before stitches are generated (`RedworkPipeline.kt:105`,
`ColorDrawingPipeline.kt:123`), shown as "too complex". The user lowers resolution or raises the piece area;
resolution is never lowered automatically (user decision 2026-11-01). The "lower detail" button remains only
for thread texture (`PhotoViewModel.lowerDetail`, `:342`).
In colour mode only contours larger than `max(2, ceil(3.0 mm² / px²))` pixels count toward the budget
(`MINIMUM_REGION_AREA_MM2 = 3.0`, `:25`; legacy value kept only for this count).
Basis: mobile workload budget (`qa/photo-silhouette-performance.md`); measured on 127 pack inputs at
99.9 mm: p50 25, p90 99, max 275, 7/127 above 128; the 275-contour picture took 143 s on a PC JVM
(`tools/color-trace/README.md`).

### 4.6 Size

| Item | Code | Value | Boundary | Basis |
|---|---|---|---|---|
| Minimum width | `engine/.../geometry/PictureSizePolicy.kt:8` | 20.0 mm | inclusive | `PhotoSizeSweep` (8 synthetic drawings × 2 styles × 3 details, 5 mm steps); not a sewing guarantee. |
| Maximum picture width | `PhotoPreparationPolicy.kt:8–9` | `floor(min(W − 1, (H − 1)·W/H)) / 10` mm → 99.9 mm on 100×100 | inclusive | AGENTS.md P2/P2a (0..units−1). |
| Slider | `photoInkWidthRange` `PhotoCrop.kt:47–50` | the slider shows **embroidery (ink)** width; frame width = ink ÷ ink-ratio | inclusive, 0.1 mm steps | Ink ratio is size-independent (`:40–44`). Re-traces only when the change is ≥ 0.05 mm (`PhotoViewModel.kt:201`). |
| SVG range | `pictureWidthRange` `PictureSizePolicy.kt:11–14` | 20 mm .. floor(min(W − 1, (H − 1)/aspect)) / 10 | inclusive | P2. |
| Size title | `EmbroiderySizeSlider.kt:31, :48`; `photoInkAspect` `PhotoCrop.kt:47` | "width × height mm (in)", height = width × traced ink aspect (SVG: art aspect), one decimal in mm | — | User request 2026-11-01. Inches use the app's `Dimensions.inches` (3.0 shows as "3"). |

### 4.7 Detail controls: resolution and small-piece area (beta.7)

Two independent step sliders on the picture screen in every mode (hidden with thread texture). Basis: user
decisions 2026-11-01. Code: `PhotoPreparationPolicy.kt:15–43`, `PhotoViewModel.kt:82–103`,
`PhotoScreen.kt:332` (`DetailControls`), `:355` (`StepSlider`).

| Item | Value | Boundary / rule |
|---|---|---|
| Resolution steps | 128, 181, 256, 362, 512, 724, 1024 px (×√2) | Top = the existing colour limit 1024 (user decision; 1448 was dropped). |
| Input limit | steps above the decoded picture's long side are hidden | `resolutionSteps(default, sourceEdge)`, inclusive: a 1024px picture keeps 1024, a 1023px one stops at 724. |
| Mode defaults | colour 1024 (crop, never scaled up); silhouette, outline and colour B&W 192 | The default step is always kept, even above the input (192 for a 128px picture, scaled up as before). |
| 0.2 mm/px cap | applies to the default only | A chosen step is not cut (user decision: large hoops). At 160×260 1024px is 0.254 mm/px (section 6.6). |
| Piece area steps | 0.25, 0.5, 1, 2, 4, 8, 16 mm² (×2, diameter ×√2), plus "off" for silhouette/outline | Colour has no "off": on complex line art it took 208 s and then hit a density block (section 6.6). |
| Piece area defaults | colour π mm² (2mm diameter, shown "3.14"), silhouette/outline 0.5 mm² | The default is inserted as its own step, so the default result is unchanged. |
| Mode / B&W change | both sliders return to the mode default | `PhotoViewModel.kt:103, :336`. |
| Applied resolution | the label shows "(actual N px)" when the traced long side differs | `appliedResolutionPx`, `:372`. |
| Commit | once on release; a tap commits the tapped step | `StepSlider` reads the slider state when the gesture ends. Before the fix a tap committed the old value captured at composition (field bug 2026-11-01; `StepSliderTapTest`). |

## 5. Thread assignment (colour modes)

`engine/.../color/ThreadAssignment.kt:30–48`, called from `ColorDrawingPipeline.kt:105–107`.
Full spec and measurements: [thread-assignment.md](thread-assignment.md).

- Each source colour's area = its pixel count × pixel area (mm²).
- Order: CIE L* **descending** (lighter first), then area descending (`:24–25`).
- Each colour takes the nearest **unused** candidate by CIEDE2000 (kL = kC = kH = 1). Different source
  colours therefore never share a thread unless colours > candidates; then the rest reuse the nearest and a
  `THREAD_CANDIDATES_EXHAUSTED` warning is attached.
- Candidates: `MyThreads.candidates()` — the owned set when My Threads is active, otherwise the Brother PEC
  chart (`engine/.../io/MyThreads.kt:10–11`). Grey route uses the neutral set.
- Basis: user decision 2026-11-01; on 127 SVGs mean ΔE00 5.86, max 18.93, 0 merged pairs.
- The step-2 preview is drawn in the assigned thread colours (`Preview.previewPixels`, `:46–49`). Step 3
  lists required threads and marks "not in my threads" (`PhotoScreen.kt:211–216`).

## 6. Step 3/3 — stitches

### 6.1 Colour drawing

`ColorDrawingPipeline.generate` (`:132–225`). All generators are Ink/Stitch v3.3.0 ports
(`b0edd96311ece82ee48816dc93466274b12c2a9c`).

| Step | Code | Rule / value | Basis |
|---|---|---|---|
| Grid phase | `:144–147` | Shift by the crop's top-left 0.1 mm rounding phase, so 99.9 mm cannot round to a 100.0 mm span | `small-regions.md` regression `(-500,-500)..(499,499)`. |
| Islands | `:152–162` | Each colour layer split into polygons in `PhotoIslandOrder` order | `docs/photo-island-order.md`. |
| Width | `:171` | `StitchTypeRule.widths` (JTS maximum inscribed circle) | — |
| Type | `:174`, `svg/SmallRegionStitches.kt:16–18` | max width **< 1.0 mm** → running; otherwise tatami. Colour photos never choose satin. | 1.0 mm = Ink/Stitch `min_satin_stroke_width`; user decision 2026-11-01 (`small-regions.md`). |
| Tatami | `:183–184`, `engine/.../inkstitch/elements/DefaultTatami.kt:21–23` | Ink/Stitch `fill_stitch.py` defaults + underlay; region expanded by `DEFAULT_EXPAND_MM = 0.2` and clipped to the closing of all colour regions (`:148`) | Expansion: user approved 2026-10-27, gap 8.96 → 5.78 % (`:26–29`). |
| Fallback | `:181–187`, `svg/StitchTypeRule.kt:60` | tatami → running; failed or blocked region → running; only a run that cannot make 2 visible needles at distinct 0.1 mm positions, or fails P4/P5, is omitted and counted | `small-regions.md`. Omitted count is shown in step 3 (`PhotoScreen.kt:224`). |
| Fill outline | `:198–199`, `image/ColorFillOutline.kt:25–26, :33–44` | After each tatami region, its unexpanded outline as two 1.2 mm running passes, the second staggered by half a stitch | User decisions 2026-10-28 / 2026-10-30; `qa/color-fill-outline.md`. |
| Outline selection | `:210`, `ColorFillOutline.kt:69–99` | Whole design is checked; outlines implicated by a blocker are removed and re-checked; fills are never removed. Skipped count shown in step 3 (`PhotoScreen.kt:225`) and in block editing. | — |
| Finishing | `:216` | `GeneratedPatternProcessor.fromGroups(pecJumpTrims, lockAtThreadBoundaries, omitZeroLengthMotions)` — Ink/Stitch `half_stitch` locks | AGENTS.md P4, P6. |
| Block data | `PhotoViewModel.kt:444–446`, `edit/ShapeObjectFactory.kt:18–28` | Result becomes a `ShapeObject` with one block per sewn region; no object (no block editing) if no region was sewn or a region lost its path | — |

### 6.2 Silhouette and outline

`RedworkPipeline.generate` (`image/RedworkPipeline.kt:133–168`).

- Silhouette: even-odd polygon → islands (`PhotoIslandOrder`) → `DefaultTatami` per island, each followed
  by its two-pass outline, same outline selection (`:184–208`).
- Outline: each traced ring as an Ink/Stitch bean running stitch with `bean_stitch_repeats = 1`
  (forward/back/forward) and `trim_after` (`image/PhotoOutline.kt:51–57`); ring order by JGraphT
  `NearestNeighborHeuristicTSP`, kept only if it shortens travel (`:35–49`).
- Fit: final integer bounds fitted into the hoop (`fitPhotoPattern`, `:170–182`).
- Single thread `0xB5492A`; no `ShapeObject`, so no block editing for these modes.

### 6.3 Thread texture (colour, optional)

When the thread-texture switch is on (`PhotoScreen.kt:188`), step 3 runs `ThreadTexturePreview.generate`
instead (`PhotoViewModel.kt:433–439`) and is gated by `ThreadTextureOutputGate`. Out of scope for this
document; see `docs/thread-texture-ui.md`.

### 6.4 SVG route: satin coverage

Automatic satin in `VectorArt` (`svg/VectorArt.kt:298`) is checked against its own needles; if any area
stays further than `zigzag_spacing_mm/2 + √2·0.05 ≈ 0.270711 mm` (inclusive accepted distance) from
them, that **non-border** region falls back to tatami (`svg/StitchTypeRule.kt:60–108`). Borders
(closed stroke or polygon with a hole) stay satin even with gaps. Basis and measurements:
[satin-coverage.md](satin-coverage.md).

### 6.5 Progress and waiting

- Progress checkpoints `regions=i/n;stitches=k` (`ColorDrawingPipeline.kt:166, :207`) feed
  `PhotoGenerationProgress` (`photo/PhotoGenerationWait.kt`). Numbers are shown with the locale's
  thousands separator.
- Inside one tatami region, `region_step=i/n` reports the path-rendering loop (`FillRouting.route`,
  `for ((index, edge) in collapsed.withIndex())`), about 100 times per fill pass; underlay and top count
  separately. It is passed down `DefaultTatami.stitchGroups` → `InkAutoFill.paths` → `TatamiFill.fill`.
  The screen shows "This region i / n". Before that, `region_stage=rows` / `region_stage=route`
  (`TatamiFill.fill`, `FillRouting.route`) show only the stage name ("making rows", "planning the path"),
  without numbers (user decision 2026-11-01). The same loop checks the thread interrupt, so "Stop"
  ends a large region within about 0.5 s (emulator measurement, 2026-11-01).
- Travel clamping (`FillRouting.clampPath`) prepares the region's 1e-6px buffer once per polygon
  (`clampArea`, JTS `PreparedGeometryFactory`) and tests `covers` on it. The original Ink/Stitch v3.3.0
  `lib/utils/clamp_path.py` also uses a prepared geometry (`prep(polygon.buffer(1e-9))`, rebuilt per call).
  Before, `clampPath` rebuilt `polygon.buffer(1e-6)` and ran an unprepared `covers()` for every travel leg.
  Measured on the wolf line art, whose largest region has 42 holes and 1,615 points: on a PC JVM 36.6 s of the
  37 s were in this region; after the fix the region took 5.9 s (commit `adc1de0b`), and 4.8 s in the
  integrator's later PC run (conditions not recorded in the repository). On the user's phone the same picture
  went from more than 134 s to 13 s (user measurement, 2026-11-01). Stitches are byte-identical
  (`WolfLineArtRegressionTest` digests).
- After 60 s (`PHOTO_GENERATION_TIMEOUT_MS = 60_000`, `:5`, inclusive `≥`) the screen asks "wait 1 more
  minute / stop" (`PhotoScreen.kt:103–108`). Waiting sets the allowance to `max(elapsed, allowance) + 60 s`
  (`:9–10`); repeatable. Basis: user request ("one-minute decision interval").

### 6.6 Measurements for the detail controls (beta.7)

PC JVM, production trace → generate → PES → `SewoutCheck` route, 99.9 mm, 4 colours, one axis changed at a time
(the other at its mode default). Tool: `./gradlew :engine:detailSplitGrid` (`DetailSplitCli`; inputs are never
copied). Outputs and comparison sheets: `/tmp/myembro-detail-split/axes/`, `/tmp/myembro-detail-split/koi/`.

Pack drawings (skull, sitting cat, baby T-rex; 1024px sources) and `robot.png`:
- Resolution 128 → 1024 px changes contours little (skull 19–20, cat 24, T-rex 16–17) and stitches by 1–2 %;
  edges get smoother (zoomed sheets). Colour never scales up, so `robot.png` (128px) stays at 128.
- Colour piece area: from 0.25 mm² up the result is almost the default. At 0 ("off", now removed for colour)
  anti-aliased edge pixels became 1,139–2,056 contours, 1,100–1,900 tiny runs were omitted and stitch generation
  took 17–23 s instead of 2–4 s.
- Silhouette piece area: no change on these clean drawings.

Complex line art (koi, 466×280px, whole picture fitted, local input):

| Mode | Piece area (default resolution) | Resolution (default piece area) |
|---|---|---|
| Colour | off: 208 s, then blocked (density 32 > 30, P7). 0.25: blocked, 0.1 mm over the hoop (section 10). 0.5: 461 contours (81 counted), 17,620 st. π (default): 63, 18,368 st. 16: 13, 11,568 st. All non-off steps from 0.5 pass. | 128: 81 contours, 10,649 st; 362: 108, 20,348 st; 512 and above = the 466px source: 63, 18,368 st. |
| Silhouette | off–0.5: 91 contours, 11,292 st; 2: 35, 9,338; 16: 6, 7,326. All pass. | 128: 43; 192 (default): 91; 256 and above: over 128 contours ("too complex"; 130 at 256, 388 at 1024). |
| Outline | Same contours as silhouette; 3,810 → 1,650 st. | Same contour counts as silhouette. |

Large hoops (skull, 1024px, mode default piece area, each hoop's maximum width): 100×100 0.098 mm/px,
16,235 st (colour); 130×180 0.176 mm/px, 38,770 st, 5.7 s; 160×260 0.254 mm/px, 64,212 st, 10.5 s. All pass,
no budget hit.

Emulator 5558, step 2 of the skull: colour 1024 px 1,660 ms, silhouette 192 px 126 ms.

## 7. Sewing check and hand-off

- Colour/silhouette/outline: `PesWriter.write` → `SewoutCheck.checkPes(..., origin = APP_GENERATED)`
  (`PhotoViewModel.kt:461–463`); SVG: `:278–280`.
- Limits (`sewout/SewoutLimits.kt`): margin 1 unit (`:9`, P2a), min coordinate 0 (`:12`, P1), header
  tolerance 0 (`:13`, P3), stitch ≤ 120 units = 12.0 mm (`:14`, P8), density cell 5 units = 0.5 mm (`:15`),
  warning ≥ 16 (`:17`), block > 30 (`:18`, P7), same position ≤ 2 (`:21`, P5), zero-length motions 0
  (`:22`, P4). The rules and their evidence are in AGENTS.md.
- Result: Pass / Caution (warnings only) / Blocked chip (`PhotoScreen.kt:204–205`). Only a passed,
  complete check keeps PES bytes (`PhotoViewModel.kt:285`); "Next" (`PhotoScreen.kt:227–233`) is enabled by
  `ThreadTextureOutputGate.canEdit` and opens the editor.

## 8. What the user can change

### 8.1 Picture screen

| Control | Where | Range | Default | Blocked / hidden when | Code |
|---|---|---|---|---|---|
| Crop zoom | step 1 | contain .. 4× | 1 (cover) | — | `PhotoCrop.kt:12–27` |
| Crop pan | step 1 | −1..1 each axis | centre | — | `PhotoViewModel.kt:304` |
| Mode | step 2 | Silhouette / Outline / Colour | Colour | — | `PhotoScreen.kt:131–137` |
| Colour count | step 2, colour | 2–6, step 1 | 4 (AI: requested value) | — | `PhotoScreen.kt:184–186`, `PhotoViewModel.kt:115–118` |
| B&W | step 2, colour | on/off | off | — | `PhotoScreen.kt:180` |
| Resolution | step 2, all modes | 128–1024 px, ×√2 steps, ≤ picture long side | colour 1024, others 192 | hidden with thread texture | section 4.7 |
| Small-piece area | step 2, all modes | colour 0.25–16 mm²; silhouette/outline off, 0.25–16 mm² | colour π (3.14), others 0.5 | disabled for colour B&W; hidden with thread texture | section 4.7 |
| Noise | step 2 | off / weak / medium / strong (0/3/5/7) | colour off, mono 3 | disabled on alpha bypass | `PhotoScreen.kt:141–149` |
| Contrast | step 2 | 0.5–2.0 | 1.0 | disabled on alpha bypass | `PhotoScreen.kt:150–155` |
| Otsu | step 2, mono | on/off | off | not offered in colour | `PhotoScreen.kt:156–160` |
| Thread texture | step 2, colour | on/off, detail | off | — | `PhotoScreen.kt:188` |
| Embroidery width | step 2 (raster), step 3 (SVG) | 20 mm .. hoop maximum (99.9 mm on 100×100) | maximum | range collapses to the maximum when the hoop is smaller than 20 mm | `PhotoScreen.kt:199–201`, section 4.6 |
| Wait 1 more minute | step 3 | repeatable | — | appears after 60 s | `PhotoScreen.kt:103–104` |

### 8.2 Editor: block editing (colour pictures, SVG, emoji)

`block/BlockEditScreen.kt`, `block/BlockApply.kt`, `edit/BlockStitchCapabilities.kt`.
Apply always runs the full sewing check; a blocked apply keeps the previous settings.

| Control | Range | Default | Blocked when | Code |
|---|---|---|---|---|
| Type | Fill / Satin / Running / Empty | from generation | Satin: `SATIN_GEOMETRY_UNSUPPORTED` (shape cannot be satin), `SATIN_GENERATION_FAILED`, `SATIN_STITCH_TOO_LONG` (> 12.0 mm at the current scale, P8). Running: `NO_RUNNING_PATH`. Fill: `EMPTY_FILL_REGION`. Disabled options stay visible with a reason. | `BlockStitchCapabilities.kt:36–46, :77`; `BlockEditScreen.kt:336` |
| Texture (fill) | Basic (`tatami_fill`), Pattern (`meander_fill`, tile grid), Contour (`contour_fill`), Circular (`circular_fill`) | Basic | Gradient not offered (needs colour stops) | `BlockApply.kt:25–26`; `BlockStitchCapabilities.kt:31` |
| Direction | Auto / Manual angle 0–179° (integer) | Auto | Running: not shown | `BlockEditState.kt:40–44`; `BlockApply.kt:51–52` |
| Manual angle hatching | thin lines at the angle over the selected blocks, display only | — | not shown for Auto or Running | `BlockEditScreen.kt:116, :270` (`drawHatch`: 1 dp lines, 6 dp gap; white on thread luminance < 0.5, dark otherwise) |
| Density | Normal only (row spacing 0.25 mm, Ink/Stitch default) | Normal | Dense/Sparse disabled: no upstream presets (**unverified**), planned for a later version | `BlockApply.kt:45–46` |
| Order | move selected blocks earlier/later | sewing order | — | `BlockEditScreen.kt:124`; `EditorViewModel.applyBlockOrder` `:580` |

Not available: stitch-only designs (opened PES) and silhouette/outline results (no `ShapeObject`).

**Empty** (beta.7, user decision 2026-11-01): `BlockStitch.Type.EMPTY` (`edit/ShapeObject.kt:24`).
- The block stays a region of the object but sews nothing: `ShapeBlockRenderer.kt:52` returns no groups,
  `VectorArt.kt:298` still records the part, `PhotoShapeRenderer.kt:40` uses an empty fragment, and
  `RegionOrderPatterns.compose` skips it (`RegionOrderPatterns.kt:42`). Its thread therefore leaves the thread
  list, stitch count, trims and time when no other block uses it.
- Choosing any other type restores the original stitches; one undo step; works with multi-select and select-all.
- Canvas: an emptied block is drawn as fabric (no fill, no outline; `BlockEditScreen.kt:250`); taps still hit
  its shape. The sheet hides direction/density and shows a one-line hint (`:354–356`).
- Emptying every block of the only object leaves no stitches: the existing sewing check refuses the apply
  (`EMPTY_DESIGN`, "no stitches") and keeps the previous settings, so an empty design is never saved or sent.
  With other objects present, one object may be fully emptied.
- Wolf line art (5558, 2026-11-01): 45 white blocks emptied → 27,167 → 14,853 stitches, 2 → 1 colours, 89 → 39 trims.
- The AI connection's `set_block_stitch` schema still offers only FILL/SATIN/RUNNING.
- Tests: `BlockEmptyTest` (vector and photo, restore, JSON round trip, all-empty refusal, undo).

### 8.3 Editor: other

| Control | Effect | Code |
|---|---|---|
| Colours tab | Replace a used thread (chart/owned/hex), change fabric colour; one undo step | `ui/editor/EditorColorsPanel.kt:43` |
| Magnet snap (rail) | On by default. While **moving** (not scaling/rotating) the selection's edges/centre snap to the hoop centre (0) and the min/max/centre of other visible objects within 8 dp on screen | `EditorViewModel.kt:245, :614, :672`; `EditorSelection.kt:53–60`; `EditorCanvas.kt:80, :139` |

## 9. What cannot be changed, and why

| Fixed | Value | Why |
|---|---|---|
| Contour budget | 128 | Mobile workload; above it generation took minutes and exceeded stitch limits (section 4.5). Exceeding stops before stitches instead of dropping contours. |
| Colour raster | ≤ 1024 px long side | Matches the pack converter's input size; parity measured at 1024 px. Larger inputs are scaled down, never up. |
| Resolution maximum | 1024 px, and never above the picture's long side | Existing colour limit (user decision 2026-11-01); 1024² is also Wu's pixel limit. |
| Default mono raster | 192 px and ≤ 0.2 mm/px | Former detail 1 (`qa/photo-silhouette-performance.md`); kept so the default result is unchanged. |
| Decode | ≤ 1536 px long edge | Existing memory/time limit. |
| k-means 10, ΔE 18, 0.5 %, 2 mm | — | Ported converter values; parity is the acceptance criterion. Changing them breaks parity. |
| Stitch generator parameters | Ink/Stitch v3.3.0 defaults | AGENTS.md: port verified originals with defaults; no tuning UI. |
| P1–P8 | AGENTS.md | Real SE400 failures; the gate blocks build/save/send. |
| Maximum width | 99.9 mm on 100 mm hoop | P2/P2a. |

## 10. Known limits and open items

| Item | Status |
|---|---|
| Boundary-colour thread choice | Greedy lighter-first order can give a mid colour a second-best thread when a lighter colour took the nearest one. Reported case: skull picture navy thread **21 vs 23** chosen differently than expected. Input and ΔE values are not recorded in the repository (**unverified** details); needs a reproducible input before any rule change. |
| Satin gaps at concave corners (crown) | The crown's black border is a polygon ring, so it is exempt from the coverage fallback and keeps satin with small gaps at concave corners (`satin-coverage.md`). Intentional for borders; no fix decided. |
| Thin pieces at the picture edge | Running outlines of pieces narrower than 1 mm are not clipped like fills, so a curve touching the frame edge can cross it by 0.1 mm. Seen on the koi line art at 0.25 mm² (x −501…499 on a 100 mm hoop); the sewing check blocks the file. Fix options: clip running outlines like fills, or fit the result as silhouette does. Not changed yet. |
| Palette shifts with resolution | On the skull the dark colour became Brother grey at 256/512 px and navy at 1024 px: the k-means centre moves slightly with resolution. A possible lead for the boundary-colour case above. |
| Silhouette piece area on clean art | On the pack drawings the piece area changes nothing (no specks); on line art it does (koi 91 → 6 contours). |
| Phone timing | Colour regions at 1024 px are timed only on PC JVM (`tools/color-trace/README.md`) and the emulator for older routes; real-phone timing is **not measured**. 2026-11-01 user test on a real phone (beta.6): no noticeable delay (an observation, not a measurement). Emulator 5558 step 2 (skull): colour 1024 px 1,660 ms, silhouette 192 px 126 ms. Large region after the clamp fix: section 6.5. |
| Random generator | Kotlin `Random` sample differs from NumPy: ≤ 60 pixels and palette channel ±1 on the parity inputs. |
| Max zoom 4×, contrast 0.5–2.0 | **Unverified** product values. |
| Dense/Sparse density | Disabled until there is a basis. |
| Large pictures | 7/127 pack pictures exceed 128 contours at 99.9 mm and are refused as too complex. Complex line art too: koi silhouette at 256 px and above (130–388 contours). |

## 11. Checking a change

Unit tests (run the related ones per change; full `test lint` at merge):

| Area | Tests |
|---|---|
| Colour regions | `engine/src/test/.../inkstitch/svg/image/SampleColorRegionsTest.kt` |
| Colour pipeline, stitches | `.../image/ColorDrawingPipelineTest.kt`, `ColorFillOutlineTest.kt`, `PhotoGenerationProgressTest.kt` |
| Mono route | `.../image/RedworkPipelineTest.kt`, `PhotoCorrectionTest.kt`, `PhotoCorrectionDifferenceTest.kt` |
| Small regions, type rule, satin | `.../inkstitch/svg/SmallRegionStitchesTest.kt`, `StitchTypeRuleTest.kt`, `SatinCoverageTest.kt` |
| Threads | `engine/src/test/.../color/ThreadAssignmentTest.kt` |
| Block editing | `engine/src/test/.../edit/BlockStitchCapabilitiesTest.kt`, `SmallRegionProjectCompatibilityTest.kt` |
| Sewing check | `engine/src/test/.../sewout/SewoutCheckTest.kt` |
| SVG input | `engine/src/test/.../agent/SvgPictureInputTest.kt`, `SvgPictureToolTest.kt` |
| App side | `app/src/test/.../ui/photo/PhotoCropTest.kt`, `PhotoCropDrawingTest.kt`, `PhotoSizeSweep.kt`, `PhotoGenerationTimeoutTest.kt`, `PhotoGenerationCancellationTest.kt`, `PictureIntakeTest.kt`; `app/src/test/.../relay/RelayPictureTest.kt`, `RelayPictureHandoffTest.kt` |

```sh
./gradlew :engine:test --tests '*SampleColorRegionsTest' --tests '*ColorDrawingPipelineTest'
./gradlew :app:testDebugUnitTest --tests '*Photo*'
./gradlew test lint          # at merge
```

Harnesses in the repository:

| Tool | Use |
|---|---|
| `tools/color-trace/` (`reference_labels.py`, `Parity.kt`) | Label/palette parity with the pack converter. Re-run after any change to `SampleColorRegions`. |
| `tools/fill-expand/` | Fill expansion / gap sweep through the production route. |
| `tools/potrace/`, `tools/wu/` | Upstream golden regeneration for the Potrace and Wu ports. |
| `./gradlew :engine:photoTiming -PphotoTimingArgs=192,baseline,full` | Mono route timing (`engine/build.gradle.kts:156`). |
| `tools/safety-tests/`, `tools/quality_gate_*.py` | Sewing-gate regression helpers. |

Review checklist:

1. Every new or changed number has a basis (upstream default, measurement, or dated user decision) in the
   code comment and here; otherwise it is marked unverified and not used as a rule.
2. Boundaries are stated as inclusive/exclusive with units, and tests cover n−1 / n / n+1.
3. Ported steps still match the original (parity tool or upstream goldens re-run, output attached).
4. Generated PES passes `SewoutCheck` (P1–P8) for every mode touched; blockers are not hidden by
   dropping representable paint.
5. Thread assignment unchanged unless intended; if changed, re-measure ΔE00 on the corpus.
6. No personal pictures in tests or the repository; local analysis stays in `/tmp`.
7. Cancellation: a new step calls the checkpoint so `revision` changes and interrupts stop it.
8. Update this document (both languages) in the same commit when a rule changes.
