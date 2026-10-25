# Photo preprocessing filters: research and integration design

Research date: 2026-10-25. Repository baseline: main `ae576e1`.
Scope: design only; no filter implementation, dependency installation, app wiring, upstream execution, or new embroidery output in this unit.

The aim is to let a user improve the raster before embroidery conversion while retaining verified algorithms and their defaults. Filter selection changes image input, not Ink/Stitch stitch settings. An attractive preview does not establish sewability: existing PES checks and P1–P8 still apply.

## Current pipeline and insertion points

Read together with [Image pipeline](image-pipeline.md), [Image to design](image-to-design.md), and [Silhouette measurements](qa/photo-silhouette-performance.md). Those documents contain historical stages; the following describes the code at the baseline above.

| Location | Current behavior | Proposed connection |
|---|---|---|
| `app/src/main/kotlin/io/github/linker2k/myembro/ui/photo/PhotoViewModel.kt`: `load` | Sampled decode with long edge bounded by 1536px, then EXIF orientation. Original decoded bitmap remains available for cropping. | Keep unchanged. Do not filter the camera-resolution input or destructively replace the source. |
| `PhotoViewModel.prepare`, `PhotoCrop.kt`: `photoCrop`, `photoRasterSize` | Hoop-aspect crop and filtered resize. Working long edge is 128/192/256px, additionally capped by physical size at 0.2mm/px. Non-color modes composite onto white; color mode preserves alpha. | Insert a pure JVM preprocessing boundary immediately after `getPixels`/resize, before the mode-specific quantization branches. |
| Silhouette/outline branch in `prepare` | Existing channel-wise `PhotoRasterFilters.median3`; `RedworkPipeline.trace` uses Wu 2-color separation, then Potrace. On eligible failure, retry once with `photoContrastStretch`. Quantized preview survives later contour rejection. | Audit/reuse the median once, not twice. A selected contrast filter replaces the hidden contrast retry for that request. An Otsu selection needs an explicit binary-mask tracing adapter; do not threshold and then silently rerun Wu/background inference. |
| Color branch: `ColorDrawingPipeline.trace` | Optional `Bt709Grayscale`, visible-pixel selection, preliminary Wu background labeling, foreground Wu at 2–6 colors, small-region cleanup, per-color Potrace. Brother thread matching follows. | RGB filters go before the two Wu calls. Posterization should reuse the existing foreground Wu result, not introduce a second quantizer or an unrelated bit-depth slider. Keep the grayscale toggle's current behavior. |
| `PhotoScreen.kt`, `vm.step == 1` | Silhouette/Outline/Color selector, detail or color-count control, grayscale toggle, size slider, image/contour preview. | Add a single named-filter selector in this existing second step. No fourth step, filter-strength sliders, stitching parameters, or stacks. Retain size/color controls and ko/en resources. |
| `PhotoViewModel.generateResult`, `vm.step == 2` | Generates from cached contours/color preview, then PES and `SewoutCheck`; sending requires complete, unblocked analysis. | Continue consuming the exact preview result. Do not run filtering again or generate stitches while choosing filters. |

Proposed contract: immutable dimensions/pixels plus a filter identifier and upstream version in, filtered pixels or an explicitly typed binary mask out. Keep it in `engine/inkstitch/svg/image`, with no Android bitmap or JNI dependency. A binary adapter should reuse the existing Potrace implementation; its routing is integration work, not a new tracing algorithm.

Capture selection with the existing request snapshot. Changing it cancels `work`, increments `revision`, invalidates outlines/color preview/render/report/PES, and uses the existing 180ms debounce. Check interruption between rows/passes; only the current revision may publish results. Cache identity must include source revision, crop, raster dimensions, mode, grayscale, filter/version, colors, and physical-size/detail settings. Keep preview/progress/cancel/error handling outside the filter core.

## Candidate implementations and exact defaults

