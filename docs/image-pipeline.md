# Photo → Embroidery S5: Research and Adoption Plan

Revised 2026-10-24. Basis: **Failure-free design** in `docs/ui-stories.md`.
Currently at the **S4 integration review branch** stage. The porting-unit records below describe their status at the time; the monochrome redwork engine and 3-step photo UI are now connected. The coordinator will merge into main after S2/S3.

## Decisions

- Freeze `codex/editor`'s custom cell-boundary tracing, shared-boundary simplification, and rectangle occupancy/width-based
  classification as experiments. Do not merge all of `e591f3d`, `d2e734b`, and `323645f` into the product path.
- First apply verified public implementations unchanged. A custom reimplementation with merely similar function names
  is not considered an upstream port. Do not reuse experimental values such as 98% occupancy, aspect ratio 3, 0.6/6mm width, or 0.5mm².
- Do not create UI for locks, density, underlay, pull compensation, angle, or stitch-type selection. Use upstream defaults
  and automatic rules whose evidence codex has checked. Do not assign arbitrary values to unconfirmed rules.
- Do not execute Ink/Stitch/ImageJ/libimagequant or install dependencies. For the latest upstream-comparison instruction,
  Potrace alone was executed as original C on just 3 small inputs in unit 2 below. Preserve Kotlin engine code
  and full license texts; do not connect it to the app.

## User flow: only 3 steps

| Step | User input | Automatic processing/result |
|---|---|---|
| 1. Pick/crop | Photo selection, cropping | Preserve original, honor orientation, preserve existing editing work on cancellation |
| 2. Color count | Integer 2–6 | Same original/size/settings yield identical quantization. Provide only thread-color and shape previews |
| 3. Result | To editor / to viewer | Actual stitch count/color count/Pass or Block. Blocked results cannot be transferred. Only position/size/color can be edited |

Do not add a separate "automatic stitch assignment" step, detail slider, presets, background-removal brush, or stitch settings.
Camera correction/background removal and other additions are outside this minimal S5 scope.

## Upstream implementations and licenses investigated

### Contour tracing: Potrace 1.16 as first candidate

