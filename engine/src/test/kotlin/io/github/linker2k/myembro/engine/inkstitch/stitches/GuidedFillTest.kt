// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.stitches

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.io.WKTReader
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.*

/** Upstream v3.3.0 has no guided_fill unit tests; these are analytic SVG-pixel fixtures. */
class GuidedFillTest {
    private val factory = GeometryFactory()
    private val reader = WKTReader(factory)
    private val mmPerPixel = 1 / (96.0 / 25.4)
    private fun polygon(wkt: String) = reader.read(wkt) as Polygon
    private fun guide(wkt: String) = reader.read(wkt)
    private val square get() = polygon("POLYGON((0 0,10 0,10 10,0 10,0 0))")
    private val horizontal get() = guide("LINESTRING(-2 0,12 0)")
    private fun parameters(strategy: Int = 0) = GuidedFill.Parameters(
        guided_fill_strategy = strategy, row_spacing_mm = mmPerPixel,
        max_stitch_length_mm = 2 * mmPerPixel, running_stitch_length_mm = 2 * mmPerPixel,
        running_stitch_tolerance_mm = .05 * mmPerPixel,
    )

    @Test fun copyUsesTranslationNormalAndPositiveThenNegativeRowSweep() {
        val guide = GuidedFill.prepareGuideLine(horizontal, square, 0)
        val rows = GuidedFill.grating(square, guide, parameters())
        assertEquals(11, rows.size)
        rows.forEachIndexed { i,row -> assertEquals(i.toDouble(),row.coordinate.y,1e-8) }
        assertTrue(rows.all { abs(it.coordinates.first().y-it.coordinates.last().y) < 1e-9 })
        val shifted = guide("LINESTRING(-2 5,12 5)")
        val shiftedRows = GuidedFill.grating(square, GuidedFill.prepareGuideLine(shifted,square,0), parameters())
        assertEquals(11,shiftedRows.size)
        listOf(5.0,6.0,7.0,8.0,9.0,10.0,4.0,3.0,2.0,1.0,0.0).zip(shiftedRows).forEach { (expected,row) -> assertEquals(expected,row.coordinate.y,1e-8) }
    }

    @Test fun explicitCopyAngleIsInSvgDegrees() {
        val diagonalGuide = guide("LINESTRING(-2 -2,12 12)")
        val parameters = parameters().copy(guided_fill_angle=90.0)
        val rows = GuidedFill.grating(square,GuidedFill.prepareGuideLine(diagonalGuide,square,0),parameters)
        assertTrue(rows.size > 4)
        assertEquals(1.0,rows[1].coordinate.y-rows[0].coordinate.y,1e-8)
        assertEquals(0.0,rows[1].coordinate.x-rows[0].coordinate.x,1e-8)
    }

    @Test fun offsetHasOriginalMacZeroDistanceWorkaround() {
        val rows = GuidedFill.grating(square,GuidedFill.prepareGuideLine(horizontal,square,1),parameters(1))
        assertTrue(rows.size>=10)
        assertEquals(.0001,rows.first().coordinate.y,1e-8)
        assertEquals(1.0,rows[1].coordinate.y,1e-8)
    }

    @Test fun bufferUsesHalfSpacingRadiiAndStopsOnceEnvelopeIsCovered() {
        val inside = guide("LINESTRING(2 5,8 5)")
        val prepared = GuidedFill.prepareGuideLine(inside,square,2)
        val rows = GuidedFill.grating(square,prepared,parameters(2))
        assertEquals(5,rows.size)
        assertTrue(rows.all { it.isClosed })
        for ((i,row) in rows.withIndex()) assertEquals(i+.5, row.distance(prepared), .01)
    }

    @Test fun copyClipsHolesAndKeepsEverySewnEdgeInside() {
        val hole = polygon("POLYGON((0 0,10 0,10 10,0 10,0 0),(3 3,3 7,7 7,7 3,3 3))")
        val rows=GuidedFill.stitchSegments(hole,horizontal,parameters())
        assertTrue(rows.size>9)
        rows.forEach { row ->
            assertTrue(row.isNotEmpty())
            assertTrue("fill_row_start" in row.first().tags)
            assertTrue("fill_row_end" in row.last().tags)
            assertTrue(row.all { "guided_fill" in it.tags && "fill_row" in it.tags })
            row.zipWithNext().forEach { (a,b) ->
                assertTrue(hole.covers(factory.createLineString(arrayOf(Coordinate(a.x,a.y),Coordinate(b.x,b.y)))))
                assertTrue(hypot(a.x-b.x,a.y-b.y)<=2.000001)
            }
        }
        assertTrue(rows.none { row -> row.any { it.x>3.0 && it.x<7.0 && it.y>3.0 && it.y<7.0 } })
    }

