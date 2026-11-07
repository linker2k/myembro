# DST, EXP, JEF and VP3 writer parity

Reference: pyembroidery1.5.1 (MIT), installed into a new isolated venv during stage1. Oracle commands use `python -I`; no Python execution, network access or package installation is part of ordinary Gradle tests. Sources and source SHA-256 are recorded in `engine/src/test/resources/format-writers/provenance.json` and the MIT notice is in `THIRD_PARTY_NOTICES.md`.

## Default behavior

| Format | Max jump / stitch axis delta (0.1mm units) | Full jump | Preserved behavior |
|---|---|---|---|
| DST | 121 /121 | false | Default non-extended512-byte header, trim_at3; extended header remains optional |
| EXP | 127 /127 | true | Original control records and no explicit END bytes |
| JEF | 127 /127 | true | Original JEF palette/red-mean match, repeat-color exclusion, STOP palette toggling and strict hoop selection; trims=false, trim_at3 |
| VP3 | 3200 /255 | false | Original producer strings, block offsets, bounds/center arithmetic, strings, color blocks, skipped jump/stop records and explicit trim on END |

The shared encoder now accepts a distinct stitch limit for VP3; the existing DST/EXP defaults and byte goldens stay unchanged. Only the six commands represented by the engine model are supported; no transforms, extra locks, or application export policy are invented by these low-level writers. Stages1–2 did not include UI integration; stage3 is documented below.

### Nondeterministic or invalid inputs

JEF's default date is the current local timestamp, exactly as upstream. Tests and the comparison CLI alone supply `20000102030405`. The production default is not frozen. The optional JEF trims=true path is also compared to an original golden.

The original empty JEF fixture raises IndexError because it tries to use a nonexistent first thread; the port likewise throws IndexOutOfBoundsException, covered by a rejection test. No fake empty JEF golden is committed. Empty VP3 uses a random filler thread upstream. The fixture generator alone substitutes RGB112233, and the Kotlin test alone injects that same filler into an internal writer entry point. The public VP3 default retains random fallback. All136 bundled comparison patterns have specified source threads; no random override is used in that sweep.

## Full bundle comparison

All136 `.pes` files in `app/src/main/assets/samples/` are repository-owned CC0 bundle assets. Our `PesReader` reads each PES **once** and exports the full absolute stitch/command list, raw thread RGB/identity metadata and pattern metadata as JSON. The JVM writes the four formats from that decoded pattern. Python builds its pattern only from the same JSON; it does not independently decode PES and cannot conceal a reader difference by using its own reader. Each original public `EmbPattern.write_embroidery()` call includes its original default Transcoder before the format writer.

| Format | Byte-identical / total | First differing byte offset |
|---|---:|---:|
| DST |136/136|−1 (none)|
| EXP |136/136|−1 (none)|
| JEF |136/136|−1 (none)|
| VP3 |136/136|−1 (none)|

Total544/544 byte-identical. Comparison was rerun after the final JEF settings API change. Full output is disposable under `/tmp/myembro-format-samples/`: `inputs/*.json`, `kotlin/*`, `python/*`, `results.json`, `summary.json`. Full136-case goldens are not committed. The scripts record actual and expected lengths, SHA-256 and first differing offset for every case, with a nonzero exit status on a mismatch.

Only three representative cases are frozen in test resources: `sample_02_lightning`(1715 commands,1 thread), `sample_03_crown`(2934 commands,2 threads), `sample_05_rocket`(9251 commands,4 threads). Each includes its existing CC0 source PES plus four original-writer byte goldens; the source asset licenses remain in the bundle's manifest and LICENSES.md. The representative directory occupies about184KiB on disk. Four synthetic cases, including the original empty behavior, are retained too. Tests verify fixed SHA values, complete bytes and source immutability.

## Commands and observed output

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 \
PATH=/home/gosh2/.jdks/jdk-17.0.20.1+1/bin:$PATH \
./gradlew --max-workers=4 :engine:test \
  --tests '*DstWriter*' --tests '*ExpWriter*' --tests '*JefWriter*' --tests '*Vp3Writer*'
# BUILD SUCCESSFUL;14 tests,0 failures,0 errors

./gradlew --max-workers=4 :engine:exportFormatWriterSamples \
  -PformatWriterOutput=/tmp/myembro-format-samples
# FORMAT_WRITER_EXPORT count=136
/tmp/myembro-format-oracle/venv/bin/python -I tools/format-writers/compare_samples.py /tmp/myembro-format-samples
# DST 136/136, EXP 136/136, JEF 136/136, VP3 136/136; firstDifferentByteOffset=-1
```

Recreate synthetic originals with `tools/format-writers/generate_oracle.py <repo>` using the same isolated venv. After a complete136-case sweep, `python3 tools/format-writers/freeze_representatives.py <repo> <out>` copies only the three named cases and their original outputs. The synthetic generator preserves those sample provenance entries. Source/test files and scripts are committed; the venv, all136-case JSON/byte outputs and private designs are not.

## Viewer export sheet integration (stage3)

The sheet has a single PES/DST/JEF/EXP/VP3 chip row with Brother/Tajima/Janome/Melco/Husqvarna captions. Its `remember` state defaults to PES and is removed with the sheet; no format preference is persisted. Save/share labels, suggested filename extensions and the share chooser title follow the selected format. Both the SAF contract and share intent use `application/octet-stream`; the cache file provider explicitly returns that MIME too, including VP3.

`ViewerViewModel` snapshots the selected format into each pending save/share request. Its shared asynchronous export routine calls `EmbroideryExport.prepare` before opening a destination or creating a share directory. That boundary first calls the existing `PesExport.prepare`: existing PES retains its checked exact bytes; other source formats use the existing PES conversion/safety preparation. Non-PES writers receive the same decoded, validated PES wire pattern. PES bytes remain unchanged. Existing sewout and PEC viewport blockers therefore apply to every selection. USB has no format argument and still calls the unchanged PES library/USB preparation path; selecting DST does not change USB output.

Verification command:

```sh
./gradlew --max-workers=4 :engine:test \
  --tests '*EmbroideryExportTest' --tests '*PesExportTest' --tests '*PesExportSafetyTest' \
  :app:testDebugUnitTest --tests '*ViewerFormatExportTest' --tests '*PesOutputRoutesTest' \
  :app:assembleDebug
```

Observed `BUILD SUCCESSFUL`; engine9 tests and app7 tests,0 failures/errors. Checks include five writer byte/extension matches with the same validated pattern, generic MIME, corrupted header/truncated source rejection, density30 allowed/31 blocked for all formats, pending-save format snapshots, English/Korean labels and blocked Viewer shares creating no file or URI. Existing PES/USB output-route regression tests remain passing.

On existing emulator5556, update installation only: an independent disposable UI probe opened the local skull, selected DST, observed `DST로 저장`/`DST 파일 공유`, captured `/tmp/myembro-format-ui/dst.png`, dismissed/reopened the sheet and observed the PES default. Probe output: `FORMAT_UI_PASS resetOnReopen=PES selected=DST saveLabel=DST shareLabel=DST noExportExecuted=true`, `INSTRUMENTATION_CODE: 0`. No save/share/USB action or emulator lifecycle operation was performed by that probe. The screenshot is disposable; no private design is committed.
