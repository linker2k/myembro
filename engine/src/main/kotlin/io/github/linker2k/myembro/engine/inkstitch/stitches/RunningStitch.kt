// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/running_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.inkstitch.utils.Prng
import kotlin.math.*

/** Ink/Stitch's sleeve-fitting running stitch. All coordinates and lengths here are SVG px. */
object RunningStitch {
    private const val MAX_STITCHES = 1_000_000
    private const val TAU = 2 * PI

    fun runningStitch(
        points: List<Point>, stitch_length: List<Double>, tolerance: Double,
        is_random: Boolean = false, stitch_length_sigma: Double = 0.1, random_seed: String = "",
        min_stitch_length: Double = 0.1, adapt_to_length: Boolean = true,
    ): List<Point> = if (is_random) randomRunningStitch(points, stitch_length, tolerance, stitch_length_sigma, random_seed)
        else evenRunningStitch(points, stitch_length, tolerance, min_stitch_length, adapt_to_length)

    fun evenRunningStitch(
        points: List<Point>, stitch_length: List<Double>, tolerance: Double,
        min_stitch_length: Double = 0.1, adapt_to_length: Boolean = true,
    ): List<Point> {
        validate(points, stitch_length, tolerance)
        require(min_stitch_length.isFinite() && min_stitch_length >= 0)
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf(points.first())
        var position = 0
        var distancePassed = 0.0
        for (curve in pathToCurves(points, 2 * tolerance)) {
            checkInterrupted()
            if (adapt_to_length) {
                val generated = stitchCurveEvenly(curve, stitch_length, tolerance, position)
                append(result, generated.points)
                position = generated.position
            } else {
                val generated = stitchCurveEvenlyStrict(curve, stitch_length, tolerance, min_stitch_length, position, distancePassed)
                append(result, generated.points)
                position = generated.position
                distancePassed = generated.distancePassed
            }
        }
        return result
    }

    fun randomRunningStitch(
        points: List<Point>, stitch_length: List<Double>, tolerance: Double,
        stitch_length_sigma: Double, random_seed: String,
    ): List<Point> {
        validate(points, stitch_length, tolerance)
        require(stitch_length_sigma.isFinite() && stitch_length_sigma >= 0)
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf(points.first())
        var position = 0
        for ((curveIndex, curve) in pathToCurves(points, 2 * tolerance).withIndex()) {
            checkInterrupted()
            val generated = stitchCurveRandomly(curve, stitch_length, tolerance, stitch_length_sigma, "$random_seed/$curveIndex", position)
            append(result, generated.points)
            position = generated.position
        }
        return result
    }

    /** Split at turns of at least 45 degrees, except short runs smaller than min_len. */
    fun pathToCurves(points: List<Point>, min_len: Double): List<List<Point>> {
        require(min_len.isFinite() && min_len >= 0)
        if (points.size < 3) return listOf(points)
        val curves = mutableListOf<List<Point>>()
        var last = 0
        var lastSegment = points[1] - points[0]
        var segmentLength = lastSegment.length()
        for (i in 1 until points.lastIndex) {
            val a = lastSegment
            val b = points[i + 1] - points[i]
            val aabb = a.dot(a) * b.dot(b)
            val ab = a.dot(b)
            val abab = ab * abs(ab)
            if (aabb > 0 && abab <= 0.5 * aabb) {
                if (segmentLength >= min_len) {
                    curves += points.subList(last, i + 1).toList()
                    last = i
                }
                segmentLength = 0.0
            }
            if (b.dot(b) > 0) lastSegment = b
            segmentLength += b.length()
        }
        curves += points.subList(last, points.size).toList()
        return curves
    }

