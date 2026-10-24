# Personal drawings → designs: first engine trial (2026-10-24)

Current status: **connected to color mode on the photo/drawing screen**. Verification, screenshots,
and reproduction: [Color drawing QA](qa/color-drawing/README.md). Below is the record of the initial
trial starting from main `98ac4ac` and subsequent changes.
First 15-minute unit: connect existing ports, create 2 original PNGs, and provide executable measurements/gates.
Personal drawings/emoticons are processed only on the user's device. Personal inputs/results are
neither sent to a server nor bundled with the app/tests. Both inputs below are original synthetic
drawings drawn by the script itself, without external characters or photographs.

## Reuse and contracts

| Stage | Existing code used / basis |
|---|---|
| N-color separation | `ImageQuantizer.quantize`, Wu 1991 Graphics Gems II `gemsii/quantizer.c@ca4898517a93b6b45444a9cda5fd50132694b864`. Retains 3 existing original-C goldens. Supports a 2–6-color argument. |
| Per-color contours | `RedworkPipeline.trace`: existing Potrace tracing, polygon approximation, curve fitting, and optimization. No new algorithm was created here. Because it receives per-color binary masks, the internal Wu 2-color call is redundant work and a candidate for later refactoring. |
| Regions/holes | Existing `PolygonRegion.fromPaths(..., EVEN_ODD)` and JTS connected components. Coordinates retain the image coordinate system shared by all colors. |
| Width measurement / experimental branching | Existing boundary-Voronoi median in `StrokeWidthMeasurement`, `TatamiLettering.methodForWidth`: <0.9mm running, 0.9–6mm satin, >6mm fill. **Reuses the integrator's existing Hangul auto-rules values; it is not claimed to be a verified branching rule for image regions.** |
| Wide areas | Ink/Stitch `DefaultTatami`, including default underlay. |
| Narrow strokes / lines | Existing AutoTrace thinning→centerline→fit → `StrokeToSatin` + `AutoSatin` / `Stroke` default running. Existing port of AutoTrace 0.31.10 `1a232b27818a46a05cd25edec09e2f77c4e9ac01`. Satin uses constant-width conversion. |
| Thread colors | Minimum existing `PecThreads.distance` in Brother's 64-color `PecThreads.SET`; ties follow table order. Alpha is excluded from color distance. |
| Finishing / files | Process all per-color groups together through existing S1 `GeneratedPatternProcessor`, trims between regions, thread-boundary locks, removal of 0-length motion → `PesWriter` V6 → `PecViewportCheck` + `SewoutCheck` with app-generated provenance. |

Ink/Stitch calls only existing, separately attributed ports of the pinned v3.3.0 source
`b0edd96311ece82ee48816dc93466274b12c2a9c`. AutoTrace/Potrace/Wu licenses and source attribution
remain in the existing `THIRD_PARTY_NOTICES.md`, `docs/inkstitch-port.md`, and port headers; there
are no new dependencies. This trial does not execute the original Ink/Stitch.

Trial inputs are 128×128 RGBA PNGs on a 30mm overall canvas, requesting 4 colors. The originals
actually have 3 colors, so the original Wu behavior finishes at 3 colors. Transparent pixels are
excluded from quantization and consume no thread-color budget. This trial allows only fully opaque/
fully transparent pixels and explicitly rejects partial alpha. It requires transparent margins around
the image and does not infer a background color. The trial tool limits input dimensions to 512px
or less. App decoding/downscaling/partial-alpha compositing contracts are not yet implemented.

## Execution and code-based decisions

```sh
python3 tools/image-to-design/generate-inputs.py
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
./gradlew :engine:test --tests '*ImageToDesignProbeTest' --tests '*ImageQuantizerOracleTest' :engine:imageToDesignProbe
# 선택: -PimageProbeColors=2 .. 6, -PimageProbeInput=... -PimageProbeOutput=...
/tmp/myembro-quality-gate/quality-gate check \
  /tmp/myembro-port-out/editor/image-to-design/face.pes \
  /tmp/myembro-port-out/editor/image-to-design/robot.pes \
  --style mixed --json /tmp/myembro-port-out/editor/image-to-design/quality-gate.json
```

Measurement: a single run on this PC JVM, face first/robot second. Warmup and environment effects
mean these are not performance guarantees. `total_ms` covers PNG decoding through the PES gate,
including S1, encoding, and checks in addition to the detailed stages.

| Synthetic PNG | Colors / regions | Stitches | Quantization+decode ms | Contours ms | Width measurement ms | Stitch groups ms | Total ms | Engine / external gate |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| face.png | 3 / 4 | 1,050 | 63.92 | 89.64 | 140.97 | 591.95 | 988.68 | PASS / PASS |
| robot.png | 3 / 5 | 600 | 7.06 | 21.58 | 52.10 | 98.90 | 204.67 | PASS / PASS |

Actual output:

```text
BUILD SUCCESSFUL in 5s
PASS .../face.pes within five-condition checks
PASS .../robot.pes within five-condition checks
PASS 2/2 cases passed
```

