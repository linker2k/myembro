# Reading thread colors (PES)

2026-10-22. User report: "Imported design files show different colors." These were completely
different colors, rather than approximations.

## Causes

PES v5 and later store the actual thread list (RGB, catalog number, description, and brand) in
the header, while the PEC block contains only Brother 64-color palette numbers. Two defects overlapped.

1. **UTF-16 strings.** Header strings use the MFC CArchive format. A length byte of `FF` is
   followed by a u16 length; `FFFE` indicates a UTF-16 string (`FF FE FF` + character count +
   UTF-16LE). Recent PE-Design versions write thread-list strings in this format. Our reader
   and pyembroidery 1.5.1 read `FF` as a 255-byte string. This shifted the position from the
   first string onward and caused UTF-16 character bytes to be used as RGB. The version-specific
   skip offsets (such as 38/26 for v8) were correct. If the thread count happened to match,
   garbage colors were used without even a warning.
2. **Thread-list order.** The thread list can differ from block order (reverse order or thread
   reuse). When the thread count was at least the block count, threads were assigned sequentially
   1:1, so colors were swapped in reverse-order files.

In addition, versions 0091·0110 did not have their headers read, and a non-0 count of predefined
fills, motifs, or feathers caused the thread list to be abandoned. Both cases fall back to PEC
approximate colors.

The rendering path (`RenderData` → `Run.color = pattern.threadAt(block).color` → StitchCanvas·
Thumbnails·EditorCanvas) performs no alpha, theme-tint, or sRGB conversion. The reader always fills alpha with FF.

## Reproduction: Brother free design "Summer lemon" (`#PES0080`, 9 blocks)

This is a personal-use design and was not added to the repository. The table below shows colors per block.

| Block | App before fix | pyembroidery 1.5.1 | After fix (header thread) | PEC number |
|---|---|---|---|---|
| 1 | `#650073` purple | `#650073` | `#FFD911` 206 | 58 Harvest Gold |
| 2 | `#0F03B4` blue | `#0F03B4` | `#A8A8A8` 005 | 16 Silver |
| 3 | `#B3021E` red | `#B3021E` | `#F0F0F0` 001 | 29 White |
| 4 | `#02D804` green | `#02D804` | `#662815` 157 Brother Fleshtone | 41 Dark Olive |
| 5 | `#021B04` | `#021B04` | `#F64A8A` 086 | 25 Deep Rose |
| 6 | `#7C0267` | `#7C0267` | `#A8DDC4` 542 | 31 Seacrest |
| 7 | `#02D804` | `#02D804` | `#662815` 157 (reused) | 41 Dark Olive |
| 8 | `#02B903` | `#02B903` | `#F993BC` 085 | 43 Pink |
| 9 | `#2E030D` | `#2E030D` | `#FFF08D` 812 | 34 Cream Yellow |

Before the fix, the emulator showed a purple lemon, red outlines, and green glasses. After the
fix, it shows a yellow lemon, white/gray outlines, teal glasses, and pink lenses.

## Sample comparison (186 files)

