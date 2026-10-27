# Exact indexed line/line crossing specialization

Base: `daff7bb`, branch `agent/no-relateng`. User-requested replacement for the
slow full classic relation calculation on long circular rows. No coordinates,
stitch defaults, grating/routing order or safety thresholds change. JTS stays at
1.20.0; the unsupported newer relation package is neither imported nor loaded.

## Measured production domain, before changing the predicate

`LineCrossesActualInputsTest` observes every actual FillRouting STRtree candidate
in 40mm circle/square circular and NC5-11a meander overrides, including their
existing fill underlay. `LineCrossesTrace` is an optional thread-local diagnostic
and does not serialize coordinates or change a result. The first measurement
ran with the old prepared-intersection/classic-relation implementation.

| Method / shape | Actual candidate pairs | Unique row vertex counts (vertices: rows) | Candidate vertices |
| --- | ---: | --- | --- |
| circular / circle | 3338 | 2:57, 2031:1 | 2 for all |
| circular / square | 14184 | 2:71, 3:20, 4:16, 5:16, 6:14, 7:12, 8:12, 9:8, 10:6, 11:4, 12:4, 2059:1 | 2 for all |
| meander / circle | 2354 | 2:53 | 2 for all |
| meander / square | 3038 | 2:53 | 2 for all |

Thus a single-segment candidate is a measured specialization; rows must still
support long polylines. There is no polyline simplification, tolerance, row-size
cutoff or new coordinate quantization.

## Exact predicate and conservative classic fallback

`IndexedLineCrosses` prepares each immutable row's nonzero segments in a classic
JTS STRtree. Each two-point candidate queries overlapping segment envelopes and
computes segment-pair intersections with `RobustLineIntersector`.

For isolated proper intersections, line interiors intersect in dimension 0;
no intersection means an empty interior intersection. Open LineString endpoints
are boundary points; a closed LineString uses the classic MOD2 empty endpoint
boundary. A positive-length collinear intersection is dimension 1 for simple
lines. The implementation never returns true before inspecting all relevant
segments, since a later nonproper or overlapping event changes what must be
classified.

Exact legacy behavior on degenerate/non-simple input requires the full graph:
initial oracle tests found differences for overlapping self-crossing polylines,
subnormal endpoint perturbations, and fully collapsed LineStrings. Those naive
universal segment-only decisions were discarded. The retained implementation
uses the original full `RelateOp.relate(...).isCrosses(1,1)` for any multi-segment
candidate, collapsed line, collinear overlap or endpoint/vertex event. It does
not approximate these cases or invent a boundary-label heuristic. The direct
classic oracle is independent of GeometryRelate configuration.

This fallback is part of the exact predicate: general inputs retain classic
semantics, while the observed routing candidates take the indexed proper/disjoint
path. After implementation, **all 22,914 actual candidate pairs used the indexed
path**, none used the fallback, and every result equaled full classic RelateOp.

## Oracle corpus and runtime compatibility

- All actual circular/meander candidate pairs above are compared, without sampling,
  to full `RelateOp.relate(row, candidate).isCrosses(1,1)` on every run.
- Fixed-seed 794709: 150,000 random/near-degenerate pairs, both orientations,
  300,000 comparisons. Mixed row/candidate vertex counts, integer and small/large
  decimal scales, offsets, repeated vertices, shared edges, closed boundaries,
  T/self-vertex contacts and one-ULP endpoint perturbations are included.
- A separate 13-input Cartesian matrix adds 169 explicit degenerate pairs in both
  orientations: empty/collapsed lines, endpoint contact, overlap, T junction,
  self-crossing/self-touching vertices and closed MOD2 boundaries.
- The random corpus used 64,543 indexed decisions; remaining decisions used the
  conservative classic paths (counts are included in raw output). There are no
  discarded or skipped mismatches in the final corpus.
- The existing 81-pair CircularPredicateParity test remains active.
- The source package ban remains active. Its isolated ClassLoader test invokes
  the actual production `LegacyCrosses.prepareLine` helper (now indexed), denies
  every unsupported class, compares 25 cases, and asserts 0 forbidden loads.
- `RobustLineIntersector`, classic noding/STRtree and the fallback RelateOp use
  only the already verified classic JTS path. No broad Error catch is added.

The diagnostic exposes counts only when observed. Production routing uses the
same row/candidate order and cached per-row wrapper. The golden coordinate and
command files are never regenerated.

## Reproduce

```sh
./gradlew --offline --max-workers=2 :engine:test \
  --tests '*IndexedLineCrosses*' --tests '*LineCrossesActualInputs*' \
  --tests '*CircularPredicateParity*' --tests '*CircularBlockProfile*' \
  --tests '*CircularFill*' --tests '*Meander*' --tests '*Shape*' --tests '*NoRelateNg*'
./gradlew --offline --max-workers=2 :engine:test
./gradlew --offline --max-workers=2 :engine:test --tests '*CircularBlockProfileTest'
```

JDK 17.0.20.1+1. The final command measures a fresh test JVM without the oracle
corpus warming the intersection code first. Same fixtures, 3 runs, no discarded
warm-up/JFR. Historical timings remain in the preceding reports; all values are
shared-host JVM wall times, not Android latency. Device measurement remains for
the device owner.

## JVM measurements and final output

| Shape | RelateNG reference ms | Previous 2-stage ms | Indexed fresh JVM ms | Indexed / reference | Within 2× target |
| --- | ---: | ---: | ---: | ---: | --- |
| circle | 796.900 | 4928.270 | 207.539 | 0.260× | yes |
| square | 784.744 | 4969.517 | 255.525 | 0.326× | yes |

- Full `:engine:test`: 943 tests, 0 failures/errors/skips; BUILD SUCCESSFUL in
  1m 27s. The requested representative suite passed 59/59 beforehand.
- All 22,914 real candidates matched full classic RelateOp; every real candidate
  used the indexed segment path (0 classic fallback calls for these fixtures).
- 150,000 random pairs / 300,000 directional comparisons and 169 explicit
  degenerate pairs matched the respective classic reference in both directions.
- 81 predicate pairs and all circular/meander/Shape goldens remained identical.
- Source ban: 380 files, 0 violations. Isolated production helper: 25 pairs,
  0 unsupported class loads.
- Separate fresh-JVM circular benchmark: 2/2 tests, exact coordinate/command
  goldens unchanged; circle 2625, square 3500 needle stitches.

Raw commands, distributions and counters:
[indexed-line-crosses-output.txt](indexed-line-crosses-output.txt).
Machine-readable timings:
[indexed-line-crosses-timing.csv](indexed-line-crosses-timing.csv).
