# Noto Latin FILL: first divergence investigation

Historical investigation; see the subsequent [ordering fix and verification](noto-fill-order-fix.md).

2026-10-25, parent `4413c6a`, branch `agent/font-snapshots`. Investigation only:
no canonicalization, rounding, route selection, stitch defaults or output fix applied.
Opt-in thread-local observations are inactive outside a diagnostic scope.

## Finding

Actual path: `TextObject` → `LetteringEngine.fromContours` →
`TatamiLettering.generateGlyphs` → `DefaultTatami.stitchGroups` →
`InkAutoFill.paths` → `TatamiFill.fill` → `FillRouting.route`.
The legacy `OutlineTextFill` bridge is not called for this case.

The first difference is **JTS Polygonizer ring start/order**, not Android TTF
extraction or a Math/StrictMath or Float/Double conversion difference. The same
prepared object reproduces the variation within one JVM as well as on Android.

`PolygonRegion.fromPaths` obtains polygons at
[`Geometry.kt:143`](../../engine/src/main/kotlin/io/github/linker2k/myembro/engine/geometry/Geometry.kt#L143).
JTS 1.20.0's `PolygonizeGraph.getEdgeRings()` traverses inherited `dirEdges` using
its set iterator and starts a ring from the encountered directed edge. `PlanarGraph`
initializes this collection as `HashSet`; directed edges retain object identity
hashing. Therefore a valid closed ring can be returned with different cyclic start
vertices even for identical noded input. The locally resolved JTS jar was inspected
with `javap -c -p` to confirm the actual dependency, including `new HashSet` and
`dirEdges.iterator()`, rather than inferring this from a different library release.
Upstream source: [PolygonizeGraph.getEdgeRings](https://github.com/locationtech/jts/blob/1.20.0/modules/core/src/main/java/org/locationtech/jts/operation/polygonize/PolygonizeGraph.java#L173),
[PlanarGraph](https://github.com/locationtech/jts/blob/1.20.0/modules/core/src/main/java/org/locationtech/jts/planargraph/PlanarGraph.java).

The initial changed glyph is **A** in `Ab12`, Noto default/Thin, 15mm. One observed
exterior ring (29 coordinates) began at `(-175.77335784094632, -43.84679203539824)`
on JVM and `(-125.69937874318404, -43.84679203539824)` on Android. Coordinates are
native 0.1mm geometry units at this stage. The vertex bit sets and normalized WKB
are identical: this is a cyclic representation change, not a geometric displacement.

## Stage-by-stage evidence

Input is the **same serialized actual app TextObject**, not independently re-extracted
fonts: `noto-sans-kr-latin.myembro.gz` from the pinned snapshot fixtures.
UTF-8 object SHA-256: `87943be73059f2b0a1a38a6897d6def8dc348ad87b01d2a2ab7116da36851247`.
Each runtime replayed it 4 times in one process. Coordinate observations use IEEE
Double bits and WKB bytes, without geometric tolerances.

| Stage (JVM run 0 vs Android run 0) | Result |
|---|---|
| Prepared object JSON | Exact SHA-256 above |
| Normalized per-glyph input rings | 4/4 bit exact |
| Noded line geometry | 4/4 WKB exact |
| Polygonizer output rings | **First difference, event index 2**, first glyph A |
| Polygonizer output after diagnostic `norm()` | 4/4 WKB exact |
| Angles, spacing, lengths, tolerance, staggers | Bit exact |
| Underlay/top intersected row segments | 10/10 bit exact |
| First fill graph before Euler augmentation | Same 13 segment edges and 26 outline edges; 13 extra edges on each side, but different extra-edge sets |
| Odd-node matching | Not invoked in this case (graphs already Eulerian); JGraphT matching is excluded |
| First rendered route | 72 vs 75 points; first changed point index 1 |
| Final generated needle count | 958 vs 962 |
| Final PES | 2934 vs 2942 bytes |

First changed rendered point (SVG-pixel units): JVM
`(-71.72314157211218, -7.663084363452894)`, Android
`(-69.60363810282902, -7.940215302464353)`.

The causal downstream consumer is
[`FillRouting.tagNodes:212`](../../engine/src/main/kotlin/io/github/linker2k/myembro/engine/inkstitch/stitches/FillRouting.kt#L212):
`LengthIndexedLine.project` measures distance from the ring's current start.
[`outlineEdges:215`](../../engine/src/main/kotlin/io/github/linker2k/myembro/engine/inkstitch/stitches/FillRouting.kt#L215)
sorts boundary nodes by that projection, then adds `extra` edges at even indices
([line 222](../../engine/src/main/kotlin/io/github/linker2k/myembro/engine/inkstitch/stitches/FillRouting.kt#L222)).
A cyclic start shift changes that alternating phase and boundary insertion order;
the Euler/travel path changes even though every fill row is identical. This is
before S1 locks, minimum-stitch filtering, native quantization and PES encoding.

Four-run observed sizes: JVM `[2934, 2934, 2942, 2934]`, Android
`[2942, 2934, 2934, 2934]`. Android run 3 and JVM run 0 even produced the **same PES
SHA-256** `af2b2251a54366eb894947258137eee25c42d8d7e1e0055acd823a58bc359132`.
Equal size alone is not equality: other 2934-byte outputs had different hashes.
The recorded stages and hashes are in [the captured comparison output](noto-fill-parity-output.txt).
Raw traces are local `/tmp/myembro-font-fill-probe/{jvm.json,android.json.gz}`;
re-running intentionally replaces these diagnostic files and can change ring starts.

This rules out floating-point differences as the **first cause for this case**.
No specific Math/StrictMath function needs replacing based on this evidence. It
does not claim that every later floating calculation on every platform is identical.

## Reproduction and checks

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 tools/font-snapshots/run-fill-probe.sh
# Installs only on emulator-5556; never 5554/5558.
# Stages, comparison and hashes: /tmp/myembro-font-fill-probe/

JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test \
  --tests '*FontFillProbeTest' --tests '*AndroidFontSnapshotTest' --tests '*FontEquivalenceTest'
```

Observed: `BUILD SUCCESSFUL in 16s`; 7 tests, 0 failures, 0 errors.
`FontFillProbeTest` asserts exact repeated input rings, noding, normalized polygon,
parameters and row coordinates. It does not assert stable unnormalized ring starts
or pretend nondeterministic output is already fixed. Existing snapshot and official
font regression tests remain in this check.

The probe records valid geometry, grating rows, pre/post Euler graph, matching
input/pairs when used, travel graph, collapsed Euler path and rendered points.
Android uses the same compiled helper and fixed object asset. AAPT automatically
expands `.gz` assets and removes the suffix; the instrumentation opens the packaged
`.myembro` filename. No font/system fallback, remote code, invented geometry,
StrictMath substitution or new stitching algorithm is used.

## Next step (not implemented)

Review canonical ring representation at the polygon-to-fill boundary against the
upstream port's intended boundary order, then verify that `extra` edge phase and
routing stay stable across repeated Android/JVM runs. Do not normalize only in a
test, change the expected PES to whichever run happened last, or hide the difference
with coordinate tolerances. A production fix is explicitly deferred to the next task.
