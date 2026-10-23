// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

/** All lengths are mm. Density is the distance between penetrations on the SAME rail. */
data class SatinOptions(
    val widthMm: Double = 2.0,
    val densityMm: Double = 0.4,
    val maxStitchLengthMm: Double = 7.0,
    /** Extra width on EACH side, applied only to the top layer. */
    val pullCompensationMm: Double = 0.0,
    val centerUnderlay: Boolean = false,
    val zigzagUnderlay: Boolean = false,
    val underlayStitchLengthMm: Double = 2.5,
    val underlayDensityMm: Double = 1.5,
    val autoDensity: Boolean = true,
) {
    fun densityForHeight(heightMm: Double, threadWeight: ThreadWeight = ThreadWeight.WT40): Double =
        when {
            !autoDensity -> densityMm
            threadWeight != ThreadWeight.WT40 -> threadWeight.spacingForHeight(heightMm)
            heightMm < 7.0 -> maxOf(0.6, densityMm)
            else -> densityMm
        }
    init {
        require(widthMm.isFinite() && widthMm > 0) { "widthMm must be positive" }
        require(densityMm.isFinite() && densityMm >= 0.2) { "densityMm must be at least 0.2 mm" }
        require(maxStitchLengthMm.isFinite() && maxStitchLengthMm in 0.2..7.0) { "Satin stitch limit must be 0.2..7 mm" }
        require(pullCompensationMm.isFinite() && pullCompensationMm >= 0) { "Pull compensation must be non-negative" }
        require(underlayStitchLengthMm.isFinite() && underlayStitchLengthMm in 0.2..7.0)
        require(underlayDensityMm.isFinite() && underlayDensityMm >= 0.2)
    }
}

/**
 * Basic two-rail satin. Input rails are mm, output is centered 0.1 mm machine coordinates.
 * Rails are paired by normalized arc length; reversed open rails are aligned automatically.
 * Conceptual reference: Ink/Stitch satin-column documentation (see THIRD_PARTY_NOTICES).
 */
object SatinColumn {
    /** Retain the matched normal sections produced by the outline extractor. */
    internal fun pairedColumnPoints(column: OutlineColumn, densityMm: Double): List<Pair<Double, Double>> {
        val center = column.centerline
        val lengths = DoubleArray(center.size)
        var leftLength = 0.0; var rightLength = 0.0
        for (i in 1 until center.size) {
            lengths[i] = lengths[i - 1] + distance(center[i - 1], center[i])
            leftLength += distance(column.leftRail[i - 1], column.leftRail[i])
            rightLength += distance(column.rightRail[i - 1], column.rightRail[i])
        }
        if (lengths.last() < 1e-9) return emptyList()
        fun at(t: Double, rail: List<Pair<Double, Double>>): Pair<Double, Double> {
            val target = t * lengths.last()
            val found = lengths.binarySearch(target)
            if (found >= 0) return rail[found]
            val i = (-found - 1).coerceIn(1, center.lastIndex)
            val fraction = (target - lengths[i - 1]) / (lengths[i] - lengths[i - 1]).coerceAtLeast(1e-9)
            return mix(rail[i - 1], rail[i], fraction)
        }
        val cycles = ceil(maxOf(leftLength, rightLength) / densityMm).toInt().coerceAtLeast(1)
        require(cycles <= 1_000_000)
        val top = buildList {
            for (i in 0 until cycles) {
                add(at(i.toDouble() / cycles, column.leftRail))
                add(at((i + 0.5) / cycles, column.rightRail))
            }
            add(column.leftRail.last()); add(column.rightRail.last())
        }
        return splitWideMoves(top)
    }

