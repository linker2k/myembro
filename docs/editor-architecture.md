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
- Entry points: library "만들기" tab, viewer "편집" action.

## Ownership (one agent per area, separate worktrees)

| Area | Agent | Files |
|---|---|---|
| Editor model | embro-model | `engine/.../edit/**` (+ tests) |
| Editor UI | embro-editor | `app/.../ui/editor/**`, strings `strings_editor.xml` |
| Lettering | embro-letter | `engine/.../lettering/**`, `engine/.../edit/TextObject.kt`, font assets |
| Save/share + UI polish | embro-worker | `ViewerScreen`, `LibraryScreen` |
| Blocking review + mockups | embro-codex | read-only |
