// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon
import kotlin.math.PI
import kotlin.test.*

/** Upstream v3.3.0 has no direct fill/tatami unit tests. These use hand-calculated source formulas. */
class FillCoreTest {
    @Test fun graphCopyUsesNetworkXNodeNeighborKeyOrderRatherThanGlobalEdgeOrder() {
        val graph=FillRouting.Graph()
        val a=graph.node(FillPoint(0.0,0.0)); val b=graph.node(FillPoint(10.0,0.0)); val c=graph.node(FillPoint(0.0,10.0))
        graph.add(a,c,"outline"); graph.add(b,c,"outline"); graph.add(a,b,"outline")
        assertEquals(listOf(c,a),graph.at(b).map { it.other(b) })
        val copied=graph.copy()
        assertEquals(listOf(a,c),copied.at(b).map { it.other(b) })
        assertEquals(3,copied.edges.size)
        assertEquals(listOf(a,b,c),copied.nodes.keys.toList())
        graph.add(a,b,"extra")
        assertEquals(listOf("outline","extra"),graph.copy().at(a).filter { it.other(a)==b }.map { it.key })
    }
    private fun rectangle(x: Double = 0.0, y: Double = 0.0, width: Double = 40.0, height: Double = 20.0): Polygon =
        FillGeometry.factory.createPolygon(arrayOf(Coordinate(x,y), Coordinate(x+width,y),
            Coordinate(x+width,y+height), Coordinate(x,y+height), Coordinate(x,y)))
    private fun fill(shape: Geometry, underpath: Boolean = true, start: FillPoint? = null, end: FillPoint? = null,
        skip: Boolean = false, gap: Int = 0) = TatamiFill.fill(shape, 0.0, 4.0, null,
        12.0, 8.0, 0.0, 4.0, skip, start, end, underpath, gap)

    @Test fun gratingIsAnchoredToTheOriginRatherThanHalfARowInsideTheBoundingBox() {
        val rows = Fill.intersectRegionWithGrating(rectangle(width=20.0, height=12.0), 0.0, 4.0)
        assertEquals(listOf(0.0, 4.0, 8.0), rows.map { it.single().first().y })
        assertEquals(listOf(0.0,20.0), rows.first().single().map { it.x })
        val shifted = Fill.intersectRegionWithGrating(rectangle(1.0,1.0,20.0,10.0), 0.0, 4.0)
        listOf(4.0,8.0).zip(shifted).forEach { (y,row) -> assertEquals(y,row.single().first().y,1e-8) }
    }

    @Test fun endSpacingUsesTheOriginalIterativeGradient() {
        val rows = Fill.intersectRegionWithGrating(rectangle(width=10.0), 0.0, 4.0, 8.0)
        val y = rows.map { it.single().first().y }
        assertEquals(4, y.size)
        listOf(0.0,4.0,8.8,14.56).zip(y).forEach { (expected, actual) -> assertEquals(expected, actual, 1e-8) }
        assertEquals(Fill.intersectRegionWithGrating(rectangle(), 0.0, 4.0),
            Fill.intersectRegionWithGrating(rectangle(), 0.0, 4.0, 0.0))
    }

    @Test fun anglesAreCounterclockwiseInSvgAndNegativeRowsUsePythonModuloAndRoundEven() {
        val diagonal = Fill.intersectRegionWithGrating(rectangle(), PI/4, 4.0)
        assertTrue(diagonal.isNotEmpty())
        for (row in diagonal) for (segment in row) {
            assertTrue(segment.last().x >= segment.first().x)
            assertTrue(segment.last().y <= segment.first().y + 1e-8)
            val projection = segment.first().dot(Fill.north(PI/4))/4
            assertEquals(Math.rint(projection), projection, 1e-7)
        }
        assertEquals(0.0, Fill.rowNum(FillPoint(0.0,2.0),0.0,4.0))
        assertEquals(2.0, Fill.rowNum(FillPoint(0.0,6.0),0.0,4.0))
        assertEquals(-1.0, Fill.adjustStagger(FillPoint(2.0,-4.0),0.0,4.0,4.0,4.0).x)
        assertEquals(0.0, Fill.adjustStagger(FillPoint(2.0,4.0),0.0,4.0,4.0,0.0).x)
    }

    @Test fun rawStitchRowPreservesSourceDuplicatesStaggersEndTagsAndSkipLast() {
        val points = Fill.stitchRow(FillPoint(0.0,0.0),FillPoint(10.0,0.0),0.0,4.0,4.0)
        assertEquals(listOf(0.0,0.0,0.0,4.0,8.0,10.0),points.map { it.x })
        assertTrue("fill_row_start" in points.first().tags)
        assertTrue("fill_row_end" in points.last().tags)
        val skipped = Fill.stitchRow(FillPoint(0.0,0.0),FillPoint(10.0,0.0),0.0,4.0,4.0,skipLast=true)
        assertEquals(8.0,skipped.last().x)
        assertTrue("fill_row_end" in skipped.last().tags)
        val staggered = Fill.stitchRow(FillPoint(0.0,4.0),FillPoint(10.0,4.0),0.0,4.0,4.0)
        assertEquals(listOf(0.0,0.0,1.0,5.0,9.0,10.0),staggered.map { it.x })
    }

