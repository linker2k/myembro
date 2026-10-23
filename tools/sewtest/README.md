# Public sewout tools

Generate individual S1 samples using the actual Android app glyph extraction and engine:

```sh
python3 tools/generate_s1_sewtest.py
```

Compose existing public test PES files into one black-thread sampler (also usable for S2/S3):

```sh
python3 tools/make_sewtest_sampler.py \
  /tmp/myembro-sewtest/s1/CIRCLE.PES /tmp/myembro-sewtest/s1/SQUARE.PES \
  /tmp/myembro-sewtest/s1/TXT05.PES /tmp/myembro-sewtest/s1/TXT08.PES \
  --output /tmp/myembro-sewtest/s1/S1SAMPL
```

The Kotlin tool preserves input needle order and internal trims; it adds a trim between samples.
COLOR_CHANGE/STOP become TRIM for this single-thread test sheet. Ordered row packing uses a 5mm gap;
rows are centered and wrap within 60mm. The run fails before writing if the pack exceeds 60×60mm.
Output is PES v1, a numbered PNG, and JSON with placements/counts/time estimate. Numbers are not stitched.
The output stem must be 8.3 safe. No AI geometry generation or personal designs are used.

Check actual bytes and the requested 60×60mm area:

```sh
/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-sewtest/s1/S1SAMPL.PES \
  --text --style running --hoop-bounds-mm -30 -30 30 30 \
  --json /tmp/myembro-sewtest/s1/S1SAMPL-gate.json
```

Time is explicitly `stitches / 400spm * 60 + encoded TRIM count * 10 seconds`, including existing
internal trims. It excludes setup and is an estimate, not a measured sewout time.