    /** Preserve source flags/tags while adding alternating backtracks per original segment. */
    fun beanStitch(stitches: List<Stitch>, repeats: List<Int>, tags_to_ignore: Set<String> = emptySet()): List<Stitch> {
        require(repeats.isNotEmpty()) { "bean_stitch_repeats must not be empty" }
        if (stitches.size < 2) return stitches.toList()
        val result = mutableListOf(stitches.first())
        for ((i, stitch) in stitches.drop(1).withIndex()) {
            append(result, listOf(stitch))
            if (stitch.tags.any { it in tags_to_ignore }) continue
            val count = repeats[i % repeats.size].coerceAtLeast(0)
            require(count.toLong() * 2 + result.size <= MAX_STITCHES) { "Bean stitch exceeds $MAX_STITCHES stitches" }
            repeat(count) {
                val a = result[result.lastIndex - 1]
                val b = result.last()
                result += a.copy()
                result += b.copy()
            }
        }
        return result
    }

    /** Equal-distance complete zigzag cycles, with averaged tangents and per-side compensation. */
    fun zigzagStitch(
        stitches: List<Stitch>, zigzag_spacing: List<Double>, stroke_width: Double,
        pull_compensation: Pair<Double, Double> = 0.0 to 0.0, zigzag_angle: Double = 0.0,
    ): List<Stitch> {
        require(zigzag_spacing.isNotEmpty() && zigzag_spacing.all { it.isFinite() && it > 0 })
        require(stroke_width.isFinite() && pull_compensation.first.isFinite() && pull_compensation.second.isFinite() && zigzag_angle.isFinite())
        if (stitches.size < 2) return stitches.toList()
        val line = ArcLine(stitches.map { it.point })
        if (line.length == 0.0) return stitches.toList()
        val rawCount = round(line.length / (zigzag_spacing.first() / 2))
        require(rawCount <= MAX_STITCHES - 2) { "Zigzag exceeds $MAX_STITCHES stitches" }
        var segments = max(2, rawCount.toInt())
        if (segments % 2 == 1) segments++
        val positions = (0..segments).map { line.interpolate(it.toDouble() / segments * line.length) }
        val angle = Math.toRadians(zigzag_angle)
        val ca = cos(angle)
        val sa = sin(angle)
        return positions.mapIndexed { i, position ->
            val direction = when (i) {
                0 -> positions[1] - positions[0]
                positions.lastIndex -> positions[i] - positions[i - 1]
                else -> {
                    val incoming = positions[i] - positions[i - 1]
                    val outgoing = positions[i + 1] - positions[i]
                    when {
                        incoming.length() > 0 && outgoing.length() > 0 -> (incoming.unit() + outgoing.unit()) / 2.0
                        incoming.length() > 0 -> incoming
                        else -> outgoing
                    }
                }
            }
            val tangent = direction.unit()
            val perpendicular = Point(-tangent.y, tangent.x)
            val rotated = Point(perpendicular.x * ca - perpendicular.y * sa, perpendicular.x * sa + perpendicular.y * ca)
            val offset = if (i % 2 == 1) -(stroke_width / 2 + pull_compensation.first) else stroke_width / 2 + pull_compensation.second
            val shifted = position + rotated * offset
            (stitches.getOrNull(i) ?: Stitch(position)).copy(x = shifted.x, y = shifted.y)
        }
    }

    private data class CurveResult(val points: List<Point>, val position: Int, val distancePassed: Double = 0.0)

    private fun stitchCurveEvenly(points: List<Point>, lengths: List<Double>, tolerance: Double, initialPosition: Int): CurveResult {
        if (points.size < 2) return CurveResult(emptyList(), initialPosition)
        val left = DoubleArray(points.size)
        for (i in points.lastIndex - 1 downTo 0) left[i] = left[i + 1] + points[i].distance(points[i + 1])
        var index: Int? = 1
        var position = initialPosition
        var last = points.first()
        val result = mutableListOf<Point>()
        while (index != null && index < points.size) {
            checkInterrupted()
            val remaining = last.distance(points[index]) + left[index]
            if (remaining == 0.0) break
            val length = remaining / ceil(remaining / lengths[position]) + 0.000001
            val taken = takeStitch(last, points, index, length, tolerance)
            index = taken.index
            taken.point?.let {
                append(result, listOf(it))
                last = it
                position = (position + 1) % lengths.size
            }
        }
        return CurveResult(result, position)
    }

