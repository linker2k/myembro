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
Put `방법:도형` IDs in the legacy gate schema's `fonts` axis. Do not claim font validation.

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


### 2026-10-24 fill candidate validation 2

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
| Resource limits | Input/output capped at 100 × ten thousand points, interruption checks | Mobile memory/cancellation safety; fail rather than substitute approximate paths |
| Random 0 progress | Descriptive exception for exactly 0 length | Prevent infinite loops without progress |
| Ripple satin | Requires centerLine/rungPairs/plotPointsOnRails provider | No arbitrary rail approximation before integration with the SatinColumn owner's port |
| Ripple clip graph | Ordered Kotlin multigraph/exact DP matching instead of NetworkX | Preserve the minimum-path-count objective without new GPL-compatible dependencies |
| Matching limits | Fail at 60 odd vertices/100 × ten thousand DP states | Lower resource limits than upstream blossom; do not silently replace with greedy |
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


## S1 revision — apply only automatic finishing (2026-10-24)

Follow the **failure-free designs** principle in revised `docs/ui-stories.md` in the main worktree.
Do not ship the sewing settings section, lock-type selection/preview, advanced parameters, or check-summary line.
The 10 lock types and advanced parameter APIs are engine features for upstream validation/later development, not exposed onscreen.
Ripple/Satin provider integration and activation of additional generation features such as Bean are excluded from this stage.

- Default locks: upstream `half_stitch`, `ties=0`, `force_lock_stitches=false`.
- Minimum stitch: 0.1mm. Group minimum jump: 3mm. `trim_after=false` is the upstream default;
  preserve existing explicit TRIM and inter-object TRIM. Add no unsupported automatic trim-distance constants.
- Remove the existing native generation bridge's custom polyline half-lock and min=0 default policy.
  Typed `StitchPlanBuilder` retains upstream group collapse/color/trim/stop rules.
- Preserve already explicit JUMP in native patterns as needle-up commands. Do not turn empty-space moves into sewing
  from needle commands without SVG group provenance; distinguish this compatibility boundary from upstream group collapse.
- Existing lettering/editor generated patterns use the same default postprocessing. Recompute generated objects
  in older `.myembro` too. Do not apply locks/minimum-length cleanup to raw PES/PEC/DST.
  Preserve original-PES byte forwarding for saving/sharing/USB transfer.
- App defaults are fixed values and do not read the `sewing_options` store or user settings.
  Preview/sewout checks/PES export use the same defaults.

### Fixed SVG matrix reproduction

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:verifyInkstitchWorkerBoundary :engine:runInkstitchWorkerMatrix
# 동등한 CLI (입력/출력 경로 옵션은 --help):
python3 tools/generate_inkstitch_worker_matrix.py
```

- Input list: `stitch_plan/`, `ties/`, `stroke/` in `/tmp/myembro-quality-gate/matrix-inkstitch.json`.
- Actual inputs: materialized SVGs in `/tmp/myembro-inkstitch-oracle/inputs/`. Verify SHA-256 of original inputs and SVGs;
  read only identity/provenance fields from `.oracle.json`.
- Output: `/tmp/myembro-port-out/worker/{stitch_plan,ties,stroke}/<id>.pes`.
- List/input hashes/execution commit/dirty state: `manifest.json`, `inputs.tsv` in the same folder.
- Active `defaults-v2`: generate all 168 cases (56 per feature). Do not inject numeric attributes overriding defaults into these SVGs.
  Earlier exploratory matrix records of 507 cases/505 generated are historical; do not mix them into new default decisions. GENERATED is not equivalence.
- The runner follows upstream Inkex CSP control points→transform→0.1px subdivision, bbox center across all commands,
  pystitch translate/scale evaluation order, and Python round-to-even.
  Do not change the public adapter/editor placement coordinate contract.
- For upstream output comparison, `PesWriteSettings(inkstitchCommands=true)` reproduces pystitch PEC's viewport prefix,
  trim/jump after the first JUMP, and trailing STOP duplicate color events.
  This opt-in profile changes the origin; do not apply it to existing app placement/import paths.
  Retain the normal writer's default bytes. Do not claim complete byte equality with official PES.
- Upstream Ink/Stitch/Inkex/pystitch were read only, not run; no dependencies were installed.

Independent-decision submission command (embro-codex comparison tool, not upstream execution):

```sh
python3 /tmp/myembro-inkstitch-oracle/compare_matrix.py --ports /tmp/myembro-port-out/worker/manifest.json --report /tmp/myembro-port-out/worker/independent-comparison.json
```

Do not change output or thresholds to match PE-Design's fixed lock bytes. Merge decisions belong to
the independent owner Codex's comparisons/recalibrated gate; actual SE400 sewout review is a separate completion condition.

### Edge case of the upstream default lock — separate from safety approval

When `half_stitch` finds no point farther than 0.5mm from the start among 15 candidates, it uses direction **+x**
and length **0.5 SVG px**. Thus, for native path `(200,198)→(198,198)`, lock needles
can extend to `(201,198)` (0.1mm beyond the shape boundary). This is unchanged upstream behavior;
do not arbitrarily clip it into the shape or replace it with a separate polyline lock.

`DigitizingTest` checks hole/concavity/disconnected-region invariants on the **filtered body**, excluding only
tracked default-lock indices from actual postprocessing output. It does not test through
`stripGeneratedLocks`, which restores the body before deletion. A separate regression explicitly records the fallback coordinates
and lack of a guarantee that all locks lie inside the region. Passing body validation or oracle equivalence is not safety approval
that locks always remain inside shapes. This edge case and explicit JUMP preservation in the native bridge require
independent Codex review; actual sewout remains unvalidated.

### Independent comparison record of earlier exploratory matrix (matrix-v1, history)

Original `compare_matrix.py` results (criteria SHA-256
`fbbf03ab6872d767b2c996f46f3c1cf3055b0b9f8f54b4c0a35fccdc095d6b74`):

| Submitted feature | Equivalent | Mismatch | Undecided |
|---|---:|---:|---:|
| stitch_plan | 168 | 0 | 1 |
| ties | 168 | 0 | 1 |
| stroke | 169 | 0 | 0 |

The 2 undecided cases are `ORACLE_UPSTREAM_ERROR`, errors in official reference output. Features after S2 not submitted
are not included here. This is a **PES-output comparison**, separate from the mechanical release gate,
full upstream source audit, and actual SE400 sewout pass. Regenerate the matrix at the final commit,
fix the commit and clean state in `manifest.json`, then submit to Codex.

### S1 automatic-default build validation

- `:engine:test`: **393 passed**; `:app:testDebugUnitTest`: **171 passed** (0 failures/skips).
- `:app:assembleDebug` succeeded. Log: `/tmp/myembro-worker-s1-automatic-tests.log`.
- Retain original adapter byte preservation for 6 fixtures and existing byte regressions for the writer default profile.
- App regressions: fixed defaults/recomputation of old projects; raw PES bytes/stitches unchanged even with 10 lock-type options;
  consistent project PES generation for viewer/USB. Advanced type options are test-only engine inputs, not user UI.
- The previous whole-shape containment invariant no longer holds due to changed default locks. Rather than hide this
  by weakening tests, split it into actual filtered-body validation and an explicit upstream fallback counterexample.
- Actual SE400 sewout could not be performed. Final merge/distribution readiness requires independent Codex and user sewout review.

### Active defaults matrix defaults-v2 submission

Independent Codex revised the matrix during work. The runner selects the independently frozen file for the new scope,
`/tmp/myembro-inkstitch-oracle/out/defaults-v2/oracle-freeze.json`, and checks each identity record's
frozen SHA-256/scope/id and **byte equality** between original and materialized SVGs.
Do not use upstream/coordinate/PES output as generation input. Explicitly support the previous scope too without mixing materials.

- Active matrix SHA-256: `17f10563b6743539dba46f6db294f6f63b5f2db29b74d251c6521404755a5fe0`.
- Input: `inputs/defaults-v2--<id>.svg`. Outputs/reproduction command unchanged from above.
- **stitch_plan 56/56, ties 56/56, stroke 56/56 equivalent**, 0 mismatches/undecided.
- Mechanical release gate v2 (`check-release --stage S1`): **144 PASS / 24 BLOCK**.
  threshold SHA-256: `cb9c68f379056ec8215c9e7673cedb5a5d166b47484923ef710c9b870bb5aff1`.
- Submitting **official Ink/Stitch output** to the same independent checker also BLOCKs the same 24 `crossing_strokes`.
  Starting S0 in three sections is counted as `UNEXPLAINED_ZERO`; small 4–10mm inputs also block on body short-stitch ratio.
  Functional start/end locks are detected. Do not pass these with arbitrary exceptions or alter upstream defaults.
- Store `independent-comparison.json`, `release-matrix.json`, `release-gate.json`,
  `reference-release-matrix.json`, `reference-mechanical-gate.json` in `/tmp/myembro-port-out/worker/`.
  The release matrix's font axis is `feature/shape` fixture IDs, not product fonts/safe size ranges.
- **S1 merge/distribution approval pending**: remaining Codex gate review and user actual sewout. Do not automatically advance to S2 or later.
  Code/unit tests/build are ready; external review/sewout completion time is undecided.


## SE400 final safe-output boundary (after actual T3 confirmation)

Default PES output removes zero JUMP/zero needle moves and same-position TRIM restarts in
`GeneratedPatternProcessor.forSewout`. Nonzero half_stitch paths remain unchanged.
This is a **user-specified final output policy** distinct from upstream recognition rules. Retain the upstream-compatible oracle profile.
Evidence/tests/retest path/staging application are recorded in [sewout-zero-start.md](sewout-zero-start.md).


### SE400 PES origin policy difference (2026-10-24)

| Boundary | Difference from upstream comparison profile | Evidence |
|---|---|---|
| Default PES v1/v6 | Move final PEC coordinates to top-left 0,0, remove newly created zero moves/needle motions. Match PEC rectangle/v6 edit transform | User SE400 left-limit stop observation, local PE-Design byte comparison, S23SAM4 independent coordinate validation |

Do not apply to algorithms/half_stitch locks/edit coordinates. Retain explicit upstream comparison profiles and original imported PES. Per-path automated tests/exceptions are recorded in [pes-viewport.md](pes-viewport.md).

### 20-minute unit: default tatami official-coordinate regression tests (automatic failure detection)

After merging main's S1 planner, moved DefaultTatami and the existing 3 smoke tests into the normal source set.
No UI/product paths connected. `DefaultTatamiOracleTest` generates official defaults-v2
rectangle/circle/hole nominal20mm **3 cases**→PES v6→PesReader and compares them.
Copied official PES/coordinate gzip/input SVG/upstream execution records unchanged under `engine/src/test/resources/inkstitch-oracle/`
and recorded provenance/pinned commit/SHA256. No private designs.
Apply only translation by subtracting each needle bbox center; calculate bidirectional Hausdorff
using point sets of all needles (including locks). No rotation/resizing/point removal.

| Representative case | Our/official stitch count | Stitch-count difference | Hausdorff mm | Result |
|---|---:|---:|---:|---|
| Rectangle | 557/571 | 2.45% | 0.5831 | FAIL |
| Circle | 722/662 | 9.06% | 0.5315 | FAIL |
| Hole | 804/824 | 2.43% | 0.5831 | FAIL |

Command: `./gradlew :engine:test --tests '*DefaultTatami*'`.
Existing smoke3 PASS, new official equivalence3 FAIL. Criteria remain user-specified stitch count±5%/Hausdorff≤0.2mm;
did not ignore failing tests or raise tolerances. Metrics/candidate PES are saved automatically in
`engine/build/tatami-oracle/`. Therefore **this commit is not ready to merge**.
Earlier S4 external gate4/4 PASS was a functional safety-condition result, not official equivalence.

When worker adds the POLICY.md v5 provenance-specific gate implementation, connect SewoutCheck assertions
to these generated PES. Do not claim the current old SewoutCheck matches that policy; its call location is explicit.
Changed CI to push on all branches and validation command to `./gradlew check`. Remote CI green is unconfirmed.
Next proposed unit: compare start/end connections and underlay→top path handoff with upstream to fix the 3 mismatches.
This unit ends with automatic detection/failure metrics recorded; await the coordinator's decision.

Also ran full `:engine:check`: 509 total, PASS506; only the above official equivalence3 FAIL.


### 20-minute unit: extended finite-input tatami sweep

Generated5 shapes×5–90mm (1mm increments)=430 combinations at default angle0° and default underlay.
`python3 tools/sweep_tatami.py --out /tmp/myembro-tatami-sweep-1mm` output:
`GATE: 430/430 PASS; 0 BLOCK`. Decision from external frozen v5 gate; worker Kotlin policy awaits merge.
Rule calculator3 tests output: `Ran 3 tests ... OK`. Extended generation does not run in CI.
Automatically allowed JSON permits only exact samples, excluding failed/untested inputs.
Do not generalize nonmonotonic failures into a single minimum-size rule.
Tables/JSON are recorded in [commands/results/range limits](tatami-sweep.md).
Official equivalence3 failures remain unresolved; app integration/distribution disabled.
Stop this unit here and await coordinator review.


### 20-minute unit: fix official equivalence3 cases

Replaced the simplified InkAutoFill graph with the existing TatamiFill/FillRouting adapter,
passing the last underlay point as the top start point. Applied upstream running tolerance0.2mm.
Test SVG boundaries share the existing worker Inkex CSP flattening port.
Recorded [before/after metrics/upstream comparison/command output](tatami-equivalence-fix.md).
`./gradlew :engine:clean :engine:check`: 509 tests, 0 failures, BUILD SUCCESSFUL in21s.
All official3 cases match stitch counts100%; Hausdorff under0.141422mm. External gate also PASS3/3.
The earlier430 sweep is previous-backend history. Keep allowed rules inactive until the new core is reswept.
Do not confuse this with remote CI/actual sewout approval. Unit complete; await coordinator instructions.


### Calculation unit: resweep430 combinations with corrected backend

`JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 python3 tools/sweep_tatami.py --out /tmp/myembro-tatami-sweep-source-routing`
Output: `GATE: 430/430 PASS; 0 BLOCK`. Generation BUILD SUCCESSFUL in1m12s, generation errors0.
Backend e154303, same5 shapes×5–90mm integer sizes×default angle0°.
Updated [automatically calculated tables/failure lists/allowed JSON](tatami-sweep.md) with current-backend results.
No engine/threshold/test changes; no app integration/distribution activation. Finish here and await instructions.
# SatinColumn implementation/validation records

## Ownership and schedule

`embro-letter` / `codex/letter`: SatinColumn geometry core and existing lettering integration.
Initial estimate: basic core/underlay 4–6 hours, split/variants/equivalence validation an additional 6–10 hours.
Automatic routing/line→satin tools are stage 2. Shared StitchPlan owner: `embro-worker`;
independent output decisions: `embro-codex`. Do not replace release decisions with local unit-test success.

## Satin connection to the shared contract

The shared contract was merged in main `cef0156`. The worker contract above takes priority; do not duplicate models with the same names. Both core and shared models use SVG px.

```text
Stitch(x, y, tags: jump/trim/stop/lock/...)
StitchGroup(color, stitches, trimAfter, stopAfter, lockStart, lockEnd,
            minStitchLengthMm, minJumpLengthMm, forceLockStitches)
