# Same-color running outline after color fill

Base: main `51f0441`. Scope: `ColorDrawingPipeline`, not vector/emoji or texture defaults.

Each retained TATAMI region is followed immediately by its original polygon exterior and
interior rings. The existing `Stroke` port generates the stitches; there is no new boundary,
routing, running-stitch or lock algorithm. Its Ink/Stitch v3.3.0 source attribution remains
in `Stroke.kt` and `StrokeParameters.kt` (upstream commit
`b0edd96311ece82ee48816dc93466274b12c2a9c`). Parameters are `repeats=1`,
`bean_stitch_repeats=[1]`, `running_stitch_length_mm=[1.655]`; other defaults are unchanged.
The same color and region ownership tags are retained, without an added color block.
The original region-to-region trim is transferred to the end of its outline.

The 1.655mm length is the running-outline corpus median, approved by the user on
2026-10-27. The local analysis `/tmp/myembro-overlap/REPORT.md` reports 45 running-outline
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
is retained. No threshold or generator default is changed. Every accepted outlined plan
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
  --hoop-bounds-mm 0 0 99.9 99.9
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
