# Per-region generation fallback: fixing small-eye/mouth reproduction

Added fallback policy to shared `StitchTypeRule.generateRegion`. Canvas `VectorArt` and color-drawing
`ColorDrawingPipeline` call the same function. Existing 0.9/6mm classification conditions are unchanged
and remain separate from canvas's planned 1.0mm/centerline-length condition.

- Satin-generation failure/empty output/gate block → tatami → running **outline** → exclude only that region.
- Tatami-classified regions start with tatami; running-classified regions start with running.
- Combine each candidate with existing retained regions and judge the actual S1/PES/`SewoutCheck(APP_GENERATED)` output. The color path includes actual inter-region trims and the selected hoop. A candidate disappearing during postprocessing is not considered success.
- Propagate interrupts/cancellation rather than swallowing them as fallback/exclusion.
- Expose the exclusion count through result `excludedCount` and pattern `myembro.excluded_regions`. Display ko/en "N excluded" in app results. If all regions are excluded, return an empty result with a count and disable saving/transferring/editing.

The shared portion was first pushed as **`claude/stitch-rule: 6b5d7a3`** by cherry-picking editor's
`ac4a780` onto the shared branch parent. That commit contains only the shared function, canvas
integration, and shared regression tests. The direct canvas messaging channel was unavailable, so
the early push hash and the starting point for follow-up work were conveyed in the integrator conversation.

## Reproduction and code-based decisions

The integrator-provided integer-pixel generation formula for the 192px·40mm synthetic sun face
in `ColorSampleCli.fail.kt` was copied unchanged into `ColorSampleRegressionTest`. It is not a
personal design or photograph. Original location:

`/tmp/claude-1000/-home-gosh2-workspace-hoonyjung-myembro/cc38832e-2ca4-4237-b6c9-177cab9b3c94/scratchpad/v025-pes/ColorSampleCli.fail.kt`

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
./gradlew check :app:assembleDebug
IMAGE_PROBE_LOGO_OUT=/tmp/myembro-port-out/editor/region-fallback \
REGION_FALLBACK_OUT=/tmp/myembro-port-out/editor/region-fallback \
./gradlew :engine:test --tests '*ColorSampleRegressionTest' --tests '*VectorArtClassificationTest'
/tmp/myembro-quality-gate/quality-gate check \
 /tmp/myembro-port-out/editor/region-fallback/sun-face.pes \
 /tmp/myembro-port-out/editor/region-fallback/logo.pes \
 --style mixed --json /tmp/myembro-port-out/editor/region-fallback/gate.json
```

```text
INTEGRATOR_REPRO stitches=1846 excluded=0 types=[TATAMI, TATAMI, TATAMI, SATIN] blockers=false
BUILD SUCCESSFUL in 40s
PASS .../sun-face.pes within five-condition checks
PASS .../logo.pes within five-condition checks
PASS 2/2 cases passed
```

Regression tests:

- Original reproduction: replace the two small round eyes with tatami, retain all 4 regions, and assert 0 exclusions and no file blockers.
- Satin null + tatami gate rejection → assert running order.
- All generators fail → exclude that region and assert generation of the next valid region.
- Block all candidates with a very small test hoop → assert an empty result and exact exclusion count, without exceptions.
- Check cancellation/interrupt propagation and existing canvas-logo classification/gates as well.

Logs: `/tmp/region-fallback-final-check.log`, `/tmp/region-fallback-artifacts.log`,
`/tmp/region-fallback-gate.log`. Final decisions are automated test/gate results and do not include
actual-sewing approval.
