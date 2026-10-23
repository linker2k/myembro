// SPDX-License-Identifier: GPL-2.0-or-later
// Ported from Potrace src/trace.c (opti_penalty, opticurve, tangent and geometry helpers),
// src/auxiliary.h@886392d351417d6753aff61de08e294d1506e502 (GPL-2.0-or-later),
// Copyright (C) 2001-2019 Peter Selinger. Kotlin translation, 2026 myembro contributors.
// See resources/licenses/potrace/COPYING.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/** Upstream stage 5 with default opttolerance=0.2 pixels; no mobile heuristics or stitch generation. */
internal object CurveOptimizer {
    private data class Candidate(
        val penalty: Double, val control0: CurvePoint, val control1: CurvePoint,
        val t: Double, val s: Double, val alpha: Double,
    )

    fun optimize(curve: FittedCurve): FittedCurve {
        val v = curve.vertices
        val segments = curve.segments
        val m = v.size
        require(m > 0 && segments.size == m)
        val conv = IntArray(m) { i ->
            if (segments[i].tag == 1) para(v[mod(i - 1, m)], v[i], v[(i + 1) % m]).sign.toInt() else 0
        }
        val areas = DoubleArray(m + 1)
        var area = 0.0
        for (i in 0 until m) {
            val next = (i + 1) % m
            if (segments[next].tag == 1) {
                val alpha = segments[next].alpha
                area += 0.3 * alpha * (4 - alpha) * para(segments[i].controls[2], v[next], segments[next].controls[2]) / 2
                area += para(v[0], segments[i].controls[2], segments[next].controls[2]) / 2
            }
            areas[i + 1] = area
        }
        val previous = IntArray(m + 1)
        val penalties = DoubleArray(m + 1)
        val lengths = IntArray(m + 1)
        val candidates = arrayOfNulls<Candidate>(m + 1)
        previous[0] = -1
        for (j in 1..m) {
            previous[j] = j - 1
            penalties[j] = penalties[j - 1]
            lengths[j] = lengths[j - 1] + 1
            for (i in j - 2 downTo 0) {
                val candidate = penalty(curve, i, j % m, conv, areas) ?: break
                if (lengths[j] > lengths[i] + 1 ||
                    (lengths[j] == lengths[i] + 1 && penalties[j] > penalties[i] + candidate.penalty)) {
                    previous[j] = i
                    penalties[j] = penalties[i] + candidate.penalty
                    lengths[j] = lengths[i] + 1
                    candidates[j] = candidate
                }
            }
        }
        val count = lengths[m]
        val vertices = arrayOfNulls<CurvePoint>(count)
        val result = arrayOfNulls<CurveSegment>(count)
        val s = DoubleArray(count)
        val t = DoubleArray(count)
        var j = m
        for (i in count - 1 downTo 0) {
            val index = j % m
            if (previous[j] == j - 1) {
                vertices[i] = v[index]
                result[i] = segments[index]
                s[i] = 1.0
                t[i] = 1.0
            } else {
                val candidate = requireNotNull(candidates[j])
                result[i] = CurveSegment(1, listOf(candidate.control0, candidate.control1, segments[index].controls[2]),
                    candidate.alpha, candidate.alpha)
                vertices[i] = interval(candidate.s, segments[index].controls[2], v[index])
                s[i] = candidate.s
                t[i] = candidate.t
            }
            j = previous[j]
        }
        return FittedCurve(vertices.map { requireNotNull(it) }, result.mapIndexed { i, segment ->
            requireNotNull(segment).copy(beta = s[i] / (s[i] + t[(i + 1) % count]))
        })
    }

