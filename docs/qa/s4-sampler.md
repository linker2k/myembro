# S4 third-round sewout sampler

Reproduce on `codex/s4` (JDK 17; existing Python/Pillow, no installation):

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 python3 tools/redwork/make-s4-sampler.py
```

The script draws two original black/white 192px line drawings (flower, house),
uses the actual S4 Wu → Potrace → Ink/Stitch running → S1 pipeline with upstream
stitch defaults, then calls the existing worker `tools/make_sewtest_sampler.py`.
No personal photos, downloaded artwork, fonts or model-generated bitmap assets.
Each motif's maximum sewn dimension is 30mm; input physical scale is calibrated
by regeneration, never by scaling finished stitches. Thick raster lines trace
both boundaries, following the existing S4 behavior rather than skeletonizing.

Worker composition uses one **Black 40wt** thread, unchanged 5mm gap, and 70mm
packing limit (two 30mm motifs plus the gap cannot fit side by side in 60mm).
All output coordinates fit the central 70×70mm area inside SE400 H100.
The 40wt designation is a sewout thread choice/label; no stitch parameters were
changed to invent thread-weight compensation. Preview numbers are not sewn.

Actual output: `/tmp/myembro-sewtest/s4/`:

- `S4SAMPL.PES`, `S4SAMPL.png`, `S4SAMPL.json` (worker output).
- `FLOWER.input.png`, `HOUSE.input.png`, `.rgb`, individual `.PES` and outline SVG.
- `inputs-report.json`: inputs' hashes, dimensions, stitch/contour counts;
  both actual PES files asserted complete and zero blockers with
  `SewoutOptions(origin=APP_GENERATED, placement=HOOP_COORDINATES)`.
- `S4SAMPL-gate.json`, `S4SAMPL-gate.txt`: independent five-condition checker output.
- `S4SAMPL-provenance.json`: executing commit, clean flag and artifact SHA-256.

Measured: flower **29.2×30.0mm / 165 stitches**, house **29.3×30.0mm / 169 stitches**.
Composed **63.5×30.0mm / 334 stitches / 1 trim / 1 color**, no color changes or STOPs
(worker writer/readback assertions). Estimated **60.1 seconds** using worker's
`334 / 400 spm * 60 + 1 encoded trim * 10 seconds`; setup excluded, not measured
physical sewout time.

Executed gate (unchanged checker/thresholds):

```sh
/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-sewtest/s4/S4SAMPL.PES \
  --text --style running --hoop-bounds-mm -35 -35 35 35 \
  --json /tmp/myembro-sewtest/s4/S4SAMPL-gate.json
```

```text
PASS /tmp/myembro-sewtest/s4/S4SAMPL.PES within five-condition checks
REFERENCE_ONLY UNEXPLAINED_ZERO_RATIO: 9/334
PASS 1/1 cases passed
```

PNG inspected; actual sewout remains for the user. No application UI or production
algorithm changes in this sampler unit.
