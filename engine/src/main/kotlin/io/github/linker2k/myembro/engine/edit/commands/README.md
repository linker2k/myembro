# Project command reducer

`apply(project, command)` wraps existing edit-model value operations. It performs no I/O,
font preparation, stitch generation, history management, random ID allocation or export.
The UI is not connected in this first implementation; ReplaceText, preparation, JSON and the
scenario runner are deliberately deferred. Existing Project/EditOperations APIs are unchanged.

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