P1–P3 check the encoded PEC origin/hoop/header with `PecViewportCheck`. The rest check the actual
PES through `SewoutCheck` with `APP_GENERATED`. Fixed tests assert no blockers, valid ranges,
3 colors, and the presence of running/satin/tatami paths in both drawings. Separate tests check
2–6-color budgets, indices, and repeated determinism. Existing Wu original-equivalence tests were
also run. Artifacts/detailed JSON are in `/tmp/myembro-port-out/editor/image-to-design/`; logs
are `/tmp/image-to-design-{test,gate}.log`. Passing the gate is not shape-preservation verification
or actual-sewing approval.

## Discovered limitations — resolve before product use

**The robot's wide body is incorrectly classified as satin.** The Voronoi median width of the body
with eye holes is 4.121mm, within the existing 0.9–6mm branch. Constant-width centerline satin
therefore fails to reproduce the original broad body area. The face area measures 6.177mm, so
tatami was selected. The failure case is explicitly recorded in tests to prevent assuming that a
passing PES is a correct drawing. No arbitrary area/elongation thresholds are invented to correct it.

Centerline intersections/branches, variable width, disappearance of small regions (Potrace default
turdsize=2), stitch overlap at neighboring boundaries, and duplicate same-color sequencing after
thread matching also remain unverified by these two synthetic drawings alone. Unconvertible
centerlines/satin groups fail the trial rather than being silently discarded. No new color/region
routing optimization was introduced; only existing per-type routing and trims are used.

## Proposed next units and decisions

1. **20 minutes:** numerically compare area preservation in these two inputs, including broad regions with holes, and investigate verified region→satin applicability conditions in reusable original Ink/Stitch/AutoTrace code. Automatic satin selection for images is not yet approved for the default path.
2. **20 minutes:** separate an engine API for the integrator-defined supported inputs/automatic selection range, and freeze transparency/partial-alpha/small-region handling contracts. Add no thresholds without evidence.
3. **20 minutes:** lock representative cases ≤10, per-color original-shape/omission checks, and actual PES gates into tests before deciding app integration. UI belongs to a later unit.

Decision needed: restrict the first product path to verified tatami-focused output, or keep all image
automatic satin experimental until shape-preservation conditions are confirmed. This commit is
test-only and does not affect existing silhouette/outline or app output.

Full engine automated-test result: `./gradlew :engine:test` → `BUILD SUCCESSFUL in 17s`;
tests=714, failures=0, errors=0.

## Follow-up unit: unify the shared model/canvas path

The shared API was not yet on origin/main `98ac4ac`, so `GlyphStrokeWidths.kt`, tests, and
documentation were brought over unchanged from model `4126735161bea2cde2168fb2c9273a527aa0b5a6`.
The canvas logo path was also not yet on main, so only engine `VectorArt.kt` was brought over
from `652d3cafd6d61133f5104538034e29030a236bc5`. App/bundled PES files were neither imported nor
changed. During integration, do not treat files overlapping those source branches as separate
implementations; retain only the following shared-function changes.

- `VectorArt.medianWidthMm(geometryMm)` calls model `GlyphStrokeWidths.measure`. Both image regions and SVG filled areas measure the median in mm through this function. The previous canvas maximum-inscribed-circle calculation was removed.
- Only `VectorArt.classifyWidth(widthMm, rules)` performs width-threshold comparison for PNG/logos. <0.9mm running, 0.9–6mm satin (both endpoints included), >6mm tatami. Default boundary constants come from existing `TatamiLettering`. Explicit constant-width SVG strokes pass their physical width to this function.
- SVG fill sews as an area, as in the original canvas path (tatami when wider than running). Satin is used for explicit SVG strokes. PNG regions still lack this SVG area/stroke semantics, so they branch only on the median. Therefore, **function unification and automatic-selection correctness are separate issues**.
- Regression tests check measurements, boundaries, logo-stroke rendering, and the actual launcher-logo PES. Immediately below 0.9mm/exactly 0.9/exactly 6/immediately above 6mm are also decided by code.

Remeasuring the same inputs through the shared API:

| Input | Regions | Stitches | Previous total ms | Shared API total ms | Shared median and selection | Engine/external gate |
|---|---:|---:|---:|---:|---|---|
| face.png | 4 | 1,050 | 988.68 | 1,018.17 | Yellow area 6.177mm → tatami | PASS/PASS |
| robot.png | 5 | 600 | 204.67 | 208.32 | Blue body 4.121mm → satin | PASS/PASS |

Measurement log: `/tmp/image-to-design-shared.log`; result JSON: `results.json` in the existing
output directory; external-gate JSON: `quality-gate-shared.json`. These are differences between
single runs, not a claim of improved/degraded speed.

