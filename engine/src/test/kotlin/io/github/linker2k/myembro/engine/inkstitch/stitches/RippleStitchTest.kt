// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/ripple_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.InkLockStitch
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.model.EmbThread
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import kotlin.test.*

/** No upstream Ripple tests exist; expected coordinates below are derived from source formulas. */
class RippleStitchTest {
    private val outline = listOf(Point(0.0, 0.0), Point(10.0, 0.0))
    private val input = RippleInput(listOf(outline), target = Point(0.0, 10.0))
    private val manual = RippleParameters(manual_pattern_placement = true, line_count = 3,
        running_stitch_length_mm = listOf(100.0))

    @Test fun getterDefaultsAndSvgNamesArePreserved() {
        val p = RippleParameters.fromAttributes(emptyMap(), "ripple42")
        assertEquals(1.0, p.staggers) // Getter differs from the upstream parameter dialog's 0.
        assertEquals("line_count", p.satin_guide_pattern_position)
        assertEquals("ripple42", p.random_seed)
        assertEquals(listOf(2.5), p.running_stitch_length_mm)
        assertEquals(2.5 * 96 / 25.4, p.lengthsPx.single(), 1e-10)
        val attrs = mapOf("inkstitch:running_stitch_length_mm" to "1 2 3", "skip_start" to "-2",
            "line_count" to "0", "grid_size_mm" to "-3", "exponent" to "0", "flip_copies" to "no")
        val custom = RippleParameters.fromAttributes(attrs)
        assertEquals(listOf(1.0, 2.0, 3.0), custom.running_stitch_length_mm)
        assertEquals(2, custom.skip_start); assertEquals(1, custom.line_count)
        assertEquals(3.0, custom.grid_size_mm); assertEquals(0.1, custom.exponent)
        assertFalse(custom.flip_copies)
    }

    @Test fun stepsMatchLinspaceExponentAndFlip() {
        assertEquals(listOf(0.0, 0.25, 1.0), RippleStitch.steps(3, exponent = 2.0))
        assertEquals(listOf(0.0, 0.75, 1.0), RippleStitch.steps(3, exponent = 2.0, flip = true))
        assertEquals(listOf(0.0), RippleStitch.steps(1))
        assertEquals(listOf(1.0, 0.5, 0.0), RippleStitch.steps(3, 1.0, 0.0))
    }

    @Test fun targetRowsAndManualEvenRowReversalFollowSource() {
        val points = RippleStitch.generate(input, manual).single().map { it.point }
        assertEquals(listOf(Point(10.0, 0.0), Point(0.0, 0.0), Point(0.0, 5.0),
            Point(5.0, 5.0), Point(0.0, 10.0), Point(0.0, 10.0)), points)
    }

    @Test fun skipOverflowResetsBothSkipsAndReverseChangesWholePath() {
        val baseline = RippleStitch.generate(input, manual).single()
        assertEquals(baseline, RippleStitch.generate(input, manual.copy(skip_start = 3, skip_end = 1)).single())
        assertEquals(baseline.reversed(), RippleStitch.generate(input, manual.copy(reverse = true)).single())
        val oneRow = RippleStitch.generate(input, manual.copy(skip_start = 1, skip_end = 1)).single()
        assertEquals(listOf(Point(5.0, 5.0), Point(0.0, 5.0)), oneRow.map { it.point })
    }

    @Test fun minimumLineSpacingKeepsConfiguredParityAndExtraPointJoinRow() {
        val p = manual.copy(min_line_dist_mm = Units.pixelsToMm(1.0), line_count = 10)
        assertEquals(10, RippleStitch.lineCountAdjust(11, p, false))
        assertEquals(11, RippleStitch.lineCountAdjust(11, p, true))
        assertEquals(11, RippleStitch.lineCountAdjust(11, p.copy(join_style = 1), false))
        assertEquals(1, RippleStitch.lineCountAdjust(0, p, false))
    }

