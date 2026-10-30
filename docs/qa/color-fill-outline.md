# Two-pass staggered same-color outline after color and silhouette fill

Base: main `51f0441`. Initial scope: `ColorDrawingPipeline`. Updated 2026-10-28 to include filled `RedworkPipeline` silhouettes; vector/emoji, texture and the separate OUTLINE mode are unchanged.

Each retained TATAMI region is followed immediately by its original polygon exterior and
interior rings. The existing `Stroke` port generates the stitches; there is no new boundary,
routing, running-stitch or lock algorithm. Its Ink/Stitch v3.3.0 source attribution remains
in `Stroke.kt` and `StrokeParameters.kt` (upstream commit
`b0edd96311ece82ee48816dc93466274b12c2a9c`). Parameters are `repeats=1`,
`bean_stitch_repeats=[0]`, `running_stitch_length_mm=[1.2]`; other defaults are unchanged.
Since 2026-10-30 each ring is sewn twice (see the two-pass decision below).
The same color and region ownership tags are retained, without an added color block.

User decision 2026-10-28: use one running pass (Ink/Stitch default), not three bean passes. Manager measurements in `/tmp/myembro-p7-freq` found peaks >=16 in 2–3% with fill alone (max 16–19), 17% with one pass (max 19), and 66% with three passes (max 29). Silhouettes reuse `ColorFillOutline.stitchGroups` and `select` with whole-region fill followed by the existing `PhotoOutline.route` order, including holes; no expansion is applied to their outlines. Existing photo extent fitting and final PES checks remain.
The original region-to-region trim is transferred to the end of its outline.

User decision 2026-10-30: the length is 1.0mm. The earlier 1.655mm (running-outline corpus
median, approved 2026-10-27) looked too sparse. A 1.0mm run puts about one needle in a 0.5mm
density cell. Measured on the repository images `example-logo-flat.png`,
`launcher-icon-artwork.png` and `readme-banner.png` at 40/60/90mm, color and silhouette
(18 outputs): stitches +4–6%, maximum needles per 0.5mm cell unchanged or +1 to +3
(highest 25, below the 30 limit), cells with 16+ needles unchanged or fewer, skipped outlines 0,
blocked outputs 0, for both lengths.
User decision 2026-10-30 (supersedes the 1.0mm single pass): **two passes of the 1.2mm
Ink/Stitch run, the second staggered by half a stitch.** Pass 1 is the unchanged `Stroke`
running stitch at 1.2mm. Pass 2 continues without a jump or trim and re-phases pass 1:
within each Ink/Stitch curve it uses the midpoints of pass 1's stitches. Following the
2026-10-30 corner correction, it also visits the original pass-1 needles at the existing
`RunningStitch.pathToCurves` boundaries, including the closing seam. It closes at the
original seam point, not by joining the last midpoint directly to the first midpoint.
Locks are planned after both passes. Reusing these boundary holes is intentional;
on the rectangular controls, no other first-pass hole is reused. The
effective interior penetration spacing is about 0.6mm; the user's estimate is 1–2 extra needles per
0.5mm density cell. No new running-stitch algorithm, same color, region tags and selection.
Color, silhouette and block-edited photo regions all use `ColorFillOutline.stitchGroups`.

Corner correction verification (`integrate/beta3 fc0b6245` base): independent PEC
measurement of a 20mm square with a 10mm square hole gives maximum hole intrusion
**0mm for both passes** (previous two-pass result: 0.3mm). The original square exterior
also has 0mm excursion. Four guarded 40mm outputs have BLOCK=0: launcher color/silhouette
four-phase peaks **24/14**, banner color/silhouette **23/22**; independent decoding agrees,
with zero 0-length motions and maximum consecutive identical needle count 1.
Reproduction and exact rational hole-distance proof: `/tmp/myembro-corner-out/report.md`.

The table below predates the corner correction and retains its original measurements.

