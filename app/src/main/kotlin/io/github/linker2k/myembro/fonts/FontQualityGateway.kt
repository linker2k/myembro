// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.fonts

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * App-side wrapper around the shared lettering contract (engine/.../lettering/FontQuality.kt,
 * implemented by embro-letter). Until that lands, [InterimFontQuality] provides the same
 * signatures; after the merge, point [FontQualityGateway.analyzer] at an adapter over the engine
 * objects and delete the interim code. Coordinates are mm, normalized to the requested height.
 */

enum class QualityGrade { GOOD, CAUTION, UNSUITABLE }

/** Mirrors the engine's FontQualityReport field for field. */
data class QualityReport(
    val minStrokeMm: Double,
    val recommendedMinHeightMm: Double,
    val stitchCount: Int,
    val jumpCount: Int,
    val tightSpots: Int,
    val grade: QualityGrade,
    /** Reason keys (see [QualityReasons]); unknown keys are shown verbatim. */
    val reasons: List<String>,
)

object QualityReasons {
    const val THIN_STROKES = "thin_strokes"
    const val TIGHT_SPOTS = "tight_spots"
    const val MANY_JUMPS = "many_jumps"
    const val TINY_DETAILS = "tiny_details"
    /** Added by the app when the sample has characters the font lacks. */
    const val MISSING_HANGUL = "missing_hangul"
    const val MISSING_LATIN = "missing_latin"
}

interface FontQualityAnalyzer {
    fun analyze(glyphContours: List<List<List<Pair<Double, Double>>>>, heightMm: Double, outline: Boolean): QualityReport
    fun simplify(contours: List<List<Pair<Double, Double>>>, toleranceMm: Double, minSegmentMm: Double): List<List<Pair<Double, Double>>>
}

object FontQualityGateway {
    /** Swap for the engine-backed adapter once FontQuality/ContourSimplifier are merged. */
    val analyzer: FontQualityAnalyzer = InterimFontQuality
}

/**
 * Stand-in heuristics. Running stitch along both edges of an outline needs the edges at least
 * [OUTLINE_GOOD_MM] apart to stay readable; single-line strokes need [LINE_GOOD_MM] between
 * neighbouring strokes. Thickness is measured as the distance between contour samples that are far
 * apart along the contour (so corners do not count as thin).
 */
internal object InterimFontQuality : FontQualityAnalyzer {
    const val OUTLINE_GOOD_MM = 1.5
    const val OUTLINE_CAUTION_MM = 0.9
    const val LINE_GOOD_MM = 1.0
    const val LINE_CAUTION_MM = 0.6
    private const val STEP_MM = 0.4
    private const val STITCH_MM = 2.5
    private const val MIN_RECOMMENDED_MM = 4.0
    /** A stroke end this close to another stroke is joined to it. */
    private const val JUNCTION_MM = 0.25

    private class Sample(val x: Double, val y: Double, val contour: Int, val arc: Double)