StitchPlan(colorBlocks)
EmbroideryElement.toStitchGroups(previousStitch)
StitchPlan -> 기존 EmbPattern/PES 어댑터
```

The font integration entry is `inkstitch.elements.SatinColumn.fromSubpaths()`; callers already knowing rails use the constructor.
`SatinLayer(points, tags, stitchTags)` is a **geometry intermediate**, not a replacement
for shared StitchGroup. Retain per-needle `satin_column_edge`, `satin_split_stitch`, `peak_a/peak_b` tags and
per-layer `satin_center_walk`, `satin_contour_underlay`, `satin_zigzag_underlay`.
The common adapter combines same-color underlay/top stitches, generates necessary running connections,
then applies the common group's lock/minimum-stitch/trim/jump policy once.
`SatinColumn` now implements `EmbroideryElement`. `toStitchGroups(previousStitch)` returns
worker's actual `StitchGroup`. It connects running between layers and retains color/tags/trim/stop,
injected LockStitch/min length/force lock settings. S2 connects to S1 worker's
`StitchPlanBuilder`/`GeneratedPatternProcessor`. The group DTO itself is a passive contract.

### Font parser integration example

```kotlin
val groups = SatinColumn.fromSubpaths(
    subpaths = flattenedPathsInSvgPixels,
    parameters = SatinParameters(zigzag_spacing_mm = 0.4),
    nodePaths = originalBezierEndpointsInSvgPixels,
    color = svgStrokeColor,
).toStitchGroups(previousStitch)
```

SVG paths may arrive in arbitrary order. Find rails using `rail_indices`;
if rungs are absent, synthesize from original Bézier endpoints. **Do not pass points added by
flattening as original nodes.** When rails/rungs are already known, use `generateLayers(left, right, ..., rungs)`.
`previousStitch`, `nextStitch`, and explicit `startPoint/endPoint` also use SVG px.
Only `SatinParameters`' `_mm` attributes use mm, converted once in private pixel getters.
For SVGs with empty `random_seed`, callers supply the element ID as default seed, as upstream does.
Do not disguise TTF outlines as this font API; the default is auto-fill + outline running.

## SatinColumn traceability table

Unless otherwise indicated, all upstream files are
`lib/elements/satin_column.py@b0edd96311ece82ee48816dc93466274b12c2a9c`.

| Feature | Upstream functions | Kotlin |
|---|---|---|
| Rail detection/node rungs/direction/swap | `rail_indices`, `_synthesize_rungs`, `_get_rails_to_reverse`, `line_string_rails` | `generateFromSubpaths`, `orient`, `sections` |
| Rung-section interpolation/density | `flattened_sections`, `_stitch_distance`, `plot_points_on_rails`, `SatinProcessor` | `Generator.pairs`, `Processor` |
| Asymmetric mm/% pull/push | `_apply_push_comp`, `_extend_line`; `lib/utils/geometry.py:offset_points` | `push`, `offsetPoints` |
| Short stitch | `inset_short_stitches_sawtooth`, `_get_offset_px` | `Generator.shorten` |
| Default/simple/staggered split | `get_split_points`, `_get_split_points_default/simple/staggered` | `Generator.splits` |
| Random split/phase | `lib/stitches/running_stitch.py:split_segment_even_n/random_phase/stagger_phase` | `splits`, `evenSplits` |
| satin/E/S/zigzag | `do_satin`, `do_e_stitch`, `do_s_stitch`, `do_zigzag` | `Generator.top` |
| Three underlay types | `do_center_walk`, `do_contour_underlay`, `do_zigzag_underlay` | `Generator.layers` |
| Start/end/center connections | `start_point`, `end_point`, `find_cut_points`, `_split_top_layer`, `_sort_stitch_groups`, `_connect_stitch_group_with_point` | `generateLayers`, `cut`, `centerConnector` |
| Reproducible randomness | `lib/utils/prng.py:join_args`, `uniform_floats`, `n_uniform_floats`, `iter_uniform_floats` | `utils.Prng` |

`SatinParameters` field names/units correspond to SVG attributes. Paired values follow first/second rail order;
`short_stitch_inset` is a **% list**, and `pull_compensation_percent` is also **%**.
Upstream defaults: spacing 0.4mm, short distance 0.25mm, inset 15%, split `default`,
no max stitch, center/contour/zigzag underlay off, underlay running length 3mm,
center repeats 2, contour inset 0.4mm, zigzag spacing 3mm.
Random split jitter is at most 100%; phase/width/spacing randomness uses upstream's BLAKE2s counter stream.
BLAKE2s is an independent Kotlin implementation of [RFC 7693 §§2–3](https://www.rfc-editor.org/rfc/rfc7693.html);
no additional dependencies installed. Compare against RFC `abc` and standard hashlib multiblock/UTF-8 vectors.

## Differences — explicit upstream/app boundaries

| Location | Difference | Reason/impact |
|---|---|---|
| SVG input | Core accepts SVG px polylines and original node lists | canvas owns Inkex path parsing/flattening. No duplicate Bézier engine |
| Geometry | Existing JTS 1.20.0 instead of Shapely. Rails use the same 1e-5 SVG px precision grid | Numerical tie-breaking in intersection/project/substring/offset may differ. Byte identity unvalidated |
| Rungs | As upstream, ignore intersecting rungs with >2 points; sort rail cuts then zip, exclude empty sections | Removed previous forced rejection of crossing rungs. JTS numerical errors require separate comparison |
| Malformed input | Reject point/command budget overruns/nonfinite values | Mobile resource limits; no claim of upstream's unlimited-size support |
| Raw core minimum length | Keep unspecified attributes null; use metadata default 0.1mm only for random split calculation; no global minimum-stitch filter yet | Upstream also applies the global filter at group→StitchPlan boundary. Preserve worker contract ownership |
| Shared type integration | `SatinColumn : EmbroideryElement` → S1 common builder. Default source half_stitch; disable by injecting null | worker d4c6776 integration, temporary Finisher removed |
| Raw core locks/trims | Do not insert `lock_start/end`, `trim_after`, `stop_after` into geometric layers | Attributes applied in shared StitchGroup/Plan. Prevent duplicate locks |
| Center-walk tolerance | Expose upstream getter/attribute, but centerline calculation uses `running_stitch_tolerance_mm` as upstream does | Matches actual v3.3.0 `_get_center_line_stitches` behavior |
| Running tolerance | Getter default **0.2mm**, unlike UI decorator 0.1 | Match v3.3.0 runtime default |
| App adapter | Map only legacy fields of existing projects into source DTOs. New selections use source defaults | Removed width >7mm forced4mm/short distance0.3/compact0.4 policy |
| App fill draft | Legacy FillStitches still merges body corners at 0.3mm | To be replaced by formal S4 auto-fill; locks belong to S1, not a blanket0.3mm requirement |
| Excessive density | Generator does not delete needles or block with a cell8 exception | Independent gate/sew-out diagnostics responsibility. Submit observed values including source locks |
| App outlines | All TTF SATIN requests, including rectangles, use auto-fill+warning. Remove new selection from UI | No automatic centerline columns before convert/auto-route validation |
| Existing line→satin | Independent miter offset in `lettering.SatinColumn.rails` remains | Stage 2 upstream convert-to-satin port target. Do not mistake for the new SVG core |
| Auto-fill draft | Fixed spacing/stagger only, bounded exact odd matching, no travel smoothing | Earlier safety draft. Fill owner will take over formal parameters/modules; details in stitch-algorithms.md |

## Earlier validation record — 3bbaaa4, not current S2 results

- Checked 21 files in v3.3.0 `tests/`. No SatinColumn counterpart tests, hence no upstream input/output fixtures
  to copy. `SatinPortTest` has 10 manually written coordinate/formula/order/random-vector cases.
- `SatinElementContractTest`: 3 cases covering SVG px/tags/colors/injected locks/trim/stop/null defaults and actual shared pattern-adapter integration.
- `SatinFixtureTool`: public synthetic SVGs in the repository → SatinColumn element → actual shared groups/adapter → 7 PES variants/explicit-axis matrix.
  `MYEMBRO_SATIN_FIXTURES=/tmp/... ./gradlew :engine:test --tests '*SatinFixtureTool'`.
- `BirdnestRegressionTest`: public OFL Dancing Script Bold, `Jung Min`, 34mm, 40wt.
  Checks fallback of unvalidated SATIN requests to output identical to auto-fill.
  Checks 0 stitches below 0.3mm, single-phase cell maximum ≤8, end lock, PES v1/v6 round trips.
- Full local regressions: **309 engine**, **167 app** passed, `:app:assembleDebug` succeeded.
- Audit: 200 generated of 203, 3 blocked for excessive density. Generated output had 0 stitches below 0.3mm,
  maximum 8 penetrations in single-phase cells. `Jung Min`: 5,389 stitches/single-phase cell maximum 7.
  Independent gate's 4-phase cell maximum is 8. This does not resolve start-format blocking.
- Comparison PNGs: `/tmp/myembro-birdnest/before.png`, `after.png`.
  Do not commit private files/extracted coordinates/private renderings.
- Independent execution: `/tmp/myembro-quality-gate/check-release --matrix … --json …`.
  Result: **BLOCK**. Synthetic satin 0/7, `Jung Min` auto-fill 0/1.
  Common blocker: `TEXT_START_SEQUENCE_MISSING` — gate expects J→S0→0.28mm reversal 2 stitches;
  current temporary app adapter removes S0/uses 0.4mm reversal. split random also showed `START_LOCK_MISSING`,
  requiring connection improvements. Some split/E/S cases are `SATIN_NOT_VERIFIABLE`.
  Do not turn insufficient decision samples into a pass.
- Reports: `/tmp/myembro-birdnest/satin-fixtures/gate-report.json`,
  `/tmp/myembro-birdnest/release-jung-min-final.json`.
  **Not a release/merge readiness decision. Upstream execution diff/physical SE400 validation incomplete.**

Decision needed from the shared owner: “remove all <0.3mm/0-length needles” and the gate's explicit S0/0.28mm
start format cannot both be satisfied. Agree on lock exceptions/shared Plan defaults,
finalize independent gate classification for satin variants, then reassess the output matrix.
Gate/thresholds unchanged on this branch.


## S2 revised application and reproduction (2026-10-24)

Current baseline is the **failure-free designs** revision of main worktree `docs/ui-stories.md`.
Cancelled previous US-3/4 detailed settings/advanced tab work. Hold S2 main merge/release until S1 user review.

- Imported S1 `384b259` revision `d4c6776` as dependency commit `3405b63`.
  Removed b798af7 temporary compact locks; use S1 common postprocessing.
- UI inputs are only text/font/height/color. Do not expose type/density/underlay/thread weight/bean/curve/spacing/alignment settings.
  Retain satin core's full parameter API for port validation/legacy-data interpretation; new generation uses only upstream defaults.
- Excluded TTF custom automatic satin from defaults. Satin targets only explicit SatinColumn and validated font rails/rungs.
  New TextObject and LetteringEngine default to RUNNING. FILL/BEAN interpret explicit requests in saved files, not new UI defaults.
- Default running uses Ink/Stitch stroke getter length 2.5mm/tolerance 0.2mm. No additional Douglas–Peucker stage.
- Do not use FontQuality's custom recommended heights for default decisions/height limits. 3b6ec12 TTF analysis is not approval evidence either.

### Single input for automatic rules

Checked embro-codex's `/tmp/myembro-quality-gate/auto-rules.md`. Numeric boundaries for safe letter height/satin width
are **unconfirmed**. Do not generalize full-PES bbox or the 4–150mm comparison matrix into a safe range.
Per the coordinator's additional decision, S2 **blocks satin and permits running only** for unconfirmed fonts. This does not mean every running height
has been machine-validated. Do not invent unconfirmed ranges such as 5–50mm.

```sh
python3 tools/import-lettering-rules.py /tmp/myembro-quality-gate/auto-rules.md
```

This tool imports only the document's explicit fenced JSON `{ "schemaVersion": 1, "profiles": [...] }`.
It does not infer numbers from tables/prose. There are currently 0 approved profiles. The sole runtime rule input is
`engine/src/main/resources/lettering/validated-ranges.json`, a distributed snapshot recording the source name/SHA256.
Update/review through this command when new numbers are approved. No separately hardcoded ranges or FontQuality fallback.
Profiles bind fontId/file-content SHA256+extraction version/glyph codepoints/method/height range/evidence.
SATIN also requires SATIN_COLUMNS geometry and a validated width range. Do not infer columns from TTF.
S3 embroidery font metadata integration is subsequent work for the parser owner.

If a range is confirmed, restrict the height slider to it. Currently the unconfirmed state uses guidance and numeric running-height input
(allowed by the coordinator). Nonfinite/heights≤0, missing glyphs, and out-of-hoop results cannot be added.

### defaults-v2 reproduction

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:satinOracle --console=plain
```

