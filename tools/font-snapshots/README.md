# Android font snapshots for headless JVM replay

Run from a clean development checkout with **emulator-5556** booted:

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 tools/font-snapshots/capture.sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*AndroidFontSnapshotTest'
```

The script explicitly installs on 5556 only. It installs the current debug app/test APKs,
retains app data, clears only its own `files/font-snapshots` output, and runs
`FontSnapshotInstrumentation`. Do not use it on release 5554 or canvas 5558.
The default destination is `engine/src/test/resources/font-snapshots`; pass an output
path to capture elsewhere for comparison without replacing pinned fixtures.
No Ink/Stitch/native reference code, network downloads or extra font packages run.

For all three bundled TTFs (Gowun Batang, Jua, Noto Sans KR), the instrumentation captures:

- Each distinct character in ` 자수봄날정민안녕H?ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789`.
- Per-code-point 1000px advance, ordered +y-down contours with the **same half-advance
  correction** as LoadedFont, coverage, and the actual reference glyph's ink height.
- Existing 128px binary rasters, 8px padding, dimensions and the exact raster-to-source
  transform. Binary pixels are 0=ink/255=white, top-down rows, base64 inside JSON.
- Android fingerprint/API, font SHA-256, extractor revision and face selection. Noto's
  normal outline default and explicit `myembro_sans.xml` wght=400 satin family are
  separate variants; the other fonts also capture both roles explicitly.
- Six **actual app** `renderLettering` PES outputs, Jua/Noto × 정민/안녕/Ab12 at 15mm,
  automatic stitch method, 1mm spacing, centered, default 9mm line gap, black 40wt,
  default `SewingOptions()`. Cases and selected methods are saved in `cases.json`.

The engine's `TtfFontSnapshotCodec` reads/writes versioned UTF-8 JSON without platform
classes. Gzip is a host storage envelope. Supply the decoded `outline` to `prepareText`;
for Hangul satin, replay its rasters through `CenterlineGlyph`, measure widths from
its contours, lay out by glyph advances/ownership and provide `CenterlineSatinData`.
`AndroidFontSnapshotTest` demonstrates the exact current app assembly and compares
object JSON for all 6 cases and satin PES bytes for the 4 Hangul cases against independently
captured app outputs, not a JVM-generated oracle.

Only the finite captured subset is supported. Missing glyphs fail lookup; snapshots
do not parse a TTF or enable unrecorded characters/weights/axes. No claim is made that
Java2D or another independent rasterizer produces Android-identical geometry. Updating
fonts, Android/Skia or extraction settings requires explicit recapture/review. JSON
numbers preserve Double round trips; binary rasters preserve pixels exactly.

`collect.py` copies the existing full OFL-1.1 notices for each font, pins file hashes
and records compressed/uncompressed sizes in `manifest.json`. All three existing
licenses allow redistribution with copyright/OFL notices; no font is renamed or
sold separately. Source paths, copyright and upstream links remain in
`THIRD_PARTY_NOTICES.md`. The snapshots are reproducible public-font-generated test
inputs; they contain no uploaded/personal design files. Keep the notices with them.

## Observed limitation: existing FILL nondeterminism

All 6 captured app TextObject JSON payloads match JVM preparation exactly. Jua and
Noto Hangul satin PES match byte-for-byte (4 cases); Jua Latin FILL also matched in
the initial diagnostic. Noto default/Thin `Ab12`, 15mm FILL did not: observed PES
sizes were 2934 and 2942 bytes. A second Android capture and JVM run exchanged
those sizes despite identical prepared object JSON. This is evidence of downstream
FILL nondeterminism, not missing snapshot precision. The cause is **unconfirmed**;
no fill-routing algorithm or production default is changed in this snapshot task.

The default regression asserts all 6 object JSON payloads and 4 satin PES files.
The 2 extra FILL PES files remain pinned for explicit investigation. To include
FILL in strict byte assertions (can fail; not a passing parity claim):

```sh
MYEMBRO_SNAPSHOT_FILL_PARITY=1 ./gradlew :engine:cleanTest :engine:test --tests '*AndroidFontSnapshotTest'
```

Do not relabel the FILL mismatch as equivalent or add a coordinate tolerance.