    private fun splitWideMoves(top: List<Pair<Double, Double>>): List<Pair<Double, Double>> = buildList {
        if (top.isNotEmpty()) add(top.first())
        top.zipWithNext().forEachIndexed { row, (p, q) ->
            val length = distance(p, q)
            if (length <= 7.0) add(q) else {
                // Maximum 3.6 mm before machine rounding; stagger interior penetrations.
                val parts = ceil(length / 3.0).toInt()
                val phase = when (row % 4) { 0 -> -0.2; 1 -> 0.1; 2 -> 0.2; else -> -0.1 }
                for (i in 1 until parts) add(mix(p, q, (i + phase) / parts))
                add(q)
            }
        }
    }
    fun generate(
        leftRail: List<Pair<Double, Double>>,
        rightRail: List<Pair<Double, Double>>,
        options: SatinOptions = SatinOptions(),
        thread: EmbThread = EmbThread(0xFF000000.toInt(), "Black"),
    ): EmbPattern {
        val points = columnPoints(leftRail, rightRail, options)
        val builder = RunningStitches(options.maxStitchLengthMm, true, simplifyPaths = false)
        if (points.isNotEmpty()) builder.stroke(points.map { (x, y) -> x * 10 to y * 10 }, thread)
        return builder.finish(thread)
    }

    /** Offset a flattened centerline, then sew it as satin. Closed paths retain a closed seam. */
    fun strokeToSatin(
        stroke: List<Pair<Double, Double>>,
        widthMm: Double,
        options: SatinOptions = SatinOptions(),
        thread: EmbThread = EmbThread(0xFF000000.toInt(), "Black"),
    ): EmbPattern {
        val (left, right) = rails(stroke, widthMm)
        return generate(left, right, options, thread)
    }

