# PC MCP host over the shared dispatcher

`:mcp` (`tools/mcp`) is a stdio host over the common engine ToolTurnController,
with one in-memory EditSession per connection. It uses the repository's existing
Kotlin/JSON stack. No Java2D font extraction, font download, provider SDK or new
stitch algorithm is introduced. The Android APK does not depend on this module.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew --offline :mcp:check :mcp:installDist
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 \
  tools/mcp/build/install/myembro-mcp/bin/myembro-mcp /tmp/myembro-output
```

Use the installed executable as the MCP command, not Gradle. Stdout contains only
newline-delimited UTF-8 JSON-RPC, diagnostics use stderr, and EOF exits. The host
supports initialization (2025-06-18), ping, tools/list and tools/call under the
[MCP stdio specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).
It does not expose remote HTTP, arbitrary paths, filesystem browsing or persistent
project sessions. The output directory is an explicit trusted-host startup grant.

## Shared services and JVM bindings

`tools/list` preserves all 70 definitions, annotations and strict input schemas in
`docs/agent/tools.json`. Executable calls and validation failures reach the shared
controller; confirmation requests may return before execution, and unsupported tools remain explicit NOT_IMPLEMENTED errors. Begin/stage/
commit/cancel and undo/redo share engine history. A schema failure in a prepared
turn reaches the controller's abort path; the host cannot leave half a turn pending.

- BundledFontResolver supplies Ink/Stitch and Hershey plus SnapshotFontResolver for
  the licensed Android captures from `agent/font-snapshots` (`4413c6a`). The finite
  captured TTF subset, exact source revisions and existing automatic height/stitch
  policy remain authoritative. Hangul satin replays recorded rasters through the
  existing CenterlineGlyph/layout/width assembly; missing characters fail closed.
- BundledDesignResolver supplies the shipped logo/sample catalog and original
  source bytes. The JVM sample library is the resolver's reproducible bundled
  fixtures, not a scan of arbitrary PC folders.
- BundledEmojiResolver supplies the same local catalog, checked SVGs and existing
  VectorArt defaults as the app. Uninstalled emoji return DOWNLOAD_REQUIRED.
- DirectoryDocumentStore maps destinationId=output to the granted directory.
  list_fonts, get_settings, checked PES artifacts, save_project and save_pes use
  the common services. There is no duplicate MCP font list or file writer.

get_project includes measured placedBoundsMm for each object, from the engine's
placed stitch bounds. Coordinates remain centered hoop mm. For H100 the integer
window is −500..499 in 0.1mm units; no tolerance is added. Query bounds to place
objects rather than assuming transform positions equal visible bounding centers.

New document names save directly. Existing names return CONFIRMATION_REQUIRED,
needsUserConfirmation=true, a single-use confirmToken and retryArguments with
normalized filename and overwrite=true. After actual approval the trusted host
resends retryArguments and `_meta: {"myembro.confirmToken":"..."}`. Shared tokens
bind the exact project revision, payload, arguments and existing file digest.
`overwrite=true` alone cannot replace a file. save_pes accepts only an opaque,
revision-current artifact from complete-PASS prepare_pes; blocked output cannot
create one. Saves are refused during active turns. See [dispatcher](dispatcher.md).
Non-storage confirmation tools retain action/arguments/revision-bound host tokens.

Ordinary CLI model tool calls do not expose arbitrary MCP `_meta`, so interactive
confirmation requires a trusted client-host adapter; reading a token is not human
approval. The schema remains unchanged. The test-only bridge handles only the
explicitly authorized test-owned filename/destination, and for overwrite checks
its recorded seed SHA-256 immediately before replaying the token. It records the
first confirmation response, unchanged target, authorization scope and token retry;
it never approves another filename or overwrites unrecognized user data. It is not
the production server launcher.

## Actual-client composition and overwrite verification

Both Claude Code and Codex CLI executed: “내장 로고 넣고 아래에 안녕 15mm,
하트 이모지 20mm 오른쪽 위, PES로 저장(이미 있는 이름이면 덮어쓰기 확인 거쳐서)”.
The test starts each client with a separate copy of a valid, public generated PES
named composition.pes. Its seed digest is recorded before either client starts.
No personal designs are used. All output/logs remain under
`/tmp/myembro-mcp-composition/clients/` and are not committed.

A reproducible layout retains the logo's full 60×63.3mm extent. Each object is
centered individually using the existing controller, then translated by the
explicit test inputs: logo (−12,−10)mm, Jua 안녕 (−12,35)mm at height 15mm, and
red-heart (35,−35)mm prepared at width 20mm. No logo scaling is needed. Actual
bounds verify text below the logo, heart to its upper right, nonoverlap and exact
integer hoop containment. The emoji's painted extent is 19.8×17.7mm after the
existing digitization/coordinate rounding; requested preparation width is 20mm.
The entire composition commits as one undo step.

```sh
python3 tools/mcp/check_client_composition.py /tmp/myembro-mcp-composition/clients
/tmp/myembro-quality-gate/quality-gate check \
  /tmp/myembro-mcp-composition/clients/claude/composition.pes \
  /tmp/myembro-mcp-composition/clients/codex/composition.pes \
  --text --json /tmp/myembro-mcp-composition/clients/quality-gate.json
```

Observed outputs: `CLIENT_COMPOSITION PASS` for both clients, `CONFIG_RESTORED
PASS`, and independent `PASS 2/2 cases passed`. Each final PES is 29,513 bytes.
`composition-assertions.json` verifies actual tool arguments and get_project bounds,
not model prose. Both audits show the first save preserving the seed and the
confirmed retry replacing it. Client JSONL logs, RPC audits, seed digest and gate
JSON are in that same directory.

Claude uses per-invocation --mcp-config/--strict-mcp-config with no persistent entry.
Codex uses a temporary named entry, explicit JAVA_HOME and per-run tool approval;
see [official Codex MCP configuration](https://developers.openai.com/codex/mcp/).
The test removes its registration in finally and restores ~/.codex/config.toml
byte-for-byte and with original permissions from a private backup. Cleanup is
verified against the backup, without printing credentials.

## Automated checks

The root CI `./gradlew check` includes :mcp:check. check_stdio.py launches the real
installed executable and asserts JSON-only stdout, exact schemas, parse errors
and EOF exit. McpHostTest covers common queries, snapshot lettering, protocol and
strict validation, token binding/replay, real logo/text/emoji composition, spatial
bounds, one-turn history and existing-file replacement with no preapproval write.
The existing engine/design/emoji/storage tests remain authoritative. Automated
PASS is not hardware sewout approval.
