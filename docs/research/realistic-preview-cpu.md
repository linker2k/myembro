# CPU realistic preview prototype

Status: experimental, JVM-only measurement, no app connection. Output is an ARGB raster;
embroidery commands/threads, PES bytes and P1–P8 rules are untouched. No reference-equivalence
PASS threshold has been invented. The remaining errors below mean this is not yet a replacement
for the existing viewer.

## Public boundary

`engine.render.realistic.RealisticThreadRenderer.render(width, height, dpi, segments, rasterizer)`
accepts immutable SVG-coordinate segments and an injected `ThreadAlphaRasterizer`.
`ThreadShape.path` returns the original transformed cubic capsule, including zero-area whiskers.
The rasterizer supplies source-alpha coverage (0–255). Kotlin performs Gaussian blur, normal
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

## Measured limitations and next comparisons

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
been achieved. Current prototype has no atlas/cache, transparent background output, cancellation,
playback, scalable document memory policy or final viewer integration.

## Recorded result (2026-10-31)

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