    /** Mitered polyline offsets, with a 2× half-width miter limit at sharp corners. */
    fun rails(stroke: List<Pair<Double, Double>>, widthMm: Double): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
        require(widthMm.isFinite() && widthMm > 0)
        val clean = clean(stroke)
        if (clean.size < 2) return emptyList<Pair<Double, Double>>() to emptyList()
        val closed = clean.size > 3 && clean.first() == clean.last()
        val points = if (closed) clean.dropLast(1) else clean
        val half = widthMm / 2
        val left = ArrayList<Pair<Double, Double>>()
        val right = ArrayList<Pair<Double, Double>>()
        fun normal(a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
            val length = distance(a, b)
            return -(b.second - a.second) / length to (b.first - a.first) / length
        }
        points.forEachIndexed { i, p ->
            val before = if (i > 0) normal(points[i - 1], p) else if (closed) normal(points.last(), p) else normal(p, points[1])
            val after = if (i < points.lastIndex) normal(p, points[i + 1]) else if (closed) normal(p, points.first()) else before
            val sum = before.first + after.first to before.second + after.second
            val length = hypot(sum.first, sum.second)
            val bisector = if (length > 1e-9) sum.first / length to sum.second / length else before
            val projection = (bisector.first * before.first + bisector.second * before.second).coerceAtLeast(0.5)
            val offset = half / projection
            left += p.first + bisector.first * offset to p.second + bisector.second * offset
            right += p.first - bisector.first * offset to p.second - bisector.second * offset
        }
        if (closed) { left += left.first(); right += right.first() }
        return left to right
    }

    internal fun strokePoints(stroke: List<Pair<Double, Double>>, options: SatinOptions): List<Pair<Double, Double>> {
        val (left, right) = rails(stroke, options.widthMm)
        return columnPoints(left, right, options)
    }

    private fun columnPoints(
        leftInput: List<Pair<Double, Double>>, rightInput: List<Pair<Double, Double>>, options: SatinOptions,
    ): List<Pair<Double, Double>> {
        val left = clean(leftInput)
        var right = clean(rightInput)
        if (left.size < 2 || right.size < 2) return emptyList()
        val forward = distance(left.first(), right.first()) + distance(left.last(), right.last())
        val backward = distance(left.first(), right.last()) + distance(left.last(), right.first())
        if (backward < forward) right = right.reversed()
        val a = Rail(left)
        val b = Rail(right)
        val length = maxOf(a.length, b.length)
        fun pair(t: Double, scale: Double = 1.0, compensation: Double = 0.0): Pair<Pair<Double, Double>, Pair<Double, Double>> {
            val p = a.at(t)
            val q = b.at(t)
            val width = distance(p, q)
            val factor = scale / 2 + if (width > 1e-9) compensation / width else 0.0
            val center = mix(p, q, 0.5)
            return (center.first + (p.first - q.first) * factor to center.second + (p.second - q.second) * factor) to
                (center.first + (q.first - p.first) * factor to center.second + (q.second - p.second) * factor)
        }
        fun cycles(spacing: Double): Int {
            val count = ceil(length / spacing)
            require(count.isFinite() && count <= 1_000_000) { "Satin column is too long/dense" }
            return count.toInt().coerceAtLeast(1)
        }
        fun zigzag(spacing: Double, widthScale: Double, compensation: Double): List<Pair<Double, Double>> {
            val n = cycles(spacing)
            return buildList {
                for (i in 0 until n) {
                    add(pair(i.toDouble() / n, widthScale, compensation).first)
                    add(pair((i + 0.5) / n, widthScale, compensation).second)
                }
                add(pair(1.0, widthScale, compensation).first)
                add(pair(1.0, widthScale, compensation).second)
            }
        }
        // Include rail vertices in center travel, so returns never cut across a corner.
        val knots = (a.fractions() + b.fractions()).distinct().sorted()
        val center = knots.map { mix(a.at(it), b.at(it), 0.5) }
        val result = ArrayList<Pair<Double, Double>>()
        fun underlay(path: List<Pair<Double, Double>>) {
            val joined = if (result.isEmpty()) path else listOf(result.last()) + path
            joined.zipWithNext().forEach { (p, q) ->
                if (result.isEmpty()) result += p
                val parts = ceil(distance(p, q) / min(options.underlayStitchLengthMm, options.maxStitchLengthMm)).toInt().coerceAtLeast(1)
                for (i in 1..parts) result += mix(p, q, i.toDouble() / parts)
            }
        }
        if (options.centerUnderlay) underlay(center + center.asReversed().drop(1))
        if (options.zigzagUnderlay) {
            underlay(listOf(center.first()) + zigzag(options.underlayDensityMm, 0.6, 0.0) + center.asReversed())
        }
        val top = zigzag(options.densityMm, 1.0, options.pullCompensationMm)
        if (result.isNotEmpty()) underlay(listOf(top.first()))
        result += top
        return result
    }

    private fun clean(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        require(points.all { (x, y) -> x.isFinite() && y.isFinite() && kotlin.math.abs(x) < 1e7 && kotlin.math.abs(y) < 1e7 }) {
            "Satin coordinates must be finite and within machine range"
        }
        return points.filterIndexed { i, p -> i == 0 || distance(p, points[i - 1]) > 1e-9 }
    }

    private class Rail(val points: List<Pair<Double, Double>>) {
        val lengths = DoubleArray(points.size).apply {
            for (i in 1 until points.size) this[i] = this[i - 1] + distance(points[i - 1], points[i])
        }
        val length = lengths.last()
        fun fractions(): List<Double> = lengths.map { it / length }
        fun at(t: Double): Pair<Double, Double> {
            val target = t.coerceIn(0.0, 1.0) * length
            val index = lengths.binarySearch(target)
            if (index >= 0) return points[index]
            val end = (-index - 1).coerceIn(1, points.lastIndex)
            return mix(points[end - 1], points[end], (target - lengths[end - 1]) / (lengths[end] - lengths[end - 1]))
        }
    }

    private fun mix(a: Pair<Double, Double>, b: Pair<Double, Double>, t: Double) =
        a.first + (b.first - a.first) * t to a.second + (b.second - a.second) * t
    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>) = hypot(b.first - a.first, b.second - a.second)
}