Currently reads 56 satin_column SVGs from `/tmp/myembro-quality-gate/matrix-inkstitch.json`.
Matrix SHA256 `17f10563b6743539dba46f6db294f6f63b5f2db29b74d251c6521404755a5fe0`.
Verifies the input and input_sha256 specified by each case unchanged. Output in `/tmp/myembro-port-out/letter/`:
56 PES/`inputs.txt`/`manifest.json`, recording commit/dirty/branch/stage/input and output hashes.
`-PmatrixFile=… -PportOutput=…` changes only the input document/output location. No upstream execution/installation/criteria changes.
The current official oracle is pinned to the previous matrix, so **defaults-v2 official equivalence is pending**.

All valid 224/224 in the earlier parameter matrix passed the official comparison tool's equivalence criteria (0 mismatches).
The zero-rails negative1 case was rejected/undecided. This record is preserved in
`/tmp/myembro-port-out/letter/legacy-parameter-matrix/`.
Do not use it as certification of current defaults-v2 or safe product height/width ranges.
`-PmatrixFile=` replays the earlier 225 frozen oracle SVGs.

`svg.ViewBoxTransform` preserves upstream `lib/svg/units.py:get_viewbox_transform`'s 6-digit %f scale
and translate @ scale order. Curve flatten tolerance is 0.1 SVG px.
Replacing this with general SVG viewport calculations changes exact split boundaries and sewing order.