Measured 2026-10-30 (temporary engine test, removed): repository images resized to 192px wide,
color = `ColorDrawingPipeline.trace(...,4)` + `generate(preview,HoopSpec.H100)`, silhouette =
`RedworkPipeline.trace` (alpha<128 → white, RGB24) + `generate(...,photoMode=true)`. Max cell =
most STITCH commands in one 0.5mm cell (`floorDiv(x,5)`, `floorDiv(y,5)`); ≥16 = cells with 16+;
block = `SewoutCheck.checkPes` blocking. Old = 1.0mm × 1 (main `9742b1b`), new = 1.2mm × 2.

| Image | mm | Mode | Stitches old → new | Skipped | Max cell | Cells ≥16 | Block |
|---|---:|---|---:|---:|---:|---:|---|
| example-logo-flat | 40 | color | 1989 → 2190 | 0 → 0 | 7 → 7 | 0 → 0 | PASS |
| example-logo-flat | 40 | silhouette | 2006 → 2206 | 0 → 0 | 6 → 8 | 0 → 0 | PASS |
| example-logo-flat | 60 | color | 3453 → 3749 | 0 → 0 | 6 → 7 | 0 → 0 | PASS |
| example-logo-flat | 60 | silhouette | 3473 → 3769 | 0 → 0 | 5 → 8 | 0 → 0 | PASS |
| example-logo-flat | 90 | color | 6303 → 6742 | 0 → 0 | 6 → 7 | 0 → 0 | PASS |
| example-logo-flat | 90 | silhouette | 6286 → 6723 | 0 → 0 | 7 → 8 | 0 → 0 | PASS |
| launcher-icon-artwork | 40 | color | 2701 → 3092 | 0 → 0 | 20 → 23 | 1 → 1 | PASS |
| launcher-icon-artwork | 40 | silhouette | 1874 → 2095 | 0 → 0 | 9 → 10 | 0 → 0 | PASS |
| launcher-icon-artwork | 60 | color | 5261 → 5975 | 0 → 0 | 11 → 13 | 0 → 0 | PASS |
| launcher-icon-artwork | 60 | silhouette | 3069 → 3391 | 0 → 0 | 10 → 13 | 0 → 0 | PASS |
| launcher-icon-artwork | 90 | color | 13030 → 14730 | 0 → 0 | 15 → 15 | 0 → 0 | PASS |
| launcher-icon-artwork | 90 | silhouette | 5286 → 5763 | 0 → 0 | 10 → 12 | 0 → 0 | PASS |
| readme-banner | 40 | color | 1050 → 1230 | 0 → 0 | 15 → 16 | 0 → 1 | PASS |
| readme-banner | 40 | silhouette | 1811 → 2083 | 0 → 0 | 13 → 18 | 0 → 2 | PASS |
| readme-banner | 60 | color | 2129 → 2470 | 0 → 0 | 11 → 12 | 0 → 0 | PASS |
| readme-banner | 60 | silhouette | 2661 → 3052 | 0 → 0 | 16 → 19 | 1 → 2 | PASS |
| readme-banner | 90 | color | 5418 → 6212 | 0 → 0 | 11 → 14 | 0 → 0 | PASS |
| readme-banner | 90 | silhouette | 4101 → 4638 | 0 → 0 | 14 → 16 | 0 → 1 | PASS |

Stitches +7–17%, max cell +0 to +5 (highest 23, below the >30 block), skipped 0, blocked 0.
Pass-2 vs pass-1 holes (color, 60mm, all rings): 0 exact coincidences in 1611 pass-2 needles;
after rounding to 0.1mm units, 3 pass-2 needles land on a pass-1 needle of a *different part*
of the same ring where the ring folds back near itself (closest 0.012mm); no 0-length stitch.

The earlier corpus note: The local analysis `/tmp/myembro-overlap/REPORT.md` reports 45 running-outline
candidates and 7 satin candidates among 334 files; 72/74 detected outlines follow fill.
Personal corpus files are not distributed with this change.

All original fills are generated first. The first candidate includes **all** outlines, with
separate temporary outline-owner tags. A complete validation cycle keeps the original raw,
centered-placement, guarded PES writer and encoded SewoutCheck checks. If that candidate
passes, it is accepted immediately. Otherwise blocking Highlight command indices identify
optional outline owners. Density Highlight indices point at the first needle, which may be
a fill needle: every owned outline needle inside the reported cell is therefore resolved.
All implicated outlines are removed together, and the complete candidate is checked again.
If a blocker cannot be attributed to any active outline, the original no-outline fill plan
is retained. Selection does not relax any threshold or alter the fill generator defaults. Every accepted outlined plan
has passed the complete original checks, not only a local or partial check.

