// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/ripple_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.linearref.LengthIndexedLine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Implemented by the SatinColumn port. Sampling must preserve its rungs, cuts and rail ordering. */
interface RippleSatinGeometry {
    val centerLine: List<Point>
    val rungPairs: List<Pair<Point, Point>>
    fun plotPointsOnRails(spacingPx: Double, reverseRails: String, swapRails: Boolean): List<Pair<Point, Point>>
}

sealed interface RippleGuide {
    data class Line(val points: List<Point>) : RippleGuide
    data class Satin(val geometry: RippleSatinGeometry) : RippleGuide
}

/** A hook for the shared Tatami graph router; clipped paths must not be joined with straight stitches. */
fun interface RippleClipRouter {
    fun route(stitches: List<Stitch>, clip: Geometry, parameters: RippleParameters,
              removeStartTravel: Boolean, removeEndTravel: Boolean, startingPoint: Point?): List<List<Stitch>>
}

/** Flattened SVG px geometry. Manual placement passes SVG nodes rather than flattened control curves. */
data class RippleInput(
    val paths: List<List<Point>>,
    val target: Point? = null,
    val guide: RippleGuide? = null,
    val anchor: Pair<Point, Point>? = null,
    val satin: RippleSatinGeometry? = null,
    val clipShape: Geometry? = null,
    val clipRouter: RippleClipRouter? = RippleClipRouting,
)

/** Ripple line construction and stitch ordering, before common lock/trim/minimum-length processing. */
object RippleStitch {
    private val factory = GeometryFactory()
    private const val MAX_POINTS = 1_000_000

    fun generateGroups(input: RippleInput, parameters: RippleParameters = RippleParameters(),
                       template: StitchGroup = StitchGroup()): List<StitchGroup> =
        generate(input, parameters).map { template.copy(stitches = it) }

