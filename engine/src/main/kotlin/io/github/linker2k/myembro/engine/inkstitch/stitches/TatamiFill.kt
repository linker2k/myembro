// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/tatami_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.operation.union.UnaryUnionOp

/** Low-level auto/tatami fill, using original SVG pixel / radian arguments. */
object TatamiFill {
    fun fill(shape: Geometry, angle: Double, rowSpacing: Double, endRowSpacing: Double?,
        maxStitchLength: Double, runningStitchLength: Double, runningStitchTolerance: Double,
        staggers: Double, skipLast: Boolean, startingPoint: FillPoint?, endingPoint: FillPoint? = null,
        underpath: Boolean = true, gapFillRows: Int = 0,
        pullCompensationPx: List<Double> = listOf(0.0,0.0),
        pullCompensationPercent: List<Double> = listOf(0.0,0.0)): List<FillPoint> {
        require(pullCompensationPx.size == 2 && pullCompensationPercent.size == 2)
        require((pullCompensationPx+pullCompensationPercent).all { it.isFinite() && it >= 0 })
        var valid = FillGeometry.makeValid(shape)
        if ((pullCompensationPx+pullCompensationPercent).any { it != 0.0 }) {
            val parts = FillGeometry.polygons(valid).map { part -> adjustShapeForPullCompensation(part,
                angle, minOf(rowSpacing, endRowSpacing?.takeIf { it > 0 } ?: rowSpacing),
                pullCompensationPx,pullCompensationPercent) }
            valid = FillGeometry.makeValid(UnaryUnionOp.union(parts))
        }
        FillTrace.emit("valid_geometry") { org.locationtech.jts.io.WKBWriter.toHex(org.locationtech.jts.io.WKBWriter().write(valid)) }
        FillTrace.emit("parameters") { listOf(angle,rowSpacing,maxStitchLength,runningStitchLength,runningStitchTolerance,staggers).joinToString { it.toBits().toString() } }
        val segments = Fill.intersectRegionWithGrating(valid, angle, rowSpacing, endRowSpacing).flatten()
        FillTrace.emit("row_segments") { FillTrace.points(segments) }
        if (segments.isEmpty()) return fallback(valid, runningStitchLength, runningStitchTolerance)
        return FillRouting.route(valid, segments, startingPoint, endingPoint, underpath,
            runningStitchLength, runningStitchTolerance, angle, skipLast, gapFillRows) { points, reversed ->
            val directed = if (reversed) points.reversed() else points
            Fill.stitchRow(directed.first(), directed.last(), angle, rowSpacing, maxStitchLength, staggers, skipLast)
        }
    }

    fun fallback(shape: Geometry, runningStitchLength: Double, runningStitchTolerance: Double): List<FillPoint> {
        val boundary = FillGeometry.lines(FillGeometry.makeValid(shape).boundary).firstOrNull() ?: return emptyList()
        return FillRunning.run(FillGeometry.points(boundary), runningStitchLength, runningStitchTolerance)
    }

    fun applyPullCompensation(segments: List<List<FillPoint>>, absolutePx: List<Double>,
        proportional: List<Double>): List<List<FillPoint>> {
        require(absolutePx.size == 2 && proportional.size == 2)
        return segments.map { segment ->
            val start = segment.first(); val end = segment.last()
            val distance = start.distance(end)
            if (distance < 0.0001) segment else {
                var a = absolutePx[0]+distance*proportional[0]
                var b = absolutePx[1]+distance*proportional[1]
                if (a+b < -distance) { val scale = -distance/(a+b); a *= scale; b *= scale }
                listOf(start+(start-end).unit()*a,end+(end-start).unit()*b)
            }
        }
    }

    fun adjustShapeForPullCompensation(shape: Geometry, angle: Double, rowSpacing: Double,
        absolutePx: List<Double>, proportional: List<Double>): Geometry {
        val rows = Fill.intersectRegionWithGrating(shape,angle,rowSpacing)
        if (rows.isEmpty()) return shape
        val segments = applyPullCompensation(rows.flatten(),absolutePx,proportional)
        val union = UnaryUnionOp.union(segments.map { FillGeometry.line(it).buffer(rowSpacing/2+0.01) })
        // ensure_polygon deliberately selects the largest component in the upstream compensation step.
        val polygon = FillGeometry.polygons(union).maxByOrNull { it.area } ?: return shape
        fun ring(line: org.locationtech.jts.geom.LineString) = FillGeometry.factory.createLinearRing(
            FillGeometry.smoothPath(FillGeometry.points(line),0.2).map { Coordinate(it.x,it.y) }.toTypedArray())
        val holes = (0 until polygon.numInteriorRing).map(polygon::getInteriorRingN)
            .filter { FillGeometry.factory.createPolygon(it.coordinates).area > rowSpacing*rowSpacing }.map(::ring)
        return FillGeometry.makeValid(FillGeometry.factory.createPolygon(ring(polygon.exteriorRing),holes.toTypedArray()))
    }
}
