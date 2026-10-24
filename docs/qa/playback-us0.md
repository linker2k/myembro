# US-0 simple preview smoke

Revised 2026-10-24. Based on `main` UI story `8b18511`.
This document replaces the speed/start/end-button smoke in existing `3a83f8d`.

## Current behavior

- One play/pause button and a slider. Removed playback speed/start/end/jump-display controls.
  Hoop shows saved project/selected machine default.
- Automatically choose preview speed by total needle count. Long-design UI playback target is approximately 20 seconds;
  small designs use at least 1 stitch per second. This is a **UI animation budget**, not machine SPM/sewing quality rules
  or generation parameters. Sewing-time estimates use the existing check report unchanged.
- Keep slider color-change/trim ticks. Seeking pauses; playing from the end returns to the beginning.
  Stop on ON_PAUSE when leaving for home/another screen.
- Canvas top badge has two states: pass/block. Complete analysis + no blockers means pass; blockers/incomplete/
  validation failure mean block. While checking, show progress instead of a badge.
- Blocks also show a one-line reason. Tapping the badge opens the existing detailed check sheet.
  Keep red circles/tap-to-zoom for located blockers. Preserve warnings/unconfirmed information in the detailed sheet.
- Do not modify imported PES. USB blocking confirmation runs once through the existing path.

## Reproduction

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

1. Open a design with color changes/trims; verify only one play button and slider.
2. Verify play/pause, pause after slider seeking, and restart from the beginning when playing at the end.
3. For blocked PES, verify top block/one-line reason, badge tap → check list, USB transfer → 1 confirmation → cancel.
4. For PES without blockers, verify pass and direct continuation into the USB callback.
5. Verify display/interaction in ko/en, portrait/landscape at 600dp or larger.

## Execution results

`io.github.linker2k.myembro.playbackpreview`, separate from the real app, emulator-5554.
Temporary inputs were tested as **imported PES** so original-file preservation could be verified.
Blocked input 280 stitches, passing input 260 stitches (both 2 colors); they differ only in deliberate repeated punctures.

| Check | Result |
|---|---|
| Automatic speed | 14/13 stitches per second respectively. Needle counts match 1-second ticks; full playback completion passed |
| Single control | No speed/start/end controls in ko/en; one button/slider displayed |
| Actual interaction | Play/stop/pause after seeking/restart after completion passed |
| Block display/transfer | Korean portrait block/one-line reason/check list/USB confirmation and cancel passed |
| Pass display/transfer | English landscape pass/nonblocking USB callback path passed |
| Original invariance | Original PES files and displayed-pattern PES v6 output bytes match before/after interaction |
| Final build/regressions | App tests 172 (0 failures), normal applicationId `:app:assembleDebug` passed |

Slider smoke starts swipes inside the screen, avoiding system back-gesture areas.
Corrected the first attempt's test input, which left the Activity by swiping from the screen edge, then reverified.

Temporary materials:

- `/tmp/myembro-simple-playback-ui.py`, `/tmp/myembro-simple-playback-smoke.json`
- `/tmp/myembro-simple-pass-check.py`
- `/tmp/myembro-simple-blocked-ko.png`, `/tmp/myembro-simple-pass-en-wide.png`
- `/tmp/myembro-playback-src/PlaybackPreviewActivity.kt`, `/tmp/myembro-playback-preview.init.gradle`
- logcat `PlaybackQA`: `AUTO_TIMING_AND_UNCHANGED_PES_PASS`, subsequent state logs.

Photo engine/stitch generation/PES writer unchanged. This smoke does not indicate new-algorithm quality
or actual sewout passing. Photo S5 research/plans are recorded separately in `docs/image-pipeline.md`.
