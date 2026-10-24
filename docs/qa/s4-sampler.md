# S4 third-round sewout sampler

Reproduce on `codex/s4` (JDK 17; existing Python/Pillow, no installation):

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 python3 tools/redwork/make-s4-sampler.py
```

The script draws two original black/white 192px line drawings (flower, house),
uses the actual S4 Wu → Potrace → model DefaultTatami（타타미+바탕 땀） → S1 pipeline with upstream
stitch defaults, then calls the existing worker `tools/make_sewtest_sampler.py`.
No personal photos, downloaded artwork, fonts or model-generated bitmap assets.
Each motif's maximum sewn dimension is 30mm; input physical scale is calibrated
by regeneration, never by scaling finished stitches. The original black bands are filled as regions with holes; Potrace boundaries are
not emitted as a pair of running outlines. No skeleton/centerline inference.

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

Measured: flower **29.1×30.0mm / 1,172 stitches**, house **29.4×30.0mm / 1,268 stitches**.
Composed **63.5×30.0mm / 2,440 stitches / 1 trim / 1 color**, no color changes or STOPs
(worker writer/readback assertions). Estimated **376 seconds (6 min 16 sec)** using worker's
`2440 / 400 spm * 60 + 1 encoded trim * 10 seconds`; setup excluded, not measured
physical sewout time.

Executed gate (unchanged checker/thresholds):

```sh
/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-sewtest/s4/S4SAMPL.PES \
  --text --style fill --hoop-bounds-mm -35 -35 35 35 \
  --json /tmp/myembro-sewtest/s4/S4SAMPL-gate.json
```

```text
PASS /tmp/myembro-sewtest/s4/S4SAMPL.PES within five-condition checks
REFERENCE_ONLY metrics are retained in S4SAMPL-gate.txt (not blockers)
PASS 1/1 cases passed
```

## Thin-region measurement and automatic exclusions

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/redwork/sweep-s4-fill.py
```

The **same two drawings** use five raster sampling levels (128/160/192/224/256px)
and five scripted source widths (1/2/4/8/10px at 192px reference size, secondary
house strokes use 80%, rounded to >=1px). These are offline fixture axes, not new
user controls. Bilinear sampling then bitmap physical scaling keeps the maximum
geometry approximately 30mm. Both drawings must generate successfully, complete
SewoutCheck under APP_GENERATED with zero blockers, **and** independently PASS the
external byte gate for a pair to enter `allowedPairs`. No interpolation over
untested values; failures enter `excludedPairs`. Generation failures stay counted.

Output `/tmp/myembro-sewtest/s4/fill-sweep/`: `inputs.json`, `results.json`,
`quality-gate.{json,txt}`, `detail-stroke-policy.json`, individual PNG/RGB/PES.
Executed result: **50/50 pass**, 25 allowed pairs, **0 excluded pairs**. This only
measures the two synthetic motifs, not all photographs; JSON is not applied as a
universal UI range. The photo screen still blocks actual generated output on
SewoutCheck blockers. No unsafe fallback to outline running is used.

Model backend source snapshot `ea5fd8d63f30db770f626ab245f9bc50bbe5bf31` is reused
byte-for-byte (8 files); defaults and holes/underlay ordering are covered by the
original three DefaultTatami tests. S4 tests cover two end-to-end PES exports
(v1/v6 with generated policy) plus a ring's white center and filled interior.

PNG inspected; actual sewout remains for the user. No application UI or production
algorithm changes in this sampler unit.
