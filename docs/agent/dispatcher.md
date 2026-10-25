# Tool dispatcher and atomic edit turns

Base: `agent/commands` at `b8309f6`, including engine `EditSession`, and the
69-tool catalog from `agent/tools`. No Android dependency or new stitch algorithm
is introduced in the engine.

## Kernel and session controller

`ToolDispatcher.dispatch(project, revision, name, arguments, confirmed=false)` is
the snapshot-in/snapshot-out preparation kernel. Its supported names cover
font/settings queries, design and emoji catalogs, text creation/replacement, project inspection, transforms, alignment, distribution, duplication, order,
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

## Font/settings queries and shared document storage

`list_fonts` and `get_settings` are implemented by the preparation kernel and may
run outside a turn through the bound controller. FontResolver.descriptors supplies
IDs, display names, kinds, installation state and known source code points. The
result compresses known code points into Unicode intervals, reports source coverage
as unconfirmed when unavailable, and includes exact font revisions. Text/filter
queries use the resolved source rather than fallback glyphs. Height intervals come
from the same automaticLetteringState function used by Add and the phone screen.
An empty range is explicitly an unconfirmed minimum under the existing running or
centerline baseline; the existing app maximum still applies. Catalog fonts without
local geometry are not claimed to support a query's text. Scope is the injected
host resolver, not a search of all fonts on the PC.

SettingsResolver supplies the current machine and preferred/default frame.
get_settings returns both that default and the active project's frame, plus the
host-injected frame choices; it does not silently replace project geometry.
Android's adapter reads SettingsPreferences, while the JVM default uses the existing
SE400/H100 defaults. Inject settings explicitly for another machine/profile.

DocumentStore is the approved-destination boundary. Inject it into ToolDispatcher
and use the bound controller's save_project/save_pes methods. DirectoryDocumentStore
maps opaque IDs to host-selected JVM folders (its single-folder constructor uses
`output`). `androidAgentDispatcher(context)` binds the actual app fonts, settings/frame choices
and library storage. AndroidDocumentStore uses `library`, the existing files/designs folder,
writeNamedDesign and GeneratedPesOrigins behavior. Only actual generated PES gets
the generated-origin record; imported origins are preserved. Existing editor name
sanitization/80-character stem rules are shared unchanged through documentSaveStem.
Agent document names must be leaf names; paths, symlink targets (JVM) and unknown
destination IDs are rejected. Android hosts should serialize this adapter with
normal editor saves and refresh library/dirty-state UI after successful saves.

New names save immediately under the user's save request. An existing case-insensitive
name returns CONFIRMATION_REQUIRED with needsUserConfirmation=true, a confirmToken
and retryArguments containing the normalized filename and overwrite=true. No bytes
are changed. The trusted host obtains user approval then calls:

```kotlin
controller.dispatch("save_project", retryArguments, confirmToken = token)
```

This token is a host API parameter, never a new field in tools.json. It is bound
to the tool, retry arguments, session revision, encoded payload and existing file
content digest; a changed target/project/payload or consumed token is refused.
`confirmed=true` alone cannot bypass save confirmation, and overwrite=false never
replaces a file. The store also checks the expected existing version before
publication. Cooperating host writes must be serialized; a portable filesystem-wide
compare-and-swap against uncooperative external writers is not claimed.

Only a successful bound prepare_pes or committed non-dry-run script publishes an
opaque PES artifact ID. Artifacts contain defensive byte copies and come exclusively
from the existing complete-PASS writer/SewoutCheck/PecViewportCheck path. save_pes
accepts only those artifacts, and refuses them after the session revision changes.
It never accepts arbitrary bytes or file paths as a checked artifact. A blocked
prepare has no artifact and cannot write a file. Project saves check projectRevision
and encode ProjectJson before opening the destination. Saves during an active turn
are refused; filesystem writes cannot be staged inside atomic or dry-run scripts.
The standalone snapshot kernel intentionally does not perform save side effects.

Verification: MoreToolsTest exercises fresh project/PES saves, unchanged bytes on
conflict even with a boolean confirmation, token-approved replacement, token replay,
external target changes, stale revisions/artifacts, blocked PES, traversal/symlink
refusal and both query outputs. Existing app save tests exercise the unchanged
naming and named-save path. No personal designs are fixtures.

## Design catalogs and approved imports

`list_builtin_designs({})` returns `designs` rows with `id`/`assetId`, name, fileName,
widthMm, heightMm, colorCount, stitchCount and SHA-256 (null for flattened project
sources). Sizes/colors use the same parsed needle bounds/nonempty color blocks as
the library. `list_designs({query, filter, sort})` uses existing library filters
ALL/FITS_100/PES/RECENT/FAVORITES, the existing seven-day recent window, and
RECENT/NAME/STITCHES sorting. Gallery FITS_100 is the nominal HoopSpec size filter;
export still enforces the separate exact P2 limit. Both queries are read-only and
may run outside an edit turn; they do not create history steps.

Inject `DesignResolver` with approved `DesignAsset` entries. `add_design` resolves
its opaque assetId and prepares `ProjectCommand.Add(StitchObject(...))` with the
explicit objectId. Unknown IDs, including paths/URLs, return DESIGN_NOT_FOUND;
object ID collisions use the existing DUPLICATE_ID reducer rejection. No model
argument grants file access. Original byte arrays are copied and retained as
sourceBytes/sourceFileName; imports are never reclassified as generated geometry.

