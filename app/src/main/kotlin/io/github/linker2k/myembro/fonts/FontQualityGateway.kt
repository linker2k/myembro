// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.fonts

import io.github.linker2k.myembro.engine.lettering.FontQuality
import io.github.linker2k.myembro.engine.lettering.FontQualityReport
import io.github.linker2k.myembro.engine.lettering.Grade
import io.github.linker2k.myembro.engine.lettering.LetteringEngine
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/*
 * App-side wrapper around the engine's FontQuality (docs/lettering.md, "임의 폰트 품질 분석 API").
 * Coordinates are mm, normalized to the requested height; one inner list per glyph.
 */

typealias QualityGrade = Grade
typealias QualityReport = FontQualityReport

/** Reason keys the app adds itself; the engine's reasons are display sentences shown as-is. */
object QualityReasons {
    const val MISSING_HANGUL = "missing_hangul"
    const val MISSING_LATIN = "missing_latin"
    /** Strokes touch or overlap, so a larger height does not help. */
    const val NOT_SCALABLE = "not_scalable"
}

object FontQualityGateway {
    /**
     * Strokes closer than this fraction of the letter height touch. Font data has small
     * near-misses (Hershey "A": the bar stops 9/662 short of the legs) that keep their
     * proportion when the text is scaled, so the tolerance scales too.
     */
    const val CONTACT_FRACTION = 0.025
    /**
     * Stroke parts closer than this fraction of the letter height to a contact are left out of
     * the gap measurement. Scaling with the height keeps the result proportional to the height,
     * which the engine's recommended height assumes: two legs meeting at an angle, or curves
     * meeting tangentially, then measure the same at every size. At 10 mm this is 2 mm, where
     * the legs of a 53° apex are still 1.8 mm apart.
     */
    const val CLEARANCE_FRACTION = 0.2
    private const val STEP_MM = 0.1

    /**
     * Outline fonts go to [FontQuality.analyze] unchanged. Single-line strokes that cross, end on
     * or retrace another stroke are drawn that way on purpose, but the engine measures them as a
     * 0 mm gap (always unsuitable). So stroke parts near those contacts are cut away before
     * measuring, while stitch and jump counts still come from the uncut strokes.
     */
    fun analyze(glyphContours: List<List<List<Pair<Double, Double>>>>, heightMm: Double, outline: Boolean): QualityReport =
        capRecommendation(measure(glyphContours, heightMm, outline))

    /**
     * Above this the engine's recommended height fits no hoop: touching or overlapping contours
     * (common in handwriting fonts) measure as a few micrometres, and "make it 6 m tall" is
     * reported the way the engine reports an exact 0 mm gap: scaling alone cannot fix it.
     */
    const val MAX_USEFUL_HEIGHT_MM = 300.0

    private fun capRecommendation(report: QualityReport): QualityReport {
        if (!report.recommendedMinHeightMm.isFinite() || report.recommendedMinHeightMm <= MAX_USEFUL_HEIGHT_MM) return report
        // Replaces the engine's "글자 높이를 …mm 이상으로 키워 보세요." sentence.
        val reasons = report.reasons.filterNot { "글자 높이를" in it && "키워 보세요" in it } + QualityReasons.NOT_SCALABLE
        return report.copy(recommendedMinHeightMm = Double.POSITIVE_INFINITY, reasons = reasons)
    }

    private fun measure(glyphContours: List<List<List<Pair<Double, Double>>>>, heightMm: Double, outline: Boolean): QualityReport {
        if (outline) return FontQuality.analyze(glyphContours, heightMm, outline = true)
        val drawable = glyphContours.map { glyph -> glyph.filter { it.size >= 2 } }.filter { it.isNotEmpty() }
        if (drawable.isEmpty()) return FontQuality.analyze(glyphContours, heightMm, outline = false)
        var stitches = 0
        var jumps = 0
        for (glyph in drawable) {
            val pattern = LetteringEngine.fromContours(glyph, heightMm, sourceHeight = heightMm)
            stitches += pattern.stitchCount
            jumps += pattern.jumpCount
        }
        val trimmed = drawable.map { trimContacts(it, heightMm * CONTACT_FRACTION, heightMm * CLEARANCE_FRACTION) }
        val report = FontQuality.analyze(trimmed, heightMm, outline = false)
        return report.copy(stitchCount = stitches, jumpCount = jumps)
    }

