// Ported from Ink/Stitch lib/stitches/circular_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.WKTReader
import kotlin.test.*

class CircularFillTest {
    private val reader = WKTReader()

    @Test fun beanPatternIgnoresTravelAndAccumulatedRepeatsMatchUpstream() {
        val a = FillPoint(0.0, 0.0)
        val b = FillPoint(4.0, 0.0)
        val travel = FillPoint(8.0, 0.0, setOf("travel"))
        val c = FillPoint(12.0, 0.0)
        val source = listOf(a, b, travel, c)
        assertEquals(listOf(a, b, a, b, travel, c, travel, c),
            CircularFill.applyBeanStitchAndRepeats(source, 1, listOf(1)))
        assertEquals(source + source.reversed(), CircularFill.applyBeanStitchAndRepeats(source, 2, listOf(0)))
        val doubled = source + source.reversed()
        assertEquals(doubled + doubled, CircularFill.applyBeanStitchAndRepeats(source, 3, listOf(0)))
    }

    @Test fun smallShapePreservesUpstreamUnclippedCircleFallback() {
        val circle = GeometryFactory().createPoint(Coordinate(0.0, 0.0)).buffer(0.1, 16)
        val result = CircularFill.generate(circle, CircularFill.Parameters(row_spacing_mm = 1.0))
        assertTrue(result.size > 3)
        assertEquals(PIXELS_PER_MM, result.first().x, 1e-9)
        assertTrue(result.any { !circle.covers(circle.factory.createPoint(Coordinate(it.x, it.y))) })
    }

    @Test fun fixedCircleIsDeterministicAndReturnsOutwardAfterCenter() {
        val region = GeometryFactory().createPoint(Coordinate(40.0, 40.0)).buffer(30.0, 16)
        val p = CircularFill.Parameters(row_spacing_mm = 1.0, running_stitch_length_mm = 1.5)
        val result = CircularFill.generate(region, p)
        assertEquals(result, CircularFill.generate(region, p))
        assertTrue(result.size > 25)
        assertTrue(result.all { region.buffer(0.01).covers(region.factory.createPoint(Coordinate(it.x, it.y))) })
        val center = FillPoint(40.0, 40.0)
        assertTrue(result.minOf { it.distance(center) } < p.row_spacing_mm * PIXELS_PER_MM * 2)
        assertTrue(result.first().distance(center) > 20)
        assertTrue(result.last().distance(center) > 20)
    }

    @Test fun holeIsClippedAndExplicitTargetAndEndSpacingAreAccepted() {
        val region = reader.read("POLYGON ((0 0,80 0,80 60,0 60,0 0),(25 20,25 40,45 40,45 20,25 20))")
        val result = CircularFill.generate(region,
            CircularFill.Parameters(row_spacing_mm = 1.0, end_row_spacing_mm = 1.5), target = FillPoint(15.0, 30.0))
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { region.buffer(0.01).covers(region.factory.createPoint(Coordinate(it.x, it.y))) })
    }

    @Test fun startEndFollowProjectedOutlineSubstring() {
        val rectangle = reader.read("POLYGON ((0 0,20 0,20 10,0 10,0 0))")
        val path = listOf(FillPoint(10.0, 0.0), FillPoint(10.0, 10.0))
        val result = CircularFill.applyStartEndCommands(rectangle, path, FillPoint(5.0, -2.0), FillPoint(3.0, 11.0))
        assertEquals(FillPoint(5.0, 0.0), result.first())
        assertEquals(FillPoint(3.0, 10.0), result.last())
        assertTrue(result.containsAll(path))
    }

    @Test fun emptyShapesAndUnsupportedRandomizationAreExplicit() {
        assertTrue(CircularFill.generate(GeometryFactory().createPolygon()).isEmpty())
        assertFailsWith<IllegalArgumentException> { CircularFill.Parameters(enable_random_stitch_length = true) }
        assertFailsWith<IllegalArgumentException> { CircularFill.Parameters(row_spacing_mm = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { CircularFill.Parameters(bean_stitch_repeats = emptyList()) }
        assertFailsWith<IllegalArgumentException> { CircularFill.Parameters(repeats = 21) }
    }
}
