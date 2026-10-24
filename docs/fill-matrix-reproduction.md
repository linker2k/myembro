# S4 fill official-matrix reproduction

> **This document is now archived.** Per the integrator's 2026-10-24 direction, stop reproduction/repeated execution of the 225 cases below.
> New verification checks stitch counts/shapes for no more than 10 representative cases per feature.
> Historical statistics are reference records, not new blocking conditions. Report after 30–60-minute units and await further instructions.

Keep FillStitch in preview until S4's turn. Do not change existing TatamiFill/OutlineFill, UI, or
original lock defaults. Start US-6 after the S2 object-stitch tab (`docs/ui-stories.md`).

## Inputs and execution

- Frozen matrix: `/tmp/myembro-quality-gate/matrix-inkstitch.json`
- SHA-256: `7ce25a2faa933047030f804b0512a6cc476fb9a2437e98a014a5aa07966221ee`
- Owner: `owner=embro-model`, `feature=fill_stitch`, **225 cases**.
- Actual SVGs: `/tmp/myembro-inkstitch-oracle/inputs/fill_stitch--*.svg`.
- 7 shapes × 8 sizes (4–150mm) × 4 profiles = 224 cases + 1 negative input with 0 spacing.
- Default output: `/tmp/myembro-port-out/model/<case-id>.pes`; full input list/results/hashes: `results.json`.
- Use JDK17 and the existing Gradle environment. Do not run original Ink/Stitch/extensions/pytest or install dependencies.

From a clean checkout of the frozen commit:

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
python3 tools/reproduce_fill_matrix.py \
  --matrix /tmp/myembro-quality-gate/matrix-inkstitch.json \
  --owner embro-model \
  --oracle-input-dir /tmp/myembro-inkstitch-oracle/inputs \
  --output-dir /tmp/myembro-port-out/model \
  --results-json /tmp/myembro-port-out/model/results.json \
  --planner-source-root /path/to/worker/engine/src/main/kotlin
```

The shared planner source root is needed only while S1 remains unmerged. The runner reads and
copies required sources into `output-dir/dependencies/source/` and compiles **that snapshot**.
Per-file SHA-256 values and original paths are recorded in `dependencies.json` and the result
manifest. This external WIP is not claimed as part of the model commit. If already on main, the
argument is unnecessary. Current dependencies are LockStitches, StitchPlanBuilder, SewingOptions,
GeneratedPatternProcessor, and PathEndPoints. GeneratedPatternProcessor is compiled because of
type dependencies but is not called by the runner.

`--commit FULL_SHA` is required for archives without .git. In a Git checkout, it must match the
actual HEAD, and dirty states are rejected. `--case-filter REGEX` is for debugging subsets;
submission runs use the default `.*` and record **all 225 cases**. Generation failures are also
recorded as ERROR, and the process exits abnormally at the end. Positive cases are not hidden
by omitting or rejecting them.

## Parameter/output mapping

| Matrix | Actual input/engine |
|---|---|
| `angle_deg` | SVG `inkstitch:angle` → FillParameters.angle |
| `row_spacing_mm` | Same name, original getter's minimum 0.1mm adjustment |
| `max_stitch_length_mm`, `staggers` | Same names |
| `underlay` | SVG `inkstitch:fill_underlay` |
| Global metadata | min_stitch_len_mm=0.1, collapse_len_mm=3.0 → shared StitchPlanBuilder |
| Coordinates | Original v3.3.0 `get_viewbox_transform`: **T(-viewBox.x,-viewBox.y) @ S**, each value to 6 places with `%f` |
| shape | Flatten to 0.1px after transformation, use the largest path as shell/the rest as holes, make_valid, precision, area >3px² |
| Threads/writer | Actual SVG fill RGB → **PES v1** (same PEC palette approximation as official output) |

Input SVG is read by a separate fixture reader, not claimed to be the full S5 SVG importer.
Unsupported elements/CSS/parameters produce explicit errors. The runner checks matrix parameters
against prepared SVG attributes and records separate hashes for original input and prepared SVG.
It does not remove viewBox translation, which affects the original grating phase, or replace it
with standard SVG S@T.

Groups generated in the original form → `StitchPlanBuilder.fromGroups` → raw adapter → PES.
The common postprocessor is not bypassed to write raw row-start duplicates directly to the file.
No separate postprocessing is added to match PE-Design lock-byte patterns.

**Negative-input note:** `zero-row-spacing` is REJECTED in the matrix, but official v3.3.0
adjusts 0→0.1mm and generates PES. This runner also records GENERATED. Rather than hiding the
discrepancy by modifying the matrix/reference or faking rejection, it is submitted for Codex review.

## Separating numeric observations from independent decisions

Official equivalence decisions belong to Codex's fixed comparator/criteria and review. If
`export_coordinates.py` stops with `FROZEN_GATE_CHANGED: gate.py` during recalibration, do not
change reference hashes or bypass checks.

In the meantime, the following produces **reference observations**. It requires existing
pyembroidery and system numpy/scipy; nothing is installed. Point `--pes-python` to the existing
pyembroidery 1.5.1 interpreter.

```sh
python3 tools/measure_fill_matrix.py \
  --results-json /tmp/myembro-port-out/model/results.json \
  --pes-python /path/to/existing/pyembroidery-venv/bin/python
