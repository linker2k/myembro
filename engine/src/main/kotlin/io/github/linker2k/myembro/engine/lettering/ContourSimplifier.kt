// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import kotlin.math.hypot

/** Geometry in mm. Contours, open endpoints, and closed-loop seams remain separate. */
object ContourSimplifier {
    /**
     * Douglas-Peucker followed by short-edge merging. A short edge is merged only when
     * removing its vertex stays within [toleranceMm] of the replacement chord, so a
     * sharp corner can retain an edge shorter than [minSegmentMm]. Zero disables each pass.
     */
    fun simplify(
        contours: List<List<Pair<Double, Double>>>,
        toleranceMm: Double,
        minSegmentMm: Double,
    ): List<List<Pair<Double, Double>>> {
        require(toleranceMm.isFinite() && toleranceMm >= 0) { "toleranceMm must be finite and non-negative" }
        require(minSegmentMm.isFinite() && minSegmentMm >= 0) { "minSegmentMm must be finite and non-negative" }
        require(contours.all { path -> path.all { (x, y) -> x.isFinite() && y.isFinite() } }) {
            "Contour coordinates must be finite"
        }
        return contours.map { path ->
            val clean = path.filterIndexed { i, p -> i == 0 || p != path[i - 1] }
            val reduced = douglasPeucker(clean, toleranceMm)
            val merged = mergeShortEdges(reduced, toleranceMm, minSegmentMm)
            // A large tolerance must not turn a closed glyph or counter into a line.
            if (clean.size >= 4 && clean.first() == clean.last() && clean.distinct().size >= 3 &&
                merged.distinct().size < 3
            ) clean else merged
        }
    }

    private fun douglasPeucker(points: List<Pair<Double, Double>>, epsilon: Double): List<Pair<Double, Double>> {
        if (points.size <= 2 || epsilon == 0.0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val pending = ArrayDeque<Pair<Int, Int>>()
        pending.add(0 to points.lastIndex)
        while (pending.isNotEmpty()) {
            val (start, end) = pending.removeLast()
            var farthest = -1
            var distance = epsilon
            for (i in start + 1 until end) {
                val d = pointChordDistance(points[i], points[start], points[end])
                if (d > distance) { distance = d; farthest = i }
            }
            if (farthest >= 0) {
                keep[farthest] = true
                pending.add(start to farthest)
                pending.add(farthest to end)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun mergeShortEdges(
        points: List<Pair<Double, Double>>, tolerance: Double, minimum: Double,
    ): List<Pair<Double, Double>> {
        if (minimum == 0.0 || points.size <= 2) return points
        val result = ArrayList<Pair<Double, Double>>()
        for (point in points) {
            result += point
            while (result.size >= 3) {
                val a = result[result.size - 3]
                val b = result[result.size - 2]
                val c = result.last()
                if (distance(a, b) >= minimum && distance(b, c) >= minimum) break
                if (pointChordDistance(b, a, c) > tolerance) break
                result.removeAt(result.size - 2)
            }
        }
        return result
    }

    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>) = hypot(b.first - a.first, b.second - a.second)

    private fun pointChordDistance(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val length = hypot(dx, dy)
        if (length == 0.0) return distance(p, a)
        val ux = dx / length
        val uy = dy / length
        val along = ((p.first - a.first) * ux + (p.second - a.second) * uy).coerceIn(0.0, length)
        return hypot(p.first - a.first - along * ux, p.second - a.second - along * uy)
    }
}
