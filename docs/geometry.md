# Geometry and digitizing foundation (Phase 3a)

Verified on 2026-10-22. Only engine APIs are provided, without app integration. The public APIs are
`Point`, `Polyline`, `Affine2D`, `SvgPath`, `PolygonRegion`, and `FillRule` in `engine.geometry`,
and `RunningStitch` and `TatamiFill` in `engine.digitizing`. Existing editor/lettering APIs are unchanged.

## Polygon operations: adopting JTS

`org.locationtech.jts:jts-core:1.20.0` is pinned. Implementing intersection noding, face extraction
from self-intersecting/overlapping paths, holes, intersection/union/difference/symmetric difference,
and offsets entirely ourselves would impose a substantial burden for floating-point degenerate
cases. JTS was selected to establish a stable polygon foundation first in Phase 3a.
JTS types are not exposed through the public API, and region operations return new results.

The [project release](https://github.com/locationtech/jts/releases/tag/1.20.0) and
[license for that version](https://github.com/locationtech/jts/blob/1.20.0/LICENSES.md) were checked.
The [official licensing FAQ](https://github.com/locationtech/jts/blob/1.20.0/FAQ-LICENSING.md)
states that users may choose between EPL-2.0 and EDL-1.0. This project selects the BSD-style
**EDL-1.0** for use as a dependency of the GPL-3.0 project. The original text and copyright notices
are packaged in engine resources under `licenses/jts/` and recorded in
[THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md).

`PolygonRegion.fromPaths` nodes the input lines, extracts faces with `Polygonizer`, and calculates
the winding number of the original paths at an interior point of each face. NON_ZERO selects
faces with a winding number other than 0, while EVEN_ODD selects odd-numbered faces, then unions them.
It handles hole orientation, same-direction overlaps, opposite-direction cancellation, overlapping
paths, and self-intersections. Intersection noding is performed first, as required by the
[JTS Polygonizer documentation](https://locationtech.github.io/jts/javadoc/org/locationtech/jts/operation/polygonize/Polygonizer.html).
Results that overlap only at points/lines have no area and are removed.

## Coordinates and SVG paths

- All geometry/stitch lengths use **0.1mm** units, with +y downward. The caller must explicitly
  specify SVG input unit/viewport transformations, such as user units, px, viewBox, and mm, using
  `Affine2D`. No automatic px→mm inference is performed.
- `SvgPath.flatten(d, transform, tolerance)` handles M/L/H/V/C/S/Q/T/A/Z, absolute/relative
  coordinates, repeated arguments, exponent notation, and signs/decimal points/arc flags without
  separators. It distinguishes Z from open paths. When filling through `polygons` or `fromPaths`,
  open subpaths also connect their ends to their starts.
- The default tolerance is **0.5 units after transformation=0.05mm**. Béziers use adaptive
  De Casteljau subdivision with transformed control points; arcs use radius correction,
  endpoint→center conversion, and a chord-error upper bound. For nonuniform scaling/skew,
  the matrix norm conservatively bounds arc subdivision error.
- `Affine2D.parse` supports matrix/translate/scale/rotate (including a center)/skewX/skewY.
  `parent * child` applies child first. SVG `translate(10 20) scale(2)` maps `(1,1)` to
  `(12,22)`. Combine hierarchical transformations using the same multiplication rule.
- This is a path-data and transform-attribute parser. It does not include XML/DTD/external
  resources/CSS/clipping/masks/`<use>`/gradients/stroke-outline generation. It executes no external code.

The implementation is based on the [W3C SVG path grammar](https://www.w3.org/TR/SVG/paths.html),
[arc parameter conversion](https://www.w3.org/TR/SVG/implnote.html#ArcImplementationNotes),
[transformation matrices](https://www.w3.org/TR/SVG11/coords.html#TransformAttribute), and
[fill-rule](https://www.w3.org/TR/SVG/painting.html#FillRuleProperty).
It is in a separate package from the existing SVG-font-specific parser and does not change lettering behavior.

```kotlin
val matrix = Affine2D.parse("translate(-100 -100) scale(2)")
val lines = SvgPath.flatten("M0 0 C0 100 100 100 100 0 Z", matrix)
val area = PolygonRegion.fromPaths(lines, FillRule.EVEN_ODD)
val inset = area.offset(-10.0) // 1mm 안쪽, 구멍은 커짐; 사라지는 작은 부분은 빈 결과
val union = area.union(otherArea)
val cut = area.difference(otherArea)
val overlap = area.intersection(otherArea)
```

## Running stitch

`RunningStitch.sample` divides a polyline by arc length, fixing the start, end, and corners bending
by at least 45 degrees by default as anchors. Each interval between anchors is evenly divided to
produce a maximum length of **25 units=2.5mm**. It does not place a stitch on every short flattened
segment forming a gentle curve. `cornerAngleDegrees = 0.0` preserves every vertex; this setting
is used for edge underlay. Open paths are not closed.

`RunningStitch.generate(lines, RunningSettings(), thread)` records TRIM/JUMP between separate
paths, STITCH at the first point of each path, and END at the end. Integer-coordinate conversion
occurs only at the final output stage, and consecutive duplicate needle stitches at the same
position after rounding are removed. Empty paths return only END.

## Simple tatami and underlay

The defaults for `TatamiFill.generate(area, TatamiSettings(), thread)` are:

| Setting | Default |
| --- | --- |
| Fill direction | 0 degrees (horizontal); positive angles are clockwise on screen |
| Row spacing | 4 units=0.4mm |
| Maximum stitch length | 40 units=4mm |
| 4-row repeating offset | 0, 1/4, 1/2, 3/4 stitch length |
| Edge running underlay | Enabled, 1mm inside the boundary, maximum 2.5mm stitches |

The region is moved into a coordinate system rotated opposite to the fill direction and intersected
with scanlines. Holes and disconnected regions are split into separate intervals. Rows alternate
direction, and offsets of a global x-grid reduce the concentration of aligned needle holes along a
line. Both interval endpoints are always retained, so stitches at the ends may be short. At least
one central row is attempted even for very thin shapes. If a concavity has no row or is cut off by
a hole, some detailed areas may receive no stitches.

A connecting stitch is added only when the path from the end of the previous interval to the next
start lies completely within the region and is at most 4mm. Otherwise, travel uses TRIM/JUMP.
Underlay first follows the outer and hole contours of an inset boundary created with a negative
buffer; it is omitted where narrow regions cause the inset to disappear. Region subdivision uses
scanline intersection intervals. Advanced routing such as region-adjacency graphs, Euler traversal,
and optimal underpaths is follow-up scope.

The algorithms directly implement general arc-length resampling and scanline intersection. They
do not port Ink/Stitch code or copy external algorithm code, and no separate Ink/Stitch copyright
is added.

```kotlin
val running = RunningStitch.generate(lines, RunningSettings(stitchLength = 25.0))
val fill = TatamiFill.generate(area, TatamiSettings(angleDegrees = 35.0))
val pes = PesWriter.writeV6(fill)
```

Final rounding may increase needle-position error by up to √0.5≈0.707 units (0.071mm) and the
length between two points by up to √2 units. Short stitches at corners/interval ends, automatic
knots/tie-in/tie-off, pull compensation, fabric-specific density adjustment, and advanced connections
between detailed regions are not yet provided. Actual machine sewing verification remains outstanding.
Apply the existing machine Preflight separately to generated output.

## Input limits and verification

NaN/infinity, invalid commands/arc flags, and spacing/stitch lengths of 0 or less are rejected with
exceptions. Point coordinates are limited to an absolute value of 10⁹ units; flattening/polygon/
running input to 200,000 points; Bézier subdivision depth to 24; fill to 100,000 rows; and generated
output to 1,000,000 commands. These are separate from machine limits.

```bash
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test
/path/to/pyembroidery-1.5.1-venv/bin/python tools/check_digitizing.py
/path/to/pyembroidery-1.5.1-venv/bin/python tools/check_writer.py
```

Unit tests check grammar, matrix order, error against dense independent curve samples, arc
degeneracy, fill-rule, overlaps, holes, self-intersections, polygon operations, corner preservation,
row spacing, offsets, angles, underlay, and invalid input. 6 types—square, donut, concave region,
disconnected region, Bézier, and arc—are generated as running/horizontal tatami/37-degree tatami,
and **36 PES files** exported as v1/v6 are read with both the internal reader and pyembroidery
1.5.1. Python checks commands, threads, headers, sewing objects, and thumbnails. Separate even-odd
containment/segment-intersection code also checks fill intrusion into holes, stitch lengths, and
TRIM between disconnected paths. A PEC-thumbnail pixel-boundary difference found with these
inputs was fixed by matching the floating-point operation order of the original pyembroidery.
The existing writer cross-verification must also pass again.

Final verification: **168 engine tests passed**, and **36 generated PES files**, **59 existing
writer outputs**, and **4 multiposition outputs** were cross-verified with pyembroidery 1.5.1.
`:app:assembleDebug` succeeded, and the APK was confirmed to contain 3 EDL original-text/notice
files under `licenses/jts/`. No app-flow integration was performed.
