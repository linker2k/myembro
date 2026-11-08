# JEF, EXP and VP3 reader parity

Reference: pyembroidery **1.5.1**, MIT, the same version as the writers. Reader source SHA-256 values and frozen public output hashes are in `engine/src/test/resources/format-readers/provenance.json`; the license is in THIRD_PARTY_NOTICES.md. No Python runtime is added to the engine/app. Python runs only in a fresh scratch venv with `-I`.

## Scope and input inventory

`JefReader`, `ExpReader` and `Vp3Reader` implement the original reader defaults. JEF reuses the original palette and identity metadata and calls trim inference with `trim_at=None`, `trim_distance=3.0mm`, `clipping=True`. EXP has no signature, thread palette or END bytes. VP3 uses bounded color blocks and the original start-position condition (`x != 0 AND y != 0`), long stitch records and thread strings. Invalid JEF offsets and truncated VP3 blocks throw EmbFormatException rather than reading outside the file.

PatternReader selects VP3 by magic and JEF/EXP by filename. App DesignFormats adds extensions, picker MIME hints and structural checks; headerless EXP can only be checked for a nonempty sequence of byte pairs, not authenticated by magic. ZIP extraction and library listing use that same extension set. PictureIntake routes the extensions (and VP3 magic) to design intake; Android VIEW/SEND/SEND_MULTIPLE accept the new format MIME types. The viewer already uses PatternReader. All patterns remain imported under SewoutCheck.originOf; the existing density policy is unchanged. Tests assert 16 and 30 penetrations allowed, 31 blocked for each new reader. Korean/English import text and manuals list all six readable machine formats.

The full sweep contains **12 existing PES/PEC golden originals** (flower/long_moves/stops, each v1 PES/v6 PES/PEC, plus three existing embroidepy cross-writer PES fixtures) and **136 bundled CC0 PES samples**. Every baseline is decoded by our PesReader, then written by our existing JEF/EXP/VP3 writers, with only the JEF test timestamp fixed. The identical **444 writer byte streams** are independently decoded by our reader and original Python reader. No private design is used or committed.

## Results

| Format | Python-reader exact matches (commands, coordinates, RGB, identity) | PES source exact command sequence | Needle-coordinate differences | Color-boundary coordinate/command differences |
|---|---:|---:|---:|---:|
| JEF |148/148|0/148|0|0|
| EXP |148/148|0/148|0|4 (4 files)|
| VP3 |148/148|0/148|269769 (52 files)|107 (49 files)|

All Python-oracle categories are zero differences. PES-source raw positional comparison totals:

| Format | Coordinate positions | Commands | Indexed color boundaries | RGB entries | Thread identities |
|---|---:|---:|---:|---:|---:|
| JEF |1565955|33969|364|456|514|
| EXP |1565941|33947|370|514|514|
| VP3 |1547377|16380|370|0|485|

These are counts, not distances. Raw comparison pairs commands by index, counts missing entries, and does not align sequences; one inserted JUMP/TRIM shifts later indices and inflates positional totals. Needle coordinates instead compare only STITCH sequences. Color-boundary coordinates compare STOP/COLOR_CHANGE sequences without their list indices. `source-differences.json` retains each of the 444 cases individually; `summary.json` records totals. The Kotlin source regression test recomputes and pins the per-file coordinate/command/indexed-boundary counts. Frozen regressions retain only six public representative sources × three formats, verify current writer bytes, then compare every decoded command and complete thread identity to frozen Python results. Ordinary tests never execute Python or rewrite fixtures.

## Observed losses and existing upstream issues (unchanged)

- JEF preserves all needle and color-boundary coordinates in the sweep, but jump splitting and default trim inference change the command sequence. Palette approximation changes 456 RGB entries; thread identity metadata changes 514 entries. Default writer does not preserve explicit trims independently.
- EXP preserves all needle coordinates. It has no thread colors/identity (514 lost entries); STOP becomes COLOR_CHANGE in four existing golden cases. Jump splitting, trim encoding and implicit END change command positions. No heuristic reconstructs original colors or STOPs.
- VP3 preserves all 514 thread RGB entries, but only supported catalog/description/brand strings survive (485 identities differ). STOP/JUMP handling and added trims differ from PES; 107 color boundaries differ across 49 files. Needle coordinates differ in 52 files, identically in the original Python reader. For example `sample_01_skull.pes` has 8514 needle commands before/after, but at needle 5171 `[14,285]` becomes `[13,284]` and later points remain shifted. The existing writer's per-block coordinate rounding and reader's axis-zero start-position condition are retained, not repaired or hidden by translation/alignment. This is an existing upstream round-trip issue, not evidence that VP3 intrinsically cannot represent the geometry.
- Hoop selection/bounds in JEF/VP3 are not converted into an app HoopSpec; the app keeps its existing viewer hoop selection behavior. EXP has no hoop metadata. Pattern metadata is not claimed to survive these formats.
- Engine coordinates are integer 0.1mm units; the sweep covers integer reader positions from our writers, not every external VP3 producer's possible fractional position. VP3 absolute block positions use the existing integer model and truncate sub-unit values. Broader third-party interoperability remains unverified.

## Reproduction and observed output

Use JDK17 (`/home/gosh2/.jdks/jdk-17.0.20.1+1`). Only related test classes were run; no emulator was used.

```sh
python3 -m venv /tmp/myembro-format-readers-venv
/tmp/myembro-format-readers-venv/bin/pip install pyembroidery==1.5.1
./gradlew --max-workers=4 :engine:exportFormatReaderSamples
# FORMAT_READER_EXPORT golden=12 samples=136 formats=3 files=444
/tmp/myembro-format-readers-venv/bin/python -I tools/format-readers/compare_samples.py /tmp/myembro-format-readers-samples
# JEF oracleMatches=148/148; all oracle differences=0
# EXP oracleMatches=148/148; all oracle differences=0
# VP3 oracleMatches=148/148; all oracle differences=0
python3 tools/format-readers/freeze_representatives.py . /tmp/myembro-format-readers-samples
./gradlew --max-workers=4 :engine:test \
  --tests '*FormatReaderTest' --tests '*ReaderParityTest' --tests '*EmbroideryExportTest' \
  --tests '*JefWriterTest' --tests '*ExpWriterTest' --tests '*Vp3WriterTest' \
  :app:testDebugUnitTest --tests '*FormatReaderImportTest' --tests '*DesignFileImporterTest' \
  --tests '*BatchDesignImporterTest' --tests '*PictureIntakeTest' --tests '*ViewerFormatExportTest'
```

The full JSON/binary sweep and venv under `/tmp/myembro-*` are disposable outputs. Reproduction scripts, public regression fixtures, provenance and this report live in the repository.

Final related-test output:

```text
BUILD SUCCESSFUL in 8s
engine: tests=21, failures=0, errors=0, skipped=0
app: tests=35, failures=0, errors=0, skipped=0
```