    private fun stitchCurveEvenlyStrict(
        points: List<Point>, lengths: List<Double>, tolerance: Double, minimum: Double,
        initialPosition: Int, initialDistancePassed: Double,
    ): CurveResult {
        if (points.size < 2) return CurveResult(emptyList(), initialPosition, initialDistancePassed)
        var index: Int? = 1
        var position = initialPosition
        var distancePassed = initialDistancePassed
        var last = points.first()
        val result = mutableListOf<Point>()
        while (index != null && index < points.size) {
            checkInterrupted()
            var length = lengths[position]
            if (distancePassed + 1 < length) length = max(0.1, length - distancePassed)
            val taken = takeStitch(last, points, index, length, tolerance)
            taken.point?.let { stitch ->
                if (result.isEmpty() || stitch.distance(result.last()) >= minimum) {
                    if (result.isNotEmpty()) {
                        val distance = stitch.distance(result.last())
                        if (distance > lengths[position] + 0.001 && distance >= 2 * minimum) {
                            takeStitch(last, points, index, distance / 2, tolerance).point?.let { append(result, listOf(it)) }
                        }
                    }
                    append(result, listOf(stitch))
                }
                distancePassed += last.distance(stitch)
                if (distancePassed + 0.1 >= lengths[position]) {
                    distancePassed = 0.0
                    position = (position + 1) % lengths.size
                }
                index = taken.index
                last = stitch
            }
        }
        val distanceToEnd = last.distance(points.last())
        if (result.isNotEmpty() && distanceToEnd < minimum) result.removeAt(result.lastIndex)
        append(result, listOf(points.last()))
        return CurveResult(result, position, distancePassed + distanceToEnd)
    }

    private fun stitchCurveRandomly(
        points: List<Point>, lengths: List<Double>, tolerance: Double, sigma: Double, seed: String, initialPosition: Int,
    ): CurveResult {
        if (points.size < 2) return CurveResult(emptyList(), initialPosition)
        var position = initialPosition
        var minimum = max(0.0, lengths[position] * (1 - sigma))
        var maximum = lengths[position] * (1 + sigma)
        var index: Int? = 1
        var last = points.first()
        var lastShortened = 0.0
        val random = Prng.iterUniformFloats(seed).iterator()
        val result = mutableListOf<Point>()
        while (index != null && index < points.size) {
            checkInterrupted()
            if (lengths.size > 1) {
                minimum = max(0.0, lengths[position] * (1 - sigma))
                maximum = lengths[position] * (1 + sigma)
                position = (position + 1) % lengths.size
            }
            val r = random.next()
            val length = lerp(lastShortened, 1.0, r) * lerp(minimum, maximum, r)
            // A zero PRNG output is possible in the source stream; it cannot advance a stitch.
            require(length > 0) { "Random stitch length is zero for seed $seed" }
            val taken = takeStitch(last, points, index, length, tolerance)
            index = taken.index
            taken.point?.let {
                append(result, listOf(it))
                lastShortened = min(last.distance(it) / length, 1.0)
                last = it
            }
        }
        return CurveResult(result, position)
    }

    private data class Taken(val point: Point?, val index: Int?)

    private fun takeStitch(start: Point, points: List<Point>, index: Int, length: Double, tolerance: Double): Taken {
        if (index >= points.size) return Taken(null, null)
        var sleeve = AngleInterval.all()
        var last = start
        for (i in index until points.size) {
            val p = points[i]
            if (sleeve.contains(p - start)) {
                if (start.distance(p) < length) {
                    sleeve = checkNotNull(sleeve.intersect(AngleInterval.fromBall(p - start, tolerance)))
                    last = p
                    continue
                }
                return Taken(cutCircle(start, length, last, p), i)
            }
            var cut = sleeve.cutSegment(start, last, p)
            if (start.distance(cut) > length) cut = cutCircle(start, length, last, p)
            return Taken(cut, i)
        }
        return Taken(points.last(), null)
    }

