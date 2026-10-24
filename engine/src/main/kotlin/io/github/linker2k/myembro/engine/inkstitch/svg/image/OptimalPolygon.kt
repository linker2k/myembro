// SPDX-License-Identifier: GPL-2.0-or-later
// Ported from Potrace src/trace.c (calc_sums, calc_lon, penalty3, bestpolygon),
// src/auxiliary.h@886392d351417d6753aff61de08e294d1506e502 (GPL-2.0-or-later),
// Copyright (C) 2001-2019 Peter Selinger. Kotlin translation, 2026 myembro contributors.
// See resources/licenses/potrace/COPYING.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/** Upstream stages 1–2 only. Returned indices refer to the unchanged boundary; no vertex adjustment. */
internal object OptimalPolygon {
    fun indices(contour: BinaryContour): List<Int> {
        val pt = contour.points
        val n = pt.size
        require(n >= 4)
        require(pt.indices.all { i ->
            val d = pt[(i + 1) % n] - pt[i]
            abs(d.x) + abs(d.y) == 1
        }) { "Expected a closed unit-step pixel boundary" }
        val lon = longestStraightPaths(pt)
        val penalty = Penalty(pt)
        val pen = DoubleArray(n + 1)
        val prev = IntArray(n + 1)
        val clip0 = IntArray(n)
        val clip1 = IntArray(n + 1)
        val seg0 = IntArray(n + 1)
        val seg1 = IntArray(n + 1)
        for (i in 0 until n) {
            var c = mod(lon[mod(i - 1, n)] - 1, n)
            if (c == i) c = mod(i + 1, n)
            clip0[i] = if (c < i) n else c
        }
        var j = 1
        for (i in 0 until n) {
            while (j <= clip0[i]) clip1[j++] = i
        }
        var i = 0
        j = 0
        while (i < n) {
            seg0[j++] = i
            i = clip0[i]
        }
        seg0[j] = n
        val m = j
        i = n
        for (segment in m downTo 1) {
            seg1[segment] = i
            i = clip1[i]
        }
        for (segment in 1..m) {
            for (end in seg1[segment]..seg0[segment]) {
                var best = -1.0
                for (start in seg0[segment - 1] downTo clip1[end]) {
                    val candidate = penalty.edge(start, end) + pen[start]
                    if (best < 0 || candidate < best) {
                        prev[end] = start
                        best = candidate
                    }
                }
                pen[end] = best
            }
        }
        val polygon = IntArray(m)
        i = n
        j = m - 1
        while (i > 0) {
            i = prev[i]
            polygon[j--] = i
        }
        return polygon.toList()
    }

    private fun longestStraightPaths(pt: List<PixelPoint>): IntArray {
        val n = pt.size
        val pivk = IntArray(n)
        val nc = IntArray(n)
        val lon = IntArray(n)
        var k = 0
        for (i in n - 1 downTo 0) {
            if (pt[i].x != pt[k].x && pt[i].y != pt[k].y) k = i + 1
            nc[i] = k
        }
        for (i in n - 1 downTo 0) {
            val ct = IntArray(4)
            val initial = pt[mod(i + 1, n)] - pt[i]
            ct[(3 + 3 * initial.x + initial.y) / 2]++
            var constraint0 = PixelPoint(0, 0)
            var constraint1 = PixelPoint(0, 0)
            k = nc[i]
            var k1 = i
            var found = false
            while (true) {
                val direction = pt[k] - pt[k1]
                ct[(3 + 3 * direction.x.sign + direction.y.sign) / 2]++
                if (ct.all { it > 0 }) {
                    pivk[i] = k1
                    found = true
                    break
                }
                val cur = pt[k] - pt[i]
                if (cross(constraint0, cur) < 0 || cross(constraint1, cur) > 0) break
                if (abs(cur.x) > 1 || abs(cur.y) > 1) {
                    var off = PixelPoint(
                        cur.x + if (cur.y >= 0 && (cur.y > 0 || cur.x < 0)) 1 else -1,
                        cur.y + if (cur.x <= 0 && (cur.x < 0 || cur.y < 0)) 1 else -1,
                    )
                    if (cross(constraint0, off) >= 0) constraint0 = off
                    off = PixelPoint(
                        cur.x + if (cur.y <= 0 && (cur.y < 0 || cur.x < 0)) 1 else -1,
                        cur.y + if (cur.x >= 0 && (cur.x > 0 || cur.y < 0)) 1 else -1,
                    )
                    if (cross(constraint1, off) <= 0) constraint1 = off
                }
                k1 = k
                k = nc[k1]
                if (!cyclic(k, i, k1)) break
            }
            if (!found) {
                val direction = pt[k] - pt[k1]
                val dk = PixelPoint(direction.x.sign, direction.y.sign)
                val cur = pt[k1] - pt[i]
                val a = cross(constraint0, cur)
                val b = cross(constraint0, dk)
                val c = cross(constraint1, cur)
                val d = cross(constraint1, dk)
                var j = 10_000_000L // upstream INFTY
                if (b < 0) j = Math.floorDiv(a, -b)
                if (d > 0) j = minOf(j, Math.floorDiv(-c, d))
                pivk[i] = Math.floorMod(k1.toLong() + j, n.toLong()).toInt()
            }
        }
        var j = pivk[n - 1]
        lon[n - 1] = j
        for (i in n - 2 downTo 0) {
            if (cyclic(i + 1, pivk[i], j)) j = pivk[i]
            lon[i] = j
        }
        var i = n - 1
        while (cyclic(mod(i + 1, n), j, lon[i])) lon[i--] = j
        return lon
    }

    private class Penalty(private val pt: List<PixelPoint>) {
        // Prefix sums x, y, x², xy, y², translated to pt[0] just as calc_sums.
        private val sums = Array(pt.size + 1) { DoubleArray(5) }
        init {
            for (i in pt.indices) {
                val x = pt[i].x.toDouble() - pt[0].x
                val y = pt[i].y.toDouble() - pt[0].y
                val values = doubleArrayOf(x, y, x * x, x * y, y * y)
                for (axis in values.indices) sums[i + 1][axis] = sums[i][axis] + values[axis]
            }
        }
        fun edge(i: Int, end: Int): Double {
            val n = pt.size
            val rotated = end >= n
            val j = if (rotated) end - n else end
            val values = DoubleArray(5) { axis ->
                sums[j + 1][axis] - sums[i][axis] + if (rotated) sums[n][axis] else 0.0
            }
            val (x, y, x2, xy, y2) = values
            val count = (j + 1 - i + if (rotated) n else 0).toDouble()
            val px = (pt[i].x.toDouble() + pt[j].x) / 2 - pt[0].x
            val py = (pt[i].y.toDouble() + pt[j].y) / 2 - pt[0].y
            val ey = (pt[j].x - pt[i].x).toDouble()
            val ex = -(pt[j].y - pt[i].y).toDouble()
            val a = (x2 - 2 * x * px) / count + px * px
            val b = (xy - x * py - y * px) / count + px * py
            val c = (y2 - 2 * y * py) / count + py * py
            return sqrt(ex * ex * a + 2 * ex * ey * b + ey * ey * c)
        }
    }

    private fun mod(a: Int, n: Int) = Math.floorMod(a, n)
    private fun cyclic(a: Int, b: Int, c: Int) = if (a <= c) a <= b && b < c else a <= b || b < c
    private fun cross(a: PixelPoint, b: PixelPoint) = a.x.toLong() * b.y - a.y.toLong() * b.x
}
