// Ported from Ink/Stitch lib/stitches/circular_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.util.GeometryFixer
import org.locationtech.jts.linearref.LengthIndexedLine
import kotlin.math.*

/** Concentric rings are interpolated into an outer-to-inner-to-outer Fermat spiral. */
object CircularFill {
    data class Parameters(
        val angle: Double = 0.0,
        val row_spacing_mm: Double = 0.25,
        val end_row_spacing_mm: Double? = null,
        val running_stitch_length_mm: Double = 2.5,
        val running_stitch_tolerance_mm: Double = 0.2,
        val bean_stitch_repeats: List<Int> = listOf(0),
        val repeats: Int = 1,
        val skip_last: Boolean = false,
        val underpath: Boolean = true,
        val enable_random_stitch_length: Boolean = false,
        val random_stitch_length_jitter_percent: Double = 10.0,
        val random_seed: String = "",
    ) {
        init {
            require(angle.isFinite())
            require(listOf(row_spacing_mm, running_stitch_length_mm, running_stitch_tolerance_mm)
                .all { it.isFinite() && it > 0 })
            require(end_row_spacing_mm == null || end_row_spacing_mm.isFinite() && end_row_spacing_mm >= 0)
            require(bean_stitch_repeats.isNotEmpty() && bean_stitch_repeats.all { it >= 0 })
            require(repeats >= 1 && repeats <= 20)
            require(random_stitch_length_jitter_percent.isFinite() && random_stitch_length_jitter_percent >= 0)
            require(!enable_random_stitch_length) { "Ink/Stitch seeded random running stitch is not ported" }
        }
    }

    fun generate(shape: Geometry, parameters: Parameters = Parameters(), startingPoint: FillPoint? = null,
        endingPoint: FillPoint? = null, target: FillPoint? = null): List<FillPoint> {
        val region = GeometryFixer.fix(shape)
        require(region.isEmpty || region.dimension == 2) { "Fill needs polygonal geometry" }
        if (region.isEmpty) return emptyList()
        val p = parameters
        val center = target ?: region.centroid.coordinate.let { FillPoint(it.x, it.y) }
        val distance = region.coordinates.maxOf { hypot(it.x - center.x, it.y - center.y) }
        val spacing = max(p.row_spacing_mm, 0.1) * PIXELS_PER_MM
        val endSpacing = p.end_row_spacing_mm?.takeIf { it > 0 }?.times(PIXELS_PER_MM)
        val length = p.running_stitch_length_mm * PIXELS_PER_MM
        val tolerance = p.running_stitch_tolerance_mm * PIXELS_PER_MM
        fun circle(radius: Double): LineString = region.factory.createPoint(Coordinate(center.x, center.y))
            .buffer(radius, 16).boundary as LineString
        if (spacing > distance) {
            // Upstream deliberately returns the spacing-sized circle without clipping in this case.
            return applyBeanStitchAndRepeats(FillRunning.run(points(circle(spacing)), length, tolerance),
                p.repeats, p.bean_stitch_repeats)
        }
        val rings = mutableListOf(circle(0.1))
        var radius = spacing
        val stop = distance + (endSpacing ?: spacing) * 2
        while (radius < stop) {
            require(rings.size < 100_000) { "Circular fill exceeds ring limit" }
            rings += circle(radius)
            val increment = if (endSpacing != null) spacing + (endSpacing - spacing) * (radius / distance) else spacing
            require(increment > 0 && increment.isFinite()) { "Circular fill end spacing stops progressing" }
            radius += increment
        }
        rings.reverse()
        val spiral = ContourFill.makeFermatSpiral(rings, length, points(rings.first()).first())
        if (spiral.size < 2) return emptyList()
        val line = region.factory.createLineString(spiral.map { Coordinate(it.x, it.y) }.toTypedArray())
        val intersection = line.intersection(region)
        if (intersection is LineString) {
            val path = applyStartEndCommands(region, points(intersection), startingPoint, endingPoint)
            return applyBeanStitchAndRepeats(FillRunning.run(path, length, tolerance), p.repeats, p.bean_stitch_repeats)
        }
        val segments = collectLines(intersection).map { FillRunning.run(points(it), length, tolerance) }
        if (segments.isEmpty()) return emptyList()
        val stitches = FillRouting.routeSegments(region, segments, startingPoint, endingPoint, p.underpath,
            length, tolerance, Math.toRadians(p.angle), p.skip_last)
        return applyBeanStitchAndRepeats(stitches, p.repeats, p.bean_stitch_repeats)
    }

    /** Upstream repeats the accumulated path on every pass (1, 2, 4, 8...), not the original path. */
    internal fun applyBeanStitchAndRepeats(stitches: List<FillPoint>, repeats: Int,
        beanStitchRepeats: List<Int>): List<FillPoint> {
        require(repeats in 1..20 && beanStitchRepeats.isNotEmpty() && beanStitchRepeats.all { it >= 0 })
        if (stitches.isEmpty()) return emptyList()
        val result = mutableListOf(stitches.first())
        for ((index, stitch) in stitches.drop(1).withIndex()) {
            result += stitch
            if ("travel" !in stitch.tags) {
                val repeat = beanStitchRepeats[index % beanStitchRepeats.size]
                require(result.size.toLong() + repeat.toLong() * 2 <= 1_000_000) { "Circular bean stitch limit exceeded" }
                repeat(repeat) { result.addAll(result.takeLast(2)) }
            }
        }
        for (pass in 1 until repeats) {
            require(result.size <= 500_000) { "Circular repeated stitch limit exceeded" }
            result.addAll(if (pass % 2 == 1) result.reversed() else result.toList())
        }
        return result
    }

    internal fun applyStartEndCommands(shape: Geometry, path: List<FillPoint>, startingPoint: FillPoint?,
        endingPoint: FillPoint?): List<FillPoint> {
        if (path.isEmpty() || startingPoint == null && endingPoint == null) return path
        val outline = collectLines(shape.boundary).firstOrNull() ?: return path
        val indexed = LengthIndexedLine(outline)
        fun sequence(from: FillPoint, to: FillPoint): List<FillPoint> = points(indexed.extractLine(
            indexed.project(Coordinate(from.x, from.y)), indexed.project(Coordinate(to.x, to.y))) as LineString)
        var result = path
        if (startingPoint != null) result = sequence(startingPoint, path.first()) + result
        if (endingPoint != null) result = result + sequence(path.last(), endingPoint)
        return result
    }

    private fun points(line: LineString) = line.coordinates.map { FillPoint(it.x, it.y) }
    private fun collectLines(geometry: Geometry): List<LineString> = when {
        geometry.isEmpty -> emptyList()
        geometry is LineString -> if (geometry.numPoints > 1 && geometry.length > 0) listOf(geometry) else emptyList()
        geometry is GeometryCollection -> (0 until geometry.numGeometries).flatMap { collectLines(geometry.getGeometryN(it)) }
        else -> emptyList()
    }
}