The comparison covers the following two collections.
- [EmbroidePy/samples](https://github.com/EmbroidePy/samples) `6e65f1ae`, MIT, 73 PES files.
- 113 Brother sewingcraft free designs (analysis only in /tmp).

Number of files where more than half the block colors were far from the PEC palette colors
(farther than a difference of 0x40 in each of red, green, and blue):

| | Before fix | pyembroidery | After fix |
|---|---|---|---|
| File count | 25 | 24 | 2 |

The remaining 2 files (EmbroidePy `random1-premier-v6/v9`) have normal Brother strings in the
header threads. They also match the pre-fix results and pyembroidery, so the files themselves
record threads different from the PEC numbers. Files with garbage UTF-16 strings decreased
from 24 to 0. EmbroidePy file thread colors match pyembroidery both before and after the fix.

## Changes

`engine/io/PesReader.kt`

- Read strings using MFC CArchive rules: `FF`, u16, `FFFE` (UTF-16), `FFFF` (u32).
  The same rules apply to metadata, hoops, and image names.
- Accept a thread list only when all of the following hold.
  - It contains 1~255 entries.
  - Every string is at most 254 characters, without control characters or U+FFFD.
  - An object table immediately follows: u16 0, or an object count + `FFFF 0000` + length +
    a class name starting with `C`, such as `CEmb…`.
- A list found at a version-specific fixed position is marked "validated". If none is found,
  search the header.
  - Applicable cases: fill/motif/feather definitions, unknown v5+ layouts such as 0091·0110,
    or a shifted position.
  - Search range: from byte 12 to the first object marker or 64KB.
  - Select the longest valid list and mark it "scanned".
- Convert appliqué steps (catalog 100~102, RGB 0, no brand) to PEC 62~64 colors and
  "Appliqué material/position/Appliqué", matching the Brother display.
- There are three candidates for pairing blocks and threads. Select the candidate with the fewest
  block colors far from the PEC palette; on a tie, use the earlier candidate.
  1. CSewSeg color log: each object (CEmbOne) has sections (CSewSeg), with (section, thread
     number) records at the end. Concatenate in object order and merge consecutive duplicates.
     Do not use this candidate if the structure differs from this format.
  2. Thread-list order. If there are fewer threads than blocks, assign the next thread to each
     distinct PEC number (the existing method).
  3. Pair each PEC number with the nearest thread in distance order (handles reversed lists).

  Reasons for this order:
  - The color log accurately distinguishes reuse and similar colors (Madeira Salsa and Cinnamon
    Candy are both PEC Red). However, some files (CGroupObject) have object order different
    from sewing order, so the log loses priority if it is farther from PEC.
  - Among 69 files that could be checked against the color log, rules 2·3 alone matched 67.
- Safeguard: if a "scanned" list has more than half its blocks far from the PEC colors, discard
  it and use PEC colors. The warning is `PES thread list does not match the PEC colors; using PEC colors`.
  A "validated" list is retained even when its colors are far away, because custom RGB threads
  (such as `R51 G37 B255`) can legitimately be far from PEC.
- A v5+ file with no thread list produces the warning `PES thread list not found; using PEC colors`.

`engine/io/PesV6Writer.kt`: limited single-byte lengths to 254. 255 (`FF`) is an MFC extension
marker, causing other programs and our reader to interpret it differently.

`engine/edit/ProjectJson.kt`: when a saved project contains original bytes (`sourceBytes`),
reread them on load. Use the new result if needle coordinates match. Since 2026-10-23, projects
without original bytes also use a filename resolver injected by the app (see the recovery section
below). Manual thread-color changes are explicitly preserved with `sourceThreadsEdited`.
Thumbnails, the library, and the viewer reread the original file each time, without a disk cache.

## Tests

- `PesColorTest`: uses synthetic headers that reproduce the PE-Design v8 structure byte by byte.
  - UTF-16 strings
  - Reversed thread lists (with/without a color log)
  - Two threads sharing a PEC number
  - Appliqué
  - Thread lists after fill definitions
  - Unknown 0110 layouts
  - Discarding scanned results that differ from PEC

  8 of the 9 cases fail with the pre-fix reader.
- Thread colors in 4 real MIT EmbroidePy files (`fixtures/embroidepy/`, brother v5·v6,
  premier v9, wilcom v6) match pyembroidery.
- `ProjectJsonTest`: recovery of colors in projects saved with the old reader, and preservation
  of colors in objects whose thread colors were edited.

## Remaining work (proposals)

- **Formats without color information.** DST, standalone PEC, and PES v1~v4 lack thread colors
  or contain only the PEC palette.
  - Display "No color information — default palette" on the viewer color card.
  - Detection: add an engine marker such as `metadata["colors"] = "palette"`.
  - Allow the editor's thread-color controls (EditorAppearance) to open directly from the viewer too.
- **Thread-name display.** Brother "EMBROIDERY" threads have chart numbers (for example, `58`)
  in the description field. Currently, numbers appear as names on color cards. Combining catalog
  number and brand as "Brother 206", or also showing the PEC name ("Harvest Gold"), would help.
- **CGroupObject.** Decoding sewing order inside grouped objects would allow the color log to
  be the 1st-priority choice for multi-object files as well.


## 2026-10-23: recovering stale thread information in pre-alpha10 projects and autorecovery

On the user's phone, only newly opened PES files were correct. Previously saved `.myembro`
files and `drafts/editor.myembro` still contained misread purple/blue/green colors and U+FFFD
thread names in `sourcePattern.threads`. Existing reinterpretation worked only with `sourceBytes`
and did not apply to snapshots that stored only a filename.

- Added an optional `ProjectSourceResolver` to `ProjectJson.decode(text, codecs, sourceResolver)`.
  The engine does not open file paths directly. It first rereads the embedded original; if absent
  or reinterpretation fails, it passes `sourceFileName` to the resolver. The new result is used
  **only when the count and every x/y coordinate exactly match** the saved commands. Command
  types are not compared, allowing the reader to improve STOP/color-change interpretation.
  Mismatches, missing sources, and read failures open with the existing saved data.
- Bytes from a successfully located file are copied into the recovered object. Subsequent project/
  recovery saves also retain the original, so reinterpretation remains possible even if the file is
  later deleted from the designs folder. Existing files are not overwritten immediately on reading.
  Placement, transforms, visibility, id, and object names are preserved.
- The app's `DesignSourceResolver` looks only for matching filenames in `files/designs`. For old
  absolute paths, only the filename is used; case-insensitive matching is allowed only when there
  is a unique match. Symlinks outside the folder and files larger than 32MiB are excluded.
  Reading uses streams supported on Android 26 as well.
- File opening and SAF URI opening share `EditorViewModel.loadProject` → `readEditorProject`.
  App-start autorecovery through `EditorDraftStore.read` uses the same function and resolver.
- New manual thread changes are saved with `sourceThreadsEdited = true`; this marker preserves
  saved threads even when an original is available. JSON stays at version 1, and the new fields
  are optional. **Older manual recoloring without a marker cannot be distinguished from an old
  parser cache, so it may be recovered to the original threads if an original with matching
  coordinates is found.** RGB values are not inferred for data without an original.
- `EmbThread.displayName/displayDetails` excludes entire fields containing U+FFFD or ISO control
  characters, displaying readable catalog numbers/brands or, if absent, RGB. The editor inspector,
  swatch accessibility names, and viewer color cards use these properties. Original thread metadata
  and identity comparisons are preserved.

Reproduction files were used only under `/tmp/myembro-colors-repro`. An old JSON with incorrect
colors and names was created from the actual lemon file. Both the embedded-original and filename-
resolver paths restored the correct colors for **19,736 stitches·9 threads** with every coordinate
preserved. Personal-use files and JSON containing them are not added to Git.

Regression tests use redistributable **synthetic v8 UTF-16 headers and original coordinates**.
`ProjectSourceMigrationTest` checks original-source priority, filename recovery, coordinate/command-
count mismatches, missing/read failures, byte retention, recoloring markers, and null/invalid Base64.
`EditorSourceRecoveryTest` checks app project files, autorecovery, the designs-folder resolver,
recoloring, missing sources, and path restrictions. `ThreadDisplayTest` checks broken names/brands/
catalogs, control characters, and display of valid Unicode such as Hangul.

Verification: 205 `:engine:test` tests and 115 `:app:testDebugUnitTest` tests passed, and
`:app:assembleDebug` succeeded.