    @Test fun holesSplitGratingRowsAndLegacySectionsWithoutStitchingAcrossTheirInterior() {
        val shape = rectangle(width=40.0,height=40.0).difference(rectangle(10.0,10.0,20.0,20.0))
        val rows = Fill.intersectRegionWithGrating(shape,0.0,4.0)
        assertEquals(2,rows.first { it.first().first().y == 12.0 }.size)
        val legacy = Fill.legacyFill(shape,0.0,4.0,null,12.0)
        assertTrue(legacy.size > 1)
        assertTrue(legacy.flatten().none { it.x > 10.00001 && it.x < 29.99999 && it.y > 10.00001 && it.y < 29.99999 })
        assertEquals(legacy.reversed().map { it.reversed() },Fill.legacyFill(shape,0.0,4.0,null,12.0,reverse=true))
        val normal = Fill.intersectRegionWithGrating(shape,0.0,4.0)
        assertEquals(normal.map { row -> row.reversed().map { it.reversed() } },
            Fill.intersectRegionWithGrating(shape,0.0,4.0,flip=true))
    }

    @Test fun evenRunningStitchAdaptsLengthsAndPreservesCorners() {
        val line = listOf(FillPoint(0.0,0.0),FillPoint(10.0,0.0))
        val sampled = FillRunning.run(line,4.0,0.0)
        assertEquals(4,sampled.size)
        listOf(0.0,10.0/3,20.0/3,10.0).zip(sampled).forEach { (x,p) -> assertEquals(x,p.x,2e-6) }
        val corner = FillRunning.run(line+FillPoint(10.0,10.0),4.0,0.0)
        assertTrue(corner.any { it.samePosition(FillPoint(10.0,0.0)) })
        assertTrue(corner.zipWithNext().all { (a,b) -> a.distance(b) <= 4.00001 })
        assertEquals(sampled.drop(1),FillRunning.curveEvenly(line,4.0,0.0))
        val strict = FillRunning.run(line,4.0,0.0,adaptToLength=false)
        assertEquals(listOf(0.0,4.0,8.0,10.0),strict.map { it.x })
    }

    @Test fun graphVisitsAllRectangleRowsWithBothTravelModesAndIsDeterministic() {
        val shape = rectangle()
        for (underpath in listOf(false,true)) {
            val result = fill(shape,underpath)
            assertEquals(result,fill(shape,underpath))
            assertEquals(5,result.count { "fill_row_end" in it.tags })
            listOf(0.0,4.0,8.0,12.0,16.0).zip(result.filter { "fill_row_end" in it.tags }.map { it.y }.sorted())
                .forEach { (expected,y) -> assertEquals(expected,y,1e-8) }
            assertTrue(result.any { "travel" in it.tags })
            assertTrue(result.all { shape.buffer(1e-6).covers(FillGeometry.point(it)) })
        }
    }

    @Test fun underpathAndOutlineTravelRespectHolesAtZeroRunningTolerance() {
        val shape = rectangle(width=60.0,height=60.0).difference(rectangle(20.0,20.0,20.0,20.0))
        for (underpath in listOf(false,true)) {
            val result = fill(shape,underpath)
            assertTrue(result.isNotEmpty())
            val tolerance = shape.buffer(2e-6)
            for ((a,b) in result.zipWithNext()) if (!a.samePosition(b) && "jump" !in b.tags)
                assertTrue(tolerance.covers(FillGeometry.line(listOf(a,b))),"Outside hole-safe route: $a -> $b")
        }
    }

    @Test fun explicitProjectedStartAndEndAreNotReplacedWithBoundingBoxCorners() {
        val result = fill(rectangle(),false,FillPoint(-10.0,6.0),FillPoint(50.0,14.0))
        assertEquals(0.0,result.first().x,1e-6)
        assertEquals(6.0,result.first().y,1e-6)
        assertEquals(40.0,result.last().x,1e-6)
        assertEquals(14.0,result.last().y,1e-6)
    }

    @Test fun disconnectedFacesKeepTheirNeedlesAndMarkTravelAsJump() {
        val shape = rectangle(width=20.0).union(rectangle(40.0,0.0,20.0,20.0))
        val result = fill(shape)
        assertTrue(result.any { it.x < 20.0 })
        assertTrue(result.any { it.x > 40.0 })
        assertEquals(1,result.count { "jump" in it.tags })
    }