**The misclassification remains unfixed.** `GlyphStrokeWidths` internally calls the same
`StrokeWidthMeasurement` directly used in the previous trial, so both produce exactly
4.121185770351488mm for the broad body with holes. Measurements confirmed that changing the
API alone does not switch it to tatami. The number was not arbitrarily raised above 6mm, nor
were new heuristics such as maximum inscribed circles or area mixed in. The integrator was asked
to decide between keeping areas as tatami, like SVG fill, or retaining the specified median-based
branching and its unresolved limitation.

Final verification: `./gradlew :engine:test` → `BUILD SUCCESSFUL in 18s`; tests=722, failures=0,
errors=0. The external-gate command is the same as above, changing only `--json` output to
`quality-gate-shared.json`, and returned `PASS 2/2 cases passed`. The actual logo was rendered at
60mm through the same classification path and passed PEC origin/hoop/header and app-generated
sew-out checks. This verification does not pass the unresolved robot-area-preservation problem.


## Final application: add the maximum-inscribed-circle condition (supersedes the previous pending decision)

Integrator decision 2026-10-24: prioritize tatami when maximum width >6.0mm. Satin is selected
only when maximum width ≤6.0mm and median ≥0.9mm; otherwise use running. Maximum width is
the diameter of JTS `MaximumInscribedCircle`. The 6mm basis is the corpus satin-crossing p95
6.0mm supplied by the integrator; the 0.9mm lower bound is integrator-specified policy.

**No duplicate implementation:** use `StitchTypeRule.kt`, `VectorArt.kt`, and `StitchTypeRuleTest.kt`
unchanged from `claude/stitch-rule` commit `1547f5c14c5c49334fb5551955abeee6e6762072`.
The editor PNG trial calls `StitchTypeRule.widths(region, 10.0)` and `classify(widths)`. Neither
logos nor PNGs calculate maximum/median values separately or compare thresholds independently.
The previous `VectorArt.classifyWidth`/`medianWidthMm` wrappers were removed.

Unlike the previous model Voronoi sample median, the shared branch's median is an **area-weighted
local-thickness sample median**. Thus the earlier table's 4.121mm and the 5.611mm below must not
be compared as values with the same definition. The editor did not separately change this definition
or JTS search precision. Byte equality with the original shared files was checked.
`GlyphStrokeWidths` itself was not modified.

| Target | Size basis | Shared median mm | Shared maximum width mm | Selection |
|---|---|---:|---:|---|
| Blue body of the actual synthetic robot | 30mm PNG canvas | 5.610841 | 7.762527 | Tatami |
| Actual app-logo M | Overall logo width 60mm | 10.696521 | 11.911968 | Tatami |
| Actual app-logo hoop ring | Overall logo width 60mm | 5.257371 | 5.271908 | Satin |

`ImageToDesignProbeTest` asserts that tatami is selected by the maximum-width condition even
though the body median remains below 6mm. It also tests the 0.9/6mm boundaries, maximum-width
priority (maximum width >6mm even when the median is below 0.9mm), and actual logo paths.
PNG satin uses the existing AutoTrace centerline/fit → Ink/Stitch `convertPathToSatin`/`AutoSatin`;
tatami, running, and S1 defaults were not changed.

| Synthetic input | Regions | Previous stitches → final | Previous total ms → final | Final engine/external gate |
|---|---:|---:|---:|---|
| face.png | 4 | 1,050 → 1,050 | 1,018.17 → 1,013.66 | PASS/PASS |
| robot.png | 5 | 600 → 831 | 208.32 → 384.85 | PASS/PASS |

The robot now fills the actual area instead of using incorrectly selected satin, increasing stitches
and time. Single-run times include warmup effects. The logo PES was also regenerated and checked
with the same external gate. Actual-sewing approval is separate; app/personal inputs were not connected.

Reproduction:

```sh
python3 tools/image-to-design/generate-inputs.py
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
IMAGE_PROBE_LOGO_OUT=/tmp/myembro-port-out/editor/image-to-design ./gradlew :engine:test :engine:imageToDesignProbe
/tmp/myembro-quality-gate/quality-gate check \
 /tmp/myembro-port-out/editor/image-to-design/face.pes \
 /tmp/myembro-port-out/editor/image-to-design/robot.pes \
 /tmp/myembro-port-out/editor/image-to-design/logo.pes \
 --style mixed --json /tmp/myembro-port-out/editor/image-to-design/quality-gate-max.json
```

Execution output: `BUILD SUCCESSFUL in 19s`; engine tests=724, failures=0, errors=0. External tool:
`PASS 3/3 cases passed`. Final logs: `/tmp/image-to-design-canvas-rule.log`,
`/tmp/image-to-design-max-gate.log`; original measurements `results.json` and `logo-widths.csv`
are in the output directory. The earlier model-API-only replacement trial and intermediate
measurements do not supersede this final result.


## App-integration unit

The trial generation path was subsequently moved into the production JVM API `ColorDrawingPipeline`
and connected to color mode under Home photo/drawing. Earlier statements about no app integration/
rejection of partial alpha describe the trial at that time; current status follows
[Color drawing QA](qa/color-drawing/README.md). Personal-image local processing remains the policy.
