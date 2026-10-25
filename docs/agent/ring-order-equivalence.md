# Ring canonicalization: before/after official-output comparison

This audit changes no engine, test, fixture, or oracle code. It compares parent
`810715d41bb05a0a870fa12632d2533f6bc5a0d6` with ring-order fix
`f0dcdd5d9078490350355e854195634d44720f9d` using the existing harnesses documented in
[tatami-equivalence-fix.md](../tatami-equivalence-fix.md) and
[fill-matrix-reproduction.md](../fill-matrix-reproduction.md).
The latest explicit request authorizes this one archived-matrix replay; it does
not restore the old matrix as an application release gate.

The matrix is the archived v1 file, not the currently active defaults-v2 file:
`/tmp/myembro-quality-gate/versions/matrix-v1/matrix-inkstitch.json`, SHA256
`7ce25a2faa933047030f804b0512a6cc476fb9a2437e98a014a5aa07966221ee`.
Official v3.3.0 PES outputs remain under `/tmp/myembro-inkstitch-oracle/out/fill_stitch/`.
The original application and Ink/Stitch were not executed or modified.

## Reproduction

Use a clean checkout of each commit, the existing SDK configuration, and
`JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1`. Substitute `before`/`after` for `SIDE`:

```sh
./gradlew :engine:test --tests '*DefaultTatamiOracleTest' --console=plain
python3 tools/reproduce_fill_matrix.py \
  --matrix /tmp/myembro-quality-gate/versions/matrix-v1/matrix-inkstitch.json \
  --output-dir /tmp/myembro-ring-equivalence/SIDE
python3 tools/measure_fill_matrix.py \
  --matrix /tmp/myembro-quality-gate/versions/matrix-v1/matrix-inkstitch.json \
  --results-json /tmp/myembro-ring-equivalence/SIDE/results.json \
  --pes-python /tmp/claude-1000/-home-gosh2-workspace-hoonyjung-myembro/8141f70b-3d10-4aef-87f7-55b03ccb7225/scratchpad/venv/bin/python
```

Do not overlap Gradle runs in the same checkout: an initial after-run was
interrupted by a second task replacing its class outputs (`FillMethod$Tartan`
not found). The completed standalone rerun supersedes that interrupted run.
Separate before/after checkouts were used. All cases, including generation
errors, are retained in each `results.json`; the reproduction task exits nonzero
if any case fails generation.

## Existing representative assertions

`DefaultTatamiOracleTest` asserts count error <=5% and centered symmetric
Hausdorff <=0.2mm. Its ordered-path measurements are diagnostics, not assertions.
Both runs report `BUILD SUCCESSFUL`: 3 tests, 0 failures/errors.

| Case | Before/after candidate needles | Official needles | Before/after Hausdorff (mm) | Before/after ordered maximum (mm) |
|---|---:|---:|---:|---:|
| rectangle | 570 / 570 | 571 | 0.14142135623731025 | 4.001249804748512 |
| circle | 661 / 661 | 662 | 0.1414213562373109 | 4.001249804748512 |
| hole | 823 / 823 | 824 | 0.14142135623731025 | 4.001249804748512 |

Thus these existing assertions remain 3/3 -> 3/3. The existing ~4mm ordered
error was already present before canonicalization; this is not proof of full
ordered-path equivalence to the official output. Older historical figures in
`tatami-equivalence-fix.md` are not substituted for this freshly measured baseline.

## Full official comparator limitation

The unchanged official decoder was attempted:

```sh
/tmp/claude-1000/-home-gosh2-workspace-hoonyjung-myembro/8141f70b-3d10-4aef-87f7-55b03ccb7225/scratchpad/venv/bin/python \
  /tmp/myembro-inkstitch-oracle/export_coordinates.py \
  --pes /tmp/myembro-ring-equivalence/after/fill_stitch--rectangle--004mm--horizontal.pes \
  --output /tmp/myembro-ring-equivalence/decode-probe.json.gz
```

```text
AssertionError: FROZEN_GATE_CHANGED: thresholds.json
```

The frozen decoder/criteria checks were not bypassed or edited. Consequently,
**full official equivalence (including lock signatures, controls, and sewing
order) is unconfirmed for this matrix**. The numeric table below uses the existing
`measure_fill_matrix.py` observations only, with the unchanged official numeric
thresholds. It is not an independent release-gate or overall equivalence verdict.

