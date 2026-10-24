# Background and antialiasing color cleanup (v0.2.5 candidate)

Color-only change. Used existing original synthetic face/robot PNGs and test-drawn faces only,
with no personal inputs.

## Causes and handling

- Partially transparent PNG pixels were composited over white before entering Wu, causing edge colors to create new thread colors. Fully transparent pixels are now excluded, and partial-alpha pixels are quantized using ARGB's straight RGB source colors. No new alpha threshold was added.
- For opaque inputs, exclude the **most frequent border-pixel color** from Wu preseparation (N+1 colors) as background. Do not decide from a single top-left pixel. After background exclusion, rerun original Wu N-color quantization so background does not consume the user's 2–6-color budget. Explicit transparent backgrounds take priority when present. No new brightness threshold was invented.
- Count standard 4-connected-component areas and merge small color fragments into the color of the largest adjacent retained region. Background can also be a merge target. Do not infer nonadjacent colors or newly fill transparent regions themselves. The existing Potrace area filter uses the same area criterion.
- The area threshold is `3.0mm²`, selected by the **30mm, 2 originals × 5 candidates = 10-case** sweep below. Smaller candidates left Khaki on the face. Assertions check the original drawing's Brother-thread-number set, not just color counts. Existing silhouette/outline area and detail were unchanged.

## Measurement table

After bilinear resampling to 192px, a 4×4px bright blemish (0.390625mm² at 30mm) was added
inside the broad body. The color budget is 4, while the original drawing has 3 colors. Existing
area candidates 0.25/0.5/1.0 were insufficient, so measurements extended to 2.0/3.0.

| Synthetic input | Minimum area mm² | Actual drawing colors | Required threads | Stitches | Engine gate |
|---|---:|---:|---:|---:|---|
| face | 0.25 | 3 | 4 | 1144 | PASS |
| face | 0.5 | 3 | 4 | 1144 | PASS |
| face | 1.0 | 3 | 4 | 1107 | PASS |
| face | 2.0 | 3 | 4 | 1107 | PASS |
| face | 3.0 | 3 | 3 | 1089 | PASS |
| robot | 0.25 | 3 | 4 | 846 | PASS |
| robot | 0.5 | 3 | 3 | 836 | PASS |
| robot | 1.0 | 3 | 3 | 836 | PASS |
| robot | 2.0 | 3 | 3 | 836 | PASS |
| robot | 3.0 | 3 | 3 | 836 | PASS |

At the selected 3.0mm², the face uses `23 Dark Gray / 49 Amber Red / 12 Orange`; the robot uses
`23 Dark Gray / 32 Sky Blue / 49 Amber Red`. **This criterion was verified for these two drawings/
sizes; it is not claimed to determine the intended color count of arbitrary drawings.** Real details
below 3mm² are also subject to simplification, so smaller or finer drawings are not guaranteed preservation.

The actual Android cropping/interpolation path was also checked (color count 3, displayed width about 30mm):

| Result | Required threads | Stitches |
|---|---|---:|
| Before fix | 23 Dark Gray / 12 Orange / **47 Beige** | 1,543 |
| After fix | 23 Dark Gray / 12 Orange / **49 Amber Red** | 1,502 |

[Updated result screenshot](result-ko.png), [updated preview](preview-ko.png). Used only PNGs
drawn directly by the standard Android instrumentation; neither MediaStore nor personal files were read.

## Reproduction and decisions

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
COLOR_CLEANUP_OUT=/tmp/myembro-port-out/editor/color-cleanup ./gradlew check :app:assembleDebug :engine:imageToDesignProbe
# 같은 코드의 실제 ART 경로는 ColorDrawingSmokeInstrumentation 실행.
/tmp/myembro-quality-gate/quality-gate check \
 /tmp/myembro-port-out/editor/color-cleanup/face-3.0.pes \
 /tmp/myembro-port-out/editor/color-cleanup/robot-3.0.pes \
 /tmp/myembro-port-out/editor/color-cleanup/android-face.pes \
 --style mixed --json /tmp/myembro-port-out/editor/color-cleanup/gate.json
```

```text
BUILD SUCCESSFUL in 51s
engine tests=730 failures=0; app debug/release tests=231 each, failures=0
COLOR_UI_SMOKE_PASS colors=3 ink_width_mm=30.2 stitches=1502
threads=[(23, Dark Gray), (12, Orange), (49, Amber Red)]
INSTRUMENTATION_CODE: 0
PASS 3/3 cases passed
```

Code-based decisions: `ColorCleanupSweep` checks the 10 gates and the minimum candidate
preserving 3 colors and original thread numbers. Separate regressions check a background with
a differently colored corner pixel, background not consuming the color budget, adjacent merging
of small regions, preservation of transparent holes, and preservation of partial-alpha source
colors. The Android smoke also asserts that catalog `47` is absent.

Raw table: `/tmp/myembro-port-out/editor/color-cleanup/sweep.tsv`; logs:
`/tmp/color-cleanup-final-check.log`, `/tmp/color-cleanup-android-smoke.log`,
`/tmp/color-cleanup-gate.log`. No personal designs/emoticons are bundled. This is for integrator
review; actual-sewing verification is separate.