```

`observations/measurements.json` records counts, symmetric needle Hausdorff, bbox differences,
length distributions, order distances, short stitches/0-length/repeated coordinates on both sides,
hole intrusion, and material-coverage samples. Lock/underlay tags cannot be recovered from PES.
Short stitches are **positive lengths of all STITCH commands, including locks**, not body-only
statistics. This file is not a gate PASS/equivalence decision.

Hole observations use a 30–70% rectangular hole normalized by translating both bbox centers.
They are diagnostics using a 0.05mm inset for quantization, 61×61 material-area samples,
≤0.2mm segment samples, and the `row_spacing/2+0.15mm` distance criterion. They are not actual
fabric coverage or a complete area proof. Needle/sewing-line intrusion inside the hole and
**missing coverage in material outside the hole** are recorded separately.

The initial 8 cases had large sewing-order differences for hole/10mm diagonal even when counts
were close. Equivalence to the original is not decided by simply blocking short stitches. Official
8mm hole dense output itself has positive stitches below 0.3mm, and valid locks/revisits increase
same-point accumulation. Full numbers and blocking causes are reported in subsequent result records.


## 1st baseline and graph-copy correction

A clean checkout of frozen commit `088b3caedff0330e07988e024687270b15256972` generated
225/225 PES files in 1min 52s. Reading the original uses only pyembroidery and is a reference
observation. Input/output hashes and results are stored under `/tmp/myembro-port-out/model-baseline-088b3ca/`.

| Scope | Cases | Stitch count ±5% | needle Hausdorff ≤0.2mm | Both conditions | Order p95≤0.3mm·maximum≤1mm |
|---|---:|---:|---:|---:|---:|
| All (including 1 negative) | 225 | 191 | 95 | 93 | 83 |
| rectangle | 32 | 32 | 29 | 29 | 29 |
| circle | 32 | 31 | 6 | 6 | 7 |
| hole | 32 | 31 | 3 | 3 | 0 |
| sharp_corner | 32 | 14 | 0 | 0 | 0 |
| narrow_tip | 32 | 32 | 28 | 28 | 23 |
| self_intersection | 32 | 19 | 1 | 0 | 0 |
| crossing_strokes | 32 | 32 | 27 | 27 | 24 |

This table is not an overall equivalence decision. Independent criteria such as lock/control-
command review remain outstanding.

Static comparison found a difference: `FillRouting.Graph.copy` copied in global edge-insertion
order. The original's [NetworkX MultiGraph.copy](https://networkx.org/documentation/stable/_modules/networkx/classes/multigraph.html#MultiGraph.copy)
reinserts bidirectional edges in **node → neighbor → key** order. Changing that order changes
`pick_edge`'s first outline choice and the order of long sections for the same set of rows.
An A/B/C 3-node regression confirmed the order, and copying was aligned with it. This corrects
source behavior; it is not a change to match PE-Design output patterns. Full replay results after
the correction are added in the subsequent record.

Observed hole/short-stitch points:

- Material-coverage samples in all 32 hole cases were 100% on both sides, and both had 0 needles inside the hole inset by 0.05mm. Current samples therefore provide no evidence that broad unfilled areas are the main cause.
- Sewing lines that cut corners due to running tolerance/quantization and cross the hole inset occurred in 22 official cases and 18 port cases. This observation does not mean needles penetrated the hole.
- Across all cases, 74 had more positive <0.3mm stitches in the port than in the original; 6 were hole cases. This includes valid locks/revisits and short row/travel end stitches, so this statistic alone does not establish an error.
- Candidate causes for `short` are row/travel connections/order changes, JTS/GEOS repair/boundary order, and quantization. Raw start duplicates in `stitch_row` also exist in the original and are handled by the final 0.1mm filter.
- Remaining differences include discarding the current node when an explicit start/end lies in a hole not touching a row, and raising an error when the underpath is exhausted and no path remains instead of the original straight-line-candidate→polygon-clamp recovery. No generation failed in these 225 cases, so these are not asserted to be observed failures. They are S4 items to address through separate hole-command regressions.


## Submission results after correction

Generation-code commit: **`3412ebbdddd2e8a58e4526b7cec4b35add7d511d`**.
The full set was generated twice from a clean `/tmp/myembro-model-fill-core-check` checkout.
225/225 cases generated, **225/225 PES files byte-identical**. Runs took 1min 42s/1min 56s,
respectively. PES files were read with the internal PesReader and pyembroidery, and every PES
SHA-256 in the manifest was rechecked.

| Scope | Cases | Stitch count ±5% | needle Hausdorff ≤0.2mm | Both conditions | Order p95≤0.3mm·maximum≤1mm |
|---|---:|---:|---:|---:|---:|
| All (including 1 negative) | 225 | 193 | 128 | 125 | 125 |
| rectangle | 32 | 32 | 32 | 32 | 32 |
| circle | 32 | 31 | 7 | 7 | 8 |
| hole | 32 | 32 | 27 | 27 | 26 |
| sharp_corner | 32 | 14 | 1 | 0 | 0 |
| narrow_tip | 32 | 32 | 30 | 30 | 31 |
| self_intersection | 32 | 20 | 1 | 0 | 0 |
| crossing_strokes | 32 | 32 | 29 | 29 | 28 |

**Counts satisfying some criteria in these columns are not an overall equivalence/PASS decision.**
There is not yet a Codex decision covering length distribution, lock boundaries, control commands,
and original-source static review as well.

- Main results: `/tmp/myembro-port-out/model/results.json`
- Repeated-run results: `/tmp/myembro-port-out/model-repeat/results.json`
- Full measurements: `/tmp/myembro-port-out/model/observations/measurements.json`
- Summary: `docs/fill-matrix-summary.json` and `/tmp/myembro-port-out/model/summary.json`
- Pinned hashes of shared S1 sources: `/tmp/myembro-port-out/model/dependencies.json`
- Immutable snapshot source root for reproduction:
  `/tmp/myembro-port-out/model-baseline-088b3ca/dependencies/source`

The Graph.copy correction changed rectangle10 diagonal's order p95 from 4.6 to approximately
0.141mm and hole10 horizontal's from 9.16 to approximately 0mm. The latter's stitch counts also
aligned from 143/140 to 143/143. This resulted solely from correcting source-graph copy order,
without shape replacement or adjustments to PE-Design locks.

After correction, coverage samples in the 32 hole cases still had a minimum of 100% on both
sides, with 0 needles inside the inset hole on either side. Sewing lines partially crossing holes
occurred in 22 official cases/23 port cases; running tolerance and quantization need separate
further review. Positive stitches below 0.3mm increased in 73 cases/decreased in 95/unchanged
in 57 compared with the official output. Decreases are not assumed to be quality improvements,
nor are all increases assumed to be regressions.

Priorities for further S4 equivalence work:

1. **Self-intersection repair/fragment·ring order:** of the 32 cases outside count ±5%, 18 are sharp_corner and 12 self_intersection. First compare JTS GeometryFixer/precision against GEOS make_valid linework/order. The remainder are 1 circle case and 1 negative 0-spacing case.
2. **Circle flattening/boundary traversal:** counts are mostly close, but Hausdorff/order differences remain. Compare samples/boundary projections of original cspsubdiv and current SVG arc flattening.
3. **Output quantization:** original `lib/output.py` uses a px→0.1mm encoder after moving to the bbox center. The current raw adapter quantizes absolute px with Math.round. Even with similar counts and ordering of 35,306 stitches for the same rectangle150 dense case, short stitches numbered 669 official/821 port. A separate probe is needed to measure how much translation/rounding contributes. Do not delete short stitches merely to match numbers without reviewing original output policy.
4. **Additional hole-command regressions:** rowless rings and recovery after underpath exhaustion are the unverified differences recorded earlier.

Verification: **394 regular engine tests** and **410 preview tests** combined with frozen S1 passed.
The official comparator's coordinate export stops at `FROZEN_GATE_CHANGED: gate.py` during
recalibration, so no pass is declared. Reproduction commands/fixed sources/PES/reference numbers
are submitted to Codex for review. UI and replacement of existing fill remain deferred until
S4 user review.