    override fun analyze(
        glyphContours: List<List<List<Pair<Double, Double>>>>, heightMm: Double, outline: Boolean,
    ): QualityReport {
        val good = if (outline) OUTLINE_GOOD_MM else LINE_GOOD_MM
        val caution = if (outline) OUTLINE_CAUTION_MM else LINE_CAUTION_MM
        // Glyphs are measured independently: each arrives at its own origin, so letters overlap in space.
        val glyphOf = ArrayList<Int>()
        val contours = ArrayList<List<Pair<Double, Double>>>()
        glyphContours.forEachIndexed { gi, glyph -> glyph.filter { it.size >= 2 }.forEach { contours += it; glyphOf += gi } }
        if (contours.isEmpty()) {
            return QualityReport(0.0, heightMm, 0, 0, 0, QualityGrade.UNSUITABLE, listOf(QualityReasons.TINY_DETAILS))
        }

        var stitches = 0
        var tiny = 0
        val samples = ArrayList<Sample>()
        val lengths = DoubleArray(contours.size)
        contours.forEachIndexed { ci, c ->
            var arc = 0.0
            samples += Sample(c[0].first, c[0].second, ci, 0.0)
            for (k in 1 until c.size) {
                val (ax, ay) = c[k - 1]
                val (bx, by) = c[k]
                val len = hypot(bx - ax, by - ay)
                stitches += max(1, ceil(len / STITCH_MM).toInt())
                val n = max(1, ceil(len / STEP_MM).toInt())
                for (s in 1..n) {
                    val t = s.toDouble() / n
                    samples += Sample(ax + (bx - ax) * t, ay + (by - ay) * t, ci, arc + len * t)
                }
                arc += len
            }
            lengths[ci] = arc
            if (arc < 1.0) tiny++
        }

        // Spatial hash so only nearby samples are compared.
        val cell = good * 2
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(ix: Int, iy: Int) = (ix.toLong() shl 32) xor (iy.toLong() and 0xffffffffL)
        samples.forEachIndexed { i, s -> grid.getOrPut(key(floor(s.x / cell).toInt(), floor(s.y / cell).toInt())) { ArrayList() } += i }

        // Junctions: an open stroke ending on another stroke (Hershey "A" apex, "T" bar). Points near a
        // junction are close because the strokes are joined, like the two arms of a corner.
        val junctions = HashMap<Long, MutableList<Pair<Double, Double>>>() // (contour pair) -> (arc on lower, arc on higher)
        fun pairKey(a: Int, b: Int) = (min(a, b).toLong() shl 32) or max(a, b).toLong()
        contours.forEachIndexed { ci, c ->
            if (c.first() == c.last()) return@forEachIndexed
            for ((ex, ey, earc) in listOf(Triple(c.first().first, c.first().second, 0.0), Triple(c.last().first, c.last().second, lengths[ci]))) {
                val ix = floor(ex / cell).toInt()
                val iy = floor(ey / cell).toInt()
                val nearest = HashMap<Int, Pair<Double, Double>>() // other contour -> (distance, arc)
                for (dx in -1..1) for (dy in -1..1) for (j in grid[key(ix + dx, iy + dy)].orEmpty()) {
                    val s = samples[j]
                    if (s.contour == ci || glyphOf[s.contour] != glyphOf[ci]) continue
                    val d = hypot(s.x - ex, s.y - ey)
                    if (d <= JUNCTION_MM && d < (nearest[s.contour]?.first ?: Double.MAX_VALUE)) nearest[s.contour] = d to s.arc
                }
                for ((other, hit) in nearest) {
                    junctions.getOrPut(pairKey(ci, other)) { ArrayList() } += if (ci < other) earc to hit.second else hit.second to earc
                }
            }
        }

        var minDist = Double.MAX_VALUE
        val tight = BooleanArray(samples.size)
        samples.forEachIndexed { i, a ->
            val ix = floor(a.x / cell).toInt()
            val iy = floor(a.y / cell).toInt()
            var best = Double.MAX_VALUE
            for (dx in -1..1) for (dy in -1..1) {
                val bucket = grid[key(ix + dx, iy + dy)] ?: continue
                for (j in bucket) {
                    if (j == i) continue
                    val b = samples[j]
                    if (glyphOf[b.contour] != glyphOf[a.contour]) continue
                    val d = hypot(a.x - b.x, a.y - b.y)
                    if (d >= best) continue
                    if (a.contour == b.contour) {
                        val along = Math.abs(a.arc - b.arc)
                        val closed = contours[a.contour].first() == contours[a.contour].last()
                        val arcDist = if (closed) min(along, lengths[a.contour] - along) else along
                        // Neighbours along the same line, including both arms of a corner.
                        if (arcDist < 4 * d + STEP_MM * 1.5) continue
                    } else {
                        val joins = junctions[pairKey(a.contour, b.contour)]
                        if (joins != null) {
                            val (lo, hi) = if (a.contour < b.contour) a to b else b to a
                            // Path length through the junction, judged like a corner.
                            if (joins.any { (arcLo, arcHi) -> Math.abs(lo.arc - arcLo) + Math.abs(hi.arc - arcHi) < 4 * d + STEP_MM * 1.5 }) continue
                        }
                    }
                    best = d
                }
            }
            if (best < minDist) minDist = best
            if (best < caution) tight[i] = true
        }
        // Count runs of consecutive tight samples as spots.
        var spots = 0
        for (i in samples.indices) if (tight[i] && (i == 0 || !tight[i - 1] || samples[i - 1].contour != samples[i].contour)) spots++

        val minStroke = if (minDist == Double.MAX_VALUE) heightMm else minDist
        val recommended = max(MIN_RECOMMENDED_MM, ceil(heightMm * good / max(minStroke, 1e-6) * 2) / 2)
        val jumps = contours.size - 1
        val reasons = ArrayList<String>()
        var grade = when {
            minStroke >= good -> QualityGrade.GOOD
            minStroke >= caution -> QualityGrade.CAUTION
            else -> QualityGrade.UNSUITABLE
        }
        if (grade != QualityGrade.GOOD) reasons += QualityReasons.THIN_STROKES
        if (spots > 0) reasons += QualityReasons.TIGHT_SPOTS
        if (contours.size.toDouble() / glyphContours.count { it.isNotEmpty() }.coerceAtLeast(1) > 8) {
            reasons += QualityReasons.MANY_JUMPS
            if (grade == QualityGrade.GOOD) grade = QualityGrade.CAUTION
        }
        if (tiny > 0) {
            reasons += QualityReasons.TINY_DETAILS
            if (grade == QualityGrade.GOOD) grade = QualityGrade.CAUTION
        }
        return QualityReport(minStroke, recommended, stitches, jumps, spots, grade, reasons)
    }

    /** Douglas–Peucker, then drop points closer than [minSegmentMm] (endpoints kept). */
    override fun simplify(
        contours: List<List<Pair<Double, Double>>>, toleranceMm: Double, minSegmentMm: Double,
    ): List<List<Pair<Double, Double>>> = contours.map { c ->
        if (c.size < 3) return@map c
        val keep = BooleanArray(c.size).also { it[0] = true; it[c.lastIndex] = true }
        fun dp(from: Int, to: Int) {
            if (to <= from + 1) return
            val (ax, ay) = c[from]
            val (bx, by) = c[to]
            val len = hypot(bx - ax, by - ay)
            var worst = -1.0
            var at = -1
            for (k in from + 1 until to) {
                val (px, py) = c[k]
                val d = if (len == 0.0) hypot(px - ax, py - ay) else Math.abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / len
                if (d > worst) { worst = d; at = k }
            }
            if (worst > toleranceMm) { keep[at] = true; dp(from, at); dp(at, to) }
        }
        dp(0, c.lastIndex)
        val out = ArrayList<Pair<Double, Double>>()
        for (k in c.indices) {
            if (!keep[k]) continue
            val p = c[k]
            val last = out.lastOrNull()
            if (last != null && k != c.lastIndex && hypot(p.first - last.first, p.second - last.second) < minSegmentMm) continue
            out += p
        }
        out
    }
}
