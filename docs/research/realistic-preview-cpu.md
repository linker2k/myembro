# CPU realistic preview prototype

Status: experimental, JVM-only measurement, no app connection. Phase 2 now uses object-bounded
filter buffers and an optional exact-key cache; phase 1 results below are retained as history. Output is an ARGB raster;
embroidery commands/threads, PES bytes and P1–P8 rules are untouched. No reference-equivalence
PASS threshold has been invented. The remaining errors below mean this is not yet a replacement
for the existing viewer.

## Public boundary

`engine.render.realistic.RealisticThreadRenderer.render(width, height, dpi, segments, rasterizer, cache, pixelsPerSvgUnit)`
accepts immutable SVG-coordinate segments and an injected `ThreadAlphaRasterizer`.
`ThreadShape.path` returns the original transformed cubic capsule, including zero-area whiskers.
The rasterizer receives local object-bounded path coordinates and supplies source-alpha coverage
(0–255). `pixelsPerSvgUnit` is the actual integer-raster/SVG-viewport ratio; the nominal `dpi/96`
default is appropriate only when that defines the intended viewport. It must not replace the actual
ratio when a physical page has been rounded to integer pixels. This prototype currently takes a
uniform scale (all reference pages and benchmark pages use equal X/Y ratios). Kotlin performs Gaussian blur, normal
calculation, distant specular lighting, atop, arithmetic composition and source-over in order.
The output is a new opaque ARGB IntArray over white. Segments and source coverage are not modified.

There are no Android, AWT or ImageIO dependencies in the production calculation code. Tests alone
use Java2D Path2D fill for coverage and ImageIO for PNG output. An API26 implementation of the
coverage interface has not been written or verified. `displayRgb` is already corrected through
upstream `visible_on_white.darker`; fixture values come from the pinned Python generator, not
from a second color heuristic. No EmbPattern/UI adapter is added in this phase.

## Originals and constants