### Jung Min and validation status

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 MYEMBRO_START_AUDIT=/tmp/myembro-birdnest/s2-running ./gradlew :engine:test --tests '*LetteringAuditTool' --rerun-tasks
/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-birdnest/s2-running/script-34.pes --text --style running --json /tmp/myembro-birdnest/s2-running/quality-v2.json
```

Revised running output for public OFL Dancing Script Bold, Jung Min, 34mm, 40wt: **531 stitches/trim8/S0 9 cases**.
Current v2 diagnostic: **BLOCK: UNEXPLAINED_ZERO 9/531**. Cell concentration/start/end/post-trim lock checks passed.
Did not remove S1 start anchors to match PE-Design sequences. Submitted the first20 stitches TSV and generated lock-tag tracking JSON
together. Do not misrepresent tracking JSON as PES record indices or independent approval.
PNG: `/tmp/myembro-birdnest/s2-running.png`. Do not commit private PES/renderings.
Earlier fill diagnostic PASS in `/tmp/myembro-birdnest/s2/` is a separate historical record, not revised S2 passing.

Local regressions: engine354/app167 and APK debug build succeeded. On a temporary API35 emulator, verified 4 inputs,
automatic running/unconfirmed guidance, blocked addition at height0, and reactivation after restoring valid values.
**Not S2 completion/release approval:** defaults-v2 official comparison, S0 decision review above, S1 user review/actual SE400 sewout remain.


### Coordinator's rerevised validation policy — end current unit

Stop hundreds-case equivalence matrices. The matrices and v2 S0-ratio BLOCK above are historical diagnostics.
S0 ratio/short-stitch ratio/length distribution/satin spacing statistics are not blocking conditions. Do not claim
reassessment under the new criteria. Subsequent validation checks stitch count/shape on at most 10 representative cases and only actual failure conditions:
0.5mm cell density/consecutive same-point punctures/functional start/end locks/maximum stitch length/file validity/hoop bounds.
Final decision is user actual sewout. End this unit where code/reproduction artifacts/simplified UI have been pushed;
proceed to the next validation unit after the coordinator's decision.

### S2 cleanup: S1 locks and ownership

Satin passes worker S1 `InkLockStitch()`'s default `half_stitch` to start/end and
uses only `GeneratedPatternProcessor.fromGroups`. No custom COMPACT/0.4mm round-trip locks.
Regression tests verify `lock_id=half_stitch` on both sides and S1 lock-tag generation.

Separated S4 tatami into `digitizing/OutlineTextFill.kt`, owned by model.
Retain the `lettering/OutlineFill` contract/delegation for saved-object compatibility. S2 changes unconfirmed
TTF satin requests to running, not to tatami.

No custom rejection at 8 per 0.5mm cell in the generator. Sewout-check defaults match the gate:
allow up to 15, block from 16. Lock criterion③ unchanged.
Representative validation regenerates only 20mm defaults for satin_column 7 shapes. Report release gate decisions
separately from `check-release`; do not claim independent approval or actual sewout passing.

Representative 7 reassessment (generation commit `0ce60ae0e820985911bf43ba5b47a6a28bc52a56`):
rectangle/circle/hole/self-intersection/narrow tip/sharp corner/crossing strokes, height 20mm, upstream defaults.
Outputs/hashes: `/tmp/myembro-port-out/letter/representative-7/manifest.json`;
reproduction inputs: `inputs.json` in the same folder.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:satinOracle -PmatrixFile=/tmp/myembro-port-out/letter/representative-7/inputs.json -PportOutput=/tmp/myembro-port-out/letter/representative-7
/tmp/myembro-quality-gate/check-release --stage S2 --matrix /tmp/myembro-port-out/letter/representative-7/release-matrix.json --json /tmp/myembro-port-out/letter/representative-7/release-report.json
/tmp/myembro-quality-gate/quality-gate check --matrix /tmp/myembro-port-out/letter/representative-7/release-matrix.json --json /tmp/myembro-port-out/letter/representative-7/quality-report.json
```

`check-release`: **BLOCK — previous stage gate/release/user sewout confirmation pending**.
Awaiting S1 approval; gate tool/stage state/lock criteria unchanged.
Separate functional diagnostics: existing gate's 5 conditions **7/7 PASS**. Do not substitute this
for release passing or final independent approval. The next stage follows the coordinator's S1 review result.

### Reproduce 7 names through the S2 app path

Opt-in instrumentation calls Android `automaticLetteringState` → `renderLettering` →
`TextObject` → `PesWriter`, not an AWT substitute path. Uses the same settings as the 4-input UI
(spacing0, centered, curve off, 40wt, upstream satin parameter defaults).
Only the test APK includes the existing OFL Dancing Script fixture.

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

Current output is the existing automatic-running baseline for TTF without approved ranges. Results table `results.md`,
per-file gate JSON/logs, `manifest.json`, independent PES parser round-trip coordinates, and `overview.png`
are stored in `/tmp/myembro-sewtest/s2/`. Of 7 cases, 6 PASS; only Jung Min 30mm BLOCKs because width133.8mm
exceeds the SE400 100mm hoop. The app renderer likewise returns outsideHoop=true.

The 0.9–6.0mm width policy later adopted by the coordinator comes from the 'adopted by coordinator' section of
`/tmp/myembro-quality-gate/auto-rules.md`. This output is not satin under that policy. TTF has no rails,
and the existing custom OutlineColumns medial-axis extractor is prohibited in the default path; without validated column conversion,
do not approve satin or create slider upper limits from FontQuality's estimated width alone.

### S2+S3: satin path for manually digitized fonts

TTF offers running (currently) and fill only after S4. Satin uses only rails/rungs from Ink/Stitch embroidery font SVGs.
`inkstitch/lettering/InkStitchFont.kt` reuses canvas's committed parser from `a8acced`.
`InkStitchLettering.render` reads all satin attributes through `SatinParametersCodec` and passes
all `SatinColumn.toStitchGroups(previousStitch)` results to S1 `fromGroups` exactly once.
It does not flatten layers into native coordinates and relock them. Running connections also preserve ties/trim/stop/minimum stitch/jump
attributes. This path does not support fill/repeated running/bean; reject rather than silently change them.
Fixed codec failure on upstream SVG `True`/`False` according to upstream `get_boolean_param` rules too.

These 3 cases use **Aventurina for local trials**. Data was read from
`fonts/src/aventurina/{ltr.svg.xz,font.json.xz,LICENSE}` in the official v3.3.0 distribution; no upstream code/official binaries were executed.
The license file states CC-BY-SA 4.0, adapter Sandrine Ratkoff Rojnoff, permission records from original artist HalfAsleepArtist,
and possible additional underlying-font restrictions. Therefore **do not bundle font data in the repository/app**;
keep it only in `/tmp/myembro-port-out/letter/jungmin/inputs/`. This differs from approval for catalog publication.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:inkFontNames
python3 tools/check-inkfont-names.py
```

Record font id/text/height/placed SVG/input SHA256 in `inputs/request.json`. Preserve original glyph curves/attributes/document order
in SVG and apply only placement transforms. Prefix repeated glyph IDs to avoid collisions.
This fixture has an identity root viewport and verifies original glyphs are direct root children.
Layout was produced by the port; no claim of equivalence with official batch lettering placement.

`tools/check-inkfont-names.py` checks only 3 cases with existing `quality-gate check --text --style satin`;
if official `/tmp/myembro-inkstitch-oracle/out/letter-jungmin/<id>.pes` exists, automatically compare only stitch count/shape.
Read existing oracle criteria of stitch count 5%/symmetric Hausdorff 0.2mm, permitting only bbox-center translation.
If reference files are absent, record **undecided** and do not exit successfully. Preserve provenance/file hashes in validation.json.

All three currently **BLOCK: missing start lock**. Original J's first running element has `ties=3`, disabling locks.
Did not arbitrarily override it or change gate③. At 20/30mm, widths124.6/186.8mm also exceed
SE400 hoop bounds. Font-recorded range converted to reference H height is approximately11.09–27.73mm,
so 30mm is an out-of-range diagnostic sample, not a UI-permitted sample. Official comparison awaits the coordinator-designated codex output.

### Start/trim boundary force and lettering default comparison

Per coordinator instructions, `InkStitchLettering.render` applies upstream `force_lock_stitches=true` only to
actual starts and the first element after trim/stop/color change. S1 implementation/gate criteria unchanged.
**Important upstream semantics:** in `lib/elements/element.py:lock_stitches`, force forces only the end lock.
With `ties=3`, the start lock remains null even with force=true. S1 follows the same rule.
Thus force alone did not resolve Aventurina J's first-running start-lock BLOCK.
Asked the coordinator whether to apply upstream `ties=0` to the first element too; did not arbitrarily enable it.
Jung Min trial scope is now only 12mm; exclude 20/30mm as outside the hoop.

Compared upstream `font.py:render_text`/`batch_lettering.py`: trim defaults to 0/off, with no
per-glyph trim default. Also compared defaults ties=0/half_stitch, force=false, back_and_forth=true.
The 16–22 stitches missing versus official output require distinguishing the earlier canvas native flatten/relock path
from the new S1 group path. Representative AB 4/10/20mm in the new path has **42/42, 65/66, 89/91** stitches.
First difference moved from index0 to2/5/5 respectively. At 4mm Hausdorff0.1mm; 10/20mm1.2mm,
so do not report complete equivalence passing. The boundary where B's exactly7.5mm segment is split into3 by the port/official4 segments
and original glyph SVG serialization precision remain. No new heuristic compensation applied.

Do not match trim differences (-2/-3/-3) by enabling lettering trim options. Upstream output.py uses
`full_jump=True`; PEC writer `pec_encode` writes trimjump flags for every JUMP after the first jump.
Our `io/PecWriter.kt`, by contrast, intentionally does not turn ordinary JUMP into TRIM.
Writer unchanged in this unit; align the normalization contract separately with its owner.

The initial official newline SVG actually contained only the first line; codex updated it as `font-newline-corrected`.
Updated output uses a separate font.json with reversible=false for a fixture without rtl variants.
Representative reproduction reads the same metadata; do not remove the port's second line to match the earlier incorrect single-line reference.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:fontDefaultsAudit
python3 tools/breakdown-fonts-representative.py
```

