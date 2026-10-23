// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/running_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import kotlin.math.*

/** The deterministic even_running_stitch subset needed for fill travel. All arguments are SVG pixels. */
object FillRunning {
    fun run(points: List<FillPoint>, stitchLengthPx: Double, tolerancePx: Double,
        minStitchLengthPx: Double = 0.1, adaptToLength: Boolean = true): List<FillPoint> =
        sample(points, stitchLengthPx, tolerancePx, minStitchLengthPx, adaptToLength, true)

    /** stitch_curve_evenly: no corner splitting, includes the end but excludes the first point. */
    fun curveEvenly(points: List<FillPoint>, stitchLengthPx: Double, tolerancePx: Double): List<FillPoint> =
        sample(points, stitchLengthPx, tolerancePx, 0.1, true, false).drop(1)

    private fun sample(points: List<FillPoint>, stitchLengthPx: Double, tolerancePx: Double,
        minStitchLengthPx: Double, adaptToLength: Boolean, splitCorners: Boolean): List<FillPoint> {
        require(stitchLengthPx.isFinite() && stitchLengthPx > 0 && tolerancePx.isFinite() && tolerancePx >= 0)
        require(minStitchLengthPx.isFinite() && minStitchLengthPx >= 0 && points.size <= 200_000)
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf(points.first())
        var distancePassed = 0.0
        for (curve in if (splitCorners) curves(points, 2 * tolerancePx) else listOf(points)) {
            if (curve.size < 2) continue
            val distLeft = DoubleArray(curve.size)
            for (j in curve.lastIndex - 1 downTo 0) distLeft[j] = distLeft[j+1] + curve[j].distance(curve[j+1])
            val stitched = mutableListOf<FillPoint>()
            var index: Int? = 1
            var last = curve.first()
            while (index != null && index < curve.size) {
                val i = index
                val length = if (adaptToLength) {
                    val left = last.distance(curve[i]) + distLeft[i]
                    if (left == 0.0) break
                    left / ceil(left / stitchLengthPx) + 0.000001
                } else if (distancePassed + 1 < stitchLengthPx) max(0.1, stitchLengthPx - distancePassed) else stitchLengthPx
                val (stitch, next) = takeStitch(last, curve, i, length, tolerancePx)
                if (adaptToLength || stitched.isEmpty() || stitch.distance(stitched.last()) >= minStitchLengthPx) {
                    if (!adaptToLength && stitched.isNotEmpty()) {
                        val d = stitch.distance(stitched.last())
                        if (d > stitchLengthPx + 0.001 && d >= 2 * minStitchLengthPx)
                            stitched += takeStitch(last, curve, i, d / 2, tolerancePx).first
                    }
                    stitched += stitch
                }
                distancePassed += last.distance(stitch)
                if (distancePassed + 0.1 >= stitchLengthPx) distancePassed = 0.0
                check(stitch.distance(last) > 1e-12 || next == null || next > i) { "Running stitch made no progress" }
                last = stitch; index = next
                require(result.size + stitched.size <= 1_000_000) { "Fill travel exceeds 1000000 stitches" }
            }
            if (!adaptToLength) {
                val distance = last.distance(curve.last())
                if (stitched.isNotEmpty() && distance < minStitchLengthPx) stitched.removeAt(stitched.lastIndex)
                stitched += curve.last()
                distancePassed += distance
            }
            result += stitched
        }
        return result
    }

    private fun curves(points: List<FillPoint>, minLength: Double): List<List<FillPoint>> {
        if (points.size < 3) return listOf(points)
        val curves = mutableListOf<List<FillPoint>>()
        var last = 0
        var previous = points[1] - points[0]
        var segmentLength = previous.length()
        for (i in 1 until points.lastIndex) {
            val next = points[i+1] - points[i]
            val aaBB = previous.dot(previous) * next.dot(next)
            val dot = previous.dot(next)
            if (aaBB > 0 && dot * abs(dot) <= 0.5 * aaBB) {
                if (segmentLength >= minLength) { curves += points.subList(last, i+1); last = i }
                segmentLength = 0.0
            }
            if (next.dot(next) > 0) previous = next
            segmentLength += next.length()
        }
        curves += points.subList(last, points.size)
        return curves
    }

    private class Interval(val a: Double, val b: Double, val all: Boolean = false) {
        fun contains(angle: Double) = all || positiveModulo(angle-a, 2*PI) <= positiveModulo(b-a, 2*PI)
        fun contains(p: FillPoint) = contains(atan2(p.y, p.x))
        fun intersect(other: Interval): Interval? = when {
            all -> other
            other.all -> this
            contains(other.a) -> if (other.contains(b)) Interval(other.a, b) else other
            other.contains(a) -> if (contains(other.b)) Interval(a, other.b) else this
            else -> null
        }
        fun cut(origin: FillPoint, aPoint: FillPoint, bPoint: FillPoint): FillPoint {
            val aAngle = atan2(aPoint.y-origin.y, aPoint.x-origin.x)
            val bAngle = atan2(bPoint.y-origin.y, bPoint.x-origin.x)
            val diff = positiveModulo(bAngle-aAngle, 2*PI)
            if (diff == 0.0 || diff == PI) return aPoint
            val arc = if (diff < PI) Interval(aAngle-1e-6, bAngle+1e-6) else Interval(bAngle-1e-6, aAngle+1e-6)
            val angle = when { arc.contains(a) -> a; arc.contains(b) -> b; else -> error("No sleeve intersection") }
            val p = aPoint-origin; val d = bPoint-aPoint; val c = FillPoint(cos(angle), sin(angle))
            val denominator = d.x*c.y-d.y*c.x
            // A zero-width sleeve on almost-collinear coordinates is numerically singular.
            // Keep the vertex instead of producing a far-away cut or discarding the path.
            if (abs(denominator) <= 1e-12 * max(1.0, d.length())) return aPoint
            val t = (p.y*c.x-p.x*c.y) / denominator
            require(t in -1e-6..1.000001) { "Invalid angle cut" }
            return aPoint + d*t
        }
        companion object {
            fun ball(p: FillPoint, tolerance: Double): Interval {
                val distance = p.length()
                if (distance <= tolerance) return Interval(0.0, 2*PI, true)
                val center = atan2(p.y, p.x); val delta = asin(tolerance / distance)
                return Interval(center-delta, center+delta)
            }
        }
    }

    private fun circle(origin: FillPoint, radius: Double, a: FillPoint, b: FillPoint): FillPoint {
        val p = a-origin; val d = b-a; val pd = p.dot(d); val d2 = d.dot(d)
        val discriminant = pd*pd + radius*radius*d2 - p.dot(p)*d2
        val t = (sqrt(max(0.0, discriminant))-pd) / d2
        require(t in -1e-6..1.000001) { "Invalid circle cut" }
        return a + d*t
    }

    private fun takeStitch(start: FillPoint, points: List<FillPoint>, index: Int, length: Double,
        tolerance: Double): Pair<FillPoint, Int?> {
        var sleeve = Interval(0.0, 2*PI, true)
        var last = start
        for (i in index until points.size) {
            val p = points[i]
            if (sleeve.contains(p-start)) {
                if (start.distance(p) < length) {
                    sleeve = sleeve.intersect(Interval.ball(p-start, tolerance)) ?: error("Empty running sleeve")
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
