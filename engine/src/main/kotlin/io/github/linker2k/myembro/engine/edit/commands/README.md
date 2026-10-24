# Project command reducer

`apply(project, command)` wraps existing edit-model value operations. It performs no I/O,
font preparation, stitch generation, history management, random ID allocation or export.
ReplaceText, preparation, JSON and the scenario runner remain deferred. Existing
Project/EditOperations APIs are unchanged. The UI session now delegates history to the engine;
UI move/size/rotation/mirror, alignment, deletion, duplication, ordering, visibility, recolor and
hoop actions now dispatch these same commands. Existing geometry/snap helpers prepare transforms;
UI-only adapters preserve historical reorder clamping and invalid-color no-ops without weakening
the strict tool reducer. Text replacement/style preparation and imports still use snapshot commits.

```kotlin
val command = ProjectCommand.Batch(listOf(
    ProjectCommand.Duplicate(sourceId = "source", newId = "copy"),
    ProjectCommand.SetTransforms("copy", Transform(x = 50.0)),
))
when (val result = apply(project, command)) {
    is CommandResult.Applied -> if (result.changed) history.commit(result.project)
    is CommandResult.Rejected -> showError(result.code, result.path)
}
```

`TransformValues` accepts unvalidated numeric inputs so zero scales and nonfinite fields return
`INVALID_TRANSFORM` through the reducer, rather than throwing in the existing Transform constructor.
`SetTransforms(id, Transform(...))` is a convenience for already validated callers. Coordinates
use the existing centered 0.1mm units; no new scaling/hoop limits are introduced.

- Explicit IDs are mandatory. Missing/blank/colliding IDs and bad thread/reorder indices reject.
- Reorder indices refer to the final current list: its indices are valid; its size is not.
  The older Project.reorder helper still clamps, unchanged.
- Duplicate retains the source transform unless an explicit replacement transform is supplied;
  there is no implicit offset or random ID.
- Recolor matches the existing app value operation: base thread only for text; imported patterns
  keep stitches/metadata/warnings, clear stale source bytes and mark threads explicitly edited.
- Nested Batch paths are zero-based child indices; root errors have an empty path. Rejected.project
  always refers to the original input even if earlier nested commands succeeded privately.
- Applied.changedIds includes final content/existence/list-position changes. A hoop-only change
  has changed=true with no changed IDs. An equal final Project returns the original reference,
  changed=false and no changed IDs; an unchanged commit therefore preserves History redo.
- Commands use Project/History's existing immutable-value ownership contract. Do not mutate
  supplied collections/source byte arrays after dispatch. No deep-freezing/asset resolver is added here.
- Successful editing does not authorize sewing. Unfinished/outside-hoop compositions remain editable;
  the existing exact placement, machine and PES/sewout checks still govern export.

Validation: `./gradlew :engine:test --tests '*ProjectCommandsTest'`.
Tests cover target/ID errors, every invalid transform field, reorder edges, recolor source migration,
empty/fallback thread slots, no-op identity/redo, nested rollback/failure paths and one-commit batch undo.

## Shared EditSession (engine only)

The engine `edit.commands.EditSession` wraps the existing History. Android EditorSession now
delegates published state/history to it while retaining its local pointer-preview draft. Its
`history` property is a read-only snapshot facade for existing count readers, not another History.

```kotlin
val session = EditSession(project)
val baseRevision = session.revision
val commands = listOf<ProjectCommand>(
    ProjectCommand.Duplicate("source", "copy"),
    ProjectCommand.SetTransforms("copy", Transform(x = 50.0)),
)
val result = session.executeTurn("agent-turn-id", baseRevision, commands)
// Only a Committed result publishes a candidate and calls History.commit once.
```

