# AI picture hand-off

Base: `agent/relay-edit-int` at `ae992d2`. This feature shares a picture-generation
request through Android's normal chooser; it does not call an AI API or a relay tool.

## Flow

- Home's AI icon, the editor's project menu and the photo screen open the same
  `AiPictureDialog`. One description field accepts text or the existing free-form
  `RecognizerIntent` voice input. A reference photo is optional; the system image
  picker can add one from any entry point. No microphone permission or custom audio
  recorder is added. The microphone is disabled when no recognizer is installed.
- Foreground color count is selectable from 2–6, default **2**. The pure
  `AiPicturePrompt.build` takes the request and photo flag, hoop, target size, color
  count and optional active owned-thread palette. It preserves the request's text
  verbatim and instructs exact lettering in bold Gothic/sans-serif.
- The prompt requests **square 2000×2000 px PNG**, **exactly N solid foreground
  colors (+ white background)**, bold outlines, no antialiasing, gradients, shadows
  or texture. Hoop and target dimensions are given in mm; the hoop's aspect ratio
  describes the artwork composition inside the square canvas. Active owned-thread
  hex colors constrain the foreground palette. Minimum line thickness reuses
  `StitchTypeRule.SATIN_MIN_MM` (1.0mm, Ink/Stitch default), expressed also as a
  percentage of target width. This is an image-generation guide, not a sewing guarantee.
- Every share opens a fresh disclosure. It says the current photo's decoded PNG
  copy and request go to the external AI app the user chooses. **Send** includes the
  photo; **Request only, without photo** shares text only. Dismissing sends nothing.
  No remember option, stored consent, automatic repeat attachment or background
  upload exists. The no-photo flow uses the same request/dialog and text-only path.
- Only an approved photo share creates a private cache PNG under `ai_picture_shares`.
  It is a bounded decoded copy from the existing photo decoder, without source URI,
  original bytes or EXIF metadata. Its FileProvider grant is read-only and specific
  to the exported copy. The text-only path creates no image file, stream, ClipData
  or URI permission. Cache copies are temporary Android cache, excluded from backup.
- `ACTION_SEND image/*` routes PNG/JPEG/WebP directly into the existing
  `PhotoViewModel.begin/configure/load` path rather than the embroidery-file importer.
  The new intent adds the photo screen to the existing stack without replacing the
  editor project. Cold launch and warm `onNewIntent` are exercised.
- 2000px is a generation request, **not** an import requirement. Imported images
  keep the existing bounded EXIF-aware decoder, `PhotoPreparationPolicy`, Wu/tracing,
  stitch generation and `SewoutCheck`. Zoom/crop and embroidery size remain the
  photo screen's existing controls. No stitch generator or sewing rule changed.

## Automated verification

```sh
ANDROID_HOME=/home/gosh2/Android/Sdk JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:testDebugUnitTest --tests '*AiPicturePromptTest' :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.photo.AiPictureInstrumentation :app:lintDebug --console=plain
adb -s emulator-5558 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5558 install -r -d -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5558 shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.photo.AiPictureInstrumentation
```

Build/unit/lint: `BUILD SUCCESSFUL in 44s`; 5 unit tests, 0 failures/errors.
Tests cover photo/no-photo requests with otherwise identical rules, exact Korean
request/lettering, palette hexes, aspect/physical dimensions, line-width ratio,
locale independence, the 99.9/100.0mm hoop boundary, and the default scenario
“2가지 색으로 스마일” containing exactly 2 colors and 2000×2000 PNG.

Device output (5558 only):

```text
INSTRUMENTATION_RESULT: result=INCOMING PNG PASS: ACTION_SEND -> existing photo.load -> choose/crop screen
INCOMING image/jpeg PASS: warm activity photo screen
INCOMING image/webp PASS: warm activity photo screen
CONSENT TRANSPORT PASS: text-only has no stream/grant/file; approved PNG decodes without original URI/EXIF
CONSENT UI PASS: choosing text-only never remembered; second share asks again
CHOOSER PASS: text and photo share sheets visible; no external app selected
SMILE PASS: generated 320x320 two-color PNG -> shared photo screen -> existing color pipeline, colors=2, stitches=1665, sewout PASS
AI_PICTURE_PASS
INSTRUMENTATION_CODE: 0
```

The smile is generated in test code with a 320×320 opaque white bitmap, a solid
colored circle, black eyes and mouth, antialiasing disabled. It is not bundled in
the app. The repository robot is the only other input. There is no personal image.
The result is 30×30mm, 2 thread colors, 1,665 stitches, sewout PASS (about 6 minutes
in the app's existing estimate). No external AI app was selected or sent content.

Logs: `/tmp/myembro-ai-picture-final-build.log`, `/tmp/myembro-ai-picture-smoke.log`.
Synthetic screenshots: `/tmp/myembro-ai-picture-shots/01-shared-image.png`,
`02-photo-consent.png`, `03-text-share-sheet.png`, `04-photo-share-sheet.png`,
`05-two-color-smile-pass.png`. Screenshots are local artifacts, not committed.
Only development emulator-5558 was installed; `-d` preserves its existing data when
its installed development version code is newer than this requested base branch.

## SVG follow-up

PNG is still the default. The explicit SVG option, safe vector import, request-preserving
PNG retry, exact-lettering hardening and one-shot requested color-count hand-off are
implemented in [SVG picture input](svg-picture.md). Its current verification results
supersede the initial PNG-only scope above. Consent remains per send, without a remember option.