    @Test fun allStrategiesRespectHolesOnCurvedGuide() {
        val hole = polygon("POLYGON((0 0,20 0,20 20,0 20,0 0),(7 7,7 13,13 13,13 7,7 7))")
        val bent = guide("LINESTRING(-5 5,10 3,25 5)")
        for (strategy in 0..2) {
            val rows=GuidedFill.stitchSegments(hole,bent,parameters(strategy))
            assertTrue(rows.isNotEmpty(),"strategy=$strategy")
            for(row in rows) for ((a,b) in row.zipWithNext()) {
                assertTrue(a.x.isFinite() && a.y.isFinite())
                assertTrue(hole.buffer(1e-8).covers(factory.createLineString(arrayOf(Coordinate(a.x,a.y),Coordinate(b.x,b.y)))),"strategy=$strategy: $a -> $b")
            }
        }
    }

    @Test fun closedGuideAndAnchorWorkForOffsetAndBuffer() {
        val ring = guide("LINESTRING(3 3,7 3,7 7,3 7,3 3)")
        val anchor = guide("MULTILINESTRING((-2 5,12 5),(5 -2,5 12))")
        for(strategy in 1..2) {
            val rows=GuidedFill.stitchSegments(square,ring,parameters(strategy),anchor)
            assertTrue(rows.isNotEmpty(),"strategy=$strategy")
            assertTrue(rows.flatten().all { square.buffer(1e-7).covers(factory.createPoint(Coordinate(it.x,it.y))) })
        }
    }