Temporary validation ownership never changes the returned region tags, locks, sewing order
or source stitches. PEC indices can differ after command normalization, so PEC findings are
mapped by their reported cell/destinations in the top-left coordinate frame, never by
mistaking an encoded command index for a source index. A region's `outlineSkipped`,
`Result.skippedOutlineCount` and `myembro.skipped_fill_outlines` record omissions;
`myembro.fill_outline_checks` records full-candidate validation cycles. Existing mandatory
export checks still apply when falling back to the original no-outline result.

`shape` is deliberately the original, unexpanded polygon. A separate `expand_mm` change
must pass expanded geometry only to the fill generator, retaining this original geometry
for the outline and editable source paths.

## Reproduction

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
export ANDROID_HOME=/home/gosh2/Android/Sdk
./gradlew :engine:test
./gradlew :engine:koreanTtfSweepClasspath -PkoreanTtfSweepClasspath=/tmp/myembro-outline-classpath.txt
java -Djava.awt.headless=true -Xmx2g -cp "$(cat /tmp/myembro-outline-classpath.txt)" \
  io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorFillOutlineProbe \
  /tmp/myembro-outline-out engine/src/test/resources/image-to-design/face.png
/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-outline-out/repository.pes \
  --hoop-bounds-mm 0 0 100 100
```

The probe optionally accepts additional local image paths. It downsizes to 192px wide,
uses 4 colors and a 60mm design width for both baseline and outlined output. It writes
`results.tsv`, guarded PES files and `overview.png` only to the requested directory.
Private input and generated private PES/PNG must stay outside the repository.

Historical measurement, 2026-10-27, initial `a80afaa` implementation (no threshold changes):

| Input | Baseline stitches | With outline | Increase | Omitted outlines | Excluded fill regions | Kotlin sewout / PEC viewport | Python quality gate |
|---|---:|---:|---:|---:|---:|---|---|
| Repository `face.png`, 60mm | 3574 | 3744 | 4.76% | 1 | 0 | PASS / valid | PASS |
| Local private proxy, 60mm | 6732 | 8465 | 25.74% | 10 | 0 | PASS / valid | PASS |

The Python gate's short-stitch and tiny-stitch ratios for the local proxy are reference
metrics only (0.074294 and 0.063850); it reports `PASS 2/2 cases passed`.
These checks establish encoded safety-policy compliance, not a physical sew-out result.

## Batched-validation performance

JDK 17, cold JVM per case, app detail=1 (192px), four colors. One sequential run per cell;
these are JVM generation times excluding tracing/final export, not device timing estimates.
Historical pipeline/helper sources were read from git and compiled under temporary object
names; no other engine implementation/dependency changed between columns.

| Input / width | Regions | `51f0441` seconds / skipped | `a80afaa` seconds / skipped | Batched seconds / skipped / candidate checks |
|---|---:|---:|---:|---:|
| Synthetic 36 / 97mm | 36 | 4.073 / 0 | 10.374 / 0 | 4.529 / 0 / 1 |
| Synthetic large / 98.7mm | 100 | 8.289 / 0 | 31.409 / 0 | 8.962 / 0 / 1 |
| Local proxy / 99.8mm | 75 | 8.388 / 0 | 19.994 / 14 | 9.662 / 18 / 5 |

The two synthetic PES outputs are byte-identical to `a80afaa`. The local proxy now omits
18 outlines rather than 14 because all outline owners implicated by a blocking cell are
removed together. Original fills are retained. All three final PES files pass Kotlin
SewoutCheck/PecViewportCheck and the external Python gate (`PASS 3/3 cases passed`).
The gate's short/tiny stitch ratio reference flags remain informational, unchanged.

The optional JVM harness is `ColorFillOutlineTiming BACKEND_SUFFIX OUTPUT_DIRECTORY WIDTH_MM
INPUT_PNG` (empty suffix = current). Local historical-source extraction, commands, TSV rows,
PES and the full table are under `/tmp/myembro-outline-perf/`; private input/output is not
committed. Tests retain the original port/encoded-output regressions and add assertions for
one-cycle success, selective density-owner omission, and no-outline baseline fallback.


## Approved one-pass policy and silhouette integration (2026-10-28)

The initial `24bc62b` replay (superseded for silhouette ordering below) of 90 color and 90 silhouette inputs per revision used
`46505dc` as the baseline. Each set covers ten images at 60/85/99.9mm and three
detail levels. The same P7 four-phase 0.5mm-cell measurement is applied to actual
PES coordinates. Two color inputs exceed the existing contour-complexity limit
in both revisions, so color percentages use 88 final outputs, not 90 attempts.

| Output after change | Measured | <=15 | 16–20 | 21–25 | 26–30 | >=31 | Maximum |
|---|---:|---:|---:|---:|---:|---:|---:|
| Color, one outline pass | 88 | 73 (83.0%) | 15 (17.0%) | 0 | 0 | 0 | 19 |
| Silhouette, one outline pass | 90 | 90 (100%) | 0 | 0 | 0 | 0 | 11 |

Color peaks >=16 fell from 58/88 (65.9%, maximum 29) to 15/88 (17.0%, maximum 19).
Silhouette had no peaks >=16 before or after; its maximum changed from 12 to 11.
All 178 produced designs pass the normal writer and encoded safety checks.
Silhouette JVM generation p50/p95 changed from 0.308/0.938s to 0.369/0.930s;
these are one sequential local replay per version, not device timings.
Generation timing excludes trace and the harness's additional export/checks.

Raw metrics, the fixed matrix and reproduction commands are in
`/tmp/myembro-p7-freq/one-pass/report.md`; private inputs/results remain local.
External Python gate samples: 6/6 accepted (five PASS, one density WARNING).
The explicit gate bounds above describe a 100mm hoop with an **exclusive** upper
bound; native coordinate 999 (99.9mm) remains allowed, 1000 remains forbidden.

The explicit-zero color PES goldens are rebuilt from baseline `46505dc` with only
`bean_stitch_repeats=[0]` overlaid. This intentionally supersedes the three-pass
byte hashes; zero expansion still bypasses buffer and clipping. OUTLINE-mode
legacy byte parity remains asserted separately. The silhouette tests verify
whole-region fill/outline order, same-color exterior/hole rings and final P1–P8
invariants; existing selection tests retain only the safe optional outlines.


Validation command for this update:

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ANDROID_HOME=/home/gosh2/Android/Sdk \
  ./gradlew --offline --max-workers=2 --continue :engine:test :app:testDebugUnitTest lint
```

