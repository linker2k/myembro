# Ink/Stitch parallel porting contract and validation

## Pinned source

- Shared baseline: **Ink/Stitch v3.3.0**, commit **`b0edd96311ece82ee48816dc93466274b12c2a9c`**.
- Official stable release: https://github.com/inkstitch/inkstitch/releases/tag/v3.3.0 .
- Source: https://github.com/inkstitch/inkstitch/tree/b0edd96311ece82ee48816dc93466274b12c2a9c .
- embro-letter's current new ports also use this commit. Do not confuse it with the
  development commit `16e192849e619399ce8fdefb4c4d249dacdb4db1` in the earlier draft.
- In `/tmp/myembro-inkstitch-source`, use `git show <고정커밋>:<path>` for **read-only** access.
  Do not run Ink/Stitch/Inkscape/Python modules or upstream tests, or install upstream dependencies.
- Ported files record SPDX, the upstream path, pinned commit, and contributor copyright. The full GPL text is
  bundled at `engine/src/main/resources/licenses/inkstitch/LICENSE`.
- Replace Shapely with the existing JTS 1.20.0 (EDL-1.0 selected). Add no NetworkX/NumPy/Python runtime dependencies.

## Shared contract — owned by embro-worker

The root package is `io.github.linker2k.myembro.engine.inkstitch`. Keep the upstream directory names
`stitch_plan`, `elements`, `stitches`, `lettering`, `utils`, and `svg`.

### Coordinates and units

**`Stitch.x/y`, `utils.Point`, and coordinates inside generation algorithms use SVG px (96dpi), +y downward**.
Use `svg.PIXELS_PER_MM` / `svg.Units.PIXELS_PER_MM = 96 / 25.4`.
The existing `engine.geometry.Point` and `EmbPattern.Stitch` use **0.1mm**; do not mix them.

Public SVG `_mm` values and group `*Mm` fields remain in **mm**. Convert once with
`Units.mmToPixels` before algorithm comparisons. Do not mistake internal upstream constants such as 0.01px/0.5px for mm.
Quantize px→0.1mm only in `EmbPatternAdapter`, independently of mm/in UI units.

### Kotlin API

```kotlin
// stitch_plan
Stitch(x: Double, y: Double, tags: Set<String> = emptySet(),
       jump: Boolean = false, trim: Boolean = false, stop: Boolean = false,
       colorChange: Boolean = false, minStitchLengthMm: Double? = null,
       end: Boolean = false)

fun interface LockStitch {
    fun stitches(stitches: List<Stitch>, position: LockPosition): List<Stitch>
}
enum class LockPosition { START, END }

StitchGroup(color: EmbThread? = null, stitches: List<Stitch> = emptyList(),
            trimAfter: Boolean = false, stopAfter: Boolean = false,
            lockStart: LockStitch? = null, lockEnd: LockStitch? = null,
            minStitchLengthMm: Double? = null, minJumpLengthMm: Double? = null,
            forceLockStitches: Boolean = false)
ColorBlock(color: EmbThread? = null, stitches: List<Stitch> = emptyList())
StitchPlan(colorBlocks: List<ColorBlock> = emptyList(),
           metadata: Map<String, String> = emptyMap(), warnings: List<String> = emptyList(),
           originalThreadPalette: List<EmbThread>? = null)

// elements
fun interface EmbroideryElement {
    fun toStitchGroups(previousStitch: Stitch?): List<StitchGroup>
}

EmbPatternAdapter.fromEmbPattern(pattern: EmbPattern): StitchPlan
EmbPatternAdapter.toEmbPattern(plan: StitchPlan): EmbPattern
```

- `tags` preserve opaque upstream tags. `lock_stitch` is the upstream lock tag.
  Interpret the `jump`, `trim`, `stop`, `color_change`, and `end` tags as OR with their corresponding Booleans.
  `lock` is a caller convenience alias; query both through `isLock`.
- Constructing a `StitchGroup` does not itself generate stitches/locks or run filters. Default locks are null;
  the element layer creates and supplies them from SVG `ties` and `lock_start/end` attributes.
- `lockStitches(position, disableTies)` returns an empty result when globally disabled or given fewer than 2 stitches.
- When combining `ColorBlock`s, compare only RGB, as upstream does (`EmbThread.sameColor`).
  Preserve `sameThread`/full data equality when retaining imported thread metadata.
- `StitchPlan` is a passive data contract. Postprocessing/locks/jumps/normalization require separate explicit calls.
- For generated plans (`originalThreadPalette == null`), the adapter supplies COLOR_CHANGE between color blocks and a final END.
  It does not duplicate existing boundary commands.
- An imported plan's `originalThreadPalette` is meaningful even as an empty array. A raw adapter round trip
  preserves all original commands (including records after END), the original thread list, metadata, and warnings unchanged.
  Do not add a global writer hook that automatically regenerates existing PES/DST or adds locks.

### SVG attribute mapping

New element parameter DTOs use SVG names unchanged. Only the shared data contract above uses Kotlin camelCase.

| SVG attribute | Shared group field | Unit/default |
|---|---|---|
| `trim_after` | `trimAfter` | false |
| `stop_after` | `stopAfter` | false |
| `lock_start`, `lock_start_scale_mm`, `lock_start_scale_percent`, `lock_custom_start` | `lockStart` factory input | half_stitch / 0.7mm / 100% / empty path |
| `lock_end`, `lock_end_scale_mm`, `lock_end_scale_percent`, `lock_custom_end` | `lockEnd` factory input | half_stitch / 0.7mm / 100% / empty path |
| `min_stitch_length_mm` | `minStitchLengthMm` | null; applying the global 0.1mm is the postprocessor's responsibility |
| `min_jump_stitch_length_mm` | `minJumpLengthMm` | null; applying the global collapse 3mm is the postprocessor's responsibility |
| `force_lock_stitches` | `forceLockStitches` | false |

## Change stages and validation status

The first commit, `dc38aac`, added **only the new contract and adapter**. It did not change existing generated output.
Review subsequent locks/minimum lengths/commands/Stroke features and connections to existing generation paths in individual commits.
Distinguish external gate BLOCK results from passing tests; do not report such features as ready to merge.

| Feature | Upstream files | Tests/external gate |
|---|---|---|
| Passive contract/units/adapter | `lib/stitch_plan/{stitch,stitch_group,color_block,stitch_plan}.py`, `lib/elements/element.py`, `lib/utils/geometry.py`, `lib/svg/units.py` | `StitchPlanContractTest`: all commands/coordinate ranges/thread metadata/existing 6 inputs→identical PES bytes/fixed SVG→PES. Independent gate results are added to the validation records below |
| Locks/common postprocessing | `lib/stitch_plan/lock_stitch.py`, `stitch_plan.py`, `color_block.py`, `lib/elements/element.py` | 10 types+default SVG, ties/scale/commands/minimum/anchor/regeneration regressions; release BLOCK |
| Running/manual/zigzag | `lib/elements/stroke.py`, `lib/stitches/running_stitch.py`, `lib/utils/prng.py` | Analytical coordinates/repeats/maximum length/deterministic randomness/fixed SVG 4 cases; release BLOCK |
| Ripple | `lib/stitches/ripple_stitch.py`, `lib/stitches/tatami_fill.py` | target/guide/grid/clip/hole/graph analytical regressions/fixed SVG 2 cases; awaiting Satin provider integration |

Upstream `tests/` has no coordinate-expectation unit tests for these lock/running/Ripple algorithms.
Do not claim analytical fixtures read directly from formulas are goldens compared against upstream execution.
Port the trim/stop OR attribute tests from `tests/test_clone.py` and the deterministic repeated output
and output-changing-with-trim properties from `tests/test_output.py` to the corresponding subsequent layer tests (use supported PES instead of JEF).

Use `/tmp/myembro-quality-gate/check-release --matrix <JSON> --json <report>` for external decisions.
Do not modify upstream/the gate/thresholds. The adapter SVG smoke validates only manual path input/output
through the public contract; it does not mean all new generation algorithms or mobile/actual sewout quality passed.
Submit a separate SVG fixture/PES matrix for each algorithm.

## Differences

| Location | Difference from upstream | Reason/limitation |
|---|---|---|
| Shared DTOs | Kotlin data/read-only List instead of mutable Python objects; some upstream field names use camelCase | Kotlin public contract; SVG parameter DTOs retain upstream names |
| `Stitch.minStitchLengthMm` / group `*Mm` | Public values stored in mm instead of upstream internal px | Retain SVG attribute units and convert explicitly at boundaries |
| `Stitch.end`, plan metadata/warnings/originalThreadPalette | Extensions beyond the upstream plan | Preserve END/palette/read diagnostics in existing EmbPattern raw round trips |
| px→0.1mm adapter | Existing app coordinate grid, Java Math.round (negative halves round toward +∞) | Connect to the existing PES integer API; retain px coordinates until the adapter |
| Command flag combination | Expand multiple flags at one location into commands in JUMP→TRIM→STOP→COLOR_CHANGE→END order | Existing single Command enum limitation; do not confuse ordinary STITCH with control commands |
| Existing UI generation path | No connection in the first contract commit; subsequent connections use an explicit generated bridge | Do not automatically change original locks/commands in imported PES/DST |

### Validation record for the first contract commit

- `StitchPlanContractTest`: **7 tests passed**. After a raw adapter round trip, the 6 existing PES/PEC/DST fixtures have
  identical commands/coordinates/thread information and existing `PesWriter` output bytes.
- Excluded algorithm sources being written in parallel through `/tmp/myembro-worker-contract-only.gradle` and
  ran `:engine:test` with only the first commit's contract sources. This temporary filter is not included in the product/repository.
- Fixed input `engine/src/test/resources/inkstitch/contract.svg` → `engine/build/inkstitch-contract/contract.pes`.
- Independent `check-release` decision: **PASS 1/1**, exit 0.
  Recorded in `/tmp/myembro-worker-contract-matrix.json`, `/tmp/myembro-worker-contract-gate.json`.
- This decision covers **only the manual SVG adapter smoke**. Do not extend it to claim that unconnected locks/Stroke/Ripple
  or all font releases have passed.


## FillStitch port — embro-model

### Scope and call boundary

In v3.3.0, the former `auto_fill.py` was renamed to **`lib/stitches/tatami_fill.py`**.
`stitches.TatamiFill` / `Fill` / `FillRouting` handle this tatami path.
Do not change existing `digitizing.TatamiFill` or `lettering.OutlineFill` calls.
The new `elements.FillAdapters` is an adapter for optional switching after approval.

- All JTS inputs and `FillPoint`s use **SVG px**. Each low-level function's `*Px` arguments also use px.
  `_mm` attributes in `FillParameters` and method-specific DTOs use mm; `angle` uses degrees.
  Low-level `angle` in `TatamiFill.fill` / `Fill.intersectRegionWithGrating` uses radians, as upstream does.
- `FillGeometry.makeValid` repairs self-intersections with JTS GeometryFixer and preserves polygon components.
  It does not use only `buffer(0)` and discard one side of a bowtie. Holes remain separate outlines.
- `FillParameters.fromSvgAttributes` reads shared `inkstitch:*` names/defaults.
  Method-specific guide/gradient/tartan resources accept typed inputs after transforms/color interpretation.
  A full SVG document/Inkex object tree parser is outside this fill engine's scope.
- `elements.FillStitch.toStitchGroups(previousStitch)` passes underlay→top, color order,
  start/end, group attributes, and lock definitions through the shared contract. Actual filter/command generation
  is the responsibility of embro-worker's explicit `StitchPlanBuilder.fromGroups` call.
  There is no default writer hook or hook that regenerates existing imported designs.

### Upstream defaults

