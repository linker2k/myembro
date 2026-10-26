# AI work project (AiWorkspace)

On a phone, the AI app is in front and myembro is behind it. Before this change, the relay
only reached the editor while the editor screen was composed. Once Android cleaned up the
editor (screen disposed or Activity destroyed), every tool answered "no design is open".

## What holds the project

- `AiWorkspace` (app, `ui/editor`) is a process-wide `ViewModelStoreOwner`. It owns the one
  `EditorViewModel`, which holds `EditorDocuments` → `EditorSession` → engine `EditSession`
  and its undo history.
  - `MainActivity` takes its editor from `AiWorkspace` instead of `by viewModels()`.
  - The ViewModel outlives the Activity. `RelayService` is a foreground service and keeps the
    process alive while "AI connection" is on.
- `RelayService` always calls tools on `AiWorkspace.relayDocument(app)`. This is the same
  editor whether it is on screen or not: the editor's own project, revision, kernel, photo
  tools, fonts and sewout check. There is no second session.
  - The viewer no longer takes over the connection.
  - `RelayProjectSource` (attach/detach while a screen is shown) is removed.
- **Editor open:** the AI edits the session the screen draws, so an edit shows at once. This
  includes split screen and multi-window.
  - Every AI edit is one history step, so undo works from either side.
- **Editor closed:** the AI edits the same session. Opening the editor shows the project with
  its undo history.
  - Tapping the "AI 연결" notification opens the editor on this project and never replaces it
    (`AiWorkspace.ACTION_OPEN`).
  - Leaving the editor with Back still starts a new empty project on the default hoop, after
    the usual unsaved-changes question. That empty project is then what the AI works on.
- **Unsaved changes:** these keep the existing autosave, `EditorDraftStore`
  (`files/drafts/editor.myembro`), written on every change.
  - After a process restart, the first tool call waits for the draft read. If a draft is
    pending, the call takes it as the last project: the recovery dialog's **Restore**, with
    the same rules (`EditorViewModel.relayReady`). No one may be there to answer the dialog
    while the AI app is in front.
  - Undo history does not survive a restart, as before.
- The `remove_objects` question is shown from `MainActivity`, so it appears on any screen.
  It still comes with the heads-up notification.

`relayReady` waits on a `CompletableDeferred`, not `snapshotFlow`. Without a composition,
nothing sends Compose apply notifications, so `snapshotFlow` never re-emitted. The first
5558 run hung on exactly this.

## Verification (5558, 2026-10-26)

`AiWorkspaceInstrumentation` has no personal image (it uses a synthetic SVG and text) and goes
through the service's own provider:

```text
CLOSED PASS: no Activity; import_picture + add_text ok, sewout PASS; 2 objects, 2 undo steps
OPEN PASS: same EditorViewModel; screen shows 2 objects; on-screen undo removes the text, redo restores
ACTIVITY_GONE PASS: Activity destroyed; AI work project and history kept
RESTART PASS: new editor recovers the autosaved draft before the first call ([Smile, 안녕]); tools keep working
AI_WORKSPACE_PASS
```

Device run with local `wrangler dev` (no deploy or login):

1. App on Home with the editor not open: import_picture (SVG smile) and add_text "안녕" both
   succeeded, sewout PASS, revision 2.
2. Real process restart (reinstall): Home showed the recovery dialog. The first relay call
   (`get_project`) restored the draft and returned [스마일, 안녕].
3. Notification tap: the editor opened with "객체 2개".
4. Split screen (Chrome on top, myembro editor below): add_text "AI" over the relay. With no
   touch, the editor showed "객체 3개" and undo was enabled.

Screenshots: `/tmp/myembro-agent-shots/relay-session/`.
