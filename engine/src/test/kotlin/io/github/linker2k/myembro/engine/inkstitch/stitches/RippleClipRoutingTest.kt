// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/ripple_stitch.py and lib/stitches/tatami_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import org.locationtech.jts.geom.*
import kotlin.test.*

/** Structural/analytic tests; no Ink/Stitch execution was used to manufacture expected outputs. */
class RippleClipRoutingTest {
    private val factory = GeometryFactory()
    private val parameters = RippleParameters(running_stitch_length_mm = listOf(0.5), running_stitch_tolerance_mm = 0.001)
    private fun rectangle(x: Double, y: Double, width: Double, height: Double): Polygon = factory.createPolygon(arrayOf(
        Coordinate(x, y), Coordinate(x + width, y), Coordinate(x + width, y + height), Coordinate(x, y + height), Coordinate(x, y)))
    private fun stitch(vararg coords: Pair<Double, Double>) = coords.map { (x, y) -> Stitch(x, y) }
    private fun route(stitches: List<Stitch>, clip: Geometry, removeStart: Boolean = true, removeEnd: Boolean = true, start: Point? = null) =
        RippleClipRouting.route(stitches, clip, parameters, removeStart, removeEnd, start)
    private fun line(a: Stitch, b: Stitch) = factory.createLineString(arrayOf(Coordinate(a.x, a.y), Coordinate(b.x, b.y)))

    @Test fun oneCrossingRetainsClippedSegmentCoordinates() {
        val groups = route(stitch(-2.0 to 5.0, 12.0 to 5.0), rectangle(0.0, 0.0, 10.0, 10.0))
        assertEquals(1, groups.size)
        // An Euler duplicate can reverse the first needle segment after terminal travel is stripped.
        assertEquals(setOf(Point(0.0, 5.0), Point(10.0, 5.0)),
            groups.single().filter { "travel" !in it.tags }.map { it.point }.toSet())
        assertTrue(groups.single().all { it.x in 0.0..10.0 && it.y in 0.0..10.0 })
        assertTrue(groups.single().filter { "travel" !in it.tags }.all { it.y == 5.0 })
    }

    @Test fun parallelClippedRowsAreJoinedByBoundaryTravelNotDirectDiagonals() {
        val clip = rectangle(0.0, 0.0, 10.0, 10.0)
        val groups = route(stitch(-2.0 to 2.0, 12.0 to 2.0, 12.0 to 8.0, -2.0 to 8.0), clip)
        val result = groups.single()
        assertEquals(Point(0.0, 2.0), result.first().point)
        assertEquals(Point(0.0, 8.0), result.last().point)
        val travel = result.filter { "travel" in it.tags }
        assertTrue(travel.isNotEmpty())
        assertTrue(travel.all { clip.boundary.distance(factory.createPoint(Coordinate(it.x, it.y))) < 1e-7 })
        assertTrue(result.zipWithNext().all { (a, b) -> clip.covers(line(a, b)) })
        assertTrue(result.any { it.point == Point(10.0, 2.0) })
        assertTrue(result.any { it.point == Point(10.0, 8.0) })
    }

    @Test fun holeRoutesAlongItsOutlineWithoutCrossingRemovedInterior() {
        val outer = rectangle(0.0, 0.0, 20.0, 20.0)
        val hole = rectangle(8.0, 8.0, 4.0, 4.0)
        val clip = factory.createPolygon(outer.exteriorRing, arrayOf(hole.exteriorRing))
        val result = route(stitch(-1.0 to 10.0, 21.0 to 10.0), clip).single()
        assertTrue(result.any { it.point == Point(0.0, 10.0) && "travel" !in it.tags })
        assertTrue(result.any { it.point == Point(20.0, 10.0) && "travel" !in it.tags })
        assertTrue(result.filter { "travel" in it.tags }.isNotEmpty())
        assertTrue(result.zipWithNext().all { (a, b) -> (a.distance(b) == 0.0 || clip.buffer(1e-8).covers(line(a, b))) }, result.toString())
        assertTrue(result.any { it.point == Point(8.0, 10.0) })
        assertTrue(result.any { it.point == Point(12.0, 10.0) })
    }

    @Test fun disconnectedClipCreatesSeparateOrderedGroups() {
        val clip = factory.createMultiPolygon(arrayOf(rectangle(0.0, 0.0, 10.0, 10.0), rectangle(20.0, 0.0, 10.0, 10.0)))
        val groups = route(stitch(-2.0 to 5.0, 32.0 to 5.0), clip)
        assertEquals(2, groups.size)
        // Source sorts BEFORE stripping terminal travel. Both components temporarily travel to
        // the global end; moving the first matching component last reverses these two groups.
        assertEquals(listOf(listOf(30.0, 20.0, 20.0, 30.0), listOf(10.0, 0.0, 0.0, 10.0)),
            groups.map { group -> group.map { it.x } })
        assertTrue(groups.all { group -> group.zipWithNext().all { (a, b) -> (a.distance(b) == 0.0 || clip.buffer(1e-8).covers(line(a, b))) } })
    }

