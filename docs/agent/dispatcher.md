# Tool dispatcher and atomic edit turns

Base: `agent/commands` at `b8309f6`, including engine `EditSession`, and the
69-tool catalog from `agent/tools`. No Android dependency or new stitch algorithm
is introduced in the engine.

## Kernel and session controller

`ToolDispatcher.dispatch(project, revision, name, arguments, confirmed=false)` is
the snapshot-in/snapshot-out preparation kernel. Its 17 supported names cover
text creation/replacement, project inspection, transforms, alignment, distribution, duplication, order,
visibility, color, removal, frame selection, sewing checks and PES preparation.
Successful edits return their resolved `ProjectCommand` for session publication.

`dispatcher.bind(editSession)` creates a `ToolTurnController` for one document.
It adds `begin_edit_turn`, `commit_edit_turn`, `cancel_edit_turn`, `undo` and
`redo`. All other catalog tools return `NOT_IMPLEMENTED` after schema validation.
Unknown names return `UNKNOWN_TOOL`; invalid inputs return `INVALID_ARGUMENTS`.
Use one controller per document and serialize all UI/tool edits on the host.
Keep the synchronous preparation/export work off the Android main thread.

Interactive mutations require a matching active transaction. Begin captures the
engine revision; subsequent calls prepare a private candidate. No candidate is
published until commit. Because `EditSession.beginTurn` requires the complete
command body, the controller buffers preparation and calls
`EditSession.executeTurn` with that full body at commit. Cancel discards the
private buffer; it has nothing to remove from engine history. A preparation
failure discards the entire buffer. A changed engine revision rejects commit.
Undo and redo delegate to the same engine session and refuse an active buffer.
There is no second project history.

Completed transaction receipts do not reapply commands. Script retries must use
the identical original base revision, call body and export settings. Receipt
replays expose the current live project, never restore an older snapshot, and
never release cached PES bytes. Callers must own immutable JSON/project inputs.

The authoritative [tools.json](tools.json) is bundled unchanged into engine
resources. `ToolSchema` validates its closed subset: types, required/closed
objects, arrays, enum values, numeric bounds and patterns. It rejects unsupported
schema keywords, unknown keys, type coercion and nonfinite numbers. Dynamic IDs,
thread indices, frame identities and reorder bounds are also checked.
`tools/check_agent_tools.py` separately meta-validates the catalog.

Removal requires trusted host consent bound to the exact arguments and base
revision. `confirmed` is a host API parameter, never a tool JSON field. Frames
and approved PES assets are injected; arbitrary paths, URLs and personal designs
are not accepted by these tools. Text creation/replacement uses the local FontResolver described below.

## Atomic scripts and phone path A

`ToolTurnController.executeScript(transactionId, expectedRevision, calls,
exportSettings, dryRun=false, confirmed=false)` prepares the entire call list
against one base revision. Every mutation must use the outer transaction ID.
Nested lifecycle/history calls are rejected. A failed call or final sewing check
publishes nothing, preserves revision and redo history, and releases no bytes.
A successful changed script commits one engine turn: one undo restores the whole
original project. An unchanged script adds no undo step.

`EditorSession.runCommandScript` is the phone command-script entry point. It
binds the supplied kernel to the editor's existing `EditSession`; normal toolbar
undo/redo therefore operate on the same script history. An active pointer gesture
rejects script execution. The host reuses its kernel for the document and syncs
its observable UI state after apply. This entry point supplies the preview/apply
service; it does not implement a preview screen or a model/provider connection.

Dry-run performs the same preparation and final sewing checks but never commits,
reserves a transaction ID, changes revision/history/redo, or returns PES bytes.
It returns `previewProject`, `changedIds`, `hoopChanged`, per-call results and
`sewout`. It also reports `needsUserConfirmation`. Deletion may be previewed
without confirmation; applying it still requires host confirmation.

## Check and PES release

Export checks actual centered placement before writer normalization, preventing
an off-hoop edit from being hidden by recentering. Existing defaults, final
thread-start cleanup, normal `PesWriter`, `SewoutCheck` and `PecViewportCheck`
remain authoritative. Only complete PASS with valid viewport releases bytes;
CAUTION, BLOCK and writer rejection do not. No safety threshold is relaxed.
Imported approved assets retain their source policy for read-only checking.
These checks do not constitute physical sewout approval or send files to a machine.

