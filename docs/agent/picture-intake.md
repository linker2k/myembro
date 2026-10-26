# Content-based picture intake

Base: `agent/ai-picture-menu` at `105c08f`.

## Cause and change

The photo OpenDocument filter previously allowed only `image/*` and `image/svg+xml`.
A provider may label a downloaded SVG `text/xml`, `application/xml`, `text/plain`
or `application/octet-stream`, making that file unselectable. MainActivity's
VIEW and SEND_MULTIPLE paths also went straight to the design importer; SEND
relied on an image MIME or SVG metadata/extension. A generic MIME/opaque filename
could therefore reach the unsupported-extension embroidery-file error.

The picker now includes all 6 requested MIME filters. Its selected file, SEND,
SEND_MULTIPLE and VIEW use `PictureIntake`/`inspectPicture`, independent of reported
MIME. At most the existing SVG byte limit plus 1 is read on IO (4789 bytes), without
XML parsing, writes or downloads in the routing layer:

- PNG: full 8-byte signature; JPEG: FF D8 FF; WebP: RIFF plus WEBP at offset 8.
- SVG: an `<svg` opening tag in the bounded prefix (also after XML declarations,
  comments or BOM), or `.svg` display-name candidate. This is routing only; the
  existing strict `SvgPictureInput` still validates the entire SVG before rendering.
- PES/PEC/DST/ZIP signatures go to the existing importer, even if mislabeled `.svg`.
  Recognized image bytes take precedence over a misleading design extension.
- In the photo picker, non-picture content shows “This is not a picture file.”
  Other external incoming files retain the existing importer's normal error handling.
- Plain chatbot text without a stream remains the Ask AI reply path. Text/XML file
  streams and ClipData URIs are classified before that path.

The existing DesignFileImporter, batch importer, import decisions and their tests
are unchanged. Original URIs and metadata are passed to that importer. Mixed
shares import non-pictures first through the original completion path, then open
the picture. Multiple pictures show an explicit choice, since the photo screen
holds one picture; they are not silently sent to the embroidery importer.

## MIME × content routing matrix

The following table is extracted from `PictureIntakeTest` output and asserted
identical for SEND, SEND_MULTIPLE and VIEW: 6 MIME types × 9 content cases × 3
entry actions = **162 rows**. PHOTO means the photo/vector screen; IMPORT means
the existing importer (including its error handling for unrelated text).

| MIME | SVG | PNG | JPEG | WebP | PES | PEC | DST | ZIP | Text |
|---|---|---|---|---|---|---|---|---|---|
| image/svg+xml | PHOTO | PHOTO | PHOTO | PHOTO | IMPORT | IMPORT | IMPORT | IMPORT | IMPORT |
| image/png | PHOTO | PHOTO | PHOTO | PHOTO | IMPORT | IMPORT | IMPORT | IMPORT | IMPORT |
| text/xml | PHOTO | PHOTO | PHOTO | PHOTO | IMPORT | IMPORT | IMPORT | IMPORT | IMPORT |
| application/xml | PHOTO | PHOTO | PHOTO | PHOTO | IMPORT | IMPORT | IMPORT | IMPORT | IMPORT |
| text/plain | PHOTO | PHOTO | PHOTO | PHOTO | IMPORT | IMPORT | IMPORT | IMPORT | IMPORT |
| application/octet-stream | PHOTO | PHOTO | PHOTO | PHOTO | IMPORT | IMPORT | IMPORT | IMPORT | IMPORT |

Extra assertions cover truncated magic, non-WebP RIFF, BOM/XML SVG, misleading
extensions, the exact picker MIME list and retention of every mixed-batch item.

## Commands and output

```sh
ANDROID_HOME=/home/gosh2/Android/Sdk JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.photo.PictureIntakeInstrumentation --console=plain
adb -s emulator-5558 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5558 install -r -d -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5558 shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.photo.PictureIntakeInstrumentation
```

Whole check/build: `BUILD SUCCESSFUL in 3m 54s` (121 actionable tasks).
The final test-APK-only rebuild after correcting the MediaStore fixture was
`BUILD SUCCESSFUL in 1s`. Existing tests, unmodified: DesignFileImporterTest 10,
BatchDesignImporterTest 16, ImportDecisionsTest 2, LaunchIntentPolicyTest 1;
all 0 failures/errors. New PictureIntakeTest: 4 tests, 0 failures/errors.

Actual 5558 output:

```text
INSTRUMENTATION_RESULT: result=ROUTE PASS: SEND text/xml + SVG -> photo vector result, sewout PASS
ROUTE PASS: android.intent.action.SEND application/octet-stream + SVG -> photo vector result, sewout PASS
ROUTE PASS: android.intent.action.SEND_MULTIPLE text/plain + SVG -> photo vector result, sewout PASS
ROUTE PASS: android.intent.action.VIEW application/xml + SVG -> photo vector result, sewout PASS
ROUTE PASS: SEND application/octet-stream + PNG bytes (.bin) -> photo choose/crop
SAF PASS: Downloads/myembro-intake-1793010540986.svg (actual MIME image/svg+xml) selected using real OpenDocument picker -> vector PES sewout PASS
SAF PASS: Downloads/myembro-intake-1793010546695.svg.xml (actual MIME text/xml) selected using real OpenDocument picker -> vector PES sewout PASS
PICKER_BOUNDARY PASS: nonpicture content -> localized not-a-picture message
PES_REGRESSION PASS: real PES bytes + application/octet-stream -> unchanged importer/viewer, never photo
PICTURE_INTAKE_PASS
INSTRUMENTATION_CODE: 0
```

The SAF test creates its own flat 2-color SVG in MediaStore.Downloads and selects
it through the real app OpenDocument launcher. A real `.svg` is tested, and a
second SVG body with actual `text/xml` is selected as well. Android MediaStore
itself appends `.xml` to that second filename to match MIME; this is recorded
rather than pretending it retained `.svg`. The body is recognized without relying
on the extension. DocumentsUI may present these Downloads files under Recent.
Both files are removed after success. The actual PES check imports a uniquely
named copy of the repository flower fixture, then removes that test copy.
No personal input or external network service is involved.

Logs: `/tmp/myembro-picture-intake-check.log`, `/tmp/myembro-picture-intake-smoke.log`.
Local screenshots: `/tmp/myembro-picture-intake-shots/01-downloads-svg-selectable.png`,
`02-saf-svg-pass.png`, `03-downloads-xml-selectable.png`, `04-saf-xml-pass.png`.
SVG byte/element/point/security limits, bitmap sampling, stitch generation and
sewing gates are unchanged. Only development emulator-5558 was installed.