    @Test fun interiorGuideEndpointsDoNotAcquireInteriorTravelNeedlePoints() {
        val clip = rectangle(0.0, 0.0, 20.0, 20.0)
        val source = stitch(5.0 to 5.0, 15.0 to 5.0, 15.0 to 15.0)
        val result = route(source, clip, false, false, Point(10.0, 10.0)).single()
        val travel = result.filter { "travel" in it.tags }
        // The initial anchor marker is emitted before travel() in the original path_to_stitches.
        // All subsequent travel() points are restricted to the boundary's <1px band.
        assertTrue(travel.drop(1).all { clip.boundary.distance(factory.createPoint(Coordinate(it.x, it.y))) < 1.0 + 1e-8 })
        assertTrue(result.any { it.point == Point(5.0, 5.0) && "travel" !in it.tags })
        assertTrue(result.any { it.point == Point(15.0, 15.0) && "travel" !in it.tags })
    }

    @Test fun removeStartAndEndTravelOnlyStripsTheTerminalTravelRuns() {
        val clip = rectangle(0.0, 0.0, 10.0, 10.0)
        val source = stitch(-2.0 to 2.0, 12.0 to 2.0, 12.0 to 8.0, -2.0 to 8.0)
        val all = route(source, clip, false, false, Point(5.0, 0.0)).single()
        val trimmed = route(source, clip, true, true, Point(5.0, 0.0)).single()
        assertTrue("travel" !in trimmed.first().tags && "travel" !in trimmed.last().tags)
        val first = all.indexOfFirst { "travel" !in it.tags }
        val last = all.indexOfLast { "travel" !in it.tags }
        assertEquals(all.subList(first, last + 1), trimmed)
        assertTrue(all.size >= trimmed.size)
    }

    @Test fun originalEmptyIntersectionFallbackIsExplicitlyPreserved() {
        val source = stitch(30.0 to 30.0, 40.0 to 40.0)
        assertEquals(listOf(source), route(source, rectangle(0.0, 0.0, 10.0, 10.0)))
        assertEquals(emptyList(), route(emptyList(), rectangle(0.0, 0.0, 10.0, 10.0)))
    }

    @Test fun fixedClippedSvgExportsPesThroughSharedStitchPlan() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><path d="M-5 48 L101 48"/><clipPath id="cut"><path d="M0 0 H96 V96 H0 Z M40 40 H56 V56 H40 Z" fill-rule="evenodd"/></clipPath></svg>"""
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
        val d = (document.getElementsByTagName("path").item(0) as org.w3c.dom.Element).getAttribute("d")
        val paths = io.github.linker2k.myembro.engine.geometry.SvgPath.flatten(d).map { path -> path.points.map { Point(it.x, it.y) } }
        val outer = rectangle(0.0, 0.0, 96.0, 96.0)
        val hole = rectangle(40.0, 40.0, 16.0, 16.0)
        val clip = factory.createPolygon(outer.exteriorRing, arrayOf(hole.exteriorRing))
        val stitches = paths.single().map(::Stitch)
        val groups = route(stitches, clip).map { io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup(
            color = io.github.linker2k.myembro.engine.model.EmbThread(0xff000000.toInt()), stitches = it,
            lockStart = io.github.linker2k.myembro.engine.inkstitch.stitch_plan.InkLockStitch(),
            lockEnd = io.github.linker2k.myembro.engine.inkstitch.stitch_plan.InkLockStitch()) }
        val pattern = io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.fromGroups(groups)
        assertTrue(pattern.metadata[io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.LOCK_INDICES_KEY].orEmpty().isNotBlank())
        val bytes = io.github.linker2k.myembro.engine.io.PesWriter.write(pattern)
        val restored = io.github.linker2k.myembro.engine.io.PesReader.read(bytes)
        assertEquals(pattern.bounds, restored.bounds)
        assertTrue(restored.stitchCount > 10)
        assertTrue(pattern.stitches.zipWithNext().none { (a, b) ->
            a.command == io.github.linker2k.myembro.engine.model.Command.STITCH &&
                b.command == io.github.linker2k.myembro.engine.model.Command.STITCH && a.x == b.x && a.y == b.y
        })
        (System.getProperty("myembro.ripple.fixtureDir") ?: System.getenv("MYEMBRO_RIPPLE_FIXTURE_DIR")
            ?: "build/inkstitch-ripple").let { directory ->
            java.io.File(directory).apply { mkdirs() }.let { folder ->
                java.io.File(folder, "ripple-clipped-hole.svg").writeText(svg)
                java.io.File(folder, "ripple-clipped-hole.pes").writeBytes(bytes)
            }
        }
    }

    @Test fun selfIntersectionNodesDoNotReplaceNeedlePenetrationPattern() {
        val source = stitch(-2.0 to 2.0, 12.0 to 8.0, -2.0 to 8.0, 12.0 to 2.0)
        val groups = route(source, rectangle(0.0, 0.0, 10.0, 10.0))
        assertTrue(groups.isNotEmpty())
        assertTrue(groups.flatten().all { it.x in 0.0..10.0 && it.y in 0.0..10.0 })
        assertTrue(groups.flatten().any { "travel" in it.tags })
    }
}
