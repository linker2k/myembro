# Hangul TTF → Centerline → Upstream Satin Conversion: Pre-implementation Research

> **Only selection logic is deferred — user correction (2026-10-24):** Upstream porting of the Hangul satin/shared centerline engine continues as planned. Kotlin thinning and golden-match tests were completed in `2e2d533`. Next comes upstream centerline path tracing → Ink/Stitch convert-to-satin port. Only automatic satin/fill selection and its width-measurement/classification logic are deferred to the next version. App integration is outside this engine-porting unit.

- **Upstream/license:** Official [AutoTrace 0.31.10](https://github.com/autotrace/autotrace/tree/1a232b27818a46a05cd25edec09e2f77c4e9ac01), pinned commit `1a232b27818a46a05cd25edec09e2f77c4e9ac01`; `src/thin-image.c` is by Martin Weber under LGPL-2.1-or-later, and `src/{autotrace,fit,curve}.c` are GPL-2.0-or-later (per-file headers), allowing combination with GPL-3. Do not label the whole project LGPL. Preserve README/COPYING/COPYING.LIB and per-file notices, and cross-check attribution scope for headerless helper files before implementation.
- **Scope/basis:** [Inkscape integration code](https://inkscape.gitlab.io/inkscape/doxygen/inkscape-autotrace_8cpp_source.html) uses `at_splines_new_full` and the centerline option (confirmed to be the same library family; byte identity between Inkscape's bundled version and the tag above is unconfirmed). Following `src/autotrace.c`, port Rosenfeld `thin-image.c` → `pxl-outline.c::find_centerline_pixels` → `fit.c` and curve/spline/vector helpers; investigate width preservation only through upstream `image-proc.c`'s distance-map path. The 8 relevant C files total 4,225 lines by `wc -l`, excluding GUI/image codecs/new algorithms.
- **Upstream comparison plan:** Run pinned C sources with a small local caller and share 5 identical black-and-white inputs (straight line, T branch, X crossing, ring, a thick raster of the Hangul syllable han from an app Hangul TTF licensed for redistribution) with Kotlin. Record/check thinning masks, paths/branches/open-closed status/order/segment counts, and control-point float/ULP differences in code. Except `centerline=true`, retain `new_fitting_opts` defaults; separate `preserve_width=true` comparison as another upstream-option axis. Store results/input/executable hashes/compiler options in `/tmp/myembro-port-out/editor/autotrace/`; subsequent PES stages check P1–P8 (including integer hoop upper bounds) through the actual-byte gate. No upstream execution/dependency installation/product coding in this unit.
- **Satin integration dependency:** Reuse letter's upstream port of Ink/Stitch v3.3.0 `lib/elements/utils/stroke_to_satin.py::convert_path_to_satin` (GPL-3.0-or-later) when ready. The investigated letter `409769937cff8cff7ff261aa92a05c6e33260205`'s `lettering.SatinColumn.strokeToSatin/rails` still uses custom miter offsets, so do not use it for this path. Upstream conversion takes constant `stroke_width`; varying Hangul stroke widths and intersection-branch connections remain unconfirmed dependencies. Confirm upstream options/contracts first rather than filling gaps with average widths/arbitrary rail approximations.
- **Estimate/next unit:** Based on the code volume above, AutoTrace core + upstream comparisons: 6–10 hours; letter upstream conversion preparation/integration checks: another 2–4 hours (estimated total 8–14 hours, not a promise of quality passage). Propose the next 20 minutes for an upstream thinning caller and small fixed-input goldens. Continue stopping/reporting every 20 minutes with command/test output; do not begin implementation at this document stage.

## Unit 1 — obtaining upstream thinning goldens

- Latest priority: reuse the same upstream centerline engine for both **(A) S4: bean for thick lines/running for thin lines** and **(B) Hangul: upstream convert-to-satin**. A uses only upstream parameters such as `bean_stitch_repeats`; thickness-classification thresholds are unconfirmed before measurement. This unit adds no classification/stitch-generation code.
- Executed upstream `thin_image` unchanged. Pinned 5 inputs (straight/T/X/ring/the Hangul syllable han rendered in public Noto Sans KR at weight 700) × 1/3 channels = 10 PGM/PBMs, plus source/font/executable hashes, in [test resources](../engine/src/test/resources/autotrace/thinning/README.md). Extracted only official headers matching the existing GLib/GObject runtime into `/tmp`, without system installation.
- Reproduce: `python3 tools/autotrace/generate-thinning-goldens.py /tmp/myembro-autotrace-reference --glib-prefix /tmp/myembro-autotrace-build/glib --verify` → `COMMITTED_GOLDENS_EXACT 10/10`, `REPEAT 10/10; IDEMPOTENT 10/10; GRAY_RGB 5/5`. Output `/tmp/myembro-port-out/editor/autotrace/`; Hangul foreground 1,493→174 pixels. Not a claim of Kotlin/full-centerline/satin/PES quality passage.
- Proposed next 20 minutes: port upstream thinning to Kotlin and test exact equality against fixed masks. Then proceed to upstream branch/path tracing; this unit stops at obtaining goldens.


## Automatic Hangul branching — only selection logic deferred (design record), 2026-10-24

The automatic branching below is a next-version candidate and is not implemented now. Upstream Hangul satin/centerline porting continues.

Do not convert all Hangul or an entire font to satin indiscriminately. Branch using the **median of local widths
measured per character or stroke region (mm)**. Do not classify by minimum width, which shrinks at corners.
The 0.9–6.0mm inclusive range directly follows existing `TatamiLettering` constants and the adopted range in
`/tmp/myembro-quality-gate/auto-rules.md`.

| Measured region | Automatic path |
|---|---|
| Line-based + median 0.9–6.0mm | Shared AutoTrace centerline → upstream convert-to-satin → Ink/Stitch satin |
| median >6.0mm or plump, non-line-based shape | Retain existing default tatami + underlay |
| median <0.9mm | Retain existing auto-rules running path; do not force satin |
| Width measurement/line-based determination unconfirmed | Do not certify as satin candidate; retain existing validated path |

Implement width measurement after checking upstream AutoTrace distance-map/`preserve_width` units/scales against
original contours. Only thinning exists now, so median measurement is not implemented yet.
Validation of line-based classification is also unconfirmed; do not invent aspect-ratio/area thresholds.
This median is a **branching criterion**. Separately confirm the input contract between upstream convert-to-satin's constant
`stroke_width` and variable-width characters; do not claim faithful contour reconstruction from the median alone.
The same centerline engine is also reused for S4 bean/running.

## Unit 2 — Kotlin thinning port

Ported `AutoTraceThinning.thin(width,height,pixels,planes,backgroundRgb)` to pure JVM.
Preserves the upstream 512-entry deletion table, N/S/W/E pass order, reverse-order color discovery, 8-bit scan-buffer
truncation, immediate deletion order, and differing boundary protection in upstream thin1/thin3.
The pinned upstream src/thin-image.c SHA-256 and existing goldens were unchanged.

| Boundary difference from upstream | Reason |
|---|---|
| GLib memory/C Pixel arrays → per-call JVM Int/Byte arrays | Identical color comparisons/byte output; preserves input without an external runtime |
| process-global background → explicit per-call background (default white) | Contract specifies background for every call, as in upstream goldens; prevents concurrent/prior-call state leakage |
| unsigned short/JVM size boundaries | Validate each dimension 1–4096px, at most 1,048,576 pixels, planes 1/3, length/RGB-background ranges; reject C undefined inputs/memory errors |
| Logs/callbacks | Current bitmap-thinning API has no progress/GLib error callbacks; rejects invalid input through exceptions |

Validation: **exact equality for every pixel/channel** against 10 existing C goldens, input preservation, repetition/idempotence,
background inversion, and call-state independence. Also obtained gray output `000/111/000` and RGB output
`000/000/110` (1=foreground) for upstream C's all-black 3×3 input, fixing these as boundary-branch regressions.
Invalid dimensions/planes/array lengths/backgrounds are rejected too. 12 Kotlin tests.
Reproduce: `./gradlew :engine:test --tests '*AutoTraceThinningTest'`; additional upstream C output
`/tmp/myembro-autotrace-edge-p{1,3}.pbm`, test log `/tmp/myembro-autotrace-kotlin-test.log`.

LGPL-2.1-or-later attribution/full text are included in engine resources and APK notices. Not connected to the app/PES;
no claim that full centerline/path tracing, smoothing, median-width measurement, or satin conversion is complete,
or that sewing quality has passed. Propose separating the next unit into a small-input caller/goldens for upstream
`find_centerline_pixels` and Kotlin path tracing.


## Unit 3 — upstream centerline pixel-path tracing

Ported `find_centerline_pixels`, `find_one_centerline`, direction marking/search/neighbor counts/reverse-path merging
from `src/pxl-outline.c@1a232b27818a46a05cd25edec09e2f77c4e9ac01` to `AutoTraceCenterline.trace`.
Preserves upstream initial directions E/SE/S/SW, relative next-direction order 0/2/6/1/7/3/5, intersection protection,
and strict `>0` condition for destination pixels above/to the left. Closed paths do not repeat the start point at the end.
Output uses upstream Cartesian pixel coordinates (upward y), not PES coordinates.

Goldens were generated by running unmodified upstream thin-image.c → pxl-outline.c through a C caller.
Across straight/T/X/ring/the Hangul syllable han from a public OFL font × gray/RGB = 10 cases, Kotlin tests compare path order, every integer coordinate,
color, and open/clockwise exactly. Inputs reuse existing thinning PGM files, with no personal designs.
The upstream ring has 1 closed path; the Hangul syllable han has 6 paths. GLib uses only the existing runtime and headers extracted
under `/tmp`, without system installation.

Reproduce: `python3 tools/autotrace/generate-centerline-goldens.py /tmp/myembro-autotrace-reference --glib-prefix /tmp/myembro-autotrace-build/glib --verify`.
Results `/tmp/myembro-port-out/editor/autotrace/centerline/`; committed
`engine/src/test/resources/autotrace/centerline/manifest.json` records upstream commit/source/caller/
generator/input-output hashes/compiler commands. `autotrace.c` links upstream bitmap functions unchanged;
unused I/O/smoothing functions are excluded by linker gc-sections.

Differences: per-call JVM arrays/Lists replace GLib reallocations and bitmaps; input is preserved.
Supported sizes match existing thinning: each dimension 1–4096px, total at most 1,048,576 pixels.
Progress/cancellation/log callbacks are not provided within this pure JVM API's scope.
The headerless pxl-outline.c is attributed in the project's GPL-2.0-or-later context with
Copyright AutoTrace contributors, and the original COPYING text is distributed alongside it.

Tests: `./gradlew :engine:test --tests '*AutoTrace*Test'` (23 tests, 0 failures).
Full `./gradlew check` → `BUILD SUCCESSFUL in 41s` (67 actionable tasks),
log `/tmp/myembro-autotrace-centerline-check.log`.
Not connected to app/PES; curve fitting/width measurement/satin conversion/sewing gates are outside this unit.
Next proposal: obtain comparison goldens for upstream `fit.c` centerline smoothing in small units first,
and confirm the input contract with the upstream Ink/Stitch convert-to-satin port.

## Letter handoff unit 1 — path-golden revalidation/upstream line→satin core

Latest `codex/hangul-centerline` **e1d0ce0** already contained not just thinning but also
a Kotlin `find_centerline_pixels` port. Integrated this into codex/letter and reverified exact matches for
thinning goldens 10/10 and centerline goldens 10/10.
The two test classes total 23 tests (including invalid-input/boundary checks), with 0 failures. Upstream code was not rerun.

Next ported Ink/Stitch **v3.3.0 b0edd96311ece82ee48816dc93466274b12c2a9c**'s
`lib/elements/utils/stroke_to_satin.py` to `elements/utils/StrokeToSatin.kt`:
`convert_path_to_satin/convert_path_to_satins`, `fix_loop`, `path_to_satin`,
`get_scores`, `local_minima`, `generate_rungs`, `_merge`, `set_first_node`.
Also covers `lib/extensions/stroke_to_satin.py:filtered_rungs`, `lib/svg/styles.py:get_join_style_args`,
and `lib/utils/geometry.py:remove_duplicate_points/roll_linear_ring`.
Upstream score 101 bins, angle-squared/int32, smoothing kernel/minima, rounding to 4 places, depth 20 bisection,
1px endpoint criterion, tangent 0.001, rungs at 1.2 times, and 1/2mm spacing are unchanged.
JTS OffsetCurve replaces custom miter offsets; resulting rails/rungs are passed directly to the existing
Ink/Stitch SatinColumn constructor. Upstream CLI/Inkex UI are outside this API's scope.

Checked Shapely LineString.offset_curve defaults quad_segs 16/mitre_limit 5 against the
[official API documentation](https://shapely.readthedocs.io/en/2.0.6/reference/shapely.LineString.html#shapely.LineString.offset_curve).
Only when upstream SVG style explicitly specifies miter is SVG's default miter-limit 4 used;
otherwise Shapely's default 5 is retained. Style code defaults to mitre.

**Validation scope for this unit:** StrokeToSatinTest 7 cases (numeric straight rails/central rungs, 90° corner score 8100,
miter coordinates, closed-path midpoint/split merging, node rungs, closed-extension start point, crossing-rung filtering,
style defaults; some items share a test). These are analytical regressions of upstream formulas;
**equivalence against official convert-to-satin output goldens is not yet determined**. Requested resource paths from the coordinator.
Do not confuse the existing 10/10 upstream centerline-golden verdict with this undetermined status.

Differences/scope:
- Shapely/GEOS replaced by existing approved JTS 1.20 OffsetCurve/LengthIndexedLine/intersection.
  Full numeric equivalence of curved joins/self-intersections is unconfirmed until official-output comparison.
- Input is an explicit path in SVG px and a positive **constant strokeWidth**. No width estimation/satin-fill selection.
  Empty/duplicate-only paths return null; invalid widths are Kotlin input errors; closed-start API requires a closed ring.
- For paths shorter than 1px, every recursively split descendant fails upstream's endpoint<1 condition,
  so recursion that always yields an empty result is skipped early. The depth 20 rule remains for paths of 1px or more and loops.
- Returns JVM Lists; excludes SVG DOM/CLI termination/logging/progress callbacks.

Full `./gradlew check`: BUILD SUCCESSFUL in 50s. All 20 upstream goldens and 7 formula regressions passed.
JUnit/output: `/tmp/myembro-port-out/letter/hangul-unit1/output.txt`.
A PES of the Hangul text Jeongmin in Noto Sans KR400 at 15mm has not yet been generated. Upstream `fit.c` smoothing/
`image-proc.c` distance-map/preserve_width contracts are not yet in Kotlin. Plain SVG output
`output-svg.c` does not separately record stroke-width for centerline paths, so satin is not generated using an arbitrary
average width or 1px default. Propose small checks of official conversion outputs and upstream curve/width contracts,
then connection to this explicit-width API. App integration/automatic selection remain deferred.

## Letter handoff unit 2 — preparing integration of the Hangul text Jeongmin in Noto400 at 15mm

Confirmed the curve contract. `convert_path_to_satin` takes a LineString, so existing upstream pixel centerline paths
can be supplied in their original order. No new spline smoothing/custom curve fitting is added.
Only coordinate transforms from AutoTrace Cartesian y to SVG y-down and raster ink-height to requested
15mm are applied. No claim of identity with `fit.c` spline output.

One input remains needed for the stroke-width contract. Upstream AutoTrace
`image-proc.c:new_distance_map`/`fit.c:fit_curve_list`
@1a232b27818a46a05cd25edec09e2f77c4e9ac01 preserves local distances and neighboring maxima as
`z = width * width_weight_factor`. `output-svg.c:out_splines` does not record
constant stroke-width. By contrast, Ink/Stitch
`convert_path_to_satin`@b0edd96311ece82ee48816dc93466274b12c2a9c requires constant
stroke_width. No mean/median-width mapping absent from upstream was added;
the coordinator was asked for this trial's constant width (mm). Automatic selection remains deferred.

Prepared `tools/hangul_satin/prepare-raster.py` and `:engine:hangulSatinSample`.
The font's default weight is 100, so variation 400 was explicitly specified. Current results:
- Noto Sans KR400, the Hangul text Jeongmin, ink-height 15mm, original font SHA
  `194018e6b2b293a7964f037b25c0249ce1418bc9ab3c971060a03aa57861e252`
- Upstream Kotlin thinning→centerline **9 paths**. Inputs/integer paths/PNGs:
  `/tmp/myembro-sewtest/krs/input.{json,pgm,png}`, `KRSAT1-centerline.{json,png}`.
- Wired to call existing convert-to-satin→SatinColumn→S1→default writer once explicit width is supplied.
  Writes PES only if the writer passes checks for one color of 40wt thread, 60×60mm bounds, origin, 0-length moves, and headers, as well as P1–P8.
  Does not overwrite existing filenames.
- **No PES generated yet**: awaiting width input; no claim of `.PES`/decoded-PES `.png`/quality PASS.
  Default trace mode explicitly generates only a centerline PNG.

`./gradlew check :engine:hangulSatinSample`: BUILD SUCCESSFUL in 44s.
Reproduction commands are recorded in `tools/hangul_satin/README.md`. After a width reply,
running with `-PstrokeWidthMm=<지정값>` proceeds to generation of the requested `/tmp/myembro-sewtest/krs/KRSAT1.PES`
and actual-byte inspection/PNG stages.

## Letter handoff unit 3 — KRSAT1 generation using upstream fit

The coordinator confirmed **1.13mm** as this trial's constant stroke width. Basis: model
GlyphStrokeWidths' measured median for Noto Sans KR400 at 15mm (Jeong 1.126, Min 1.125mm);
not extended into automatic selection/general font-width estimation rules.

The trial used **unmodified upstream AutoTrace C fit**. `tools/autotrace/fit_oracle.c` only calls
thin-image→pxl-outline→`fit.c:fitted_splines`; no new smoothing algorithm was written.
Only centerline=TRUE was set on `new_fitting_opts` defaults. preserve_width remains default FALSE
(satin width is a separately confirmed constant). Helper C files for curve/spline/vector/epsilon-equal are
also original text from `1a232b27818a46a05cd25edec09e2f77c4e9ac01`.
**Kotlin fit port/app integration are not yet complete**; C execution is exclusively for this local trial/
future comparison-golden generation. Ink/Stitch Python was not run, and no dependencies were installed.

Upstream fit result: **9 paths/21 segments/14 curves of degree 3**, byte-exact on rerun.
Input, each C file/caller/executable, output SHA, and compiler options are recorded in
`/tmp/myembro-sewtest/krs/fit-provenance.json`.
Kotlin reads original line/cubic controls and applies `output-svg.c`'s float y=height-y and
6-digit `%g` formatting, then flattens with existing SvgPath.flatten font-parser precision 0.1 SVG px.
This is curve subdivision, not custom smoothing. Original fit input/output hashes are checked;
if fit.json is missing, fallback generation of PES from pixel-staircase paths is prohibited.

**Artifacts** `/tmp/myembro-sewtest/krs/KRSAT1.PES` (new name,2,298bytes),
decoded-PES PNG `KRSAT1.png`, upstream fit centerline `KRSAT1-centerline.png`,
inspection output `KRSAT1-check.txt`, gate JSON/text `KRSAT1-gate.{json,txt}`.
Font input ink-height 15mm and actual needle bounds **26.8×14.4mm** are reported separately.
627 stitches/8 trims/one color of black 40wt; no automatic satin/fill branching added.

| Actual-byte check | Result |
|---|---|
| P1/P2 origin/integer bounds | min(0,0), max(268,144), within HoopSpec 100×100mm/trial 60×60mm |
| P3 header |268×144, exactly matches actual bounds |
| P4 0-length needle/JUMP |0 |
| P5 consecutive same-point needles |Maximum 1 |
| P6 missing locks at start/end/before and after trims |All 0, S1 half_stitch |
| P7 0.5mm cell density |Maximum 6 |
| P8 maximum needle length |2.4186773245mm |

`quality-gate check --text --style satin` tool v7: **PASS1/1**; code/thresholds were
not changed. This automated code verdict is separate from user physical-device approval, which remains pending.
PES SHA256: `c26ff1bb5fdbee8c46397c2fcaba1800aa27da3b98b9b0d49d13a340fd202e79`.
Full `./gradlew check` passed (last run 13s). For the upstream fit generator/reproduction commands,
see `tools/hangul_satin/README.md`. Do not overwrite KRSAT1 by regenerating it;
use a new stem for the next physical-test file.

## Letter handoff unit 4 — AutoTrace fit Kotlin port

`AutoTraceFit.kt` ports the centerline default path of `fit.c` from the same pinned AutoTrace 0.31.10.
Covers `split_at_corners/find_corners/find_vectors/remove_adjacent_corners`,
`remove_knee_points`, `filter`, `fit_curve/fit_with_line/fit_with_least_squares`,
`set_initial_parameter_values`, `find_tangent/find_half_tangent`, `fit_one_spline`,
`find_error`, `spline_linear_enough/change_bad_lines`, float vector operations in `vector.c`,
and `spline.c:evaluate_spline`. Upstream commit and GPL2+FSF notices are in the file header;
full upstream license text remains in existing engine resources.

Defaults: corner_surround 4, corner_threshold 100°, corner_always_threshold 60°,
filter_iterations 4, tangent_surround 3, error_threshold 2px, line_threshold 1,
line_reversion_threshold 0.01, remove_adjacent_corners=false,
centerline=true, preserve_width=false. Preserves upstream `gfloat` operation order/rounding, corner-end handling,
filter-collapse cancellation and previous-point state, open-curve link handling, unnormalized average tangents,
and shared tangents during recursive splitting. No custom curve smoothing or width estimation.

**Goldens:** 5 existing synthetic inputs: straight/T/X/ring/the Hangul syllable han in OFL Noto × 1/3 channels, 10 cases.
`tools/autotrace/generate-fit-goldens.py` calls upstream C unchanged to generate
[fit resources](../engine/src/test/resources/autotrace/fit/README.md).
Compares path counts/open-closed status/direction/segment order/degree/all control-point xyz as exact float bits.
`AutoTraceFitTest`: **upstream goldens 10/10 + 1 empty-input/invalid-coordinate check passed**.
C-golden reruns also 10/10 byte-exact. The local Hangul text Jeongmin at 15mm was not added as a test fixture;
separate comparison uses `/tmp/myembro-sewtest/krs/input.pgm`: **9 paths/21 segments/84 control points,
exact float bits** (`/tmp/myembro-port-out/letter/autotrace/fit/local-fit.log`).

The trial CLI now passes **only Kotlin fit results** to convert-to-satin.
If optional C fit.json exists, it is used only for hash/provenance validation and bitwise checks of all control points;
without it, the same Kotlin fit is used. No pixel-staircase-path PES fallback.
Generated `KRFIT1-centerline.png/json` locally and did not change the delivered KRSAT1.PES.
Separate from app UI integration/automatic satin-fill selection/user sew-out approval.

Scope/differences from upstream:
- Supports only constant-width centerline fit. preserve_width distance-map/z-width fitting, outline fitting,
  arbitrary fit settings/progress-cancellation callbacks/logging are outside this API.
- GLib allocations/links replaced with JVM objects/Lists. Abnormal empty paths/negative or out-of-ushort-range pixels
  are input errors; degenerate tangents that would loop forever/fail splitting upstream terminate with explicit errors.
- Exact matches for 10 upstream cases and a separate local case measure only those inputs; they do not establish
  all-input equivalence or official-oracle equivalence for line→satin.

Validation output: `/tmp/myembro-port-out/letter/autotrace/fit/verify.log`, `local-fit.log`,
`check.log`. `./gradlew check`: **BUILD SUCCESSFUL in 38s**, engine 650/app Debug 205/
Release 205 tests, 0 failures, Lint passed. This unit ends with fit replacement/comparison; no additional PES
physical-test file was generated. The delivered KRSAT1 SHA256 also matches the previous record.
