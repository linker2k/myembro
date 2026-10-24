# Free-design directory / shared import integration notes

## Current implementation and integrator tasks

Implemented after merging `main`'s `e0d2c32`. MainActivity was not modified.
The public destination is `FreeDesignSourcesScreen(onBack: () -> Unit)`, called inside the enclosing
`MyembroTheme`. Connect this destination to the Home/design-list 'Find free designs' action
and pass the screen stack's back callback.

The screen reads validated JSON offline and opens sites and terms in Custom Tabs.
It does not newly bundle or automatically download actual designs/site images/logos.
Browsers without Custom Tabs open a regular browser view; if no browser is installed,
a snackbar is shown. Uses AndroidX Browser 1.8.0.

SEND / SEND_MULTIPLE filters were added to the Manifest, but **MainActivity currently handles only VIEW.**
The integrator must wire the following path into `handleIntent` for shared import to work.
Both `onCreate` and the existing `onNewIntent` must pass through this handler.

1. Obtain SEND URIs with `IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)`;
   for SEND_MULTIPLE use `IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)`.
   For some sharing apps, also combine each `getItemAt(i).uri` from `intent.clipData`, deduplicating URIs.
   Do not download HTTP strings/EXTRA_TEXT links. Handle only `content://` URIs.
2. While temporary read permission remains valid, pass each URI on `Dispatchers.IO` to
   `DesignLibrary(context).importShared(uri): List<File>`. A single ZIP can produce
   multiple files, so do not pass it to the existing single-result `importForViewing`.
   Use this new API for ZIPs selected in the ordinary file picker as well.
3. Accumulate successful results per URI and show errors for failed URIs. Imports are independent per URI,
   so a later URI failure must not erase an earlier URI's success.
4. Call `LibraryViewModel.refresh()` after success. For one resulting file, open the viewer with existing
   `openDesign(file)`; for multiple files, navigate to the design list.
   Do not send errors or ZIPs to the existing `viewer.open(uri)` fallback.

Core call example:

```kotlin
val stored: List<File> = withContext(Dispatchers.IO) {
    DesignLibrary(applicationContext).importShared(uri)
}
library.refresh()
// 한 개: openDesign(stored.single()); 여러 개: LIBRARY 목적지
```

## Import contract

The Android adapter `DesignLibrary.importShared` queries display name/MIME, opens a stream,
and passes it to pure JVM `DesignFileImporter(directory).import(input, fileName, mimeType)`.
Tests/non-Android callers can use the latter directly. The caller closes the stream with `use`.

- Also checks ZIP magic, recognizing `application/octet-stream` / ZIPs without extensions.
- Reads only internal `.pes` / `.pec` / `.dst` files(case-insensitive). Does not recursively extract nested ZIPs.
- Discards both `/`·`\` paths and uses only filenames. Replaces control/reserved characters
  and limits filename length. Ignores `__MACOSX` and `._` files.
- Default limits: 16 MiB per file, 64 MiB total extracted data, 64 MiB source data read,
  ZIP entries 512, designs 100. Ignored items also count toward byte/entry limits.
- Does not trust ZIP header sizes; counts bytes actually read and reads unsupported files
  with a bounded buffer. Entry CRC/read errors fail the import.
- Writes all candidate designs to temporary files, validates them with `PatternReader`, then publishes them.
  On an intermediate failure, removes new files published from that URI and temporary files.
- Duplicate filenames are saved as `name (1).pes`, without overwriting existing files.
- ZIPs without designs, invalid files, and exceeded limits return exceptions. The UI reports failure.

## Validation

`DesignFileImporterTest`: actual PES/PEC/DST, Unix/Windows path traversal, duplicate names,
generic-MIME ZIPs, long Unicode names, full rollback on invalid designs, compression bombs in ignored files,
input/extracted-size limits, entry/design-count limits, empty/corrupt ZIPs.

`FreeDesignSourcesTest`: Korean/English directory text/check dates/formats/terms, duplicate IDs,
rejection of unsafe URLs and unsupported schema versions. Network checks are excluded from offline unit tests;
they are recorded in the [source document](free-design-sources.md) and JSON evidenceUrls.