    fun generate(input: RippleInput, parameters: RippleParameters = RippleParameters()): List<List<Stitch>> {
        // Bound all subpaths before flattening/filtering or constructing JTS coordinate arrays.
        var inputPoints = 0L
        for (path in input.paths) {
            inputPoints += path.size
            require(inputPoints <= MAX_POINTS) { "Ripple input exceeds total point limit" }
        }
        val paths = input.paths.filter { it.size >= 2 }
        if (paths.sumOf { line(it).length } < 0.1) return emptyList()
        if (input.clipShape != null && input.clipRouter == null) {
            throw UnsupportedOperationException("Clipped ripple requires Ink/Stitch Tatami graph routing; a direct connector would alter stitches")
        }
        val p = parameters
        val closed = paths.size == 1 && paths[0].first().distance(paths[0].last()) < 0.05
        val (linear, helpers, gridHelpers) = helperLines(input, p, paths, closed)
        if (helpers.isEmpty() || helpers[0].isEmpty()) return emptyList()
        val count = helpers[0].size
        require(helpers.all { it.size == count }) { "Unequal ripple helper lengths" }
        val skipStart = if (abs(p.skip_start).toLong() + abs(p.skip_end) >= count) 0 else abs(p.skip_start)
        val skipEnd = if (abs(p.skip_start).toLong() + abs(p.skip_end) >= count) 0 else abs(p.skip_end)
        val rows = (skipStart until count - skipEnd).map { index -> helpers.map { it[index] } }
        var stitches = sewLines(rows, p, linear, skipStart).map { Stitch(it, setOf("ripple_stitch")) }
        if (p.reverse) stitches = stitches.reversed()
        val originalGroups = route(input, p, stitches, true, p.gridPx == 0.0, null)
        val repeats = p.repeats.coerceAtLeast(1)
        val repeatedCount = totalPoints(originalGroups) * repeats.toLong()
        require(repeatedCount <= MAX_POINTS) { "Ripple repeats exceed total stitch limit" }
        var gridGroups: List<List<Stitch>> = emptyList()
        if (originalGroups.isNotEmpty() && p.gridPx != 0.0 && stitches.isNotEmpty()) {
            var grid = gridHelpers.mapIndexed { index, helper ->
                val selected = helper.subList(skipStart, helper.size - skipEnd)
                if (index % 2 != 0 && linear && !p.flip_copies && p.join_style == 0) selected.reversed() else selected
            }
            grid = nearestGridCorner(grid, stitches.last().point)
            val gridStitches = staggeredStitches(grid, p, 0).map { Stitch(it, setOf("ripple_stitch")) }
            // Determine the repeated path endpoint without materializing repeated groups first.
            val last = originalGroups.last()
            val gridStart = (if (repeats % 2 == 1) last.last() else last.first()).point
            gridGroups = route(input, p, gridStitches, false, true, gridStart)
            require(repeatedCount + totalPoints(gridGroups) <= MAX_POINTS) { "Ripple grid exceeds total stitch limit" }
        }
        val bean = p.bean_stitch_repeats.any { it != 0 }
        var expandedTotal = 0L
        fun budgetBean(size: Int) {
            expandedTotal += if (bean) beanPointCount(size, p.bean_stitch_repeats) else size.toLong()
            require(expandedTotal <= MAX_POINTS) { "Ripple bean expansion exceeds total stitch limit" }
        }
        // Predict bean expansion from group sizes before allocating even the repeated lists.
        originalGroups.forEach { budgetBean((it.size.toLong() * repeats).toInt()) }
        gridGroups.forEach { budgetBean(it.size) }
        // All repeated paths plus grid and bean outputs have now been budgeted globally.
        var groups = originalGroups.map { repeatCoordinates(it, repeats) } + gridGroups
        if (p.gridPx != 0.0 && p.grid_first) groups = groups.reversed().map { it.reversed() }
        val result = ArrayList<List<Stitch>>(groups.size)
        var produced = 0L
        for (group in groups) {
            val tagged = group.map { it.copy(tags = it.tags + "ripple_stitch") }
            val expanded = if (bean) RunningStitch.beanStitch(tagged, p.bean_stitch_repeats) else tagged
            produced += expanded.size
            require(produced <= MAX_POINTS) { "Ripple output exceeds total stitch limit" }
            result += expanded
        }
        return result
    }

    private fun totalPoints(groups: List<List<Stitch>>): Long {
        var total = 0L
        for (group in groups) {
            total += group.size
            require(total <= MAX_POINTS) { "Ripple routed groups exceed total stitch limit" }
        }
        return total
    }

    private fun beanPointCount(size: Int, repeats: List<Int>): Long {
        var count = size.toLong()
        for (segment in 0 until (size - 1).coerceAtLeast(0)) {
            count += 2L * repeats[segment % repeats.size].coerceAtLeast(0)
            require(count <= MAX_POINTS) { "Ripple bean expansion exceeds total stitch limit" }
        }
        return count
    }

    private fun route(input: RippleInput, p: RippleParameters, stitches: List<Stitch>,
                      removeStart: Boolean, removeEnd: Boolean, start: Point?): List<List<Stitch>> {
        if (stitches.isEmpty()) return emptyList()
        val clip = input.clipShape ?: return listOf(stitches)
        return input.clipRouter!!.route(stitches, clip, p, removeStart, removeEnd, start)
    }

    private data class Helpers(val linear: Boolean, val helpers: List<List<Point>>, val grid: List<List<Point>>)
    private fun helperLines(input: RippleInput, p: RippleParameters, paths: List<List<Point>>, closed: Boolean): Helpers {
        if (paths.size > 1) {
            val satin = requireNotNull(input.satin) { "Multi-path ripple requires SatinColumn rail geometry" }
            val helpers = satinHelpers(satin, p, false, closed)
            return Helpers(true, helpers, if (p.gridPx > 0) satinHelpers(satin, p, true, closed) else helpers)
        }
        val lengths = if (p.gridPx > 0) listOf(p.gridPx) else p.lengthsPx
        val outline = if (p.manual_pattern_placement) paths[0] else
            RunningStitch.evenRunningStitch(paths[0], lengths, p.tolerancePx)
        if (outline.size < 2) return Helpers(true, emptyList(), emptyList())
        val helper = when (val guide = input.guide) {
            is RippleGuide.Line -> guidedHelpers(outline, guide.points, p, closed)
            is RippleGuide.Satin -> satinGuideHelpers(input, outline, guide.geometry, p, closed)
            null -> targetHelpers(outline, input.target ?: centroid(paths), p, closed)
        }
        val converged = when {
            closed -> convergeHelpers(helper)
            p.join_style == 1 -> convergeHelpers(helper, true)
            else -> helper
        }
        return Helpers(!closed, converged, converged)
    }

