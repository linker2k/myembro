> **v6 repeat criterion revision**: App output is blocked when consecutive STITCH/JUMP destination positions repeat more than 2 times (including 0-length moves and needle penetrations).
> Imported files retain the caution policy for excessive repeats. This replaces only the v5 repeat criterion below.
> The needle-only corpus has p99=2, and p99=3 with JUMP included; the threshold of 2 is a user-specified stricter policy.
> Calculation tables, definitions, and reproduction: [sewout-repeat-policy.md](sewout-repeat-policy.md).

> **POLICY v5 (2026-10-24)** — Supersedes the initial policy below. Generated output: more than 15 penetrations in a 0.5mm cell,
> more than 3 consecutive needle penetrations at the same point, or unconfirmed start/end thread locks are blockers. Imported PES: caution, except cell density above 30 is a blocker.
> The maximum stitch is the frozen gate's 12.184118164618463mm (measured maximum across 334 files +0.15mm); file validity and hoop/machine limits remain in force.
> Density is checked at four grid phases with x/y offsets of 0 and 0.25mm each; revisiting after a different needle position is not a consecutive repeat.
> Locks are assessed using the frozen `lock_geometry.py`: backtracking within 1mm / 2–3 boundary penetrations, or the existing functional 3mm lock.
> Short stitches and overlaps are INFO metrics. Badges are Pass/Caution/Block, and transfer cannot bypass a blocked state.
> Provenance is established by the engine generation marker and SHA-256 records of PES exported by the app. It is not inferred from extensions or filenames.
> PES with unknown provenance predating these records is treated as imported. Inspection does not modify needles/files.


Regression comparisons with Python gate v6 use `GatePolicyParityTest` and a total of 10 public S1 sampler and synthetic boundary cases.
They compare the SHA-256 of identical PES bytes, verdicts (generated/imported), stitch counts, 4-phase density, consecutive repeats,
missing locks, and maximum length. Import verdicts are calculated by the POLICY adapter, not v5's own options.
CI reads stored expectations; updates are explicitly run with `tools/refresh_sewout_gate_reference.py`.
END/commands outside the declared PEC block are blockers, and combined TRIM+JUMP flags are preserved.
0-length segments for control commands are excluded from lock opportunities but included in density/consecutive needle counts.
The first needle's move from the origin is also included in the actual PES maximum stitch length. The public `PesReader` API remains unchanged.

# Pre-sewing Validation — Sew-out Check

2026-10-23. The `engine.sewout` API in the pure JVM `:engine` module follows input commands in order
and calculates problem coordinates, operation order, time, and thread consumption. It does not modify the input or PES.
It is not a physical simulator of machine firmware/motors/fabric, nor certification of acceptance by an actual machine model.

## Public API and UI integration

```kotlin
import io.github.linker2k.myembro.engine.sewout.*

val options = SewoutOptions(
    plannedSpm = 350.0,             // 사용자가 설정/측정한 값; 생략하면 프로필 또는 가정값
    availableBobbinMeters = 60.0, // 실제 측정한 잔량. 모르면 null 유지
)
val preview = SewoutCheck.check(pattern, SewoutMachines.SE400, options)
val placed = SewoutCheck.check(project, SewoutMachines.SE600, options)
val final = SewoutCheck.checkPes(exportedPesBytes, SewoutMachines.SE700, options)
// final.findings / final.density / final.tasks / final.stages
// final.hasBlockingIssues / final.analysisComplete / final.compatibility.acceptance
```

`check(pattern)` and `checkPes` default to `CENTER_DESIGN`, placing imported files at the center of their movement bounds.
`check(project)` uses `project.flatten()` and **checks the project hoop and placement coordinates as they are**.
It does not automatically shift the hoop center to hide out-of-bounds placement. A plain pattern can also use
`placement = HOOP_COORDINATES`. For multiposition designs, check each split output file separately.

