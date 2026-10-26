# Picture input: review first, then add a layer

File pickers (Home, library, editor and photo screen), VIEW/SEND/SEND_MULTIPLE,
and relay `import_picture` open the same photo input screen. The default is color
drawing. SVG retains the existing vector path and skips raster tracing. Picking or
receiving a picture does not add a layer or replace the current project. Only the
screen's explicit confirmation adds the generated object to the existing editor.
PhotoScreen's correction controls are unchanged; only its picker callback changed.

## Relay contract

| Argument | Meaning |
|---|---|
| `format` | `svg`, `png`, `jpeg`, `bmp`, `webp`, `gif`, `heif`, `avif` |
| `data` | SVG text; raster base64 (data-URL prefix allowed) |
| `colors` | optional initial color count, 2..6, default 4 |
| `title` | optional proposed layer name; existing trimming/client-name rules |

The strict app schema still requires all fields; the relay projection supplies the
existing optional defaults. The catalog and deployment golden remain generated from
the app's hello, with schema drift checked on both sides.

`import_picture` validates the signature/size and hands bytes to a process-memory
inbox. It does not prepare stitches, export PES, mutate the project, issue an undo
step or write an attachment file. Its response is:

```json
{"ok":true,"status":"awaiting_user_confirmation","added":false,"message":"Opened in picture input; added only after user confirmation.","format":"svg","title":"Smile"}
```

The UI binds incoming requests only while MainActivity is STARTED. In the background
it posts the existing notification channel with the picture-open action; tapping
opens the latest pending picture. If notification permission is denied, returning
to the app still consumes the latest request. Pending image bytes/URIs are not saved
across process death, by design. Existing explicit AI photo-consent attachments are
separate from this incoming-picture inbox.

## Latest input and privacy

A single memory slot replaces the previous pending image. A generation ticket stops
an earlier asynchronous URI inspection from publishing after a newer request. In a
batch, only the final picture URI opens; nonpicture designs retain their existing
import handler. There is no multi-picture chooser or pending queue. PhotoViewModel's
revision/cancellation checks discard older decode/generation results. No original
picture is copied to cache, the library, or saved state by these ingress paths.
Only a user-confirmed project/generated PES can subsequently be saved by normal UI.

## Formats and BMP

Routing uses content, independent of MIME/name: PNG/JPEG magic, BMP `BM` file header,
WebP RIFF brand, GIF header and HEIF/AVIF ftyp brands. Existing PES/PEC/DST/archive
signatures retain their import path; unsupported SVG is checked by SvgPictureInput.
Raster decode reuses the bounded BitmapFactory/EXIF decoder for both URI and memory
streams. [Android's official image support table](https://developer.android.com/media/platform/supported-formats#image-support)
marks BMP decoding YES for all Android versions, as well as GIF/JPEG/PNG; HEIF/AVIF
support depends on OS/device. Unsupported/corrupt decode stays in the photo screen
with an error rather than adding a layer. A generated 70-byte 24-bit 2×2 BMP fixture
is host-decoded and independently content-sniffed in tests. This is not an Android
hardware decode result; emulator/device execution was prohibited.

## Size limits and their basis

Cloudflare documents a WebSocket message size of **32 MiB for received messages only**.
This applies to Workers and Durable Objects (developers.cloudflare.com/durable-objects/platform/limits/,
workers/runtime-apis/websockets). It was raised from **1 MiB** on 2025-10-31 (changelog
2025-10-31-increased-websocket-message-size-limit). Nothing is documented for messages a
Worker *sends*, and the picture travels relay→app as a sent message. The MCP POST into the
Worker has a 100 MB request-body limit (Free/Pro), so it is not the binding limit.

- The relay caps every call message to the app at **1,048,576 UTF-8 bytes**
  (`MAX_CALL_BYTES`, the last documented 1 MiB). It refuses larger calls itself, without
  sending them, with the share guidance (`TOO_LARGE`): "save the picture and share it to
  myembro".
- The app caps raster `data` at **1,000,000 base64 characters**
  (`RelayPicture.MAX_BASE64_CHARS`). That leaves 48,576 bytes for the envelope; the relay test
  checks the maximum data with a 600-Hangul-character title still fits.
- SVG keeps `SvgPictureInput.MAX_BYTES` = **4788** UTF-8 bytes (the measured corpus maximum,
  docs/agent/svg-picture.md). An SVG over that limit gets "simplify it or send a small PNG".
- No app↔relay chunking: one picture under the cap is one message, and over the cap the user
  shares it instead. The share flow is the existing AI picture return (docs/agent/ai-picture.md).

The tool description requests small raster payloads and explains that addition requires user confirmation.

## Automated verification

```text
./gradlew --offline --max-workers=2 :engine:test :app:testDebugUnitTest
engine: {'tests': 939, 'failures': 0, 'errors': 0, 'skipped': 0}
app: {'tests': 434, 'failures': 0, 'errors': 0, 'skipped': 0}
RELAY_PICTURE_HANDOFF latest=last editor_mutations=0 disk_writes=0 awaiting_user_confirmation=true
npm test (relay): 6 files, 53 tests passed
```

PictureIntakeTest covers 234 MIME/action/content combinations; PictureInboxTest covers latest-only ordering, stale publication and defensive memory copies. RelayPictureHandoffTest uses rejecting fake decode/export/publish services to assert no editor or disk effects, including when the editor is unavailable. Existing notification instrumentation scenarios were updated for user confirmation, but not executed.

Final non-writing verification:

```text
./gradlew --offline --max-workers=2 :engine:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL in 56s
npm test: 6 files / 53 tests PASS
```
