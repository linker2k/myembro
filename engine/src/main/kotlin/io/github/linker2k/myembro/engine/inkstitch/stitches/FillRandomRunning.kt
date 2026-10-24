// Ported from Ink/Stitch lib/stitches/running_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/utils/prng.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * random_running_stitch/stitch_curve_randomly, in SVG pixels. Sigma is a relative
 * fraction (10% = 0.1), not mm and not a Gaussian standard deviation. The source
 * intentionally draws the first stitch with a shorter, randomized phase.
 */
object FillRandomRunning {
    data class CurveResult(val stitches: List<FillPoint>, val stitchLengthPosition: Int)

    fun run(points: List<FillPoint>, stitchLengthPx: Double, tolerancePx: Double,
        stitchLengthSigma: Double = 0.1, randomSeed: String = ""): List<FillPoint> =
        run(points, listOf(stitchLengthPx), tolerancePx, stitchLengthSigma, randomSeed)

    fun run(points: List<FillPoint>, stitchLengthsPx: List<Double>, tolerancePx: Double,
        stitchLengthSigma: Double = 0.1, randomSeed: String = ""): List<FillPoint> {
        validate(points, stitchLengthsPx, tolerancePx, stitchLengthSigma)
        if (points.isEmpty()) return emptyList()
        val stitches = mutableListOf(points.first())
        var position = 0
        for ((index, curve) in curves(points, 2 * tolerancePx).withIndex()) {
            val result = curveRandomly(curve, stitchLengthsPx, tolerancePx, stitchLengthSigma,
                "$randomSeed/$index", position)
            stitches += result.stitches
            position = result.stitchLengthPosition
            require(stitches.size <= 1_000_000) { "Random running stitch resource limit exceeded" }
        }
        return stitches
    }

    /** Includes the end but excludes the first point; list-length phase carries between curves. */
    fun curveRandomly(points: List<FillPoint>, stitchLengthsPx: List<Double>, tolerancePx: Double,
        stitchLengthSigma: Double, randomSeed: String, stitchLengthPosition: Int = 0): CurveResult {
        validate(points, stitchLengthsPx, tolerancePx, stitchLengthSigma)
        require(stitchLengthPosition in stitchLengthsPx.indices)
        if (points.size < 2) return CurveResult(emptyList(), stitchLengthPosition)
        var position = stitchLengthPosition
        var minimum = max(0.0, stitchLengthsPx[position] * (1 - stitchLengthSigma))
        var maximum = stitchLengthsPx[position] * (1 + stitchLengthSigma)
        var index: Int? = 1
        var last = points.first()
        var lastShortened = 0.0
        val stitches = mutableListOf<FillPoint>()
        val random = uniformFloats(randomSeed).iterator()
        while (index != null && index < points.size) {
            if (stitchLengthsPx.size > 1) {
                minimum = max(0.0, stitchLengthsPx[position] * (1 - stitchLengthSigma))
                maximum = stitchLengthsPx[position] * (1 + stitchLengthSigma)
                position = (position + 1) % stitchLengthsPx.size
            }
            val r = random.next()
            // Reuse the SAME random draw in the phase and length factors (not two draws).
            val requested = lerp(lastShortened, 1.0, r) * lerp(minimum, maximum, r)
            require(requested.isFinite() && requested > 0) { "Random stitch length cannot make progress" }
            val (stitch, next) = takeStitch(last, points, index, requested, tolerancePx)
            stitches += stitch
            require(stitches.size <= 1_000_000) { "Random running stitch resource limit exceeded" }
            lastShortened = min(last.distance(stitch) / requested, 1.0)
            check(stitch.distance(last) > 1e-12 || next == null || next > index) { "Random running stitch made no progress" }
            last = stitch
            index = next
        }
        return CurveResult(stitches, position)
    }

    /** prng.iter_uniform_floats(seed): big-endian words from each BLAKE2s hex digest. */
    internal fun uniformFloats(seed: String): Sequence<Double> = sequence {
        var counter = 0L
        while (true) {
            val hash = MeanderPrng.blake2s("$seed/${counter++}".toByteArray(Charsets.UTF_8))
            for (i in 0..7) {
                var value = 0L
                for (j in 0..3) value = (value shl 8) or (hash[i * 4 + j].toLong() and 255)
                yield(value.toDouble() / 4294967295.0)
            }
        }
    }

