// Ported from Ink/Stitch lib/stitches/linear_gradient_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.geom.util.GeometryFixer
import kotlin.math.*

/** Gradient coordinates are already transformed into SVG user space; colors retain SVG spelling. */
object LinearGradientFill {
    data class Stop(val offset: Double, val color: String) {
        init { require(offset.isFinite() && offset in 0.0..1.0 && color.isNotBlank()) }
    }
    data class Gradient(val start: FillPoint, val end: FillPoint, val stops: List<Stop>) {
        init {
            require(start.distance(end) > 0 && stops.size >= 2)
            require(stops.zipWithNext().all { (a, b) -> a.offset <= b.offset })
        }
    }
    data class Parameters(
        val row_spacing_mm: Double = 0.25,
        val max_stitch_length_mm: Double = 4.0,
        val staggers: Double = 4.0,
        val angle: Double = 0.0,
        val running_stitch_length_mm: Double = 2.5,
        // The upstream property getter uses 0.2 although its UI decorator advertises 0.1.
        val running_stitch_tolerance_mm: Double = 0.2,
        val skip_last: Boolean = false,
        val fill_underlay: Boolean = true,
        val stop_at_ending_point: Boolean = false,
        val enable_random_stitch_length: Boolean = false,
        val random_stitch_length_jitter_percent: Double = 10.0,
        val random_seed: String = "",
    ) {
        init {
            require(listOf(row_spacing_mm, max_stitch_length_mm, running_stitch_length_mm,
                running_stitch_tolerance_mm).all { it.isFinite() && it > 0 })
            require(angle.isFinite() && staggers.isFinite() && staggers >= 0)
            require(random_stitch_length_jitter_percent.isFinite() && random_stitch_length_jitter_percent >= 0)
            require(!enable_random_stitch_length) { "Ink/Stitch seeded random running stitch is not ported" }
        }
    }
    data class ColorSection(val color: String, val stitches: List<FillPoint>)

    fun generate(shape: Geometry, parameters: Parameters = Parameters(), gradient: Gradient? = null,
        color: String = "#000000", startingPoint: FillPoint? = null,
        endingPoint: FillPoint? = null): List<ColorSection> {
        require(color.isNotBlank())
        val region = GeometryFixer.fix(shape)
        require(region.isEmpty || region.dimension == 2) { "Fill needs polygonal geometry" }
        if (region.isEmpty) return emptyList()
        val p = parameters
        val spacing = max(p.row_spacing_mm, 0.1) * PIXELS_PER_MM
        val stitchLength = max(p.max_stitch_length_mm, 0.1) * PIXELS_PER_MM
        val angle = gradient?.let { atan2(it.end.y - it.start.y, it.end.x - it.start.x) }
            ?: Math.toRadians(p.angle)
        val (lines, bottom) = getLines(region, angle, spacing, stitchLength, p.staggers)
        val colors: List<String>
        val indices: List<Int>
        if (gradient == null) {
            colors = listOf(color, "none")
            indices = listOf(0, lines.size)
        } else {
            colors = gradient.stops.map { it.color }
            var startIndex = Math.rint(project(bottom.first, bottom.second, gradient.start) / spacing).toInt()
            if (startIndex == 0) {
                startIndex = -Math.rint(project(gradient.start, gradient.end, bottom.first) / spacing).toInt()
            }
            val length = gradient.start.distance(gradient.end)
            indices = gradient.stops.map { Math.rint(length * it.offset / spacing).toInt() + startIndex }
        }
        val colorLines = colorLineIndices(lines.size, colors, indices)
        val result = mutableListOf<ColorSection>()
        for ((index, entry) in colorLines.entries.withIndex()) {
            val segments = entry.value.flatMap { row ->
                val coordinates = lines[row].map { org.locationtech.jts.geom.Coordinate(it.x, it.y) }.toTypedArray()
                collectLines(region.factory.createLineString(coordinates).intersection(region))
                    .map { line -> line.coordinates.map { FillPoint(it.x, it.y) } }
            }
            if (segments.isEmpty()) continue
            val stitches = FillRouting.routeSegments(region, segments, startingPoint, endingPoint, false,
                p.running_stitch_length_mm * PIXELS_PER_MM, p.running_stitch_tolerance_mm * PIXELS_PER_MM,
                Math.toRadians(p.angle), p.skip_last)
            result += ColorSection(entry.key, removeStartEndTravel(stitches, colorLines.size, index,
                p.fill_underlay, p.stop_at_ending_point))
        }
        return result
    }

