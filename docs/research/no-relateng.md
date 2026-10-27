# Classic JTS crossing predicate for Android verification compatibility

Base: `agent/block-int` `b59e9bb`; work branch `agent/no-relateng`.
The coordinator reported an ART `VerifyError` rejecting JTS 1.20
`RelatePointLocator` on device 5560, reached from the prepared relation call
introduced by optimization `8715b23` in `FillRouting.buildTravelGraph`.
This change removes that call path; it does not catch/suppress `Error`, change
geometry, predicate semantics, ordering, stitch parameters or safety thresholds.
Device remeasurement is owned by claude-canvas; the evidence here is JVM-only.

## Replacement and bytecode evidence

Retain cached row LineStrings and the existing STRtree insertion/query order.
For every candidate, call `LegacyCrosses.test(row, travelLine)`, which performs
an envelope rejection then explicitly calls
`RelateOp.relate(a, b).isCrosses(a.dimension, b.dimension)`.
This is the existing classic JTS OGC crosses implementation, not a new predicate
or an approximate segment-intersection algorithm.

PreparedLineString was inspected rather than assumed safe: its `crosses` calls
BasicPreparedGeometry.crosses, which delegates to Geometry.crosses. JTS 1.20
GeometryRelate can select the newer relation implementation based on its
configuration. The replacement bypasses GeometryRelate entirely for this query.

Local primary artifact: Gradle's unchanged `org.locationtech.jts:jts-core:1.20.0`.
`javap -p -c -classpath <jts-core-1.20.0.jar> <class>` verified:

| Class | Observed crossing/relation call path |
| --- | --- |
| PreparedLineString | BasicPreparedGeometry.crosses |
| BasicPreparedGeometry | Geometry.crosses |
| GeometryRelate | Configurable implementation, including the unsupported newer engine |
| RelateOp | GeometryGraphOperation + RelateComputer.computeIM |
| RelateComputer | Classic geometry graph relation calculation |
| GeometryGraphOperation / GeometryGraph | Classic geometry graph construction/noding |
| IntersectionMatrix | DE-9IM `isCrosses` dimension test |

The last 5 classic-path classes contain no bytecode reference to the unsupported
relation package. Full javap evidence is retained locally in
`/tmp/myembro-no-relateng-javap.txt` and
`/tmp/myembro-no-relateng-legacy-javap.txt`.

## Automated regression prevention

`NoRelateNgTest.engineSourcesDoNotReferenceUnsupportedRelationPackage` scans all
engine `src` Kotlin/Java files (including test/oracle sources), refusing qualified
package references in dotted or slash form. The forbidden name is assembled
inside the guard so the test itself does not need an exemption.

`NoRelateNgTest.classicRelationRunsWithUnsupportedClassesCompletelyUnavailable`
loads JTS in an isolated ClassLoader that rejects every class in the unsupported
package. It runs classic relation matrices on crossing, touching, contained and
self-crossing line pairs, asserting zero forbidden class-loading attempts. This
proves the replacement's exercised internal call path independently of a
machine's default JTS relation selection. It does not simulate ART verification.

`CircularPredicateParityTest` compares the production helper with classic
Geometry.crosses on 81 line pairs. Frozen CircularBlockProfile coordinates and
commands remain authoritative; goldens are compared, never regenerated.
CircularFill, Meander and Shape regression tests cover the same affected routing
backend. Full `:engine:test` also runs the source guard automatically.

## JVM before/after measurement

Both runs use JDK 17.0.20.1+1, the same 40mm circle/square fixtures and committed
coordinate goldens, no JFR/instrumentation and 3 fresh override renders per shape
with no discarded warm-up. Reported medians are sequential wall times on this
shared workstation, not controlled benchmarks or Android latency estimates.
Classic relation recomputes/nodes the row for each candidate, so the prepared
predicate optimization's speed gain is deliberately given up for compatibility.

Before command (unmodified base):

```sh
./gradlew --offline --max-workers=2 :engine:test --tests '*CircularBlockProfileTest'
```

After representative regression command:

```sh
./gradlew --offline --max-workers=2 :engine:test \
  --tests '*CircularPredicateParity*' --tests '*CircularBlockProfile*' \
  --tests '*CircularFill*' --tests '*Meander*' --tests '*Shape*' --tests '*NoRelateNg*'
```

| Shape | Prepared before median ms | Classic after median ms | After / before | Stitch count | Golden |
| --- | ---: | ---: | ---: | ---: | --- |
| circle | 796.900 | 6734.647 | 8.45× | 2625 | IDENTICAL |
| square | 784.744 | 9346.530 | 11.91× | 3500 | IDENTICAL |

## Test output

- Representative command: 56 tests, 0 failures; BUILD SUCCESSFUL in 1m 27s.
- Full `./gradlew --offline --max-workers=2 :engine:test`: 940 tests,
  0 failures, 0 errors, 0 skipped; BUILD SUCCESSFUL in 1m 58s.
- Circle: 2625 needle stitches; square: 3500. Every frozen coordinate and command
  was identical before/after. No golden, dependency or safety policy changed.

```text
NO_UNSUPPORTED_RELATION_PACKAGE sources=376 violations=0
CLASSIC_RELATION_ISOLATION pairs=25 forbidden_class_loads=0

CIRCULAR_PREDICATE_PARITY pairs=81 identical=true
```

Raw before/after runs: [no-relateng-output.txt](no-relateng-output.txt).
Numeric table: [no-relateng-timing.csv](no-relateng-timing.csv).
Full local command/result summary: `/tmp/myembro-no-relateng-results.txt`.
The exact rollout tradeoff is a measured 8.45× circle / 11.91× square JVM latency
regression for this compatibility fallback. Do not label the replacement as a
performance optimization; Android timing and crash verification remain pending.
