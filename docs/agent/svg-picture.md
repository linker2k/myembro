# SVG picture input and PNG retry

This extends the existing `agent/ai-picture` flow. It adds no SVG renderer,
trace algorithm, stitch settings, automatic sewing exception, or external service.

## Input boundary and measured limits

`ACTION_SEND image/svg+xml` and the photo screen's `OpenDocument` picker accept SVG.
Generic shared content named `.svg` is also recognized through its display name.
`SvgPictureInput` validates the complete input **before** `VectorArt.parseSvg`.

Limits are the exact measured maxima of the **30 locally bundled** entries in
`app/src/main/assets/emoji/bundled`, from the existing Fluent catalog pinned to
`1ffb34c752ecf5d402f04cfb4b392c77f57c54bc`. This is a conservative local corpus,
not a claim that all downloadable catalog entries fit these limits. Larger or
more complex AI output falls back to PNG rather than raising limits speculatively.

| Resource | Maximum (inclusive) | Source of maximum |
|---|---:|---|
| UTF-8 file bytes | 4788 | `lady-beetle.svg` |
| XML elements, including root | 14 | `lady-beetle.svg` |
| Flattened path vertices across shapes | 202 | `teddy-bear.svg`, existing `SvgPath.flatten` default tolerance/transform |

`SvgCorpusMeasureTest` prints measurements per file, while `SvgPictureInputTest`
asserts the maxima and all 30 accepted entries. The input stream reads at most
4789 bytes, detecting overflow without buffering the rest. Per-path point budgets
are enforced during the existing flattening operation; the original global
200000-emission guard and default behavior remain unchanged for existing callers.
The aggregate cap counts the same stored vertices as the measured corpus.

XML must be UTF-8 with no NUL or DOCTYPE/ENTITY declaration. Declarations are
unconditionally rejected before either parser; this does not depend on Android's
support for JAXP features. External-entity/DTD features are disabled where supported,
entity expansion is disabled, and a throwing entity resolver adds another layer.
Only the existing flat shape/group subset is accepted. Script, foreignObject,
embedded images, use/text, unsupported namespaces/elements, href/xlink:href
(including internal references), event handlers, CSS url()/imports, gradients,
filters and masks are rejected. There is no external fetch or bitmap SVG preview.

## Conversion and sizing

After validation, SVG uses `VectorArt.parseSvg` → `VectorArt.render` **with existing
defaults** → existing PES writer → `SewoutCheck`, like the emoji path. It enters the
photo result screen directly, without bitmap decoding, quantization or tracing.
The initial 50mm width comes from `EmojiViewModel.WIDTH_MM`, clamped using existing
`pictureWidthRange`. Slider changes use the same range and 0.1mm rounding, then
regenerate from vectors. Failed parsing/limits/unsupported input or blocked sewing
shows: “This SVG cannot be used. Please send a PNG instead.” Blocked output cannot
be sent. Existing editor and checked send actions are reused for successful output.

## Request preservation and fallback

PNG remains the initial output choice; SVG is an explicit alternative. Request,
optional reference photo, color count and selected format live in the Activity's
in-memory `AiPictureDraft`. They are not saved to disk. Consent is deliberately
not in that draft: every send still opens a fresh photo/text-only disclosure.

“Request PNG again” is available for SVG input (including errors) and for a returned
AI picture the user dislikes. It preserves the description/reference/colors and
changes only the format to PNG; it opens the normal request dialog and never sends
automatically. A replacement photo from the dialog also stays in the draft for retry.

Prompt hardening based on `docs/claude-png-spike.md` in the manager worktree forbids
unrequested lettering, decoration, stars, frames and borders. Quoted lettering is
repeated as an exact literal (for example `Lettering must be exactly: "코코"`),
with no translation/additional letters. This guides the external AI; the user still
previews its semantic output. Stitch/sewing checks cannot verify lettering content.

After an AI request opens its chooser, the **next shared image** is treated as its
return. Its requested N is consumed and applied via the existing color-count and
color-mode setters before the normal photo load. The user can still change N.
Starting the normal photo flow clears that pending marker; normal `begin()` retains
its 4-color default. No bilinear sampling, decoder, Wu or conversion default changed.
Android shares do not carry a reliable generation-request ID: chooser cancellation
cannot establish whether an external AI generated anything. The pending marker is
session-local and is consumed once; entering the normal photo flow cancels it.

## Verification

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*SvgPictureInputTest' --tests '*SvgCorpusMeasureTest' --tests '*SvgPathTest'
ANDROID_HOME=/home/gosh2/Android/Sdk JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:testDebugUnitTest --tests '*AiPicturePromptTest' :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.photo.SvgPictureInstrumentation :app:lintDebug
adb -s emulator-5558 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5558 install -r -d -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5558 shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.photo.SvgPictureInstrumentation
ANDROID_HOME=/home/gosh2/Android/Sdk JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check --console=plain
```

Whole repository check: `BUILD SUCCESSFUL in 3m 2s`.
Targeted build/unit/lint: `BUILD SUCCESSFUL in 1m 18s`.
Engine tests cover every byte/element/point cap at n−1/n/n+1, XXE/internal/external
DTD rejection, external href/url, scripts/foreignObject/image/text/events, all
30 bundled entries and a 2-color SVG producing PES sewing PASS with the original
VectorArt-default stitches. App tests cover PNG/SVG format and literal lettering,
unchanged request/colors on PNG retry and the one-shot AI-return marker.

Device output (5558 only):

```text
INSTRUMENTATION_RESULT: result=SVG_DIRECT PASS: Android parser -> VectorArt defaults -> 2 colors -> PES sewout PASS, no bitmap/trace
SVG_SIZE PASS: existing picture range + 0.1mm rounding; size=30mm, sewout PASS
AI_COLOR_2 PASS: shared PNG automatically sets N=2; actual threads=2; existing bilinear unchanged; stitches=1665; sewout PASS
AI_COLOR_3 PASS: shared PNG automatically sets N=3; actual threads=3; existing bilinear unchanged; stitches=1840; sewout PASS
NORMAL_PHOTO PASS: begin() retains 4-color default
SVG_RETRY PASS: unsupported element -> localized PNG guidance; request/reference/colors preserved, only format becomes PNG; no auto share
SVG_PICTURE_PASS
INSTRUMENTATION_CODE: 0
```

All images are generated in test code or are repository synthetic inputs; no
personal photo is used. The 2-color smile and 3-color heart have 320×320px,
showing that 2000px is not an import restriction. Both are sized to 30mm using the
existing photo slider. The SVG's vector input remains separate from raster inputs.

Logs: `/tmp/myembro-svg-check.log`, `/tmp/myembro-svg-app-build.log`,
`/tmp/myembro-svg-app-smoke.log`. Synthetic screenshots are in
`/tmp/myembro-svg-shots/` (`01-svg-direct-pass.png`, `02-2-color-pass.png`,
`03-3-color-pass.png`, `04-unsupported-png-retry.png`). No external AI app is sent data.
A real-machine sew-out and external AI output fidelity are not asserted by these tests.
