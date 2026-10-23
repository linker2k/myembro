// SPDX-License-Identifier: GPL-2.0-or-later
// Ported from Potrace src/trace.c (pointslope, adjust_vertices, reverse, smooth),
// src/auxiliary.h@886392d351417d6753aff61de08e294d1506e502 (GPL-2.0-or-later),
// Copyright (C) 2001-2019 Peter Selinger. Kotlin translation, 2026 myembro contributors.
// See resources/licenses/potrace/COPYING.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

internal data class CurvePoint(val x: Double, val y: Double)

/** Native tags: 1 = cubic Bezier; 2 = corner (control0 unused, control1 is the corner). */
internal data class CurveSegment(
    val tag: Int,
    val controls: List<CurvePoint>,
    val alpha: Double,
    val alpha0: Double,
    val beta: Double = 0.5,
)

/** Pixel coordinates, y up. Intermediate stage 4, before upstream opticurve optimization. */
internal data class FittedCurve(val vertices: List<CurvePoint>, val segments: List<CurveSegment>)

internal object CurveFitter {
    /** Upstream default alphamax=1.0. This deliberately does not claim to implement stage 5. */
    fun fit(contour: BinaryContour): FittedCurve {
        val polygon = OptimalPolygon.indices(contour)
        val vertices = adjustVertices(contour.points, polygon).let {
            if (contour.positive) it else it.reversed()
        }
        return FittedCurve(vertices, smooth(vertices))
    }