    @Test fun repeatIncludesTurnaroundDuplicatesAndBeanRunsAfterRepeats() {
        assertEquals(listOf(1, 2, 3, 3, 2, 1, 1, 2, 3), RippleStitch.repeatCoordinates(listOf(1, 2, 3), 3))
        val points = RippleStitch.generate(input, manual).single()
        val twice = RippleStitch.generate(input, manual.copy(repeats = 2)).single()
        assertEquals(points + points.reversed(), twice)
        val bean = RippleStitch.generate(input, manual.copy(bean_stitch_repeats = listOf(1))).single()
        assertEquals(RunningStitch.beanStitch(points, listOf(1)), bean)
    }

    @Test fun guideAppliesTranslateRotateAndAxisScaleAroundGuidePoint() {
        val guide = RippleGuide.Line(listOf(Point(0.0, 0.0), Point(0.0, 20.0)))
        val result = RippleStitch.generate(input.copy(guide = guide), manual.copy(
            flip_copies = false, scale_start = 100.0, scale_end = 50.0)).single().map { it.point }
        assertEquals(listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(0.0, 10.0),
            Point(7.5, 10.0), Point(0.0, 20.0), Point(5.0, 20.0)), result)
    }

    @Test fun gridStartsAtNearestOfFourCorners() {
        val grid = listOf(listOf(Point(0.0, 0.0), Point(0.0, 5.0)), listOf(Point(10.0, 0.0), Point(10.0, 5.0)))
        assertEquals(Point(0.0, 0.0), RippleStitch.nearestGridCorner(grid, Point(-1.0, -1.0)).first().first())
        assertEquals(Point(0.0, 5.0), RippleStitch.nearestGridCorner(grid, Point(-1.0, 6.0)).first().first())
        assertEquals(Point(10.0, 0.0), RippleStitch.nearestGridCorner(grid, Point(11.0, -1.0)).first().first())
        assertEquals(Point(10.0, 5.0), RippleStitch.nearestGridCorner(grid, Point(11.0, 6.0)).first().first())
    }

    @Test fun multiRailInputUsesExactSatinSamplerAndPreservesGroupMetadata() {
        var requested: Triple<Double, String, Boolean>? = null
        val rails = object : RippleSatinGeometry {
            override val centerLine = listOf(Point(0.0, 5.0), Point(10.0, 5.0))
            override val rungPairs = emptyList<Pair<Point, Point>>()
            override fun plotPointsOnRails(spacingPx: Double, reverseRails: String, swapRails: Boolean): List<Pair<Point, Point>> {
                requested = Triple(spacingPx, reverseRails, swapRails)
                return listOf(Point(0.0, 0.0) to Point(0.0, 10.0), Point(10.0, 0.0) to Point(10.0, 10.0))
            }
        }
        val template = StitchGroup(color = EmbThread(0xff112233.toInt()), trimAfter = true)
        val groups = RippleStitch.generateGroups(RippleInput(listOf(outline, outline), satin = rails), manual.copy(
            reverse_rails = "both", swap_satin_rails = true), template)
        assertEquals(Triple(manual.tolerancePx, "both", true), requested)
        assertEquals(template.color, groups.single().color); assertTrue(groups.single().trimAfter)
        assertEquals(listOf(Point(10.0, 0.0), Point(0.0, 0.0), Point(0.0, 5.0), Point(10.0, 5.0),
            Point(10.0, 10.0), Point(0.0, 10.0)), groups.single().stitches.map { it.point })
    }

    @Test fun closedOutlineConvergesToSpiralAndStaysWithinBounds() {
        val square = listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0), Point(0.0, 10.0), Point(0.0, 0.0))
        val result = RippleStitch.generate(RippleInput(listOf(square), target = Point(5.0, 5.0)), manual).single()
        assertEquals(15, result.size) // 4 helper levels, converged to 3 spiral rows × 5 outline nodes.
        assertTrue(result.all { it.x in 0.0..10.0 && it.y in 0.0..10.0 })
        assertTrue(result.first().point != square.last())
    }

    @Test fun manualConnectorsAreSubdividedUsingFirstStitchPatternLength() {
        val p = manual.copy(running_stitch_length_mm = listOf(Units.pixelsToMm(2.0), Units.pixelsToMm(5.0)))
        val result = RippleStitch.generate(input, p).single()
        assertTrue(result.size > 6)
        // Original outline node-to-node movements remain manual, but each inter-copy connector is split.
        val start = result.indexOfFirst { it.point == Point(0.0, 0.0) }
        val end = result.indexOfFirst { it.point == Point(0.0, 5.0) }
        assertTrue((start until end).all { result[it].distance(result[it + 1]) <= 2.0 + 1e-9 })
    }

    @Test fun clippedRippleRequiresExactRouterAndForwardsTravelFlags() {
        val factory = GeometryFactory()
        val clip = factory.createPolygon(arrayOf(Coordinate(-1.0, -1.0), Coordinate(11.0, -1.0),
            Coordinate(11.0, 11.0), Coordinate(-1.0, 11.0), Coordinate(-1.0, -1.0)))
        assertFailsWith<UnsupportedOperationException> { RippleStitch.generate(input.copy(clipShape = clip, clipRouter = null), manual) }
        var called = false
        val router = RippleClipRouter { stitches, shape, _, removeStart, removeEnd, starting ->
            assertSame(clip, shape); assertTrue(removeStart); assertTrue(removeEnd); assertNull(starting)
            called = true; listOf(stitches)
        }
        assertEquals(RippleStitch.generate(input, manual), RippleStitch.generate(input.copy(clipShape = clip, clipRouter = router), manual))
        assertTrue(called)
    }

    @Test fun adaptiveSatinGuideUsesAnchoredScaleForOverlapChecks() {
        val rectangle = listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 2.0), Point(0.0, 2.0), Point(0.0, 0.0))
        val guide = object : RippleSatinGeometry {
            override val centerLine = listOf(Point(30.0, 30.0), Point(30.0, 35.0))
            override val rungPairs = emptyList<Pair<Point, Point>>()
            override fun plotPointsOnRails(spacingPx: Double, reverseRails: String, swapRails: Boolean): List<Pair<Point, Point>> {
                assertEquals(0.1, spacingPx)
                return listOf(30.0, 33.0, 35.0).map { y -> Point(20.0, y) to Point(40.0, y) }
            }
        }
        val result = RippleStitch.generate(RippleInput(listOf(rectangle), guide = RippleGuide.Satin(guide),
            anchor = Point(0.0, 0.0) to Point(10.0, 0.0)), manual.copy(satin_guide_pattern_position = "adaptive")).single()
        // 2x scale around each translated rail origin makes y=33 overlap the preceding 4px-high bbox.
        // Only y=30 and y=35 remain; closed-shape convergence makes a single spiral row.
        val expected = listOf(Point(20.0, 35.0), Point(20.0, 37.75), Point(40.0, 36.5), Point(40.0, 31.25), Point(20.0, 30.0))
        assertEquals(expected, result.map { it.point })
    }

    @Test fun fixedSvgPathCreatesReadablePesWithoutQuantizationEscapes() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0 L75 0 L75 75 L0 75 Z"/></svg>"""
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(svg.byteInputStream())
        val data = (document.getElementsByTagName("path").item(0) as org.w3c.dom.Element).getAttribute("d")
        val paths = io.github.linker2k.myembro.engine.geometry.SvgPath.flatten(data)
            .map { path -> path.points.map { Point(it.x, it.y) } }
        val groups = RippleStitch.generateGroups(RippleInput(paths), RippleParameters(line_count = 8, staggers = 0.0),
            StitchGroup(color = EmbThread(0xff000000.toInt()), lockStart = InkLockStitch(), lockEnd = InkLockStitch()))
        val pattern = io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.fromGroups(groups)
        assertTrue(pattern.metadata[io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.LOCK_INDICES_KEY].orEmpty().isNotBlank())
        val bytes = io.github.linker2k.myembro.engine.io.PesWriter.write(pattern)
        val restored = io.github.linker2k.myembro.engine.io.PesReader.read(bytes)
        assertEquals(pattern.bounds, restored.bounds)
        assertTrue(restored.stitchCount > 30)
        assertEquals(1, restored.threads.size)
        assertTrue(restored.stitches.filter { it.command == io.github.linker2k.myembro.engine.model.Command.STITCH }
            .all { it.x in 0..199 && it.y in 0..199 })
        // Always produce source-default lock fixtures; property/environment can override the build destination.
        (System.getProperty("myembro.ripple.fixtureDir") ?: System.getenv("MYEMBRO_RIPPLE_FIXTURE_DIR")
            ?: "build/inkstitch-ripple").let { directory ->
            java.io.File(directory).apply { mkdirs() }.let { folder ->
                java.io.File(folder, "ripple-square.svg").writeText(svg)
                java.io.File(folder, "ripple-square.pes").writeBytes(bytes)
            }
        }
    }

    private class TrackedStitches(override val size: Int) : AbstractList<Stitch>() {
        var reads = 0
        override fun get(index: Int): Stitch {
            check(index in indices); reads++
            return Stitch(index.toDouble(), 0.0)
        }
    }
    private fun routedInput(router: RippleClipRouter): RippleInput {
        val clip = GeometryFactory().createPolygon(arrayOf(Coordinate(-1.0, -1.0), Coordinate(11.0, -1.0),
            Coordinate(11.0, 11.0), Coordinate(-1.0, 11.0), Coordinate(-1.0, -1.0)))
        return input.copy(clipShape = clip, clipRouter = router)
    }

    @Test fun totalInputPointsAreRejectedBeforeAnyCoordinateArraysAreAllocated() {
        var reads = 0
        val points = object : AbstractList<Point>() {
            override val size = 500_001
            override fun get(index: Int): Point { reads++; return Point(0.0, 0.0) }
        }
        assertFailsWith<IllegalArgumentException> { RippleStitch.generate(RippleInput(listOf(points, points)), manual) }
        assertEquals(0, reads)
    }

    @Test fun routedGroupBudgetCountsAllGroupsBeforeReadingOrRepeatingThem() {
        val a = TrackedStitches(600_000); val b = TrackedStitches(600_000)
        val source = routedInput(RippleClipRouter { _, _, _, _, _, _ -> listOf(a, b) })
        assertFailsWith<IllegalArgumentException> { RippleStitch.generate(source, manual) }
        assertEquals(0, a.reads + b.reads)
    }

    @Test fun repeatBudgetCountsAllGroupsBeforeAllocatingRepeatedLists() {
        val a = TrackedStitches(2); val b = TrackedStitches(2)
        val source = routedInput(RippleClipRouter { _, _, _, _, _, _ -> listOf(a, b) })
        assertFailsWith<IllegalArgumentException> { RippleStitch.generate(source, manual.copy(repeats = 300_001)) }
        assertEquals(0, a.reads + b.reads)
    }

    @Test fun beanBudgetCountsAllGroupsBeforeAllocatingExpandedOrRepeatedLists() {
        val a = TrackedStitches(2); val b = TrackedStitches(2)
        val source = routedInput(RippleClipRouter { _, _, _, _, _, _ -> listOf(a, b) })
        // Each group would be 500002 stitches; their sum must fail before either is materialized.
        assertFailsWith<IllegalArgumentException> {
            RippleStitch.generate(source, manual.copy(bean_stitch_repeats = listOf(250_000)))
        }
        assertEquals(0, a.reads + b.reads)
    }

    @Test fun gridAndRepeatedGroupsShareOneBudgetBeforeRepeatAllocation() {
        val original = TrackedStitches(2); val grid = TrackedStitches(4)
        var routes = 0
        val source = routedInput(RippleClipRouter { _, _, _, _, _, _ ->
            routes++; if (routes == 1) listOf(original) else listOf(grid)
        })
        assertFailsWith<IllegalArgumentException> {
            RippleStitch.generate(source, manual.copy(repeats = 499_999, grid_size_mm = 100.0))
        }
        assertEquals(2, routes)
        assertEquals(1, original.reads) // Only the mathematically repeated endpoint is inspected.
        assertEquals(0, grid.reads)
    }

    @Test fun missingRailSamplerAndUnboundedRequestsFailExplicitly() {
        assertFailsWith<IllegalArgumentException> { RippleStitch.generate(RippleInput(listOf(outline, outline)), manual) }
        assertFailsWith<IllegalArgumentException> { RippleStitch.repeatCoordinates(listOf(1, 2), Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { RippleParameters(exponent = Double.NaN) }
        assertEquals(emptyList(), RippleStitch.generate(RippleInput(emptyList()), manual))
    }
}
