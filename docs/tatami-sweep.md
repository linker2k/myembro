# Extended tatami sweep — local only

> Current results reswept with backend `e154303`. Computed the same430combinations without algorithm changes.

Retains CI representative cases(official-default square/circle/hole 3 cases + smoke 3 cases).
The extended-sweep task is not a dependency of `check`; run it separately locally before a stage release.
Only rule-calculator unit tests 3 cases were added to CI.

## Reproduction commands

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
python3 -m unittest discover -s tools/tests -p test_tatami_sweep.py
python3 tools/sweep_tatami.py --out /tmp/myembro-tatami-sweep-source-routing
```

After invoking `./gradlew :engine:generateTatamiSweep`, the script collects independent external
`/tmp/myembro-quality-gate/quality-gate check --matrix …/generation.json --json …/gate.json`
verdicts. The worker's POLICY.md Kotlin gate has not yet been merged into this branch, so old
SewoutCheck results are not treated as the same policy. Records SHA256 of external gate code, thresholds, and POLICY.md;
if these change during checks, rule publication stops. Does not execute upstream Ink/Stitch.

## Computed input space

- Size: **5–90mm, 1mm increments**, 86 values. Only the default angle0°.
- Shapes: square, circle, donut(inner/outer diameter ratio0.4), star(5points·inner/outer radius0.45), thick H.
- H is an original outline. Height=size, width=0.8×size, stroke thickness=0.2×size.
- Star size is its circumcircle diameter. Its actual bbox is smaller than size.
- Total430combinations. No private designs/fonts; shapes are original(CC0-1.0).
- Uses default DefaultTatami(top0°/.25mm/4mm, underlay90°/.75mm/4mm), S1 default planner,
  and PES v6 writer. Input flattening error is0.01mm. Algorithms are unchanged.

## Generation results and allow rules

The output directory contains `generation.log/json`, candidate PES430files, independent verdicts `gate.log/json`,
`results.json`, `failures.json`, `summary.md`, `fill-allowed-regions.json`, and `provenance.json`.
Generation failures, file mismatches, and gate BLOCKs are automatically excluded from the allowlist.
Code computes passing sizes, failing sizes, first failing size, and minimum passing sample area per shape.
Preserves exact passing lists rather than hiding non-monotonic failures behind a single minimum size.

**The allowlist refers only to this finite input set.** For example, passing 5mm and6mm does not automatically
allow5.5mm. It does not validate arbitrary polygons/glyphs/hole ratios/curvature and is not generalized by area alone.
`minimum_verified_area_mm2` is an observed per-shape sample value, not a general area criterion.
Sizes below 5mm are untested, so this does not claim to find the actual minimum safe size.
Untested inputs are DENY, with `interpolation_allowed=false`, `arbitrary_polygons_covered=false`.
Unit tests check that intermediate or complete failures do not produce false safe ranges.
Interrupted runs leave only INCOMPLETE/DENY rules and never reuse a previous run's allowlist.

The allow JSON is a candidate rule set, not connected to the app, and retains `deployment_enabled=false`.
Official equivalence3cases passed in the backend-fix unit. Gate passage is separate from remote CI green/
actual sewout approval. Sweep results alone are not reported as satisfying those requirements.

## New backend resweep output

```text
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 python3 tools/sweep_tatami.py --out /tmp/myembro-tatami-sweep-source-routing
BUILD SUCCESSFUL in 1m 12s
GATE: 430/430 PASS; 0 BLOCK

# gate.log의 마지막 판정
PASS 430/430 cases passed
```

| Shape | Passed/total | Minimum passing sample mm | First failing size | Maximum cell density | Maximum consecutive identical points | Maximum stitch mm |
|---|---:|---:|---|---:|---:|---:|
| square | 86/86 | 5 | None | 8 | 1 | 4.001250 |
| circle | 86/86 | 5 | None | 10 | 1 | 4.101219 |
| donut | 86/86 | 5 | None | 10 | 1 | 4.101219 |
| star | 86/86 | 5 | None | 9 | 1 | 4.101219 |
| block_h | 86/86 | 5 | None | 7 | 1 | 4.101219 |

Generation errors0; failure list is `[]`. Short-stitch statistics visible for circle/donut/star/H are reference metrics, not blocking reasons.
Sizes below 5mm are untested; do not interpret this as finding the actual minimum safe size.

- [Machine verdict summary/provenance/gate SHA256](tatami-sweep-results/summary.json)
- [Finite-sample allow JSON for the new backend](tatami-sweep-results/fill-allowed-regions.json)
- [Failure list](tatami-sweep-results/failures.json)

Full original verdicts `gate.json/log`, generation `generation.json/log`, and candidate430PES files are in
`/tmp/myembro-tatami-sweep-source-routing/`. Previous backend results remain as history in
`/tmp/myembro-tatami-sweep-1mm/` and commit4f21a67.
This unit performed computation and result recording only. No engine/threshold/test/CI configuration changes.