This script recalculates only the same stitch count/trim/jump/first difference/bbox-center-normalized Hausdorff metrics
as codex's `breakdown_fonts.py` for representative4 cases (at most10). It does not regenerate the original96 cases.
Output: `/tmp/myembro-port-out/letter/font-defaults/breakdown/cases.{json,md}`.
Explicitly set `forceBoundaryLocks=false` for official raw-default comparisons. This reproduction argument prevents confusion
between approved product boundary-force policy and upstream defaults; it adds no UI setting.

#### Coordinator follow-up confirmation: first-element ties=0 + force

Per coordinator reply, also apply `ties=0` to the first element at starts/after trim/stop/color changes.
Upstream `LockStitches.fromParameters` creates default half_stitch; S1 postprocessing runs only once.
Preserve internal-element ties. Raw-default comparison mode (`forceBoundaryLocks=false`) omits
both product-policy overrides. Record the same two attributes in output placement SVGs too.
Regression checking the first needle's S1 lock tag and all engine360 tests passed.

**Jung Min 12mm: existing --text gate PASS**; no official oracle output yet, so undecided.
Exclude 20/30mm from this assessment. Representative default breakdown: AB 4/10/20mm 42/42,65/66,89/91;
two-line10mm with the same reversible=false metadata 130/132. Not complete equivalence passing;
PEC output trim contract/glyph serialization precision differences remain separate follow-up work.

### Trace and fix Hausdorff 1.2mm cause

Reproduce: `python3 tools/locate-font-deviation.py`. Limited to at most10 cases; calculate nearest points in both directions
using independent PES parser/KDTree. Output needle/command indices (from0), raw/bbox-center-normalized coordinates,
nearest needles, previous/next2 needles, original glyph/path/subpath, and both PES hashes as JSON/tables.
Map elements by order only if original SVG subpath and official sewn-run counts match; record unresolved when collapse
changes counts. Do not force unrelated element names onto them.

Before the fix, 10mm AB's **official needle36** raw(11.3,5.0), normalized(3.8,0.0), had nearest
port needle36 raw(12.5,-5.0), normalized(5.0,0.0), distance1.2mm.
Element: **B / path17694 / subpath0**, first horizontal stroke. Official splits that segment into4;
port into3. At 20mm, official needle6 in A/path17279/subpath0 also differed by1.2mm.
This is an **actual sampling path difference**, not an allowed PEC rounding/trim difference.

Cause: Ink/Stitch `glyph.py:_process_group/_move_to_origin` saves paths as strings.
Unlike pinned dependency inkex `EXTENSIONS_AT_INKSCAPE_1.4.1`, `paths/interfaces.py:PathCommand`'s
`number_template="{:.6g}"`, the port retained transformed coordinates at full precision.
This changed which side of the 7.5mm / 2.5mm boundary they lay on and thus upstream `ceil`'s segment count.
Without adjusting numeric epsilon/stitch length, applied upstream **6-significant-digit path serialization**
to transformed endpoints/Bezier control points before splitting. SVG viewport also reuses
the existing upstream6-digit fixed-decimal transform helper.
Added an optional canonicalize argument to `SvgPath.flatten`; existing calls retain their precision.
Arc (A/a) parameter serialization is a separate port, so those cases retain the existing path.

Upstream reference: https://gitlab.com/inkscape/extensions/-/blob/EXTENSIONS_AT_INKSCAPE_1.4.1/inkex/paths/interfaces.py .
Source/dependency code was read only, not executed/installed.

After fix, AB4/10/20mm **42/42·66/66·91/91**, two-line10mm with identical metadata **132/132**.
All Hausdorff **0.1mm**. Only the remaining one-grid (0.1mm) coordinate difference from original coordinate serialization
and final0.1mm grid rounding is recorded as an **allowed difference** for these representative cases.
Trim-command count differences (-2/-3/-3/-7) are a separate PEC writer contract difference, excluded from this allowance.
Do not extend the claim to full control-command/all-font equivalence or sewout certification.

Before-fix materials: `/tmp/myembro-port-out/letter/font-defaults/{deviation,breakdown}-before-precision/`.
After fix: `{deviation,breakdown}/` in the same folder; especially `deviation/max-deviations.{json,md}`.
Regression uses identical A/B path input from the upstream test font to check the 7.5mm boundary/66 stitches.

### Trim boundary breakdown and preservation of upstream PEC behavior

Reproduce: `python3 tools/locate-font-trims.py`. For representative4 cases, normalize actual needle coordinates
before/after JUMP moves from independent PES decode by bbox center, then map to original placed SVG line/glyph/path/subpath
endpoints. Mark official/port boundary positions as matching only within0.2mm. This is the existing
official coordinate comparison tolerance; gate unchanged. Also record both PES SHA256 in JSON.

The3 missing in 10mm AB are A/path17279 internal subpath0→1, the A→B glyph boundary,
and B/path17694 internal subpath0→1. The two-line7 also include the L1 B→L2 B line boundary.
Thus enabling one trim per glyph cannot match upstream.
Upstream `font.py:render_text/_add_trims`, `batch_lettering.py:setup_trim` default to
`trim_option=0/off`. Options 1=line/2=word/3=glyph add trim_after only when selected.
Kept this default at0.

Actual cause: `lib/output.py`'s full_jump setting and **pyembroidery1.5.1
PecWriter.py:pec_encode**. Upstream PEC writes JUMPs after the first as trimjump.
Did not globally change our existing writer contract that preserves ordinary JUMP. Instead,
an **embroidery-font-only opt-in output adapter**, `GeneratedPatternProcessor.fromGroups(..., pecJumpTrims=true)`,
preserves explicit TRIM on jumps after actual needle sewing. Existing TRIM is not duplicated;
no new trim on initial/consecutive jumps. Needle coordinates/stitch counts unchanged.
S1 lock/run/command anchor indices are shifted to account for added control commands.
Upstream `StitchPlanBuilder` defaults and normal/imported-design writer defaults remain unchanged.

New PEC trims exposed3 missing post-trim locks on internal elements disabled by original ties=3.
Added opt-in `lockAtThreadBoundaries` to apply the coordinator-confirmed thread-boundary ties=0 policy
also **where S1 actually computes need_tie_in**. Retain existing defined custom start/end locks;
use worker default InkLockStitch(half_stitch) only where disabled. No new lock shapes or
custom distance thresholds; S1 postprocessing runs once. Raw official comparisons with `forceBoundaryLocks=false`
do not use this product override. Oracle name-placement SVGs mark ties=0 on paths,
with force only on first elements at start/after explicit trim, preserving source's actual boundary decisions.

After fix, AB4/10/20mm and two-line10mm have **trim counts 2/2·3/3·3/3·7/7**;
**all 15 calculated boundaries match**. Stitch counts42/42·66/66·91/91·132/132 and Hausdorff0.1mm retained.
Initial-origin jump count difference (-1) remains; do not claim full control-stream equivalence.
Jung Min12mm now has **2,076 stitches, existing --text gate PASS**, including new trims/restart locks.
Official Jung Min output pending, so oracle equivalence undecided.
Engine363 tests passed, including PES round-trip trims/unchanged needles/no duplicate trims/S1 lock indices/restart locks.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:fontDefaultsAudit :engine:inkFontNames
python3 tools/locate-font-trims.py
python3 tools/breakdown-fonts-representative.py
python3 tools/check-inkfont-names.py
```

Before fix: `/tmp/myembro-port-out/letter/font-defaults/trims-before-compatibility/`.
After fix: `/tmp/myembro-port-out/letter/font-defaults/trims/trim-boundaries.{md,json}`.
S1 file changes are limited to optional arguments/tracking preservation required to separate font-only output/product boundary policy.

### CI-pinned representative font oracle (4 cases)

Added `FontEquivalenceTest` to normal `engine:test`, so it runs automatically in `./gradlew check`.
CI JVM validation also uses `./gradlew check`, preserving its JUnit XML as a `font-equivalence-results`
artifact. Normal check fails on failures/exceptions. No separate opt-in task,
environment variables, external /tmp materials, network, Python, or Ink/Stitch execution required.

`engine/src/test/resources/inkstitch/font-equivalence/` contains only **4 cases/331 needle coordinates**:
official testfont AB4/10/20mm and two-line10mm. Includes provenance/official release/binary/PES/
independent decode/input hashes, newline reversible=false correction records, and GPL/OFL notices.
Total approximately52KB; tests also enforce at most 10 cases/whole bundle under1MB.
Expected coordinates are a frozen subset from independent decode of codex official output, not values generated by the port.

Checks: exact stitch count, symmetric Hausdorff≤0.2mm with only bbox-center translation,
TRIM/STOP/color-change order/count/anchor≤0.2mm, thread order. These are existing frozen-oracle metrics;
no gate criteria changes or restoration of length-statistic blockers. Exclude the initial-position JUMP count difference
as already documented. This does not replace independent certification of all catalog fonts/satin or actual sewout.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check
# 4건만 재실행하려면
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*FontEquivalenceTest'
```

