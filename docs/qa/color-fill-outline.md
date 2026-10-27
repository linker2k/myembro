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

All original fills are generated first. Optional outlines are inserted one region at a time
and checked against **all** fills, including later ones, plus previously accepted outlines.
This prevents an optional early outline from excluding a later fill. Existing SewoutCheck
and the guarded PES writer enforce P4/P5/P7/P8 and the remaining output rules without
relaxing any thresholds. The existing centered editor/agent placement is checked as well:
integer translation can change density grid buckets. Failed candidates leave the complete
fill plan unchanged and omit only that region's outline. A region's `outlineSkipped`,
`Result.skippedOutlineCount` and `myembro.skipped_fill_outlines` metadata record omissions.
All-outline failures still leave the ordinary mandatory output checks in place.

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

Measured 2026-10-27, final implementation (no threshold changes):

| Input | Baseline stitches | With outline | Increase | Omitted outlines | Excluded fill regions | Kotlin sewout / PEC viewport | Python quality gate |
|---|---:|---:|---:|---:|---:|---|---|
| Repository `face.png`, 60mm | 3574 | 3744 | 4.76% | 1 | 0 | PASS / valid | PASS |
| Local private proxy, 60mm | 6732 | 8465 | 25.74% | 10 | 0 | PASS / valid | PASS |

The Python gate's short-stitch and tiny-stitch ratios for the local proxy are reference
metrics only (0.074294 and 0.063850); it reports `PASS 2/2 cases passed`.
These checks establish encoded safety-policy compliance, not a physical sew-out result.
