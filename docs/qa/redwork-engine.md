# Single-color redwork engine, 1st pass

Not connected to the app. Uses only 2 original PBMs, with no personal photos/designs.

## Execution path and basis

- Input RGB24/physical width/known background pixel → fixed Wu v2 2-color quantization → exclude background label.
- Potrace 1.16 defaults: MINORITY, turdsize=2, alphamax=1, opticurve=1, opttolerance=0.2.
- Coordinates: raster top-down → Potrace y-up → centered-origin SVG px/y-down, preserving aspect ratio.
- Optimized Bézier → existing `SvgPath.flatten(tolerance=0.01px)`; uses the same boundary as existing `StrokeFixtureTest`.
- Unchanged Ink/Stitch v3.3.0 `StrokeParameters()`: running 2.5mm, tolerance 0.2mm,
  repeats=1, bean=0. Default half-stitch start/end locks.
- `GeneratedPatternProcessor.fromGroups` (S1 locks/filter/native deduplication) → `PesV6Writer`.
- No new parameters/automatic background/skeleton tracing/path reordering/bean-settings UI.
  Both boundaries of a thick band remain separate contours.
- Wu/Potrace source commits/licenses/C-comparison tests were preserved through dependency merge commits.
  This does not mean the combined pipeline is an official Ink/Stitch image feature.

## Reproduction and artifacts

```sh
tools/redwork/check-fixtures.sh /tmp/myembro-quality-gate/quality-gate
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check
```

Artifacts under `/tmp/myembro-port-out/editor/redwork/`: PBM, SVG, PES, SewoutCheck findings,
`matrix.json` with commit/input/output SHA-256, `quality-gate.json`, and `quality-gate.txt`.
The first run and CI unit tests call only the engine; there is no app entry point.
The gate program/thresholds/stage configuration files were not modified. There is no automatic
stage advancement or release approval either.

## External quality-gate check output

```text
PASS /tmp/myembro-port-out/editor/redwork/rectangle.pes within five-condition checks
PASS /tmp/myembro-port-out/editor/redwork/round.pes within five-condition checks
PASS 2/2 cases passed
```

| Input | Needle count | Maximum consecutive same-point count | 0.5mm cell peak | Maximum stitch mm | Unconfirmed start/end locks |
|---|---:|---:|---:|---:|---:|
| rectangle | 62 | 1 | 4 | 2.6000 | 0/0 |
| round | 51 | 1 | 4 | 2.5554 | 0/0 |

## Regression fix for internal-decision mismatch

Existing SewoutCheck counted cumulative visits per coordinate as consecutive repetition, blocking
even a normal closed contour returning after a lock as REPEATED_PENETRATION. User policy is
**more than 3 consecutive penetrations at the same point**. Threshold 4 was retained, and the
consecutive counter was changed to reset only on movement to another needle coordinate.
It continues counting across other control commands if the needle coordinate remains the same.

- Retain the existing test blocking an actual 4 consecutive repetitions.
- Add a regression test confirming that separated revisits are not consecutive repetitions.
- Assert that the existing fan shape remains density-blocked; update only the incorrect consecutive-repetition assertion to 0.
- Do not hide existing internal lock WARNING or unconfirmed-machine INFO. External v5 functional lock checks confirmed start/end locks in both files. No claim is made of complete internal/external lock-detector equivalence.


## Full check execution result

```text
./gradlew check
BUILD SUCCESSFUL in 54s
66 actionable tasks: 24 executed, 42 up-to-date
```

Engine: 416 tests / 0 failures / 0 errors; app debug/release: 176 tests / 0 failures each.
These numbers come from local JUnit XML and Gradle output, not a claim of green remote CI.