JUnit output: `engine/build/test-results/test/TEST-io.github.linker2k.myembro.engine.inkstitch.FontEquivalenceTest.xml`.
All 4 passed with stitch counts42/42·66/66·91/91·132/132, trims2·3·3·7, Hausdorff0.1mm.

Validation results: **367 full engine tests passed**, pinned oracle4 cases passed.
Full `./gradlew check` failed on **14 app Lint errors**. Affected files are unchanged from
HEAD before this change (6 API27 windowLightNavigationBar cases in API26 resources,
7 Compose ProduceStateDoesNotAssignValue cases, missing1 Korean app_name translation).
Did not disable Lint or add a baseline; full CI is blocked until the app owner fixes these.

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
| Resource limits | Input/output capped at 100 × ten thousand points, interruption checks | Mobile memory/cancellation safety; fail rather than substitute approximate paths |
| Random 0 progress | Descriptive exception for exactly 0 length | Prevent infinite loops without progress |
| Ripple satin | Requires centerLine/rungPairs/plotPointsOnRails provider | No arbitrary rail approximation before integration with the SatinColumn owner's port |
| Ripple clip graph | Ordered Kotlin multigraph/exact DP matching instead of NetworkX | Preserve the minimum-path-count objective without new GPL-compatible dependencies |
| Matching limits | Fail at 60 odd vertices/100 × ten thousand DP states | Lower resource limits than upstream blossom; do not silently replace with greedy |
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


### S3 app integration: default tatami+underlay

Integrated letter S2 baseline111ae77 and connected TTF fill/rails and centerline satin exceeding 6.0mm
to DefaultTatami→S1 groups. Added fill to automatic labels; height upper limit is sweep90mm.
Recorded [implementation scope/evidence/test output](s3-fill-app-integration.md).
`./gradlew :engine:check :app:assembleDebug :app:testDebugUnitTest`: BUILD SUCCESSFUL in19s,
engine552/app176 failures0. Integration6 cases; external gatePASS1/1 for bundled Jua TTF Korean word for embroidery,1230 stitches.
Remote CI/physical approval separate; stop here and await coordinator review.


### S1 generated postprocessing integration record

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


## S1 revision — apply only automatic finishing (2026-10-24)

Follow the **failure-free designs** principle in revised `docs/ui-stories.md` in the main worktree.
Do not ship the sewing settings section, lock-type selection/preview, advanced parameters, or check-summary line.
The 10 lock types and advanced parameter APIs are engine features for upstream validation/later development, not exposed onscreen.
Ripple/Satin provider integration and activation of additional generation features such as Bean are excluded from this stage.

- Default locks: upstream `half_stitch`, `ties=0`, `force_lock_stitches=false`.
- Minimum stitch: 0.1mm. Group minimum jump: 3mm. `trim_after=false` is the upstream default;
  preserve existing explicit TRIM and inter-object TRIM. Add no unsupported automatic trim-distance constants.
- Remove the existing native generation bridge's custom polyline half-lock and min=0 default policy.
  Typed `StitchPlanBuilder` retains upstream group collapse/color/trim/stop rules.
- Preserve already explicit JUMP in native patterns as needle-up commands. Do not turn empty-space moves into sewing
  from needle commands without SVG group provenance; distinguish this compatibility boundary from upstream group collapse.
- Existing lettering/editor generated patterns use the same default postprocessing. Recompute generated objects
  in older `.myembro` too. Do not apply locks/minimum-length cleanup to raw PES/PEC/DST.
  Preserve original-PES byte forwarding for saving/sharing/USB transfer.
- App defaults are fixed values and do not read the `sewing_options` store or user settings.
  Preview/sewout checks/PES export use the same defaults.