Incremental equivalent: `beginTurn(transactionId, expectedRevision, commands)`, then `applyNext(id)`
for each declared command, then `commitTurn(id, expectedRevision)`. Declare the full command body at
begin so reuse of an ID with different content can be rejected, including nested batches.
InProgress results contain progress counts, not the candidate Project. `snapshot()` exposes only
published Project/revision/history counts/turn state. `cancelTurn(id)` discards the candidate.
An incomplete commit also aborts without publishing intermediate commands. After abort, subsequent
calls for that turn return the recorded failure/cancellation; they cannot resume the candidate.

Revision is session-local and monotonic on successful changed commits and effective undo/redo;
it does not count undo depth and does not roll back with history. No-op turns/history operations
keep the token and redo. Wrong revision, wrong transaction and active-turn conflicts return typed errors.
Both undo and redo are refused while a turn is active. Transition methods synchronize access to
private state; do not expose History for mutation outside the session.

Receipt rules:

- Started turns retain committed, rejected-command/incomplete/stale-commit, or cancelled outcomes
  for the session's lifetime. A matching retry returns that same result object, even if later undo/redo
  has changed the live Project. Historical receipt snapshots are not instructions to overwrite live state.
- A retry must use the original base revision and the same declared command values. Reusing the ID
  with another body/base revision returns TRANSACTION_ID_REUSED. Re-decoded stitch payloads compare
  source stitches/threads/metadata/warnings and byte contents, not EmbPattern/array reference equality.
- Begin validation/busy/conflict errors do not start a turn or reserve its ID. Wrong-ID operations do
  not abort another active turn. Repeated begin with identical active input returns current progress.
- Command-list/map/set containers are captured. Object payloads still follow Project's immutable-value
  ownership contract; mutating an object's source arrays/collections after dispatch is unsupported.
- Receipts are not persisted or silently evicted. Constructing a session for another document resets
  revision/receipts/history. Durable restart-safe IDs, receipt retention budgets, preparation, JSON and
  transport-level command streaming remain later integration work.
- Cancellation is explicit: coroutine/worker owners must call cancelTurn when they abandon incremental
  work. Cancellation after a recorded completion returns that completion and does not implicitly undo it.

A rejected command reports a zero-based failure path from the turn's command list through nested
Batch children, with its Project reset to the published baseline so failed candidates do not leak.

Validation: `./gradlew :engine:test --tests '*EditSessionTest' --tests '*ProjectCommandsTest'`.
Session tests cover atomic publication, single-step undo, redo-preserving abort/cancel/no-op,
revision checks, replay after undo, changed-body ID conflicts, value-equivalent imports,
wrong-owner/incomplete-turn handling, captured command containers and the existing history bound.

### Existing editor snapshot compatibility

`commitSnapshot(next, expectedRevision)` is a trusted host adapter for the existing UI's prepared
Project values, not a new ProjectCommand or a future JSON/tool escape hatch. It rejects stale
revision/active turns and uses the same History/revision as command turns. It retains the previous
History equality/reference behavior without interpreting text settings or regenerating stitches.
UI gesture frames stay outside this engine session until pointer-up, preserving old enablement,
late-pointer rejection and exact final-preview identity. Empty/equal-value gestures preserve redo.
Document opening/recovery still construct a fresh EditorSession through unchanged EditorDocuments;
save baselines, dirty comparison and recovery draft storage are unchanged.

Typed UI edits run `apply` before committing through this same history bridge. Gesture completion
builds one transform/order Batch from the published baseline and final preview, runs the reducer,
and checks exact Project equality before retaining the preview reference. Thus caches and the
previous undo/reference behavior survive while tool and UI mutation/validation share the reducer.
There is no second UI mutation reducer. Shared text preparation remains a separate migration step.

`EditorCommandParityTest` was run before replacing the ViewModel call sites (18 tests, zero failures).
It compares the previous UI expressions with commands; recolor additionally compares complete
Project JSON because EmbPattern uses reference equality. `EditorCommandSessionTest` covers shared
command dispatch, rejected-batch rollback, no-op redo preservation and mixed gesture batching.

Before this migration, EditorSessionMigrationTest's characterization cases were added and passed
against the old app session. The same tests run after migration, including document redo reset,
failed-read retention and recovered-draft dirty state.
