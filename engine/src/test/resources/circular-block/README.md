# Circular BlockStitch coordinate goldens

Synthetic fixtures only, no personal designs. Gzip CSV rows are `x,y,command` in
engine integer coordinates (0.1mm). All commands, including jumps and trims, are
compared exactly without tolerance by CircularBlockProfileTest.

Frozen from `agent/block-pipeline` commit `8a6b71f`, before the prepared-predicate
optimization. These are regression goldens for the existing Kotlin output, **not
an official Ink/Stitch equivalence oracle**. The official source comparison is
separately documented in docs/research/circular-block-profile.md.

Inputs are the same SVG paths and BlockStitch defaults used in the mobile timing:
40×40mm square `M0 0H40V40H0Z` and diameter-40mm circle
`M40 20A20 20 0 1 0 0 20A20 20 0 1 0 40 20Z`, fill `#224488`, width 40mm,
`fill_method=circular_fill`, `automatic_angle=false`, unchanged default angle and
all other defaults. The lazy ShapeObject override renderer is measured.

The opt-in `tools/research/profile-circular.py --record DIR` writes scratch goldens
only. CI compares these committed files; it never updates them.