### Fixed SVG matrix reproduction

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:verifyInkstitchWorkerBoundary :engine:runInkstitchWorkerMatrix
# 동등한 CLI (입력/출력 경로 옵션은 --help):
python3 tools/generate_inkstitch_worker_matrix.py
```

- Input list: `stitch_plan/`, `ties/`, `stroke/` in `/tmp/myembro-quality-gate/matrix-inkstitch.json`.
- Actual inputs: materialized SVGs in `/tmp/myembro-inkstitch-oracle/inputs/`. Verify SHA-256 of original inputs and SVGs;
  read only identity/provenance fields from `.oracle.json`.
- Output: `/tmp/myembro-port-out/worker/{stitch_plan,ties,stroke}/<id>.pes`.
- List/input hashes/execution commit/dirty state: `manifest.json`, `inputs.tsv` in the same folder.
- Active `defaults-v2`: generate all 168 cases (56 per feature). Do not inject numeric attributes overriding defaults into these SVGs.
  Earlier exploratory matrix records of 507 cases/505 generated are historical; do not mix them into new default decisions. GENERATED is not equivalence.
- The runner follows upstream Inkex CSP control points→transform→0.1px subdivision, bbox center across all commands,
  pystitch translate/scale evaluation order, and Python round-to-even.
  Do not change the public adapter/editor placement coordinate contract.
- For upstream output comparison, `PesWriteSettings(inkstitchCommands=true)` reproduces pystitch PEC's viewport prefix,
  trim/jump after the first JUMP, and trailing STOP duplicate color events.
  This opt-in profile changes the origin; do not apply it to existing app placement/import paths.
  Retain the normal writer's default bytes. Do not claim complete byte equality with official PES.
- Upstream Ink/Stitch/Inkex/pystitch were read only, not run; no dependencies were installed.

Independent-decision submission command (embro-codex comparison tool, not upstream execution):

```sh
python3 /tmp/myembro-inkstitch-oracle/compare_matrix.py --ports /tmp/myembro-port-out/worker/manifest.json --report /tmp/myembro-port-out/worker/independent-comparison.json
```

Do not change output or thresholds to match PE-Design's fixed lock bytes. Merge decisions belong to
the independent owner Codex's comparisons/recalibrated gate; actual SE400 sewout review is a separate completion condition.

### Edge case of the upstream default lock — separate from safety approval

When `half_stitch` finds no point farther than 0.5mm from the start among 15 candidates, it uses direction **+x**
and length **0.5 SVG px**. Thus, for native path `(200,198)→(198,198)`, lock needles
can extend to `(201,198)` (0.1mm beyond the shape boundary). This is unchanged upstream behavior;
do not arbitrarily clip it into the shape or replace it with a separate polyline lock.

`DigitizingTest` checks hole/concavity/disconnected-region invariants on the **filtered body**, excluding only
tracked default-lock indices from actual postprocessing output. It does not test through
`stripGeneratedLocks`, which restores the body before deletion. A separate regression explicitly records the fallback coordinates
and lack of a guarantee that all locks lie inside the region. Passing body validation or oracle equivalence is not safety approval
that locks always remain inside shapes. This edge case and explicit JUMP preservation in the native bridge require
independent Codex review; actual sewout remains unvalidated.

### Independent comparison record of earlier exploratory matrix (matrix-v1, history)

Original `compare_matrix.py` results (criteria SHA-256
`fbbf03ab6872d767b2c996f46f3c1cf3055b0b9f8f54b4c0a35fccdc095d6b74`):

| Submitted feature | Equivalent | Mismatch | Undecided |
|---|---:|---:|---:|
| stitch_plan | 168 | 0 | 1 |
| ties | 168 | 0 | 1 |
| stroke | 169 | 0 | 0 |

The 2 undecided cases are `ORACLE_UPSTREAM_ERROR`, errors in official reference output. Features after S2 not submitted
are not included here. This is a **PES-output comparison**, separate from the mechanical release gate,
full upstream source audit, and actual SE400 sewout pass. Regenerate the matrix at the final commit,
fix the commit and clean state in `manifest.json`, then submit to Codex.

### S1 automatic-default build validation

- `:engine:test`: **393 passed**; `:app:testDebugUnitTest`: **171 passed** (0 failures/skips).
- `:app:assembleDebug` succeeded. Log: `/tmp/myembro-worker-s1-automatic-tests.log`.
- Retain original adapter byte preservation for 6 fixtures and existing byte regressions for the writer default profile.
- App regressions: fixed defaults/recomputation of old projects; raw PES bytes/stitches unchanged even with 10 lock-type options;
  consistent project PES generation for viewer/USB. Advanced type options are test-only engine inputs, not user UI.
- The previous whole-shape containment invariant no longer holds due to changed default locks. Rather than hide this
  by weakening tests, split it into actual filtered-body validation and an explicit upstream fallback counterexample.
- Actual SE400 sewout could not be performed. Final merge/distribution readiness requires independent Codex and user sewout review.

### Active defaults matrix defaults-v2 submission

Independent Codex revised the matrix during work. The runner selects the independently frozen file for the new scope,
`/tmp/myembro-inkstitch-oracle/out/defaults-v2/oracle-freeze.json`, and checks each identity record's
frozen SHA-256/scope/id and **byte equality** between original and materialized SVGs.
Do not use upstream/coordinate/PES output as generation input. Explicitly support the previous scope too without mixing materials.

- Active matrix SHA-256: `17f10563b6743539dba46f6db294f6f63b5f2db29b74d251c6521404755a5fe0`.
- Input: `inputs/defaults-v2--<id>.svg`. Outputs/reproduction command unchanged from above.
- **stitch_plan 56/56, ties 56/56, stroke 56/56 equivalent**, 0 mismatches/undecided.
- Mechanical release gate v2 (`check-release --stage S1`): **144 PASS / 24 BLOCK**.
  threshold SHA-256: `cb9c68f379056ec8215c9e7673cedb5a5d166b47484923ef710c9b870bb5aff1`.
- Submitting **official Ink/Stitch output** to the same independent checker also BLOCKs the same 24 `crossing_strokes`.
  Starting S0 in three sections is counted as `UNEXPLAINED_ZERO`; small 4–10mm inputs also block on body short-stitch ratio.
  Functional start/end locks are detected. Do not pass these with arbitrary exceptions or alter upstream defaults.
- Store `independent-comparison.json`, `release-matrix.json`, `release-gate.json`,
  `reference-release-matrix.json`, `reference-mechanical-gate.json` in `/tmp/myembro-port-out/worker/`.
  The release matrix's font axis is `feature/shape` fixture IDs, not product fonts/safe size ranges.
- **S1 merge/distribution approval pending**: remaining Codex gate review and user actual sewout. Do not automatically advance to S2 or later.
  Code/unit tests/build are ready; external review/sewout completion time is undecided.

### Full check after integrating main 95a7447

Merged main's Lint fixes/S1 processing statistics/options. S2 PEC trims and boundary-lock options
preserve current S1 processing statistics; record default sewing-options metadata in completed fromGroups output
so app reinvocation with the same defaults does not reprocess original font locks/trims.
Size-transform regressions compare with coordinates obtained by applying current S1 defaults to transformed body stitches, instead of earlier fixed lock lengths.

`./gradlew check`: **BUILD SUCCESSFUL**. Engine450, app Debug177/Release177, failures0;
Android Lint passed. The4 official frozen oracle cases retain stitch-count/trim/coordinate checks too.
CI follows main's all-branch push trigger and full engine-test-results artifacts.

### codex/model + staging/s2s3 integration (2026-10-24)

- Merged staging `df7da34` into model `3170545`. There were 8 actual conflicts;
  `ci.yml` merged automatically. Retained all-branch push CI and model's Python aggregation-test step.
- `GeneratedPatternProcessor` and `StitchPlanBuilder` are byte-identical to staging letter/S1 files.
  Preserve PEC jump trims/thread-boundary locks/processing statistics. Model's default sewing-options metadata
  recording already exists in staging, so it was not redefined.
- Gradle's 3 letter reproduction tasks already had the same definitions in model; no duplicates added.
  Retained tatami sweep/oracle-boundary source set/TTF sweep classpath tasks and JGraphT dependency.
- Preserved every section in both sides' notices/documents. Tests check tatami row phase on the actually filtered body
  and compare transformed-letter lock coordinates to S1 regeneration while retaining model's length checks.
- `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check --console=plain`:
  **BUILD SUCCESSFUL in 1m 10s**, 67 tasks (58 executed / 9 up-to-date).
  JUnit XML: engine **571**, app Debug **177**/Release **177**, failures/errors/skips **0**.
  Android Lint passed. Also recorded [command output](checks/model-s2s3-check.log).
- `python3 -m unittest discover -s tools/tests -p 'test_*sweep.py' -v`:
  **Ran 11 tests; OK**. Code also verified retention of both sides' headings and identity of staging common processor files.
- [TTF sample sweep results](s3-ttf-fill-sweep.md) record the frozen engine before merging.
  Tatami official equivalence/TTF integration tests also passed in the post-merge full check.
  Distinguish remote CI and user actual sewout from these local checks.

## claude-canvas S3: Ink/Stitch embroidery font catalog/list (branch `claude/inkfonts-s3`)

Rebased onto `staging/s2s3`. Font reading/layout/sewing (`engine/.../inkstitch/lettering/InkStitchFont.kt`, `InkStitchLettering.kt`) use embro-letter's implementation unchanged. This branch adds only:

- Catalog `app/src/main/assets/fonts/inkstitch-catalog.json`: pinned URLs/sha256, glyph names, allowed height ranges for redistributable fonts. Generated by `tools/inkstitch_catalog.py`.
- Allowed heights: `InkStitchFontSweep` (opt-in engine test) evaluates 3 text samples with `SewoutCheck` across each font's recorded range (60mm cap) at 2mm increments; uses the longest consecutive passing interval. Results in `docs/inkstitch-font-sweep.json`. Count hoop overflow separately rather than shrinking the range (confirmed by coordinator).
- List UI: put "Validated embroidery fonts" first (name/stitch preview); dim and disable fonts lacking input characters. Limit the height slider to the allowed range.

### Numerical breakdown of remaining4 font cases from 92/96

Targets are the 4 `forceBoundaryLocks=false` cases in independent comparison
`latest-letter-fonts/df7da347cda358092a4ee29c19fe256474f12913/`. testfont/compressed/multifile/compressed multifile are all
**150mm BAAB** and have the same cause. Do not mix them with true results that enforce start locks by policy.

| Metric | Official / port (common to 4 cases) |
|---|---|
| Needle count | 850 / 850 |
| Hausdorff (bbox-center translation only) | 0.1mm |
| Total trims | 8 / 7 |
| First needle command index (from 0) | 4 / 1 |
| Control order/count after first needle | Match (TRIM 7 / 7) |
| Maximum control-position deviation after first needle | 0.0mm |

Official initial commands: `J(-184.6,75) → J(-297.1,75) → T(-297.1,75) →
J(-409.6,75) → S`(mm)이며 포트는 `J(0,-75) → S`.
The official first J is the PEC viewport position prefix; the next **225mm** position move exceeds
per-axis maximum **204.7mm**, splitting into **112.5mm in two segments**. Thus there is
one extra trimjump only before the first actual needle. No difference in the full850-needle path or7 trims during sewing.

Upstream comparison (read-only):
- Ink/Stitch `lib/output.py:write_embroidery_file`
  @b0edd96311ece82ee48816dc93466274b12c2a9c: origin translation, full_jump/trims output settings.
- pystitch `PecWriter.py:pec_encode`, `MAX_JUMP_DISTANCE=2047`
  @8fc0b113e02ea81d9a507bfad3f7fd624a8895bd: only the first J in init is jump; later J are trimjump.
- Same commit, `EmbEncoder.py:interpolate_gap_stitches`, `jump_to`: ceil subdivision by per-axis maximum length.
- Compare with move normalization starting at origin in port `io/PecWriter.kt:normalizePattern`.

**Documented as an allowed difference**: needle-up trim1 from absolute placement/PEC serialization before the first needle.
Do not change letter paths or force the app PES origin to match official SVG placement.
The official comparator's `TRIM_STOP_COLOR_ORDER` and **92/96 decision are unchanged**;
the independent evaluator must review this breakdown's evidence. Do not generalize it into a blanket exception for missing trims.

Reproduce: `python3 tools/breakdown-font-residuals.py` (read-only JSON/gzip, at most10 cases).
Check input PES SHA/cache identity and compare all control commands/coordinates after the first needle.
Output: `/tmp/myembro-port-out/letter/font-residuals/residuals.{json,md}`.
Validation: all 4 `INITIAL_NEEDLE_UP_TRIM_ONLY`, unresolved0, script exit0.
Engine generation logic/gate/oracle caches unchanged.

### Font NPE7 cases and bean/fill integration regressions

Cause: `InkStitchLettering.render` called `params[key]!!` while iterating parameter keys.
The accessor returns null for empty/whitespace attributes, causing NPE.
Upstream `lib/elements/element.py:get_param`@b0edd96311ece82ee48816dc93466274b12c2a9c
uses the default when empty after `.strip()`. Now exclude empty attributes and use each port's defaults.
All7 fonts had empty attributes: chicken_scratch164, digory_doodles_bean193,
emilio_20_applique8, emilio_20_bold8, manga_impact347, precious10, roaring_twenties_KOR_small368 cases.
Roaring Twenties also contains the number `.&`; added `SatinParametersCodec.decodeSvg`,
which uses defaults on SVG numeric parsing failure like `get_float_param`. Project `decode` retains
existing strict input validation/round-trip contract.

Connections (no new stitch algorithm implementation):
- InkRun → worker `elements/Stroke` + `StrokeParameters.fromAttributes`: bean/repeats;
  apply final trim/stop across all subpaths once to the final group, like the upstream facade.
- InkFill → copied model's staging/s2s3 **4899d66** `FillParameters`, `FillUnderlay`,
  `TatamiFill/FillRouting` and related files unchanged. JGraphT/licenses also follow
  the same snapshot's dependencies/notices. Existing native `InkAutoFill` unchanged.
- `FontTatami` is a coordinate/group connection adapter. Preserve SVG px; construct regions/holes by SVG fill-rule;
  pass component-wise underlay→top fill, upstream nearest-next-component end position,
  tag jump separation, colors/upstream locks/minimum lengths/trim/stop through S1 once.
  Upstream comparison: `fill_stitch.py:to_stitch_groups/do_underlay/do_tatami_fill` (same pinned upstream).
  This unit connects only default tatami/auto_fill. Do not arbitrarily replace other fill_method values with tatami.

`InkFontElementRegressionTest`: runs4 cases in CI without external files for empty attributes/invalid numbers/bean/
fill with holes/PES round trips. Separate local7-font tests use an environment variable specifying release-data paths;
input fonts are not bundled in the repository. Reproduce KOR_small with **Hamburg**, as in the actual sweep
(this font's record is Latin; do not assume Korean alphabet support).
Included model's FillParameters/FillCore/FillUnderlay/FillRandomRunning tests unchanged too.

```sh
INKSTITCH_REGRESSION_ROOT=/tmp/myembro-port-out/letter/font-crashes/inputs \
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check
```

Results: engine502/app Debug and Release177 each, failures0; Lint/full check passed.
All local7 fonts rendered/PES round-tripped; existing frozen oracle4 passed.
Hamburg stitch counts: chicken1370, digory1870, applique23914, bold18588,
manga1743, precious5289, KOR_small826.
Reproduction files/input hashes/empty-attribute examples/before and after XML/output:
`/tmp/myembro-port-out/letter/font-crashes/`. This is a crash/integration regression decision;
do not extend it to independent gate/actual sewout/full catalog certification.

## S5 separate upstream: partial monochrome contour/polygon port

This prepares photo input, a feature absent from Ink/Stitch; do not identify it as ported Ink/Stitch code.
From upstream Potrace 1.16 / mirror commit `886392d351417d6753aff61de08e294d1506e502`,
`src/decompose.c`, `src/trace.c`, `src/bitmap.h`, `src/auxiliary.h`, ported to internal engine
`BinaryContourTracer` / `OptimalPolygon`. Preserve GPL-2.0-or-later attribution/full license.
See the first porting unit in [image-pipeline.md](image-pipeline.md) for detailed hashes/reproduction commands.

| Location | Difference from upstream | Reason/limitation |
|---|---|---|
| Monochrome bitmap | BooleanArray, XOR anchored at x=0, copied input | Replace only word storage; preserve crossing parity |
| Path area | Retain Long, omit C API INT_MAX saturation | Intermediate engine API, not connected to existing PES API |
| Decomposition result | Traversal order and positive/negative signs, no hierarchy tree | This unit ports only default MINORITY tracing |
| After polygon | No vertex adjustment/Bezier/curve optimization | Do not connect incomplete intermediates to the product path |

Representative Kotlin functional tests 8 and all engine tests 293 passed. No upstream execution/independent gate run;
no claim of equivalence/sewout quality passing. No new PES/app entry point either.


### S5 curve-fitting follow-up unit

Ported `pointslope`/`adjust_vertices`/`reverse`/`smooth` from the same Potrace commit
to `CurveFitter.kt`. Updates the unimplemented vertex adjustment/Bezier status in the table above.
Curve optimization (stage 5) and hierarchy tree remain unimplemented; app unconnected.
For 3 small PBMs, ran unchanged upstream C with `opticurve=0` and compared:
boundaries/polygons match exactly; vertices/control points/alpha/beta absolute error within 1e-10.
Engine tests 296 passed including 3 new comparison tests. Reproduction commands/hashes:
`engine/src/test/resources/potrace/README.md`. No claim of independent sewout quality passing.


### S5 curve-optimization follow-up unit

`CurveOptimizer.kt` ports the same upstream commit's `opti_penalty`/`opticurve` and geometry functions.
Automatically compares upstream final paths at default opticurve=1 / opttolerance=0.2px.
8 stage 4/5 comparisons of 4 representative PBMs passed; assert the same upstream 7→3
curve merge on rounded shapes. Coordinate/alpha/beta absolute error within 1e-10. Updates the earlier unimplemented
curve-optimization record; path hierarchy/SVG/embroidery/app integration remain unimplemented.
Reproduce: `tools/potrace/generate-stage5-goldens.sh /path/to/potrace-1.16`.
Output `/tmp/myembro-port-out/editor/potrace-stage5/`; code included in default engine tests.


## Separate upstream: Wu image color quantization

Do not identify this as Ink/Stitch functionality. `ImageQuantizer.kt` ports Xiaolin Wu's
Graphics Gems `gemsii/quantizer.c@ca4898517a93b6b45444a9cda5fd50132694b864`.
Preserve custom Graphics Gems permission/original distribution notices in `licenses/graphics-gems/`.
Differences (per-call arrays/Long and Float preservation/sub-box Vol/input bounds) are
recorded in the Wu table in `docs/image-pipeline.md`. Automatically compare upstream C LUT/pixel
indices on 3 representative RGB cases; no app/stitch-generation connections.


## S4 photo-region fill integration — 2026-10-24

Removed previous running outlines on both sides of black bands from the product line-art path. Flatten Potrace curves
to 0.01 SVG-px, convert to 0.1mm PolygonRegion(EVEN_ODD), then pass to
model `DefaultTatami.stitchGroups`, as with Korean TTF. Top stitches 0.25mm/4mm/0°,
underlay 0.75mm/4mm/90°, underpath=true and S1 defaults unchanged.

Copied the 8 files DefaultTatami/InkAutoFill/TatamiFill/Fill/FillRouting/
FillGeometry/FillRunning/FillPoint from model snapshot `ea5fd8d63f30db770f626ab245f9bc50bbe5bf31` **without modification**.
This prepares API dependencies before S2/S3 merging, not an independent fill reimplementation;
retain the same model files during integration. Preserve GPL attribution headers/JGraphT LGPL selection and transitive library notices/full licenses.

| Integration boundary | Difference/evidence |
|---|---|
| Contour→region | Convert Potrace y-up to SVG y-down; SVG-px→0.1mm, preserve holes through EVEN_ODD |
| Output | Model default tatami/underlay instead of boundary running; apply S1 once |
| Narrow lines | No custom density/spacing compensation; exclude only combinations failing actual generation+sewout checks+external gate |
| Centerline | autotrace etc. are investigation candidates for next version, not applied in this default path |

See [qa/s4-sampler.md](qa/s4-sampler.md) for reproduction/metrics/PNGs/sweep scope.

### P4: remove duplicate needles at font-element JUMP landings

Upstream comparison confirmed **upstream also retains these duplicates**.
`lib/stitch_plan/stitch_plan.py:stitch_groups_to_stitch_plan`
@b0edd96311ece82ee48816dc93466274b12c2a9c JUMPs to `lock_stitches[0]`, then
appends the entire lock list (including the first point). Even without locks, JUMP to the first body stitch,
then append all body stitches. In the same commit, `color_block.py:filter_duplicate_stitches`
exempts duplicates when the previous command is JUMP or the current point is lock_stitch.
Thus this cannot be preserved simply for upstream equivalence; the app's new P4 takes priority.

The default font generation path uses S1 `fromGroups`'s `omitZeroLengthMotions`.
Exclude JUMP/needle moves of length0 against the actual motion cursor; for same-point TRIM→JUMP
restarts, remove both and continue sewing. Do not change remaining half_stitch coordinates.
Reindex locks/commands/statistics; preserve pre-removal body stitches as source metadata,
so regeneration after translation/scaling/rotation retains starts/ends. Propagate the P4 marker to regeneration.
Fix the generated font output itself, rather than hiding the issue in the save writer.

Keep the existing `forceBoundaryLocks=false` path that measures upstream as **upstream-comparison-only**
with product policy disabled; retain original stitch counts for the4 frozen oracle cases.
Default app calls enable both boundary locks and P4 handling. Do not falsely report this difference as upstream equivalence
or weaken comparators/gates. Do not apply this handling to imported PES.

FontNonzeroMotionTest3 cases: two-glyph running/bean/satin/fill rendering and PES round trips,
same-point trim restart, regeneration after size changes/lock indices/idempotence.
Track all JUMP/STITCH continuously from initial physical cursor(0,0), not just needle-to-needle comparisons.
Assert **0-length needles0/JUMP0** in all representative output.
Both2 before-fix regressions failed; after fix, full `./gradlew check` passed:
engine589/app Debug and Release190 each, failures0; Lint and existing frozen oracle4 passed.
Output: `/tmp/myembro-port-out/letter/p4-nonzero/output.txt` and `tests.xml`.

## Photo outline integration (editor)

Apply existing upstream `Stroke`/`RunningStitch`/S1 ports to the same Potrace boundaries.
`bean_stitch_repeats=[1]` (3 total passes), `trim_after=true`, other upstream defaults.
No new stitch algorithm port or Ink/Stitch execution. Details: [photo outlines](qa/photo-outline.md).

| Integration scope differing from upstream | Evidence |
|---|---|
| Reuse JGraphT 1.5.2 NearestNeighborHeuristicTSP for order selection | Preserve all mobile closed contours and apply only when jump-anchor distance decreases; no claim of AutoRun port/global shortest-path identity |

### StrokeToSatin upstream port handoff unit

`elements/utils/StrokeToSatin.kt`: `lib/elements/utils/stroke_to_satin.py`,
`lib/extensions/stroke_to_satin.py:filtered_rungs`, `lib/svg/styles.py`,
`lib/utils/geometry.py`@b0edd96311ece82ee48816dc93466274b12c2a9c.
Rail offsets use approved JTS; satin generation uses the existing SatinColumn port.
Golden status/upstream function list/all differences are recorded in the
“letter handoff unit1” section of `docs/hangul-centerline.md`. Revalidated AutoTrace upstream centerline10/10 exact;
7 analytical line→satin regressions passed; line→satin official-output equivalence/Korean PES remain undecided.

### Korean satin routing unit1

`stitches/utils/SatinRouteGraph.kt`: ports only graph traversal/duplicate-section underpath marking from pinned `autoroute.py:find_path/_get_shortest_path`
and `auto_satin.py:path_to_operations`.
Upstream graph generation/rail cutting/DOM integration incomplete; official oracle undecided.
The7 analytical cases/differences/next dependencies and KRSAT1 motion baseline are recorded in
[Korean satin routing](hangul-satin-routing.md).

### Korean satin routing unit2

`stitches/AutoSatin.kt`: connects upstream auto_satin's Section1mm subdivision/minimum connections/upstream graph
traversal/duplicate underpath/both-rail cutting/rung relocation and addition/running merge to existing ports.
Actually generated KRSAT2 using user-specified glyph/8-direction ink component/bbox order.
Only convert-to-satin Section defaults, not general raw SVG rails; official oracle
equivalence undecided. Upstream functions/all differences/actual-byte jump and lock tables are
recorded in [unit2 report](hangul-satin-krsat2.md).