Result: `BUILD SUCCESSFUL in 3m 7s`; engine 1009 tests and app 456 tests,
zero failures/errors/skips. Lint completed with zero errors (145 warnings and
one informational issue). Log: `/tmp/myembro-p7-freq/one-pass/tests-lint.log`.


## Silhouette jump-distance correction (2026-10-28)

Measured 90 matched silhouette cases with the independent `gate.decode` reader.
Jump count includes the first JUMP; distance sums only jumps with a known previous
command position, excluding the implicit origin-to-first-landing movement.

| Variant | Jumps | Jump distance mm | Needles | Peak | Omitted regions | Trims |
|---|---:|---:|---:|---:|---:|---:|
| A: 24bc62b per-polygon/minX | 658 | 16387.254 | 496466 | 11 | 80 | 0 |
| B: per-polygon/JTS order | 658 | 16387.254 | 496466 | 11 | 80 | 0 |
| C: whole-region fill, JTS outlines | 799 | 16248.893 | 497051 | 15 | 80 | 0 |
| D: 46505dc fill only | 400 | 7460.580 | 465981 | 12 | 0 | 0 |
| E: whole-region fill, existing PhotoOutline.route | 796 | 14271.611 | 497030 | 15 | 80 | 0 |

E minimizes absolute aggregate jump-distance difference from D among A/B/C/E and
is the selected implementation. It reduces A's distance by 12.91%, but remains
91.29% above D; this does not claim baseline-level travel or eliminate manual
jump-thread cleanup. The explicit minX sort is removed. DefaultTatami is called
once on the entire region, retaining its existing fill routing/start propagation.
Only the existing JGraphT-backed PhotoOutline.route orders optional exterior-ring
anchors; interior rings stay with their original polygon owner. No new tour,
stitching or locking algorithm is introduced. `select` retains multiple outline
owners at the same fill boundary and removes only the implicated optional owners.

