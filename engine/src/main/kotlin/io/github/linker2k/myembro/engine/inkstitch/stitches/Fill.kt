// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import kotlin.math.*

/** Upstream low-level functions: angles in radians and distances in SVG pixels. */
object Fill {
    fun east(angle: Double) = FillPoint(cos(angle), -sin(angle))
    fun north(angle: Double) = FillPoint(sin(angle), cos(angle))
    fun rowNum(point: FillPoint, angle: Double, rowSpacing: Double): Double = Math.rint(point.dot(north(angle)) / rowSpacing)

    fun adjustStagger(stitch: FillPoint, angle: Double, rowSpacing: Double, maxStitchLength: Double,
        staggers: Double): FillPoint {
        val phase = positiveModulo(rowNum(stitch, angle, rowSpacing) / if (staggers == 0.0) 1.0 else staggers, 1.0)
        val offset = positiveModulo(stitch.dot(east(angle)) - phase*maxStitchLength, maxStitchLength)
        return stitch - east(angle)*offset
    }

    fun stitchRow(beg: FillPoint, end: FillPoint, angle: Double, rowSpacing: Double,
        maxStitchLength: Double, staggers: Double = 4.0, skipLast: Boolean = false): List<FillPoint> {
        require(listOf(angle, rowSpacing, maxStitchLength, staggers).all { it.isFinite() })
        require(rowSpacing > 0 && maxStitchLength > 0)
        if (end.samePosition(beg)) return listOf(beg.tagged("fill_row_start"))
        val first = beg.tagged("fill_row_start")
        // Preserve upstream duplicates in raw stitch groups. Shared plan filtering handles short stitches.
        val stitches = mutableListOf(first, first)
        val direction = (end-beg).unit()
        val length = beg.distance(end)
        var next = adjustStagger(beg, angle, rowSpacing, maxStitchLength, staggers)
        if ((next-beg).dot(direction) < 0) next += direction*maxStitchLength
        var offset = next.distance(beg)
        require(length/maxStitchLength <= 1_000_000) { "Fill row exceeds 1000000 stitches" }
        while (offset < length) {
            stitches += (beg+direction*offset).tagged("fill_row")
            offset += maxStitchLength
        }
        if (end.distance(stitches.last()) > 0.1*PIXELS_PER_MM && !skipLast) stitches += end.tagged("fill_row_end")
        else stitches[stitches.lastIndex] = stitches.last().tagged("fill_row_end")
        return stitches
    }

    fun intersectRegionWithGrating(shape: Geometry, angle: Double, rowSpacing: Double,
        endRowSpacing: Double? = null, flip: Boolean = false): List<List<List<FillPoint>>> {
        require(angle.isFinite() && rowSpacing.isFinite() && rowSpacing > 0)
        require(endRowSpacing == null || endRowSpacing.isFinite() && endRowSpacing >= 0)
        if (shape.isEmpty) return emptyList()
        val bounds = shape.envelopeInternal
        val upperLeft = FillPoint(bounds.minX, bounds.minY)
        val center = FillPoint((bounds.minX+bounds.maxX)/2, (bounds.minY+bounds.maxY)/2)
        val halfLength = hypot(bounds.width, bounds.height)/2
        val direction = FillPoint(-1.0, 0.0).rotate(-angle)
        val normal = direction.rotate(-PI/2)
        val rotated = AffineTransformation.rotationInstance(angle, center.x, center.y).transform(shape).envelopeInternal
        var start = rotated.minY-center.y
        val end = rotated.maxY-center.y
        val height = abs(end-start)
        if (height == 0.0) return emptyList()
        start -= positiveModulo(start+normal.dot(center), rowSpacing)
        var current = start
        var iterations = 0
        var segmentCount = 0
        val rows = mutableListOf<List<List<FillPoint>>>()
        while (current < end) {
            require(++iterations <= 100_000) { "Fill grating exceeds 100000 rows" }
            val p0 = center+normal*current+direction*halfLength
            val p1 = center+normal*current-direction*halfLength
            val grating = FillGeometry.line(listOf(p0, p1))
            val intersection = OverlayNGRobust.overlay(grating, shape, OverlayNG.INTERSECTION)
            var runs = FillGeometry.lines(intersection).map(FillGeometry::points)
                .filter { it.size >= 2 && it.first().distance(it.last()) > 1e-12 }
                .map { if ((it.last()-it.first()).dot(p1-p0) < 0) it.reversed() else it }
                .sortedBy { it.first().distance(upperLeft) }
            if (flip) runs = runs.reversed().map { it.reversed() }
            if (runs.isNotEmpty()) { rows += runs; segmentCount += runs.size }
            require(segmentCount <= 200_000) { "Fill grating exceeds 200000 segments" }
            val spacing = if (endRowSpacing != null && endRowSpacing != 0.0)
                rowSpacing+(endRowSpacing-rowSpacing)*((current-start)/height) else rowSpacing
            require(spacing > 0 && current+spacing > current) { "Non-progressing grating spacing" }
            current += spacing
        }
        return rows
    }

    fun legacyFill(shape: Geometry, angle: Double, rowSpacing: Double, endRowSpacing: Double?,
        maxStitchLength: Double, flip: Boolean = false, reverse: Boolean = false,
        staggers: Double = 4.0, skipLast: Boolean = false): List<List<FillPoint>> {
        var rows = intersectRegionWithGrating(shape, angle, rowSpacing, endRowSpacing, flip)
            .map { it.toMutableList() }.toMutableList()
        val groups = mutableListOf<List<List<FillPoint>>>()
        while (rows.isNotEmpty()) {
            val run = mutableListOf<List<FillPoint>>()
            var previous: List<FillPoint>? = null
            for (row in rows) {
                val first = row.first()
                if (previous != null && !sameRun(previous, first, shape, rowSpacing, endRowSpacing)) break
                run += first; previous = first; row.removeAt(0)
            }
            groups += run
            rows = rows.filter { it.isNotEmpty() }.toMutableList()
        }
        val result = groups.map { group -> group.flatMapIndexed { i, segment ->
            val points = if (i % 2 == 0) segment else segment.reversed()
            stitchRow(points.first(), points.last(), angle, rowSpacing, maxStitchLength, staggers, skipLast)
        } }
        return if (reverse) result.reversed().map { it.reversed() } else result
    }

    private fun sameRun(a: List<FillPoint>, b: List<FillPoint>, shape: Geometry,
        spacing: Double, endSpacing: Double?): Boolean {
        if (FillGeometry.line(a).distance(FillGeometry.line(b)) > max(spacing, endSpacing ?: spacing)*1.1) return false
        val quad = FillGeometry.factory.createPolygon(listOf(a.first(), a.last(), b.last(), b.first(), a.first())
            .map { Coordinate(it.x, it.y) }.toTypedArray())
        if (quad.area == 0.0) return false
        return OverlayNGRobust.overlay(shape, quad, OverlayNG.INTERSECTION).area/quad.area >= 0.9
    }
}