| Code | Fixed original / parameters |
|---|---|
| `ThreadShape` | [Ink/Stitch rendering.py:16–126](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/svg/rendering.py#L16): `1.398` height, Bezier `0.613/1.4`, whiskers `0.55/0.1`, `0.2mm` visual shortening, `96/25.4` SVG units/mm. No curve flattening added. |
| `ThreadFilterMath` parameters | Same file: SourceAlpha sigma `0.9`, sRGB, surfaceScale `4.29`, specularConstant `0.65`, exponent `1.6`, azimuth `154`, elevation `112`, atop then arithmetic `k1=0,k2=0.8,k3=1.2,k4=0`. |
| Normals / lighting | [resvg lighting.rs](https://github.com/linebender/resvg/blob/4ba2462da4ecf1f93731e957fc65d4ccb7c51bed/crates/resvg/src/filter/lighting.rs): 3×3 Sobel and boundary factors `1/4`, `1/3`, `1/2`, `2/3`, halfway vector `(light + (0,0,1))`. |
| Inkscape reference differences | [nr-filter-specularlighting.cpp:53–68,177–194](https://gitlab.com/inkscape/inkscape/-/blob/ebf0e940d050842d608d57f6a04e6daaa5c65f37/src/display/nr-filter-specularlighting.cpp#L53): multiply surfaceScale by device-space scale, and explicitly premultiply specular RGB by alpha. Copying resvg without these differences substantially brightens the result. Original light/filter constants remain unchanged. |
| Alpha / arithmetic rounding | Same Inkscape commit `cairo-utils.h:149–153`, `nr-filter-composite.cpp:36–57`: UN8 multiply and `255²` coefficient scaling/rounding/clamping. |
| `InkscapeGaussian` | [nr-filter-gaussian.cpp](https://gitlab.com/inkscape/inkscape/-/blob/ebf0e940d050842d608d57f6a04e6daaa5c65f37/src/display/nr-filter-gaussian.cpp): non-subsampled BEST branch. FIR radius `ceil(3*sigma)`, 16 fractional coefficient bits; IIR when sigma `>3`, poles `1.40098+i*1.00236`, `1.85132`, binary-search precision `sigma/2³⁰`, Young–van Vliet recursion and Triggs–Sdika boundaries. |

All numerical constants in these operations are original defaults, original numerical representation
or original filter coefficients. No empirically fitted brightness/blur correction is used.
resvg's five-box approximation was measured first, but the final prototype uses the original
Inkscape Gaussian because the two algorithms do not have identical output. Licenses and attribution
are in THIRD_PARTY_NOTICES and the source headers.

## Reproduction

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*RealisticThreadRendererTest*'
```

The eight synthetic fixtures in `engine/src/test/resources/realistic-preview/scenes.json` match
`tools/realistic-preview/generate_reference.py`: horizontal, vertical, diagonal, satin, tatami,
crossing, white and black. They are display fixtures, not machine-ready lock/trim stitch plans.
Tests write 16 candidate PNGs plus 16 no-filter coverage controls and per-scene timing JSONs to
`engine/build/realistic-preview-cpu/`. Timings include path creation, test-only Java2D coverage and
CPU filtering, exclude PNG encoding, and are individual JVM invocations with JIT/GC noise.
They are not Android performance results.

With phase 1 references in `/tmp/myembro-realistic-ref`:

```sh
/tmp/myembro-realistic-ref-venv/bin/python tools/realistic-preview/compare_images.py --reference /tmp/myembro-realistic-ref/png/300 --candidate engine/build/realistic-preview-cpu/png/300 --out /tmp/myembro-realistic-cpu/comparison-300.json
/tmp/myembro-realistic-ref-venv/bin/python tools/realistic-preview/compare_images.py --reference /tmp/myembro-realistic-ref/png/600 --candidate engine/build/realistic-preview-cpu/png/600 --out /tmp/myembro-realistic-cpu/comparison-600.json
```

Repeat with the `flat-control/png/` trees to isolate coverage rasterization. Metrics are RGB8,
white-composited, without resizing/registration; source PNG references are Inkscape 1.4.2 RGBA16.
The metric decoder does not compare the low 8 bits. Whole-page MAE/SSIM can hide a thin-line error,
so inspect foreground errors and the high-error tatami case separately.

## Phase 1 diagnosis (historical; bounds fixed in phase 2)

| Existing implementation / limitation | Prototype change or next proposal | Measurement that decides improvement |
|---|---|---|
| resvg numeric path is not the Inkscape reference: device-space bump height and specular premultiplication differ | Port the verified Inkscape operations; keep its defaults | 300dpi tatami MAE fell from `19.567883` to `5.353825`, 600dpi `22.071291` to `2.367767` before Gaussian/rounding refinements. These were measurements, not fitted coefficients. |
| resvg box blur approximates a Gaussian | Use Inkscape's original FIR/IIR and fixed-point rounding | Per-scene original-reference comparison; results in `/tmp/myembro-realistic-cpu/report.md`. |
| Whole-page filter buffers do not reproduce Inkscape's per-object filter-region edges | Next: reproduce exact FilterSlot/FilterUnits buffer bounds and transforms from the same Inkscape commit | Horizontal 300dpi middle-column blurred alpha: reference `[188,185,173,152,125,94]`, whole-page prototype `[118,137,143,135,115,89]` at y=92…97. Changing only the diagnostic SVG filter region to the full page reduces tatami MAE to about `0.70` at 300dpi before final rounding changes. Keep the original reference unchanged; do not tune sigma. |
| Java2D and Cairo coverage AA differ | Next: match and measure coverage before changing lighting | No-filter controls already have small nonzero errors (300dpi MAE `0.011804…0.441636`). Derivatives can amplify these differences. Record per-case coverage error and final error independently. |
| Full-page buffers and per-pixel normal tuples allocate excessively for large plans | After fidelity: per-stitch bounded bake/cache, preserve light direction and subpixel placement | Cache-on/off equality, reference MAE/SSIM, peak allocations and 50k/100k render times. No large-plan speed claim in this phase. |

The diagnostic whole-page SVG changes are investigative outputs under `/tmp`, not a new default
or a replacement baseline. The remaining filter-buffer and coverage differences are explicit;
tests assert artifact validity, determinism and numeric invariants, not equivalence that has not
been achieved. At the end of phase 1 the prototype had no atlas/cache, transparent background output, cancellation,
playback, scalable document memory policy or final viewer integration.

## Phase 1 recorded result (2026-10-31, commit 28c5465b)

Command: `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --console=plain`.
Output: `BUILD SUCCESSFUL in 1m 38s`; XML aggregate **1043 tests, 0 failures, 0 errors,
0 skipped**, including **15 prototype tests**. These assert artifact/numeric behavior, not
pixel-equivalence. `summarize_cpu.py` produces the following unthresholded measurements:

| Scene | 300dpi MAE / SSIM | 600dpi MAE / SSIM |
|---|---:|---:|
| black-thread | 0.235520 / 0.99796941 | 0.128720 / 0.99946084 |
| crossing | 0.067542 / 0.99995521 | 0.087903 / 0.99987241 |
| diagonal | 0.019568 / 0.99998929 | 0.028517 / 0.99996696 |
| horizontal | 0.293945 / 0.99780387 | 0.321130 / 0.99468451 |
| satin-column | 0.312907 / 0.99863065 | 0.190411 / 0.99920866 |
| tatami-patch | 4.893499 / 0.89303316 | 2.157125 / 0.96055719 |
| vertical | 0.295316 / 0.99761989 | 0.298625 / 0.99490398 |
| white-thread | 0.373058 / 0.99896737 | 0.240776 / 0.99883642 |

JVM rendering: 3.552–649.783ms per scene/DPI,
2946.267ms total, including coverage but excluding PNG encoding.
Foreground-union MAE reaches 24.201 for the 600dpi horizontal single stitch: high whole-page SSIM
alone does not demonstrate correct thread highlights. Full table, timing JSON, overview/diffs and
test outputs are in `/tmp/myembro-realistic-cpu/`. Next proposal: port exact Inkscape per-object
filter-buffer layout before sprite caching or any UI connection.

Regenerate the complete table and contact sheet:

```sh
/tmp/myembro-realistic-ref-venv/bin/python tools/realistic-preview/summarize_cpu.py --candidate engine/build/realistic-preview-cpu --reference /tmp/myembro-realistic-ref --out /tmp/myembro-realistic-cpu
```

## Phase 2: original bounds/mapping and exact-key cache

### Source-based mapping correction

1. [drawing-item.cpp:577–581,734–748,826](https://gitlab.com/inkscape/inkscape/-/blob/ebf0e940d050842d608d57f6a04e6daaa5c65f37/src/display/drawing-item.cpp#L577)
   computes the filter effect area from the object bbox, rounds outwards and clips dependency
   expansion back to `_drawbox`. The original filter is x=0,y=0,width=1,height=1. There is no new
   fitted blur padding. The Kotlin region includes the original zero-area whisker extrema and exact
   cubic derivative extrema, then subtracts the integer origin before coverage rasterization.
2. [FilterSlot:29–56,137–158,192–215](https://gitlab.com/inkscape/inkscape/-/blob/ebf0e940d050842d608d57f6a04e6daaa5c65f37/src/display/nr-filter-slot.cpp#L29)
   determines integer surface dimensions and the local/device mapping;
   [FilterUnits:66–85](https://gitlab.com/inkscape/inkscape/-/blob/ebf0e940d050842d608d57f6a04e6daaa5c65f37/src/display/nr-filter-units.cpp#L66)
   supplies the scale. The prototype uses the corresponding uniform-scale/translation path,
   not a general SVG filter implementation (arbitrary group affine transforms remain out of scope).
3. [PNG export:429–443](https://gitlab.com/inkscape/inkscape/-/blob/ebf0e940d050842d608d57f6a04e6daaa5c65f37/src/helper/png-write.cpp#L429)
   sets scale to `integer_width / area.width()` and `integer_height / area.height()` and selects
   exact/BEST filter rendering. For 16mm at 300dpi, the actual scale is `189/(16*96/25.4)` =
   `3.125390625`, not `3.125`. The horizontal filter min y crosses an integer boundary: its top
   is **92**, not **91**. This is an export-coordinate correction, not a new sigma/brightness value.
4. Original C++ FIR functions were extracted without numerical changes to a minimal `/tmp` harness.
   With the actual 97×6 region, sigma 2.8125, center-column input alpha
   `[170,255,255,255,170,0]` produces `[188,185,173,152,125,94]`, exactly the Inkscape PNG trace.
   This numeric output is fixed by `originalCppFirGoldenAtActualFilterBounds`.

### Cache and allocation changes

`ThreadShapeCache(maxPixelBytes)` is explicit, caller-owned and **thread-confined**. No application
memory budget is selected here. LRU keys contain exact length, angle, DPI, actual scale, X/Y phase,
RGB, region dimensions and the complete normalized path. No angle/length/phase buckets, tolerance,
sprite rotation or geometry simplification are used. The additional exact path is conservative:
floating-point translation differences can reduce hits instead of silently changing pixels.
The cache stores premultiplied colored pixels, then composites each region in original sewing order.
The byte budget covers pixel arrays only; key/object/native overhead remains additional.

Per-pixel FloatArray normals and test-rasterizer boxed alpha lists were replaced by primitive
locals/arrays. This is representation/allocation only: all 16 PNG files stayed byte-identical,
and all four large-workload ARGB SHA-256 values stayed identical before/after that optimization.
No sigma, lighting coefficient or composite constant changed. Memory peaks did not reliably fall
under the large JVM heap cap: GC behavior and remaining Java2D/temporary buffers still matter.

### Measured results

The main path below uses Java2D coverage (test-only), **not reference masks**. Times are independent
cold JVM experiments, not Android results. The 512MiB-cap rows are the primary measurements;
128MiB-cap rows are separate constrained-memory experiments, not a new app policy. Both caps
produce identical ARGB hashes for the same workload. Exact cache repeats are also asserted against
uncached rendering for all 16 reference scene/DPI combinations, including a second warm render.
Tiny-cache eviction/recolor, zero-budget misses and phase/direction changes are regression tests.

## Before/after (same Inkscape reference)

| Scene | DPI | MAE before → after | SSIM before → after |
|---|---:|---:|---:|
| black-thread | 300 | 0.235520 → 0.060945 | 0.99796941 → 0.99987827 |
| crossing | 300 | 0.067542 → 0.040378 | 0.99995521 → 0.99998213 |
| diagonal | 300 | 0.019568 → 0.019391 | 0.99998929 → 0.99999009 |
| horizontal | 300 | 0.293945 → 0.062139 | 0.99780387 → 0.99988428 |
| satin-column | 300 | 0.312907 → 0.133208 | 0.99863065 → 0.99973893 |
| tatami-patch | 300 | 4.893499 → 1.185922 | 0.89303316 → 0.99188310 |
| vertical | 300 | 0.295316 → 0.000327 | 0.99761989 → 0.99999980 |
| white-thread | 300 | 0.373058 → 0.209933 | 0.99896737 → 0.99958476 |
| black-thread | 600 | 0.128720 → 0.095364 | 0.99946084 → 0.99963954 |
| crossing | 600 | 0.087903 → 0.042036 | 0.99987241 → 0.99995015 |
| diagonal | 600 | 0.028517 → 0.027810 | 0.99996696 → 0.99996975 |
| horizontal | 600 | 0.321130 → 0.054317 | 0.99468451 → 0.99984574 |
| satin-column | 600 | 0.190411 → 0.142727 | 0.99920866 → 0.99947893 |
| tatami-patch | 600 | 2.157125 → 0.925228 | 0.96055719 → 0.98784326 |
| vertical | 600 | 0.298625 → 0.015999 | 0.99490398 → 0.99996706 |
| white-thread | 600 | 0.240776 → 0.177438 | 0.99883642 → 0.99926180 |

## Large synthetic workloads (600dpi, 2250×2000px)

Separate cold JVMs. Render excludes fixture creation/hash/PNG encoding. Heap peak is sampled every 1ms; process RSS is Linux VmHWM, including JVM/native memory. Cache budget covers pixel payload only (one 18,000,000-byte output frame), not key/object overhead.

| Budget/run | Workload | Stitches | ms | ms/stitch | Peak sampled heap MiB | Peak process RSS MiB | Hits/misses |
|---|---|---:|---:|---:|---:|---:|---:|
| 512MiB JVM cap | repeated | 5000 | 488.961 | 0.097792 | 99.93 | 165.84 | 3291/1709 |
| 512MiB JVM cap | repeated | 50000 | 886.046 | 0.017721 | 220.10 | 307.53 | 48291/1709 |
| 512MiB JVM cap | varied | 5000 | 1026.582 | 0.205316 | 220.95 | 312.32 | 0/5000 |
| 512MiB JVM cap | varied | 50000 | 7928.385 | 0.158568 | 341.37 | 434.02 | 0/50000 |
| 128MiB JVM cap | repeated | 50000 | 830.062 | 0.016601 | 89.89 | 184.80 | 48291/1709 |
| 128MiB JVM cap | varied | 50000 | 7967.028 | 0.159341 | 103.57 | 201.22 | 0/50000 |

Repeated = a grid of 2500 placements repeated (best case). Varied = unique phase/delta per stitch (zero hits, stress case). These display-only synthetic paths are repository-owned, not machine-ready PES files. The heap cap experiments are benchmark configuration, not application memory policy.


### Remaining error isolation

## Coverage substitution diagnostic

Same Kotlin filters, only Java2D coverage replaced by per-path Inkscape SourceAlpha. Reference PNGs unchanged. This is an explicit diagnostic, not the production rasterizer.

| Scene | DPI | Java2D MAE / SSIM | Cairo-alpha MAE / SSIM |
|---|---:|---:|---:|
| horizontal | 300 | 0.062139 / 0.99988428 | 0.000000 / 1.00000000 |
| tatami-patch | 300 | 1.185922 / 0.99188310 | 0.000000 / 1.00000000 |
| vertical | 300 | 0.000327 / 0.99999980 | 0.000000 / 1.00000000 |
| horizontal | 600 | 0.054317 / 0.99984574 | 0.000000 / 1.00000000 |
| tatami-patch | 600 | 0.925228 / 0.98784326 | 0.000000 / 1.00000000 |
| vertical | 600 | 0.015999 / 0.99996706 | 0.000000 / 1.00000000 |

The six diagnostic images match decoded RGB8 pixels exactly after replacing **only coverage**.
The six ordinary Java2D outputs are still non-identical. This isolates the remaining differences in
these scenes to the coverage boundary, which includes Cairo-vs-Java2D antialiasing and the original
Inkex six-significant-digit path serialization. Their individual contributions have not yet been
separated. This does not prove equality for every scene or arbitrary SVG input, nor does it provide
an Android rasterizer. No reference masks are used by the production Kotlin renderer or ordinary
unit tests. PNG sixteen-bit LSBs are not compared.

### Commands

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*RealisticThreadRendererTest*'
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:benchmarkRealisticPreview -PpreviewCount=5000 -PpreviewWorkload=repeated
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:benchmarkRealisticPreview -PpreviewCount=50000 -PpreviewWorkload=repeated
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:benchmarkRealisticPreview -PpreviewCount=5000 -PpreviewWorkload=varied
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:benchmarkRealisticPreview -PpreviewCount=50000 -PpreviewWorkload=varied
```

Use `-PpreviewHeap=128m -PpreviewOutput=/tmp/myembro-realistic-cpu-stage2/benchmark-128m` for the
separate limited-heap experiment. Default test/CI never launches the large benchmark or Inkscape.
For the optional source-alpha diagnostic:

```sh
/tmp/myembro-realistic-ref-venv/bin/python tools/realistic-preview/probe_coverage.py --reference /tmp/myembro-realistic-ref --out /tmp/myembro-realistic-cpu-stage2/coverage-oracle --inkscape /tmp/myembro-realistic-ref/squashfs-root/AppRun
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:probeRealisticCoverage
/tmp/myembro-realistic-ref-venv/bin/python tools/realistic-preview/summarize_cpu.py --candidate engine/build/realistic-preview-cpu --reference /tmp/myembro-realistic-ref --out /tmp/myembro-realistic-cpu-stage2/final --before /tmp/myembro-realistic-cpu --benchmark /tmp/myembro-realistic-cpu-stage2/benchmark /tmp/myembro-realistic-cpu-stage2/benchmark-128m --coverage /tmp/myembro-realistic-cpu-stage2/cairo-coverage
```

Next proposal: measure a verified Cairo-compatible coverage backend or port, preserving these filters.
Keep independent end-to-end comparisons; do not replace normal candidate metrics with diagnostic-mask
metrics. Mobile memory/latency remains unconfirmed until an API26 backend is measured. No UI connection.


Phase 2 validation command: `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --console=plain`.
Output: **BUILD SUCCESSFUL in 1m 36s**. XML aggregate: **1047 tests, 0 failures, 0 errors,
0 skipped**, including **19 renderer tests**. The full-suite 16 output PNG files match the measured
snapshot byte-for-byte. Machine-readable comparisons, workload JSON, Cairo-alpha isolation output,
source hashes and command logs are under `/tmp/myembro-realistic-cpu-stage2/`;
`final/validation.log` records the checks. No application code or stitching/export rule changed.