| Attribute | Default/upstream handling |
|---|---|
| `fill_method` | `tatami_fill` (`auto_fill` is a compatibility alias) |
| `row_spacing_mm` / `max_stitch_length_mm` | 0.25 / 4.0, each at least 0.1mm when calculated |
| `angle` / `staggers` / `skip_last` / `expand_mm` | 0° / 4 / false / 0mm |
| `underpath` / `gap_fill_rows` | true / 0 |
| `running_stitch_length_mm` / `running_stitch_tolerance_mm` | 2.5 / 0.2; use 0.2 from the upstream getter (different from the UI's 0.1) |
| `fill_underlay` / `underlay_underpath` | true / true |
| `fill_underlay_angle` | If unspecified, top angle+90°; for gradient, negative gradient direction |
| `fill_underlay_row_spacing_mm` | If unspecified/0, top spacing×3 |
| `fill_underlay_max_stitch_length_mm` | If unspecified/0, top maximum length |
| `fill_underlay_inset_mm` / `fill_underlay_skip_last` | 0 / false |
| `pull_compensation_mm` / `pull_compensation_percent` | 0 on both sides; a single value applies to both sides |
| `min_stitch_length_mm` / `min_jump_stitch_length_mm` | null; common postprocessing defaults 0.1mm / 3mm |
| `ties` / `lock_start` / `lock_end` | 0 (both ends) / `half_stitch` / `half_stitch` |

### Upstream files and test mapping

| Feature | Upstream files | Kotlin checks |
|---|---|---|
| grating/stagger/legacy | `lib/stitches/fill.py` | Origin-aligned rows, negative modulo/round-even, changing end spacing, holes, flip/reverse, raw start duplicate tags |
| tatami/Euler/underpath/start/end | `lib/stitches/tatami_fill.py` | Visit every row, determinism, hole detours, jumps between disconnected regions, compensation, boundary splits/duplicate-edge direction regressions |
| running/smooth/clamp | `lib/stitches/running_stitch.py`, `lib/utils/{smoothing,clamp_path}.py` | Corners/length/tolerance, U-shaped external excursion, hole corners, endpoint preservation |
| underlay/element parameters | `lib/elements/fill_stitch.py` | Multiple angles, inset, spacing/max inheritance, collapse fallback, angle tokenizer, expand order before top |
| contour | `lib/stitches/contour_fill.py` | inner/single/double, direction, holes, branches, bean/repeats, smoothing |
| guided | `lib/stitches/guided_fill.py`, `lib/stitches/utils/connect_geometries.py` | copy/offset/buffer, minimum-area rotated box, anchor, flexible/stagger, holes, smoothing, fallback |
| circular | `lib/stitches/circular_fill.py` | Fermat, upstream fallback for small shapes, holes, target/end spacing, start/end, bean/repeats |
| linear gradient | `lib/stitches/linear_gradient_fill.py` | Square-root row distribution, color stop, missing gradient, holes, start/underlay connection length |
| tartan | `lib/stitches/tartan_fill.py`, `lib/tartan/utils.py` | warp/weft, color order, width/scale/rotation, running stripes, herringbone, bowtie connection length |
| meander | `lib/stitches/meander_fill.py`, `lib/tiles.py`, `lib/utils/prng.py` | Load 75 tiles, SVG transforms, MST/branches, connectivity, seed, known BLAKE2s vectors, clip/smooth/zigzag/repeats |

The gradient/tartan inputs and expected colors from `tests/test_threads_color.py` were ported to the corresponding Kotlin tests.
Other than that, upstream `tests/` has no coordinate golden unit tests for these fills. Fixed expectations and
property checks read from formulas/branches are not goldens compared against upstream execution.
Upstream code was only read, not executed; no dependencies were installed.

### Fill differences

| Location | Difference from upstream | Reason/limitation |
|---|---|---|
| Shapely/GEOS | Use JTS GeometryFixer, buffer/overlay/offset | Curve approximation/small topology fragments/order may differ numerically from GEOS. No claim of identical byte output |
| NetworkX graph | Kotlin multigraph/BFS/Dijkstra/Hierholzer + JGraphT minimum-weight perfect matching | LGPL-2.1-or-later selected. Tied matching/neighbor order may differ from the NetworkX version |
| Input/resource limits | Finite-number validation, maximum rows/vertices/stitches/repeats/matching workload | Prevent unbounded memory/CPU use on pure JVM. Reject large inputs with explicit exceptions |
| Graph precision | Normalize vertices to 1e-6px; clamp buffer also 1e-6px | Prevent boundary leg deletion and long connecting stitches. Slight differences from upstream snap/GEOS precision |
| Start/end insertion | Do not create virtual fill edges on independent rings untouched by rows | Prevent artificial connections across holes |
| Unreachable travel | Error instead of straight-line fallback | Do not produce unmarked sewing across holes/outside regions |
| Travel clamping | Connect the whole external excursion along the shortest corresponding boundary arc, preserving both ends | Exact projection instead of upstream 0.01px boundary buffer clipping. Fix loss of hole-only intersections and terminal point intersections |
| Collinear running sleeve | Use previous vertex for a nearly 0 denominator | Prevent infinite/oversized coordinates at 0 tolerance or nearly parallel lines |
| Disconnected shapes/explicit jump | Request a needle-up boundary from the common planner through forced locks on the previous group | Prevent sewing across disconnected regions even when closer than the 3mm collapse threshold |
| Random-length stitches | `enable_random_stitch_length=true` explicitly unsupported | Deterministic fill and meander seeded paths implemented. Random running-length distribution requires a later port |
| Guided minimum rotated box | Use JTS MinimumAreaRectangle, omit random coordinate perturbation | Keep reproducible coordinates; differs from upstream 0~0.01px jitter used to avoid GEOS issues |
| Guided self revisit | Clip each traversal segment instead of projecting linearly once | Prevent missing paths that revisit the same point |
| staggers=0 | Treat guided division by 0 as 1 | Handling for valid output/explicit validation |
| Tartan/gradient input | Typed palette/gradient, interpreted CSS colors/geometry | Upper importer handles SVG resource/CSS/JSON text interpretation |
| Tartan herringbone | Preserve single-row blocks | Fix upstream loop-variable leakage that makes single rows disappear |
| Circular small shapes | Return an unclipped circle as upstream does when spacing exceeds radius | Preserve upstream behavior. May exceed bounds; requires gate/hoop checks |
| Adapter | Explicit native 0.1mm or mm→px conversion. Reject nonuniform row offset/AUTO satin/border | Do not silently change fill semantics. Optionally porting the existing edge-running underlay differs from the new tatami underlay |

### Fill quality submission method/current decision

Fixed SVGs: `engine/src/test/resources/inkstitch/fill/{rectangle,hole,concave,bowtie}.svg`.
Use only public self-created fixtures; do not commit user PES/font materials.
`exportFillQualityFixtures` declares 14 modes×4 shapes×12/24mm = **112 cases**,
and generates through actual element→common postprocessing→PES v6. Do not exclude failed generations from the matrix.
Put `method:shape` IDs in the legacy gate schema's `fonts` axis. Do not claim font validation.

```sh
# Normal committed engine (independent core):
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test
# Explicit integration candidate; provide the shared planner source root until it is merged:
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew \
  -I tools/inkstitch-fill-preview.gradle \
  -PfillPlannerSourceRoot=/path/to/worker/engine/src/main/kotlin \
  :engine:test :engine:exportFillQualityFixtures
/tmp/myembro-quality-gate/check-release --matrix /tmp/myembro-fill-quality/matrix.json \
  --json /tmp/myembro-fill-quality/release-report.json
```

The first external gate decision was **BLOCK 112/112**. The upstream default half_stitch lock does not match
its fixed text start/lock sequence; some shapes also exceed short-stitch/density criteria.
Fix the initial candidate's generation failures (odd-vertex matching limit) and long connecting stitches, then revalidate.
Do not change the gate/criteria/upstream defaults to pass. Add final results to subsequent validation records.
Currently **no approval to replace existing fill or merge into main**. Passing JVM unit tests is separate from independent quality approval.


### 2026-10-24 second validation of fill candidates

- The 3 common postprocessing files (`LockStitches`, `StitchPlanBuilder`, `PathEndPoints`) are not yet
  merged into main. A **temporary combination** adding embro-worker's read-only WIP as compile input
  through `/tmp/myembro-fill-preview.gradle` passed **391 engine tests**.
  Distinguish this from a standard `:engine:test` pass. Do not commit this temporary path/worker WIP.
- With upstream defaults, **112/112 PES generated and reread by PesReader successfully**, 0 generation errors.
  Independent `check-release`: **BLOCK 112/112, exit 1**.
- Gate reasons: text start sequence 112, start lock 112, end lock 72,
  body<0.3mm ratio 38, body<0.27mm ratio 33, overall short-stitch ratio 11,
  density 5, unexplained zero 1. Multiple reasons may occur in a single file.
- **LONG_STITCH 0, read failures 0**. Maximum sewing move approximately 4.102mm.
  Fixes eliminated long connecting stitches/missing files, but this is not an overall quality pass.
- Results: `/tmp/myembro-fill-quality/{matrix.json,release-report.json,gate.log}`.
  Commit the full generation integration API/additional integration regressions separately after common postprocessing is merged.
  First provide the fill core independent of common postprocessing in feature-specific commits.


### Distinguishing the committed core from integration candidates

- `99087da`: tatami/legacy rows/routing graph/underlay/common parameters.
- `d373677`: contour/guided/meander and GPL tile data.
- `629d162`: circular/linear gradient/tartan.
- These 3 commits passed **381 tests with standard `./gradlew :engine:test`** in a clean separate checkout.
  No common planner WIP or temporary exclusion filters were used.
- Keep `FillStitch`/`FillAdapters` and element/PES integration tests as **integration candidates** in
  `engine/src/inkstitchPreview{,Test}/kotlin/`.
  They reference common postprocessing APIs not yet present, so are excluded from normal engine build/distribution sources.
  Low-level fill APIs and `FillParameters`/`FillUnderlay` are immediately usable in the normal engine.
- Run candidate validation by **explicitly** injecting a source root into the repository's `tools/inkstitch-fill-preview.gradle`.
  This script does not copy/modify worker files; no external source root is needed if main contains the common 3 files.
  Do not put fixed developer-PC paths in product code.
- Move candidates into the normal source set after the common planner is merged and independent quality approval is obtained.
  Do not interpret candidate storage/381 passing tests as completed public element integration or sewout quality approval.


### Random running helper (for later integration)

`stitches.FillRandomRunning` separately ports upstream `running_stitch.py`'s
`random_running_stitch` / `stitch_curve_randomly` and `utils/prng.py`.
It preserves scalar/list lengths, per-curve seeds, the BLAKE2s counter, a randomized phase for the first stitch,
then phase correction after tolerance shortens a stitch. Coordinate/length arguments use px;
`stitchLengthSigma` is a ratio, 10%=0.1. Added 12 tests for known hashes/straight-line formulas/corners/list cycling/seeds/endpoints.
Do not claim these are coordinate goldens obtained by running upstream.

This helper is **not yet connected to individual fills' random options**. Keep the explicit rejection of
`enable_random_stitch_length=true` in the table above. Implementing a random distribution alone does not mean
all randomized fill/the gate passes. Additionally validate finite numbers/progress/resource limits.

Final core validation: standard `JAVA_HOME=… ./gradlew :engine:test`, **393 tests passed**.
Integration candidates passed **391 tests and generated 112/112 PES** in a separate preview run;
the final external decision remains **BLOCK 112/112**. Also confirmed that the saved report's PES SHA-256 values match
all current 112 candidate files (`docs/inkstitch-fill-gate.json`).


## Transition to staged application — S4 official fill matrix

Follow the S1→S2→S3→S4 order in `docs/ui-stories.md`. Keep FillStitch in preview;
do not start US-6/replacement of existing fill. The earlier BLOCK 112/112 is a **historical record** of
the initial PE-Design-pattern-based gate. The coordinator reported excessive blocking of official Ink/Stitch output too,
and Codex is recalibrating it against functional criteria. Do not change upstream behavior to match that lock sequence.

The reproduction CLI/mapping/code and external S1 source provenance, metrics, and blocking reasons for the fixed
225 official `fill_stitch` SVGs are recorded in [fill-matrix-reproduction.md](fill-matrix-reproduction.md).
Two runs of `3412ebb` generated 225/225 PES with matching bytes; normal engine394/preview410 tests passed.
Matching the upstream NetworkX graph copy's node→neighbor→key insertion order improved count/Hausdorff/order,
but there is no overall equivalence decision yet. External S1 WIP is a separate hash-pinned input;
do not present it as included in the model commit. Independent gate/criteria were not modified.


## Revised S4 scope — failure-free designs (2026-10-24)

Read the coordinator's revised `docs/ui-stories.md` (main worktree). This instruction takes priority
over the earlier complete feature/UI list. The default S4 product path uses **only tatami + default underlay**.
The upstream-default-only entry point accepts geometry/thread, with no user-adjustable parameters.

- Angle 0°, row_spacing 0.25mm, max_stitch_length 4mm, staggers 4.
- fill_underlay=true; default angle top+90°=90°, row_spacing=top×3=0.75mm,
  max_stitch_length inherits top's 4mm, inset 0mm, underlay_underpath=true.
- Locks/minimum lengths/jumps use S1's upstream default postprocessing.
- Automatically fill only regions exceeding satin width, using S2's validated width decision. Do not reuse existing OutlineFill.AUTO,
  custom column extraction, height-based density changes, or arbitrary width constants in the default path.
  Do not define a new width limit before the Codex-calibrated value used by S2 is finalized.
- Compare custom safety branches such as forced locks between disconnected regions in existing ports with upstream behavior
  and separate them from the default path. Do not alter upstream stitches to match PE-Design lock bytes/statistics.
- contour/guided/meander/circular/gradient/tartan and random/bean helpers remain unexposed and deferred to the next version,
  even if engine code exists. Do not build detailed parameter UI, lock selection, sewing settings, advanced object stitch tabs,
  fill sheets, or angle handles.
- Switch existing paths at S4 after S1→S2→S3 review. This is currently preview preparation/validation.

The existing official 225 cases are a **regression baseline** across profiles. Do not expand that claim to
validation of the default product combination alone. A separate oracle fixed to the actual default combination
(angle0,row0.25,underlay=true) is needed. Keep existing matrices/criteria unchanged and confirm an additional defaults bundle with Codex.

Estimated engine preparation: **4–6 hours**:

| Task | Estimate |
|---|---:|
| Defaults-only API/separation of existing heuristic/variant paths | Approximately 1 hour |
| Automatic switching/boundary contract accepting S2's validated over-width regions | 1–2 hours |
| Default-combination regressions/PES/official-output comparisons and difference checks | 2–3 hours |

Waiting for Codex's width criteria/official reference output, staged merge approvals, and actual sewout review is separate.
This instruction update records scope/schedule; it does not start UI exposure or replacement of existing fill.


## Latest validation/work policy — wait after small-unit review (2026-10-24)

This policy takes priority over the earlier large matrices/strict statistical-equivalence procedures.

- Keep only blockers corresponding to actual sewout failure: needle density in 0.5mm cells, consecutive penetrations at the same point,
  presence of thread-start/end locks (regardless of shape), excessive stitch length, file validity, and hoop bounds.
- PE-Design start byte patterns, S0/short-stitch ratios, satin spacing statistics, and length distributions are reference metrics.
  Do not change Ink/Stitch's default minimum-length postprocessing or alter stitches to match statistics.
- Compare upstream using **at most 10 representative cases** per feature, focusing on stitch count/shape.
  Existing 112/225-case results and scripts are historical records; stop rerunning large matrices.
  Do not use historical Hausdorff/order/distribution metrics as mandatory blockers for new releases.
- Final acceptance is the user's actual sewout. Do not claim unit tests/automatic gates confirm actual sewing.
- Limit each work unit to **30–60 minutes**. On completion, commit/push,
  report 3–5 lines of 'work done / results / proposed next task / decisions needed', then await the coordinator's instructions.
  Do not automatically start the next unit.

No model validation is currently running. This wrap-up changes only policy records, not the engine/UI.
The next candidate unit is preparation of a defaults-only tatami+underlay API. Start only on the coordinator's next decision.


### 20-minute unit: prepare default tatami API

Added `elements.DefaultTatami.stitchGroups(region, thread)` to **preview**.
Input PolygonRegion uses existing engine units (0.1mm); returned StitchGroup coordinates use shared SVG px.
No user settings arguments/width decisions/UI/connections to existing paths. The caller collects object groups, then applies S1 once.

Checked letter's `stitches.InkAutoFill` and **call that generator unchanged**. Added no new scanline/graph
algorithm; this default entry point also does not call model's separate TatamiFill/FillRouting.
Instead of using the letter file's existing Settings defaults (0.4mm/3.5mm), explicitly set upstream fill defaults
0°/0.25mm/4mm/4 stagger/underpath=true. Underlay uses90°/0.75mm/4mm.
Each component has underlay→top fill order and S1 default lock definitions.

Validated **only 3 representative cases**: rectangle (default spacing/units/locks), hole (PES reading/no needles inside),
disconnected components/empty input (layer order/empty result). `DefaultTatamiTest`: 3 passed. No full matrix was run.
Extracted InkAutoFill/InkRunning read-only from letter commit `b7106ec` for compilation
(InkAutoFill introduction commit `1cbdd569747032a48075ca16e732629b15555d6c`).
Preview required `fillTatamiSourceRoot` then, but the ownership transfer below removed it.

This API preparation does not mean the letter backend has passed official equivalence/actual sewout or that S4 is applied.
In particular, the current backend has no start-point argument, so strengthening the contract to pass the final underlay point
into top-path generation can be reviewed in a separate unit. Stop this unit here and await the coordinator's decision.


### 15-minute unit: transfer InkAutoFill ownership to model

Brought `stitches/InkAutoFill.kt` into model's normal `src/main`. Used the file stored in letter
commit `b7106ec361eb5a75eaac54b0cba450cf1041a554`;
its original introduction commit is `1cbdd569747032a48075ca16e732629b15555d6c`.
Also included the directly required shared running utility `InkRunning.kt` at the same path
(introduced in `c8d2f4f0b66376f3d0a70f313ba3717b4559dcdd`). File headers record transfer provenance,
upstream Ink/Stitch paths, pinned commit, and GPL. Algorithm/defaults unchanged.
Future InkAutoFill changes belong to model; letter S2 will not add a duplicate generator.
InkRunning remains a shared dependency utility with the same FQCN/file.

Also transferred letter's existing `AutoFillPortTest` with provenance. Normal `:engine:test`
compiles/runs without the letter worktree or external sources. `DefaultTatami` remains in preview,
requiring only unmerged S1 source input. Removed the `fillTatamiSourceRoot` option and external letter input.
UI/existing product-path integration and start-point contract changes are outside this unit.

Reproduction commands (JDK 17):

```sh
./gradlew :engine:test --console=plain
./gradlew -I tools/inkstitch-fill-preview.gradle \
  -PfillPlannerSourceRoot=/tmp/myembro-port-out/model/dependencies/source \
  :engine:test --tests '*DefaultTatamiTest*' --console=plain
```

Validation results: **395 normal engine tests passed** without specifying external sources;
preview `DefaultTatamiTest`: **3 passed** with only S1 sources specified. Also verified that the two generator/utility files
are byte-for-byte identical to letter's recorded commit except for provenance comments. No full matrix was run.


### 15-minute unit: 4 S4 default-fill sewout candidates

The `exportDefaultTatamiSewtest` task calls existing DefaultTatami + S1 default planner to generate
PES v6/SVG/manifest in `/tmp/myembro-sewtest/s4/`. Rectangle 20×20mm (739 stitches),
circle diameter25mm (1,027 stitches), donut outer diameter30/inner diameter12mm (1,490 stitches), self-created bold H height20mm (616 stitches).
Generation also checked our PesReader round-trip stitch counts, 100mm hoop bounds, and absence of needles inside the donut hole.
All designs are original geometry (CC0); no private files/fonts used.

External `/tmp/myembro-quality-gate/quality-gate check` v5 result: **4/4 PASS**.
Peak density6 penetrations/0.5mm cell, maximum consecutive same-point penetrations2, maximum stitch under4.11mm;
thread-start/end locks and hoop100×100 checks passed. Short-stitch ratio warnings are reference only.
No S4 stage approval was requested; this does not mean current stage progression/release/actual sewout approval.
`tools/render_tatami_sewtest.py` renders the actual PES read by pyembroidery at a common scale into
`overview.png`/individual PNGs. Exact reproduction commands are recorded in the output directory README.
No algorithm/UI/existing product-path changes. Stop here and await coordinator review.
## Lock/plan/command ports

In `LockStitchParameters`, `ties` means 0=both (default), 1=start, 2=end, 3=none.
Apply the priority of `force_lock_stitches` and global `disable_ties` as upstream does.
`InkLockStitch` supports **half_stitch, arrow, back_forth, bowtie, cross, star, simple,
triangle, zigzag, custom**. Preserve absolute/relative scaling, custom numeric patterns and SVG
endpoint paths, END reversal, and exclusion of the final two anchor/direction nodes.
Upstream half_stitch searches at most 15 points for one farther than 0.5mm, capped at 1.5mm.
If none is found, fallback is **0.5px** (not mm). Do not arbitrarily fix the upstream behavior
that omits lock tags in back_forth's negative extension branch.

`StitchPlanBuilder.fromGroups` explicitly runs upstream `stitch_groups_to_stitch_plan`/`filter_duplicate_stitches`.
Preserve default collapse=3mm, minimum length=0.1mm, RGB block merging,
the previous group's minimum jump/force, tie-off→color change→tie-in, and TRIM→STOP order.
Points at or below the minimum length (<=) are retained when the previous retained point is JUMP or the current point is a lock/control command.
This differs from simply removing all 0-length points. Final integer-grid deduplication occurs at a separate generated boundary.

`ElementCommands` accepts ignore_object/layer, trim_after, stop_after,
starting_point/ending_point, and legacy fill_start/fill_end aliases after SVG connectors are resolved.
ignore skips generation itself; trim/stop are ORed onto the element's last nonempty group.
Element-specific routing uses start/end points. This facade does not create arbitrary coordinate moves.

| Location | Difference from upstream | Reason/limitation |
|---|---|---|
| Direct lock calls | Empty result for fewer than 2 points | Apply upstream StitchGroup guards in the reusable API too |
| SVG lock parser | Endpoint-only Kotlin parser instead of Inkex | Only M/L/H/V/C/S/Q/T/A/Z endpoints needed; do not use curve control points as needle positions |
| Validation | Reject nonfinite scales/coordinates early | Prevent malformed files/unbounded expansion |
| Lock type display order | Represent END UI cross/bowtie order differences with a single ID list | IDs and generated coordinates unchanged |
| Empty groups | All-empty input yields an empty plan; exclude leading/trailing empty groups | Provide an engine return value rather than UI exit/empty-block indexing errors; preserve final actual group tie-off |
| Group minimum | Do not overwrite per-stitch values with group values when present | Preserve fine-grained overrides in the shared contract; Stroke facade sets element values on all points as upstream elements do |
| END | Preserve END in the filter, although absent from the upstream model | Guarantee the existing machine API's end command |
| Command lookup | Resolved flags/points instead of Inkex XML/clone/connector traversal | Explicit integration boundary with SVG import owner; caller determines layer ignore status |
| Terminal command | Apply to the last nonempty group | Prevent trim/stop loss on trailing empty groups |

## Stroke/Ripple ports

`StrokeParameters` / `RippleParameters.fromAttributes` accept `inkstitch:*`, XML expanded names,
and local attribute names, retaining public mm/percent values. `Stroke` accepts transformed/clipped SVG px
polylines. Pass original node endpoints for manual, flattened curves for running/zigzag.
Do not claim upstream Inkex DOM loading/transforms/curve flattening are automatically included.

Running includes sleeve/angle intervals, circle intersections, 45-degree corners, even/fixed carry, length lists, tolerance,
repeats, bean, and deterministic random length. Zigzag follows Python round-to-even, even samples,
average tangents, asymmetric pull compensation, angle/closed-path/repeat rules. Manual splits at maximum length
and generates no locks without force. `Prng` uses the same API/code as the shared lettering port,
reproducing BLAKE2s counter-stream hex-word order and UTF-8 seeds (no new dependencies).

Ripple includes target/convex-hull centroid, closed spiral, line/satin guides, manual placement,
line count/minimum spacing/parity, skip/stagger/exponent, per-axis scale/rotation/rungs, grid/grid-first,
repeats/bean, and clipped multigraph routing. Use upstream getter defaults:
`staggers=1.0`, `satin_guide_pattern_position=line_count` (different from UI defaults).
Clipping uses JTS overlay, boundary graphs, Euler correction/boundary travel/groups per connected component.
Multipass/satin guides require an actual SatinColumn rail/rung sampler through `RippleSatinGeometry`.
Without that provider, do not arbitrarily assume two paths are rails.

| Location | Difference from upstream | Reason/limitation |
|---|---|---|
| Stroke input | Transformed/clipped polylines and resolved commands instead of Inkex nodes | Integration boundary between SVG element parsing/clipping and generation algorithms |
| Empty running | Empty List instead of None | Kotlin collection API |
| Invalid numeric lists | Defaults or validation errors for empty/unparseable lists | Prevent NaN propagation/indexing errors; preserve valid-input defaults |
| Resource limits | Input/output capped at 1 million points, interruption checks | Mobile memory/cancellation safety; fail rather than substitute approximate paths |
| Random 0 progress | Descriptive exception for exactly 0 length | Prevent infinite loops without progress |
| Ripple satin | Requires centerLine/rungPairs/plotPointsOnRails provider | No arbitrary rail approximation before integration with the SatinColumn owner's port |
| Ripple clip graph | Ordered Kotlin multigraph/exact DP matching instead of NetworkX | Preserve the minimum-path-count objective without new GPL-compatible dependencies |
| Matching limits | Fail at 60 odd vertices/1 million DP states | Lower resource limits than upstream blossom; do not silently replace with greedy |
| Graph ties | DP matching/one-way Dijkstra may choose another order with the same optimum | Different tie-breaking from NetworkX blossom/bidirectional paths |
| JTS/GEOS | Overlay fragments/order may differ for degenerate/touching shapes | Numerical differences from geometry-library substitution; exact pointwise equality not guaranteed |

Preserve unusual upstream behavior too: when the Ripple clip intersection is empty, return original stitches;
when no travel route exists, use endpoint fallback then the boundary <1px filter. The initial travel marker
is added before distance filtering. Connected components are sorted before terminal travel removal, so removal
may apparently reverse their order. This does not guarantee newly safe clipping behavior.

### Independent gate and validation scope

Store fixed SVGs in `engine/src/test/resources/inkstitch/`; generate PES during test execution.
Algorithm tests use analytical expectations from the formulas/control flow read, with no Ink/Stitch execution comparison.
Kotlin regressions check clone trim/stop OR semantics and deterministic output properties.

On 2026-10-24, `check-release` for Stroke 4 cases (running/manual/zigzag/bean) returned **BLOCK 0/4**.
The independent tool's original decision is preserved in `/tmp/myembro-worker-stroke-{matrix,gate}.json`.
This gate's matrix treats every input as text and enforces a `J→S0→S0.28→S0.28` start and
0.27–0.65mm lock range. It does not recognize the upstream default half_stitch's usual 0.75mm reversal,
blocking with TEXT_START_SEQUENCE_MISSING/START_LOCK_MISSING/END_LOCK_MISSING.
Do not pass by changing the upstream default to 0.28mm or modifying the gate.
Regardless of general-design checks, merging is blocked until the responsible Codex decides upstream-lock/nontext policy.
The matrix's font names identify SVG fixtures; this is not an actual complete font-release matrix.


## Connecting existing generation paths — app policy separate from upstream ports

`GeneratedPatternProcessor.fromGroups` applies the adapter and final integer deduplication after upstream plan generation.
`finish(EmbPattern)` is a bridge called only for native patterns **newly generated by the existing app**.
It runs at the end of lettering running/bean/satin/fill, direct SatinColumn, and digitizing running/tatami generation.
Insert locks before Bean expansion so locks are not tripled too; when passing internal fill output into another generator,
use a body with only our traceable locks removed.

Imported PES/PEC/DST in the editor is raw sewing. Object composition preserves each completed object's needle order
and does not reapply locks to the whole imported design. Scaling/free rotation of generated objects removes our locks
and rebuilds them on the transformed body, preserving actual lock length in mm. Grid-preserving transforms such as integer translation,
90-degree rotation, and mirroring transform needles and tracked coordinates together, preventing rounding from causing
0.1mm offsets. Leave imported-file reader/writer raw round-trip paths untouched.

| Location | Difference from upstream | Reason/limitation |
|---|---|---|
| Native generated bridge | Preserve all explicit JUMP/control commands as boundaries | Do not turn app commands already finalized as needle-up into sewing via 3mm collapse; typed groups retain upstream collapse |
| Native minimum length | From S1, apply upstream default 0.1mm then remove consecutive duplicate STITCH on the integer grid | Remove previous min=0 preservation policy from the default path |
| Native locks | From S1, use upstream `InkLockStitch()` half-stitch | Remove separate polyline lock heuristic; retain upstream fallback/direction rules |
| Quantization duplicates | Remove consecutive identical STITCH on the native grid | Prevent duplicate punctures of needles quantized to 0.1mm; preserve control commands and first STITCH after JUMP |
| Native final grid | Remove only consecutive STITCH→identical STITCH; preserve JUMP→first STITCH | Points distinct in upstream px can become 0-length after 0.1mm quantization |
| Native explicit maximum length | Revalidate/split caller's maxStitchLengthMm on the integer grid | Fit locks to existing lettering API limits such as 0.2mm; express a 0.1mm diagonal as the necessary 2 axis moves |
| Provenance | Add finish/lock indices/run anchors/command anchors/max length metadata | Strip/rebuild only our own locks without guessing body coordinates from arbitrary lock shapes |
| Native regeneration | Default calls regenerate with upstream `SewingOptions()`; explicit options API remains engine-only | No detailed settings/type-selection UI in the app. Store original body separately so length filters can be reapplied |
| Composition result | Retain per-object locks; do not mark the entire composition as finished | Do not globally relock mixed raw/generated patterns; the composed file itself is a completed machine pattern |

### Integration notes

- Do not connect embro-letter's separate `StitchPlanFinisher`/`InkLock` and this common processor twice.
  Share this contract/upstream defaults; separate nonupstream minimum-length/lock policies as explicit options.
- Connecting Ripple's actual SatinColumn provider still requires a separate owner's API extension. Current
  public plotPointsOnRails applies pull compensation/0.01mm clamping, so it cannot replace a raw 0.01px sampler.
  Required APIs: **raw rail sampler accepting spacingPx·offsetPx·offsetProportional·useRandom**,
  flattened sections after direction/precision/rung splitting, and subpath→rail/rung classification results.
- Provider centerLine is the first coordinate of raw pairs with `offsetProportional=(-0.5,-0.5)`, without running resampling.
  rungPairs are end rail pairs of each flattened section, not raw rung endpoints.
  reverse/swap flags must agree between centerLine/rungPairs and plotPointsOnRails.
- Do not report complete SVG DOM functionality before SVG input/connector resolution and this satin provider are connected.

### Reproduction

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug
python3 tools/check_inkstitch_worker.py --general
# 기능별: --family locks / --family stroke / --family ripple
```

Where the gate tool is absent, use `--gate-dir` to specify a tool **provided by the independent owner**.
This helper does not run Ink/Stitch; it submits matrices of generated PES paths to the independent tool.
General-mode PASS does not override release BLOCK; the exit code reflects release failure unchanged.
Generate PES/reports only in build or /tmp; do not commit binaries.


### 2026-10-24 final validation record (worker)

- **379 engine tests and 167 app tests passed**, `:app:assembleDebug` succeeded.
  Includes 101 new `inkstitch` tests. Test/build log:
  `/tmp/myembro-worker-final-attributes-tests.log`.
- `StitchPlanContractTest` continues to pass its guarantee of identical PES bytes after raw round trips of 6 existing files.
- SVG fixtures: 17 total, locks 10 types+1 actual default, Stroke 4, Ripple 2.
  Ripple fixtures explicitly use default start/end `InkLockStitch` like real elements and check metadata.
- Ran `python3 tools/check_inkstitch_worker.py --general`:
  **general designs PASS 17/17**, **required release BLOCK 17/17, exit 1**.
  Original matrix/results/logs: `/tmp/myembro-worker-inkstitch-quality/`.
  Blocking reasons were TEXT_START_SEQUENCE_MISSING and some START/END_LOCK_MISSING;
  this run reported no other byte-structure/coordinate/length/density blocking reasons.
- Pinned gate threshold SHA-256:
  `313eaf7dd85125d5c0340f9d87b5c14ca6cc9a640faf360214c0d300c2b4bca9`.
  Tool/thresholds/pin hashes unchanged. General PASS does not replace release blocking.
- **Merge on hold**: independent owner Codex must provide decision policy appropriate for nontext/upstream locks.
  Satin provider and SVG DOM boundary integration remain pending as noted above.

| Feature commit | Upstream | Verification |
|---|---|---|
| `dc38aac` contract/adapter | stitch_plan, elements/element, geometry, units | Contract 7 tests, manual SVG adapter smoke external PASS 1/1 |
| `d4c6776` locks/minimum lengths/commands/generated bridge | lock_stitch, stitch_plan, color_block, element | Dedicated 37 tests, lock SVG 11 cases; release BLOCK |
| `63eb068` running/bean/zigzag samplers/PRNG | running_stitch, utils/prng | Dedicated 12 tests; actual element SVG validation with Stroke |
| `ec620c1` Ripple/clip graph | ripple_stitch, tatami_fill | Dedicated 29 tests, SVG 2 cases; release BLOCK |
| `26aa2d3` Stroke facade | elements/stroke, elements/element | Dedicated 13 tests, SVG 4 cases; release BLOCK |
| `a77f591` existing generation-path integration | App-specific integration, common postprocessing above | Integration 3 tests and all existing regressions passed; raw/body/transform preserved |


## S1 개정 — 자동 마무리만 적용 (2026-10-24)

개정한 main 작업트리 `docs/ui-stories.md`의 **실패 없는 디자인** 원칙을 따른다.
봉제 설정 섹션, 잠금 타입 선택/미리보기, 고급 파라미터 및 점검 요약 줄은 배포하지 않는다.
10종 잠금과 고급 파라미터 API는 원본 검증·후속 개발용 엔진 기능이며 화면에 노출하지 않는다.
Ripple/Satin provider 연결, Bean 등 추가 생성 기능 활성화는 이번 단계에서 제외한다.

- 기본 잠금: 원본 `half_stitch`, `ties=0`, `force_lock_stitches=false`.
- 최소 땀: 0.1mm. 그룹 최소 점프: 3mm. `trim_after=false`는 원본 기본값이며,
  기존 명시적 TRIM 및 객체 사이 TRIM은 보존한다. 근거 없는 자동 트림 거리 상수는 추가하지 않는다.
- 기존 native 생성 bridge의 자체 폴리라인 half-lock과 min=0 기본 정책을 제거한다.
  typed `StitchPlanBuilder`는 원본 그룹 collapse·색·trim/stop 규칙 그대로 사용한다.
- native 패턴의 이미 명시된 JUMP는 needle-up 명령으로 보존한다. SVG 그룹 provenance가 없는
  바늘 명령에서 빈 공간을 재봉으로 바꾸지 않으며, 이 호환 경계는 원본 그룹 collapse와 구분한다.
- 기존 레터링/편집기의 생성 패턴은 같은 기본 후처리를 사용한다. 이전 `.myembro`의 생성
  객체도 다시 계산한다. raw PES/PEC/DST에는 잠금/최소 길이 정리를 적용하지 않는다.
  원본 PES 저장·공유·USB 전송의 바이트 전달은 유지한다.
- 앱 기본값은 고정값이며 `sewing_options` 저장소나 사용자 설정을 읽지 않는다.
  미리보기·봉제 점검·PES 내보내기는 같은 기본값을 사용한다.

### 고정 SVG 매트릭스 재현

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:verifyInkstitchWorkerBoundary :engine:runInkstitchWorkerMatrix
# 동등한 CLI (입력/출력 경로 옵션은 --help):
python3 tools/generate_inkstitch_worker_matrix.py
```

- 입력 목록: `/tmp/myembro-quality-gate/matrix-inkstitch.json`의 `stitch_plan/`, `ties/`, `stroke/`.
- 실제 입력: `/tmp/myembro-inkstitch-oracle/inputs/`의 materialized SVG. 원래 입력과 SVG의
  SHA-256을 검증하며 `.oracle.json`에서는 식별·출처 필드만 읽는다.
- 출력: `/tmp/myembro-port-out/worker/{stitch_plan,ties,stroke}/<id>.pes`.
- 목록·입력 해시·실행 커밋·dirty 여부: 같은 폴더 `manifest.json`, `inputs.tsv`.
- 활성 `defaults-v2`: 168건(각 기능 56건) 전부 생성. 이 SVG에는 기본값을 덮어쓰는 숫자 속성을 주입하지 않는다.
  이전 탐색 매트릭스 507건/505개 생성 기록은 과거 기록이며 새 기본값 판정에 섞지 않는다. GENERATED는 동등 판정이 아니다.
- Runner는 Inkex CSP 제어점→transform→0.1px subdivision, 전체 명령의 bbox 중심,
  pystitch translate/scale 평가 순서와 Python 짝수 반올림을 원본처럼 적용한다.
  공개 adapter와 편집기 배치 좌표 계약은 변경하지 않는다.
- 원본 출력 비교용 `PesWriteSettings(inkstitchCommands=true)`는 pystitch PEC의 viewport prefix,
  첫 JUMP 이후 trim/jump, trailing STOP duplicate 색 이벤트를 재현한다.
  이 opt-in 프로필은 원점을 바꾸므로 앱의 기존 배치·가져오기 경로에는 적용하지 않는다.
  일반 writer의 기본 바이트는 유지한다. 공식 PES의 모든 바이트 동일성을 주장하지 않는다.
- 원본 Ink/Stitch/Inkex/pystitch는 읽기만 했으며 실행·의존성 설치를 하지 않았다.

독립 판정 제출 커맨드(원본 실행이 아닌 embro-codex 비교 도구):

```sh
python3 /tmp/myembro-inkstitch-oracle/compare_matrix.py --ports /tmp/myembro-port-out/worker/manifest.json --report /tmp/myembro-port-out/worker/independent-comparison.json
```

PE-Design 고정 잠금 바이트에 맞추려고 출력이나 임계값을 바꾸지 않는다. 병합 판정은
독립 담당 Codex의 비교·재보정 게이트가 맡으며, 실제 SE400 봉제 검토는 별도 완료 조건이다.

### 원본 기본 잠금의 경계 사례 — 안전 승인과 구분

`half_stitch`는 15개 후보 중 시작점에서 0.5mm보다 먼 점이 없으면 방향을 **+x**, 길이를
**0.5 SVG px**로 사용한다. 따라서 `(200,198)→(198,198)` native 경로에서는 잠금 바늘이
`(201,198)`까지 나올 수 있다(도형 경계보다 0.1mm 바깥). 원본 그대로의 동작이며 임의로
도형 안으로 잘라 내거나 별도 폴리라인 잠금으로 대체하지 않는다.

`DigitizingTest`는 실제 후처리 출력에서 추적된 기본 잠금 인덱스만 제외하고 **필터된 본문**의
구멍·오목부·분리 영역 불변식을 검사한다. 삭제 전 본문을 복구하는 `stripGeneratedLocks`로
검사하지 않는다. 별도 회귀는 위 fallback 좌표와 전체 잠금의 영역 포함 보장이 없음을 명시한다.
본문 검증 통과나 oracle 동등이 잠금까지 항상 도형 안에 있다는 안전 승인을 뜻하지 않는다.
이 경계 사례와 native bridge의 명시적 JUMP 보존은 독립 Codex 검토 항목이며, 실제 봉제는 미검증이다.

### 이전 탐색 매트릭스 독립 비교 기록 (matrix-v1, 이력)

`compare_matrix.py`(criteria SHA-256
`fbbf03ab6872d767b2c996f46f3c1cf3055b0b9f8f54b4c0a35fccdc095d6b74`) 원문 결과:

| 제출 기능 | 동등 | 불일치 | 미판정 |
|---|---:|---:|---:|
| stitch_plan | 168 | 0 | 1 |
| ties | 168 | 0 | 1 |
| stroke | 169 | 0 | 0 |

미판정 2건은 `ORACLE_UPSTREAM_ERROR`로 공식 기준 출력의 오류다. 제출하지 않은 S2 이후
기능은 이 기록에 포함하지 않는다. 이는 **PES-output comparison**이며 mechanical release gate,
원본 소스 전체 감사, SE400 실제 봉제 통과와 별개의 판정이다. 최종 커밋에서 매트릭스를 재생성해
`manifest.json`의 커밋과 clean 여부를 고정한 다음 Codex에 제출한다.

### S1 자동 기본값 빌드 검증

- `:engine:test` **393개 통과**, `:app:testDebugUnitTest` **171개 통과**(실패/skip 0).
- `:app:assembleDebug` 성공. 로그: `/tmp/myembro-worker-s1-automatic-tests.log`.
- 원본 adapter의 6개 fixture 바이트 보존 및 writer 기본 프로필의 기존 바이트 회귀 유지.
- 앱 회귀: 고정 기본값/이전 프로젝트 재계산, 잠금 10종 옵션에도 raw PES의 바이트·땀 불변,
  viewer/USB의 프로젝트 PES 생성 일치. 고급 타입 옵션은 테스트용 엔진 입력일 뿐 사용자 UI가 아니다.
- 변경된 기본 잠금 때문에 이전의 전체 도형 포함 불변식은 더 이상 성립하지 않는다. 이를
  테스트를 느슨하게 해 숨기지 않고, 실제 필터된 본문 검증과 명시적인 원본 fallback 반례로 나눴다.
- SE400 실물 봉제는 수행하지 못했다. 최종 병합·배포 가능 판정은 독립 Codex와 사용자 실봉제 검토가 필요하다.

### 활성 기본값 매트릭스 defaults-v2 제출

작업 중 독립 Codex가 매트릭스를 개정했다. Runner는 새 scope의 독립 동결 파일
`/tmp/myembro-inkstitch-oracle/out/defaults-v2/oracle-freeze.json`을 선택하고, 각 identity record의
동결 SHA-256·scope·id 및 원본 SVG와 materialized SVG의 **바이트 동일성**을 검사한다.
원본/좌표/PES 출력은 생성 입력으로 사용하지 않는다. 이전 scope도 명시적으로 지원하되 자료를 섞지 않는다.

- 활성 matrix SHA-256: `17f10563b6743539dba46f6db294f6f63b5f2db29b74d251c6521404755a5fe0`.
- 입력: `inputs/defaults-v2--<id>.svg`. 출력과 재현 커맨드는 위와 동일하다.
- **stitch_plan 56/56, ties 56/56, stroke 56/56 동등**, 불일치·미판정 0.
- 기계적 릴리스 게이트 v2 (`check-release --stage S1`): **144 PASS / 24 BLOCK**.
  threshold SHA-256: `cb9c68f379056ec8215c9e7673cedb5a5d166b47484923ef710c9b870bb5aff1`.
- 같은 독립 검사기에 **공식 Ink/Stitch 출력**을 제출해도 같은 24개 `crossing_strokes`가 BLOCK이다.
  세 구간의 시작 S0가 `UNEXPLAINED_ZERO`로 집계되고 작은 4–10mm 입력에서는 본문 짧은 땀 비율도 차단된다.
  functional 시작/끝 잠금은 검출된다. 이 결과를 임의 예외로 통과시키거나 원본 기본값을 변경하지 않는다.
- `independent-comparison.json`, `release-matrix.json`, `release-gate.json`,
  `reference-release-matrix.json`, `reference-mechanical-gate.json`은 `/tmp/myembro-port-out/worker/`에 둔다.
  릴리스 매트릭스의 font 축은 `feature/shape` fixture ID이며 제품 폰트·안전 크기 범위가 아니다.
- **S1 병합/배포 승인 대기**: Codex의 남은 게이트 검토와 사용자 실제 봉제. S2 이후로 자동 진전하지 않는다.
  코드·단위 테스트·빌드는 준비됐으며 외부 검토/봉제 완료 시점은 미정이다.


## SE400 최종 안전 출력 경계 (T3 실기 확인 후)

기본 PES 출력은 `GeneratedPatternProcessor.forSewout`에서 제로 JUMP·제로 바늘과
같은 위치 TRIM 재시작을 제거한다. 비제로 half_stitch 경로는 변경하지 않는다.
이는 원본 인식 규칙과 다른 **사용자 지정 최종 출력 정책**이다. 원본 호환 oracle 프로필은 유지한다.
근거·테스트·재시험 경로·staging 적용은 [sewout-zero-start.md](sewout-zero-start.md)에 기록했다.


### SE400 PES 원점 정책 차이 (2026-10-24)

| 경계 | 원본 비교 프로필과의 차이 | 근거 |
|---|---|---|
| 기본 PES v1/v6 | 최종 PEC 좌표를 좌상단 0,0으로 이동, 새로 생긴 제로 이동/바늘 제거. PEC 사각형·v6 편집 변환 일치 | 사용자 SE400의 왼쪽 한계 정지 관찰, PE-Design 로컬 바이트 비교, S23SAM4 독립 좌표 검증 |

알고리즘·half_stitch 잠금·편집 좌표에는 적용하지 않는다. 명시적 원본 비교 프로필과 가져온 PES 원본은 유지한다. 경로별 자동 테스트와 예외는 [pes-viewport.md](pes-viewport.md)에 기록했다.

### 20분 단위: 기본 타타미 공식 좌표 회귀 테스트 (실패를 자동 검출)

main의 S1 planner를 병합한 뒤 DefaultTatami와 기존 3개 smoke 테스트를 일반 source set으로
옮겼다. UI/제품 경로는 연결하지 않았다. `DefaultTatamiOracleTest`는 공식 defaults-v2의
사각·원·구멍 nominal20mm **3건**을 생성→PES v6→PesReader로 읽어 비교한다.
공식 PES/좌표 gzip/입력 SVG/원본 실행 기록을 `engine/src/test/resources/inkstitch-oracle/`
아래에 변경 없이 복사하고 출처·고정 커밋·SHA256을 기록했다. 개인 도안은 없다.
서로의 바늘 bbox 중심을 빼는 평행이동만 적용하며, 전 바늘(잠금 포함)의 점 집합으로
양방향 Hausdorff를 계산한다. 회전·크기 조절·점 제거는 하지 않는다.

| 대표 사례 | 우리/공식 땀 수 | 땀 수 차이 | Hausdorff mm | 결과 |
|---|---:|---:|---:|---|
| 사각 | 557/571 | 2.45% | 0.5831 | FAIL |
| 원 | 722/662 | 9.06% | 0.5315 | FAIL |
| 구멍 | 804/824 | 2.43% | 0.5831 | FAIL |

명령: `./gradlew :engine:test --tests '*DefaultTatami*'`.
기존 smoke3개 PASS, 새 공식 동등성3개 FAIL. 기준은 사용자 지정 땀수±5%/Hausdorff≤0.2mm
그대로이며, 실패 테스트를 무시하거나 허용치를 높이지 않았다. 수치·후보 PES는
`engine/build/tatami-oracle/`에 자동 저장한다. 따라서 **이 커밋은 병합 준비가 아니다**.
이전 S4 외부 게이트4/4 PASS는 기능 안전 조건 결과이며 공식 동등성 통과를 뜻하지 않았다.

worker가 POLICY.md v5의 출처별 게이트 구현을 추가하면 이 생성 PES에 SewoutCheck assert를
연결해야 한다. 현재 구형 SewoutCheck를 해당 정책과 같다고 주장하지 않고, 호출 위치를 명시했다.
CI push 대상은 모든 브랜치, 검증 명령은 `./gradlew check`로 바꿨다. 원격 CI 녹색은 미확인이다.
다음 단위 제안: 시작/끝 연결 및 바탕 땀→top 경로 전달을 원본과 대조해 3건 불일치를 수정.
이번 단위는 자동 검출·실패 수치 기록까지 하고 총괄 결정을 기다린다.

전체 `:engine:check`도 실행했다: 509개 중506개 PASS, 위 공식 동등성3개만 FAIL.


### 20분 단위: 타타미 유한 입력 확장 스윕

기본 각도0° 및 기본 바탕 땀으로5도형×5–90mm(1mm 간격)=430조합을 생성했다.
`python3 tools/sweep_tatami.py --out /tmp/myembro-tatami-sweep-1mm` 출력:
`GATE: 430/430 PASS; 0 BLOCK`. 외부 frozen v5 게이트 판정이며 worker Kotlin 정책은 병합 대기다.
규칙 계산기3개 테스트 출력은 `Ran 3 tests ... OK`. 확장 생성은 CI에서 실행하지 않는다.
자동 허용 JSON은 정확한 표본만 허용하고 실패·미검사 입력은 제외한다.
비단조 실패를 단일 최소 크기 규칙으로 일반화하지 않는다.
[명령/결과/범위 제한](tatami-sweep.md)에 표와 JSON을 기록했다.
공식 동등성3건 실패는 해결하지 않은 상태이며 앱 연결/배포는 비활성이다.
이번 단위는 여기서 멈추고 총괄 검토를 기다린다.


### 20분 단위: 공식 동등성3건 수정

간소화 InkAutoFill 그래프를 기존 TatamiFill/FillRouting 어댑터로 바꾸고,
바탕 땀 마지막점을 top 시작점에 전달했다. 원본 running tolerance0.2mm를 적용했다.
테스트 SVG 경계는 기존 worker의 Inkex CSP 평탄화 포트를 공유한다.
[전후 수치/원본 대조/명령 출력](tatami-equivalence-fix.md)을 기록했다.
`./gradlew :engine:clean :engine:check`: 509 tests, 0 failures, BUILD SUCCESSFUL in21s.
공식3건 모두 땀수100% 일치, Hausdorff0.141422mm 미만. 외부 게이트도 PASS3/3.
이전430스윕은 이전backend 이력이다. 새 코어 재스윕 전 허용규칙 비활성은 유지한다.
원격 CI/실봉제 승인과 혼동하지 않는다. 이번 단위 종료, 총괄 지시 대기.


### 계산 단위: 수정 backend430조합 재스윕

`JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 python3 tools/sweep_tatami.py --out /tmp/myembro-tatami-sweep-source-routing`
출력: `GATE: 430/430 PASS; 0 BLOCK`. 생성은 BUILD SUCCESSFUL in1m12s, 생성 오류0건.
backend e154303, 같은5도형×5–90mm 정수크기×기본각도0°.
[자동 계산 표·실패 목록·허용 JSON](tatami-sweep.md)을 현재 backend 결과로 갱신했다.
엔진/임계값/테스트 변경은 없고 앱 연결/배포 활성화도 하지 않았다. 여기서 마무리하고 지시 대기.
# SatinColumn 담당 구현·검증 기록

## 담당과 일정

`embro-letter` / `codex/letter`: SatinColumn 기하 코어와 기존 레터링 연결.
최초 예상은 기본 코어·언더레이 4–6시간, split/변형·동등성 검증 추가 6–10시간이다.
자동 경로·선→새틴 도구는 2단계다. 공통 StitchPlan 소유자는 `embro-worker`,
독립 출력 판정은 `embro-codex`다. 로컬 단위 테스트 성공을 릴리스 판정으로 대신하지 않는다.

## 새틴의 공통 계약 연결

공통 계약은 main `cef0156`에서 병합했다. 위의 worker 계약이 우선하며 같은 이름의 모델을 중복 생성하지 않는다. 코어와 공유 모델은 모두 SVG px를 사용한다.

```text
Stitch(x, y, tags: jump/trim/stop/lock/...)
StitchGroup(color, stitches, trimAfter, stopAfter, lockStart, lockEnd,
            minStitchLengthMm, minJumpLengthMm, forceLockStitches)
StitchPlan(colorBlocks)
EmbroideryElement.toStitchGroups(previousStitch)
StitchPlan -> 기존 EmbPattern/PES 어댑터
```

폰트 연결 위치는 `inkstitch.elements.SatinColumn.fromSubpaths()`이며 직접 레일을 아는 호출자는 생성자를 쓴다.
`SatinLayer(points, tags, stitchTags)`는 **기하 중간 결과**이며 공통 StitchGroup을
대체하지 않는다. 바늘별 `satin_column_edge`, `satin_split_stitch`, `peak_a/peak_b` 태그와
레이어별 `satin_center_walk`, `satin_contour_underlay`, `satin_zigzag_underlay`를 유지한다.
공통 어댑터는 같은 색의 언더레이/본 땀을 합치고 필요한 러닝 연결을 만든 뒤,
공통 그룹의 잠금·최소 땀·트림·점프 정책을 한 번 적용해야 한다.
`SatinColumn`은 이제 `EmbroideryElement`를 구현한다. `toStitchGroups(previousStitch)`가
worker의 실제 `StitchGroup`을 반환한다. 레이어 간 러닝을 연결하고 색·태그·trim/stop·
주입된 LockStitch·min length·force lock 설정을 보존한다. S2는 S1 worker의
`StitchPlanBuilder`/`GeneratedPatternProcessor`에 연결한다. 그룹 DTO 자체는 수동 계약이다.

### 폰트 파서 연결 예

```kotlin
val groups = SatinColumn.fromSubpaths(
    subpaths = flattenedPathsInSvgPixels,
    parameters = SatinParameters(zigzag_spacing_mm = 0.4),
    nodePaths = originalBezierEndpointsInSvgPixels,
    color = svgStrokeColor,
).toStitchGroups(previousStitch)
```

SVG의 path들을 임의 순서로 받을 수 있다. `rail_indices` 방식으로 레일을 찾으며,
렁이 없으면 원래 Bézier 끝점으로 합성한다. **flattening으로 추가한 점을 원래 노드로
전달하지 않는다.** 직접 레일·렁을 알고 있으면 `generateLayers(left, right, ..., rungs)`를 쓴다.
`previousStitch`, `nextStitch`, 명시적 `startPoint/endPoint`도 SVG px다.
`SatinParameters`의 `_mm` 속성만 mm이며 private pixel getter에서 한 번 변환한다.
`random_seed`가 빈 SVG에서는 호출자가 원본처럼 element ID를 기본 seed로 넣는다.
TTF 외곽선은 이 폰트 API로 가장하지 않으며 기본은 auto-fill + 윤곽 러닝이다.

## SatinColumn 추적표

원본 파일은 별도 표시가 없으면 모두
`lib/elements/satin_column.py@b0edd96311ece82ee48816dc93466274b12c2a9c`다.

| 기능 | 원본 함수 | Kotlin |
|---|---|---|
| 레일 찾기·노드 렁·방향·swap | `rail_indices`, `_synthesize_rungs`, `_get_rails_to_reverse`, `line_string_rails` | `generateFromSubpaths`, `orient`, `sections` |
| 렁 구간 보간·밀도 | `flattened_sections`, `_stitch_distance`, `plot_points_on_rails`, `SatinProcessor` | `Generator.pairs`, `Processor` |
| 비대칭 mm/% pull·push | `_apply_push_comp`, `_extend_line`; `lib/utils/geometry.py:offset_points` | `push`, `offsetPoints` |
| short stitch | `inset_short_stitches_sawtooth`, `_get_offset_px` | `Generator.shorten` |
| 기본/단순/엇갈림 split | `get_split_points`, `_get_split_points_default/simple/staggered` | `Generator.splits` |
| 난수 split·phase | `lib/stitches/running_stitch.py:split_segment_even_n/random_phase/stagger_phase` | `splits`, `evenSplits` |
| satin·E·S·zigzag | `do_satin`, `do_e_stitch`, `do_s_stitch`, `do_zigzag` | `Generator.top` |
| 세 종류 언더레이 | `do_center_walk`, `do_contour_underlay`, `do_zigzag_underlay` | `Generator.layers` |
| 시작·끝·중심 연결 | `start_point`, `end_point`, `find_cut_points`, `_split_top_layer`, `_sort_stitch_groups`, `_connect_stitch_group_with_point` | `generateLayers`, `cut`, `centerConnector` |
| 재현 가능한 난수 | `lib/utils/prng.py:join_args`, `uniform_floats`, `n_uniform_floats`, `iter_uniform_floats` | `utils.Prng` |

`SatinParameters` 필드명·단위는 SVG 속성에 대응한다. 쌍 값은 첫/둘째 레일 순서,
`short_stitch_inset`은 **% 목록**, `pull_compensation_percent`도 **%**다.
원본 기본: spacing 0.4mm, short distance 0.25mm, inset 15%, split `default`,
max stitch 없음, center/contour/zigzag 언더레이 꺼짐, 언더레이 러닝 길이 3mm,
center repeats 2, contour inset 0.4mm, zigzag spacing 3mm다.
랜덤 split의 jitter는 최대 100%, 위상·폭·간격 난수는 원본의 BLAKE2s 카운터 스트림을 쓴다.
BLAKE2s는 [RFC 7693 §§2–3](https://www.rfc-editor.org/rfc/rfc7693.html)의 독립 Kotlin 구현이며
추가 의존성을 설치하지 않았다. RFC `abc`와 표준 hashlib 다중 블록/UTF-8 벡터로 대조한다.

## 차이점 — 숨기지 않는 원본/앱 경계

| 위치 | 차이 | 이유/영향 |
|---|---|---|
| SVG 입력 | 코어는 SVG px 폴리라인과 원래 노드 목록을 받음 | Inkex의 path parsing/flattening은 canvas 담당. Bézier 엔진을 중복 구현하지 않음 |
| 기하 | Shapely 대신 기존 JTS 1.20.0. 레일은 동일한 1e-5 SVG px precision grid 적용 | 교차·project·substring·offset의 수치 tie-break가 다를 수 있음. 바이트 동일성 미검증 |
| 렁 | 원본처럼 >2점 교차 렁 무시, 각 레일 cut 정렬 후 zip, 빈 구간 제외 | 이전 crossing-rung 강제 거절 정책 제거. JTS 수치 오차는 별도 비교 대상 |
| 불량 입력 | 점/명령 수 예산 초과·비유한 값은 거절 | 모바일 자원 제한; 원본의 무제한 크기 지원을 주장하지 않음 |
| raw 코어 최소 길이 | 속성 미지정은 null로 유지하고 random split 계산에서만 metadata 기본 0.1mm를 사용; 전체 최소 땀 필터는 아직 적용하지 않음 | 원본도 그룹→StitchPlan 경계에서 전체 필터를 적용. worker 계약 소유권 유지 |
| 공통 타입 연결 | `SatinColumn : EmbroideryElement` → S1 공통 builder. 기본은 source half_stitch; null 주입으로 해제 가능 | worker d4c6776 통합, 임시 Finisher 삭제 |
| raw 코어 잠금/트림 | `lock_start/end`, `trim_after`, `stop_after`는 geometric layer에 삽입하지 않음 | 공통 StitchGroup/Plan에서 적용할 속성. 중복 잠금 방지 |
| center-walk tolerance | 원본 getter/속성은 노출하되 중심선 계산은 원본처럼 `running_stitch_tolerance_mm` 사용 | v3.3.0 `_get_center_line_stitches` 실제 동작과 일치 |
| running tolerance | UI decorator 0.1과 달리 getter 기본 **0.2mm** 사용 | v3.3.0 런타임 기본에 맞춤 |
| 앱 어댑터 | 기존 프로젝트의 legacy 필드만 source DTO로 매핑. 신규 선택은 source 기본값 | 폭 >7mm 강제4mm·short distance0.3·compact0.4 정책 제거 |
| 앱 채우기 초안 | 레거시 FillStitches는 아직 0.3mm 본문 모서리 병합을 사용 | S4 정식 auto-fill로 대체할 영역; 잠금은 S1 소유이며 일괄0.3mm 강제가 아님 |
| 과밀 | 생성기에서 바늘을 지우거나 셀8회 예외로 차단하지 않음 | 독립 gate/sew-out 진단 책임. source 잠금 포함 관찰값을 제출 |
| 앱 외곽선 | TTF SATIN 요청은 사각형 포함 전부 auto-fill+경고. UI에서 신규 선택 제거 | convert/auto-route 검증 전 자동 중심선 기둥 생성 금지 |
| 기존 선→새틴 | `lettering.SatinColumn.rails`의 독립 miter offset은 아직 남아 있음 | 2단계의 원본 convert-to-satin 포팅 대상. 새 SVG 코어 구현으로 오인하지 않음 |
| auto-fill 초안 | 일정 간격/고정 stagger만, bounded exact odd matching, 이동 smoothing 생략 | 이전 안전 초안. fill 담당자가 정식 파라미터/모듈로 이어받을 영역; 상세는 stitch-algorithms.md |

## 이전 검증 기록 — 3bbaaa4, 현재 S2 결과가 아님

- v3.3.0 `tests/` 21개 파일을 확인했다. SatinColumn 대응 테스트가 없어 복제할 원본
  입출력 fixture는 없다. `SatinPortTest`는 수기 좌표, 수식, 순서, 난수 벡터 10개다.
- `SatinElementContractTest` 3개: SVG px·태그·색·주입 잠금·trim/stop·null 기본값과 실제 공통 패턴 어댑터 연결.
- `SatinFixtureTool`: 저장소의 공개 합성 SVG → SatinColumn 요소 → 실제 공유 그룹/어댑터 → 7종의 PES/명시적 축 매트릭스 생성.
  `MYEMBRO_SATIN_FIXTURES=/tmp/... ./gradlew :engine:test --tests '*SatinFixtureTool'`.
- `BirdnestRegressionTest`: 공개 OFL Dancing Script Bold, `Jung Min`, 34mm, 40wt.
  검증되지 않은 SATIN 요청도 auto-fill과 동일한 출력으로 fallback함을 검사한다.
  0.3mm 미만 땀 0, 단일 위상 셀 최대 ≤8, 끝 잠금, PES v1/v6 왕복을 검사한다.
- 전체 자체 회귀: 엔진 **309개**, 앱 **167개** 성공, `:app:assembleDebug` 성공.
- 감사 203개 중 200개 생성, 3개 과밀 차단. 생성된 결과의 0.3mm 미만 땀은 0개,
  단일 위상 셀 최대 8회. `Jung Min`은 5,389땀·단일 위상 셀 최대 7회.
  독립 게이트의 4위상 셀 최대는 8회다. 시작 형식 차단을 해소한 것은 아니다.
- 비교 PNG: `/tmp/myembro-birdnest/before.png`, `after.png`.
  개인 파일·추출 좌표·개인 렌더링은 커밋하지 않는다.
- 독립 실행: `/tmp/myembro-quality-gate/check-release --matrix … --json …`.
  결과는 **BLOCK**. 합성 새틴 0/7, `Jung Min` auto-fill 0/1이다.
  공통 차단: `TEXT_START_SEQUENCE_MISSING` — 게이트는 J→S0→0.28mm 반전 2땀,
  현재 임시 앱 어댑터는 S0 제거·0.4mm 반전이다. split random은 `START_LOCK_MISSING`도
  관측되어 연결부 보완이 필요하다. split/E/S 일부는 `SATIN_NOT_VERIFIABLE`이다.
  판정 표본이 부족한 것을 통과로 바꾸지 않는다.
- 보고서: `/tmp/myembro-birdnest/satin-fixtures/gate-report.json`,
  `/tmp/myembro-birdnest/release-jung-min-final.json`.
  **릴리스/병합 가능 판정 아님. 원본 실행 차분·SE400 실물 검증 미완료.**

공통 소유자에게 필요한 결정: “모든 <0.3mm/0길이 바늘 제거”와 게이트의 명시적 S0/0.28mm
시작 형식은 동시에 만족할 수 없다. 잠금 예외와 공통 Plan의 기본값을 합의하고,
새틴 변형에 적용할 독립 게이트 분류를 확정한 뒤 출력 매트릭스를 다시 판정해야 한다.
게이트나 임계값은 이 브랜치에서 수정하지 않았다.


## S2 개정 적용과 재현 (2026-10-24)

현재 기준은 main 작업트리 `docs/ui-stories.md`의 **실패 없는 디자인** 개정안이다.
이전 US-3/4 상세 설정·고급 탭 작업은 취소했다. S1 사용자 검토 전 S2 main 병합/릴리스는 보류한다.

- S1 `384b259` 갱신판 `d4c6776`은 의존 커밋 `3405b63`으로 가져왔다.
  b798af7 임시 compact 잠금은 삭제했고 S1 공통 후처리를 사용한다.
- UI 입력은 글자·글꼴·높이·색만이다. 종류/밀도/바탕 땀/실 굵기/빈/곡선/자간/정렬 설정은 노출하지 않는다.
  새틴 코어의 전체 파라미터 API는 포팅 검증과 기존 데이터 해석을 위해 남기지만 신규 생성은 원본 기본값만 쓴다.
- TTF 자체 자동 새틴은 기본에서 제외했다. 새틴은 명시적 SatinColumn과 검증된 글꼴의 레일/렁만 대상이다.
  새 TextObject와 LetteringEngine 기본은 RUNNING이다. FILL/BEAN은 기존 저장 파일의 명시적 요청을 해석할 뿐 신규 UI 기본이 아니다.
- 기본 러닝은 Ink/Stitch stroke getter의 길이 2.5mm·허용오차 0.2mm다. 추가 Douglas–Peucker 단계를 적용하지 않는다.
- FontQuality의 자체 권장 높이는 기본 결정·높이 제한에 사용하지 않는다. 3b6ec12 TTF 분석도 승인 근거가 아니다.

### 자동 규칙의 단일 입력

embro-codex의 `/tmp/myembro-quality-gate/auto-rules.md`를 확인했다. 안전한 글자 높이·새틴 폭의
수치 경계는 **미확인**이다. PES 전체 bbox나 4–150mm 비교 매트릭스를 안전 범위로 일반화하지 않는다.
총괄의 추가 결정에 따라 미확인 글꼴은 S2에서 **새틴을 막고 러닝만 허용**한다. 이는 러닝의 모든 높이가
실기 검증됐다는 뜻이 아니다. 확인되지 않은 5–50mm 등의 범위를 새로 만들지 않는다.

```sh
python3 tools/import-lettering-rules.py /tmp/myembro-quality-gate/auto-rules.md
```

이 도구는 문서의 명시적 fenced JSON `{ "schemaVersion": 1, "profiles": [...] }`만 가져온다.
표/본문의 숫자는 추론하지 않는다. 현재 승인 프로필은 0개다. 런타임의 유일한 규칙 입력은
`engine/src/main/resources/lettering/validated-ranges.json`이며, 원문 이름·SHA256을 기록한 배포 스냅샷이다.
새 수치가 승인되면 이 명령으로 갱신하고 리뷰한다. 별도 하드코딩 범위나 FontQuality fallback은 없다.
프로필은 fontId·파일 내용 SHA256+추출 버전·글리프 코드포인트·방식·높이 범위·근거를 묶는다.
SATIN은 SATIN_COLUMNS 기하와 검증된 폭 범위도 필요하다. TTF를 기둥으로 추정하지 않는다.
S3 자수 글꼴 메타데이터 연결은 파서 담당의 후속 작업이다.

확정 범위가 있으면 높이 슬라이더를 그 범위로 제한한다. 현재 미확인 상태는 안내와 러닝 높이 숫자 입력을
사용한다(총괄 허용). 비유한/0 이하 높이·없는 글리프·후프 밖 결과는 추가할 수 없다.

### defaults-v2 재현

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:satinOracle --console=plain
```

현재 `/tmp/myembro-quality-gate/matrix-inkstitch.json`의 satin_column 56개 SVG를 읽는다.
매트릭스 SHA256 `17f10563b6743539dba46f6db294f6f63b5f2db29b74d251c6521404755a5fe0`.
각 case가 지정한 input과 input_sha256을 그대로 검증한다. 출력은 `/tmp/myembro-port-out/letter/`의
PES 56개·`inputs.txt`·`manifest.json`이며 commit/dirty/branch/stage/입출력 해시를 기록한다.
`-PmatrixFile=… -PportOutput=…`로 입력 문서/출력 위치만 바꾼다. 원본 실행·설치·기준 변경은 하지 않는다.
현재 공식 oracle은 이전 매트릭스에 고정돼 있어 **defaults-v2 공식 동등성 판정은 대기**다.

이전 파라미터 매트릭스의 유효 224/224는 공식 비교 도구의 동등 기준을 통과했다(불일치0).
zero-rails 음성1건은 거절/미판정이었다. 이 기록은
`/tmp/myembro-port-out/letter/legacy-parameter-matrix/`에 보존했다.
현재 defaults-v2나 제품의 안전한 높이/폭 범위를 인증한 결과로 쓰지 않는다.
`-PmatrixFile=`는 이전 frozen oracle SVG 225건을 재생한다.

`svg.ViewBoxTransform`은 원본 `lib/svg/units.py:get_viewbox_transform`의 6자리 %f 축척과
translate @ scale 순서를 보존한다. curve flatten tolerance는 0.1 SVG px다.
일반 SVG viewport 계산으로 대체하면 정확한 split 경계와 봉제 순서가 달라진다.

### Jung Min 및 검증 상태

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 MYEMBRO_START_AUDIT=/tmp/myembro-birdnest/s2-running ./gradlew :engine:test --tests '*LetteringAuditTool' --rerun-tasks
/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-birdnest/s2-running/script-34.pes --text --style running --json /tmp/myembro-birdnest/s2-running/quality-v2.json
```

공개 OFL Dancing Script Bold, Jung Min, 34mm, 40wt의 개정 러닝 결과는 **531땀·트림8·S0 9개**다.
현재 v2 진단은 **BLOCK: UNEXPLAINED_ZERO 9/531**. 셀 집중·시작/끝/트림 잠금 검사는 통과했다.
S1의 시작 앵커를 PE-Design 수열에 맞춰 삭제하지 않았다. 시작20땀 TSV와 생성 잠금 태그 추적 JSON을
함께 제출한다. 추적 JSON은 PES 레코드 인덱스나 독립 승인으로 가장하지 않는다.
PNG는 `/tmp/myembro-birdnest/s2-running.png`. 개인 PES와 개인 렌더링은 커밋하지 않는다.
이전 fill 진단 PASS는 `/tmp/myembro-birdnest/s2/`의 별도 과거 기록이며 개정 S2 통과가 아니다.

자체 회귀는 엔진354개·앱167개 및 APK debug 빌드 성공. 임시 API35 에뮬레이터에서 4입력,
자동 러닝/미확인 안내, 높이0 추가 차단·정상값 복원 후 활성화를 확인했다.
**S2 완료/릴리스 승인 아님:** defaults-v2 공식 비교, 위 S0 판정 검토, S1 사용자 검토·SE400 실제 봉제가 남았다.


### 총괄의 검증 정책 재개정 — 현재 단위 종료

수백 건 동등성 매트릭스 검증은 중단한다. 위 매트릭스와 v2 S0 비율 BLOCK은 과거 진단 기록이다.
S0 비율·짧은 땀 비율·길이 분포·새틴 간격 통계는 차단 조건이 아니다. 현재 새 기준으로 재판정했다고
주장하지 않는다. 이후 검증은 대표 사례 10개 이내에서 땀 수·모양을 확인하고, 실제 실패 조건인
0.5mm 셀 밀집·같은 점 연속 천공·기능적 시작/끝 잠금·최대 땀 길이·파일 유효성/후프 범위만 다룬다.
최종 판정은 사용자 실제 봉제다. 이번 단위는 코드·재현 산출물·간소화 UI를 push한 지점에서 종료하며
다음 검증 단위는 총괄 결정 후 진행한다.

### S2 정리: S1 잠금과 담당 범위

새틴은 worker S1 `InkLockStitch()`의 기본 `half_stitch`를 시작·끝에 전달하고
`GeneratedPatternProcessor.fromGroups` 하나만 거친다. 자체 COMPACT/0.4mm 왕복 잠금은 없다.
회귀 테스트는 양쪽 `lock_id=half_stitch`와 S1 잠금 태그 생성을 확인한다.

S4 타타미 구현은 `digitizing/OutlineTextFill.kt`로 분리했으며 model 담당이다.
기존 저장 객체 호환을 위한 `lettering/OutlineFill` 계약·위임은 유지한다. S2에서는 확정되지 않은
TTF 새틴 요청을 러닝으로 바꾸며, 새틴 요청을 타타미로 대체하지 않는다.

생성기에 자체 0.5mm 셀당 8회 거부 조건을 두지 않는다. 봉제 점검 기본값은 게이트와 동일하게
15회까지 허용하고 16회부터 차단한다. 잠금 판정 기준③은 바꾸지 않았다.
대표 검증은 satin_column 7개 형상의 20mm 기본값만 재생성한다. 릴리스 게이트 판정은
`check-release` 결과로 따로 보고하며 독립 승인이나 실제 봉제 통과로 주장하지 않는다.

대표 7건 재판정(생성 커밋 `0ce60ae0e820985911bf43ba5b47a6a28bc52a56`):
사각형·원·홀·자기 교차·좁은 끝·급한 꺾임·교차 획, 높이 20mm, 원본 기본값이다.
출력과 해시는 `/tmp/myembro-port-out/letter/representative-7/manifest.json`,
재현 입력은 같은 폴더 `inputs.json`이다.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:satinOracle -PmatrixFile=/tmp/myembro-port-out/letter/representative-7/inputs.json -PportOutput=/tmp/myembro-port-out/letter/representative-7
/tmp/myembro-quality-gate/check-release --stage S2 --matrix /tmp/myembro-port-out/letter/representative-7/release-matrix.json --json /tmp/myembro-port-out/letter/representative-7/release-report.json
/tmp/myembro-quality-gate/quality-gate check --matrix /tmp/myembro-port-out/letter/representative-7/release-matrix.json --json /tmp/myembro-port-out/letter/representative-7/quality-report.json
```

`check-release`: **BLOCK — previous stage gate/release/user sewout confirmation pending**.
S1 승인 대기이며 게이트 도구·단계 상태·잠금 기준은 바꾸지 않았다.
별도 기능 진단은 기존 게이트의 5개 조건 **7/7 PASS**. 릴리스 통과나 독립 최종 승인으로
대체하지 않는다. 다음 단계는 총괄의 S1 검토 결과에 따른다.

### S2 앱 경로 이름 7건 재현

AWT 대체 경로가 아니라 Android `automaticLetteringState` → `renderLettering` →
`TextObject` → `PesWriter`를 호출하는 opt-in instrumentation이다. 4입력 UI와 같은 설정
(간격0, 가운데, 곡선off, 40wt, 원본 새틴 파라미터 기본값)을 쓴다.
테스트 APK만 기존 OFL Dancing Script fixture를 포함한다.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.lettering.LetteringSewtestInstrumentation
adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5560 shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.lettering.LetteringSewtestInstrumentation
mkdir -p /tmp/myembro-sewtest/s2
adb -s emulator-5560 exec-out run-as io.github.linker2k.myembro tar -C files/sewtest-s2 -cf - . | tar -xf - -C /tmp/myembro-sewtest/s2
# 각 파일에 실행. 별도 후프 크기나 게이트 기준 override 없음.
/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-sewtest/s2/jung-min-12mm.pes --text --style running --json /tmp/myembro-sewtest/s2/jung-min-12mm-quality.json
```

현재 출력은 승인 범위가 없는 TTF에 대한 기존 자동 러닝 기준선이다. 결과 표 `results.md`,
각 파일 게이트 JSON/log, `manifest.json`, 독립 PES 파서 왕복 좌표와 `overview.png`는
`/tmp/myembro-sewtest/s2/`에 둔다. 7건 중 6 PASS, Jung Min 30mm만 폭133.8mm로
SE400 100mm 후프 초과 BLOCK. 앱 렌더러도 동일하게 outsideHoop=true를 반환한다.

총괄이 이후 채택한 0.9–6.0mm 폭 정책은 `/tmp/myembro-quality-gate/auto-rules.md`의
‘총괄 채택’ 절이 출처다. 이 출력은 그 정책을 적용한 새틴 결과가 아니다. TTF는 레일이 없고
기존 자체 OutlineColumns 중축 추출기는 기본 경로 금지 상태이므로, 검증된 기둥 변환 없이
FontQuality의 추정 폭만으로 새틴 승인이나 슬라이더 상한을 만들지 않는다.

### S2+S3: 사람이 디지타이즈한 글꼴의 새틴 경로

TTF는 러닝(현재)·S4 이후 채우기만 제공한다. 새틴은 Ink/Stitch 자수 글꼴 SVG의
레일·렁만 사용한다. `inkstitch/lettering/InkStitchFont.kt`는 canvas `a8acced`의 커밋된 파서를
재사용한다. `InkStitchLettering.render`는 모든 새틴 속성을 `SatinParametersCodec`으로 읽고
각 `SatinColumn.toStitchGroups(previousStitch)` 결과를 S1 `fromGroups`에 한 번만 전달한다.
레이어를 native 좌표로 평탄화했다가 재잠금하지 않는다. 러닝 연결도 ties/trim/stop/최소 땀·점프
속성을 보존한다. 채우기·반복 러닝·bean은 이 경로에서 지원하지 않으며 조용히 바꾸지 않고 거절한다.
원본 SVG의 `True`/`False` 때문에 codec이 실패한 문제도 원본 `get_boolean_param` 규칙으로 수정했다.

이번 3건은 **로컬 시험용 Aventurina** 필기체다. 데이터는 공식 v3.3.0 배포물의
`fonts/src/aventurina/{ltr.svg.xz,font.json.xz,LICENSE}`를 읽었으며 원본 코드·공식 바이너리를 실행하지 않았다.
라이선스 파일은 CC-BY-SA 4.0, 어댑터 Sandrine Ratkoff Rojnoff, 원본 작가 HalfAsleepArtist의
사용 허락 기록과 underlying font 추가 제약 가능성을 명시한다. 따라서 **폰트 데이터는 저장소·앱에
번들하지 않고** `/tmp/myembro-port-out/letter/jungmin/inputs/`에만 둔다. 카탈로그 공개 승인과 다르다.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:inkFontNames
python3 tools/check-inkfont-names.py
```

`inputs/request.json`에 font id·텍스트·높이·배치 SVG·입력 SHA256을 기록한다. SVG는 원본 글리프의
곡선·속성·문서 순서를 보존하고 배치 transform만 적용한다. 반복 글리프의 ID는 충돌 방지를 위해
접두사를 붙인다. 이 fixture의 root viewport는 identity이며 원본 glyph가 root 직속인지 검증한다.
레이아웃은 포트가 만든 것으로, 공식 batch lettering 배치와 동등하다고 주장하지 않는다.

`tools/check-inkfont-names.py`는 3건만 기존 `quality-gate check --text --style satin`으로 검사하고,
공식 `/tmp/myembro-inkstitch-oracle/out/letter-jungmin/<id>.pes`가 있으면 땀 수·형상만 자동 비교한다.
기존 oracle 기준의 땀 수 5%·대칭 Hausdorff 0.2mm를 읽고 bbox 중심 평행이동만 허용한다.
기준 파일이 없으면 **미판정**으로 기록하고 성공 종료하지 않는다. 출처·파일 해시는 validation.json에 남긴다.

현재 세 건 모두 **BLOCK: 시작 잠금 없음**. 원본 J의 첫 러닝 요소가 `ties=3`으로 잠금을 끈 상태다.
이를 임의로 덮어쓰지 않았으며 게이트③도 변경하지 않았다. 20/30mm는 각각 폭124.6/186.8mm로
SE400 후프도 초과한다. 기준 H 높이로 환산한 글꼴 기록 범위는 약11.09–27.73mm이므로
30mm는 UI 허용 샘플이 아니라 범위 밖 진단 샘플이다. 공식 비교는 총괄이 지정한 codex 출력 대기다.

### 시작/트림 경계 force와 lettering 기본값 대조

총괄 지시에 따라 `InkStitchLettering.render`는 실제 시작·trim/stop·색 변경 뒤 첫 요소에만
원본 `force_lock_stitches=true`를 적용한다. S1 구현이나 게이트 기준은 바꾸지 않는다.
**중요한 원본 의미:** `lib/elements/element.py:lock_stitches`에서 force는 끝 잠금만 강제한다.
`ties=3`이면 force=true여도 시작 잠금은 null이다. S1도 같은 규칙을 따른다.
따라서 Aventurina J 첫 러닝의 시작 잠금 BLOCK은 force만으로 해소되지 않았다.
첫 요소의 원본 `ties=0`도 함께 적용할지 총괄에게 확인 요청했으며 임의로 켜지 않았다.
Jung Min 시험 범위는 이제 12mm 한 건, 20/30mm는 후프 범위 밖으로 제외한다.

원본 `font.py:render_text`/`batch_lettering.py`의 trim 기본값은 0/off이며 글자마다 트림하는
기본 옵션은 없다. 기본 ties=0/half_stitch, force=false, back_and_forth=true도 대조했다.
공식 대비 부족했던 16–22땀은 이전 canvas native 평탄화/재잠금 경로와 새 S1 group 경로를
구분해야 한다. 새 경로의 대표 AB 4/10/20mm는 각각 **42/42, 65/66, 89/91**땀이다.
첫 차이는 인덱스0에서 각각2/5/5로 이동했다. 4mm Hausdorff0.1mm, 10/20mm1.2mm라
완전 동등 통과로 보고하지 않는다. B의 정확히7.5mm 선분이 포트3분할/공식4분할되는 경계와
원본 glyph SVG 직렬화 정밀도가 남아 있다. 이를 새 휴리스틱으로 보정하지 않았다.

트림 차이(-2/-3/-3)는 lettering trim 옵션을 켜서 맞추면 안 된다. 원본 output.py는
`full_jump=True`, PEC writer의 `pec_encode`는 첫 점프 이후 모든 JUMP에 trimjump 플래그를
쓴다. 반면 우리 `io/PecWriter.kt`는 의도적으로 일반 JUMP를 TRIM으로 바꾸지 않는 계약이다.
writer는 이 단위에서 수정하지 않았으며 담당자와 별도로 정규화 계약을 맞춰야 한다.

초기 newline 공식 SVG는 실제 첫 줄만 들어 있었고, codex가 `font-newline-corrected`로 갱신했다.
갱신 출력은 rtl variant 없는 fixture를 위해 reversible=false인 별도 font.json을 쓴다.
대표 재현도 동일 메타데이터를 읽으며 이전 잘못된 한 줄 기준에 맞춰 포트의 두 번째 줄을 지우지 않는다.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:fontDefaultsAudit
python3 tools/breakdown-fonts-representative.py
```

이 스크립트는 codex `breakdown_fonts.py`와 같은 땀 수·트림·점프·첫 차이·bbox 중심 정규화
Hausdorff 수치만 대표4건(최대10건 제한)에 다시 계산한다. 원본96건을 재생성하지 않는다.
출력은 `/tmp/myembro-port-out/letter/font-defaults/breakdown/cases.{json,md}`다.
공식 raw 기본값 비교에는 `forceBoundaryLocks=false`를 명시한다. 이는 승인된 제품 경계 force
정책과 원본 기본값을 혼동하지 않기 위한 재현 인자이며 UI 설정을 추가하지 않는다.

#### 총괄 후속 확정: 첫 요소 ties=0 + force 적용

총괄 답변으로 시작·trim/stop·색 변경 뒤 첫 요소에 `ties=0`도 함께 적용했다.
원본 `LockStitches.fromParameters`가 기본 half_stitch를 만들며 S1 후처리는 한 번뿐이다.
내부 요소의 ties는 보존한다. 비교용 raw 기본값 모드(`forceBoundaryLocks=false`)에서는
이 두 제품 정책 override를 모두 생략한다. 출력용 배치 SVG에도 동일한 두 속성을 기록했다.
첫 바늘이 S1 lock 태그를 갖는 회귀 테스트와 전체 엔진360개 테스트가 통과했다.

**Jung Min 12mm: 기존 --text 게이트 PASS**, 공식 오라클 출력은 아직 없어 미판정이다.
20/30mm는 이번 판정 범위에서 제외했다. 대표 기본값 분해는 AB 4/10/20mm 42/42,65/66,89/91,
같은 reversible=false 메타데이터의 두 줄10mm 130/132다. 완전 동등 통과는 아니며
PEC 출력 트림 계약과 glyph 직렬화 정밀도 차이는 별도 후속 작업으로 남는다.

### Hausdorff 1.2mm 원인 추적과 수정

재현: `python3 tools/locate-font-deviation.py`. 최대10건 제한이며 두 방향의 최근접 점을
독립 PES 파서·KDTree로 계산한다. 바늘/명령 인덱스(0부터), raw·bbox 중심 정규화 좌표,
최근접 바늘, 앞뒤2개 바늘, 원본 glyph/path/subpath, 양 PES 해시를 JSON/표로 출력한다.
원본 SVG subpath와 공식 sewn run 개수가 같을 때만 순서로 요소를 대응시키고, collapse로
개수가 다르면 unresolved로 기록한다. 억지로 다른 요소 이름을 붙이지 않는다.

수정 전 10mm AB의 **공식 바늘36** raw(11.3,5.0), 정규화(3.8,0.0)의 최근접은
포트 바늘36 raw(12.5,-5.0), 정규화(5.0,0.0)로 거리1.2mm였다.
해당 요소는 **B / path17694 / subpath0**, 첫 가로획이다. 공식은 그 선분을4구간,
포트는3구간으로 나눴다. 20mm는 A/path17279/subpath0의 공식 바늘6도1.2mm 차이였다.
이는 **실제 샘플링 경로 차이**이므로 PEC 반올림·트림의 허용 차이로 처리하지 않았다.

원인: Ink/Stitch `glyph.py:_process_group/_move_to_origin`은 path를 문자열로 저장한다.
고정 의존성 inkex `EXTENSIONS_AT_INKSCAPE_1.4.1`, `paths/interfaces.py:PathCommand`의
`number_template="{:.6g}"`와 달리 포트는 변환된 좌표를 full precision으로 유지했다.
7.5mm / 2.5mm가 경계의 어느 쪽인지 달라져 원본 `ceil`의 구간 수가 달라졌다.
숫자 epsilon이나 땀 길이를 조정하지 않고, 원본의 **6자리 유효숫자 경로 직렬화**를
변환된 endpoint/Bezier control point에 적용한 뒤 분할하도록 수정했다. SVG viewport도
기존 원본6자리 고정소수 변환 공용 함수를 재사용한다.
`SvgPath.flatten`에 선택적 canonicalize 인자를 추가했으며 기존 호출은 정밀도가 바뀌지 않는다.
원호(A/a) 파라미터 직렬화 포트는 별도이므로 그 경우 기존 경로를 유지한다.

원본 확인: https://gitlab.com/inkscape/extensions/-/blob/EXTENSIONS_AT_INKSCAPE_1.4.1/inkex/paths/interfaces.py .
소스/의존성 코드는 읽기만 했으며 실행·설치하지 않았다.

수정 후 AB4/10/20mm **42/42·66/66·91/91**, 동일 메타데이터 두 줄10mm **132/132**.
모두 Hausdorff **0.1mm**다. 원본 좌표 직렬화와 최종0.1mm 격자 반올림에 남은
한 격자(0.1mm) 좌표 차이만 이번 대표 사례의 **허용 차이**로 기록한다.
트림 명령 수 차이(-2/-3/-3/-7)는 별도의 PEC writer 계약 차이이며 이 허용에 포함하지 않는다.
전체 제어 명령·전체 글꼴의 완전 동등/봉제 인증으로 확대하지 않는다.

수정 전 자료: `/tmp/myembro-port-out/letter/font-defaults/{deviation,breakdown}-before-precision/`.
수정 후: 같은 폴더 `{deviation,breakdown}/`; 특히 `deviation/max-deviations.{json,md}`.
회귀는 원본 테스트 폰트의 동일 A/B path 입력으로 7.5mm 경계·66땀을 확인한다.

### 트림 경계 분해 및 원본 PEC 동작 보존

재현: `python3 tools/locate-font-trims.py`. 대표4건에 대해 독립 PES decode의
실제 JUMP 이동 전후 바늘 좌표를 bbox 중심 정규화하고, 원본 배치 SVG의 line/glyph/path/subpath
endpoint와 대응시킨다. 공식/포트 경계 위치가0.2mm 이내일 때만 일치로 표시한다. 이 값은
기존 공식 비교 좌표 허용값이며 게이트를 바꾸지 않는다. 양 PES SHA256도 JSON에 기록한다.

10mm AB의3개 누락은 A/path17279 내부 subpath0→1, A→B 글자 경계,
B/path17694 내부 subpath0→1이다. 두 줄의7개 중에는 L1 B→L2 B 줄 경계도 있다.
따라서 글자마다 한 번 trim 옵션을 켜서는 원본과 같아지지 않는다.
원본 `font.py:render_text/_add_trims`, `batch_lettering.py:setup_trim`에서 기본값은
`trim_option=0/off`다. 1=line/2=word/3=glyph 옵션은 선택한 경우에만 trim_after를 추가한다.
이번 기본값은0 그대로 유지했다.

실제 원인은 `lib/output.py`의 full_jump 설정과 **pyembroidery1.5.1
PecWriter.py:pec_encode**다. 원본 PEC는 첫 점프 이후의 JUMP를 trimjump로 기록한다.
일반 JUMP를 유지하는 기존 우리 writer 계약을 전역 변경하지 않았다. 대신
`GeneratedPatternProcessor.fromGroups(..., pecJumpTrims=true)`라는 **자수 글꼴 전용 opt-in
출력 어댑터**로 실제 바늘을 꿰맨 뒤의 점프에 명시 TRIM을 보존한다. 기존 TRIM은 중복하지 않고
처음·연속 점프에는 새 트림을 추가하지 않는다. 바늘 좌표·땀 수는 바뀌지 않는다.
S1 잠금/실행/명령 anchor 인덱스는 추가된 제어 명령에 맞춰 이동시킨다.
원본 `StitchPlanBuilder` 기본값과 일반/가져온 도안의 writer 기본값은 그대로다.

원본 ties=3으로 꺼져 있던 내부 요소에서 PEC 트림 뒤 잠금 누락3곳이 새로 드러났다.
총괄이 확정한 thread-boundary ties=0 정책을 **S1이 실제 need_tie_in을 계산한 위치**에도
적용하는 opt-in `lockAtThreadBoundaries`를 추가했다. 시작/끝이 이미 정의된 커스텀 잠금은
유지하며, 꺼진 경우에만 worker의 기본 InkLockStitch(half_stitch)를 쓴다. 새 잠금 형태나
자체 거리 임계값은 없고, S1 후처리는 한 번이다. `forceBoundaryLocks=false`인 raw 공식 비교에서는
이 제품 override를 쓰지 않는다. 오라클용 이름 배치 SVG는 ties=0을 path에 표시하되
force는 시작/명시 trim 뒤 첫 요소만 설정하므로 source의 실제 경계 결정은 유지된다.

수정 후 AB4/10/20mm 및 두 줄10mm의 **트림 수 2/2·3/3·3/3·7/7**,
계산한 **15개 경계 전부 일치**. 땀 수42/42·66/66·91/91·132/132와 Hausdorff0.1mm 유지.
초기 원점 점프 수 차이(-1)는 남아 있으며 전체 제어 스트림 완전 동등으로 확대하지 않는다.
Jung Min12mm는 새 트림/재시작 잠금을 포함해 **2,076땀, 기존 --text 게이트 PASS**다.
공식 Jung Min 출력은 대기이므로 그 오라클 동등 판정은 미판정이다.
엔진363개 테스트 통과: PES 왕복 트림·바늘 불변·중복 트림 방지·S1 잠금 인덱스·재시작 잠금 검증 포함.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:fontDefaultsAudit :engine:inkFontNames
python3 tools/locate-font-trims.py
python3 tools/breakdown-fonts-representative.py
python3 tools/check-inkfont-names.py
```

수정 전: `/tmp/myembro-port-out/letter/font-defaults/trims-before-compatibility/`.
수정 후: `/tmp/myembro-port-out/letter/font-defaults/trims/trim-boundaries.{md,json}`.
S1 파일 수정은 글꼴 전용 출력·제품 경계 정책을 분리하기 위해 필요한 선택 인자/추적 보존에 한정한다.

### CI 고정 대표 글꼴 오라클 (4건)

`FontEquivalenceTest`를 일반 `engine:test`에 넣었으므로 `./gradlew check`에서 자동 실행한다.
CI JVM 검증 단계도 `./gradlew check`로 연결하고 해당 JUnit XML을 `font-equivalence-results`
아티팩트로 보존한다. 실패·예외가 있으면 일반 check가 실패한다. 별도 opt-in task나
환경 변수, 외부 /tmp 자료, 네트워크, Python, Ink/Stitch 실행은 필요하지 않다.

`engine/src/test/resources/inkstitch/font-equivalence/`는 공식 testfont AB4/10/20mm와
두 줄10mm의 **4건·331개 바늘 좌표**만 포함한다. 출처/공식 릴리스·바이너리/PES/
독립 decode/입력 해시와 newline reversible=false 보정 기록, GPL/OFL 고지를 함께 둔다.
총량 약52KB, 테스트 자체도 10건 이하·전체 묶음1MB 이하를 검사한다.
기대 좌표는 포트가 만든 값이 아니라 codex 공식 출력의 독립 decode에서 고정한 부분집합이다.

검사 항목은 정확한 땀 수, bbox 중심 평행이동만 허용한 대칭 Hausdorff≤0.2mm,
TRIM/STOP/색 변경의 순서·수·anchor≤0.2mm, 실 순서다. 기존 동결 오라클의 수치이며
게이트 기준을 바꾸거나 길이 통계를 차단 조건으로 되살리지 않았다. 초기 위치 JUMP 수 차이는
이미 문서화된 대로 제외한다. 모든 카탈로그 폰트·새틴의 독립 인증이나 실제 봉제를 대체하지 않는다.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check
# 4건만 재실행하려면
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*FontEquivalenceTest'
```

JUnit 출력: `engine/build/test-results/test/TEST-io.github.linker2k.myembro.engine.inkstitch.FontEquivalenceTest.xml`.
4건 모두땀 수42/42·66/66·91/91·132/132, 트림2·3·3·7, Hausdorff0.1mm로 통과했다.

검증 실행 결과: 엔진 전체 **367개 통과**, 고정 오라클4건 통과.
전체 `./gradlew check`는 앱 Lint **14개 오류**로 실패했다. 이 오류가 있는 파일은
이번 변경 전 HEAD와 동일하다(API26 리소스의 API27 windowLightNavigationBar 6건,
Compose ProduceStateDoesNotAssignValue 7건, app_name 한국어 번역 누락1건).
Lint를 끄거나 기준선을 추가하지 않았으며, 앱 담당 수정 전에는 전체 CI가 차단된다.

## 잠금·플랜·명령 포트

`LockStitchParameters`의 `ties`는 0=양쪽(기본), 1=시작, 2=끝, 3=없음이다.
`force_lock_stitches`와 전체 `disable_ties` 우선순위를 원본대로 적용한다.
`InkLockStitch`는 **half_stitch, arrow, back_forth, bowtie, cross, star, simple,
triangle, zigzag, custom**을 지원한다. 절대/상대 스케일, custom 숫자 패턴 및 SVG
endpoint 경로, END 반전, 마지막 두 anchor/direction 노드 제외 규칙을 보존한다.
원본 half_stitch는 최대 15점에서 0.5mm보다 먼 점을 찾고 1.5mm로 제한한다.
찾지 못하면 **0.5px**로 fallback한다(mm가 아니다). back_forth의 음수 연장 분기에
잠금 태그가 빠지는 원본 동작도 포트에서 임의 수정하지 않는다.

`StitchPlanBuilder.fromGroups`는 원본 `stitch_groups_to_stitch_plan`/`filter_duplicate_stitches`를
명시적으로 실행한다. 기본 collapse=3mm, 최소 길이=0.1mm, RGB 블록 결합,
이전 그룹의 최소 점프/force, tie-off→색변경→tie-in, TRIM→STOP 순서를 유지한다.
최소 길이 이하(<=) 점은 이전 보존 점이 JUMP이거나 현재 점이 잠금/제어명령이면 보존한다.
단순한 모든 0길이 점 삭제와 다르다. 최종 정수 격자 중복 제거는 별도 generated 경계에서 한다.

`ElementCommands`는 SVG 커넥터가 해석된 후의 ignore_object/layer, trim_after,
stop_after, starting_point/ending_point와 이전 fill_start/fill_end 별칭을 받는다.
ignore는 생성 자체를 생략하고, trim/stop은 요소의 마지막 비어 있지 않은 그룹에 OR한다.
시작·끝 점은 요소별 라우팅 담당이 사용한다. 이 파사드는 임의 좌표 이동을 만들지 않는다.

| 위치 | 원본과 차이 | 이유/한계 |
|---|---|---|
| Lock 직접 호출 | 2점 미만은 빈 결과 | 원본 StitchGroup 보호 조건을 재사용 가능한 API에서도 적용 |
| SVG lock parser | Inkex 대신 endpoint 전용 Kotlin 파서 | M/L/H/V/C/S/Q/T/A/Z endpoint만 필요; 곡선 control point를 바늘 위치로 사용하지 않음 |
| 유효성 검사 | 비유한 스케일/좌표는 조기에 거부 | 잘못된 파일·무한 확장 방지 |
| lock 타입 표시 순서 | END UI의 cross/bowtie 순서 차이는 단일 ID 목록으로 표현 | ID와 생성 좌표는 동일 |
| 빈 그룹 | 전체 빈 입력은 빈 플랜, 앞뒤 빈 그룹 제외 | UI 종료/빈 블록 인덱스 오류 대신 엔진 반환값 제공, 마지막 실제 그룹 tie-off 보존 |
| 그룹 minimum | per-stitch 값이 있을 때 그룹 값으로 덮어쓰지 않음 | 공유 계약의 세밀한 override 보존; Stroke 파사드는 원본 요소처럼 모든 점에 요소 값을 지정 |
| END | 원본 모델에 없는 END도 필터에서 보존 | 기존 machine API의 종료 명령 보장 |
| 명령 탐색 | Inkex XML·clone·커넥터 탐색 대신 resolved flags/points | SVG 가져오기 담당과의 명시적 통합 경계; 레이어 무시 판정 자체는 호출자 책임 |
| terminal command | 마지막 비어 있지 않은 그룹에 적용 | trailing empty 그룹에서 trim/stop이 사라지는 경우 방지 |

## Stroke·Ripple 포트

`StrokeParameters` / `RippleParameters.fromAttributes`는 `inkstitch:*`, XML 확장 이름,
로컬 속성 이름을 받고 공개 mm/percent 값을 유지한다. `Stroke`는 변환·클립 완료된 SVG px
폴리라인을 받는다. manual은 원래 노드 endpoint, running/zigzag는 평탄화한 곡선을 전달해야 한다.
원본 Inkex DOM 로딩·transform·curve flattening이 자동으로 포함됐다고 주장하지 않는다.

Running은 sleeve/angle interval·원 교차·45도 코너·even/fixed carry, 길이 목록, tolerance,
반복, bean 및 결정적 random length를 포함한다. Zigzag는 Python 짝수 반올림, 짝수 샘플,
평균 접선, 비대칭 pull compensation, 각도·폐곡선·반복 규칙을 따른다. Manual은 최대 길이로
분할하고 force가 없으면 잠금을 만들지 않는다. `Prng`는 공통 lettering 포트와 같은 API/코드로,
BLAKE2s counter stream의 hex-word 순서와 UTF-8 seed를 재현한다(신규 의존성 없음).

Ripple은 target/convex hull centroid, closed spiral, line/satin guide, 수동 배치,
줄 수/최소 간격·parity, skip/stagger/exponent, 축별 scale·회전·rungs, grid/grid-first,
반복·bean과 클립 다중 그래프 라우팅을 포함한다. 원본 getter 기본값을 사용한다:
`staggers=1.0`, `satin_guide_pattern_position=line_count`(UI default와 차이 있음).
클립은 JTS overlay, 경계 그래프, Euler 보정·경계 travel·연결 성분별 그룹으로 처리한다.
멀티패스/satin guide는 `RippleSatinGeometry`의 실제 SatinColumn rail/rung 샘플러가 필요하다.
이 공급자 없이 임의로 두 경로를 rail로 추측하지 않는다.

| 위치 | 원본과 차이 | 이유/한계 |
|---|---|---|
| Stroke 입력 | Inkex node 대신 transformed/clipped 폴리라인과 resolved commands | SVG 요소 파서/클리핑과 생성 알고리즘의 통합 경계 |
| empty running | None 대신 빈 List | Kotlin collection API |
| 잘못된 숫자 목록 | 빈/파싱 불가 목록 기본값 또는 유효성 오류 | NaN 전파·인덱스 오류 방지; 유효 입력의 기본값은 보존 |
| 자원 상한 | 입력·출력 100만 점, interruption 검사 | 모바일 메모리/취소 안전성; 근사 경로로 대체하지 않고 실패 |
| random 0 진행 | 정확히 0 길이면 설명 있는 예외 | 무진행 무한 루프 방지 |
| Ripple satin | centerLine/rungPairs/plotPointsOnRails 공급자 필요 | SatinColumn 담당 포트와 통합 전 임의 rail 근사 금지 |
| Ripple clip 그래프 | NetworkX 대신 순서 있는 Kotlin multigraph·정확 DP matching | GPL 호환 신규 의존성 없이 최소 경로 개수 목적 보존 |
| matching 한계 | 홀수 정점 60개/DP 100만 상태 상한에서 실패 | 원본 blossom보다 자원 상한이 낮음; greedy로 조용히 대체하지 않음 |
| 그래프 동률 | DP matching·단방향 Dijkstra가 같은 최적값의 다른 순서를 선택할 수 있음 | NetworkX blossom/bidirectional 경로와 동률 결정 방식 차이 |
| JTS/GEOS | 퇴화·접촉 도형의 overlay 조각/순서가 달라질 수 있음 | 기하 라이브러리 대체의 수치적 차이; 점 단위 완전 일치 미보증 |

원본의 특이 동작도 보존한다: Ripple 클립 교집합이 빈 경우 원래 stitches를 반환하고,
travel route가 없으면 endpoint fallback 후 경계 <1px 필터를 적용한다. 시작 travel marker는
거리 필터 전 추가된다. 연결 성분 정렬은 terminal travel 제거 전에 수행하므로 제거 후
겉보기 순서가 역전될 수 있다. 이는 새로 안전한 클립 동작을 보증한다는 의미가 아니다.

### 독립 게이트와 검증 범위

고정 SVG는 `engine/src/test/resources/inkstitch/`에 두고 PES는 테스트 실행 중 생성한다.
알고리즘 테스트는 읽은 수식·제어 흐름의 분석 기대값이며 Ink/Stitch 실행 비교는 하지 않았다.
clone trim/stop OR 의미와 결정적 출력 성질은 Kotlin 회귀로 검증한다.

2026-10-24의 Stroke 4개(running/manual/zigzag/bean) `check-release` 결과는 **BLOCK 0/4**다.
`/tmp/myembro-worker-stroke-{matrix,gate}.json`에 독립 도구의 원문 판정을 남겼다.
이 게이트의 매트릭스는 모든 입력을 텍스트로 판정하고 `J→S0→S0.28→S0.28` 시작 및
0.27–0.65mm 잠금 범위를 강제한다. 원본 기본 half_stitch의 통상 0.75mm 반전은 인식하지 못해
TEXT_START_SEQUENCE_MISSING/START_LOCK_MISSING/END_LOCK_MISSING으로 차단됐다.
원본 기본값을 0.28mm로 바꾸거나 게이트를 수정해서 통과시키지 않는다.
일반 도안 검사 결과와 별개로, 담당 Codex의 원본 잠금/비텍스트 정책 판정 전에는 병합 차단이다.
이 매트릭스의 font 이름은 SVG fixture 식별자이며 실제 전체 폰트 릴리스 매트릭스가 아니다.


### S3 앱 연결: 기본 타타미+바탕 땀

letter S2기반111ae77을 통합하고 TTF 채우기·6.0mm 초과 레일/센터라인 새틴을
DefaultTatami→S1그룹으로 연결했다. 자동 표시에 채우기를 추가하고 높이 상한은 스윕90mm다.
[구현 범위·근거·테스트 출력](s3-fill-app-integration.md)을 기록했다.
`./gradlew :engine:check :app:assembleDebug :app:testDebugUnitTest`: BUILD SUCCESSFUL in19s,
engine552/app176 failures0. 통합6건, bundled Jua TTF ‘자수’1230땀의 외부 게이트PASS1/1.
원격CI/실기승인은 별도이며 여기서 멈추고 총괄 검토를 기다린다.


### S1 생성 후처리 통합 기록

`GeneratedPatternProcessor.fromGroups`는 원본 플랜 생성 후 어댑터와 최종 정수 중복 제거를
적용한다. `finish(EmbPattern)`는 **기존 앱이 새로 만든** native 패턴에만 호출하는 bridge다.
레터링 running/bean/satin/fill, 직접 SatinColumn, digitizing running/tatami의 생성 끝에서 실행한다.
Bean 확장 전에 잠금을 넣어 잠금까지 삼중화하지 않으며, 내부 fill 결과를 다른 생성기에 넣을
때는 추적 가능한 우리 잠금만 제거한 본문을 사용한다.

편집기에서 가져온 PES/PEC/DST는 raw sewing이다. 객체 합성은 이미 완성된 각 객체의 바늘
순서를 보존하며 가져온 도안 전체에 잠금을 다시 붙이지 않는다. 생성 객체의 배율/자유 회전은
우리 잠금을 제거하고 변환한 본문에 재생성하여 잠금의 실제 mm 길이를 유지한다. 정수 이동·
90도 회전·미러링처럼 격자를 보존하는 변환은 바늘과 추적 좌표를 함께 변환하므로 반올림으로
0.1mm가 어긋나지 않는다. 가져온 파일의 reader/writer raw 왕복 경로는 건드리지 않는다.

| 위치 | 원본과 차이 | 이유/한계 |
|---|---|---|
| native generated bridge | 모든 명시적 JUMP/제어 명령을 경계로 보존 | 이미 needle-up으로 확정된 앱 명령을 3mm collapse로 재봉으로 바꾸지 않음; typed groups는 원본 collapse 유지 |
| native 최소 길이 | S1부터 원본 기본 0.1mm 적용 후 정수 격자의 연속 중복 STITCH 제거 | 이전 min=0 보존 정책은 기본 경로에서 제거 |
| native 잠금 | S1부터 원본 `InkLockStitch()` half-stitch 사용 | 별도 폴리라인 잠금 휴리스틱 제거; 원본 fallback과 방향 규칙 유지 |
| 양자화 중복 | native 격자에서 연속 동일 STITCH 제거 | 0.1mm로 양자화된 바늘의 중복 천공 방지; 제어 명령과 JUMP 뒤 첫 STITCH 보존 |
| native 최종 격자 | 연속 STITCH→동일 STITCH만 삭제, JUMP→첫 STITCH는 보존 | 원본 px에서 다른 점도 0.1mm 양자화 후 0길이가 될 수 있음 |
| native 명시적 최대 길이 | 호출자의 maxStitchLengthMm를 정수 격자에서 재검증/분할 | 0.2mm 등 기존 레터링 API의 상한에 잠금도 맞춤; 0.1mm 대각선은 필요한 축 이동 2개로 표현 |
| provenance | finish/lock indices/run anchors/command anchors/max length 메타데이터 추가 | 임의 잠금 모양으로 본문 좌표를 추측하지 않고, 오직 우리 잠금만 strip/rebuild |
| native 재생성 | 기본 호출은 원본 `SewingOptions()`로 재생성; 명시적 옵션 API는 엔진에만 유지 | 앱에 상세 설정·타입 선택 UI 없음. 원본 본문을 별도로 보존하여 길이 필터를 다시 적용할 수 있음 |
| 합성 결과 | 객체별 잠금 유지, 합성 전체에는 finished 마커를 붙이지 않음 | 서로 다른 raw/생성 패턴을 다시 전역 잠금하지 않음; 합성 파일 자체는 완성 machine pattern |

### 통합 담당 메모

- embro-letter의 별도 `StitchPlanFinisher`/`InkLock`와 이 공통 processor를 중복 연결하면 안 된다.
  이 계약과 원본 기본값을 공유하고, 원본과 다른 최소 길이·잠금 정책은 명시적 옵션으로 분리한다.
- Ripple의 실제 SatinColumn provider 연결은 아직 별도 담당 API 확장이 필요하다. 현재
  public plotPointsOnRails는 pull 보상/0.01mm clamp를 적용하므로 raw 0.01px sampler의 대용이 아니다.
  필요한 API는 **spacingPx·offsetPx·offsetProportional·useRandom을 받는 raw rail sampler**,
  방향/정밀도/rung 분할 후 flattened sections, subpath→rail/rung 판별 결과다.
- provider centerLine은 `offsetProportional=(-0.5,-0.5)`인 raw pair의 first 좌표이며 running 재샘플링을
  하지 않는다. rungPairs는 원시 rung endpoint가 아닌 각 flattened section의 끝 rail pair다.
  reverse/swap flags는 centerLine/rungPairs와 plotPointsOnRails에서 일치해야 한다.
- SVG 입력/커넥터 해석과 해당 새틴 provider가 연결되기 전 전체 SVG DOM 기능 완료로 보고하지 않는다.

### 재현

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug
python3 tools/check_inkstitch_worker.py --general
# 기능별: --family locks / --family stroke / --family ripple
```

게이트 도구가 없는 환경에서는 `--gate-dir`로 **독립 담당자가 제공한** 도구를 지정한다.
이 helper는 Ink/Stitch를 실행하지 않고 생성된 PES 경로의 매트릭스를 독립 도구에 제출한다.
일반 모드 PASS가 릴리스 BLOCK을 덮어쓰지 않으며 종료 코드는 릴리스 실패를 그대로 반영한다.
PES/리포트는 build 또는 /tmp에만 생성하고 바이너리를 커밋하지 않는다.


### 2026-10-24 최종 검증 기록 (worker)

- **엔진 379개, 앱 167개 테스트 통과**, `:app:assembleDebug` 성공.
  신규 `inkstitch` 테스트 101개 포함. 테스트·빌드 로그:
  `/tmp/myembro-worker-final-attributes-tests.log`.
- `StitchPlanContractTest`의 6개 기존 파일 raw 왕복→PES 바이트 동일 보장은 계속 통과한다.
- SVG fixture 17개: 잠금 10종+실제 기본값 1개, Stroke 4개, Ripple 2개.
  Ripple fixture도 실제 요소처럼 기본 시작·끝 `InkLockStitch`를 명시하고 메타데이터를 검사한다.
- `python3 tools/check_inkstitch_worker.py --general` 실행:
  **일반 도안 PASS 17/17**, **필수 release BLOCK 17/17, exit 1**.
  원문 매트릭스·결과·로그: `/tmp/myembro-worker-inkstitch-quality/`.
  차단 사유는 TEXT_START_SEQUENCE_MISSING 및 일부 START/END_LOCK_MISSING이며,
  이 실행에서 다른 바이트 구조·좌표·길이·밀도 차단 사유는 보고되지 않았다.
- 고정 게이트 threshold SHA-256:
  `313eaf7dd85125d5c0340f9d87b5c14ca6cc9a640faf360214c0d300c2b4bca9`.
  도구·임계값·pin 해시는 수정하지 않았다. 일반 PASS는 release 차단을 대체하지 않는다.
- **병합 보류**: 독립 담당 Codex가 비텍스트/원본 잠금에 맞는 판정 정책을 제공해야 한다.
  Satin provider와 SVG DOM 경계의 통합 대기도 위 메모대로 남아 있다.

| 기능 커밋 | 원본 | 확인 |
|---|---|---|
| `dc38aac` 계약·어댑터 | stitch_plan, elements/element, geometry, units | 계약 7개, 수동 SVG 어댑터 smoke 외부 PASS 1/1 |
| `d4c6776` 잠금·최소 길이·명령·generated bridge | lock_stitch, stitch_plan, color_block, element | 전용 37개, 잠금 SVG 11개; release BLOCK |
| `63eb068` running/bean/zigzag 샘플러·PRNG | running_stitch, utils/prng | 전용 12개; 실제 요소 SVG 검증은 Stroke와 함께 |
| `ec620c1` Ripple·클립 그래프 | ripple_stitch, tatami_fill | 전용 29개, SVG 2개; release BLOCK |
| `26aa2d3` Stroke 파사드 | elements/stroke, elements/element | 전용 13개, SVG 4개; release BLOCK |
| `a77f591` 기존 생성 경로 연결 | 앱 고유 연결, 위 공통 후처리 | 통합 3개 및 기존 전체 회귀 통과; raw/본문/변환 보존 |


## S1 개정 — 자동 마무리만 적용 (2026-10-24)

개정한 main 작업트리 `docs/ui-stories.md`의 **실패 없는 디자인** 원칙을 따른다.
봉제 설정 섹션, 잠금 타입 선택/미리보기, 고급 파라미터 및 점검 요약 줄은 배포하지 않는다.
10종 잠금과 고급 파라미터 API는 원본 검증·후속 개발용 엔진 기능이며 화면에 노출하지 않는다.
Ripple/Satin provider 연결, Bean 등 추가 생성 기능 활성화는 이번 단계에서 제외한다.

- 기본 잠금: 원본 `half_stitch`, `ties=0`, `force_lock_stitches=false`.
- 최소 땀: 0.1mm. 그룹 최소 점프: 3mm. `trim_after=false`는 원본 기본값이며,
  기존 명시적 TRIM 및 객체 사이 TRIM은 보존한다. 근거 없는 자동 트림 거리 상수는 추가하지 않는다.
- 기존 native 생성 bridge의 자체 폴리라인 half-lock과 min=0 기본 정책을 제거한다.
  typed `StitchPlanBuilder`는 원본 그룹 collapse·색·trim/stop 규칙 그대로 사용한다.
- native 패턴의 이미 명시된 JUMP는 needle-up 명령으로 보존한다. SVG 그룹 provenance가 없는
  바늘 명령에서 빈 공간을 재봉으로 바꾸지 않으며, 이 호환 경계는 원본 그룹 collapse와 구분한다.
- 기존 레터링/편집기의 생성 패턴은 같은 기본 후처리를 사용한다. 이전 `.myembro`의 생성
  객체도 다시 계산한다. raw PES/PEC/DST에는 잠금/최소 길이 정리를 적용하지 않는다.
  원본 PES 저장·공유·USB 전송의 바이트 전달은 유지한다.
- 앱 기본값은 고정값이며 `sewing_options` 저장소나 사용자 설정을 읽지 않는다.
  미리보기·봉제 점검·PES 내보내기는 같은 기본값을 사용한다.

### 고정 SVG 매트릭스 재현

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:verifyInkstitchWorkerBoundary :engine:runInkstitchWorkerMatrix
# 동등한 CLI (입력/출력 경로 옵션은 --help):
python3 tools/generate_inkstitch_worker_matrix.py
```

- 입력 목록: `/tmp/myembro-quality-gate/matrix-inkstitch.json`의 `stitch_plan/`, `ties/`, `stroke/`.
- 실제 입력: `/tmp/myembro-inkstitch-oracle/inputs/`의 materialized SVG. 원래 입력과 SVG의
  SHA-256을 검증하며 `.oracle.json`에서는 식별·출처 필드만 읽는다.
- 출력: `/tmp/myembro-port-out/worker/{stitch_plan,ties,stroke}/<id>.pes`.
- 목록·입력 해시·실행 커밋·dirty 여부: 같은 폴더 `manifest.json`, `inputs.tsv`.
- 활성 `defaults-v2`: 168건(각 기능 56건) 전부 생성. 이 SVG에는 기본값을 덮어쓰는 숫자 속성을 주입하지 않는다.
  이전 탐색 매트릭스 507건/505개 생성 기록은 과거 기록이며 새 기본값 판정에 섞지 않는다. GENERATED는 동등 판정이 아니다.
- Runner는 Inkex CSP 제어점→transform→0.1px subdivision, 전체 명령의 bbox 중심,
  pystitch translate/scale 평가 순서와 Python 짝수 반올림을 원본처럼 적용한다.
  공개 adapter와 편집기 배치 좌표 계약은 변경하지 않는다.
- 원본 출력 비교용 `PesWriteSettings(inkstitchCommands=true)`는 pystitch PEC의 viewport prefix,
  첫 JUMP 이후 trim/jump, trailing STOP duplicate 색 이벤트를 재현한다.
  이 opt-in 프로필은 원점을 바꾸므로 앱의 기존 배치·가져오기 경로에는 적용하지 않는다.
  일반 writer의 기본 바이트는 유지한다. 공식 PES의 모든 바이트 동일성을 주장하지 않는다.
- 원본 Ink/Stitch/Inkex/pystitch는 읽기만 했으며 실행·의존성 설치를 하지 않았다.

독립 판정 제출 커맨드(원본 실행이 아닌 embro-codex 비교 도구):

```sh
python3 /tmp/myembro-inkstitch-oracle/compare_matrix.py --ports /tmp/myembro-port-out/worker/manifest.json --report /tmp/myembro-port-out/worker/independent-comparison.json
```

PE-Design 고정 잠금 바이트에 맞추려고 출력이나 임계값을 바꾸지 않는다. 병합 판정은
독립 담당 Codex의 비교·재보정 게이트가 맡으며, 실제 SE400 봉제 검토는 별도 완료 조건이다.

### 원본 기본 잠금의 경계 사례 — 안전 승인과 구분

`half_stitch`는 15개 후보 중 시작점에서 0.5mm보다 먼 점이 없으면 방향을 **+x**, 길이를
**0.5 SVG px**로 사용한다. 따라서 `(200,198)→(198,198)` native 경로에서는 잠금 바늘이
`(201,198)`까지 나올 수 있다(도형 경계보다 0.1mm 바깥). 원본 그대로의 동작이며 임의로
도형 안으로 잘라 내거나 별도 폴리라인 잠금으로 대체하지 않는다.

`DigitizingTest`는 실제 후처리 출력에서 추적된 기본 잠금 인덱스만 제외하고 **필터된 본문**의
구멍·오목부·분리 영역 불변식을 검사한다. 삭제 전 본문을 복구하는 `stripGeneratedLocks`로
검사하지 않는다. 별도 회귀는 위 fallback 좌표와 전체 잠금의 영역 포함 보장이 없음을 명시한다.
본문 검증 통과나 oracle 동등이 잠금까지 항상 도형 안에 있다는 안전 승인을 뜻하지 않는다.
이 경계 사례와 native bridge의 명시적 JUMP 보존은 독립 Codex 검토 항목이며, 실제 봉제는 미검증이다.

### 이전 탐색 매트릭스 독립 비교 기록 (matrix-v1, 이력)

`compare_matrix.py`(criteria SHA-256
`fbbf03ab6872d767b2c996f46f3c1cf3055b0b9f8f54b4c0a35fccdc095d6b74`) 원문 결과:

| 제출 기능 | 동등 | 불일치 | 미판정 |
|---|---:|---:|---:|
| stitch_plan | 168 | 0 | 1 |
| ties | 168 | 0 | 1 |
| stroke | 169 | 0 | 0 |

미판정 2건은 `ORACLE_UPSTREAM_ERROR`로 공식 기준 출력의 오류다. 제출하지 않은 S2 이후
기능은 이 기록에 포함하지 않는다. 이는 **PES-output comparison**이며 mechanical release gate,
원본 소스 전체 감사, SE400 실제 봉제 통과와 별개의 판정이다. 최종 커밋에서 매트릭스를 재생성해
`manifest.json`의 커밋과 clean 여부를 고정한 다음 Codex에 제출한다.

### S1 자동 기본값 빌드 검증

- `:engine:test` **393개 통과**, `:app:testDebugUnitTest` **171개 통과**(실패/skip 0).
- `:app:assembleDebug` 성공. 로그: `/tmp/myembro-worker-s1-automatic-tests.log`.
- 원본 adapter의 6개 fixture 바이트 보존 및 writer 기본 프로필의 기존 바이트 회귀 유지.
- 앱 회귀: 고정 기본값/이전 프로젝트 재계산, 잠금 10종 옵션에도 raw PES의 바이트·땀 불변,
  viewer/USB의 프로젝트 PES 생성 일치. 고급 타입 옵션은 테스트용 엔진 입력일 뿐 사용자 UI가 아니다.
- 변경된 기본 잠금 때문에 이전의 전체 도형 포함 불변식은 더 이상 성립하지 않는다. 이를
  테스트를 느슨하게 해 숨기지 않고, 실제 필터된 본문 검증과 명시적인 원본 fallback 반례로 나눴다.
- SE400 실물 봉제는 수행하지 못했다. 최종 병합·배포 가능 판정은 독립 Codex와 사용자 실봉제 검토가 필요하다.

### 활성 기본값 매트릭스 defaults-v2 제출

작업 중 독립 Codex가 매트릭스를 개정했다. Runner는 새 scope의 독립 동결 파일
`/tmp/myembro-inkstitch-oracle/out/defaults-v2/oracle-freeze.json`을 선택하고, 각 identity record의
동결 SHA-256·scope·id 및 원본 SVG와 materialized SVG의 **바이트 동일성**을 검사한다.
원본/좌표/PES 출력은 생성 입력으로 사용하지 않는다. 이전 scope도 명시적으로 지원하되 자료를 섞지 않는다.

- 활성 matrix SHA-256: `17f10563b6743539dba46f6db294f6f63b5f2db29b74d251c6521404755a5fe0`.
- 입력: `inputs/defaults-v2--<id>.svg`. 출력과 재현 커맨드는 위와 동일하다.
- **stitch_plan 56/56, ties 56/56, stroke 56/56 동등**, 불일치·미판정 0.
- 기계적 릴리스 게이트 v2 (`check-release --stage S1`): **144 PASS / 24 BLOCK**.
  threshold SHA-256: `cb9c68f379056ec8215c9e7673cedb5a5d166b47484923ef710c9b870bb5aff1`.
- 같은 독립 검사기에 **공식 Ink/Stitch 출력**을 제출해도 같은 24개 `crossing_strokes`가 BLOCK이다.
  세 구간의 시작 S0가 `UNEXPLAINED_ZERO`로 집계되고 작은 4–10mm 입력에서는 본문 짧은 땀 비율도 차단된다.
  functional 시작/끝 잠금은 검출된다. 이 결과를 임의 예외로 통과시키거나 원본 기본값을 변경하지 않는다.
- `independent-comparison.json`, `release-matrix.json`, `release-gate.json`,
  `reference-release-matrix.json`, `reference-mechanical-gate.json`은 `/tmp/myembro-port-out/worker/`에 둔다.
  릴리스 매트릭스의 font 축은 `feature/shape` fixture ID이며 제품 폰트·안전 크기 범위가 아니다.
- **S1 병합/배포 승인 대기**: Codex의 남은 게이트 검토와 사용자 실제 봉제. S2 이후로 자동 진전하지 않는다.
  코드·단위 테스트·빌드는 준비됐으며 외부 검토/봉제 완료 시점은 미정이다.

### main 95a7447 통합 후 전체 check

main의 Lint 수정·S1 처리 통계/옵션을 병합했다. S2 PEC 트림과 경계 잠금 옵션은
최신 S1 처리 통계를 보존하며, fromGroups 완료 결과에 기본 sewing options 메타데이터를
기록해 앱에서 같은 기본 설정으로 재호출해도 원본 글꼴 잠금/트림이 재처리되지 않게 했다.
크기 변환 회귀 검사는 이전 고정 잠금 길이 대신 변환된 본 땀에 현재 S1 기본값을 적용한 좌표와 비교한다.

`./gradlew check`: **BUILD SUCCESSFUL**. 엔진450개, 앱 Debug177개·Release177개 실패0,
Android Lint 통과. 공식 고정 오라클4건도 땀 수·트림·좌표 검사를 유지한다.
CI는 main의 모든 브랜치 push 트리거와 전체 engine-test-results 아티팩트를 따른다.

### codex/model + staging/s2s3 통합 (2026-10-24)

- model `3170545`에 staging `df7da34`를 병합했다. 실제 충돌은 8개였고
  `ci.yml`은 자동 병합됐다. 모든 브랜치 push CI와 model의 Python 집계 테스트 단계를 유지했다.
- `GeneratedPatternProcessor`와 `StitchPlanBuilder`는 staging의 letter/S1 파일과
  바이트 동일하다. PEC 점프 트림·실 경계 잠금·처리 통계를 보존한다. model의
  기본 sewing-options 메타데이터 기록은 staging에도 이미 포함돼 재정의하지 않았다.
- Gradle의 letter 재현 태스크 3개는 model에도 이미 같은 정의가 있어 중복을 만들지 않았다.
  타타미 스윕·오라클 경계 source set·TTF 스윕 classpath 태스크와 JGraphT 의존성을 유지했다.
- 양쪽 고지·문서의 모든 섹션을 보존했다. 테스트는 실제 필터 후 본문에 타타미 행 위상을
  검사하고, 변환된 글자의 잠금 좌표를 S1 재생성 결과와 비교하면서 model의 길이 검사를 유지한다.
- `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check --console=plain`:
  **BUILD SUCCESSFUL in 1m 10s**, 67 tasks (58 executed / 9 up-to-date).
  JUnit XML: 엔진 **571**, 앱 Debug **177**·Release **177**, 실패·오류·skip **0**.
  Android Lint 통과. [명령 출력](checks/model-s2s3-check.log)을 함께 기록했다.
- `python3 -m unittest discover -s tools/tests -p 'test_*sweep.py' -v`:
  **Ran 11 tests; OK**. 양쪽 문서 제목 보존 및 staging 공통 처리 파일 동일성도 코드로 확인했다.
- [TTF 표본 스윕 결과](s3-ttf-fill-sweep.md)는 병합 전 고정 엔진의 기록이다.
  병합 후 전체 check의 타타미 공식 동등성·TTF 통합 테스트도 통과했다.
  원격 CI와 사용자 실제 봉제는 위 로컬 검사 결과와 구분한다.

## claude-canvas S3: Ink/Stitch 자수 글꼴 카탈로그·목록 (브랜치 `claude/inkfonts-s3`)

`staging/s2s3` 위에 올렸습니다. 글꼴 읽기·배치·봉제(`engine/.../inkstitch/lettering/InkStitchFont.kt`, `InkStitchLettering.kt`)는 embro-letter 구현을 그대로 씁니다. 이 브랜치가 더하는 것은 다음뿐입니다.

- 카탈로그 `app/src/main/assets/fonts/inkstitch-catalog.json`: 재배포 가능한 글꼴의 고정 URL·sha256, 글리프 이름, 허용 높이 범위. `tools/inkstitch_catalog.py`가 만듭니다.
- 허용 높이: `InkStitchFontSweep`(opt-in 엔진 테스트)가 글꼴 기록 범위(60mm 상한)를 2mm 간격으로, 텍스트 3종을 `SewoutCheck`로 판정하고, 가장 긴 연속 통과 구간을 씁니다. 결과는 `docs/inkstitch-font-sweep.json`에 둡니다. 후프 초과는 범위를 줄이지 않고 따로 셉니다(총괄 확인).
- 목록 UI: "검증된 자수 글꼴"을 맨 앞에 두고(이름과 땀 미리보기), 입력 글자가 없는 글꼴은 흐리게·선택 불가로 둡니다. 높이 슬라이더는 허용 범위로 제한합니다.

### 글꼴 92/96의 잔여4건 계산 분해

대상은 독립 비교 `latest-letter-fonts/df7da347cda358092a4ee29c19fe256474f12913/`의
`forceBoundaryLocks=false` 4건이다. testfont·압축·multifile·압축multifile 모두
**150mm BAAB**이며 원인은 동일하다. 정책상 시작 잠금을 강제하는 true 결과와 혼합하지 않는다.

| 지표 | 공식 / 포트 (4건 공통) |
|---|---|
| 바늘 수 | 850 / 850 |
| Hausdorff (bbox 중심 평행이동만) | 0.1mm |
| 전체 트림 | 8 / 7 |
| 첫 바늘 command index (0부터) | 4 / 1 |
| 첫 바늘 이후 제어 순서·수 | 일치 (TRIM 7 / 7) |
| 첫 바늘 이후 제어 위치 최대 편차 | 0.0mm |

공식 초기 명령은 `J(-184.6,75) → J(-297.1,75) → T(-297.1,75) →
J(-409.6,75) → S`(mm)이며 포트는 `J(0,-75) → S`다.
공식 첫 J는 PEC viewport 위치 prefix이고, 다음 **225mm** 위치 이동은 축당 최대
**204.7mm**를 넘어 **112.5mm 두 구간**으로 나뉜다. 따라서 첫 실제 바늘 전에만
추가 trimjump가 하나 생긴다. 전체850개 바늘 경로와 봉제 중 트림7개에는 차이가 없다.

원본 대조(읽기만):
- Ink/Stitch `lib/output.py:write_embroidery_file`
  @b0edd96311ece82ee48816dc93466274b12c2a9c: origin translate, full_jump/trims 출력 설정.
- pystitch `PecWriter.py:pec_encode`, `MAX_JUMP_DISTANCE=2047`
  @8fc0b113e02ea81d9a507bfad3f7fd624a8895bd: init에서 첫 J만 jump, 이후 J는 trimjump.
- 같은 커밋 `EmbEncoder.py:interpolate_gap_stitches`, `jump_to`: 축 최대 길이로 ceil 분할.
- 포트 `io/PecWriter.kt:normalizePattern`의 원점에서 시작하는 이동 정규화와 비교.

**허용 차이로 문서화**: 첫 바늘 이전의 절대 배치/PEC 직렬화에서 생긴 needle-up 트림1개다.
글자 경로를 바꾸거나 앱 PES 원점을 공식 SVG의 배치로 억지로 옮기지 않는다.
공식 비교기의 `TRIM_STOP_COLOR_ORDER`와 **92/96 판정은 변경하지 않았고**,
독립 판정자가 이 분해 근거를 검토해야 한다. 일반 트림 누락의 포괄 예외로 확대하지 않는다.

재현: `python3 tools/breakdown-font-residuals.py` (JSON/gzip 읽기만, 최대10건).
입력 PES SHA·캐시 identity를 검사하고 처음 바늘 이후 모든 제어 명령과 좌표를 비교한다.
출력: `/tmp/myembro-port-out/letter/font-residuals/residuals.{json,md}`.
검증: 4건 모두 `INITIAL_NEEDLE_UP_TRIM_ONLY`, unresolved0, 스크립트 종료0.
엔진 생성 로직·게이트·오라클 캐시는 수정하지 않았다.

### 글꼴 NPE7건·bean/fill 연결 회귀

원인: `InkStitchLettering.render`가 파라미터의 keys를 순회하면서 `params[key]!!`를
호출했다. accessor는 빈/공백 속성에서 null을 반환하므로 NPE가 났다.
원본 `lib/elements/element.py:get_param`@b0edd96311ece82ee48816dc93466274b12c2a9c는
`.strip()` 후 빈 값이면 default를 사용한다. 이제 빈 속성을 제외하고 해당 포트의 기본값을 쓴다.
7글꼴 모두 빈 속성이 있었다: chicken_scratch164, digory_doodles_bean193,
emilio_20_applique8, emilio_20_bold8, manga_impact347, precious10, roaring_twenties_KOR_small368개.
Roaring Twenties에는 `.&` 숫자도 있어 `get_float_param`처럼 SVG 숫자 실패 시
기본값으로 처리하는 `SatinParametersCodec.decodeSvg`를 추가했다. 프로젝트 `decode`는
기존 엄격한 입력 검증·round-trip 계약을 유지한다.

연결(새 스티치 알고리즘 구현 없음):
- InkRun → worker `elements/Stroke` + `StrokeParameters.fromAttributes`: bean/repeats,
  전체 subpath의 종료 trim/stop을 원본 facade처럼 마지막 그룹에 한 번 적용한다.
- InkFill → model의 staging/s2s3 **4899d66**의 `FillParameters`, `FillUnderlay`,
  `TatamiFill/FillRouting`과 관련 파일을 변경 없이 가져왔다. JGraphT 및 라이선스도
  같은 snapshot의 의존성과 고지를 따른다. 기존 native `InkAutoFill`은 변경하지 않았다.
- `FontTatami`는 좌표·그룹 연결 어댑터다. SVG px를 유지하고 SVG fill-rule로
  영역/홀을 구성하며, 컴포넌트별 언더레이→본 채움, 원본 최근접 다음 컴포넌트 끝 위치,
  태그 jump 분리, 색·원본 잠금/최소 길이·trim/stop을 S1 한 번에 넘긴다.
  원본 대조: `fill_stitch.py:to_stitch_groups/do_underlay/do_tatami_fill` (동일 고정 upstream).
  본 단위는 기본 tatami/auto_fill만 연결한다. 다른 fill_method를 임의로 태타미로 바꾸지 않는다.

`InkFontElementRegressionTest`: CI에서 외부 파일 없이 빈 속성·잘못된 숫자·bean·
홀 있는 fill/PES 왕복4건 실행. 별도 로컬 글꼴7건은 환경변수로 release 데이터 경로를
지정해 실행하며 입력 폰트는 저장소에 번들하지 않는다. KOR_small도 실제 스윕처럼
**Hamburg**로 재현한다(이 글꼴의 기록은 Latin이며 가나다를 지원한다고 가정하지 않는다).
model의 FillParameters/FillCore/FillUnderlay/FillRandomRunning 테스트도 그대로 포함했다.

```sh
INKSTITCH_REGRESSION_ROOT=/tmp/myembro-port-out/letter/font-crashes/inputs \
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check
```

결과: 엔진502개·앱 Debug/Release 각177개 실패0, Lint 및 전체check 통과.
로컬7글꼴 모두 렌더·PES 왕복 통과, 기존 고정 오라클4건 통과.
Hamburg 땀 수: chicken1370, digory1870, applique23914, bold18588,
manga1743, precious5289, KOR_small826.
재현 파일·입력 해시·빈 속성 예시·전후 XML·출력:
`/tmp/myembro-port-out/letter/font-crashes/`. 이는 crash/연결 회귀 판정이며
독립 게이트·실제 봉제·전체 카탈로그 인증으로 확대하지 않는다.

## S5 별도 원본: 흑백 윤곽·다각형 부분 포팅

Ink/Stitch에 없는 사진 입력 준비 기능이며, Ink/Stitch 코드를 포팅한 것으로 표시하지 않는다.
원본 Potrace 1.16 / 미러 커밋 `886392d351417d6753aff61de08e294d1506e502`,
`src/decompose.c`, `src/trace.c`, `src/bitmap.h`, `src/auxiliary.h`에서 엔진 내부
`BinaryContourTracer` / `OptimalPolygon`으로 옮겼다. GPL-2.0-or-later 출처와 전문을 보존한다.
상세 해시·재현 명령은 [image-pipeline.md](image-pipeline.md)의 첫 포팅 단위 참조.

| 위치 | 원본과 차이 | 이유/한계 |
|---|---|---|
| 흑백 비트맵 | BooleanArray, XOR 기준 x=0, 입력 복사 | 워드 저장만 대체; 교차 패리티 유지 |
| 경로 면적 | Long 유지, C API의 INT_MAX 포화 생략 | 중간 엔진 API이며 기존 PES API에 미연결 |
| 분해 결과 | 탐색 순서와 양/음 부호, 계층 트리 없음 | 이번 단위는 기본 MINORITY 추적까지만 이식 |
| 다각형 이후 | vertex adjustment·Bezier·곡선 최적화 없음 | 불완전한 중간 결과를 제품 경로에 연결하지 않음 |

대표 Kotlin 기능 테스트 8개와 엔진 전체 테스트 293개 통과. 원본 실행/독립 게이트는
실행하지 않았고 동등성·봉제 품질 통과를 주장하지 않는다. 새 PES/앱 진입점도 없다.


### S5 곡선화 후속 단위

동일 Potrace 커밋의 `pointslope`/`adjust_vertices`/`reverse`/`smooth`를
`CurveFitter.kt`로 포팅했다. 위 표의 vertex adjustment·Bezier 미구현 상태를 갱신한다.
곡선 최적화(stage 5)와 계층 트리는 여전히 미구현이며 앱 미연결이다.
작은 PBM 3개에 대해 수정하지 않은 원본 C를 `opticurve=0`으로 실행해 비교했다:
경계·다각형 정확히 일치, 꼭짓점·제어점·alpha/beta 절대오차 1e-10 이내.
새 비교 테스트 3개 포함 엔진 테스트 296개 통과. 재현 명령/해시는
`engine/src/test/resources/potrace/README.md` 참조. 독립 봉제 품질 통과 주장은 없다.


### S5 곡선 최적화 후속 단위

`CurveOptimizer.kt`가 같은 원본 커밋의 `opti_penalty`/`opticurve`와 기하 함수를
이식한다. 기본 opticurve=1 / opttolerance=0.2px의 원본 최종 경로를 자동 비교한다.
4개 대표 PBM의 stage 4/5 비교 8개가 통과하며, 둥근 도형에서 원본과 동일한 7→3
곡선 병합을 assert한다. 좌표/alpha/beta 절대오차 1e-10 이내. 기존 곡선 최적화 미구현
기록을 갱신하며, 경로 계층·SVG·자수/앱 연결은 여전히 미구현이다.
재현: `tools/potrace/generate-stage5-goldens.sh /path/to/potrace-1.16`.
출력 `/tmp/myembro-port-out/editor/potrace-stage5/`; 코드는 기본 엔진 테스트에 포함된다.


## 별도 원본: Wu 이미지 색 양자화

Ink/Stitch 자체 기능으로 표시하지 않는다. `ImageQuantizer.kt`는 Xiaolin Wu의
Graphics Gems `gemsii/quantizer.c@ca4898517a93b6b45444a9cda5fd50132694b864`를
포팅한다. custom Graphics Gems 허가/원본 배포 고지는 `licenses/graphics-gems/`에 보존.
차이점(호출별 배열·Long/Float 유지·하위 박스 Vol·입력 경계)은
`docs/image-pipeline.md`의 Wu 표에 기록한다. 대표 RGB 3개에 대해 원본 C LUT/픽셀
인덱스를 자동 비교하며, 앱·땀 생성에는 연결하지 않는다.


## S4 사진 영역 채우기 연결 — 2026-10-24

기존 검은 띠의 양쪽 윤곽 러닝은 제품 선화 경로에서 제거했다. Potrace 곡선을
0.01 SVG-px로 평탄화한 뒤 0.1mm PolygonRegion(EVEN_ODD)으로 변환하고, 한글 TTF와
같은 model `DefaultTatami.stitchGroups`에 넘긴다. 윗땀 0.25mm/4mm/0°,
바탕 땀 0.75mm/4mm/90°, underpath=true 및 S1 기본값은 바꾸지 않는다.

model 스냅샷 `ea5fd8d63f30db770f626ab245f9bc50bbe5bf31`의 DefaultTatami/InkAutoFill/TatamiFill/Fill/FillRouting/
FillGeometry/FillRunning/FillPoint 8파일을 **수정 없이** 가져왔다.
이는 독립 채우기 재구현이 아니라 S2·S3 병합 전 API 의존성 준비이며, 통합 시 같은
model 파일을 유지한다. GPL 출처 헤더·JGraphT LGPL 선택 및 전이 라이브러리 고지·전문을 보존한다.

| 연결 경계 | 차이/근거 |
|---|---|
| 윤곽→영역 | Potrace y-up을 SVG y-down으로 변환; SVG-px→0.1mm 후 EVEN_ODD로 구멍 보존 |
| 출력 | 경계 러닝 대신 model 기본 타타미·바탕 땀; S1 한 번 적용 |
| 좁은 선 | 자체 밀도/간격 보정 없음; 실제 생성+봉제 검사+외부 gate의 실패 조합만 제외 |
| 센터라인 | autotrace 등은 다음 버전 조사 후보; 이번 기본 경로에 미적용 |

재현·수치·PNG·스윕 범위는 [qa/s4-sampler.md](qa/s4-sampler.md) 참조.

### P4: 글꼴 요소 JUMP 도착점의 중복 바늘 제거

원본 대조 결과 **upstream도 해당 중복을 남긴다**.
`lib/stitch_plan/stitch_plan.py:stitch_groups_to_stitch_plan`
@b0edd96311ece82ee48816dc93466274b12c2a9c는 `lock_stitches[0]` 위치로 JUMP한 후
잠금 목록 전체(첫 점 포함)를 추가한다. 잠금이 없어도 첫 본 땀 위치로 JUMP한 후
본 땀 전체를 추가한다. 같은 커밋 `color_block.py:filter_duplicate_stitches`는
이전 명령이 JUMP이거나 현재 점이 lock_stitch이면 중복 필터에서 제외한다.
따라서 이 부분은 원본과 동일하다는 이유로 보존할 수 없고, 앱의 새 P4가 우선한다.

글꼴 기본 생성 경로에서 S1 `fromGroups`의 `omitZeroLengthMotions`를 사용한다.
실제 이동 cursor를 기준으로 JUMP/바늘의 길이0을 제외하고, 같은 점에서 TRIM→JUMP
재시작하는 경우 둘을 없애 이어서 봉제한다. half_stitch의 나머지 좌표는 바꾸지 않는다.
잠금/명령 인덱스·통계를 다시 매기며, 제거 전 본 땀을 source metadata로 보존해
이동·확대·회전 후 재생성에서도 시작/끝을 잃지 않는다. P4 표식은 재생성에 전파한다.
저장 writer에서 감추는 수정이 아니라 글꼴 생성 결과 자체를 고친다.

원본을 측정하는 기존 `forceBoundaryLocks=false` 경로는 제품 정책을 끈
**원본 비교 전용**으로 유지하며, 고정 오라클4건의 원본 땀 수를 바꾸지 않는다.
앱 기본 호출은 경계 잠금과 P4 처리가 모두 켜져 있다. 이 차이를 원본 동등성으로
거짓 보고하거나 비교기/게이트를 완화하지 않는다. 가져온 PES에는 이 처리를 적용하지 않는다.

FontNonzeroMotionTest3건: running/bean/satin/fill의 두 글자 렌더와 PES 왕복,
같은 점 트림 재시작, 크기 변경 재생성·잠금 인덱스·멱등성.
물리 cursor는 최초(0,0)부터 모든 JUMP/STITCH를 연속 추적하며 바늘끼리만 비교하지 않는다.
모든 대표 출력의 **0길이 바늘0개·JUMP0개**를 assert한다.
수정 전 회귀2건 모두 실패, 수정 후 전체 `./gradlew check` 통과:
엔진589·앱 Debug/Release 각190개 실패0, Lint 및 기존 고정 오라클4건 통과.
출력: `/tmp/myembro-port-out/letter/p4-nonzero/output.txt` 및 `tests.xml`.

## 사진 외곽선 통합 (editor)

기존 `Stroke`/`RunningStitch`/S1 원본 포트를 같은 Potrace 경계에 적용한다.
`bean_stitch_repeats=[1]` (총 3회), `trim_after=true`, 나머지 원본 기본값.
새 땀 알고리즘 포트나 Ink/Stitch 실행은 없다. 상세: [사진 외곽선](qa/photo-outline.md).

| 원본과 다른 통합 범위 | 근거 |
|---|---|
| 순서 선택은 JGraphT 1.5.2 NearestNeighborHeuristicTSP 재사용 | 모바일의 닫힌 윤곽 전체를 보존하며 점프 앵커 거리 감소 시에만 적용; AutoRun 포트/전역 최단 동일성 주장은 하지 않음 |

### StrokeToSatin 원본 포트 인계 단위

`elements/utils/StrokeToSatin.kt`: `lib/elements/utils/stroke_to_satin.py`,
`lib/extensions/stroke_to_satin.py:filtered_rungs`, `lib/svg/styles.py`,
`lib/utils/geometry.py`@b0edd96311ece82ee48816dc93466274b12c2a9c.
레일 offset은 승인된 JTS, 새틴 생성은 기존 SatinColumn 포트를 사용한다.
골든 상태·원본 함수 목록과 모든 차이점은 `docs/hangul-centerline.md`의
“letter 인계 단위1”에 기록했다. AutoTrace 원본 centerline10/10 exact 재검증,
선→새틴7개 수식 회귀 통과; 선→새틴 공식 출력 동등성·한글 PES는 아직 미판정이다.

### 한글 새틴 라우팅 단위1

`stitches/utils/SatinRouteGraph.kt`: pinned `autoroute.py:find_path/_get_shortest_path`
및 `auto_satin.py:path_to_operations`의 그래프 순회와 중복 구간 underpath 표식만 포트.
원본 그래프 생성·레일 절단·DOM 연결은 미완료, 공식 오라클 미판정.
분석적7건과 차이점/다음 의존성 및 KRSAT1 이동 기준값은
[한글 새틴 라우팅](hangul-satin-routing.md)에 기록했다.

### 한글 새틴 라우팅 단위2

`stitches/AutoSatin.kt`: 원본 auto_satin의 Section1mm 분할·최소 연결·원본 그래프
순회·중복 underpath·양쪽 rail 절단·렁 재배치/추가·러닝 합치기를 기존 포트에 연결.
사용자 지정 글자/8방향 잉크 component/bbox 순서로 KRSAT2 실제 생성.
범용 raw SVG rails가 아닌 convert-to-satin Section 기본값 전용, 공식 오라클
동등성 미판정. 원본 함수·모든 차이와 실제 바이트 점프/잠금 표는
[단위2 보고](hangul-satin-krsat2.md)에 기록했다.
