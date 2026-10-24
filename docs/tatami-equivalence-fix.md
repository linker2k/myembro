# Default tatami official-equivalence fix — numerical breakdown

Inputs, official coordinates, stitch count±5%, and Hausdorff≤0.2mm criteria are unchanged.
Tests compare all coordinates and locks and also output the first differing index in ordered comparisons.
No rotation/scaling/point removal beyond centering translation. Indices start at0.

| Case | Before stitch-count ratio(ours/official) | Before Hausdorff mm | Before first difference | After stitch count | After Hausdorff mm | After first difference | After first difference exceeding0.2mm |
|---|---:|---:|---:|---:|---:|---:|---:|
| rectangle | 0.975482 | 0.583095 | 1 | 571/571 | 0.141421 | 1 | -1 |
| circle | 1.090634 | 0.531507 | 0 | 662/662 | 0.141421 | 0 | -1 |
| hole | 0.975728 | 0.583095 | 1 | 824/824 | 0.141421 | 1 | -1 |

`-1` means no such difference. Remaining exact-coordinate differences are at native0.1mm quantization/
writer output-origin handling boundaries; maximum ordered error is below0.141422mm for all3cases.
Test assertions determine whether results are within tolerance. No byte identity is claimed.

## Upstream comparison and changes

Pinned upstream is Ink/Stitch v3.3.0 `b0edd96311ece82ee48816dc93466274b12c2a9c`.
Upstream was read only; no execution/dependency installation.

1. `lib/stitches/tatami_fill.py:find_stitch_path` includes start/end nodes and internal travel from the actual starting point
   in the path. The previous simplified InkAutoFill omitted outline paths during traversal and began at the first fill row,
   differing from the first lock direction onward. Removed the separate simplified graph and calls the already ported
   TatamiFill/FillRouting through a native-unit compatibility adapter. No duplicate algorithm remains.
2. `lib/elements/fill_stitch.py:do_underlay/to_stitch_groups` passes the final underlay stitch as
   top's starting_point. DefaultTatami preserves this connection as well.
3. Upstream travel gives `even_running_stitch` the getter default0.2mm tolerance.
   Changed the simplified code's0 to upstream0.2mm. Existing path tests check needle coordinates are inside;
   segment corner deviations use this upstream tolerance(0-length segments use point covers checks).
4. After core replacement, circle was still662/662stitches, Hausdorff0.570088mm. Test SVG arc
   flattening differed from upstream Inkex CSP. Shared the worker's already ported ManualCspPath.flatten
   from the oracle source set to compare using the same polygon Ink/Stitch receives.
   Official goldens were unchanged. Oracle code use was added only to the test classpath.

`DefaultTatamiOracleTest` outputs raw underlay/top stitch counts/start/end points, postprocess needle counts,
actual PES needle counts, official stitch counts, count_ratio, Hausdorff, first difference,
first difference exceeding0.2mm, and ordered_max_error_mm to `engine/build/tatami-oracle/*.json`.
Before/after source results for this unit are preserved in [comparison.json](tatami-equivalence-results/comparison.json).

## Execution verdicts

```text
./gradlew :engine:clean :engine:check --console=plain
BUILD SUCCESSFUL in 21s
ENGINE CHECK: 509 tests; 0 failures; 0 errors

/tmp/myembro-quality-gate/quality-gate check engine/build/tatami-oracle --json /tmp/myembro-tatami-equivalence-fix/after/gate.json
PASS 3/3 cases passed
```

Representative3cases passed official equivalence. Worker Kotlin POLICY gate integration is separately pending.
The previous430combination sweep is history for the old backend and cannot be reused after this core change.
Keeps allow-rule deployment_enabled=false. The next unit must resweep the new backend.
Remote CI green and actual sewout approval have not yet been confirmed.
