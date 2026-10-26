# Circular BlockStitch JVM profile and exact-output optimization

Scope: `agent/block-pipeline`, baseline `8a6b71f`; no emulator used. Synthetic
40mm square/circle paths and explicit BlockStitch defaults exactly match
[the Android timing fixtures](block-mobile-timing.md). JVM is JDK 17.0.20.1+1.
This is JVM evidence only; optimized mobile latency remains unmeasured.

## Reproduce

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
python3 tools/research/profile-circular.py --baseline-ref 8a6b71f --jfr /tmp/circular-before.jfr
python3 tools/research/profile-circular.py --jfr /tmp/circular-after.jfr
./gradlew --offline --max-workers=2 :engine:test
```

The script copies sources into a temporary directory, optionally substitutes only
baseline FillRouting from the requested Git revision, and adds timing markers in
those copies. An init script selects them only for that Gradle invocation; tracked
algorithms are not patched. Temporary FillTrace ignores ordinary graph/point dumps
to avoid measuring diagnostic serialization. No new production timing hooks,
settings, dependencies, geometry changes or output tolerance are introduced.

The timed action is a fresh lazy `ShapeObject.localPattern()` override. Source SVG
parsing, the initial default render, and override validation happen before the timer.
Each shape runs 3 times, no discarded warm-up. These are sequential wall-time
measurements with JFR enabled, not statistically controlled hardware benchmarks.

Stage boundaries are nonoverlapping: geometry covers VectorArt setup and region
processing outside other stages; underlay includes its generation/routing; top
covers circular ring/spiral creation, clipping, sampling and repeats; routing is
specifically the circular top-fill FillRouting call; finish is
GeneratedPatternProcessor plus final pattern construction. Per-stage medians are
computed independently and need not sum exactly to the median total.

## Source comparison and hotspot

Official source, read locally without running upstream:
Ink/Stitch v3.3.0 commit `b0edd96311ece82ee48816dc93466274b12c2a9c`.

- [circular_fill.py:36–114](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/stitches/circular_fill.py#L36): concentric rings → Fermat spiral → intersection with shape. A single LineString takes the direct path; multiple fragments use fill/travel graph routing. The app's flattened SVG circle can produce multiple fragments, so its circular top fill also reaches routing. This optimization does not change which branch is selected.
- [contour_fill.py:436–469 and 569–592](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/stitches/contour_fill.py#L436): resample corresponding rings and interpolate the alternating spiral. This algorithm and its stitch coordinates are unchanged.
- [tatami_fill.py:475–505](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/stitches/tatami_fill.py#L475): builds an STRtree from existing segment LineStrings, queries with the OGC `crosses` predicate, and attaches crossing travel edges to their source fill row.

Our previous `buildTravelGraph` rebuilt `FillGeometry.line(row.points)` for every
STRtree candidate and called legacy JTS `Geometry.crosses`. JFR located the dominant
cost in legacy geometry intersection/noding (`MonotoneChainEdge` and
`LineIntersector`) under that repeated relation test. Long circular spiral rows
make that repeated work expensive. The measured hotspot is **implementation
inefficiency in the same predicate**, not evidence that a different stitch
algorithm or default is required.

Production diff: retain each row LineString once, prepare its JTS 1.20 `RelateNG`
predicate once, and evaluate `RelatePredicate.crosses()` for each candidate.
STRtree insertion/query order, graph edge order, underpath edge removal, row
sampling, ring spacing, locks and stitch arithmetic are unchanged. This retains
the original source's OGC crossing rule, using an indexed/prepared implementation.
Preparation data lives only for the current travel-graph build. The existing JTS
version already contains these APIs; no dependency update is needed.

This targeted diagnosis is not a claim that every existing Kotlin geometry/routing
port detail is identical to GEOS/NetworkX. The existing regression goldens freeze
our old output; they are not newly generated official Ink/Stitch oracles.

## Exact regression evidence

`CircularBlockProfileTest` compares every final integer x/y coordinate and command
against 2 compressed CSV goldens generated **before** optimization. Each old and
new profiled run asserts those immutable goldens. The circle has 2625 needle
stitches and the square 3500. All commands, including jumps and trims, are in the
comparison; no coordinate rounding/tolerance is introduced by the test.

`CircularPredicateParityTest` additionally checks 81 ordered pairs covering proper
crossings, endpoint contact, collinear overlap, repeated vertices, self-crossing
and nearly collinear lines against the legacy JTS predicate. The normal full-engine
run covers other shared FillRouting callers as well.

Goldens: 8,986 + 11,771 = 20,757 bytes. No personal designs.
SHA-256:

```text
f38e3fa4df96bba188ce56949f2e7cf0dd505501d1b95c0b14c78725ebfe764b  circular-circle.csv.gz
7370a3543446fbc59bca735bfbe5b1b35e5e05a2f1d811debdb10e367f632902  circular-square.csv.gz
```

Normal, non-overlay verification:

```text
./gradlew --offline --max-workers=2 :engine:test
BUILD SUCCESSFUL in 1m 18s
917 tests; failures=0; errors=0; skipped=0
```

## Final measurements (3-run medians, ms)

| Version | Shape | Total | Geometry | Underlay | Top fill | Routing | Finish |
|---|---|---:|---:|---:|---:|---:|---:|
| before | circle | 5101.501 | 6.354 | 68.700 | 71.684 | 4945.453 | 9.311 |
| before | square | 6986.297 | 4.465 | 45.186 | 101.801 | 6824.200 | 10.584 |
| after | circle | 565.234 | 5.776 | 55.762 | 68.007 | 426.074 | 9.616 |
| after | square | 636.846 | 4.062 | 39.468 | 99.929 | 485.691 | 10.719 |

circle: 5101.501 → 565.234ms (9.03× faster).

square: 6986.297 → 636.846ms (10.97× faster).

Both final profile invocations passed 2 tests × 3 runs; all frozen coordinates and
commands matched on all 12 baseline/optimized renders. The before profile took
55s including compilation, the after profile 22s including compilation.

JFR before: 3403 execution samples. Most frequent leaf frames:

- `org/locationtech/jts/geomgraph/index/MonotoneChainEdge.computeIntersectsForChain`: 1543 samples.
- `org/locationtech/jts/algorithm/LineIntersector.computeIntersection`: 819 samples.
- `org/locationtech/jts/algorithm/RobustLineIntersector.computeIntersect`: 484 samples.

JFR after: 352 execution samples. Most frequent leaf frames:

- `org/locationtech/jts/index/chain/MonotoneChain.computeOverlaps`: 99 samples.
- `org/locationtech/jts/algorithm/RobustLineIntersector.computeIntersect`: 44 samples.
- `org/locationtech/jts/algorithm/LineIntersector.computeIntersection`: 42 samples.

Sampling counts identify where time was spent; they are not call counts or exact
CPU durations. Raw recordings remain local in `/tmp/myembro-circular-before.jfr`
and `/tmp/myembro-circular-after.jfr`. Committed [stage CSV](circular-block-profile.csv)
and [per-run output](circular-block-profile-output.txt) provide reproducible numeric
evidence without committing the recordings or temporary source overlays.

The optimized Android timing is deferred by the manager's no-emulator instruction;
these JVM measurements do not assert that mobile now meets a latency target.