    /**
     * Splits each stroke of one glyph around the points where another stroke comes within
     * [contactMm] of it (crossings, joins, retraced runs), keeping only parts at least
     * [clearanceMm] away from those points.
     */
    internal fun trimContacts(
        strokes: List<List<Pair<Double, Double>>>, contactMm: Double, clearanceMm: Double,
    ): List<List<Pair<Double, Double>>> {
        val dense = strokes.map(::densify)
        val contacts = List(strokes.size) { mutableListOf<Pair<Double, Double>>() }
        for (a in strokes.indices) for (b in strokes.indices) {
            if (a == b) continue
            val segments = strokes[b].zipWithNext()
            dense[a].filterTo(contacts[a]) { p -> segments.any { (s, e) -> segmentDistance(p, s, e) < contactMm } }
        }
        return strokes.indices.flatMap { i ->
            var near = contacts[i] + contacts.indices.filter { it != i }.flatMap { j -> contacts[j] }.filter { p ->
                dense[i].any { hypot(it.first - p.first, it.second - p.second) < contactMm }
            }
            if (near.isEmpty()) return@flatMap listOf(strokes[i])
            // The engine never compares a stroke with itself, so its pieces must not touch either:
            // where they do (a retraced stem, a bowl folding back), that is a contact too.
            var runs = cut(dense[i], near, contactMm, clearanceMm)
            repeat(MAX_PASSES) {
                val touching = runs.withIndex().flatMap { (r, run) ->
                    run.filter { p -> runs.withIndex().any { (o, other) -> o != r && other.any { hypot(it.first - p.first, it.second - p.second) < contactMm } } }
                }
                if (touching.isEmpty()) return@flatMap runs
                near += touching
                runs = cut(dense[i], near, contactMm, clearanceMm)
            }
            runs
        }
    }

    private const val MAX_PASSES = 4

    /** Pieces of [dense] at least [clearanceMm] from every point of [near], with a closed loop kept whole. */
    private fun cut(
        dense: List<Pair<Double, Double>>, near: List<Pair<Double, Double>>, contactMm: Double, clearanceMm: Double,
    ): List<List<Pair<Double, Double>>> {
        val runs = mutableListOf<MutableList<Pair<Double, Double>>>()
        var current: MutableList<Pair<Double, Double>>? = null
        for (p in dense) {
            if (near.any { hypot(it.first - p.first, it.second - p.second) < clearanceMm }) {
                current = null
            } else {
                if (current == null) current = mutableListOf<Pair<Double, Double>>().also { runs += it }
                current += p
            }
        }
        val closed = hypot(dense.first().first - dense.last().first, dense.first().second - dense.last().second) < contactMm
        if (closed && runs.size > 1 && runs.first().first() == dense.first() && runs.last().last() == dense.last()) {
            runs.last() += runs.removeAt(0)
        }
        return runs.filter { it.size >= 2 }
    }

    private fun densify(stroke: List<Pair<Double, Double>>): List<Pair<Double, Double>> = buildList {
        add(stroke.first())
        for ((p, q) in stroke.zipWithNext()) {
            val n = max(1, ceil(hypot(q.first - p.first, q.second - p.second) / STEP_MM).toInt())
            for (k in 1..n) add(p.first + (q.first - p.first) * k / n to p.second + (q.second - p.second) * k / n)
        }
    }

    private fun segmentDistance(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.first - a.first) * dx + (p.second - a.second) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
    }
}
