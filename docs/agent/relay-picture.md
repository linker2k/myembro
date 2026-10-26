# import_picture: a picture drawn by the connected AI, straight into myembro

The relay tool `import_picture` lets a chatbot connector put a picture it drew into the
open editor. There is no new stitch algorithm, sewing exception or setting: each format uses
an existing path.

| Argument | Meaning |
|---|---|
| `format` | `svg`, `png` or `jpeg` |
| `data` | SVG: the SVG text itself. PNG/JPEG: base64 (a `data:...;base64,` prefix is allowed) |
| `colors` | 2..6, optional (default 4, the photo screen's `begin()` default). Applies to PNG/JPEG only |
| `title` | optional object name (default `AI picture`, trimmed to 60 characters) |

The relay offers `colors` and `title` as optional with defaults (`RelayCatalog.OPTIONAL`). The
strict app schema (`RelayPicture.DEFINITION`) keeps every argument required, as `ToolSchema`
expects, and the app fills the defaults before validating.

## Path (app, `RelayPhotoTools.importPicture`)

1. `RelayPicture.check`: format, size and content come first. SVG must be text that starts
   with `<svg` or `<?xml`. PNG must start with its 8-byte signature and JPEG with `FF D8 FF`.
2. Preparation runs outside the turn and changes nothing:
   - **SVG**: `ToolTurnController.prepareSvgPicture` runs `SvgPictureInput.parse` (the same
     limits and content checks as docs/agent/svg-picture.md). Then `VectorArt.render` with
     its defaults, at the photo screen's first SVG width (50 mm,
     `EmojiViewModel.WIDTH_MM`), clamped by `pictureWidthRange` and rounded to 0.1 mm. Then
     `PesWriter.writeV1` and `SewoutCheck`, the same gate as emoji.
   - **PNG/JPEG**: the picture is saved as an AI picture attachment
     (`cache/ai_picture_imports/<id>.<ext>`). It is then listed by `list_attachments` and
     `view_attachment` like a handed-over photo, and removed on revoke or clear. It goes
     through `prepare_photo` in the photo screen's color mode with N = `colors`, at the
     photo screen's default width (`photoMaxWidthMm`), filter `original`.
3. `add_generated_art` runs as one turn through the editor's existing `publish`: one undo
   step, sewout-checked. The answer has the `RelayEdits.result` shape plus a `picture`
   summary (format, bytes, colors, width and height in mm).
4. The phone shows a snackbar: "AI 그림이 들어왔어요: <title> (되돌리기로 취소)".

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
- The app caps PNG/JPEG `data` at **1,000,000 base64 characters**
  (`RelayPicture.MAX_BASE64_CHARS`). That leaves 48,576 bytes for the envelope; the relay test
  checks the maximum data with a 600-Hangul-character title still fits.
- SVG keeps `SvgPictureInput.MAX_BYTES` = **4788** UTF-8 bytes (the measured corpus maximum,
  docs/agent/svg-picture.md). An SVG over that limit gets "simplify it or send a small PNG".
- No app↔relay chunking: one picture under the cap is one message, and over the cap the user
  shares it instead. The share flow is the existing AI picture return (docs/agent/ai-picture.md).

The tool description says *SVG is preferred (text)* and *PNG or JPEG ... must be small*.

## Verification (2026-10-26)

- `RelayPictureTest` covers the base64 cap at n−4/n/n+4 (base64 grows in steps of 4) and
  n+1 characters. It also covers the SVG byte cap at n−1/n/n+1, including Hangul UTF-8;
  signature mismatches; defaults; and strict versus offered schemas.
- `SvgPictureToolTest` covers:
  - A 2-color smile gives sewout PASS, one turn and one undo, which restores the project.
  - Width clamps to 99.9 and 20 and rounds to 42.4.
  - An oversized, `<text>` or `<image href>` SVG gives SVG_NOT_SUPPORTED with no change.
- The relay `static-tools.test.ts` checks:
  - A message of exactly 1 MiB is sent; 1 byte more gets `TOO_LARGE` and is not sent.
  - The app cap fits inside the message limit.
  - import_picture matches its strict schema in `RelayPicture.kt`.
- End to end on emulator-5558 with local `wrangler dev` (no deploy or login) and `claude -p`:
  - "2색 스마일 SVG를 그려 import_picture로 넣어줘" made one call, ok, sewout PASS. The
    50×50 mm object had 2 thread colors, and undo returned the editor to 0 objects.
  - A direct MCP call with the repository synthetic `robot.png` and `colors` 2 gave PASS,
    2 thread colors, 99.9×78.1 mm, and the snackbar.
  - A message over 1 MiB was refused by the relay; 1,000,004 characters was refused by the
    app with PICTURE_TOO_LARGE.
