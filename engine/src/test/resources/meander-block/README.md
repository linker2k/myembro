# NC5-11a BlockStitch regression goldens

Synthetic 40×40mm square and diameter-40mm circle, identical to the mobile timing
paths. `MeanderBlockProfileTest` contains their explicit SVG and settings.
All existing BlockStitch defaults are used, selecting `meander_fill`, `NC5-11a`
and explicit unchanged angle (`automatic_angle=false`). No personal inputs.

Frozen from `73d3c35` before the integer-ID optimization. This includes the already
shared upstream cutoff fix from `10fbcef`: cumulative 2,000,000-search-step rejection
removed, official 7/10 edge cutoffs and cooperative cancellation retained. Without
that prerequisite, both requested inputs failed and had no successful golden.

These are old Kotlin-port output goldens, not official Ink/Stitch oracle files.
Gzip CSV rows contain every final `x,y,command` in integer 0.1mm units, with no
tolerance. Normal tests only read committed goldens. The profiling script's explicit
`--record DIR` option writes scratch files and compares repeat runs against them.

SHA-256 (6,413 bytes total):

```text
ba99879504f72d5672a25b9135b7b5d26c1d15d031284649109da7e76118a222  meander-circle.csv.gz
7348e0ecbb5bfa9a186b451445dbf8e5dcaee088859adefc3753cb89fe089aa2  meander-square.csv.gz
```