    @Test fun makeValidPreservesBothBowtieFacesAndCollapsedBuffersFallBackToOriginal() {
        val bowtie = FillGeometry.factory.createPolygon(arrayOf(Coordinate(0.0,0.0),Coordinate(20.0,20.0),
            Coordinate(0.0,20.0),Coordinate(20.0,0.0),Coordinate(0.0,0.0)))
        assertFalse(bowtie.isValid)
        val valid = FillGeometry.makeValid(bowtie)
        assertTrue(valid.isValid)
        assertEquals(2,FillGeometry.polygons(valid).size)
        assertEquals(200.0,valid.area,1e-8)
        assertEquals(rectangle().area,FillGeometry.shrinkOrGrow(rectangle(),-1000.0).area)
        assertTrue(fill(bowtie).isNotEmpty())
    }

    @Test fun tinyFillFallsBackToOuterRunningAndEmptyShapesStayEmpty() {
        val tiny = rectangle(1.0,1.0,1.0,1.0)
        assertEquals(TatamiFill.fallback(tiny,8.0,0.0),fill(tiny))
        assertTrue(fill(FillGeometry.factory.createPolygon()).isEmpty())
        assertFailsWith<IllegalArgumentException> { Fill.intersectRegionWithGrating(rectangle(),0.0,0.0) }
        assertFailsWith<IllegalArgumentException> { FillRunning.run(listOf(FillPoint(0.0,0.0)),0.0,0.0) }
    }

    @Test fun externalExcursionClampsOnceWithoutProjectingEachOutsideVertex() {
        val polygon = FillGeometry.factory.createPolygon(listOf(0.0 to 0.0,100.0 to 0.0,100.0 to 100.0,
            60.0 to 100.0,60.0 to 40.0,40.0 to 40.0,40.0 to 100.0,0.0 to 100.0,0.0 to 0.0)
            .map { Coordinate(it.first,it.second) }.toTypedArray())
        val path = listOf(FillPoint(30.0,80.0),FillPoint(45.0,80.0),FillPoint(55.0,95.0),
            FillPoint(55.0,50.0),FillPoint(70.0,80.0))
        val clamped = FillRouting.clampPath(path,polygon)
        assertEquals(10.0+80.0+kotlin.math.sqrt(500.0),clamped.zipWithNext().sumOf { (a,b) -> a.distance(b) },1e-7)
        assertTrue(clamped.any { it.samePosition(FillPoint(40.0,40.0)) })
        assertTrue(clamped.any { it.samePosition(FillPoint(60.0,40.0)) })
        assertTrue(clamped.none { it.samePosition(FillPoint(60.0,95.0)) })
    }

    @Test fun eulerizationKeepsDuplicatedSegmentGeometryOrientedInTraversalDirection() {
        val segments = listOf(listOf(FillPoint(0.0,0.0),FillPoint(10.0,0.0)),
            listOf(FillPoint(10.0,10.0),FillPoint(0.0,0.0)),
            listOf(FillPoint(0.0,10.0),FillPoint(10.0,10.0)))
        val route = FillRouting.route(rectangle(width=10.0,height=10.0),segments,FillPoint(0.0,0.0),null,
            false,2.0,0.0,0.0,false) { points, reversed ->
            FillRunning.run(if (reversed) points.reversed() else points,2.0,0.0)
        }
        assertTrue(route.size > 20)
        assertTrue(route.zipWithNext().all { (a,b) -> a.distance(b) <= 2.00001 })
    }

    @Test fun pullCompensationAppliesAbsoluteAndFullRowProportionalAmountsToEachSide() {
        val segment = listOf(FillPoint(0.0,0.0),FillPoint(10.0,0.0))
        val expanded = TatamiFill.applyPullCompensation(listOf(segment),listOf(1.0,2.0),listOf(0.1,0.2)).single()
        assertEquals(listOf(FillPoint(-2.0,0.0),FillPoint(14.0,0.0)),expanded)
        val contracted = TatamiFill.applyPullCompensation(listOf(segment),listOf(-10.0,-10.0),listOf(0.0,0.0)).single()
        assertEquals(listOf(FillPoint(5.0,0.0),FillPoint(5.0,0.0)),contracted)
        val shape = TatamiFill.adjustShapeForPullCompensation(rectangle(),0.0,4.0,listOf(2.0,2.0),listOf(0.0,0.0))
        assertTrue(shape.isValid)
        assertTrue(shape.envelopeInternal.minX < -1.0 && shape.envelopeInternal.maxX > 41.0)
    }
    @Test fun clampHoleCornerUsesHoleBoundaryWhenOnlyEndpointsIntersect() {
        val shape = org.locationtech.jts.io.WKTReader().read("POLYGON ((0 0,80 0,80 60,0 60,0 0),(25 20,25 40,45 40,45 20,25 20))") as org.locationtech.jts.geom.Polygon
        val start = FillPoint(25.0,37.354779)
        val end = FillPoint(28.192362,40.0)
        val path = FillRouting.clampPath(listOf(start,end),shape)
        assertEquals(start,path.first())
        assertTrue(path.last().distance(end)<1e-8)
        assertTrue(path.contains(FillPoint(25.0,40.0)))
        assertTrue(path.zipWithNext().sumOf { (a,b)->a.distance(b) } < 6.0)
    }

}