    private fun targetHelpers(outline: List<Point>, target: Point, p: RippleParameters, closed: Boolean): List<List<Point>> {
        val count = satinLineCount(outline.map { it to target }, p, closed)
        val steps = steps(count, exponent = p.exponent.coerceAtLeast(0.1), flip = p.flip_exponent)
        checkGridSize(outline.size, count)
        return outline.map { point -> steps.map { point * (1 - it) + target * it } }
    }

    private fun satinHelpers(satin: RippleSatinGeometry, p: RippleParameters, grid: Boolean, closed: Boolean): List<List<Point>> {
        val pairs = satin.plotPointsOnRails(if (grid && p.gridPx > 0) p.gridPx else p.tolerancePx, p.reverse_rails, p.swap_satin_rails)
        if (pairs.isEmpty()) return emptyList()
        val count = satinLineCount(pairs, p, closed).coerceAtLeast(2)
        val steps = steps(count, exponent = p.exponent.coerceAtLeast(0.1), flip = p.flip_exponent)
        checkGridSize(pairs.size, count)
        val helper = pairs.map { (a, b) -> steps.map { a * (1 - it) + b * it } }
        return if (p.join_style == 1 || !p.flip_copies) convergeHelpers(helper, true, p.flip_copies) else helper
    }

    private fun satinLineCount(pairs: List<Pair<Point, Point>>, p: RippleParameters, closed: Boolean): Int {
        val distance = p.minDistancePx
        // Python uses 0 as a sentinel and therefore replaces a zero distance with the next distance.
        var shortest = 0.0
        pairs.forEach { (a, b) -> val d = a.distance(b); if (shortest == 0.0 || d < shortest) shortest = d }
        val count = if (distance == null) p.line_count else ceil(shortest / distance).toInt()
        return lineCountAdjust(count, p, closed)
    }

    private fun guidedLineCount(guide: List<Point>, p: RippleParameters, closed: Boolean): Int {
        val count = p.minDistancePx?.let { ceil(line(guide).length / it).toInt() } ?: p.line_count
        return lineCountAdjust(count, p, closed)
    }

    internal fun lineCountAdjust(count: Int, p: RippleParameters, closed: Boolean): Int {
        var n = count
        if (p.minDistancePx != null && p.line_count % 2 != n % 2) n--
        n = n.coerceAtLeast(1)
        if (closed || p.join_style == 1) n++
        require(n in 1..MAX_POINTS) { "Ripple line count exceeds limit" }
        return n
    }

    internal fun steps(count: Int, start: Double = 0.0, end: Double = 1.0,
                       exponent: Double = 1.0, flip: Boolean = false): List<Double> {
        require(count in 1..MAX_POINTS)
        val result = List(count) { i -> (if (count == 1) start else start + (end - start) * i / (count - 1)).pow(exponent) }
        return if (flip) result.reversed().map { 1.0 - it } else result
    }

    private fun convergeHelpers(helpers: List<List<Point>>, pointEdge: Boolean = false, flipCopies: Boolean = true): List<List<Point>> {
        val f = steps(helpers.size)
        return helpers.mapIndexed { i, helper ->
            (0 until helper.size - 1).map { j ->
                val k = if (pointEdge && j % 2 == 1 && flipCopies) helpers.lastIndex - i else i
                helper[j] * (1 - f[k]) + helper[j + 1] * f[k]
            }
        }
    }

