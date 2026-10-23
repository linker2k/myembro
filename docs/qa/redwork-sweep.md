# S4 synthetic redwork detail sweep

## Reproduce

From the repository root (JDK 17 and Python 3 with Pillow already available):

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/redwork -p test_sweep.py -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/redwork/sweep.py > /tmp/myembro-redwork-sweep.log 2>&1
```

The script generates original 512×512 black/white drawings: four filled shapes
(square/disk/triangle/star), four line drawings (ring/zigzag/cross/spiral), four
geometric glyphs (A/H/M/Z; no font files), four thin diagonal lines (1–4px), and
four isolated dots (radius 1–4px). No personal designs/photos, external artwork,
Ink/Stitch execution, or new dependencies are involved.

Five **measurement** levels are 128/160/192/224/256px, bilinearly sampled with
Pillow from each fixed source. Current UI levels remain 128/192/256px. This is a
JVM-engine sweep, not a claim of pixel-identical Android bitmap resampling.

## Fixed configuration and verdict

The 100×100mm image is centered in SE400 H100, top-left pixel is the known white
background, actual output is the app's default PES v1, read back by
`SewoutCheck.checkPes`. `Placement.HOOP_COORDINATES` and inclusive density
threshold 16 (>15 penetrations per 0.5mm cell) match the photo UI and generated
output policy. All other SewoutCheck options and upstream Wu/Potrace/Stroke/S1
parameters remain unchanged. Sources/defaults are documented in
[redwork-engine.md](redwork-engine.md) and the separately attributed port files.

A case passes this sweep only if generation succeeds, analysis is complete, and
SewoutCheck reports zero blockers. Warnings remain visible. Every generation
exception is a failed combination, never silently excluded. The policy is the
intersection across **all 20** fixtures; discrete allowed levels and adjacent
measured groups prevent bridging failures. An empty intersection disables all
levels. This JSON is a measurement proposal; it does not modify the app or
claim safety on arbitrary photographs/interpolated settings.

## Measured output

`/tmp/myembro-port-out/editor/sweep/` contains:

- `sources/*.png`: 20 original generated drawings.
- `inputs/*.rgb` and `inputs.json`: all 100 RGB24 inputs and matrix.
- `pes/*.pes`: actual 100 exported files.
- `results.json`: every verdict, input SHA-256, stitch/contour counts, findings,
  measured values, thresholds, severity counts and analysis completeness.
- `summary.json`: executing commit, dirty flag, sampling version/configuration,
  aggregate results and results SHA-256.
- `detail-policy.json`: automatically computed allowed levels and restrictions.

Initial executed sweep: **100 pass / 0 blocked / 0 generation errors**; each
level has 20/20 with zero blockers. All five measured levels remain allowed.
Warnings: `MISSING_LOCK_STITCH` 100 findings, `SHORT_STITCH` 39,
`OVERLAP_ACCUMULATION` 4. In particular, the existing SewoutCheck lock recognizer
still warns on the upstream half-stitch ties; this sweep does **not** assert
independent functional lock verification or replace the external quality gate.
See the earlier two-fixture external gate output in redwork-engine.md.

Policy calculation regression suite: four tests (all pass, middle-level failure,
generation failure, missing/duplicate matrix entry), `Ran 4 tests ... OK`.
The runnable command ends with `SWEEP_COMPLETE 100 cases`; no CI or physical
sewout result is implied by this engine-only measurement.
