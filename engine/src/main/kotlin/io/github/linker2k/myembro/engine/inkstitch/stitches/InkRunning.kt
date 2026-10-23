// SPDX-License-Identifier: GPL-3.0-or-later
// Transferred unchanged from myembro codex/letter b7106ec361eb5a75eaac54b0cba450cf1041a554; source introduced at c8d2f4f0b66376f3d0a70f313ba3717b4559dcdd.
// Copyright (c) 2010 Ink/Stitch Authors (see upstream git history); Kotlin changes: myembro contributors.
// Ported from Ink/Stitch lib/stitches/running_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// lib/stitches/running_stitch.py: AngleInterval, take_stitch, cut_segment_with_angle/circle,
// path_to_curves, stitch_curve_evenly, even_running_stitch, bean_stitch, split_segment_even_n.
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.geometry.Point
import kotlin.math.*

internal operator fun Point.plus(p: Point) = Point(x + p.x, y + p.y)
internal operator fun Point.minus(p: Point) = Point(x - p.x, y - p.y)
internal operator fun Point.times(t: Double) = Point(x * t, y * t)
internal infix fun Point.dot(p: Point) = x * p.x + y * p.y
internal fun Point.norm() = hypot(x, y)
internal fun positiveMod(x: Double, n: Double): Double = ((x % n) + n) % n

/** All dimensions use the caller's units. Deterministic (non-randomized) upstream running mode. */
object InkRunning {
    private class Angle(val a: Double = 0.0, val b: Double = 2 * PI, val all: Boolean = true) {
        fun contains(v: Double) = all || positiveMod(v - a, 2 * PI) <= positiveMod(b - a, 2 * PI)
        fun contains(p: Point) = contains(atan2(p.y, p.x))
        fun intersect(o: Angle): Angle? = when {
            all -> o
            o.all -> this
            contains(o.a) -> if (o.contains(b)) Angle(o.a, b, false) else o
            o.contains(a) -> if (contains(o.b)) Angle(a, o.b, false) else this
            else -> null
        }
        fun cut(origin: Point, p: Point, q: Point): Point {
            val x = atan2(p.y - origin.y, p.x - origin.x)
            val y = atan2(q.y - origin.y, q.x - origin.x)
            val diff = positiveMod(y - x, 2 * PI)
            if (diff == 0.0 || abs(diff - PI) < 1e-12) return p
            val segment = if (diff < PI) Angle(x - 1e-6, y + 1e-6, false) else Angle(y - 1e-6, x + 1e-6, false)
            val theta = when { segment.contains(a) -> a; segment.contains(b) -> b; else -> return p }
            val v = p - origin; val d = q - p; val c = Point(cos(theta), sin(theta))
            val denominator = d.x * c.y - d.y * c.x
            if (abs(denominator) < 1e-15) return p
            return p + d * ((v.y * c.x - v.x * c.y) / denominator).coerceIn(0.0, 1.0)
        }
        companion object {
            fun ball(p: Point, tolerance: Double): Angle {
                val d = p.norm()
                if (d <= tolerance) return Angle()
                val center = atan2(p.y, p.x); val delta = asin((tolerance / d).coerceIn(0.0, 1.0))
                return Angle(center - delta, center + delta, false)
            }
        }
    }
    private fun circle(origin: Point, radius: Double, a: Point, b: Point): Point {
        val p = a - origin; val d = b - a; val pd = p dot d; val dd = d dot d
        if (dd < 1e-15) return a
        val discriminant = pd * pd + radius * radius * dd - (p dot p) * dd
        return a + d * ((sqrt(max(0.0, discriminant)) - pd) / dd).coerceIn(0.0, 1.0)
    }
    private fun take(start: Point, points: List<Point>, index: Int, length: Double, tolerance: Double): Pair<Point, Int?> {
        var sleeve = Angle(); var last = start
        for (i in index until points.size) {
            val p = points[i]
            if (sleeve.contains(p - start)) {
                if (start.distance(p) < length) {
                    sleeve = sleeve.intersect(Angle.ball(p - start, tolerance)) ?: sleeve
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
    /** Upstream splits at turns >=45 degrees, but suppresses corner micro-messes below 2*tolerance. */
    internal fun curves(points: List<Point>, minimum: Double): List<List<Point>> {
        if (points.size < 3) return listOf(points)
        val result = arrayListOf<List<Point>>()
        var last = 0; var previous = points[1] - points[0]; var length = previous.norm()
        for (i in 1 until points.lastIndex) {
            val a = previous; val b = points[i + 1] - points[i]
            val ab = a dot b; val aabb = (a dot a) * (b dot b)
            if (aabb > 0 && ab * abs(ab) <= 0.5 * aabb) {
                if (length >= minimum) { result += points.subList(last, i + 1); last = i }
                length = 0.0
            }
            if ((b dot b) > 0) previous = b
            length += b.norm()
        }
        result += points.subList(last, points.size)
        return result
    }
    fun even(points: List<Point>, stitchLength: Double, tolerance: Double = 0.1): List<Point> {
        require(stitchLength.isFinite() && stitchLength > 0 && tolerance.isFinite() && tolerance >= 0)
        require(points.size <= 200_000)
        val clean = points.filterIndexed { i, p -> i == 0 || p != points[i - 1] }
        if (clean.size < 2) return clean
        val result = arrayListOf(clean.first())
        for (curve in curves(clean, 2 * tolerance)) {
            val remaining = DoubleArray(curve.size)
            for (i in curve.lastIndex - 1 downTo 0) remaining[i] = remaining[i + 1] + curve[i].distance(curve[i + 1])
            var index: Int? = 1; var last = curve.first()
            while (index != null && index < curve.size) {
                val i = index
                val d = last.distance(curve[i]) + remaining[i]
                if (d <= 1e-12) break
                val length = d / ceil(d / stitchLength) + 0.000001
                val (next, newIndex) = take(last, curve, i, length, tolerance)
                require(next != last || newIndex != index) { "Running stitch made no progress" }
                result += next; last = next; index = newIndex
                require(result.size <= 1_000_000)
            }
        }
        return result
    }
    fun bean(stitches: List<Point>, repeats: List<Int> = listOf(1)): List<Point> {
        require(repeats.isNotEmpty() && repeats.all { it in 0..10 })
        if (stitches.size < 2) return stitches
        val result = arrayListOf(stitches.first())
        stitches.drop(1).forEachIndexed { i, stitch ->
            result += stitch
            repeat(repeats[i % repeats.size]) { result += result.takeLast(2) }
        }
        return result
    }
    fun split(a: Point, b: Point, maximum: Double): List<Point> {
        require(maximum > 0)
        val n = ceil(a.distance(b) / maximum).toInt().coerceAtLeast(1)
        require(n <= 1_000_000)
        return (1 until n).map { a.interpolate(b, it.toDouble() / n) }
    }
}