    private fun guidedHelpers(original: List<Point>, guide: List<Point>, p: RippleParameters, closed: Boolean): List<List<Point>> {
        require(guide.size >= 2) { "Ripple guide requires at least two points" }
        val outline = if (p.manual_pattern_placement) original else RunningStitch.evenRunningStitch(original,
            if (p.gridPx > 0) listOf(p.gridPx) else p.lengthsPx, p.tolerancePx)
        val count = if (p.satin_guide_pattern_position == "render_at_rungs") guide.size else guidedLineCount(guide, p, closed)
        checkGridSize(outline.size, count)
        val progression = steps(count, exponent = p.exponent.coerceAtLeast(0.1), flip = p.flip_exponent)
        val scales = steps(count, start = p.scale_start / 100, end = p.scale_end / 100)
        val startRotation = rotation(interpolate(guide, 0.0), interpolate(guide, 0.1))
        var previous: Point? = null
        val copies = List(count) { i ->
            val point = if (p.satin_guide_pattern_position == "render_at_rungs") guide[i] else interpolate(guide, progression[i], true)
            val angle = if (p.rotate_ripples && previous != null) rotation(previous!!, point) - startRotation else 0.0
            previous = point
            transform(outline, point - guide.first(), angle, scales[i], point, p.scale_axis)
        }
        return transpose(copies)
    }

    private fun satinGuideHelpers(input: RippleInput, outline: List<Point>, guide: RippleSatinGeometry,
                                  p: RippleParameters, closed: Boolean): List<List<Point>> {
        val endpoints = input.anchor ?: (outline.first() to outline.last())
        if (endpoints.first == endpoints.second) {
            val count = guidedLineCount(guide.centerLine, p, closed)
            val spacing = line(guide.centerLine).length / (count - 1).coerceAtLeast(1)
            val pairs = if (p.satin_guide_pattern_position == "render_at_rungs") guide.rungPairs.dropLast(1)
                .ifEmpty { guide.plotPointsOnRails(spacing, p.reverse_rails, p.swap_satin_rails) }
                else guide.plotPointsOnRails(spacing, p.reverse_rails, p.swap_satin_rails)
            if (pairs.isEmpty()) return emptyList()
            val initialWidth = pairs.first().first.distance(pairs.first().second)
            require(initialWidth > 0) { "Zero-width satin ripple guide" }
            val initialAngle = rotation(pairs.first().first, pairs.first().second)
            val center = line(outline).centroid.coordinate.point()
            checkGridSize(outline.size, pairs.size)
            return transpose(pairs.map { (a, b) ->
                val guideCenter = (a + b) / 2.0
                transform(outline, guideCenter - center, if (p.rotate_ripples) rotation(a, b) - initialAngle else 0.0,
                    a.distance(b) / initialWidth, guideCenter, p.scale_axis)
            })
        }
        val width = endpoints.first.distance(endpoints.second)
        val outlineRotation = rotation(endpoints.first, endpoints.second)
        if (p.satin_guide_pattern_position == "adaptive") return adaptiveHelpers(outline, guide, p, endpoints.first, width, outlineRotation)
        val pairs = if (p.satin_guide_pattern_position == "render_at_rungs") guide.rungPairs else {
            val count = guidedLineCount(guide.centerLine, p, closed)
            guide.plotPointsOnRails(line(guide.centerLine).length / (count - 1).coerceAtLeast(1), p.reverse_rails, p.swap_satin_rails)
        }
        if (pairs.isEmpty()) return emptyList()
        val selected = if (pairs.first() == pairs.last()) pairs.dropLast(1) else pairs
        checkGridSize(outline.size, selected.size)
        return transpose(selected.map { (a, b) -> transform(outline, a - endpoints.first,
            rotation(a, b) - outlineRotation, a.distance(b) / width, a, 0) })
    }