The default JVM BundledDesignResolver reads the APK's sample manifest and PES files
plus `builtin:myembro-logo`. Its local library exposes the shipped samples again as
`library:<sample filename>` fixtures, so JVM list_designs/add_design need no user
filesystem access. Resources include the existing sample notices and CC0
license. The Android binding is `ToolDispatcher(designResolver =
AndroidDesignResolver(context), fontResolver = AndroidTextFontResolver(context))`.
It uses BundledDesigns/DefaultLogoInstaller assets and DesignLibrary files and
favorites. Library bytes use the existing bounded, canonical-directory
DesignSourceResolver. Existing .myembro library entries flatten with DesignFormats,
exactly as editor import, without pretending their JSON is original stitch bytes.
Run this synchronous adapter on the host's serialized IO/edit executor.

An unchanged single visible imported PES with identity transform, unedited threads
and matching parsed source keeps its exact bytes and PES version after validation;
requested writer version applies when a composition is actually encoded. Moved,
rotated, scaled or recolored designs use the normal checked writer; original source
bytes remain intact unless the existing recolor command invalidates them. No gate
is relaxed: imported density blocks above 30 penetrations per 0.5mm cell; app output
blocks above 15. The dispatcher's existing PASS-only release rule is unchanged:
imported density 16–30 can be WARNING/CAUTION and therefore returns no prepared PES.
Tests distinguish that release policy from the P7 blocker threshold.

Reproduce logo lookup/add/move/export with:

```sh
./gradlew :engine:runAgentScenario \
  -PagentScenario=docs/agent/scenarios/add-builtin-logo.json \
  -PagentOutput=/tmp/myembro-design-tools-NEW
```

`DesignToolTest` covers lookup, complete catalog metadata, missing IDs, collision
rollback, library IDs, source byte ownership/preservation, read-only history and
imported 20/30/31 density classification using synthetic fixtures only.

## Local emoji preparation and generated art

`list_emoji({query, groupId})` uses the same EmojiCatalog/CatalogEmoji.matches as
the picker, now in the engine with app type aliases. Empty query/group lists the
30 bundled entries plus locally downloaded entries. A query searches the full
catalog (including CLDR Korean names/keywords); an explicit group lists that
catalog group. Rows carry id, name, nameKo, glyph, groupId, bundled, available,
downloadRequired and the pinned SHA-256. Listing does not read/download SVG data.

`prepare_emoji({emojiId, frameId, widthMm})` resolves only local verified bytes via
EmojiResolver. BundledEmojiResolver loads the same shipped catalog/SVGs/MIT and
CLDR notices; AndroidEmojiResolver calls EmojiRepository.localSvg, which never
calls the downloading svg() API. Missing local data returns DOWNLOAD_REQUIRED
with no resultId; unknown IDs, checksum and local read failures return
EMOJI_NOT_FOUND, ASSET_INTEGRITY_ERROR or EMOJI_READ_FAILED. No tool downloads.
The normal app picker remains responsible for its existing download flow.

The generation path is unchanged: VectorArt.parseSvg -> VectorArt.render with
all defaults -> checked PesWriter/SewoutCheck using APP_GENERATED origin. The
existing pictureWidthRange formula and PhotoSizeSweep-derived 20mm minimum now
live in the engine; app photo/emoji sliders delegate to them. Aspect comes from
the painted envelope already used by VectorArt, not SVG viewBox whitespace.
Out-of-policy widths return WIDTH_OUT_OF_RANGE with minWidthMm/maxWidthMm;
accepted widths use the picker's existing 0.1mm rounding. No stitch/lock/density
setting is exposed or changed. Existing writer/sewout blockers remain active.

Successful preparation returns READY, resultId, dimensions, allowed range and
verdict without editing a Project or history. Keep the dispatcher for the document
lifetime: its opaque result cache retains the generated EmbPattern, source locks
and provenance. `add_generated_art({transactionId, resultId, objectId, name})`
resolves only this cache and submits Add(StitchObject). It does not read a PES back
or label generated art imported; sourceBytes is null and generated P7=15 remains.
Unknown/foreign result IDs return RESULT_NOT_FOUND. Preparation/listing may run
outside a turn; adding requires an edit turn through the shared controller and is
undoable once. Cached preparations are not persisted or evicted by undo/cancel;
they confer no export permission. Final prepare_pes retains its PASS-only rule.

Bind Android with `ToolDispatcher(emojiResolver = AndroidEmojiResolver(context),
fontResolver = AndroidTextFontResolver(context), designResolver =
AndroidDesignResolver(context))` on the serialized background executor.

`prepare_photo` remains NOT_IMPLEMENTED. Its source field is now attachmentId,
meaning only a photo already picked/granted in the app; existing frame/style/crop
choices stay explicit. Raw path, URL, bytes and the former assetId fields fail
closed under the strict schema. No attachment is opened by this stub.

Verification: `EmojiToolTest` covers heart 20mm prepare/add/prepare_pes PASS,
minimum/maximum ±0.1mm, Korean search, 30 bundled plus local downloads, no-fetch
status, missing IDs, integrity/read errors, preparation/history isolation, one-step
undo and exact source stitches/threads/metadata against the existing VectorArt path.