The [official project](https://potrace.sourceforge.net/) and
[official manual](https://potrace.sourceforge.net/potrace.1.html) specify **GPL-2.0-or-later**.
The plan is to preserve attribution to Peter Selinger and upstream contributors and incorporate it under distribution
terms suitable for this GPL-3.0-or-later project. Proprietary Potrace Professional is excluded.

- Official source: <https://potrace.sourceforge.net/download/1.16/potrace-1.16.tar.gz>
- Read-only copy: `/tmp/myembro-potrace-1.16-source.tar.gz`
- Downloaded source SHA-256: `be8248a17dedd6ccbaab2fcc45835bb0502d062e40fbded3bc56028ce5eb7acc`
- Core files: `src/decompose.c` (path decomposition), `src/trace.c` (curves),
  `src/curve.c`, `src/potracelib.c` (default parameters), and related headers.
- Defaults confirmed in `potracelib.c`:

| Parameter | Upstream default | Interpretation |
|---|---|---|
| `turdsize` | 2 | Small-speck removal by pixel count. Not mm² |
| `turnpolicy` | MINORITY | Resolves ambiguity at diagonal contacts |
| `alphamax` | 1.0 | Corner handling |
| `opticurve` | 1 | Enable curve optimization |
| `opttolerance` | 0.2 | Upstream curve-optimization tolerance. Not mm |

Potrace takes a black-and-white bitmap. It is not a color-embroidery algorithm accepting an RGB photo directly.
The surrounding connection that builds and traces per-color masks after quantization must be validated separately.
Do not arbitrarily convert default pixel units to mm²/mobile-resolution values.
Compare origin/y-axis conversion against the upstream SVG backend; connect internal embroidery coordinates through the shared SVG px contract.

### Color-quantization candidate comparison

| Candidate | Official basis/license | Assessment |
|---|---|---|
| [libimagequant](https://pngquant.org/lib/) | RGBA→indexed palette. Official page specifies GPL v3-or-later and additional copyright notices, with current Rust/C API guidance | First comparison candidate with explicit licensing. Maintaining the pure JVM contract requires faithfully porting the core and comparing upstream palettes/indices. Do not introduce JNI arbitrarily |
| [ImageJ 1.x](https://imagej.net/software/imagej/) `ij/process/MedianCut.java` | Project described as public domain. File identifies Heckbert median-cut, lineage from Anton Kruger's existing implementation, and AWT dependencies | Candidate for comparing JVM porting costs. Do not adopt before further checking file lineage/notices and AWT boundaries. Do not infer file-specific terms solely from the project-wide description |
| Existing `ImageQuantizer.kt` | Custom median cut/CIELAB matching | A standard name alone does not establish output equivalence. Excluded from the default path |

Pinned ImageJ research source:
[tag v1.54p / 03382be1a0b650010b077102b5e5b20eaa6a8967](https://github.com/imagej/ImageJ/tree/03382be1a0b650010b077102b5e5b20eaa6a8967),
`ij/process/MedianCut.java` SHA-256
`ec26c65f82e60c9e711bfeefeb2ae2141c4a831fa5d4ea63865623043ddbaf94`.
Reference file: `/tmp/myembro-imagej-MedianCut.java`; not executed.
The libimagequant version/commit for adoption is **not yet decided**; pin it before beginning the actual port.

When porting, record upstream path/version/commit/copyright in each file and update `THIRD_PARTY_NOTICES.md`
and full distribution license texts together. No new dependencies are added for this partial engine port either.

## Implementation boundaries and unconfirmed rules

1. `:engine` retains its pure JVM contract. Separate Android decoding/camera/EXIF/UI from the original algorithms.
   C/Rust upstream code is a reference; Kotlin ports preserve calculations/branches/defaults exactly.
2. Preserve quantization palettes and index maps. Check and pin upstream dithering/alpha rules too.
   Do not restore the previous `alpha=128` threshold, white compositing, or arbitrary dithering removal without validation.
3. Preserve holes, diagonal contacts, topology, and Beziers in per-color Potrace paths. New overlaps/gaps between
   per-color curves remain comparison targets. Do not conceal upstream differences through custom JTS correction to obtain a pass.
4. First consider reusing the already sourced `PecThreads.distance` (pyembroidery red-mean) for thread-color matching.
   Do not add custom color distances/arbitrary thresholds. Compare source colors, thread colors, and actual color counts.
5. Pass results into the upstream conversions and default stitch generation verified by S2/S4. Do not unconditionally
   repurpose font-height rules to classify photo regions as satin/running. Do not generate arbitrary rails for regions
   lacking upstream conversions, rules, and gates. Tatami also accepts only validated inputs.
6. Minimum mm² for tiny fragments, per-photo-region type decisions, and validated resolution/size ranges are **unconfirmed**.
   Do not use them as product defaults before codex provides evidence/validation. Do not claim `turdsize=2px` alone
   proves the minimum area that can actually be sewn.
7. Pass edits as ordinary editable objects preserving original path/crop/color count/thread palette/physical size/algorithm version
   (S4/model contract needs confirmation). Regenerate stitches when size changes.
   Do not save only a `StitchObject` and lose the path, or unconditionally scale stitch coordinates as for imported PES.

## Comparisons and stages required before adoption

- Compare upstream palettes/index maps against the port using identical RGB input and color counts 2–6.
- Fix black-and-white rectangle/curve/hole/diagonal-contact/small-island/transparent-boundary inputs and compare
  topology/curves with upstream Potrace paths. The independent evaluator determines numeric tolerances.
- Check end-to-end behavior with 2–3 self-created logos/shapes with explicit usage terms or user-authorized photos.
- Stitch generation uses defaults from Ink/Stitch v3.3.0 `b0edd96311ece82ee48816dc93466274b12c2a9c`.
  Compare at most 10 representative inputs per feature with gates based on actual sewing failures. Do not fit output to old PE-Design
  lock-byte patterns. Do not reuse the 7 past experimental PES BLOCK verdicts as decision evidence after recalibration.
- During implementation, provide reproduction commands recording identical input lists/upstream hashes/settings/output hashes,
  and output PNG/masks/paths/PES together under `/tmp/myembro-port-out/editor/`.
  **No executable S5 reproduction task or new PES has been created at this point.**
- Adopt in order: upstream comparison → codex functional gate → 3-step UI smoke → alpha → actual user sew-out verification.
  Do not add photo-UI entry points or hidden default paths before validation.

## Revised time estimate

Current playback UI simplification + this research/plan + smoke: **1–2 hours**.
S5 implementation is separate work after S2/S4 and comparison-oracle preparation; current assumptions follow.

| Future work | Approximate time for a pure JVM port |
|---|---|
| Pin quantization upstream/port core/compare upstream | 8–16 hours |
| Port Potrace/compare paths and topology | 16–24 hours |
| Connect validated embroidery generation/independent quality validation | 8–16 hours |
| Minimal 3-step photo UI/mobile smoke | 6–8 hours |
| Total | **38–64 hours**, excluding external review/actual sew-out waiting |

The previous 4–6-hour estimate covered the custom experimental foundation. It did not include the upstream algorithm equivalence,
file-specific licensing, and independent validation now required, so it is not an S5 schedule. Reusing a validated JVM implementation
could shorten it, but no schedule is finalized based on reuse that is currently unconfirmed.


## First porting unit — 2026-10-24, engine only

- Branch `codex/potrace`, based on main. Added only `internal` APIs, not connected to the app.
- Pinned source: the 1.16 distribution above + read-only mirror commit
  `886392d351417d6753aff61de08e294d1506e502` (`Merge with v1.16`).
  The two C files being ported have identical SHA-256 hashes to the distribution:
  - `src/decompose.c`: `a54e9a5af575ecdf974ed8686e0045628c03beda9d0869759963fefdb7efdf0f`
  - `src/trace.c`: `f70964c69d8fec779e4f3968ddb5fcdc6c45a2666f9d96195fafd82543e195fe`
- `BinaryContourTracer`: Ported `findpath`, `majority`, `xor_path`, the default MINORITY policy,
  and upstream `turdsize=2`'s **area > 2** pixel test.
- `OptimalPolygon`: Ported `calc_sums`, `calc_lon`, `penalty3`, and `bestpolygon`.
  Results are index lists of original boundary points, not finished curves/SVG.
- Coordinates retain the upstream **bottom-left origin, upward y, pixel corners**.
  There is not yet an adapter to the shared SVG contract (downward y).
- GPL-2.0-or-later attribution/modifier identification and the full GPL text are included in engine resources.
  Can be combined with the app by choosing GPL v3 terms; no new libraries/native binaries.
- Reproduce: `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*BinaryContourTracerTest'`.
  Checks only 8 representative functions (empty input, specks, rectangles, holes/islands, diagonal contacts, stairs,
  input preservation/separate regions, invalid input). No claim of upstream execution comparisons or independent quality-gate passage.
- Proposed next unit: port upstream vertex adjustment → smoothing.
  `pathlist_to_tree`, Beziers and curve optimization, SVG conversion, and actual embroidery integration are not yet implemented.

### Differences in the partial port

| Item | Current difference and reason |
|---|---|
| Bitmap storage | BooleanArray instead of C word padding. XOR uses x=0 as its reference to preserve even-crossing parity on the same row; input is copied |
| Region area | Intermediate area retained as Long. C public path.area saturation at INT_MAX not yet implemented |
| Path hierarchy | Parent/child reordering by `pathlist_to_tree` not implemented. Currently returns decomposition search order and original positive/negative signs only |
| Policy/API | Only default MINORITY exposed. Other turnpolicy options, progress callbacks, and C memory-error returns are outside this unit |
| Polygon | Non-cyclic bestpolygon containing boundary point 0, as upstream. Not presented as final curves/vectors |


## Porting unit 2 — vertex adjustment and smoothing

- `CurveFitter.kt`: Ported `pointslope`, `adjust_vertices`, `reverse`, and `smooth` from the same pinned commit.
  Preserves covariance eigenvectors, addition of orthogonal axes for singular matrices, minimization on 1-pixel-square boundaries,
  reversal of negative paths, and corner/Bezier branching at upstream `alphamax=1.0`.
- 3 upstream comparisons: 6×4 rectangle, 5×5 stairs, 7×7 region with a hole. Unmodified original C sources
  were executed through a temporary API caller. Source execution is limited to this explicit upstream-comparison request;
  no Ink/Stitch execution or dependency installation.
- Boundaries/polygons/positive and negative signs/areas match exactly. Adjusted vertices/Bézier control points/alpha/alpha0/beta
  match within absolute error `1e-10`. **296 engine tests passed**, including 3 comparison tests.
- Compared only **implemented stages 1–4** with upstream `opticurve=0`. This does not establish equivalence
  to final optimized curves with default `opticurve=1`. Stage 5 optimization and path hierarchy remain unimplemented.
- Reproduce: `tools/potrace/generate-stage4-goldens.sh /path/to/potrace-1.16`;
  output `/tmp/myembro-port-out/editor/potrace-stage4/`. For inputs/upstream goldens/hashes,
  see `engine/src/test/resources/potrace/README.md`. Ordinary tests do not require C execution.
- No new stitches/PES/app entry points. Not subject to the independent sewing-quality gate; no passage is claimed.
- Proposed next 20-minute unit: first partial port of upstream `opti_penalty`/`opticurve`.


## Porting unit 3 — stage 5 curve optimization

- `CurveOptimizer.kt`: Ported `opti_penalty`, `opticurve`, `tangent`, and curve/dot-product/area
  calculations from the same pinned upstream. Uses upstream convexity/corner/179-degree bend limits, tangent error,
  dynamic programming, and beta reconstruction unchanged. Default `opttolerance=0.2px`, with no arbitrary adjustments.
- Added comparisons of **final upstream paths with default `opticurve=1`** on the same rectangle/stairs/hole inputs.
  Also added one small 10×10 rounded mask to verify actual merging (7→3 in both upstream/Kotlin).
- 8 stage 4/5 tests across 4 representative inputs: identical boundaries/polygons/signs/areas/tags/curve counts;
  adjusted/optimized vertices/control points/alpha/alpha0/beta match within absolute error `1e-10`.
  Code also asserts that optimization does not mutate original curves.
- Reproduce: `tools/potrace/generate-stage5-goldens.sh /path/to/potrace-1.16`;
  output `/tmp/myembro-port-out/editor/potrace-stage5/`. Ordinary Gradle tests automatically compare committed upstream
  goldens, requiring no C compiler/download/external process.
- Full upstream path hierarchy/sorting, SVG coordinate conversion, and app/embroidery-generation integration remain separate work.
  Comparison of 4 cases is not extrapolated into equivalence for all inputs or passage of actual sewing quality.
- Under the automated validation policy, subsequent embroidery-generation integration must verify actual sewing-failure conditions in
  `SewoutCheck` and official stitch-count/Hausdorff comparisons through code. Current output is curves, so there is no new PES.


### Unit 3 automated validation results and shared check blocker

- **301** engine tests including 8 upstream comparisons, and **172** app tests each for debug/release: 0 failures.
- `./gradlew check` run: `:engine:check` and app unit tests passed, but failed on 14 existing errors
  in `:app:lintDebug`. Affected files are identical to the main base at porting start (`c0b5f94^`) and
  were not modified by this port. Shared `origin/main` can move during parallel work. No lint baseline/suppressions were added.
- The 14 errors: 6 minSdk26/API27 theme-resource attributes, 7 existing produceState lint errors,
  1 Korean `app_name` translation. Detailed report: `app/build/reports/lint-results-debug.txt`.
- Current branch CI triggers only on main push/PR and runs JVM tests and APK builds.
  Switching to all-branch pushes + shared-policy check must accompany the coordinator's CI/app cleanup.
  Local unit-test success is not described as green CI or merge approval.


## Wu quantization replacement unit — 2026-10-24

- Upstream is Xiaolin Wu's Graphics Gems II (1991) algorithm **C v2**, `gemsii/quantizer.c`.
  Pinned commit `ca4898517a93b6b45444a9cda5fd50132694b864`; upstream SHA-256
  `38b0963722bc3346b18ba58695c87bb7beeb375278f1abd0af05e46933d2a8f6`.
- Preserved the custom Graphics Gems reuse permission and upstream “Free to distribute” notice.
  Not labeled MIT/public domain. Full text/author notices are included in engine resources and distribution notices.
- Main lacks the frozen custom ImageQuantizer, so `ImageQuantizer.kt` is newly added as the Wu-based engine
  entry point. The full median cut/CIELAB/alpha/region heuristics from `codex/editor` are not imported.
  Subsequent integration must replace the experimental file with this new implementation.
- Algorithm follows upstream unchanged: 33³ cumulative moments, 5-bit RGB buckets, float32 squared sums/scores/variance,
  maximum-variance box selection, red→green→blue tie priority, first maximum split, integer-division palettes,
  and pixel labels by box membership. No nearest-color reassignment/palette sorting/dithering added.
- Accepts RGB24 only. Alpha compositing/transparent pixels/thread-chart matching are outside the original Wu core,
  so no arbitrary defaults apply. Supports original requested color counts 1–256, separately from mobile color-count UI.
- Just 3 representative upstream comparisons: primary colors, 8×8 gradient, 5-bit boundary colors. Fills only the existing I/O gaps
  in C main to extract actual LUT/Qadd. Asserts palette order/every pixel index at integer precision.
  For reproduction commands/provenance/hashes, see `engine/src/test/resources/wu/README.md`.
- Output `/tmp/myembro-port-out/editor/wu/`. No app calls/new stitches/PES, so not subject to a sewing-quality verdict.

### Explicit differences in the Wu port

| Item | Difference and basis |
|---|---|
| Execution/memory | Per-call JVM arrays instead of C globals; immutable input, defensive copying of returned indices |
| Statistical integers | Preserves LP64 C long as Kotlin Long. The 1,048,576-pixel input cap is a JVM resource limit, not a color-selection rule |
| Bottom/Top | Calculated using the same sub-box's 8-term Vol. Bounded Long integer sums are exact, yielding identical statistics while preserving float-score order |
| Bounds checking | Rejects empty input, values outside RGB24, and color counts outside 1–256. Blocks upstream undefined input/memory errors through JVM exceptions |
| Classification/alpha | Existing custom heuristics not ported. Separated from RGB quantization |

### Wu unit execution results

- `./gradlew :engine:test --tests '*ImageQuantizerOracleTest'`: 4 tests, 0 failures, 0 errors.
  Includes exact-equality assertions for palette order and all pixel indices of 3 upstream C inputs.
- `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:check`:
  Engine 397 tests, 0 failures, 0 errors.

```text
BUILD SUCCESSFUL in 9s
6 actionable tasks: 4 executed, 2 up-to-date
```

These are local engine-check results, not green CI or passage of sewing quality for new embroidery/PES.


## Monochrome redwork integration 1

Connected the user-decided initial path through `RedworkPipeline.generate`. RGB24 → Wu 2 colors →
exclude caller-specified background pixel label → default Potrace optimized contours → existing SVG flattening →
Ink/Stitch running defaults → S1 → PES v6. No app calls.
Two self-created rectangle/circle PBMs, code assertions, original external quality-gate output,
and an internal consecutive-repeat counting regression fix are recorded in [qa/redwork-engine.md](qa/redwork-engine.md).
Reproduce: `tools/redwork/check-fixtures.sh`; output `/tmp/myembro-port-out/editor/redwork/`.


## Updated user decision: fill black line regions

2026-10-24: Potrace produces both boundaries of a band, so contour running is not used as the default
for photo line art. Black regions are constructed with holes using EVEN_ODD, then filled with the same model
DefaultTatami (default tatami + underlay) used for Hangul TTF. No detailed parameter UI is added.
Detail/line-thickness pairs blocked by inspection are automatically excluded from the sweep JSON allowlist.
Automation covers measured synthetic inputs, not a claim of safety for all ordinary photos.

Centerline algorithms (public algorithms such as Autotrace) remain research candidates for the next version. Before adoption,
require a pinned upstream/GPL license compatibility/representative comparisons of thin lines, intersections, and endpoints;
this work does not implement custom heuristics for centerline estimation, contour averaging, or skeletons.


## v0.2.1 user decision — single-color silhouette

Current S4 is named ‘single-color silhouette’. Retain original tatami fill and separate 2nd-step contour
preview from 3rd-step stitch generation. True line art (centerline/running) and preprocessing-filter
selection UI remain next-version candidates only. Performance/failure causes and numeric evidence are recorded in
[Silhouette measurement report](qa/photo-silhouette-performance.md).
