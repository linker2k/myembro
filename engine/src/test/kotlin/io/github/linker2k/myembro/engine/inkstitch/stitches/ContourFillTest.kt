// Ported from Ink/Stitch lib/stitches/contour_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Polygon
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** v3.3.0 has no contour module tests. Fixtures below assert its published formulas and topology. */
class ContourFillTest {
    private val factory = GeometryFactory()
    private fun rectangle(a: Double, b: Double, c: Double, d: Double): List<FillPoint> =
        listOf(FillPoint(a, b), FillPoint(c, b), FillPoint(c, d), FillPoint(a, d), FillPoint(a, b))
    private fun ring(points: List<FillPoint>): LinearRing = factory.createLinearRing(points.map { Coordinate(it.x, it.y) }.toTypedArray())
    private fun polygon(points: List<FillPoint>, holes: List<List<FillPoint>> = emptyList()): Polygon =
        factory.createPolygon(ring(points), holes.map(::ring).toTypedArray())
    private fun square(size: Double = 80.0) = polygon(rectangle(0.0, 0.0, size, size))

    @Test fun interpolationMatchesNumpyLinspaceAndLinearResamplingFormula() {
        // int(20 * 40 / 160) = 5 points; weights = 0, .25, .5, .75, 1.
        assertEquals(listOf(FillPoint(0.0, 0.0), FillPoint(9.5, 0.5), FillPoint(9.0, 9.0),
            FillPoint(1.5, 8.5), FillPoint(2.0, 2.0)),
            ContourFill.interpolateLinearRings(rectangle(0.0, 0.0, 10.0, 10.0),
                rectangle(2.0, 2.0, 8.0, 8.0), 160.0))
        assertTrue(ContourFill.interpolateLinearRings(rectangle(0.0, 0.0, 1.0, 1.0),
            rectangle(0.2, 0.2, 0.8, 0.8), 100.0).isEmpty())
    }

    @Test fun offsetsUseOriginalExteriorAndTenPixelNestedInsets() {
        val contours = ContourFill.offsetContours(square(100.0), 10.0, 2)
        assertEquals(5, contours.size)
        assertEquals(listOf(100.0, 80.0, 60.0, 40.0, 20.0), contours.map { path -> path.maxOf { it.x } - path.minOf { it.x } })
        assertTrue(contours.all { it.first() == it.last() })
        assertEquals(10000.0, square(100.0).area)
    }

    @Test fun holeOffsetsBecomeReverseAncestryAndNeverFillTheHole() {
        val shape = polygon(rectangle(0.0, 0.0, 100.0, 100.0), listOf(rectangle(20.0, 20.0, 80.0, 80.0)))
        val contours = ContourFill.offsetContours(shape, 5.0, 2)
        assertEquals(listOf(100.0, 90.0, 70.0, 60.0), contours.map { path -> path.maxOf { it.x } - path.minOf { it.x } })
        val stitches = ContourFill.fill(shape, ContourFillParameters(row_spacing_mm = 1.0, max_stitch_length_mm = 2.0))
        assertTrue(stitches.size > 100)
        assertTrue(stitches.all { shape.buffer(1e-7).covers(factory.createPoint(Coordinate(it.x, it.y))) })
        assertTrue(stitches.none { it.x > 20.01 && it.x < 79.99 && it.y > 20.01 && it.y < 79.99 })
    }

    @Test fun threeStrategiesAreDeterministicAndBoundStitchLength() {
        val shape = square()
        val paths = (0..2).map { strategy ->
            val options = ContourFillParameters(contour_strategy = strategy, row_spacing_mm = 1.0,
                max_stitch_length_mm = 2.0, running_stitch_tolerance_mm = 0.1)
            val stitches = ContourFill.fill(shape, options, FillPoint(-10.0, -10.0))
            assertEquals(stitches, ContourFill.fill(shape, options, FillPoint(-10.0, -10.0)))
            assertTrue(stitches.size > 30)
            assertTrue(stitches.all { it.x >= -1e-7 && it.y >= -1e-7 && it.x <= 80.0000001 && it.y <= 80.0000001 })
            assertTrue(stitches.zipWithNext().all { (a, b) -> hypot(a.x - b.x, a.y - b.y) <= 2.0 * 96.0 / 25.4 + 1e-5 })
            stitches
        }
        assertFalse(paths[0] == paths[1])
        assertFalse(paths[1] == paths[2])
        // Single winds inward, while double/Fermat returns to an outer ring.
        val single = paths[1].last()
        val double = paths[2].last()
        assertTrue(hypot(single.x - 40.0, single.y - 40.0) < hypot(double.x - 40.0, double.y - 40.0))
    }

    @Test fun innerToOuterVisitsBothChildrenAfterThinNeckSplits() {
        val shape = polygon(listOf(FillPoint(0.0, 0.0), FillPoint(30.0, 0.0), FillPoint(30.0, 14.0),
            FillPoint(70.0, 14.0), FillPoint(70.0, 0.0), FillPoint(100.0, 0.0), FillPoint(100.0, 30.0),
            FillPoint(70.0, 30.0), FillPoint(70.0, 16.0), FillPoint(30.0, 16.0), FillPoint(30.0, 30.0),
            FillPoint(0.0, 30.0), FillPoint(0.0, 0.0)))
        val stitches = ContourFill.fill(shape, ContourFillParameters(row_spacing_mm = 0.8, avoid_self_crossing = true))
        assertTrue(stitches.any { abs(it.x - 15.0) < 4.0 && abs(it.y - 15.0) < 4.0 })
        assertTrue(stitches.any { abs(it.x - 85.0) < 4.0 && abs(it.y - 15.0) < 4.0 })
    }

