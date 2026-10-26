# Prepared line intersection filter before classic crosses

Base: `2cc1479`, branch `agent/no-relateng`. This is the requested 2-stage
predicate optimization after the ART compatibility fallback, not a replacement
stitching algorithm. Row geometry, STRtree order, routing and PES output rules
remain unchanged; goldens are compared, not regenerated.

## Implementation and exactness

For each row LineString, prepare once using
`PreparedGeometryFactory.prepare(row) as PreparedLineString`. Reuse that object's
`intersects(candidateLine)` before calling the existing
`RelateOp.relate(row, candidate).isCrosses(row.dimension, candidate.dimension)`.

`crosses` implies `intersects`: a false intersection result cannot discard a
crossing. Every surviving candidate still uses the same exact classic DE-9IM
crossing predicate. No intersection result is treated as a crossing by itself.
This safely rejects disjoint STRtree envelope candidates without repeating their
full relation/noding calculation. Cached prepared objects remain local to one
travel graph and their geometries are not mutated.

## Dependency call path

JTS is unchanged at 1.20.0. Local `javap -p -c -classpath <jts-core-1.20.0.jar>`
shows the line/line path:

```text
PreparedGeometryFactory.prepare -> create -> PreparedLineString
PreparedLineString.intersects
  -> envelopesIntersect
  -> PreparedLineStringIntersects.intersects
  -> SegmentStringUtil.extractSegmentStrings
  -> PreparedLineString.getIntersectionFinder
  -> FastSegmentSetIntersectionFinder.intersects
  -> MCIndexSegmentSetMutualIntersector / SegmentIntersectionDetector
  -> RobustLineIntersector
```

None of the inspected factory, prepared line, prepared line intersection,
segment finder/detector, mutual intersector or robust line intersector classes
contains a bytecode reference to the unsupported newer relation package.
Line/line arguments do not take the polygon/point fallback branches.
`PreparedLineString.crosses` is still deliberately unused, since it delegates
through BasicPreparedGeometry/GeometryRelate. Survivors explicitly use RelateOp.
Full bytecode dump: `/tmp/myembro-prepared-filter-javap.txt`.

The source-use ban remains active. The isolated ClassLoader regression now loads
the actual engine `LegacyCrosses.prepareLine` production helper plus JTS/Kotlin,
blocks every unsupported relation class, and compares all 25 candidate pairs
against direct classic RelateOp results. It asserts 0 forbidden class loads.
The separate 81-pair parity test checks crossing, touching, overlap, duplicate
points and self-crossing lines through the actual two-stage helper.

## JVM timing

Same 40mm square/circle, same 3-run median policy, JDK 17.0.20.1+1, no JFR and
no discarded warm-up. Historical reference values are preserved from
[the previous fallback measurement](no-relateng.md); current values come from
the representative test invocation in the raw output below. These shared-host
wall times do not predict Android latency. The requested target is current
median at most 2 times the measured prepared newer-engine reference.

| Shape | Prepared reference ms | Classic-only ms | Two-stage ms | Current / reference | Within 2× target |
| --- | ---: | ---: | ---: | ---: | --- |
| circle | 796.900 | 6734.647 | 4928.270 | 6.18× | NO |
| square | 784.744 | 9346.530 | 4969.517 | 6.33× | NO |

The target was not reached for either fixture. The two-stage implementation is
retained for review with the measured shortfall, without substituting a new
predicate, changing density/underpath parameters or loosening output checks.
Mobile timing and ART verification remain for the device owner.

Raw commands/runs: [prepared-crosses-filter-output.txt](prepared-crosses-filter-output.txt).
Machine-readable table: [prepared-crosses-filter-timing.csv](prepared-crosses-filter-timing.csv).

## Validation output

- Requested representative regression: 56/56 tests, 0 failures/errors,
  BUILD SUCCESSFUL in 1m 4s.
- Full `./gradlew --offline --max-workers=2 :engine:test`: 940 tests,
  0 failures, 0 errors, 0 skipped; BUILD SUCCESSFUL in 2m 54s.
- Circle/square coordinates and commands remain identical to committed goldens.
- 8 inspected prepared/noding class bytecode dumps contain 0 unsupported package
  references; source and runtime isolation checks below also passed.

```text
NO_UNSUPPORTED_RELATION_PACKAGE sources=376 violations=0
TWO_STAGE_RELATION_ISOLATION pairs=25 forbidden_class_loads=0

CIRCULAR_PREDICATE_PARITY pairs=81 identical=true
```

Full local result summary: `/tmp/myembro-prepared-filter-results.txt`.
