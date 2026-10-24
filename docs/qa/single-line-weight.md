# Normal/bold single-line lettering (v0.2.8)

Integrator decision 2026-10-24: new single-line text defaults to **Normal**; Bold uses Ink/Stitch
bean for 3 total passes. Editing a previously saved BEAN object opens it as Bold, and completing
the edit saves the choice in existing `TextObject.stitchStyle`. No new serialization fields.

## Implementation

- Added only two choices, **Normal / Bold**, below the lettering font card. Show them only for the automatic RUNNING path of single-line SVG/Hershey, not TTF outlines/Hangul satin.
- Hide both choices for Ink/Stitch digitized fonts because original elements own their repeats. Also hide them for single-line fonts with bean in their id, such as `*_bean`. Do not apply BEAN again to those fonts.
- The main preview, quick-choice cards, adding designs, and editing existing text share the same selection. A weight-only change counts as an edit and triggers the existing discard confirmation on Back. Normal runs the original path once. Original-font retracing is preserved; no separate branch-reordering algorithm was created.
- Unified BEAN expansion through worker's single `inkstitch.stitches.RunningStitch.beanStitch`. Split body runs at movement/trim/color change/end before calling it, and apply shared S1 postprocessing once. All 6 existing bean-coordinate/stitch-count/color/curve/save-restore regression tests still pass.

## Upstream repeats and overlapping sections

Source: Ink/Stitch v3.3.0 `lib/stitches/running_stitch.py:bean_stitch` and
`lib/elements/stroke.py:do_bean_repeats`, commit `b0edd96311ece82ee48816dc93466274b12c2a9c`
(GPL-3.0-or-later).

`repeats=[1]` adds 1 round trip per segment: **forward→back→forward, 3 total passes**.
`repeats=[3]` means 7 total passes and is not used in this UI. Distinguish "3-pass bold" from
the numeric upstream repeat parameter.

The original can skip repeats for specified tags through `tags_to_ignore`, but **has no option to
automatically detect already-traversed segments and reduce repeats**. Ordinary Stroke calls also
pass only `bean_stitch(stitches, self.bean_stitch_repeats)`. No new visit-detection/deduplication
rule was added. Thus an original traversing a section twice can yield **6 passes** in Bold.
This was confirmed for EMS Readability at 10mm in the [full calculation table](single-line-passes.md).
Original overlaps also remain in Normal.

## Verification and reproduction

On **emulator-5556** with public-catalog pinned SVGs `hershey-script-1`·`ems-readability` and
their respective licenses installed:

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest \
  -PmyembroTestRunner=io.github.linker2k.myembro.ui.lettering.SingleLineWeightInstrumentation
adb -s emulator-5556 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w \
  io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.lettering.SingleLineWeightInstrumentation
adb -s emulator-5556 exec-out run-as io.github.linker2k.myembro \
  tar -C files -cf - single-line-weight > /tmp/single-line-weight.tar
# 압축 해제한 폴더에 기존 quality-gate check --text --style running 실행
```

The actual path is `automaticLetteringState` → weight selection → `renderLettering` →
`TextObject` → PES. Text: Yeonwoo, Black 40wt, letter spacing 0mm (same as the current 4-input UI).
Hershey Script 1-stroke at 10/15mm, EMS Readability at 10mm × Normal/Bold = **6 cases**.
Each object matches down to stitch coordinates after save/restore.

Output `/tmp/myembro-port-out/letter/single-line-weight/`: `build.log`, `instrumentation.log`,
`gate.json`, `gate.txt`; 6 PES cases, `result.tsv`, `normal.png`, `bold.png`, and `discard.png`
under `single-line-weight/`. Existing gate **6/6 PASS**, with no gate-code/limit changes.
These are functional checks; final sewing judgment belongs to the user's actual sewout.

Results: `./gradlew check`·debug APK/instrumentation APK builds PASS, `SingleLineWeightTest`
3/3, existing `BeanStitchTest` 6/6. Actual chip clicks on 5556, discard confirmation on weight-only
changes, and serialized stitch equality PASS.

| Font | Height mm | Normal stitches | Bold stitches | Gate |
|---|---:|---:|---:|---|
| Hershey Script 1-stroke | 10 | 182 | 348 | Both PASS |
| Hershey Script 1-stroke | 15 | 205 | 417 | Both PASS |
| EMS Readability | 10 | 120 | 234 | Both PASS |

These are total needle counts including locks, so they are not exactly 3-fold. Body segments repeat
3 times; locks/movements are not expanded by bean.