    private fun adjustVertices(pt: List<PixelPoint>, po: List<Int>): List<CurvePoint> {
        val n = pt.size
        val m = po.size
        val sums = Sums(pt)
        val lines = po.indices.map { i ->
            val j = Math.floorMod(po[(i + 1) % m] - po[i], n) + po[i]
            sums.pointSlope(po[i], j)
        }
        val q = lines.map { (center, direction) ->
            val d = direction.x * direction.x + direction.y * direction.y
            val matrix = Array(3) { DoubleArray(3) }
            if (d != 0.0) {
                val v = doubleArrayOf(direction.y, -direction.x, 0.0)
                v[2] = -v[1] * center.y - v[0] * center.x
                for (l in 0..2) for (k in 0..2) matrix[l][k] = v[l] * v[k] / d
            }
            matrix
        }
        return po.indices.map { i ->
            val s = CurvePoint((pt[po[i]].x - pt[0].x).toDouble(), (pt[po[i]].y - pt[0].y).toDouble())
            val j = Math.floorMod(i - 1, m)
            val matrix = Array(3) { l -> DoubleArray(3) { k -> q[j][l][k] + q[i][l][k] } }
            var w: CurvePoint
            while (true) {
                val det = matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0]
                if (det != 0.0) {
                    w = CurvePoint(
                        (-matrix[0][2] * matrix[1][1] + matrix[1][2] * matrix[0][1]) / det,
                        (matrix[0][2] * matrix[1][0] - matrix[1][2] * matrix[0][0]) / det,
                    )
                    break
                }
                val v = when {
                    matrix[0][0] > matrix[1][1] -> doubleArrayOf(-matrix[0][1], matrix[0][0], 0.0)
                    matrix[1][1] != 0.0 -> doubleArrayOf(-matrix[1][1], matrix[1][0], 0.0)
                    else -> doubleArrayOf(1.0, 0.0, 0.0)
                }
                val d = v[0] * v[0] + v[1] * v[1]
                v[2] = -v[1] * s.y - v[0] * s.x
                for (l in 0..2) for (k in 0..2) matrix[l][k] += v[l] * v[k] / d
            }
            val fitted = if (abs(w.x - s.x) <= 0.5 && abs(w.y - s.y) <= 0.5) w else {
                var minimum = quadform(matrix, s)
                var best = s
                fun consider(candidate: CurvePoint, within: Boolean = true) {
                    val value = quadform(matrix, candidate)
                    if (within && value < minimum) {
                        minimum = value
                        best = candidate
                    }
                }
                if (matrix[0][0] != 0.0) for (z in 0..1) {
                    val y = s.y - 0.5 + z
                    val x = -(matrix[0][1] * y + matrix[0][2]) / matrix[0][0]
                    consider(CurvePoint(x, y), abs(x - s.x) <= 0.5)
                }
                if (matrix[1][1] != 0.0) for (z in 0..1) {
                    val x = s.x - 0.5 + z
                    val y = -(matrix[1][0] * x + matrix[1][2]) / matrix[1][1]
                    consider(CurvePoint(x, y), abs(y - s.y) <= 0.5)
                }
                for (l in 0..1) for (k in 0..1) consider(CurvePoint(s.x - 0.5 + l, s.y - 0.5 + k))
                best
            }
            CurvePoint(fitted.x + pt[0].x, fitted.y + pt[0].y)
        }
    }

    private class Sums(private val pt: List<PixelPoint>) {
        private val sums = Array(pt.size + 1) { DoubleArray(5) }
        init {
            for (i in pt.indices) {
                val x = (pt[i].x - pt[0].x).toDouble()
                val y = (pt[i].y - pt[0].y).toDouble()
                val values = doubleArrayOf(x, y, x * x, x * y, y * y)
                for (axis in values.indices) sums[i + 1][axis] = sums[i][axis] + values[axis]
            }
        }
        fun pointSlope(start: Int, end: Int): Pair<CurvePoint, CurvePoint> {
            val n = pt.size
            val i = Math.floorMod(start, n)
            val j = Math.floorMod(end, n)
            val r = Math.floorDiv(end, n) - Math.floorDiv(start, n)
            val values = DoubleArray(5) { axis -> sums[j + 1][axis] - sums[i][axis] + r * sums[n][axis] }
            val (x, y, x2, xy, y2) = values
            val count = (j + 1 - i + r * n).toDouble()
            val center = CurvePoint(x / count, y / count)
            var a = (x2 - x * x / count) / count
            val b = (xy - x * y / count) / count
            var c = (y2 - y * y / count) / count
            val lambda2 = (a + c + sqrt((a - c) * (a - c) + 4 * b * b)) / 2
            a -= lambda2
            c -= lambda2
            val direction = if (abs(a) >= abs(c)) {
                val length = sqrt(a * a + b * b)
                if (length == 0.0) CurvePoint(0.0, 0.0) else CurvePoint(-b / length, a / length)
            } else {
                val length = sqrt(c * c + b * b)
                if (length == 0.0) CurvePoint(0.0, 0.0) else CurvePoint(-c / length, b / length)
            }
            return center to direction
        }
    }

    private fun smooth(vertices: List<CurvePoint>): List<CurveSegment> {
        val m = vertices.size
        val segments = arrayOfNulls<CurveSegment>(m)
        for (i in 0 until m) {
            val j = (i + 1) % m
            val k = (i + 2) % m
            val p0 = vertices[i]
            val p1 = vertices[j]
            val p2 = vertices[k]
            val p4 = interval(0.5, p2, p1)
            val rx = -(p2.y - p0.y).sign
            val ry = (p2.x - p0.x).sign
            val denom = ry * (p2.x - p0.x) - rx * (p2.y - p0.y)
            var alpha = if (denom != 0.0) {
                val para = (p1.x - p0.x) * (p2.y - p0.y) - (p2.x - p0.x) * (p1.y - p0.y)
                val dd = abs(para / denom)
                (if (dd > 1) 1 - 1 / dd else 0.0) / 0.75
            } else 4 / 3.0
            val alpha0 = alpha
            if (alpha >= 1.0) {
                segments[j] = CurveSegment(2, listOf(CurvePoint(0.0, 0.0), p1, p4), alpha, alpha0)
            } else {
                if (alpha < 0.55) alpha = 0.55 else if (alpha > 1) alpha = 1.0
                segments[j] = CurveSegment(1, listOf(
                    interval(0.5 + 0.5 * alpha, p0, p1),
                    interval(0.5 + 0.5 * alpha, p2, p1), p4,
                ), alpha, alpha0)
            }
        }
        return segments.map { requireNotNull(it) }
    }

    private fun interval(lambda: Double, a: CurvePoint, b: CurvePoint) =
        CurvePoint(a.x + lambda * (b.x - a.x), a.y + lambda * (b.y - a.y))

    private fun quadform(matrix: Array<DoubleArray>, point: CurvePoint): Double {
        val v = doubleArrayOf(point.x, point.y, 1.0)
        var sum = 0.0
        for (i in 0..2) for (j in 0..2) sum += v[i] * matrix[i][j] * v[j]
        return sum
    }
}