Report coordinates **retain the input coordinate system, in 0.1mm units, with +y downward**. The UI handles screen transforms.
`Highlight` provides inclusive, 0-based index ranges of original commands, segment endpoints, and grid rectangles.
Density is available as the full `DensityCell` list, operation order as `OperatorTask`, and color stages as `ColorStage`.
Stage indices also start at 0. `FindingCode` and `Severity(BLOCKER/WARNING/INFO)` are stable identifiers
for decisions, with a separate Korean `message`. Original reader warnings are also retained verbatim.

The UI must display `hasBlockingIssues`, `analysisComplete`, and `suppressedFindings` together.
If machine version/color-count limits are unconfirmed, `compatibility.acceptance` is
`UNVERIFIED` even without blockers. `WITHIN_CONFIRMED_LIMITS` does not mean an actual machine has read and sewn the file.
This work did not change the UI or existing Preflight. Integration follows embro-editor's viewer revision.

## General inspection rules

| Check | Default rule | Result |
| --- | --- | --- |
| Hoop | Whether all command coordinates are inside the selected hoop; includes move/END positions | Blocker, problem coordinates |
| Machine area | Whether the selected hoop and movement bounds fit within the confirmed single sew-out size | Block if exceeded |
| Long stitch | Euclidean length of an actual STITCH move >12.1mm | Warning; adjustable quality policy |
| Short stitch | STITCH move <0.3mm | Warning; excludes first point and 0-distance starting anchors after jumps |
| Physical maximum length | Applied only to profiles with confirmed `maximumStitchMm` | Block if exceeded; unconfirmed for the three default machines |
| PEC displacement | STITCH displacement on one axis exceeds 2047 units | Warning; recheck the actual exported file |
| Needle density | STITCH endpoints in a 0.5mm grid >8 times (9 or more) | Blocker, grid location |
| Same-point sewing | Entry at identical integer coordinates >3 times (4 or more); accumulates across colors/objects/trims | Blocker, coordinates of fourth entry |
| Start lock stitch | Short anchoring not confirmed at design/object start or first sewing after TRIM/color change | Warning, starting command range |
| Same-segment sewing | Sew a segment with the same endpoints 3 times regardless of direction | Cumulative overlap warning |
| Accumulation across stages | 2 or more color stages pass through the same grid cell 6 or more times | Accumulation warning |
| Statistics | STITCH·JUMP·TRIM·COLOR_CHANGE·STOP, stages/unique threads, size/length | Structured metrics |