    private fun adaptiveHelpers(original: List<Point>, guide: RippleSatinGeometry, p: RippleParameters,
                                outline0: Point, width: Double, angle: Double): List<List<Point>> {
        val outline = transform(original, Point(0.0, 0.0), -angle, 1.0, outline0, 0)
        val envelope = line(outline).envelopeInternal
        val bbox = factory.toGeometry(envelope)
        val minDistance = p.minDistancePx ?: 0.1
        val shifted = org.locationtech.jts.geom.Envelope(envelope.minX, envelope.maxX, envelope.minY - minDistance, envelope.maxY - minDistance)
        val distanceBox = factory.toGeometry(shifted)
        var previousBox: Geometry? = null
        val copies = mutableListOf<List<Point>>()
        for ((a, b) in guide.plotPointsOnRails(0.1, p.reverse_rails, p.swap_satin_rails)) {
            checkInterrupted()
            val scaling = a.distance(b) / width
            val rotation = rotation(a, b)
            val translation = a - outline0
            val currentBox = transformGeometry(bbox, translation, rotation, scaling, a)
            if (previousBox?.disjoint(currentBox) == false) continue
            checkGridSize(outline.size, copies.size + 1)
            copies += transform(outline, translation, rotation, scaling, a, 0)
            previousBox = transformGeometry(distanceBox, translation, rotation, scaling, a)
        }
        return transpose(copies)
    }

    private fun sewLines(lines: List<List<Point>>, p: RippleParameters, linear: Boolean, skipStart: Int): List<Point> {
        if (p.manual_pattern_placement) {
            val stitches = mutableListOf<Point>()
            lines.forEachIndexed { i, original ->
                checkInterrupted()
                val row = if (i % 2 == 0 && p.flip_copies) original.reversed() else original
                if (row.isEmpty()) return@forEachIndexed
                if (stitches.isNotEmpty()) {
                    val a = stitches.last(); val b = row.first()
                    val count = ceil(a.distance(b) / p.lengthsPx.first()).toInt().coerceAtLeast(1)
                    require(stitches.size.toLong() + count + row.size <= MAX_POINTS) { "Ripple exceeds stitch limit" }
                    for (part in 1 until count) stitches += a + (b - a) * (part.toDouble() / count)
                }
                stitches += row
            }
            return stitches
        }
        if (linear && p.flip_copies) return staggeredStitches(lines, p, skipStart)
        return run(lines.flatten(), p, p.random_seed)
    }

    private fun staggeredStitches(lines: List<List<Point>>, p: RippleParameters, skipStart: Int): List<Point> {
        val result = mutableListOf<Point>()
        lines.forEachIndexed { index, original ->
            checkInterrupted()
            if (original.size <= 1) return@forEachIndexed
            var row = original
            val connector = if (result.isNotEmpty() && p.join_style == 0) {
                val first = if (index % 2 == 0 || !p.flip_copies) row.first() else row.last()
                RunningStitch.evenRunningStitch(listOf(result.last(), first), p.lengthsPx, p.tolerancePx).drop(1).dropLast(1)
            } else emptyList()
            val reverse = (if (p.join_style == 0) index else index + skipStart) % 2 == 1
            val seed = "${p.random_seed}/$index"
            val stitched = if (p.staggers == 0.0) {
                if (reverse && p.flip_copies) row = row.reversed()
                run(row, p, seed)
            } else {
                val points = applyStagger(row, p, index, seed)
                if (reverse && p.flip_copies) points.reversed() else points
            }
            if (stitched.isNotEmpty()) { result += connector; result += stitched }
            require(result.size <= MAX_POINTS) { "Ripple exceeds stitch limit" }
        }
        return result
    }