## Archived fill matrix: unchanged numeric results

| Observation | Before | After | Change |
|---|---:|---:|---:|
| Cases attempted | 225 | 225 | +0 |
| PES generated / compared | 178 | 178 | +0 |
| Count error <=5% | 151 | 151 | +0 |
| Hausdorff <=0.2mm | 119 | 119 | +0 |
| Both count and Hausdorff | 115 | 115 | +0 |
| Ordered p95 <=0.3mm and max <=1.0mm | 0 | 0 | +0 |
| Rejected by existing hoop safety check | 47 | 47 | 0 |

```text
before {'commit': '810715d41bb05a0a870fa12632d2533f6bc5a0d6', 'generated': 178, 'compared': 178, 'count_pass': 151, 'hausdorff_pass': 119, 'count_and_hausdorff_pass': 115, 'ordered_pass': 0}
after {'commit': 'f0dcdd5d9078490350355e854195634d44720f9d', 'generated': 178, 'compared': 178, 'count_pass': 151, 'hausdorff_pass': 119, 'count_and_hausdorff_pass': 115, 'ordered_pass': 0}
count regressions 0 improvements 0

hausdorff regressions 0 improvements 0

order regressions 0 improvements 0

combined regressions 0 improvements 0
```

Each generation log records all 225 cases and finishes nonzero because the
47 hoop exclusions are errors to that existing task. Both measurement commands
complete successfully: `Measured 178 pairs; unavailable 47`. Generation status
is identical case by case. None of the excluded cases is counted as equivalent.

### Per-shape count-and-Hausdorff observations

| Shape | Compared | Before both | After both | Regressions |
|---|---:|---:|---:|---:|
| circle | 24 | 24 | 24 | 0 |
| crossing_strokes | 24 | 20 | 20 | 0 |
| hole | 24 | 21 | 21 | 0 |
| narrow_tip | 28 | 26 | 26 | 0 |
| rectangle | 24 | 24 | 24 | 0 |
| self_intersection | 25 | 0 | 0 | 0 |
| sharp_corner | 28 | 0 | 0 | 0 |
| zero-row-spacing | 1 | 0 | 0 | 0 |

### Decreased cases and interpretation

**No decreased cases** in any observed count, coordinate, or ordered-path
criterion. More strongly, all **178/178 generated PES files have identical
SHA256 before/after**; the representative 3/3 PES files are also byte-identical.
Per-case hashes, the 47 exclusions, and empty regression lists are in
[the machine-readable report](ring-order-equivalence-results.json). Given
identical official references and candidate bytes, a deterministic full
comparator would also give the same before/after verdicts, although its absolute
verdict counts could not be obtained due to the frozen-decoder error above.

This is deliberately a statement about the existing harness scope.
`FillMatrixInput.kt:86–102` constructs rings directly using `ManualCspPath.flatten`,
area ordering, `createPolygon`, validity repair, and precision reduction; it
does not call the changed `PolygonRegion.fromPaths` Polygonizer boundary.
`DefaultTatamiOracleTest.kt:28` wraps that same prepared geometry. Thus these
unchanged files demonstrate **no regression to the existing oracle harness**,
not a new official-equivalence proof for Android TTF contour polygonization.
The preceding fix
[noto-fill-order-fix.md](noto-fill-order-fix.md) separately records the changed
TTF route, Android/JVM byte equality, and 430/430 mechanical checks; those are
not substituted here for official-output equivalence. No new algorithms,
threshold exceptions, engine changes, or test changes were introduced.

## Local evidence

- `/tmp/myembro-ring-equivalence/{before,after}/results.json`: 225 case outcomes each.
- `/tmp/myembro-ring-equivalence/{before,after}/observations/measurements.json`: 178 numeric comparisons each.
- `/tmp/myembro-ring-equivalence/{before,after}-tatami/`: 3 PES and 3 diagnostic JSON files each.
- `/tmp/myembro-ring-equivalence/{before,after}-generation.log` and `*-tatami.log`: actual task output.
- `/tmp/myembro-ring-equivalence/summary.json`: aggregated counts, hashes, and transitions.

No push or main merge.