**12.1mm has not been confirmed as a byte limit of the PES format.**
The [pyembroidery 1.5.1 PES writer](https://github.com/EmbroidePy/pyembroidery/blob/1.5.1/pyembroidery/PesWriter.py) and
[PEC writer](https://github.com/EmbroidePy/pyembroidery/blob/1.5.1/pyembroidery/PecWriter.py)
set maximum displacement per axis to 2047 units (204.7mm). This differs from the physical maximum stitch length of an actual machine.
The requested 12.1mm is retained as a conservative warning policy, without falsely blocking it as a profile limit.
0.3mm, grid >8 penetrations, same point >3 entries, and cumulative 6 passes are likewise inspection policies, not quantitative manufacturer guarantees.
**Blocking grid >8 penetrations and same-point >3 entries is a safety policy from the 2026-10-23 user regression request for a defective design**,
not an official machine limit. `hotspotPenetrations=9` and `repeatedPointThreshold=4` are
**inclusive thresholds** at which blocking begins. Default severity changed from warning to blocker.
Thresholds can be adjusted through options, but changing grid size also changes the meaning of the density policy.

Needle holes and thread-path passes are distinguished. `penetrations` counts only STITCH endpoints,
while `segmentPasses`/`sewnLengthMm` counts sewn segments passing through the grid. A long thread crossing a cell
does not count as needle holes in between. Negative coordinates use floor to determine the grid.
Segments are split and counted at actual grid boundaries; cells beside a diagonal corner are not arbitrarily counted.
`stageIndices` identifies which color stages contributed accumulated thread.

PES has no reliable flag preserving lock-stitch semantics, so locks are inferred from starting coordinates.
Within the first 6 consecutive commands from the first STITCH, the first four points excluding consecutive
0-distance anchors must form short backtracking (A-B-A-B) or triangular (A-B-C-A) anchoring with three moves of 0.3–1.5mm.
The four points must be within a 1.5mm radius of the start, with return-coordinate error no greater than 0.3mm.
Anchoring is not synthesized across jumps/trims/color changes/STOP, and 0-distance repetition alone is not accepted as a lock.
Other valid locking methods may trigger warnings, and small ordinary stitches may resemble anchoring,
so `MISSING_LOCK_STITCH` is an **unconfirmed warning**, not a definitive assessment of lock presence or strength.
Recognizing a lock shape does not exempt it from density/same-point blocking criteria.

Project object boundaries are inspected through TRIM from `flatten()`, so objects using the same thread are checked individually.
Plain PES/patterns lack object IDs, so initial sewing and TRIM/color changes serve as boundaries.
JUMP/STOP after a boundary does not consume a pending check; it waits until the first actual needle position.
Empty objects without STITCH or a final TRIM do not produce start warnings. Lock checks do not include end locks,
and do not automatically correct commands or insert machine stops.

Processing stops at the first END; without END, the end of input is treated as termination. The first coordinate becomes the start,
and the unknown preceding move from the origin is not counted as a long stitch/thread consumption. Subsequent moves follow
actual current coordinates to the next command even across JUMP/STOP/color changes. If a control command changes coordinates,
it is calculated as a needle-up move, as in the current writer, so JUMP statistics may include implicit moves.
Color changes are recorded separately from actual color-stage counts and unique RGB counts; STOP does not add a color stage.

A reference for general inspections returning problem locations and severity is
[Ink/Stitch Troubleshoot](https://inkstitch.org/docs/troubleshoot/). The code and numeric defaults
are independently implemented, not a port reproducing Ink/Stitch, PE-Design, or Embrilliance behavior/thresholds.
The [official SE700 manual p.96](https://download.brother.com/welcome/doch102582/888p10_om01en.pdf)
warns of thread/needle problems with excessively dense embroidery or 3 or more overlapping layers.
This qualitative warning is not converted into a definitive breakage probability for each fabric/needle.

## Machine facts and unconfirmed items

| Item | SE400 | SE600 | SE700 |
| --- | --- | --- | --- |
| Single sew-out area | 100×100mm confirmed | 100×100mm confirmed | 100×100mm confirmed |
| Automatic cutting at color-stage end | Confirmed when enabled | Confirmed when enabled | Confirmed when enabled |
| Automatic jump trimming capability/actual setting | unconfirmed | unconfirmed | unconfirmed |
| Guidance on manual cleanup of connecting threads within a color | Specific jump conditions unconfirmed | Official guidance available | Official guidance available |
| Maximum embroidery SPM | unconfirmed in this investigation | unconfirmed in this investigation | 400 confirmed |
| Stitches per transferred design | 30,000 confirmed | unconfirmed | unconfirmed |
| Stitches per design in internal memory | 30,000 rule applied | unconfirmed | 100,000 confirmed |
| PES version list/color count/per-file size | unconfirmed | unconfirmed | unconfirmed |
| Whether PEC thumbnails are required/length of one bobbin | unconfirmed | unconfirmed | unconfirmed |

Sources and exact pages are in the Sew-out section of [machines.md](machines.md) and
`Specification.source` in `SewoutMachines`. null means unconfirmed, not unsupported/unlimited.
SE700's 100,000 stitches apply only to `Destination.INTERNAL_MEMORY`. Its 512KB/1024KB total internal
storage capacity is not converted into a per-file allowance. SE600/SE700 do not inherit SE400's 30,000 stitches.

## Operation schedule

Color changes, explicit STOP, and termination have `machineStops = true`. Jumps/standalone TRIM are not stop commands,
so the need for manual cutting is not displayed as an automatic machine stop. To cut at that point,
the operator must pause manually according to `operatorMustPause`. Machine commands are not inserted or modified.

- `REQUIRED`: Automatic jump trimming is confirmed unsupported, the user disabled it, or official manual connecting-thread
  cleanup guidance exists. SE600/SE700 guidance also includes cleanup after sewing, so it is not interpreted
  as a manufacturer's instruction to stop immediately before that particular jump.
- `POSSIBLE`: Support/settings are unconfirmed, so manual work may be needed after checking connecting threads.
- `AUTOMATIC`: Used only when both confirmed support and the user-entered enabled setting are satisfied.
  Automatic jump trimming is currently unconfirmed for the three default profiles, so enabling a setting does not make it confirmed.

Consecutive JUMPs and accompanying TRIM are grouped into one connecting-thread segment; moves before sewing starts
do not create manual cuts. STOP/JUMP after an already handled standalone TRIM does not count the same thread for cutting again.
`requiredManualCuts/possibleManualCuts` counts jump/standalone TRIM operations, separately from color changes/end-thread cleanup.
Tasks include command range, endpoint coordinates, color stage, planned start time, and task duration.

## Time and thread consumption — exposing assumptions

SPM is selected in order from entered `plannedSpm`, confirmed maximum SPM×0.8, or an assumed 400 SPM if unconfirmed.
Input exceeding a confirmed maximum produces a warning and calculations use the maximum. The maximum sewing speed of 710 SPM
is not used as embroidery speed. `SpeedEstimate` provides separate sources for machine maximum and current estimated speed.

- Needle time = stitch count×60/SPM.
- Travel time = nonzero jump length/50mm/s + 0.2 seconds per move command.
- Default assumptions: automatic cut 1 second, manual cut 8 seconds, color change 30 seconds, programmed-stop work 10 seconds.
  The color-change 30 seconds includes thread replacement/manual cleanup; a separate 1 second is added when automatic cutting is confirmed.
- Unconfirmed cutting tasks are included in the total as planned work. `conditionalOperatorSeconds` and
  `withoutConditionalCutsSeconds` also provide values excluding them. These are not probabilistic error intervals.
- Upper thread (m) = (sewn length (mm)×1.25 + uncut jump length + 50mm cut/change tails×count)/1000.
- Bobbin thread (m) = (sewn length (mm)×0.5 + uncut jump length + 20mm tails×count)/1000.
  Connecting-thread length is excluded only when automatic jump trimming is confirmed.

All coefficients, speeds, overheads, and tail lengths can be calibrated through options. Time/upper thread/bobbin thread
are returned per color stage; totals sum the same stage values. This is not a model measuring tension, thread thickness,
fabric thickness, or acceleration/deceleration, so actual consumption may differ. `ENOUGH/INSUFFICIENT` is determined only
when remaining bobbin thread is entered or measured profile capacity is provided. Default profiles have unconfirmed capacity,
so the result is `UNKNOWN`.

## File acceptance and resource limits

`checkPes` checks actual signatures/PEC offsets, software readability, byte count, and graphics-area structure.
Not all manufacturer hoop codes/settings in the PES header are currently interpreted. Since it checks the specified hoop and
actual movement bounds, firmware rejection caused solely by a stored hoop code remains unconfirmed.
Successful PES v1/v6 output or successful reading by the app/pyembroidery is not used as certification of SE400/600/700
firmware acceptance. Only confirmed machine version/color/file-size conditions are compared; others remain UNKNOWN.
Reader warnings about truncated sewing data are elevated to blockers. Arbitrary source files or user material are not scanned.

Defaults limit commands to 250,000, grid cells to 200,000, path-grid visits to 1,000,000, and retained Findings to 1,000.
Grid size can be set to 0.01mm or greater. Exceeding a limit returns `analysisComplete=false` and a blocker.
Statistics/estimates in incomplete results describe only the analyzed portion. Even when the retained Finding limit is exceeded,
overall counts by severity/code and blocking status are preserved, with blockers/warnings retained ahead of information.
Do not hide `suppressedFindings`. Full command/grid/task lists are not returned without bounds.

## Tests

```bash
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test
```

The repository's 26 redistributable samples under CC0/MIT and similar licenses are checked against three machines.
Existing redistributable flower fixtures and synthetic commands test boundary values, stop order, TRIM/JUMP deduplication,
thread/time formulas, coordinate placement, negative grids, needle-entry/thread-path distinction, malformed PES, limits, and determinism.
User PE-Design material and private PES in `/tmp/myembro-fonts-ref` or scratchpad were not used in unit tests/commits.
Actual machine file recognition, physical sew-outs, and calibration of time/thread coefficients remain follow-up validation.

Validation results: `:engine:test` **227 passed** (including 13 SewoutCheck regressions + 9 safety-policy regressions).
Actual PES inspection of 26 bundled samples×3 machines and determinism checks of existing fixtures passed.
Synthetic coordinates test 8/9 and 3/4 boundaries, accumulation across colors/trims, warnings at start/trim/object boundaries,
backtracking/triangular anchoring, prevention of false recognition of 0-distance anchors/long backtracking, PES round trips of fan repeats,
and preservation of blockers when report details are suppressed.

A separate local regression read a user-specified private defective PES only in `/tmp`. Files, images, original
coordinates, and derived files were not added to the repository/fixtures. The old policy blocked none of SE400/SE600/SE700,
whereas the new default policy blocks all of them. Maximum 0.5mm cell count is 38, identical-coordinate count is 10,
with 78 density blockers, 113 same-coordinate blockers, and 22 unconfirmed start-lock warnings; analysis completed.
The 256 short-stitch warnings comprise 255 nonzero stitches below 0.3mm and 1 0-distance repeat.
No check classifying the fan-shaped satin itself was added; blocking is based on accumulated needle entries.

## Automated policy regressions (2026-10-24)

- `SewoutSafetyTest`: 15/16, 30/31/38, 3/4 boundaries, four-phase density, start/end locks, provenance, file/hoop/maximum stitch, suppressed blocking results.
- `S1PolicyRegressionTest`: Regenerate a total of 5 `.myembro` inputs from the actual Android app: “ABC 가나다” at 5/8/12mm, a circle, and a square
  → default PES v1 → assert passing the `SewoutCheck` generated-output criteria.
- 2 official v3.3.0 ties rectangle/stitch_plan corner cases assert stitch count ±5% and symmetric Hausdorff ≤0.2mm.
  Only bbox-center translation is normalized; rotation/scale are not matched. A total of 7 representative cases, with no large matrix execution.
- Provenance/input hashes: `engine/src/test/resources/{sewout/s1,inkstitch-oracle}/PROVENANCE.json`.
  No personal designs or CI dependency on `/tmp`. Original programs are not executed.
- Runs automatically under `./gradlew check`. CI runs the same command and APK build on every push/PR.

Basis: `/tmp/myembro-quality-gate/POLICY.md` and frozen gate v5.

Fixed-reference SHA-256: thresholds.json `a3fbf692887a40a4539247bfd649cec27075d5adc696afd57777c996bef3f848`, lock_geometry.py `5488e5070e857f50ae8120bd53e4e73e3a7c76aa4a935992451cee51218c7f42`.

Validation command: `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug --console=plain`.
Execution output: **BUILD SUCCESSFUL in 46s**, engine 401 tests, app debug/release 172 each, 0 failures.
Results for 7 representative cases automatically extracted from JUnit: `/tmp/myembro-sewout-policy-results.json`.
Existing API-27 theme items discovered during full check were moved into v27 resources, and asynchronous UI loading was
organized with explicit remember/LaunchedEffect state updates. Lint checks were not disabled and criteria were not lowered.
