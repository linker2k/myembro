# NC5-11a BlockStitch JVM profile

Scope: `agent/block-pipeline`, 40mm circle/square, JDK 17.0.20.1+1. No emulator
used. Inputs are the same SVG paths and BlockStitch route as the
[circular mobile/JVM measurements](circular-block-profile.md). Defaults are unchanged;
only fill method `meander_fill` and tile `NC5-11a` are selected.

## Prerequisite and reproduction

The initial `5eea9b6` baseline failed both cases with
`Meander path search resource limit exceeded`: it still contained the removed
cumulative 2,000,000-step cap. The already shared source alignment patch `10fbcef`
(code plus cancellation test only) was applied separately as `73d3c35`. All successful
before/after measurements below include this prerequisite; removing the cap is not
counted as a latency optimization. Successful old output was frozen before changing
the search representation.

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
python3 tools/research/profile-meander.py --baseline-ref 73d3c35 --jfr /tmp/meander-before.jfr
python3 tools/research/profile-meander.py --jfr /tmp/meander-after.jfr
./gradlew --offline --max-workers=2 :engine:test
```

The script uses temporary source copies and an invocation-local Gradle init script.
Its probes never modify tracked algorithms. Baseline replay substitutes only
MeanderFill from the requested revision. Ordinary FillTrace graph/point serialization
is disabled in the temporary copy, avoiding diagnostic payload overhead. Tests assert
all frozen x/y coordinates and commands on every non-recording run. The explicit
`--record DIR` mode writes scratch goldens only, checking repeat runs for equality.
CI never updates the committed goldens.

Each shape has 3 sequential samples with no discarded warm-up. Time is fresh lazy
`ShapeObject.localPattern()`: SVG parsing, original default rendering and override
validation are outside the timer. JFR is enabled for both versions. Results are
wall time on a shared host, not controlled hardware benchmarks or mobile estimates.

## Algorithm comparison

Official Ink/Stitch v3.3.0 commit `b0edd96311ece82ee48816dc93466274b12c2a9c`,
read locally without executing upstream:

- [meander_fill.py:22–43](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/stitches/meander_fill.py#L22) builds the tile graph, connects components, finds endpoints, initializes the seeded RNG, expands a path, and post-processes it.
- [meander_fill.py:102–169](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/stitches/meander_fill.py#L102) starts from an unweighted shortest path and removes its edges. Edge replacement enumerates simple edge paths on unused nodes plus the two endpoints, with cutoff **7**, accepting the first path longer than 1 edge. Pair replacement uses cutoff **10**, accepting the first path longer than 2 edges. Random edge/pair selection and its order are preserved.
- Upstream's `graph.subgraph(...)` plus NetworkX enumeration represents the same allowed-node predicate and bounded DFS. The optimization changes storage and lookup, not that search strategy. The exponential worst-case nature of bounded simple-path enumeration remains; no pruning heuristic or new search budget is added.

JFR showed coordinate equality and HashMap/HashSet access dominating the old search.
Each alternative copied the full unused-coordinate set, hashed coordinate objects
for adjacency/visited tests, and allocated an Edge for each attempted DFS branch.
These are implementation costs, not evidence that the verified algorithm or
7/10 cutoff should be changed.

The production optimization is confined to `MeanderFill.generatePath`:

- Assign stable integer IDs in the existing LinkedHashMap node order; copy adjacency
  IDs in the existing LinkedHashSet neighbor order.
- Use boolean arrays for unused/visited membership and a depth-bounded integer path
  buffer. Build Edge objects only when returning an accepted replacement.
- On every accepted replacement, remove the same edges from both the original graph
  and the indexed adjacency, preserving remaining neighbor order. Clear the same
  unused start nodes. The original graph remains mutated as before.

Shortest path, random calls/pop order, path-index replacement, graph geometry,
seed, accepted path ordering, cutoffs, post-processing, locks and stitch finishing
are unchanged. Existing original-algorithm cancellation checks remain. This is a
representation/temporary-allocation optimization, not a new meander algorithm.

## Stages and verification

Nonoverlapping markers measure geometry, underlay (including its routing), tile
loading/graph construction, connectivity plus endpoints/RNG initialization, path
search, post-processing, and final stitch-plan finishing. `top` is the small wrapper
outside the named meander sub-stages. Stage medians are independent and need not
sum to the median total. Tile/JIT caches are not reset between samples.

Goldens cover every final command and coordinate: circle 994 needle stitches,
square 1234; files total 6,413 bytes. No personal patterns. They freeze our baseline
port output, **not** newly generated official Ink/Stitch equivalence oracles.
The existing MeanderFillTest additionally checks deterministic seeded graph paths,
tile loading, endpoint logic, component augmentation, and cancellation.

Optimized mobile timing is unmeasured under the no-emulator instruction. The
40mm results do not establish latency bounds for all sizes, scales or seeds.

## Measured medians (ms)

| Version | Shape | Total | Geometry | Underlay | Top wrapper | Tile graph | Connect/endpoints | Search | Post | Finish |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| before | circle | 5308.873 | 8.397 | 69.434 | 0.087 | 56.777 | 1.438 | 5120.187 | 0.630 | 6.082 |
| before | square | 4057.653 | 5.329 | 64.410 | 0.062 | 6.838 | 1.783 | 3975.875 | 0.512 | 11.838 |
| after | circle | 1015.266 | 25.799 | 143.357 | 0.090 | 54.243 | 3.853 | 763.321 | 5.338 | 17.224 |
| after | square | 409.190 | 5.386 | 61.380 | 0.063 | 7.261 | 1.908 | 313.594 | 0.638 | 7.715 |

circle: 5308.873 → 1015.266ms (5.23× faster).

square: 4057.653 → 409.190ms (9.92× faster).

JFR before: 2538 execution samples; most frequent leaf frames:

- `kotlin/jvm/internal/Intrinsics.areEqual`: 675.
- `java/util/HashMap.getNode`: 538.
- `java/util/HashMap.putVal`: 258.
- `java/util/LinkedHashMap.afterNodeInsertion`: 203.
- `java/util/HashMap.remove`: 183.

JFR after: 393 execution samples; most frequent leaf frames:

- `io/github/linker2k/myembro/engine/inkstitch/stitches/MeanderFill.generatePath$alternative$dfs`: 234.
- `java/util/HashMap.resize`: 4.
- `org/locationtech/jts/operation/relateng/RelateNG.computeLineEnds`: 3.
- `io/github/linker2k/myembro/engine/inkstitch/stitches/FillRouting.route$engine`: 3.
- `io/github/linker2k/myembro/engine/sewout/SewoutCheck.run`: 3.

Samples identify hotspots, not exact call counts. The records remain local at
`/tmp/myembro-meander-before.jfr` and `/tmp/myembro-meander-after.jfr`.

Automated result:

```text
before profile: BUILD SUCCESSFUL in 52s (including compilation), scratch repeat goldens identical
optimized profile: BUILD SUCCESSFUL in 35s (including compilation), 6/6 coordinate/command goldens identical
./gradlew --offline --max-workers=2 :engine:test
BUILD SUCCESSFUL in 1m 18s
tests=933 failures=0 errors=0 skipped=0
```

[Stage CSV](meander-block-profile.csv) and [per-run output](meander-block-profile-output.txt) preserve the measurements. No emulator, deployment, push or main merge was performed.
