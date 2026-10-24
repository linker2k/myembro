# PC MCP host over the shared dispatcher

This replaces the `agent/mcp-spike` Java2D lettering implementation. `:mcp`
(`tools/mcp`) depends on `:engine`, uses the existing Kotlin/JSON versions and
introduces no provider SDK, Java2D rasterizer, font download or stitch algorithm.
The Android APK does not depend on `:mcp`.

## Build and stdio launch

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew --offline :mcp:test :mcp:installDist
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 \
  tools/mcp/build/install/myembro-mcp/bin/myembro-mcp /tmp/myembro-output
```

Use the generated executable, not Gradle, as the MCP command. Stdout contains only
newline-delimited UTF-8 JSON-RPC; diagnostics use stderr. EOF exits the host. This
implements the [MCP stdio transport](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports),
initialization (2025-06-18), ping, tools/list and tools/call. No remote HTTP endpoint,
filesystem browser, prompts or server-managed LLM is provided. One connection owns
one in-memory project and one engine EditSession; state is not persisted on exit.

## Tools and state

`tools/list` returns the exact 69-tool catalog in `docs/agent/tools.json`, including
closed input schemas and existing annotations. Tool calls are schema-validated
before consent or execution. Engine tools go through the bound ToolTurnController;
unsupported tools return NOT_IMPLEMENTED. The host also binds `list_fonts` to its
local resolver and `save_pes` to the approved output directory. SCRIPT font tagging
is unbound and fails explicitly; installedOnly includes only installed snapshots
and bundled fonts. No network font discovery is attempted.

Editing uses get_project → begin_edit_turn → edits using one transactionId →
commit_edit_turn with the original revision. Cancel, undo and redo share engine
history. Text placement uses the existing centered hoop coordinate system. It does
not reinterpret coordinates as top-left mm. center_objects uses the dispatcher.

prepare_pes runs the existing writer, SewoutCheck and PecViewportCheck and creates
an opaque in-memory artifact only after complete PASS. The MCP response includes
artifactId and the original check report, not raw PES bytes. save_pes accepts that
artifact, destinationId=output and a plain .pes filename. Paths, traversal and
symlink targets are refused. New saves use CREATE_NEW-equivalent hard-link
publication without replacing an existing file. Overwrite is explicit, confirmed
and uses atomic replacement. This filesystem implementation was tested on Linux;
other filesystems' atomic-move/hard-link support is unconfirmed.

## Font resolution

BundledFontResolver supplies the five shipped Ink/Stitch fonts and Hershey. Its
snapshot slot is bound to SnapshotFontResolver, which replays the licensed Android
captures from `agent/font-snapshots` at `4413c6a`. Only the finite captured glyph
subset is available. No substitute glyph or java.awt extraction is used. Normal
outline preparation uses engine prepareText; Hangul satin replays captured rasters
through existing CenterlineGlyph and the exact layout/width assembly demonstrated
in AndroidFontSnapshotTest. Missing glyphs and source revisions fail explicitly.
The existing automatic lettering policy and height ranges are unchanged.

The module bundles captured geometry/rasters, manifest, provenance README and OFL
licenses, not the captured sample PES files. The original capture checksums and
Android/JVM parity tests remain in engine tests. list_fonts returns supported IDs
and exact revisions; initialize instructions also advertise the installed subset.

## Confirmation contract

Every tool whose catalog annotation needsUserConfirmation=true first returns
CONFIRMATION_REQUIRED with a random confirmToken, exact arguments and current
revision. It does not execute. After obtaining user approval, a trusted client host
must repeat the identical tools/call with:

```json
{
  "name": "save_pes",
  "arguments": {"artifactId": "...", "destinationId": "output", "name": "design.pes", "overwrite": false},
  "_meta": {"myembro.confirmToken": "..."}
}
```

Tokens are session-local, action/argument/revision-bound and consumed on an
attempt. Changed arguments, changed revision and replay are refused. Possession of
a token does not prove a human approved it: obtaining approval is the trusted
host's responsibility. No optional token field is added to tools.json. Ordinary
CLI model tool calls do not expose arbitrary MCP _meta; direct interactive consent
therefore needs a client-host adapter. Do not pretend this works automatically in
an unmodified CLI.

`test_client_bridge.py` is **test only**. The user's explicit integration-test
request authorizes one new named PES in the supplied /tmp output directory. The
bridge forwards the first save call, records CONFIRMATION_REQUIRED, then resends
with its token only for that exact filename/destination and overwrite=false. It
never approves other actions or overwrites. Its audit records both requests and
responses and the authorization scope. It is not the production server launcher.

## Client integration and cleanup

Use per-invocation Claude Code --mcp-config / --strict-mcp-config to avoid changing
persistent registration. For Codex CLI, use a temporary named entry with JAVA_HOME
and its per-run approval mode; see the [official Codex MCP configuration](https://developers.openai.com/codex/mcp/).
Back up ~/.codex/config.toml byte-for-byte before registering, remove the test entry
in finally and restore the backup, including permissions. Do not log credentials
or commit client config backups.

Automated evidence from this task is kept in `/tmp/myembro-mcp/`:

- `direct-rpc.json`: real newline stdio initialize/list/call/confirmation round trip.
- `claude-exact-client.jsonl`, `codex-client.jsonl`: actual client execution logs.
- `claude-exact-rpc.jsonl`, `codex-rpc.jsonl`: host-side tool and confirmation audit.
- `config-restored.json`: restoration hash and temporary-entry removal check.
- `quality-gate.json`: independent Python gate results for both clients' PES.

The client task is “안녕 myembro 넣고 가운데 정렬 후 PES 저장”, with a reproducible
jua 8mm black-thread setup, one edit turn, center_objects, prepare_pes for SE400/H100
and a new output filename. Unit tests also assert exact catalog, strict fields,
confirmation binding/replay, third-party snapshot preparation, checked saves,
existing-file refusal and traversal refusal. Automated PASS is not hardware sewout
approval.

Observed real-client result: Claude Code and Codex CLI both completed get_project /
list_fonts → begin → add_text → center_objects → commit → prepare_pes → save_pes.
Both prepare_pes reports were PASS and both saves logged first-call confirmation
followed by the scoped host token replay. Independent validation command:

```sh
/tmp/myembro-quality-gate/quality-gate check \
  /tmp/myembro-mcp/claude-exact/claude.pes /tmp/myembro-mcp/codex/codex.pes \
  --text --json /tmp/myembro-mcp/quality-gate.json
```

Output: `PASS 2/2 cases passed`. Reference-only short-stitch statistics remain
reported; they are not blocking criteria under the frozen gate policy. The Codex
config restoration check reports restored=true and temporaryEntryRemoved=true.
Claude used only an invocation-local config and left no registered MCP item.

The first Claude trial shortened the wording to 안녕. It is retained as a diagnostic,
not the requested exact-text evidence. The repeated `claude-exact-*` trial explicitly
verified the full literal 안녕 myembro in add_text and get_project; the final gate
report uses that repeated output and the Codex output. Both actual add_text argument
values are checked by script, not inferred from client prose.

`:mcp:check` also runs `check_stdio.py` against the installed executable: JSON-only
stdout, exact 69-tool schema preservation, invalid-JSON response and clean EOF exit.
The existing root `./gradlew check` CI step therefore includes this module.
