# Photo fill island order

Silhouette fill islands and regions within each color layer reuse `PhotoOutline.route`:
JGraphT 1.5.2 `NearestNeighborHeuristicTSP`, fixed first vertex, adopting a candidate
only when its open anchor-path cost improves. The input anchors are the original
polygon exterior rings. Holes stay attached to their polygon. Color layer order is
unchanged. This is the existing photo routing algorithm, not another tour solver.

Each silhouette polygon independently uses the unchanged `DefaultTatami` generator,
followed immediately by its original, unexpanded one-pass exterior/hole outlines.
The existing `ColorFillOutline.select` still removes only unsafe optional outlines,
checking the final photo fit and selected hoop. Like the color path, a retained
island ends with `trimAfter` only when another retained island follows. The final
island gets no extra terminal trim. The OUTLINE photo style is unchanged.

## Matched output measurement (2026-10-28)

Baseline: main `b23060f`. Same frozen 90 silhouette and 90 color inputs from the
P7 frequency measurement: widths 60/85/99.9 mm, detail levels 0/1/2; additionally
the local 91.7 mm center-square proxy. Two color inputs fail the same existing
`PHOTO_TOO_COMPLEX` check before and after, leaving 88 color PES outputs.
Private proxy images and generated files are local only, never fixtures or commits.

Metrics are calculated from independently decoded PES commands using quality-gate
v8 `gate.decode` (bounded PEC reader plus pyembroidery). Distances include every
encoded JUMP from its preceding command position; TRIM counts are decoded PEC
trim flags. Peak is the existing four-phase 0.5 mm-cell statistic.

| Feature | Version | PES | JUMPs | Jump distance sum (mm) | TRIMs | Maximum jump (mm) | Maximum peak | Gate BLOCK |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Silhouette | b23060f | 90 | 891 | 16204.931 | 0 | 103.773 | 15 | 0 |
| Silhouette | Island route | 90 | 685 | 13615.477 | 346 | 85.295 | 11 | 0 |
| Color | b23060f | 88 | 1747 | 48073.308 | 1659 | 132.220 | 19 | 0 |
| Color | Island route | 88 | 1747 | 39137.527 | 1659 | 132.220 | 19 | 0 |
| Local 91.7 mm proxy | b23060f | 1 | 57 | 1313.434 | 0 | 94.518 | 11 | 0 |
| Local 91.7 mm proxy | Island route | 1 | 33 | 521.065 | 27 | 77.991 | 11 | 0 |

Aggregate jump-distance reductions: silhouette 15.98%, color 18.59%, local proxy
60.33%. Silhouette individual cases: 52 shorter, 27 unchanged, 11 longer. Color:
17 shorter, 71 unchanged, none longer. The route's improvement condition applies
to polygon-anchor cost; adding each island's immediate outline and trim changes
the complete sewing itinerary, so it does not guarantee shorter PES travel for
every individual input. No custom optimization or extra acceptance threshold was
introduced to address those cases.

Both variants pass the SE400 v8 gate: `PASS 179/179 cases passed`. The independent
JVM harness also invokes the normal checked PES writer for every output; no writer
rejection occurs. These measurements are not a real-machine sewout result.

## Local reproduction artifacts

- `/tmp/myembro-island-order/Frequency.kt`, `cases.json`: frozen inputs and JVM harness.
- `bash /tmp/myembro-island-order/run.sh baseline` and `candidate`: generation.
- Existing venv Python with `PYTHONPATH=/usr/lib/python3/dist-packages` runs
  `/tmp/myembro-island-order/audit.py`, `paired.py`, and `draw.py`.
- `/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-island-order/pes/candidate --json /tmp/myembro-island-order/candidate-gate.json`
- Corresponding baseline command substitutes `baseline` for `candidate`.
- `/tmp/myembro-island-order/table.md`, `decoded.json`, `*-gate.json`, `*-all.jsonl`.
- `/tmp/myembro-island-order/stitch-jump-before-after.png`: brown needle stitches,
  blue needle-up travel. Private image, local only.

Automated regressions cover fixed-start routing, identity/hole preservation,
unchanged per-island tatami coordinates, immediate outlines, inter-island trims,
selected-hoop validation, exclusive 998/999/1000 bounds, and encoded P1–P8 checks.

## Validation

- Engine: 1022 tests, 0 failures/errors/skips.
- App debug: 465 tests, 0 failures/errors/skips.
- New island-order regression: 2 tests pass, including original per-island fill
  coordinates, holes, immediate outlines and trims only between retained islands.
- `:app:test` also runs release tests: 463/465 pass; the two
  `EditorColorsPanelUiTest` methods fail before displaying content with
  `Unable to resolve activity ... androidx.activity.ComponentActivity`.
  The unchanged `app/build.gradle.kts:121` provides `ui-test-manifest` only through
  `debugImplementation`; the merged debug manifest contains ComponentActivity,
  while the merged release manifest does not. Neither that build configuration
  nor the UI test is changed by this branch. This full-task failure is reported,
  not hidden or fixed as part of the photo routing change.
- Full task output: `/tmp/myembro-island-order/tests-lint.log`.
- `./gradlew --offline :engine:test :app:testDebugUnitTest lint --max-workers=2`
  (JDK 17, Gradle heap 2 GB): `BUILD SUCCESSFUL in 31s`; lint passes.
- `git diff --check`: pass.
