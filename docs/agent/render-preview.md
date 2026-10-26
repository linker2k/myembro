# Android render_preview

The relay spike's read-only image tool renders a captured current-project snapshot. The Android implementation is separate from the pure JVM command engine and has no network, disk-writing, session-commit, save/export, camera, or selection side effects.

## Host entry point

```kotlin
import io.github.linker2k.myembro.ui.agent.ProjectPreviewRenderer

// Capture the current editor/session Project once, then render off the UI thread.
val imageContent = ProjectPreviewRenderer.renderPreview(projectSnapshot, widthPx)
// Relay result: {"type":"result","id":callId,"isError":false,"content":[imageContent]}
```

The single public function returns a `JsonObject` with exactly:

```json
{"type":"image","data":"<base64 PNG, no line breaks>","mimeType":"image/png"}
```

The project argument is host-supplied, never accepted from remote JSON. `docs/agent/tools.json` exposes only required integer `widthPx`, inclusive 256..1024, with `additionalProperties=false`, `readOnly=true`, and no user confirmation. Out-of-range values throw `IllegalArgumentException` before image allocation or design rendering. The host converts rendering failures to an error result rather than returning a partial picture.

This unit adds the image function and authoritative catalog entry, not a WebSocket client or relay dispatcher. The relay spike must allowlist only `get_project` and `render_preview`; the catalog's other tools are not authorized for that spike.

## Rendering rules reused

`ProjectPreviewDrawing.kt` extracts the existing `AgentScreens.PreviewPane` native Canvas body into a shared function, now called by both the UI and PNG renderer. It uses the existing `RenderData` STITCH-only, thread-color runs of visible objects' `placedPattern()` in sewing order. Jumps, hidden layers and selection handles are not drawn.

Hoop-centered placement, 90% fit, rectangular hoop geometry, 1.5px border, 1.2px round thread lines, and object transforms are the existing project-preview rules. There is no export flattening, recentering, stitch generation, or newly invented stitch-rendering algorithm. A rectangular hoop retains its aspect ratio inside the square PNG.

The PNG uses an opaque white background as required by the relay contract and the shared light-theme outline token for the hoop, independent of dark screen mode. PNG bitmap memory is released in `finally`. Read-only rendering may populate existing design memoization caches, but does not change the serialized project, revision, undo/redo, or active-turn state.

## Validation

```sh
python3 tools/check_agent_tools.py
ANDROID_HOME=/home/gosh2/Android/Sdk JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.agent.RenderPreviewInstrumentation --console=plain
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.agent.RenderPreviewInstrumentation
```

Schema output: `RENDER_PREVIEW PASS: readOnly, strict widthPx, 255/256/1024/1025 boundaries`; all 70 portable tool schemas/projections pass. Missing/unknown fields and fractional/string/boolean widths are also rejected by the schema check.

APK/test APK build: `BUILD SUCCESSFUL in 44s`. Actual Android instrumentation uses synthetic red/blue stitches only and reports:

```text
REJECT 255
REJECT 1025
PNG 256 square/decoded/opaque/white/hoop PASS
PNG 1024 square/decoded/opaque/white/hoop PASS
RECTANGULAR_HOOP aspect/placement PASS
THREAD_COLORS placement/no-jumps/readOnly PASS
HIDDEN_OBJECT ignored PASS
RENDER_PREVIEW_PASS checks=7
INSTRUMENTATION_CODE: 0
```

Checks cover PNG signature/base64/envelope/dimensions, alpha on every pixel, white background and interior, hoop border pixels, rectangular aspect, placed red/blue runs, absent jump travel, hidden-object exclusion, and unchanged session snapshot/project JSON. Only development emulator-5556 is installed. No user design/image or release emulator is involved.

Logs: `/tmp/myembro-render-preview-build.log`, `/tmp/myembro-render-preview-instrumentation.log`. The test-only rendered artifact is `/tmp/myembro-render-preview/synthetic.png`; the production function creates no file.

Additional regressions:

```sh
ANDROID_HOME=/home/gosh2/Android/Sdk JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :app:testDebugUnitTest :app:lintDebug :engine:test --tests '*ToolDispatcherTest' --tests '*AdversarialSafetyTest' --console=plain
ANDROID_HOME=/home/gosh2/Android/Sdk JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :mcp:test --tests '*McpHostTest' --tests '*AdversarialMcpSafetyTest' :mcp:checkStdio --console=plain
```

App unit tests (361), related engine tests (14), and app lint passed: `BUILD SUCCESSFUL in 1m 2s`. MCP tests and the subprocess smoke passed: `MCP_STDIO PASS: JSON-only stdout; exact 70 schemas; parse error; EOF exit`, `BUILD SUCCESSFUL in 8s`. Existing catalog-count expectations were updated from 69 to 70; unsupported-tool behavior remains unchanged. Logs: `/tmp/myembro-render-preview-final-check.log`, `/tmp/myembro-render-preview-mcp-check.log`.