    private fun applyStagger(row: List<Point>, p: RippleParameters, index: Int, seed: String): List<Point> {
        val cycle = if (p.staggers == 0.0) 1.0 else p.staggers
        val phase = ((index / cycle) % 1 + 1) % 1 // Python modulo, including negative staggers.
        val start = phase * p.lengthsPx.sum()
        val segment = row[1] - row[0]
        val length = segment.length().coerceAtLeast(0.1)
        val center = (row[0] + row[1]) / 2.0
        val scale = (length + 2 * start) / length
        val extendedFirst = center + (row[0] - center) * scale
        val points = RunningStitch.runningStitch(listOf(extendedFirst) + row, p.lengthsPx, p.tolerancePx,
            p.enable_random_stitch_length, p.jitter, seed, 0.0)
        if (points.size <= 1) return emptyList()
        val indexed = LengthIndexedLine(line(points))
        return indexed.extractLine(start, indexed.endIndex).coordinates.map { it.point() }
    }

    private fun run(points: List<Point>, p: RippleParameters, seed: String) = RunningStitch.runningStitch(
        points, p.lengthsPx, p.tolerancePx, p.enable_random_stitch_length, p.jitter, seed)

    internal fun nearestGridCorner(grid: List<List<Point>>, last: Point): List<List<Point>> {
        if (grid.isEmpty() || grid.first().isEmpty()) return grid
        val corners = listOf(grid.first().first(), grid.first().last(), grid.last().first(), grid.last().last())
        val index = corners.indices.minBy { corners[it].distance(last) }
        var result = if (index == 1 || index == 3) grid.map { it.reversed() } else grid
        if (index == 2 || index == 3) result = result.reversed()
        return result
    }

    internal fun <T> repeatCoordinates(points: List<T>, repeats: Int): List<T> {
        require(repeats.toLong() * points.size <= MAX_POINTS) { "Ripple repeat exceeds stitch limit" }
        return (0 until repeats).flatMap { if (it % 2 == 1) points.reversed() else points }
    }

    private fun transpose(copies: List<List<Point>>): List<List<Point>> {
        if (copies.isEmpty()) return emptyList()
        require(copies.size.toLong() * copies[0].size <= MAX_POINTS) { "Ripple helper grid exceeds limit" }
        require(copies.all { it.size == copies[0].size }) { "Unequal guided ripple copies" }
        return copies[0].indices.map { i -> copies.map { it[i] } }
    }
    private fun checkGridSize(a: Int, b: Int) {
        require(a.toLong() * b <= MAX_POINTS) { "Ripple helper grid exceeds limit" }
        checkInterrupted()
    }
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Ripple generation interrupted")
    }
    private fun line(points: List<Point>) = factory.createLineString(points.map { Coordinate(it.x, it.y) }.toTypedArray())
    private fun Coordinate.point() = Point(x, y)
    private fun centroid(paths: List<List<Point>>) = factory.createMultiLineString(paths.map { line(it) }.toTypedArray()).convexHull().centroid.coordinate.point()
    private fun interpolate(points: List<Point>, distance: Double, normalized: Boolean = false): Point {
        val l = line(points)
        return LengthIndexedLine(l).extractPoint(if (normalized) distance * l.length else distance).point()
    }
    private fun rotation(a: Point, b: Point) = atan2(b.y - a.y, b.x - a.x)
    private fun transform(points: List<Point>, translation: Point, rotation: Double, scale: Double,
                          origin: Point, axis: Int): List<Point> = points.map { point ->
        val moved = point + translation - origin
        val rotated = Point(moved.x * cos(rotation) - moved.y * sin(rotation), moved.x * sin(rotation) + moved.y * cos(rotation))
        origin + Point(rotated.x * if (axis == 2 || axis == 3) 1.0 else scale,
            rotated.y * if (axis == 1 || axis == 3) 1.0 else scale)
    }
    private fun transformGeometry(geometry: Geometry, translation: Point, rotation: Double, scale: Double, origin: Point): Geometry {
        val transform = AffineTransformation.translationInstance(translation.x, translation.y)
        transform.rotate(rotation, origin.x, origin.y)
        // JTS composes these operations on the left: T(origin) · S · T(-origin).
        transform.translate(-origin.x, -origin.y)
        transform.scale(scale, scale)
        transform.translate(origin.x, origin.y)
        return transform.transform(geometry)
    }
}