    @Test fun selfCrossingPolygonRepairsBothFacesAndMarksInterPartTravel() {
        val bowtie = polygon(listOf(FillPoint(0.0, 0.0), FillPoint(40.0, 40.0), FillPoint(0.0, 40.0),
            FillPoint(40.0, 0.0), FillPoint(0.0, 0.0)))
        assertFalse(bowtie.isValid)
        val stitches = ContourFill.fill(bowtie, ContourFillParameters(row_spacing_mm = 0.5))
        assertTrue(stitches.any { it.y < 10.0 })
        assertTrue(stitches.any { it.y > 30.0 })
        assertEquals(1, stitches.count { "jump" in it.tags })
        assertTrue(stitches.all { it.x.isFinite() && it.y.isFinite() })
    }

    @Test fun clockwiseMatchesSvgInvertedYAxisAndCanBeReversed() {
        fun signedArea(path: List<FillPoint>) = path.zipWithNext().sumOf { (a, b) -> a.x * b.y - b.x * a.y } / 2
        assertTrue(ContourFill.offsetContours(square(), 10.0, clockwise = true).all { signedArea(it) > 0 })
        assertTrue(ContourFill.offsetContours(square(), 10.0, clockwise = false).all { signedArea(it) < 0 })
    }

    @Test fun degenerateSpiralEmptyPolygonAndUnsupportedOptionsAreExplicit() {
        assertTrue(ContourFill.fill(factory.createPolygon()).isEmpty())
        // _make_spiral has no ring pairs when an inset consumes a thin shape.
        assertTrue(ContourFill.fill(square(0.5), ContourFillParameters(contour_strategy = 1, row_spacing_mm = 1.0)).isEmpty())
        assertFailsWith<IllegalArgumentException> { ContourFillParameters(row_spacing_mm = 0.0) }
        assertFailsWith<IllegalArgumentException> { ContourFillParameters(contour_strategy = 3) }
        assertFailsWith<IllegalArgumentException> { ContourFill.fill(square(), ContourFillParameters(enable_random_stitch_length = true)) }
    }

    @Test fun innerContourSmoothingPreservesEndpointsAndIsClampedAroundAHole() {
        val shape = polygon(rectangle(0.0, 0.0, 100.0, 100.0), listOf(rectangle(30.0, 30.0, 70.0, 70.0)))
        val options = ContourFillParameters(row_spacing_mm = 1.0, max_stitch_length_mm = 2.0,
            running_stitch_tolerance_mm = 0.02, smoothness_mm = 0.2)
        val smoothed = ContourFill.fill(shape, options)
        val unsmoothed = ContourFill.fill(shape, options.copy(smoothness_mm = 0.0))
        assertTrue(smoothed.isNotEmpty())
        assertFalse(smoothed == unsmoothed)
        assertTrue(smoothed.first().distance(unsmoothed.first()) < 1e-6)
        assertTrue(smoothed.last().distance(unsmoothed.last()) < 1e-6)
        val allowed = shape.buffer(0.02 * 96.0 / 25.4 + 1e-6)
        assertTrue(smoothed.zipWithNext().all { (a, b) -> allowed.covers(factory.createLineString(arrayOf(Coordinate(a.x, a.y), Coordinate(b.x, b.y)))) })
        assertEquals(smoothed, ContourFill.fill(shape, options))
    }

    @Test fun singleAndDoubleSpiralsIgnoreSmoothnessAsTheirSourceSignaturesDo() {
        for (strategy in 1..2) {
            val options = ContourFillParameters(contour_strategy = strategy, row_spacing_mm = 1.0)
            assertEquals(ContourFill.fill(square(), options), ContourFill.fill(square(), options.copy(smoothness_mm = 0.7)))
        }
    }

    @Test fun beanAndRepeatExtensionMatchSourceListSemantics() {
        val normal = ContourFill.fill(square(), ContourFillParameters(row_spacing_mm = 2.0))
        val bean = ContourFill.fill(square(), ContourFillParameters(row_spacing_mm = 2.0, bean_stitch_repeats = listOf(1)))
        assertEquals(1 + (normal.size - 1) * 3, bean.size)
        assertEquals(listOf(normal[0], normal[1], normal[0], normal[1]), bean.take(4))
        val repeated = ContourFill.fill(square(), ContourFillParameters(row_spacing_mm = 2.0, repeats = 3))
        assertEquals(normal + normal.reversed() + normal + normal.reversed(), repeated)
    }

    @Test fun fermatSharedApiPreservesOriginalEvenAndOddRingTraversal() {
        val rings = listOf(rectangle(0.0, 0.0, 40.0, 40.0), rectangle(5.0, 5.0, 35.0, 35.0),
            rectangle(10.0, 10.0, 30.0, 30.0), rectangle(15.0, 15.0, 25.0, 25.0))
        val lines = rings.map { factory.createLineString(it.map { p -> Coordinate(p.x, p.y) }.toTypedArray()) }
        val forward = ContourFill.interpolateLinearRings(rings[0], rings[2], 10.0, FillPoint(0.0, 0.0))
        val back = ContourFill.interpolateLinearRings(rings[1], rings[3], 10.0, FillPoint(0.0, 0.0))
        assertEquals(forward + back.reversed(), ContourFill.makeFermatSpiral(lines, 10.0, FillPoint(0.0, 0.0)))
    }
}
