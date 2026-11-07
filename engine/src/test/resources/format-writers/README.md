# DST / EXP writer byte oracles

Four repository-owned synthetic patterns in `patterns.json`: mono, multicolor, split-trim, empty. No personal embroidery is included. Coordinates are absolute 0.1mm units, y down; command names are the engine's six core commands. Thread RGB values omit alpha. The split fixture contains a command after END to verify terminal handling and an emoji name to check Python-compatible label padding/UTF-8 header bytes.

Generated with **pyembroidery 1.5.1 (MIT)** from a new isolated venv, running `python -I`. The public `EmbPattern.write_embroidery()` pipeline executes the original default encoder before DstWriter/ExpWriter. Gradle tests read only fixed files and never invoke Python or access the network.

- DST: `MAX_STITCH_DISTANCE=MAX_JUMP_DISTANCE=121`, `ROUND=True`, `FULL_JUMP=False`; default non-extended header, `trim_at=3`.
- Extended DST: only `version=extended` differs; checks author/copyright/thread header output.
- EXP: limits127, `ROUND=True`, `FULL_JUMP=True`.
- Default encoder: no tie-on/off, no explicit trim around color changes, long-stitch contingency `JUMP_NEEDLE`. Intermediate gaps retain fractional coordinates; writer deltas use Python round-to-even against the cumulative encoded position. These low-level ports preserve those defaults, including zero-length records where upstream emits them; they do not add the app's PES-specific normalization or export gate. No UI or export path is connected in this step.
- DST header `ST` counts normalized commands, not just penetrations or expanded trim records, matching upstream. There is no invented recentering, header truncation, or header-size rejection. Missing thread entries use random filler colors upstream; extended output is deterministic only with specified threads, as in these fixtures.

`provenance.json` records the installed source SHA-256 values, version, output sizes and SHA-256 values. All12 outputs occupy4,476bytes. Both test classes verify the frozen output SHA and byte equality, reporting `firstDifferentByteOffset` (−1 for equality), and verify input immutability. The engine model has no sequin, speed, matrix, or on-the-fly setting commands; only its representable six commands take the corresponding original default Transcoder paths.

Recreate from a new empty directory (venv is disposable, not committed):

```sh
python3 -m venv /tmp/myembro-format-oracle/venv
/tmp/myembro-format-oracle/venv/bin/python -I -m pip install pyembroidery==1.5.1
/tmp/myembro-format-oracle/venv/bin/python -I tools/format-writers/generate_oracle.py "$PWD"
./gradlew --max-workers=4 :engine:test --tests '*DstWriter*' --tests '*ExpWriter*'
```

Source: https://github.com/EmbroidePy/pyembroidery, package version1.5.1; `DstWriter.py`, `ExpWriter.py`, `EmbEncoder.py`, and `EmbPattern.write_embroidery`. MIT notice: repository `THIRD_PARTY_NOTICES.md`. Kotlin implementations: `engine/.../io/{DstWriter,ExpWriter,LegacyWriterEncoder}.kt`.