    /** Exact integer-color distribution from upstream _get_color_lines/_add_lines, including clipping. */
    fun colorLineIndices(lineCount: Int, colors: List<String>, stopColorLineIndices: List<Int>): Map<String, List<Int>> {
        require(lineCount in 0..200_000 && colors.isNotEmpty() && colors.size == stopColorLineIndices.size)
        require(stopColorLineIndices.all { it in -1_000_000..1_000_000 })
        require(stopColorLineIndices.zipWithNext().all { (a, b) -> a <= b })
        val colorLines = linkedMapOf<String, MutableList<Int>>()
        colors.forEach { colorLines[it] = mutableListOf() }
        var previous: Int? = null
        var previousColor = colors.first()
        var lastColor = colors.first()
        for ((position, color) in stopColorLineIndices.zip(colors)) {
            lastColor = color
            var lineIndex = position
            if (previous == null) {
                if (lineIndex > 0) colorLines.getValue(color).addAll(0 until min(lineIndex + 1, lineCount))
                previous = lineIndex
                previousColor = color
                continue
            }
            if (previous < 0 && lineIndex < 0) {
                previous = lineIndex
                previousColor = color
                continue
            }
            previous += 1
            lineIndex += 1
            val total = lineIndex - previous
            val sections = floor(sqrt(max(total, 0).toDouble())).toInt()
            val color1 = mutableListOf<Int>()
            val color2 = mutableListOf<Int>()
            var c2Count = 0
            var maxCount = 1000
            var current = 0
            var lineCountDiff = floor((total - sections.toDouble() * sections) / 2).toInt()
            var stop = false
            for (i in 0 until sections) {
                if (stop) break
                c2Count++
                val remainder = (sections - c2Count) % c2Count
                val c1Count = ceil((sections - c2Count).toDouble() / c2Count).toInt()
                var count = 0
                for (j in 0 until c2Count) {
                    if (stop) break
                    count = if (remainder == 0 || j < remainder) c1Count else c1Count - 1
                    if (lineCountDiff > 0) { count++; lineCountDiff-- }
                    count = min(count, maxCount)
                    for (k in 0 until max(0, count)) {
                        color1 += current++
                        if (total / 2.0 <= current + 1) { stop = true; break }
                    }
                    color2 += current++
                }
                maxCount = count
            }
            if (color1.isEmpty() || color2.isEmpty()) continue
            val secondHalf = color2.last() * 2 + 1
            val fullColor1 = color1 + color2.map { secondHalf - it }
            val fullColor2 = color2 + color1.map { secondHalf - it }
            val origin = previous
            val shifted1 = fullColor1.map { it + origin }
            val shifted2 = fullColor2.map { it + origin }
            colorLines.getValue(previousColor).addAll(shifted1.filter { it > 0 && it < lineCount })
            colorLines.getValue(color).addAll(shifted2.filter { it > 0 && it < lineCount })
            previous = shifted2.max()
            previousColor = color
        }
        val start = ((previous ?: -1) + 1).coerceIn(0, lineCount)
        colorLines.getValue(lastColor).addAll(start until lineCount)
        colorLines.remove("none")
        return colorLines.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() }
    }

    internal fun removeStartEndTravel(stitches: List<FillPoint>, colorCount: Int, colorSection: Int,
        fillUnderlay: Boolean, stopAtEndingPoint: Boolean): List<FillPoint> {
        var result = stitches
        if (colorSection > 0 || !fillUnderlay) {
            val before = (0 until max(0, result.lastIndex)).firstOrNull { "travel" !in result[it].tags } ?: 0
            result = result.drop(before)
        }
        var after = result.lastIndex
        // Preserve upstream's len(colors)-2 condition and its final-point slicing behavior.
        if (colorSection < colorCount - 2 || !stopAtEndingPoint) {
            for (i in result.lastIndex downTo 1) if ("travel" !in result[i].tags) { after = i + 1; break }
        }
        return result.take(max(0, after))
    }

    private fun getLines(shape: Geometry, angle: Double, spacing: Double, maxLength: Double,
        staggers: Double): Pair<List<List<FillPoint>>, Pair<FillPoint, FillPoint>> {
        val bounds = shape.envelopeInternal
        val rotation = AffineTransformation.rotationInstance(-angle, bounds.minX, bounds.maxY)
        val rotated = rotation.transform(shape).envelopeInternal
        val inverse = rotation.inverse
        fun point(x: Double, y: Double): FillPoint {
            val c = org.locationtech.jts.geom.Coordinate()
            inverse.transform(org.locationtech.jts.geom.Coordinate(x, y), c)
            return FillPoint(c.x, c.y)
        }
        val left = rotated.minX - maxLength
        val right = rotated.maxX + maxLength
        val top = rotated.minY - spacing
        val bottom = rotated.maxY + spacing
        val rawCount = ceil((right - left) / spacing)
        require(rawCount.isFinite() && rawCount in 1.0..200_000.0) { "Gradient fill exceeds row limit" }
        val count = rawCount.toInt()
        var totalPoints = 0L
        val rows = (0..count).map { row ->
            val x = left + (right - left) * row / count
            val begin = point(x, top)
            val end = point(x, bottom)
            val length = begin.distance(end)
            val phase = ((row / (if (staggers == 0.0) 1.0 else staggers)) % 1) * maxLength
            val projections = mutableListOf(0.0)
            var position = phase
            while (position < length) {
                require(projections.size < 1_000_000) { "Gradient row exceeds stitch limit" }
                projections += position
                position += maxLength
            }
            val result = if (projections.size < 2) listOf(begin, end) else {
                if (length - projections.last() > spacing / 2) projections += length
                projections.map { begin.interpolate(end, it / length) }
            }
            totalPoints += result.size
            require(totalPoints <= 1_000_000) { "Gradient fill exceeds stitch limit" }
            result
        }
        return rows to (point(left, bottom) to point(right, bottom))
    }

    private fun project(start: FillPoint, end: FillPoint, point: FillPoint): Double {
        val dx = end.x - start.x; val dy = end.y - start.y
        val length = hypot(dx, dy)
        if (length == 0.0) return 0.0
        return (((point.x - start.x) * dx + (point.y - start.y) * dy) / length).coerceIn(0.0, length)
    }

    private fun collectLines(geometry: Geometry): List<LineString> = when {
        geometry.isEmpty -> emptyList()
        geometry is LineString -> if (geometry.numPoints > 1 && geometry.length > 0) listOf(geometry) else emptyList()
        geometry is GeometryCollection -> (0 until geometry.numGeometries).flatMap { collectLines(geometry.getGeometryN(it)) }
        else -> emptyList()
    }
}