A was regenerated to collect omitted-region metadata; all 90 PES hashes match the
original A files. The earlier harness did not record silhouette omissions.
Color comparison on the same 88 produced outputs (two complexity rejections):
3-pass/1-pass both have 1747 JUMPs, 48073.308mm jump travel and 1659 encoded TRIMs.
Needles decrease 762642→658230, peak 29→19. Color output geometry/order is unchanged
by the silhouette routing correction.

All generation/selection/intermediate V6 export paths receive the caller's hoop,
including PhotoViewModel and AttachmentStore. The optional-outline checks use the
same selected-frame profile as PesOutputSafety, rather than a hidden SE400 limit.
AttachmentStore reuses the selected-frame writer report. Real machine-profile
checks remain in the app's final validation; P1–P8 limits are unchanged. Synthetic
regressions cover >100mm outlines and photo attachments in a 200×200mm frame,
whole-region fill parity, existing route order, holes, and selective omission when
several owners share one insertion point.

Local matrix, exact sources, per-case decoded metrics and reproduction commands:
`/tmp/myembro-p7-freq/jump-variants/report.md`. Private assets/PES stay in `/tmp`.


Correction validation:

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ANDROID_HOME=/home/gosh2/Android/Sdk \
  ./gradlew --offline --max-workers=2 --continue :engine:test :app:testDebugUnitTest lint :engine:jar
```

`BUILD SUCCESSFUL in 3m 30s`; engine 1012 and app 456 tests, zero failures/errors/skips.
The final production engine replayed all 180 cases: silhouette E 90/90 and color
one-pass 88/88 PES are byte-identical to their measured targets. The same two color
complexity rejections remain. Local proof: `jump-variants/final-verification.log`.


## Final-coordinate outline validation (2026-10-28)

The preceding E measurements were before the photo-fit selection correction.
Three 99.9mm local proxy cases had raw width 1000 native units before fitting.
The selector checked those raw coordinates and could not attribute the hoop
blocker to an optional owner, dropping all 23/28/29 outlines. Final output already
fit the same coordinates to width 999, so the omission was a pre-fit false block.

The existing photoMode fit/centering formula is now shared by final output and
silhouette candidate preparation (`fitPhotoPattern`). Candidate groups use the
same GeneratedPatternProcessor defaults and V6 PES writer as final generation.
Only the prepared actual-PES report decides silhouette selection; pre-fit raw
and recentered checks are not applied to that prepared path. Owner indices are
preserved by the coordinate map. No tolerance, extra margin, routing rule or
P1–P8 threshold changes. Explicit boundary tests retain outlines at 998/999 units
and reject 1000 units; the unscaled color selector has matching boundary tests.

| Silhouette, matched 90 cases | Jump count | Jump distance mm | Peak | Omitted regions | Trims |
|---|---:|---:|---:|---:|---:|
| Before (afb9b7f) | 796 | 14271.611 | 15 | 80 | 0 |
| After final-coordinate validation | 891 | 16204.931 | 15 | 0 | 0 |

All 90 final silhouette PES pass the normal writer. Only the three affected proxy
outputs change; the other 87 are byte-identical. Restoring the previously omitted
outlines increases jump travel as shown; no additional path optimization was made.
Color has no post-selection photoMode fit, so its existing validation path remains.
The same 90 color attempts produce 88 byte-identical PES and two unchanged
complexity rejections, with zero omitted outlines before/after. Independent
`gate.decode` measurements and Kotlin four-phase cell peaks agree in every output.

`./gradlew --offline --max-workers=2 --continue :engine:test :app:testDebugUnitTest lint :engine:jar`
completed `BUILD SUCCESSFUL in 3m 13s`: engine 1014 and app 456 tests, zero failures,
errors or skips. Exact reproduction/metrics: `/tmp/myembro-p7-freq/fit-output/report.md`;
private inputs and outputs remain local only.
