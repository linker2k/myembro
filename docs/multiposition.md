# Multi-position frame splitting (Phase 3a)

Coordinates use 0.1mm units, +y points downward, and the origin is the project center. Each position's actual sewing area is
at most 100×100mm; the overall frame size does not increase the area that can be sewn in a single position.

## Integrator: connect the SA434 position contract

`engine.edit.MultiPositionSplitter` accepts a list of
`SewingPosition(id, name, originX, originY, width, height)`.
A position's `originX/Y` is the center of its 100×100 sewing window in project coordinates.
At the time of this work, `HoopSpec.positions` was not yet present on this branch, so the shared types were not changed
arbitrarily. **Convert the confirmed SA434 position list to this type and pass it in**. No SA434 defaults based on guessed
hardware position spacing are provided. Test centers -35/0/+35mm are synthetic inputs for validating splitting,
not SA434 specifications. The app, hoop UI, and machine settings were not modified.

```kotlin
val positions: List<SewingPosition> = /* 확인된 HoopSpec.positions에서 변환 */
val result = MultiPositionSplitter.split(project, positions) // 또는 split(pattern, positions)
val errors: List<UnassignedStitch> = result.errors
val files = result.writePes("design", PesWriteSettings(PesVersion.V6))
// design_position1.pes, design_position2.pes, ... (비어 있는 위치는 생략)
// files[i].instructions, files[i].metadata, files[i].part.stitchCount, files[i].part.colors
```

Brother recommends Latin letters, digits, hyphens, and underscores for filenames, so position suffixes use ASCII such as `position1`.
Displayed position instructions are in Korean. The base name itself is not automatically converted, so check it with
`Preflight.check` before transfer. File numbers are the 1-based indices of the input position list; omitted empty positions
do not cause renumbering. `writePes` throws if any stitch fits no position, preventing partial export with missing design
content. The error list preserves original command indices and coordinates,
and `sourceStitchCount == parts.sumOf { it.stitchCount } + errors.size`.

Also apply the existing SE400 `Preflight` before exporting each file. Splitting alone does not automatically resolve the 30,000stitch limit.
Actual SA434 mounting positions and design alignment require machine sewout verification.

## Assignment and order

- Processes only up to the first END. Assigns each STITCH to exactly one position and adds no new needle stitches or
  duplicate stitches for overlap sewing. Window boundaries are inclusive.
- Uses dynamic programming to choose the assignment with the fewest position changes among eligible positions.
  Also handles cases where assigning an earlier overlapping portion to the next position lets the entire continuous section
  be sewn in one position. Ties favor staying in the same position, then input position order.
  Time O(stitches×positions²), memory O(stitches×positions).
- Preserves original stitch order per position. After a position change, an original JUMP/TRIM, or a skipped erroneous stitch,
  the next section starts with TRIM/JUMP; each file also ends with TRIM.
  Sections that leave for another position and return are not sewn continuously in the same file.
- Records a thread change when the actual sewing thread at that position changes; STOP stays at the preceding stitch's position.
  `colors` lists threads in color-block order and may contain duplicate entries when returning to the same color.
- Grouping by position may change the overall overlap order across different positions. Display the Korean warnings in the result.
  This is not a scheduler that resolves arbitrary design layer dependencies.
- Splitting assigns needle positions, rather than clipping line segments. It may break continuous segments at unavoidable
  position boundaries and adds no connecting stitches, knots, or sewing allowance. It does not guarantee frame remounting precision.

Each part's `sourceStitchIndices` corresponds to STITCH order. `position.toProject(localStitch)` exactly restores
original coordinates. File coordinates are relative to the corresponding position center. END/jumps/trims are not
included in stitch-count statistics. Input limits are 200,000 STITCH and at most 16 positions.

## Metadata and validation

The returned `pattern.metadata` contains `multiposition.index/id/name/originX/originY/width/height`.
PES v6 additionally saves the position number, name, and origin in standard `name`/`comments` fields. Arbitrary keys
are not saved in PES, and v1 saves only the short PEC label, so retain the result object's instructions as well.

```bash
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test
/path/to/pyembroidery-1.5.1-venv/bin/python tools/check_writer.py engine/build/multiposition-check
```

Tests check that each part of a 100×170 synthetic design fits within 100×100, total stitch counts, original indices, coordinate round trips,
early overlap assignment, jumps/trims/thread changes/STOP, out-of-range errors and blocked output, empty positions, and invalid input.
Each position is exported as v1/v6 and checked with the internal reader and pyembroidery 1.5.1.