Pin the implementations below rather than treating an algorithm name as an equivalence claim. These are research pins, not assertions that they are the latest releases. Primary sources are linked in each row. Required arguments without defaults are explicitly identified.

| Filter / use | Pinned original and default parameters | License and pure Kotlin feasibility | Automated pixel comparison |
|---|---|---|---|
| Otsu / black-and-white stencil | scikit-image 0.25.2 [thresholding.py](https://github.com/scikit-image/scikit-image/blob/v0.25.2/skimage/filters/thresholding.py), `threshold_otsu(image=None, nbins=256, hist=None)`. Supply a 2-D uint8 luminance image. Integer histograms use integer levels; retain the constant-image return and first-maximum tie choice. Thresholding produces a mask, not a sketch. | This file is **BSD-2-Clause**, not the package's general BSD-3-Clause. Small histogram/cumulative-statistics port; preserve upstream intermediate dtypes and reduction order. | Assert identical threshold and every mask pixel, including ties/constant inputs. For dark ink use the documented `image <= threshold` convention; ink maps to black and background to white. Preserve that polarity explicitly. |
| Median / remove isolated noise | scikit-image 0.25.2 [filters/_median.py](https://github.com/scikit-image/scikit-image/blob/v0.25.2/skimage/filters/_median.py): `footprint=None`, `out=None`, `mode='nearest'`, `cval=0.0`, `behavior='ndimage'`. On a 2-D channel, the default footprint is 3×3. Compare each RGB channel separately; never treat channels as a third spatial axis. | BSD-3-Clause wrapper; underlying [SciPy 1.15.2 rank-filter source](https://github.com/scipy/scipy/blob/v1.15.2/scipy/ndimage/src/ni_filters.c) is BSD-3-Clause. Existing `median3` already implements this neighborhood/replicated-edge convention, but has no upstream pixel oracle yet. Reuse only after that audit; a literal rank-filter port must preserve its own attribution. | Exact uint8 RGB equality, including the entire border, isolated impulses, ties, and narrow images. Compare the existing helper first; algorithm-name similarity is insufficient. |
| Autocontrast / global contrast | Pillow 11.3.0 [ImageOps.py](https://github.com/python-pillow/Pillow/blob/11.3.0/src/PIL/ImageOps.py), `autocontrast`: `cutoff=0`, `ignore=None`, `mask=None`, `preserve_tone=False`. Use L or RGB; retain independent channel histograms, identity for constant channels, floating LUT calculation, truncation, and clamping. | MIT-CMU under the [Pillow license](https://github.com/python-pillow/Pillow/blob/11.3.0/LICENSE); retain its actual notice, not a generic MIT substitution. Small histogram/LUT port. Existing integer `photoContrastStretch` is not automatically bit-equivalent to Pillow's floating LUT. | Exact uint8 output and all LUT entries, especially narrow channel ranges and values near truncation boundaries. No percentile clipping or invented strength. |
| CLAHE / uneven lighting | scikit-image 0.25.2 [exposure/_adapthist.py](https://github.com/scikit-image/scikit-image/blob/v0.25.2/skimage/exposure/_adapthist.py): `kernel_size=None`, `clip_limit=0.01`, `nbins=256`. Default kernel is `max(dimension // 8, 1)` per spatial axis: 16×16 at 128×128, 32×32 at 256×256. Retain 14-bit internal range, reflection padding, redistribution, interpolation, and final rescaling. | BSD-3-Clause; file explicitly records Karel Zuiderveld's permission to relicense the Graphics Gems IV algorithm under Modified BSD. Medium port plus dtype helpers. For color, upstream's HSV-value adapter must also be ported; do not substitute per-RGB-channel CLAHE and call it equivalent. | Compare tile histograms/LUTs and every output pixel, especially padding/tile seams/constant images. Record raw floating differences and assert exact final uint8 equality after the pinned upstream conversion. |
| Canny / edge preview, future sketch candidate | scikit-image 0.25.2 [feature/_canny.py](https://github.com/scikit-image/scikit-image/blob/v0.25.2/skimage/feature/_canny.py): `sigma=1.0` pixels, `low_threshold=None`, `high_threshold=None`, `mask=None`, `use_quantiles=False`, `mode='constant'`, `cval=0.0`. Effective default thresholds are 0.1/0.2 in normalized intensity units. | BSD-3-Clause, with file-specific MIT/Broad contributor notices. Larger port: Gaussian/Sobel, bilinear nonmaximum suppression in `_canny_cy.pyx`, border-mask handling, and hysteresis components. No OpenCV threshold substitutions. | Exact boolean edge mask, connected components, and border behavior. Record Gaussian/gradient differences separately; a one-pixel edge shift fails mask equality. |
| Unsharp mask / recover soft detail | scikit-image 0.25.2 [filters/_unsharp_mask.py](https://github.com/scikit-image/scikit-image/blob/v0.25.2/skimage/filters/_unsharp_mask.py): `radius=1.0` pixels, `amount=1.0`, `preserve_range=False`, `channel_axis=None`. Initial use is grayscale to retain the channel default. Its Gaussian uses reflect borders; retain float conversion/clipping and upstream Gaussian truncation. | BSD-3-Clause plus the license of any copied SciPy helpers. Medium port of separable Gaussian and the original blend; no custom sharpening kernel. Color support requires an explicit channel contract rather than pretending RGB is spatial data. | Compare float intermediates and exact final uint8 output after the pinned upstream conversion. Test impulses, flat images, clipping, and borders; do not waive differences using appearance/SSIM alone. |
| Channel posterize / reference alternative | Pillow 11.3.0 [ImageOps.py](https://github.com/python-pillow/Pillow/blob/11.3.0/src/PIL/ImageOps.py), `posterize(image, bits)`. **bits is required; there is no upstream default.** It clears low bits independently in L/RGB channels. This does not mean a 2–6-color palette. | MIT-CMU. Very small LUT port, but choosing a new bits value would be a product decision, not preservation of an upstream default. Defer it in favor of existing Wu color-count control. | Exact LUT/output bytes for every input intensity and supported bits argument. Do not compare it to Wu as though they were interchangeable. |
| Palette posterization / recommended color simplification | Existing `ImageQuantizer.quantize`, Xiaolin Wu 1991 Graphics Gems II C v2 [gemsii/quantizer.c](https://github.com/erich666/GraphicsGems/blob/ca4898517a93b6b45444a9cda5fd50132694b864/gemsii/quantizer.c). Reuse unchanged 33³ moments, 5-bit buckets, float scores/ties, and integer palette means. N is a caller argument; 2–6 is the current app range, 4 its current initial selection, not a Wu default. | Existing custom Graphics Gems reuse permission/author notices; not labeled MIT or public domain. Already pure Kotlin, with original-C goldens; no new port or dependency. | Preserve the 3 existing C comparisons: palette order and all labels exactly. Compare color-pipeline preview and generated inputs to the same cached labels; background removal remains its existing separate contract. |

The scikit-image [per-file license inventory](https://github.com/scikit-image/scikit-image/blob/v0.25.2/LICENSE.txt) distinguishes thresholding, Canny, CLAHE, and package-wide terms. BSD-2-Clause, BSD-3-Clause, and MIT-CMU permit incorporation in this GPL-3.0-or-later app with their notices preserved. Any implementation unit must add the source path, pinned commit, modifiers, applicable full license, and `THIRD_PARTY_NOTICES.md`/APK notice entries for copied code and helper dependencies. No notices are changed in this research-only unit.

## First bundle recommendation

Use **median, autocontrast, Otsu, and existing Wu posterization** as the first four algorithm units. These cover noise, contrast, black-and-white separation, and color simplification with deterministic integer outputs and comparatively small implementation surfaces.

- Keep the current route as the default until equivalence/regression tests pass. Existing monochrome median is already automatic; expose/relabel that state consistently rather than stacking another median pass. Explicit Clean in color mode is new behavior and needs separate alpha handling.
- Offer named choices only, with an original/current-behavior option. The initial UI can group Clean/Contrast/Black-and-white with the existing Color drawing + color-count control; Posterize reuses that color workflow and is not an additional quantization pass. Labels and previews must distinguish preprocessing from the Silhouette/Outline/Color embroidery modes.
- Otsu first applies to monochrome modes. Keep the existing BT.709 helper as a separately identified input conversion, with identical grayscale bytes fed to the upstream oracle. This is an app adapter, not an Otsu default. Do not change the color-mode grayscale/thread-selection contract incidentally.
- For this first bundle, optional filters operate on fully opaque inputs after the existing monochrome white compositing. Transparent/partial-alpha color artwork keeps the existing color path until a separately verified masked-filter contract exists. Do not pass alpha through a spatial median or let hidden RGB contaminate foreground colors. Upstream CLAHE drops alpha, so it cannot be inserted blindly.
- Defer CLAHE, Canny, and unsharp mask until their helper ports and comparisons are ready. Canny returns boundaries, not a pencil sketch or centerline. Sending thin edge bands through Potrace and outline embroidery can produce paired contours, the issue recorded in the existing image-pipeline notes. No dilation, gap closing, width guessing, or custom sketch synthesis is authorized by this design.

Existing region budgets, background cleanup, stitch-type selection/fallback, and minimum-area policy remain downstream. When a filter erases the foreground or makes the input too complex, show its raster preview and the existing actionable error; do not silently select a different filter or stitch a partial contour subset.

## Mobile time estimates: unconfirmed planning ranges

These are **estimates, not measured filter results, acceptance thresholds, or guarantees**. Input sizes below are square 128×128 and 256×256 rasters, not 12MP originals. Times exclude decode/crop, the existing debounce, Potrace, width analysis, stitching, PES, and checks. Actual handset times are unconfirmed; no emulator was used in this research unit.

| Candidate | Estimated 128px CPU time | Estimated 256px CPU time | Basis and uncertainty |
|---|---:|---:|---|
| Otsu including grayscale/mask | 2–10ms | 8–40ms | Histogram plus cumulative statistics and linear pixel passes; hypothesis from source structure, not a benchmark. |
| Existing RGB median | 15–40ms | 60–160ms | Existing 192px emulator preprocessing measured 48.6ms. Pure area scaling gives 21.6/86.4ms; ranges allow overhead. That stage was not an isolated median benchmark. |
| Autocontrast | 2–10ms | 8–40ms | Histograms/LUT and linear RGB passes; unconfirmed implementation estimate. |
| CLAHE | 15–60ms | 60–240ms | Local histograms, clipping, interpolation, and conversion; unconfirmed estimate. |
| Canny | 10–50ms | 40–200ms | Several convolution passes, suppression, and component traversal; topology-dependent, unconfirmed. |
| Unsharp mask | 5–25ms | 20–100ms | Separable Gaussian and blend, fixed default radius; unconfirmed estimate. |
| Pillow bit posterize | 1–5ms | 4–20ms | One LUT pass; unconfirmed estimate, not a reason to invent a bits default. |
| Existing Wu | 10–40ms | 20–100ms | Previous 192px emulator Wu stage was 21.9ms. Fixed 33³ moment work means whole-runtime scaling is not purely proportional to image area; these remain unconfirmed ranges. |

Only the 48.6ms and 21.9ms anchor measurements above come from [the existing performance report](qa/photo-silhouette-performance.md), a single API 35 x86_64 run with warmup effects. No private image is retrieved or reused here. Other ranges are engineering hypotheses based on fixed-neighborhood versus multipass algorithm structure and roughly fourfold pixel growth, not observed results. Replace them with measurements before showing a filter-specific ETA.

Benchmark each implemented filter on synthetic opaque gradients, noise, and shapes at 128/192/256px. Record device/API/ABI, cold time, warmed median/p95, allocations, cancellation latency, and separate filter/quantization/trace/stitch times. Use emulator-5556 for development and a real handset for mobile confirmation; do not install on release emulator-5554. Existing 2-second preview objective and slow/cancel guidance remain product objectives, not filter-equivalence criteria.

## Reproducible upstream comparisons before implementation approval

Pin scikit-image 0.25.2 commit `e8a42ba85aaf5fd9322ef9ca51bc21063b22fcae` and Pillow 11.3.0 commit `89f1f4626a2aaf5f3d5ca6437f41def2998fbe09`. SciPy 1.15.2 is the proposed helper version; pin its resolved commit and NumPy version/dtypes when preparing an oracle. This unit inspected source only, without executing/installing those packages.

Downloaded research-source SHA-256 values:

| Source file | SHA-256 |
|---|---|
| scikit-image `filters/thresholding.py` | `d0db935f85506c0daf44337c56a01d20de07953901f88b61059d5db12a1a65fd` |
| scikit-image `filters/_median.py` | `e29a751d32178a32969eddbd8fcad14ddaa35de45a7580cf10690e190ce78ee4` |
| scikit-image `exposure/_adapthist.py` | `bb14732b5a4c4f419ba757454b65bedb623c7a7d79e72a870d7c04f0da1d5ac4` |
| scikit-image `feature/_canny.py` | `8386807a674ee79de3445d3833ba125d6403bda13d6ffdbd79ec1059b217f463` |
| scikit-image `filters/_unsharp_mask.py` | `128279247ae775c82b9620ec79ffd671033a3b71c69ecc115145b7b4b065518b` |
| Pillow `src/PIL/ImageOps.py` | `03af6a8edfa6c4323df77f3bcffe1c1c8fb0b47f3948b2ff10d4c8f4478e4062` |

For each filter, use at most 8 generated representative inputs: constant, two-level tie, ramp, impulse/noise, border-touching shape, narrow/odd-sized raster, low-contrast pattern, and synthetic multicolor artwork. No personal photos, downloaded characters, or fonts without redistribution permission. Exhaustive 256-entry LUT checks are scalar arithmetic tests rather than additional image matrices.

A future oracle generator must record input hashes, upstream commit/dependency versions, dtypes, all default arguments, intermediate arrays, and raw output bytes with hashes in provenance. Feed the exact same cropped/resized pixels to Kotlin and upstream; do not compare two JPEG decoders or two resize implementations. Generate goldens once in an explicitly approved reference environment; normal JVM tests read committed redistributable fixtures and need no Python/native runtime/network.

Integer/LUT outputs, Otsu masks, Canny masks, palette order, and label maps require zero differing pixels. For floating filters, target identical intermediate arithmetic and require exact final uint8 pixels using the upstream conversion; log maximum absolute/ULP error for investigation. Do not quietly introduce a tolerance, border exclusion, SSIM-only pass, or alternate threshold when equality fails. Resolve measured differences against the original before approval.

Add engine tests for input immutability/determinism and app tests for revision invalidation, cancellation, preview without stitching, and result reuse. Then generate representative PES through the unchanged S1/writer/check path and assert actual-byte P1–P8/`SewoutCheck` results. Pixel equality is algorithm verification; neither it nor a gate pass substitutes for the user's actual sew-out.

## Next units

1. Audit existing median and implement the small autocontrast LUT port, with original-output fixtures and attribution, before enabling any new UI selection.
2. Port Otsu and add the binary-mask tracing adapter; retain threshold/mask/preview comparisons and early error handling.
3. Wire the named selector and existing Wu color workflow, then measure synthetic inputs on development emulator/handset. Decide whether CLAHE is warranted from those results.

This document does not claim implemented filters, an upstream-oracle PASS, or mobile performance PASS. Documentation checks cover only source/default transcription, local links, English text, and formatting.