    private fun penalty(curve: FittedCurve, i: Int, j: Int, convexity: IntArray, areas: DoubleArray): Candidate? {
        val v = curve.vertices
        val segments = curve.segments
        val m = v.size
        if (i == j) return null
        val i1 = (i + 1) % m
        val conv = convexity[i1]
        if (conv == 0) return null
        val initialDistance = distance(v[i], v[i1])
        var k = i1
        while (k != j) {
            val k1 = (k + 1) % m
            val k2 = (k + 2) % m
            if (convexity[k1] != conv) return null
            if (cross(v[i], v[i1], v[k1], v[k2]).sign.toInt() != conv) return null
            if (dot1(v[i], v[i1], v[k1], v[k2]) <
                initialDistance * distance(v[k1], v[k2]) * -0.999847695156) return null // upstream COS179
            k = k1
        }
        val p0 = segments[i].controls[2]
        var p1 = v[i1]
        var p2 = v[j]
        val p3 = segments[j].controls[2]
        var area = areas[j] - areas[i]
        area -= para(v[0], segments[i].controls[2], segments[j].controls[2]) / 2
        if (i >= j) area += areas[m]
        val a1 = para(p0, p1, p2)
        val a2 = para(p0, p1, p3)
        val a3 = para(p0, p2, p3)
        val a4 = a1 + a3 - a2
        if (a2 == a1) return null
        val t = a3 / (a3 - a4)
        val s = a2 / (a2 - a1)
        val a = a2 * t / 2.0
        if (a == 0.0) return null
        val ratio = area / a
        val alpha = 2 - sqrt(4 - ratio / 0.3)
        p1 = interval(t * alpha, p0, p1)
        p2 = interval(s * alpha, p3, p2)
        var penalty = 0.0
        k = i1
        while (k != j) {
            val k1 = (k + 1) % m
            val tangent = tangent(p0, p1, p2, p3, v[k], v[k1])
            if (tangent < -0.5) return null
            val point = bezier(tangent, p0, p1, p2, p3)
            val d = distance(v[k], v[k1])
            if (d == 0.0) return null
            val d1 = para(v[k], v[k1], point) / d
            if (abs(d1) > 0.2) return null
            if (dot(v[k], v[k1], point) < 0 || dot(v[k1], v[k], point) < 0) return null
            penalty += d1 * d1
            k = k1
        }
        k = i
        while (k != j) {
            val k1 = (k + 1) % m
            val c0 = segments[k].controls[2]
            val c1 = segments[k1].controls[2]
            val tangent = tangent(p0, p1, p2, p3, c0, c1)
            if (tangent < -0.5) return null
            val point = bezier(tangent, p0, p1, p2, p3)
            val d = distance(c0, c1)
            if (d == 0.0) return null
            var d1 = para(c0, c1, point) / d
            var d2 = para(c0, c1, v[k1]) / d
            d2 *= 0.75 * segments[k1].alpha
            if (d2 < 0) {
                d1 = -d1
                d2 = -d2
            }
            if (d1 < d2 - 0.2) return null
            if (d1 < d2) penalty += (d1 - d2) * (d1 - d2)
            k = k1
        }
        return Candidate(penalty, p1, p2, t, s, alpha)
    }

    private fun tangent(p0: CurvePoint, p1: CurvePoint, p2: CurvePoint, p3: CurvePoint, q0: CurvePoint, q1: CurvePoint): Double {
        val aa = cross(p0, p1, q0, q1)
        val bb = cross(p1, p2, q0, q1)
        val cc = cross(p2, p3, q0, q1)
        val a = aa - 2 * bb + cc
        val b = -2 * aa + 2 * bb
        val c = aa
        val d = b * b - 4 * a * c
        if (a == 0.0 || d < 0) return -1.0
        val root = sqrt(d)
        val r1 = (-b + root) / (2 * a)
        val r2 = (-b - root) / (2 * a)
        return when {
            r1 >= 0 && r1 <= 1 -> r1
            r2 >= 0 && r2 <= 1 -> r2
            else -> -1.0
        }
    }
    private fun bezier(t: Double, p0: CurvePoint, p1: CurvePoint, p2: CurvePoint, p3: CurvePoint): CurvePoint {
        val s = 1 - t
        fun coordinate(a: Double, b: Double, c: Double, d: Double) =
            s * s * s * a + 3 * (s * s * t) * b + 3 * (t * t * s) * c + t * t * t * d
        return CurvePoint(coordinate(p0.x, p1.x, p2.x, p3.x), coordinate(p0.y, p1.y, p2.y, p3.y))
    }
    private fun mod(a: Int, n: Int) = Math.floorMod(a, n)
    private fun interval(lambda: Double, a: CurvePoint, b: CurvePoint) =
        CurvePoint(a.x + lambda * (b.x - a.x), a.y + lambda * (b.y - a.y))
    private fun cross(p0: CurvePoint, p1: CurvePoint, p2: CurvePoint, p3: CurvePoint) =
        (p1.x - p0.x) * (p3.y - p2.y) - (p1.y - p0.y) * (p3.x - p2.x)
    private fun para(p0: CurvePoint, p1: CurvePoint, p2: CurvePoint) = cross(p0, p1, p0, p2)
    private fun dot(p0: CurvePoint, p1: CurvePoint, p2: CurvePoint) = dot1(p0, p1, p0, p2)
    private fun dot1(p0: CurvePoint, p1: CurvePoint, p2: CurvePoint, p3: CurvePoint) =
        (p1.x - p0.x) * (p3.x - p2.x) + (p1.y - p0.y) * (p3.y - p2.y)
    private fun distance(a: CurvePoint, b: CurvePoint): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
