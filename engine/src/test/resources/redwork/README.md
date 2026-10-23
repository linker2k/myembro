# Redwork integration fixtures

Two original 64×64 P1 PBM masks drawn mathematically for myembro, 2026, GPL-3.0-or-later:
rectangle x=12..51/y=18..45; disk centered at (31.5,31.5), radius 18 raster pixels.
No personal photographs, downloaded designs or commercial artwork.

Both use white background, black foreground, full-image width 60mm. Background seed is
explicit top-left pixel index 0; no automatic background heuristic is being claimed.

Pipeline: original-C-compared Wu v2 (2 colors) → Potrace 1.16 default MINORITY,
turdsize=2, alphamax=1, opticurve=1/opttolerance=0.2 → shared SvgPath flattening
at 0.01 SVG px (same value as StrokeFixtureTest) → Ink/Stitch v3.3.0 Stroke defaults
(2.5mm running, 0.2mm tolerance, repeats=1, bean=0, default half-stitch ties) →
GeneratedPatternProcessor.fromGroups (S1) → PesV6Writer.

Run the two engine tests and frozen external five-condition gate:

```sh
tools/redwork/check-fixtures.sh /tmp/myembro-quality-gate/quality-gate
```

Artifacts `/tmp/myembro-port-out/editor/redwork/`:
PBM input, contour SVG, PES, SewoutCheck findings, matrix (commit/input/output SHA-256),
quality-gate stdout and JSON. The script asserts gate success through its exit status;
JUnit asserts complete SewoutCheck, no blockers, single-color valid PES and repeatability.
Current generated-output density limit is >15/0.5mm per POLICY.md (option threshold 16).
The existing SewoutCheck lock warning is distinct from the frozen external functional lock
recognizer; neither warning nor informational unknown machine facts is hidden or removed.

This traces region boundaries, not stroke centerlines: an outlined raster band yields both
its inner and outer contours. No automatic skeletonization, bean mode, shape routing,
app wiring or release/stage advance is included in this first unit.