    private data class AngleInterval(val a: Double, val b: Double, val all: Boolean = false) {
        fun containsAngle(angle: Double): Boolean = all || modulo(angle - a) <= modulo(b - a)
        fun contains(point: Point): Boolean = containsAngle(atan2(point.y, point.x))
        fun intersect(other: AngleInterval?): AngleInterval? = when {
            other == null -> null
            all -> other
            other.all -> this
            containsAngle(other.a) -> if (other.containsAngle(b)) AngleInterval(other.a, b) else other
            other.containsAngle(a) -> if (containsAngle(other.b)) AngleInterval(a, other.b) else this
            else -> null
        }
        fun cutSegment(origin: Point, a: Point, b: Point): Point {
            val arc = fromSegment(a - origin, b - origin) ?: return a
            return when {
                arc.containsAngle(this.a) -> cutAngle(origin, this.a, a, b)
                arc.containsAngle(this.b) -> cutAngle(origin, this.b, a, b)
                else -> error("Sleeve does not cross segment")
            }
        }
        companion object {
            fun all() = AngleInterval(0.0, TAU, true)
            fun fromBall(p: Point, epsilon: Double): AngleInterval {
                val distance = p.length()
                if (distance <= epsilon) return all()
                val angle = atan2(p.y, p.x)
                val delta = asin(epsilon / distance)
                return AngleInterval(angle - delta, angle + delta)
            }
            fun fromSegment(a: Point, b: Point): AngleInterval? {
                val aa = atan2(a.y, a.x)
                val ba = atan2(b.y, b.x)
                val difference = modulo(ba - aa)
                if (difference == 0.0 || difference == PI) return null
                return if (difference < PI) AngleInterval(aa - 1e-6, ba + 1e-6) else AngleInterval(ba - 1e-6, aa + 1e-6)
            }
        }
    }

    private fun cutAngle(origin: Point, angle: Double, a: Point, b: Point): Point {
        val p = a - origin
        val d = b - a
        val c = Point(cos(angle), sin(angle))
        val t = (p.y * c.x - p.x * c.y) / (d.x * c.y - d.y * c.x)
        require(t >= -0.000001 && t <= 1.000001) { "Angle intersection outside segment: $t" }
        return a + d * t
    }

    private fun cutCircle(origin: Point, radius: Double, a: Point, b: Point): Point {
        val p = a - origin
        val d = b - a
        val p2 = p.dot(p)
        val d2 = d.dot(d)
        val pd = p.dot(d)
        val t = (sqrt(pd * pd + radius * radius * d2 - p2 * d2) - pd) / d2
        require(t >= -0.000001 && t <= 1.000001) { "Circle intersection outside segment: $t" }
        return a + d * t
    }

    private class ArcLine(private val points: List<Point>) {
        private val distances = DoubleArray(points.size)
        init { for (i in 1 until points.size) distances[i] = distances[i - 1] + points[i - 1].distance(points[i]) }
        val length: Double get() = distances.last()
        fun interpolate(distance: Double): Point {
            if (distance <= 0) return points.first()
            if (distance >= length) return points.last()
            var end = distances.binarySearch(distance)
            if (end >= 0) return points[end]
            end = -end - 1
            val fraction = (distance - distances[end - 1]) / (distances[end] - distances[end - 1])
            return points[end - 1] + (points[end] - points[end - 1]) * fraction
        }
    }

    private fun validate(points: List<Point>, lengths: List<Double>, tolerance: Double) {
        require(points.size <= MAX_STITCHES) { "Too many input points" }
        require(lengths.isNotEmpty() && lengths.all { it.isFinite() && it > 0 }) { "Stitch lengths must be positive" }
        require(tolerance.isFinite() && tolerance >= 0) { "Tolerance must be nonnegative" }
    }
    private fun <T> append(target: MutableList<T>, values: List<T>) {
        require(target.size.toLong() + values.size <= MAX_STITCHES) { "Running stitch exceeds $MAX_STITCHES stitches" }
        target.addAll(values)
    }
    private fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Stitch generation interrupted") }
    private fun modulo(value: Double): Double = (value % TAU).let { if (it < 0) it + TAU else it }
    private fun lerp(a: Double, b: Double, t: Double): Double = (1 - t) * a + t * b
}
