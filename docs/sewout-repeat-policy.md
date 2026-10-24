# Repetition policy v6 after the SE400 start-command incident

User instruction: app-generated output **blocks when STITCH/JUMP destination coordinates repeat consecutively more than 2 times**.
Counts 0-length needle stitches and 0-length JUMPs as 1 command each. Does not count the implicit origin;
TRIM/STOP/COLOR_CHANGE/END neither add to the count nor hide continuity.
A JUMP/STITCH to another coordinate resets the consecutive count to 1.
Imported PES retain warnings for excess repetition. Density above 30, file validity/hoop/maximum-stitch policies remain.

## Computation evidence and policy choice

Computed by reading the entire 334file local PE-Design corpus only. Files are not committed.

| Definition | 1 time | 2 times | 3 times | 5 times | Linear-interpolation p99 |
|---|---:|---:|---:|---:|---:|
| Needle(STITCH) only | 173 | 158 | 1 | 2 | 2.0 |
| STITCH + JUMP | 39 | 286 | 7 | 2 | 3.0 |

New threshold 2 is a **user-specified policy extending needle-only p99=2 to the stricter JUMP-inclusive definition**.
Does not claim the JUMP-inclusive distribution's p99 is 2. Corpus-style patterns corresponding to app output 9/334files
would also block under this criterion, but actual external PES use import policy and are not blocked by repetition alone.
This change does not confirm the machine incident's cause or a successful sewout.

## Reproduction and validation

- `python3 tools/update_gate_repeat_policy.py`: revises independent `/tmp/myembro-quality-gate` to v6.
  Checks original gate.py SHA-256 and preserves executable v5 files in versions/frozen-v5-before-motion-repeat.
  Changes only repetition aggregation and limits, without recalibrating existing thresholds. Records new helper/configuration hashes in the manifest.
- `python3 -m unittest discover -s tools/tests -p test_quality_gate_repeat.py`: Python boundary/command tests 5.
- `./gradlew check`: Kotlin boundary tests and Python v6 snapshot verdict/metric comparisons for representative PES 10types.
  Python is needed only when updating independent expectations. CI compares committed fixtures and SHA-256/metrics.
- `tools/refresh_sewout_gate_reference.py --inputs /tmp/myembro-gate-parity-v6`:
  see the script header for export environment-variable usage. Previous v5 expectations are preserved.

| Actual saved file | Maximum consecutive including JUMP | v6 app output |
|---|---:|---|
| S1SAMPL.PES | 4 | Block |
| S23SAMPL.PES (0a60ff0 and earlier) | 3 | Block |
| T1PEDS.PES | 1 | Pass |
| T2OURS.PES | 3 | Block |
| T3NOZRO.PES | 1 | Pass |

Existing S1 generation-path project regressions 5 cases were also changed to verify repetition blocking.
Inputs/generation algorithms/files are not modified to fit the gate. Retains coordinate/stitch-count comparisons for official oracles 2types.
