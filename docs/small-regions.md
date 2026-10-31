# Preserve small picture regions

User-approved policy, 2026-11-01. This changes omission policy, not the stitch generators or safety thresholds. It applies to newly generated color-picture and SVG/vector output; existing imported PES bytes are not reinterpreted.

## Basis and exact rules

- Keep paint that previously disappeared in color-label area merging, Potrace area filtering, or vector post-occlusion sliver removal.
- Measure maximum inscribed width with the existing JTS-based `StitchTypeRule` geometry. At **1.0mm or wider**, reuse the existing fill/satin choice (a formerly omitted RUNNING-classified piece becomes existing tatami). Below 1.0mm, use the existing Ink/Stitch running outline. Existing running length/tolerance, fill/satin settings, underlay, half-stitch locks and finishing defaults stay unchanged.
- The exact 1.0mm boundary is the user's selected rule and the existing Ink/Stitch `min_satin_stroke_width` default, not a universal industry guarantee. [Embrilliance's tapering guide](https://www.embrilliance.com/Help/Platform%20Mac%201161/satincolumntaperinglevel2.htm) warns that very narrow satin can stress fabric and recommends more travel where possible. [Impressions' digitizing guide](https://impressionsmagazine.com/process-technique/custom-machine-embroidery-digitizing-101-how-to/167083/) describes running stitches for fine details and lines below 0.8mm. Those sources support the direction; they do not independently establish this app's exact 1.0mm cutoff.
- Omit only a run that cannot represent paint with 2 finished non-lock needles at distinct 0.1mm positions, or fails P4/P5. Lock needles alone cannot masquerade as the drawing. Count these in `myembro.tiny_regions_omitted` and the result's exclusion count. Other blockers, including density and hoop violations, are never hidden by deleting representable paint: final standard export checks can reject it.

## Implementation and compatibility

`SmallRegionStitches` is shared policy wiring around existing `Stroke`, `GeneratedPatternProcessor` and `PesWriter.writeChecked`; it implements no new tracing/fill/running algorithm.

`ColorDrawingPipeline` retains the original quantized labels instead of merging small components. Its layer tracing opts into the existing upstream Potrace `turdsize=0` parameter. The original Redwork/Silhouette overload and default Potrace behavior remain unchanged; trailing checkpoint lambdas retain their original overload. The legacy area argument now identifies substantial contours for the existing complexity budget, rather than removing paint. Small added contours do not consume that old fill-complexity budget; substantial contours still face its original limit. No thread-palette/nearest-thread/owned-thread/grayscale assignment code changed. User-selected image preprocessing and quantization remain upstream of this preservation policy.

Color-picture integer rounding uses the original crop's top-left grid phase while retaining a centered object-local frame. Independently rounding both sides of a 99.9mm centered crop could otherwise turn its preserved edge into a 100.0mm span. The regression now produces source/placed bounds `(-500,-500)..(499,499)` and equal result/editor PES. No extra margin, post-generation shrink, lock modification or tolerance was introduced.

New vector paint uses `small-region-*` IDs, leaving existing `region-*` IDs untouched. Old saved orders append only newly preserved IDs and keep old block edits bound to their original paint. Old cached photo payloads keep their native fragments. Tests cover saved order/angle edits, repeated JSON round-trips and owner-capture/PES byte parity.

New SVG logo generation retains three formerly dropped clamp-body running regions. The already shipped PES is immutable stored stitch data: its original SHA and safety are checked separately rather than silently regenerating existing library/chat fixtures. The affected SVG-heart agent and synthetic pipeline snapshots were updated from code measurements; success, rejection, rollback and safety expectations did not change.

## Automated verification

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ANDROID_HOME=/home/gosh2/Android/Sdk ./gradlew test lint --max-workers=2
```

Final command result: `BUILD SUCCESSFUL in 2m 59s`. XML totals: engine 1,038, app debug/release 969, MCP 10; 2,017 tests, 0 failures/errors/skips. Android lint: 0 errors/fatals, 177 warnings. Full output: `/tmp/myembro-small-regions/full-check.log`.

New regression coverage includes the 1.0mm boundary, sub-millimeter SVG eyes, one-pixel photo paint under a legacy 3mm² threshold, substantial-contour budget handling, counted unrepresentable runs, density blockers not causing silent deletion, old project IDs/order/edits, and byte-identical ownership capture. Existing generator/oracle tests remain enabled.

## Offline 136-design measurement

Inputs remain external in `/tmp/myembro-cute-art/`; no personal images or generated design fixtures are committed. Selected sizes are held fixed at `/tmp/myembro-sample-pack/selected-fit-mm.json`. The before/after harness, frozen classpath, tables, flat PNGs and CORE comparison are in `/tmp/myembro-small-regions/`. Final engine CORE replay asserts all 6 PES byte-identical to the measured snapshot, proving the ID/codec compatibility additions do not change their needles.

| Metric | Before | After |
|---|---:|---:|
| Measured designs / produced PES | 136 | 136 |
| Designs with sewout blockers | 0 | 0 |
| Designs inside selected fit box (+1mm) | 136 | 136 |
| Missing regions, all 136 | 938 | 296 |
| Missing regions, original 39-design cohort | 790 | 270 |
| Stitches, total | 1,342,926 | 1,428,827 |
| Weighted stitch increase | — | 6.40% |

The requested baseline 790 belongs to the original 39-design cohort. The later 9 recovered long-running designs added 148 previously unmeasured omissions; all 136 therefore start at 938. Both totals are reported rather than changing the denominator. Remaining omissions are the new counted extreme-run exceptions, not area/sliver filtering. The largest per-design increase is `F15-floral-sprig`, 2,761 → 7,021 stitches (+154.29%). The complete per-design table includes these increases; no increase threshold is used as a safety bypass.

| CORE design | Before missing | After tiny omissions | Restoration assessment |
|---|---:|---:|---|
| 07 sitting cat | 13 | 1 | Partial numeric recovery; full facial-detail recovery unconfirmed |
| 14 rabbit | 22 | 7 | Partial numeric recovery; full facial-detail recovery unconfirmed |
| 18 owl | 18 | 2 | Partial numeric recovery; full facial-detail recovery unconfirmed |
| 20 baby dinosaur | 20 | 2 | Partial numeric recovery; full facial-detail recovery unconfirmed |
| c28 cosmic whale | 7 | 2 | Belly lines visible again in flat comparison; tiny details remain |
| c20 surreal fish balloon | 9 | 0 | Tether visible again; no counted omitted region |

`core-before-after.png` retains identical per-row display scale. The last column is visual review, not an automated claim of a successful real sewout. Per-design before/after counts, actual dimensions, blockers and stitch increases are code-measured in `comparison.{json,csv,md}`. The final write/check boundary is unchanged and uses the selected hoop, never an implicit SE400 for geometry generation.