    @Test fun zeroStaggersAndShortRowsNeverDivideByZero() {
        val tiny=polygon("POLYGON((0 0,1 0,1 1,0 1,0 0))")
        val rows=GuidedFill.stitchSegments(tiny,horizontal,parameters().copy(staggers=0.0,row_spacing_mm=.5*mmPerPixel))
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.flatten().all { it.x.isFinite() && it.y.isFinite() })
    }

    @Test fun routedRowsPreserveSkipLastAndBeanOptions() {
        val regular=GuidedFill.generate(square,horizontal,parameters())
        val skipped=GuidedFill.generate(square,horizontal,parameters().copy(skip_last=true))
        val bean=GuidedFill.generate(square,horizontal,parameters().copy(bean_stitch_repeats=listOf(1)))
        assertTrue(regular.isNotEmpty())
        assertTrue(skipped.count { "fill_row" in it.tags } < regular.count { "fill_row" in it.tags })
        assertTrue(bean.size>regular.size)
        assertEquals(regular.first().x,bean.first().x)
    }

    @Test fun smoothedCurvedRowsStayFiniteAndClipped() {
        val bent = guide("LINESTRING(-5 2,5 8,15 2)")
        val rows = GuidedFill.stitchSegments(square,bent,parameters().copy(smoothness_mm=.1))
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.flatten().all { square.buffer(1e-8).covers(factory.createPoint(Coordinate(it.x,it.y))) })
    }

    @Test fun invalidSelfCrossingRegionIsRepairedBeforeFill() {
        val invalid=guide("POLYGON((0 0,10 10,0 10,10 0,0 0))")
        assertFalse(invalid.isValid)
        val stitches=GuidedFill.generate(invalid,horizontal,parameters())
        assertTrue(stitches.isNotEmpty())
        assertTrue(stitches.all { it.x.isFinite() && it.y.isFinite() })
    }

    @Test fun defaultsMatchSvgGetterUnitsAndGlobalMinimumFallback() {
        val p = GuidedFill.Parameters()
        assertEquals(0,p.guided_fill_strategy)
        assertNull(p.guided_fill_angle)
        assertEquals(.25,p.row_spacing_mm)
        assertEquals(4.0,p.max_stitch_length_mm)
        assertEquals(4.0,p.staggers)
        assertEquals("flexible",p.stitch_position_method)
        assertFalse(p.skip_last)
        assertTrue(p.underpath)
        assertEquals(2.5,p.running_stitch_length_mm)
        assertEquals(.2,p.running_stitch_tolerance_mm)
        assertNull(p.min_stitch_length_mm)
        assertEquals(.1 / mmPerPixel,p.minimum,1e-12)
        assertEquals(p.minimum,p.copy(min_stitch_length_mm=0.0).minimum,1e-12)
        assertEquals(.7 / mmPerPixel,p.copy(min_stitch_length_mm=.7).minimum,1e-12)
        val clamped=p.copy(row_spacing_mm=0.0,max_stitch_length_mm=0.0,
            running_stitch_length_mm=0.0,running_stitch_tolerance_mm=0.0)
        assertEquals(.1 / mmPerPixel,clamped.rowSpacing,1e-12)
        assertEquals(.1 / mmPerPixel,clamped.maxLength,1e-12)
        assertEquals(.01,clamped.runningLength)
        assertEquals(.01,clamped.tolerance)
    }

    @Test fun missingGuideUsesFillAngleButEmptyGratingUsesGuideAngle() {
        val p=parameters().copy(angle=90.0)
        val expectedMissing=TatamiFill.fill(square,Math.PI/2,p.rowSpacing,null,p.maxLength,
            p.runningLength,p.tolerance,p.staggers,p.skip_last,null,null,p.underpath)
        assertEquals(expectedMissing,GuidedFill.generate(square,guide("LINESTRING EMPTY"),p))
        // A horizontal guide shifted along its own axis cannot reach this square.
        // The upstream empty-grating fallback uses the guide slope, ignoring angle=90.
        val distant=guide("LINESTRING(-2 100,12 100)")
        val copyAlongGuide=p.copy(guided_fill_angle=0.0)
        val expectedEmpty=TatamiFill.fill(square,-0.0,p.rowSpacing,null,p.maxLength,
            p.runningLength,p.tolerance,p.staggers,p.skip_last,null,null,p.underpath)
        assertEquals(expectedEmpty,GuidedFill.generate(square,distant,copyAlongGuide))
    }

    @Test fun routedCurvesReachHoleEndingPointWithoutCrossingTheHole() {
        val region=polygon("POLYGON((0 0,40 0,40 40,0 40,0 0),(10 10,10 30,30 30,30 10,10 10))")
        val bent=guide("LINESTRING(-5 3,20 6,45 3)")
        val start=FillPoint(-5.0,18.0)
        val end=FillPoint(10.0,16.0)
        for(strategy in 0..2) for(underpath in listOf(false,true)) {
            val p=parameters(strategy).copy(underpath=underpath,running_stitch_tolerance_mm=0.0)
            val points=GuidedFill.generate(region,bent,p,starting_point=start,ending_point=end)
            assertTrue(points.isNotEmpty(),"strategy=$strategy underpath=$underpath")
            assertTrue(points.first().distance(FillPoint(0.0,18.0))<1e-4)
            assertTrue(points.last().distance(end)<1e-4)
            val tolerant=region.buffer(.025)
            for((a,b) in points.zipWithNext()) if("jump" !in b.tags && !a.samePosition(b)) {
                assertTrue(a.distance(b)<maxOf(p.maxLength,p.runningLength)+.025,
                    "Unmarked long connector: strategy=$strategy underpath=$underpath $a -> $b")
                assertTrue(tolerant.covers(factory.createLineString(arrayOf(Coordinate(a.x,a.y),Coordinate(b.x,b.y)))),
                    "Hole-crossing connector: strategy=$strategy underpath=$underpath $a -> $b")
            }
        }
    }

    @Test fun invalidParametersAndUnportedRandomizationAreExplicit() {
        assertFailsWith<IllegalArgumentException>{GuidedFill.Parameters(guided_fill_strategy=3)}
        assertFailsWith<IllegalArgumentException>{GuidedFill.Parameters(staggers=Double.NaN)}
        assertFailsWith<IllegalArgumentException>{GuidedFill.Parameters(bean_stitch_repeats=emptyList())}
        assertFailsWith<IllegalArgumentException>{GuidedFill.Parameters(enable_random_stitch_length=true)}
        assertTrue(GuidedFill.generate(square,guide("LINESTRING EMPTY"),parameters()).isNotEmpty())
        assertFailsWith<IllegalArgumentException>{GuidedFill.generate(square,guide("LINESTRING(1 1,1 1)"))}
    }
}
