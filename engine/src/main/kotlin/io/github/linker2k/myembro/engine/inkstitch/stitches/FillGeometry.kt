// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/fill_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import org.locationtech.jts.geom.*
import org.locationtech.jts.geom.util.GeometryFixer

/** Shapely's polygonal make_valid/ensure_multi_polygon and fill buffer fallback, backed by JTS. */
object FillGeometry {
    val factory = GeometryFactory()
    fun point(p: FillPoint): org.locationtech.jts.geom.Point = factory.createPoint(Coordinate(p.x, p.y))
    fun line(points: List<FillPoint>): LineString = factory.createLineString(points.map { Coordinate(it.x, it.y) }.toTypedArray())
    fun points(line: LineString): List<FillPoint> = line.coordinates.map { FillPoint(it.x, it.y) }
    fun polygons(shape: Geometry): List<Polygon> = when {
        shape.isEmpty -> emptyList()
        shape is Polygon -> listOf(shape)
        shape is GeometryCollection -> (0 until shape.numGeometries).flatMap { polygons(shape.getGeometryN(it)) }
        else -> emptyList()
    }
    fun lines(shape: Geometry): List<LineString> = when {
        shape.isEmpty -> emptyList()
        shape is LineString -> listOf(shape)
        shape is GeometryCollection -> (0 until shape.numGeometries).flatMap { lines(shape.getGeometryN(it)) }
        else -> emptyList()
    }
    fun makeValid(shape: Geometry): Geometry {
        require(shape.coordinates.all { it.x.isFinite() && it.y.isFinite() })
        val fixed = if (shape.isValid) shape.copy() else GeometryFixer.fix(shape)
        return factory.createMultiPolygon(polygons(fixed).toTypedArray())
    }
    /** Upstream intentionally falls back to the original shape if a buffer removes everything. */
    fun shrinkOrGrow(shape: Geometry, amount: Double): Geometry {
        require(amount.isFinite())
        val result = if (amount == 0.0) shape else shape.buffer(amount)
        return makeValid(if (result.isEmpty) shape else result)
    }

    /** utils/smoothing.py: resample without corner splitting, then five Chaikin iterations. */
    fun smoothPath(path: List<FillPoint>, amount: Double, iterations: Int = 5): List<FillPoint> {
        require(amount.isFinite() && amount >= 0 && iterations in 0..10)
        if (amount == 0.0 || path.size < 2) return path
        var points = FillRunning.curveEvenly(path, amount*5, amount*2)
        repeat(iterations) {
            if (points.isNotEmpty()) {
                val next = mutableListOf(points.first())
                points.zipWithNext().forEach { (a,b) -> next += a*0.75+b*0.25; next += a*0.25+b*0.75 }
                next += points.last(); points = next
            }
        }
        return listOf(path.first())+points+path.last()
    }
}
