# Editor architecture (shared contract for parallel work)

Status: 2026-10-22. Owner of this contract: Claude (coordinator). Agents may **add** to these
types; changing an existing signature needs a note in the PR/commit message.

## Engine (`engine/.../engine/edit/`)

- `Project(hoop, objects)` — immutable; edits return a new `Project` (undo = keep old values).
- `DesignObject` (sealed): `id`, `name`, `transform`, `visible`, `localPattern()` centered on
  (0,0), `placedPattern()` in hoop coordinates.
  - `StitchObject` — imported PES/PEC/DST (recentered).
  - `TextObject` — **lettering agent** adds it (text, font, height, spacing, arc, thread).
- `Transform(x, y, rotationDeg, scaleX, scaleY)` — scale/rotate about the object center, then
  translate; negative scale = mirror.
- `Project.flatten(): EmbPattern` — sewing order = list order; trim between objects, color
  change only when the thread changes. Writers (PEC/PES) take this result.
- **Model agent** adds: `History` (undo/redo), alignment/distribution helpers, duplicate,
  hoop-fit checks, stitch-density warnings when scaling (> ±20 % warns), project
  serialization (`.myembro` JSON) and tests.

## App (`app/.../ui/editor/`)

- `EditorViewModel` holds `History<Project>` + selection.
- `EditorScreen`: hoop canvas (pinch/pan), objects drawn from `placedPattern()`, tap to
  select, drag to move, two-finger rotate/scale, handles; layer list (reorder = sewing
  order, visibility); bottom toolbar: add design (from library), add text, duplicate,
  mirror, rotate 90°, align center, delete, undo/redo; save project; export (PES → library/USB).
- Entry points: library "Create" tab, viewer "Edit" action.

## Ownership (one agent per area, separate worktrees)

| Area | Agent | Files |
|---|---|---|
| Editor model | embro-model | `engine/.../edit/**` (+ tests) |
| Editor UI | embro-editor | `app/.../ui/editor/**`, strings `strings_editor.xml` |
| Lettering | embro-letter | `engine/.../lettering/**`, `engine/.../edit/TextObject.kt`, font assets |
| Save/share + UI polish | embro-worker | `ViewerScreen`, `LibraryScreen` |
| Blocking review + mockups | embro-codex | read-only |

## Editor host hooks (2026-10-22)

- `EditorRecoveryDialog(vm, onRestored, onDismiss)` can be placed beside Home after process restart;
  `onRestored` should navigate to the editor. It only renders when `vm.recoveryProject != null`.
  The editor also offers recovery on entry. Dismissal keeps the recovery snapshot; Discard deletes it.
- `EditorScreen(vm, onBack, multiPositionPositions = confirmedPositions,
  onMultiPositionExport = { project, positions -> ... })` preserves existing two-argument calls.
  The optional export callback runs after the user accepts the position instructions, and after splitting
  and engine machine preflight have succeeded for every part. The host writes the actual files.
- Repositionable profile hoops expose their full coverage in the hoop picker. Confirmed `SewingPosition`
  coordinates are drawn as numbered translucent windows. No SA434 offset is inferred from frame size;
  absent/invalid positions or an absent export callback disable position export. Single PES export still
  rejects a coverage-only hoop. Editable `.myembro` saves preserve unfinished or oversized compositions.

## Transform rendering/cache contract (2026-10-24)

Text geometry is shared across `withTransform/withId/withVisible`; content/style copies invalidate it.
`TextObject.placedPattern()` caches the original scale-only placement/S1 result and maps rigid poses
without regenerating stitches. Gesture Canvas draws cached arrays through relative matrices; scale
render work is deferred 300ms after pointer-up. No serialized field/signature changed. Details and
exact-output tests: [editor transform performance](qa/editor-transform-performance.md).
