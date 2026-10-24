# Porting and verifying stitch algorithms

## Original source and license

- Original: [Ink/Stitch](https://github.com/inkstitch/inkstitch), pinned commit
  `b0edd96311ece82ee48816dc93466274b12c2a9c` (stable tag v3.3.0).
- The full GPL v3 text in `LICENSE` and the **GNU GPL version 3.0 or later** notices in
  `lib/stitches/running_stitch.py` and `lib/stitch_plan/stitch_plan.py` were checked directly.
  A copy of the full license is in `engine/src/main/resources/licenses/inkstitch/LICENSE`.
  Original Authors/copyright notices, files, functions, and commit references are also retained
  in comments in each ported file.
- The original repository was **read only** under `/tmp`. The original Python was not executed,
  dependencies were not installed, Inkscape/Ink/Stitch was not run, and no goldens were generated
  with the original.
- Kotlin uses the existing JTS dependency in place of Shapely. No NetworkX runtime is included.
  Neither the entire original Python package nor user PES files are bundled.

## Scope and traceability table

The authoritative satin contract, status, and differences are in [inkstitch-port.md](inkstitch-port.md).
This document records the existing app safety boundaries and deterministic auto-fill draft. It does
not claim all original-project options or byte-for-byte output equivalence. Arbitrary randomness/
jitter, gradient/gap fill, a user SVG-lock editor, and the original explicit start/end-point UI are
not exposed by this API.

| Kotlin | Original file at pinned commit | Target functions/classes |
|---|---|---|
| `inkstitch/stitches/InkRunning.kt` | `lib/stitches/running_stitch.py` | `AngleInterval`, `cut_segment_with_angle`, `cut_segment_with_circle`, `take_stitch`, `path_to_curves`, `stitch_curve_evenly`, `even_running_stitch`, `bean_stitch`, `split_segment_even_n/dist` |
| `inkstitch/elements/SatinColumn.kt` | `lib/elements/satin_column.py`, `lib/utils/geometry.py` | See inkstitch-port.md for the full satin traceability table |
| `InkAutoFill.kt` | `lib/stitches/fill.py` | `intersect_region_with_grating`, `row_num`, `adjust_stagger`, `stitch_row` |
| `InkAutoFill.kt` | `lib/stitches/tatami_fill.py` | `build_fill_stitch_graph`, `tag_nodes_with_outline_and_projection`, `add_edges_between_outline_nodes`, `graph_make_valid`, `find_stitch_path`, `pick_edge`, `collapse_sequential_outline_edges` |
| `InkAutoFill.kt` | `lib/stitches/tatami_fill.py` | `build_travel_edges`, `process_travel_edges`, `travel`, `path_to_stitches` |
| `OutlineFill.kt` | `lib/elements/fill_stitch.py` | `fill_underlay_angle` / `fill_underlay_row_spacing` (crossed at 90°, 3 times the top-stitch spacing) |
| `LockStitches.kt` (S1 worker) | `lib/stitch_plan/lock_stitch.py` | `RelativeLock`, `AbsoluteLock`, `SVGLock`, `LOCK_DEFAULTS` |
| `StitchPlanBuilder.kt`, `GeneratedPatternProcessor.kt` (S1 worker) | `lib/stitch_plan/stitch_plan.py`, `color_block.py` | `stitch_groups_to_stitch_plan`, `filter_duplicate_stitches`, existing 0.1mm pattern adapter |

Base path for original-source links:
`https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/`

## Explicit app policies and differences

- **Running is the revised S2 default for outlines.** Auto-fill is for explicit legacy requests/
  subsequent S4. The previous TTF raster-centerline-estimation satin was removed from the
  generation path. `OutlineColumns` is a past experimental API and is not used to generate satin.
- Requesting SATIN for TTF switches to auto-fill with a warning, including rectangles.
  Only separately verified explicit rails/rungs are passed to the satin core.
- Explicit two-rail/rung satin aligns rail direction using a 10-point comparison, as in the original,
  and interpolates both intervals together between rungs. Spacing is corrected at most twice,
  based on the longer side in the normal direction of the previous crossing stitch. The original
  sawtooth short-stitch default inset is 15% of the width. The default satin proximity threshold
  is the same 0.25mm as the original.
- Pull compensation is applied in mm to both satin rails. Fill compensation retains the existing
  app policy of buffering the outer boundary while preserving original holes. It is not claimed
  to match the original grating-reconstruction-based fill compensation. Center-walk/contour/zigzag
  underlay is supported through the API.
- Auto-fill uses an origin-aligned grating, 4-stage staggering, outer/hole boundary graphs, and
  reverse Hierholzer routing. Internal travel uses +45°/−45°/90° grids, 3-fold boundary weights,
  and removal of internal paths crossing already-filled rows. JTS nodes intersections. The original
  internal-travel smoothing is not used; paths whose segments actually lie inside the region are retained.
- The **minimum-edge-count matching of odd vertices** solved by `networkx.eulerize` in
  `graph_make_valid` is solved with exact subset DP, not a substitute greedy matching. It fails
  beyond 18 odd vertices, 100,000 nodes, 300,000 edges, or 100,000 grating rows. This is not a
  port of the original general Blossom implementation or of unlimited problem sizes. The objective
  was checked against the [official NetworkX eulerize explanation/source](https://networkx.org/documentation/stable/_modules/networkx/algorithms/euler.html#eulerize).
- **The temporary `InkLock`/`StitchPlanFinisher` from b798af7 were removed.** Updated commit
  `d4c6776` from S1 `384b259` was brought in as a dependency, using `GeneratedPatternProcessor`.
  There is no separate compact 0.4mm rule, blanket 0.3mm enforcement for body/locks, or 8-per-cell
  generation exception.
- Explicit satin handles default half_stitch, group minimum length/minimum jump, and trim/stop
  through source `StitchGroup` → `fromGroups`. Global minimum length is 0.1mm. S0 and short
  source locks are not removed or replaced to match PE-Design byte sequences.
- Existing app running/bean/fill uses S1's native `finish` adapter. It preserves already-sampled
  corners and creates half-stitches along the actual path. This may differ from the SVG source
  lock's vector fallback (a difference owned by S1). Arbitrary JUMPs also remain existing generation boundaries.
- The satin engine API stores 41 source names/defaults in `SatinParameters` (detailed UI canceled).
  Legacy `SatinOptions` in existing projects explicitly maps saved values such as max 7mm.
  New selections use source defaults (no max). Conversion is mm→px once in the core and
  px→0.1mm once in the common adapter. Split lengths use the source float path; after
  quantization, error of up to approximately 0.15mm is possible.
- Arbitrary rotation/scaling removes only locks recorded by S1 and replans them. Integer-grid-
  preserving transforms also transform provenance. **Imported PES and the general PES writer are unchanged.**

## Density/start observations and shared criteria with embro-model

Actual decisions belong to [sewout-check.md](sewout-check.md) and embro-codex's fixed gate.
`PatternStartAnalysis.starts` only reads the cell peak, maximum same-coordinate count, and count
below 0.3mm for the first 20 STITCH commands at the start/after trim·color change·STOP. It neither
deletes output needles nor blocks generation. S1's valid locks must be distinguished from the body;
S0/0.28mm fixed sequences must not be required.

0.5mm cells are compared across four phases with x/y each shifted by 0·0.25mm. The user's bad
file had 38 penetrations in a cell but a consecutive maximum of 3, so accumulation must be checked.
The current independent gate uses 15, the rounded-up p99 of per-file maxima in the normal corpus.
The previous 8-per-cell/consecutive6 generation block is a discarded experimental criterion.
Short-stitch counts report both raw and lock-excluded values. General PES has no object IDs, so
a generator that knows object-start sections declares `object_start_runs` in the gate matrix.

## Verification approach

Under the constraint of not executing the original, verification uses **handwritten/analytic goldens**
ported from reading original formulas and fixed definitions, independent geometric invariants, full
regression, and PES v1/v6 round trips. It does not claim completed automatic differential comparison
with Ink/Stitch execution output or actual SE400 sewing verification.

- Running straight/curve error, 45° corners, maximum length, and bean forward–back–forward coordinates.
- Fixed lock-shape coordinates, ordering before/after trim, minimum stitches, and lock lengths after placement/scaling.
- Reversed rails, explicit rungs, per-rail rung alignment as in the original, satin density, pull compensation, and three underlay types.
- Auto-fill holes/disconnected regions, multiple angles, fill rows, path geometry, and determinism across repeated generation.
- Dancing Script Bold (OFL, pinned SHA-256) `Jung Min` 34mm, 40wt, and Hangul/English 10–30mm.
- Personal files, extracted coordinates, renderings, and audit dumps remain only in `/tmp/myembro-birdnest`.
  Personal PES/PNG or test fixtures derived from personal files are not added to the repository.
