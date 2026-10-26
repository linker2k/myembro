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

## Showing AI changes, and reconnecting fast (relay5)

On a phone, Claude added a turtle and nothing on the phone showed it. Every successful AI connection
change now reaches the user in one of three ways (`EditorViewModel.relayChanged`):

| myembro state | What the user sees |
|---|---|
| Editor on screen (resumed, split screen included) | A short snackbar in the editor |
| MainActivity started, editor not shown, at rest (home or library, nothing open) | Switch to the editor, then the snackbar |
| MainActivity started, busy (a sheet or dialog, a recovery or removal question, another task screen) | A top banner with **보기** and close. It is its own non-focusable window, so it stays above dialogs and sheets. |
| In the background | One notification (`RelayChangeNotification`). Later changes update it with a count. A tap opens the editor (`AiWorkspace.ACTION_OPEN`). |

- **Notification grouping:** the change notification has its own group. When Android bundled it
  with the "AI 연결" notification, a tap on the bundle opened Home (5558).
- **Background:** Android does not let a background app bring itself forward, so the notification
  is the only way there.
- **Home card:** Home and the Create sheet show "AI 작업 중인 도안 열기" when the AI work project
  has objects.

**Reconnect**
- Backoff is 1, 2, 4 and 8 s, then a 10 s cap (it was 60 s).
- An app ping not answered within 10 s ends the socket (`RelayProtocol.PONG_TIMEOUT_MS`).
  Before, only 90 s of silence did.
- `RelayService` calls `RelayClient.kick()` on network return (default network callback) and on
  screen-on or unlock. That skips the backoff wait, or checks an open socket at once.

Measured on 5558 with a local relay stopped for 30 s and started again, two runs per build:

| Build | App reconnected after relay ready | First successful call after relay ready |
|---|---:|---:|
| 171f07b (60 s cap) | 31.6 s, 31.6 s | 40.8 s, 32.6 s |
| relay5 (10 s cap) | 3.6 s, 3.6 s | 4.8 s, 3.8 s |

The 101 s seen on the phone matches a socket that was dead but not closed: 90 s of silence plus
backoff. Locally the relay closes its sockets, so `anUnansweredPingEndsTheSocketWithinThePongTimeout`
covers that case instead.

Tests:
- `AiChangeInstrumentation`: VISIBLE, BUSY and BACKGROUND.
- `AiWorkspaceInstrumentation`: NOTIFY and SHOWN.
- `RelayClientTest`: the 10 s cap, the pong timeout and kick.