    private fun validate(points: List<FillPoint>, lengths: List<Double>, tolerance: Double, sigma: Double) {
        require(points.size <= 200_000 && lengths.isNotEmpty() && lengths.size <= 10_000)
        require(lengths.all { it.isFinite() && it > 0 && (it * (1 + sigma)).isFinite() })
        require(tolerance.isFinite() && tolerance >= 0 && sigma.isFinite() && sigma >= 0)
    }
    private fun lerp(a: Double, b: Double, t: Double) = (1 - t) * a + t * b

    private fun curves(points: List<FillPoint>, minLength: Double): List<List<FillPoint>> {
        if (points.size < 3) return listOf(points)
        val result = mutableListOf<List<FillPoint>>()
        var last = 0
        var previous = points[1] - points[0]
        var segmentLength = previous.length()
        for (i in 1 until points.lastIndex) {
            val next = points[i + 1] - points[i]
            val aaBB = previous.dot(previous) * next.dot(next)
            val dot = previous.dot(next)
            if (aaBB > 0 && dot * abs(dot) <= 0.5 * aaBB) {
                if (segmentLength >= minLength) { result += points.subList(last, i + 1); last = i }
                segmentLength = 0.0
            }
            if (next.dot(next) > 0) previous = next
            segmentLength += next.length()
        }
        result += points.subList(last, points.size)
        return result
    }

    private class Interval(val a: Double, val b: Double, val all: Boolean = false) {
        fun contains(angle: Double) = all || positiveModulo(angle - a, 2 * PI) <= positiveModulo(b - a, 2 * PI)
        fun contains(p: FillPoint) = contains(atan2(p.y, p.x))
        fun intersect(other: Interval): Interval? = when {
            all -> other
            other.all -> this
            contains(other.a) -> if (other.contains(b)) Interval(other.a, b) else other
            other.contains(a) -> if (contains(other.b)) Interval(a, other.b) else this
            else -> null
        }
        fun cut(origin: FillPoint, aPoint: FillPoint, bPoint: FillPoint): FillPoint {
            val aAngle = atan2(aPoint.y - origin.y, aPoint.x - origin.x)
            val bAngle = atan2(bPoint.y - origin.y, bPoint.x - origin.x)
            val diff = positiveModulo(bAngle - aAngle, 2 * PI)
            if (diff == 0.0 || diff == PI) return aPoint
            val arc = if (diff < PI) Interval(aAngle - 1e-6, bAngle + 1e-6) else Interval(bAngle - 1e-6, aAngle + 1e-6)
            val angle = when { arc.contains(a) -> a; arc.contains(b) -> b; else -> error("No random running sleeve intersection") }
            val p = aPoint - origin
            val d = bPoint - aPoint
            val c = FillPoint(cos(angle), sin(angle))
            val denominator = d.x * c.y - d.y * c.x
            // Same finite-precision guard as FillRunning on effectively collinear vertices.
            if (abs(denominator) <= 1e-12 * max(1.0, d.length())) return aPoint
            val t = (p.y * c.x - p.x * c.y) / denominator
            require(t in -1e-6..1.000001) { "Invalid random running angle cut" }
            return aPoint + d * t
        }
        companion object {
            fun ball(p: FillPoint, tolerance: Double): Interval {
                val length = p.length()
                if (length <= tolerance) return Interval(0.0, 2 * PI, true)
                val center = atan2(p.y, p.x)
                val delta = asin(tolerance / length)
                return Interval(center - delta, center + delta)
            }
        }
    }

    private fun circle(origin: FillPoint, radius: Double, a: FillPoint, b: FillPoint): FillPoint {
        val p = a - origin
        val d = b - a
        val pd = p.dot(d)
        val d2 = d.dot(d)
        val discriminant = pd * pd + radius * radius * d2 - p.dot(p) * d2
        val t = (sqrt(max(0.0, discriminant)) - pd) / d2
        require(t in -1e-6..1.000001) { "Invalid random running circle cut" }
        return a + d * t
    }

    private fun takeStitch(start: FillPoint, points: List<FillPoint>, index: Int, length: Double,
        tolerance: Double): Pair<FillPoint, Int?> {
        var sleeve = Interval(0.0, 2 * PI, true)
        var last = start
        for (i in index until points.size) {
            val p = points[i]
            if (sleeve.contains(p - start)) {
                if (start.distance(p) < length) {
                    sleeve = sleeve.intersect(Interval.ball(p - start, tolerance)) ?: error("Empty random running sleeve")
                    last = p
                } else return circle(start, length, last, p) to i
            } else {
                var cut = sleeve.cut(start, last, p)
                if (start.distance(cut) > length) cut = circle(start, length, last, p)
                return cut to i
            }
        }
        return points.last() to null
    }
}
