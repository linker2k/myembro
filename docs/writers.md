# PEC / PES v1·v6 output

`PecWriter.kt` ports `PecWriter.py`, `PecGraphics.py`, and `EmbThread.py` from
pyembroidery **1.5.1** to Kotlin. `PesWriter.kt` provides the `write_truncated_version_1`
path from `PesWriter.py`, and `PesV6Writer.kt` provides the full `write_version_6` path.
The originals are MIT-licensed; the notices are included in
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

## Usage

```kotlin
val pec: ByteArray = PecWriter.write(pattern)
val pesV1: ByteArray = PesWriter.write(pattern) // 기존 기본값 유지
val pesV6: ByteArray = PesWriter.writeV6(pattern)
val selected: ByteArray = PesWriter.write(pattern, PesWriteSettings(PesVersion.V6))
// 파일에 기록될 명령·좌표·팔레트 색상을 사전에 확인할 때:
val normalized: EmbPattern = PecWriter.normalize(pattern)
```

The existing public `EmbPattern` API and the behavior of the single-argument `PesWriter.write`
are preserved. Input lists and thread lists are not modified either. Coordinates use the existing
0.1mm units, with +y pointing downward; no origin translation, axis reversal, or automatic hoop
fitting is performed. The name is stored in the PEC `LA:` label.

## Normalization and limits

- Long STITCH/JUMP commands are split along a straight line so that displacement on each axis
  is **-2047..2047**. Intermediate points use ties-to-even rounding, like Python `round`, and
  the original endpoint is preserved. The signed 12-bit value -2048 is also conservatively split.
  Overflow is not hidden by masking bytes.
- STOP is written as a duplicate palette entry for the current color and a `FE B0 02/01`
  color-change command. On reading, the existing duplicate-color restoration recovers STOP.
  Only a thread-changing COLOR_CHANGE advances through the original thread list.
- A standalone TRIM is written as PEC's combined TRIM+JUMP(0,0) command. If the next command
  is JUMP, it is combined with that first movement. Ordinary JUMP commands carry only the JUMP flag.
- TRIM/STOP/COLOR_CHANGE/END commands whose coordinates change first move to that position
  using JUMP. Input after the first END is ignored; if END is missing, it is added at the final position.
- For color/STOP blocks without stitches, the boundaries and unused threads are removed while
  movement is preserved. COLOR_CHANGE to the same thread becomes STOP. Thread identity is
  determined by RGB, description, catalog, brand, and chart information, so different catalog threads
  remain separate even when their RGB matches. No arbitrary needle stitches are inserted.
- Used threads are quantized to the PEC **64-color** palette. Metadata of unique threads is also
  distinguished. Exact palette matches are secured first; the remaining threads are assigned, in input
  order, to the nearest available colors. This uses the original red-mean distance and the rule that
  later indices win ties. The unique mapping prevents different threads from merging into the same
  color and becoming STOP. More than 64 unique threads are rejected.
- If thread information is insufficient, the existing `threadAt` default palette is used. Unused extra
  threads are not saved. Even an empty design records one default thread color. This interoperability
  choice avoids the pyembroidery 1.5.1 reader's incorrect interpretation of the no-color marker
  `0xFF` as 256 colors.
- Including STOP duplicates, the limits are **255 color blocks**, width and height of
  **65535 units (6553.5mm)** each before and after normalization, and a **24-bit length** for
  the stitch block. These are file-field limits; actual machine hoop/sewing limits must be checked separately.
- PEC and PES v1 cannot store custom RGB or details such as thread descriptions and brands.
  As in the original, PEC names are limited to the first 8 characters, and non-ASCII/control
  characters are replaced with `?` to keep the header exactly 512 bytes. PEC/v1 output no input
  metadata other than the name. The v6 behavior is described below.

## Differences from pyembroidery

- The original `pec_encode` omits TRIM, converts JUMP after the first command into trim/jump,
  and inserts 0-distance STITCH commands after some movements. This implementation
  distinguishes explicit TRIM from JUMP and adds no STITCH commands. Therefore, **its file
  bytes are not identical to those of the original writer**.
- The original unique palette is affected by `set(threadlist)` order and thread-metadata equality.
  This implementation uses a unique palette determined by thread-information identity, exact-color
  priority, and input order. When multiple custom threads collide, quantization may differ from the
  original writer.
