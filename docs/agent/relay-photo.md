# Relay photo tools (standalone integration unit)

Base: `agent/relay-header` at `6f4f76e`. This unit does not change relay advertisements,
`RelayProtocol`, the dispatcher catalog, the relay service, or editor navigation.
The registry owner connects it after integration. Only the repository's synthetic
`engine/src/test/resources/image-to-design/robot.png` is used in tests.

## Registry/host contract

- Create one `RelayPhotoAttachments(applicationContext)` for the current editor session.
  Supply that **same instance** as `attachmentStore` when constructing the existing
  Ask AI `androidAgentKernel`. Supply its **existing** bound `ToolTurnController` to
  `RelayPhotoTools`; do not create another live `EditSession` or independent kernel.
- Mount `RelayPhotoHandoff(photos)` in the connected editor's controls. It opens the
  system image picker, then asks for consent **for that individual photo**. Cancellation,
  dismiss, or merely choosing a photo grants no capability. The trusted UI's
  `handoff(uri, consentGiven)` is not a remote tool and must never be mapped to JSON.
- Register `RelayPhotoTools.definitions` and route its four `NAMES` to `call`.
  Definitions retain the catalog annotation names; the registry projects them through
  `RelayProtocol.mcpAnnotations`, like existing tools. Do not register the UI's consent
  methods. These definitions are standalone until the registry hook is integrated.
- Supply the editor's current export settings (`machineId`, `frameId`, `pesVersion`),
  its existing publish/edit lock + refresh callback, and `canEdit` (false for viewer,
  closed editor or a replaced session). Use the default machine from app settings.
  Callbacks must be safe from the Default dispatcher. Construct a fresh tools instance
  on document/session/kernel replacement, and clear the old photo store on disconnect
  or session disposal. `revoke(id)` removes an individual capability.
- The registry/relay timeout must be 120 s as specified by the shared edit plan.
  This unit neither changes timeouts nor adds a network connection.

## Tool boundaries

| Tool | Closed arguments | Result / behavior |
|---|---|---|
| `list_attachments` | `{}` | Consented opaque attachment IDs only; no URI, path, gallery enumeration or pixels. |
| `view_attachment` | `attachmentId: string`, `maxEdgePx: integer 1..768` | MCP image block (`type: image`, `data: base64`, `mimeType: image/png`); full image, preserved aspect, never enlarged. |
| `prepare_photo` | Existing strict catalog arguments unchanged | Existing dispatcher/PhotoService preparation and sewing check, outside an edit turn; project/revision/undo unchanged. Result handles stay local to this tools instance and photo consent. |
| `add_generated_art` | Existing strict catalog arguments unchanged | Existing controller `executeScript` with one call, existing full candidate `prepare_pes` checks, then one atomic turn. Only results prepared by this photo extension and still-consented photos are accepted. |

`view_attachment` first validates its strict schema and capability, then uses the existing
bounded EXIF-aware photo decoder. Only the resulting PNG (at most 768px on its long
edge) is transmitted. The raw image/URI is never present in list, prepare or edit replies.
There is no file save/export, share, USB, gallery access, restore or settings mutation.
The production preview writes no image files. Photos and capabilities are memory-only;
there are no persistent URI grants or automatic recovery of consent after restart.

After an attempted atomic add, the text JSON includes `ok`, live `revision`, the existing
`ToolDispatcher.summary` (placed bounds in mm and thread colors), and `sewout` with
`verdict: PASS|BLOCK`, findings and the original check details. A blocked candidate is
not committed. Early invalid-argument/capability/viewer errors perform no edit.
Prepared results cannot be added after their photo's consent is revoked. Only the
explicit image-view tool exposes photo pixels; implicit existing Ask AI attachments
are not imported into this store.

## Android verification

```sh
ANDROID_HOME=/home/gosh2/Android/Sdk JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.relay.RelayPhotoInstrumentation :app:lintDebug --console=plain
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.relay.RelayPhotoInstrumentation
```

Build + lint: `BUILD SUCCESSFUL in 44s`. Instrumentation uses actual Android image
sampling/PNG codecs and the real existing photo generation and sewing check. It verifies
no consent/no exposure, unissued paths and URIs, exact 767/768 long edges, rejection of
769/unknown consent field/string width, withdrawal, viewer refusal, unchanged preparation,
PASS + findings/summary/revision on add, exactly 1 undo, and out-of-hoop BLOCK with no
partial project/history change.

Logs: `/tmp/myembro-relayphoto-build-final.log`,
`/tmp/myembro-relayphoto-instrumentation-final.log`. Test copies are synthetic only and
removed on success from app cache `pes_exports/relay-photo-test/`. No personal photo or
release emulator is used. UI/registry wiring and end-to-end connected-chat smoke remain
the integration unit, intentionally outside this standalone commit.

Final device output:

```text
INSTRUMENTATION_RESULT: result=CONSENT PASS: declined/unissued URI/path not listed, viewed or prepared
VIEW 767 PASS: PNG decoded, long side=767, aspect preserved
VIEW 768 PASS: PNG decoded, long side=768, aspect preserved
VIEW 769 / forged consent / noninteger PASS: rejected before decode
REVOKE PASS: preview capability removed
PREPARE PASS: real PhotoService pipeline, sewout PASS, session unchanged
ADD PASS: sewout PASS + findings + summary/revision; exactly 1 undo restores project
HOOP_BLOCK PASS: out-of-hoop candidate rejected; findings included; no partial edit/history
REVOKED_RESULT PASS: no edit after withdrawal
RELAY_PHOTO_PASS
INSTRUMENTATION_CODE: 0
```

Existing Ask AI regression + final lint:

```sh
ANDROID_HOME=/home/gosh2/Android/Sdk JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:lintDebug :app:testDebugUnitTest --tests '*AgentPhotoAttachTest' --console=plain
python3 tools/check_agent_tools.py
```

`BUILD SUCCESSFUL in 9s`; AgentPhotoAttachTest tests=9 failures=0 errors=0. The schema checker reports
`DRAFT_2020_12 PASS: 70 input schemas` and `PROJECTIONS PASS: 70 MCP / OpenAI / Gemini schemas unchanged`.
Log: `/tmp/myembro-relayphoto-regression.log`.
