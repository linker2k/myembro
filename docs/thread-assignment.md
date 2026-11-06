# Design-wide thread assignment

User decision, 2026-11-01: assign the complete source palette before generating
stitches. Different source RGBs must use different available threads, unless the
source palette exceeds the candidate palette. This changes color selection, not
geometry, stitching parameters, order, hoop limits, or imported PES bytes.

## API and ordering

`ThreadAssignment.assign(List<DesignColor>, MyThreads, fallback)` is JVM-only.
It combines identical 24-bit RGBs and sums their painted areas, sorts by CIE L*
descending and then area descending, and chooses the nearest unused candidate.
Stable source/candidate order breaks any remaining ties. Distance is CIEDE2000,
with kL=kC=kH=1. Candidate selection calls the existing `MyThreads.candidates()`;
no owned-thread restriction is bypassed. Identical candidate RGBs count as one
available thread. After all candidates have been used, remaining colors select
the nearest candidate again. The structured `Shortage` warning reports source,
available, and reused counts; generated `EmbPattern.warnings` also carries its
`THREAD_CANDIDATES_EXHAUSTED` message. Empty input produces no warning.

`VectorArt.assignThreads` exposes allocation without stitch generation. SVG
areas use the existing transformed JTS fill/stroke geometry, aggregated by RGB
(before overlap removal); source-coordinate square units suffice because every
shape shares the output scale. Rendering reuses the same cached geometries.
`ColorDrawingPipeline.trace` measures foreground pixels after background/small
region cleanup, multiplied by pixel area. Both paths call the same allocator.
Grayscale retains its existing neutral fallback palette; active owned threads
still take precedence. Legacy single-color `nearestThread`/candidate suggestion
APIs retain red-mean behavior; picture/SVG rendering uses batch assignment.

SVG and emoji screens pass the current MyThreads snapshot into `Art`.
ShapeObject defensively retains it and the additive optional `myThreads` JSON
field preserves it across block edits and project reloads. Older projects that
lack the field use the existing default unrestricted chart. Photo regions keep
the threads chosen by the photo pipeline. Imported PES and the frozen bundled
logo PES are not recolored or rewritten.

## Measured choice of distance

Local corpus: 127 SVGs, 464 distinct (design, RGB) entries, 405 globally unique
RGBs; each source color has equal weight in these error statistics. White SVG
foreground is included. All reported errors are CIEDE2000 against the original
source RGB. Palette: existing Brother PEC chart, without its null entry.

| Independent nearest method | Mean ΔE00 | Maximum ΔE00 | ΔE00 >20 | Merged pairs / designs |
|---|---:|---:|---:|---:|
| red-mean | 7.061729 | 33.260313 | 14 | 4 / 4 |
| Lab ΔE76 | 6.431952 | 23.667646 | 2 | 5 / 5 |
| CIEDE2000 | 5.748982 | 18.931115 | 0 | 7 / 7 |

CIEDE2000 improves both mean and maximum, satisfying the user's selection rule.
Independent nearest matching still merges colors; the batch uniqueness rule fixes
that separately. Allocation through the actual Kotlin API on all 127 SVGs:

| Policy | Mean ΔE00 | Maximum ΔE00 | ΔE00 >20 | Merged pairs |
|---|---:|---:|---:|---:|
| Previous independent red-mean | 7.061729 | 33.260313 | 14 | 4 |
| Unique greedy CIEDE2000 | 5.858085 | 18.931115 | 0 | 0 |

No candidate shortage occurred. Kotlin/Python maximum absolute ΔE00 discrepancy
was 1.71e-13. Local artifacts: `/tmp/myembro-thread-match/measure.py`,
`AssignmentCheck.java`, `compare_assignment.py`, `colors.csv`, `assigned.csv`,
`assignment-summary.json`, `swatches-10.png`. These measurements do not change
sew-out thresholds or claim physical thread color accuracy.

## Mathematical source and tests

The sRGB transfer function, RGB→XYZ matrix, D65/2° reference white, Lab branch
constants, and CIEDE2000 formula are adapted from scikit-image 0.25.2
[`colorconv.py`](https://github.com/scikit-image/scikit-image/blob/v0.25.2/skimage/color/colorconv.py)
and [`delta_e.py`](https://github.com/scikit-image/scikit-image/blob/v0.25.2/skimage/color/delta_e.py),
BSD-3-Clause; see THIRD_PARTY_NOTICES. No fitted constants or runtime dependency
are introduced. The CIEDE2000 source cites Sharma, Wu and Dalal's implementation
notes and supplementary test data.

`ThreadAssignmentTest` compares 256 synthetic RGB pairs with pinned numerical
outputs from scikit-image 0.25.2 (numpy default_rng seed 0), the published Sharma
2.0425 pair, identity, symmetry, uniqueness, priority, shortage, owned candidates,
SVG/photo integration, and ShapeObject JSON backward compatibility.
Run `./gradlew :engine:test --tests '*ThreadAssignmentTest'` or the complete
`./gradlew test lint`. Existing color-ID/PES-hash goldens are updated for the
approved color change; stitch coordinates and safety assertions stay enforced.