## JSON scenario runner

[scenarios/builtin-logo.json](scenarios/builtin-logo.json) exercises the public
bundled logo: move/rotate, recolor, generate and check. Input contains
`initialProject`, `calls`, `export` and optional `transactionId` (default
`scenario`). Initial project is inline ProjectJson or an approved built-in
descriptor. Only the bundled logo and sample heart are resolved; external paths
and downloads are not read. `$revision` resolves to the single base revision.
The entire scenario uses `executeScript`, including the mandatory final check.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew --offline :engine:runAgentScenario \
  -PagentScenario=docs/agent/scenarios/builtin-logo.json \
  -PagentOutput=/tmp/myembro-agent-scenario-NEW --console=plain
```

Add `-PagentDryRun=true` for preview only. The CLI equivalent is
`AgentScenarioCli INPUT.json OUTPUT_DIRECTORY [--dry-run]`.
The output directory must not already exist. `results.json` contains each call,
preview, final live project, history counts and export diagnostics. `output.pes`
is written only on successful real apply. A rejected run exits nonzero without
PES; dry-run writes only results. The runner does not grant removal consent or
perform USB transfer, restore or publication.

## Verification

```sh
python3 tools/check_agent_tools.py
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew --offline :engine:check :app:testDebugUnitTest --console=plain
```

Tests cover every kernel tool with accepted/rejected input; interactive lifecycle,
wrong/stale transactions, retry identity, undo/redo, third-command rollback,
one-undo scripts, dry-run revision/history/redo preservation, confirmation,
final-check rollback, actual phone-session history and gesture exclusion.
The existing encoded 999 PASS / 1000 BLOCK boundary tests are retained.
Fixtures use redistributable built-ins; generated outputs and logs remain local.

## Text tools and local font resolution

`add_text` calls engine `prepareText` followed by `ProjectCommand.Add`;
`replace_text` prepares the replacement and uses `ProjectCommand.ReplaceText`.
The reducer retains the target ID, transform, visibility and layer index. No
stitch generation or font loading occurs in the structural reducer.

Inject `FontResolver` into `ToolDispatcher`. `resolve(fontId)` returns a local
`ResolvedTextFont` or null, and `revision(fontId)` identifies that exact installed
source. `resolveText` lets a platform/snapshot adapter extract only the requested
glyphs; `prepare` defaults to engine `prepareText`. The Android adapter uses the
same font loading, glyph extraction and Hangul centerline preparation as the
lettering screen. Bind it with `ToolDispatcher(fontResolver = AndroidTextFontResolver(context))`.
It never downloads a font. Construct and use adapters on the
host's serialized background edit executor.

The default JVM `BundledFontResolver` loads the five APK-bundled Ink/Stitch fonts
and existing engine Hershey Futural. Its optional resolver parameter is the
extension point for the separate TTF OutlineSnapshot adapter. Font revisions are
resolved by the host, not guessed by callers: use `resolver.revision(fontId)`.
Unknown fonts, stale revisions, missing glyphs and unavailable preparation return
`FONT_NOT_FOUND`, `FONT_REVISION_MISMATCH`, `UNSUPPORTED_CHARACTERS` and
`TEXT_PREPARATION_UNAVAILABLE`, respectively. No fallback glyph is substituted.

The app's pure `LetteringHeightLimits`, automatic selection and weight functions
now live in the engine; the app delegates to them. They use the same swept TTF
range asset, the app’s pinned Ink/Stitch catalog ranges and existing running baseline. There are no new
height thresholds: out-of-policy heights return `HEIGHT_OUT_OF_RANGE` (schema
bounds still return `INVALID_ARGUMENTS`). Hershey has no evidenced positive
minimum in the existing range-free running policy; tests do not invent one.
JVM and Android use the same pinned catalog ranges and SVG revision convention.
Ink/Stitch range tests exercise minimum−0.1, minimum, maximum and maximum+0.1mm.
Empty/whitespace-only text and nonpositive heights are rejected explicitly.

Prepared Ink/Stitch TextObjects retain their resolved font as an in-memory value,
so rendering, recoloring and placement do not depend on a mutable global registry.
This field is not embedded in project JSON; reopening uses the existing installed
font registry. The JVM runner still needs its normal approved-font binding when
reopening an initial project with pre-existing digitized text.