- END is explicitly the two bytes `FF 00`. The original writes one `FF` byte and lets the first
  thumbnail `00` be read as the next byte; this implementation makes termination independent
  of the thumbnail.
- v1 writes only `#PES0001`, a **22-byte minimal header**, and the PEC payload that follows.
  v6 writes `#PES0060`, the full header, `CEmbOne`/`CSewSeg`, PEC, and the v6 addendum.
  It does not reconstruct arbitrary original high-level editing objects.
- Thumbnails use the same 48×38, 1-bit graphics with 6 bytes per row as the original. One image
  covers the whole design, and one covers each color block, including STOP duplicates. The original
  hoop border, point-coordinate scaling, bit order, and floating-point operation order are used.
  Translation is calculated first to avoid different floor results at pixel boundaries.
  Rendering does not fill between line segments.

## Reproducing verification

```bash
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test
/path/to/pyembroidery-1.5.1-venv/bin/python tools/check_writer.py
```

If tests are already up to date but verification output is missing, regenerate it with
`:engine:test --rerun-tasks`. The only external package required is **pyembroidery==1.5.1**.
Output goes to `engine/build/writer-check/` and is not included in Git.

`WriterParityTest` reads the existing 12 fixtures (3 designs × PEC/PES v1/PES v6/DST), writes
them in both formats, and reads them again. Commands and coordinates are preserved except for
splits and 0-distance JUMPs required by normalization. Existing PEC palette colors are unchanged;
v6 custom colors are compared against the verified nearest PEC colors. Separate inputs check long
positive/negative movements, the -2048 boundary, multiple STOPs, standalone TRIM, empty designs,
palette collisions, all 64 colors, control commands with coordinates, and input after END. Unit tests
also verify color-block/size limits, rejection conditions, golden bytes for short/long coordinates,
and input immutability.

`tools/check_writer.py` reads both the **original input and expected results** left by the Kotlin
tests. Python independently calculates normalization and color mapping, reads the Kotlin files with
pyembroidery, and compares commands, coordinates, and colors. It also checks PES offsets, color
tables, dimensions, 24-bit block lengths, and END positions, and regenerates thumbnails with the
original `PecGraphics` for **byte-for-byte** comparison.

`PesV6WriterTest` rewrites the same 12 fixtures plus empty designs, long movements, TRIM/STOP,
different threads sharing RGB, empty color blocks, UTF-8, and 255 blocks as v6. Cross-verification
compares custom RGB and commands, checks the full v6 header/thread table/color addendum, and
compares `CEmbOne`/`CSewSeg` and the object tree byte for byte with the original 1.5.1
`write_pes_blocks` output. Unit tests also check splitting sewing sections exceeding 65,535 points,
UTF-8 boundaries, input immutability, settings selection, and confirmed Preflight boundaries.

Results of this verification: **133 tests passed** in `:engine:test` on JDK 17, and **59 Kotlin
outputs** were cross-verified with pyembroidery 1.5.1 (19 v6 + 40 PEC/minimal v1).
The existing app's `:app:compileDebugKotlin` also passed.

## v6 information and differences from the original

v6 stores custom thread RGB, descriptions, catalogs, brands, charts, and the name/category/author/
keywords/description. STOP inserts duplicate threads in the header and is restored as STOP from
PEC. Empty designs store valid PEC without sewing objects. Coordinate units and axes match v1.

Unlike the original, UTF-8 string lengths record the **byte count**, rather than the character
count, and are truncated at a code-point boundary at 255 bytes. The reader also reads PES strings
as UTF-8. The signed 16-bit limit on v6 sewing coordinates is checked, and continuous sections
exceeding 65,535 points are split into separate sections sharing an endpoint. Actual PEC commands
use the common normalization, preserving explicit trims and avoiding additional needle stitches.

App version-setting/warning integration notes and confirmed SE400 limits are in
[machines.md](machines.md). Default v1 behavior is retained, and SE400 v6 support still requires
confirmation on the actual machine.

## Remaining verification

Actual machine recognition and sewing, size, orientation, colors, STOP, jump, and trim behavior
have not yet been verified. In particular, successful reading alone does not guarantee compatibility
of the minimal PES header with each editor/machine. File-size limits are checked, but the current
implementation holds the entire pattern and output in memory, so memory use and cancellation
for large output on mobile remain follow-up work.
