# Batch design import

Connects file opening from Home, the design list, and the viewer, device-file import in the editor, and external sharing to the same process.
System file selection uses `OpenMultipleDocuments`; folder selection uses `OpenDocumentTree`.
Designs can still be added from the editor's existing design list.

- Supported files: PES / PEC / DST / MYEMBRO and ZIP archives containing these files.
- MIME types include `application/octet-stream`, embroidery-specific MIME types, ZIP, and project JSON.
  MIME alone cannot precisely restrict extensions, so extensions, signatures, and actual parsers are checked after selection.
  DST checks for a 512byte header starting with `LA:`. Unsupported/corrupt files are summarized with their skipped filenames.
- Folders include subfolders; visited document IDs prevent duplicate traversal/cycles.
  ZIP paths are flattened to safe filenames; nested ZIP archives are not extracted.
  ZIP limits are 16MiB per file, 64MiB each for input/total extracted data, 4096 entries, and 1000 designs.

## Duplicate choices and cancellation

Compares both SHA-256 and filenames(case-insensitive, after filename sanitization).
If only the name matches but contents differ, preserves the existing file and adds a copy such as `name (1).pes`.
Designs previously given copy names also retain their original names and hashes for comparison on subsequent imports.

If the list already contains the same name and contents, shows **N existing designs** and the full name list,
and asks **Skip / Add as copy / Replace** for each current design.
**Apply to all remaining** applies from the current item onward and preserves earlier choices.
Repeated identical names/contents within the same selection/ZIP are prepared only once and marked as duplicates in the summary.

Validates all inputs in a hidden temporary folder before requesting duplicate choices. **Cancelling during duplicate selection or
preparation saves neither new designs nor replacements.** Saves only after all choices are complete;
originals changed/deleted in the meantime are not replaced. On save errors/coroutine cancellation, deletes new files and
restores replaced originals and duplicate records. The cancel button is disabled during saving.

## Background processing and the editor

Provider queries, folder traversal, validation, hashing, and file saving run on IO, with per-file progress.
Duplicate confirmation waits in the ViewModel, preserving pending choices across screen rotation.
Thumbnails are generated separately after saving; rendering/statistics for many editor objects are computed on Default.

Importing multiple designs in the editor adds independent objects per file, undoable in one action.
Adding a MYEMBRO project merges the visible design into one object; selecting a single
MYEMBRO through project opening preserves editable layers via the existing opening path. Projects can also be
opened from the design list/Home; USB export converts projects to PES. The engine public API is unchanged.

## Validation

```bash
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug
```

JVM: checks mixed formats, ZIP errors and path sanitization, subfolders/cycles, 350 designs, SHA-256 duplicates,
the three choices, copy-name history, full cancellation, cancellation partway through choices, rollback during saving, rejection of originals changed during selection,
per-file editor objects and undo/project round trips.

On the emulator, a separate QA package verified SAF multi-file selection from Home/the design list, inability to select JPEG,
recursive folder/ZIP summaries, duplicate lists, and adding copies with apply-to-all. After cancelling an import mixing
existing designs 3 and new designs, confirmed that design-file and duplicate-record hashes were all unchanged.
