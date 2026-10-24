# S2 + S3 public sewout sampler generator

Run after local integration of letter `0a60ff0`, model `ab23976` (automatic outline
tatami), canvas `9469c9b` catalog and worker `2b89226`. The script never merges branches.
It builds an isolated application ID, uses an emulator's actual Android glyph
outlines and our engine, pulls the candidates and uninstalls that isolated app.

```sh
python3 tools/generate_s23_sewtest.py --fonts /path/to/embroidery-fonts/src
/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-sewtest/s23/S23SAMPL.PES \
  --hoop-bounds-mm -30 -30 30 30 --json /tmp/myembro-sewtest/s23/S23SAMPL-gate.json
```

Pinned font data: embroidery-fonts `c7e3a05c3d6f5c77b881c50f257851af9a8e9cb8`.
No upstream code is run; no fonts or personal designs are committed by this tool.
Geneva Simple's 10mm height/40wt suitability and SVG hash are checked against the
canvas catalog. Aventurina is the letter team's existing 12mm script trial, excluded
from the redistributable app catalog because of conditional original-font terms.
Its source data and LICENSE stay external; their hashes are in the output manifest.

The unmodified single-line `Jung Min` is 75mm wide, so `SCRIPT12.PES` remains a
separate reference. The 60mm sampler uses `JUNG12` and `MIN12` on separate rows,
keeping the 12mm size. Sampler labels: 1 Jung, 2 circle15, 3 Min, 4 square15,
5 Abc10 (Geneva Simple), 6 정민15 (Noto Sans KR default tatami, app default spacing0).
The labels are PNG annotations only. One black 40wt thread, 5mm gaps and trims
between items. Existing encoded internal trims are preserved.

Updated result (0a60ff0): 59.4×55.5mm, 4,439 stitches, 15 trims, Python gate v5 PASS 1/1;
seven individual source files PASS 7/7. Time estimate: 4439/400×60 + 15×10
= 815.85 seconds (13min36s), excluding setup. These are code checks, not a sewout claim.
