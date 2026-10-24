# Python/Kotlin sewout-check crosscheck

## Previous results and causes

Codex's comparison on main `38594a2` differed for 70 of 334 corpus files and 1 of 4 app boundary cases, 71 total. The counts below can overlap within the same file.

| Condition | Python → Kotlin | Count | Cause/fix |
|---|---|---:|---|
| file | PASS → BLOCK | 71 | Kotlin counted `PEC_HOOP` as `INVALID_PES`. Separate hoop overflow as `HOOP_EXCEEDED`. |
| zero_motion | WARNING → PASS | 32 | Exporter missed warnings displaced from the bounded UI detail list. Aggregate worst severity per condition separately across all findings. |
| locks | WARNING → PASS | 26 | Same detail-list limit as above. |
| length | WARNING → PASS | 5 | Same detail-list limit as above. |
| repetition | WARNING → PASS | 4 | Same detail-list limit as above. |

Verdicts are based on AGENTS P1/P2/P3/P4/P5/P7/P8 and the local POLICY corpus scope. The Python crosscheck reference corpus uses `apply_hoop=false`, but Kotlin lacked equivalent scope control. Kotlin's scope/classification was wrong in treating hoop issues in 70 corpus files as file corruption. App boundary 1000 correctly blocks for hoop overflow; **additionally reporting a file-corruption block** was the error. Coordinate-origin/header mismatches remain blocked in both scopes.

`SewoutOptions.applyHoop=false` is used only for offline reference comparison. Defaults to true; app transfer/display paths are unchanged. `findingSeverities` aggregates all conditions independently of the maximum displayed detail count. Exporter uses this aggregation too. Retains existing imported warning policy(locks/repetition/0-length/long stitches) and density above 15 warning/above 30 block. Python core/thresholds/frozen profiles were not changed to force agreement.

## Reproduction

```sh
/tmp/myembro-quality-gate/crosscheck.sh \
  --repo /home/gosh2/workspace/hoonyjung/myembro-worker --commit HEAD \
  --kotlin-command python3 \
  /home/gosh2/workspace/hoonyjung/myembro-worker/tools/crosscheck_kotlin_export.py \
  '{manifest}' '{output}' '{checkout}'
```

Crosscheck archives the specified commit in `/tmp` and runs Kotlin. The exporter receives only input files/hashes and does not read Python verdicts. Private designs/names/paths/detailed results are not committed. Public regressions are `CrosscheckScopeTest` and `ExactPolicyTest`, automatically included in `check`.

Rerun output is `0`(exit code 0). Corpus **0/334 mismatches**, app boundaries **0/4 mismatches**, total **0/338 mismatches**. Local results are in `/tmp/myembro-worker-crosscheck.stdout`, `/tmp/myembro-quality-gate/crosscheck-latest.json`, and its referenced `comparison.json`. `CrosscheckScopeTest` verifies preservation of condition severities/final verdicts for 999/1000/1001, invalid headers, and `maxFindings=1`.
