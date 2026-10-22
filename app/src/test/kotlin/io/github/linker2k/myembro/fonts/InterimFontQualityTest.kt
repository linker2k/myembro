// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.fonts

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterimFontQualityTest {
    private val q = InterimFontQuality

    /** Closed rectangle outline, like the edge of one vertical stroke. */
    private fun bar(width: Double, height: Double = 10.0, x: Double = 0.0) =
        listOf(x to 0.0, x + width to 0.0, x + width to height, x to height, x to 0.0)

    @Test
    fun thickOutlineIsGood() {
        val r = q.analyze(listOf(listOf(bar(2.0))), 10.0, outline = true)
        assertEquals(QualityGrade.GOOD, r.grade)
        assertEquals(2.0, r.minStrokeMm, 0.05)
        assertEquals(0, r.tightSpots)
        // 1.5 mm needed, 2.0 mm present at 10 mm → 7.5 mm.
        assertEquals(7.5, r.recommendedMinHeightMm)
    }

    @Test
    fun thinOutlineGradesAndRecommendsLargerHeight() {
        val caution = q.analyze(listOf(listOf(bar(1.0))), 10.0, outline = true)
        assertEquals(QualityGrade.CAUTION, caution.grade)
        assertTrue(QualityReasons.THIN_STROKES in caution.reasons)
        // 1.5 mm needed, 1.0 mm present at 10 mm → 15 mm.
        assertEquals(15.0, caution.recommendedMinHeightMm)

        val bad = q.analyze(listOf(listOf(bar(0.5))), 10.0, outline = true)
        assertEquals(QualityGrade.UNSUITABLE, bad.grade)
        assertTrue(bad.tightSpots > 0)
        assertEquals(30.0, bad.recommendedMinHeightMm)
    }

    @Test
    fun scalingTheSameGlyphMovesTheGrade() {
        val small = q.analyze(listOf(listOf(bar(1.0))), 10.0, outline = true)
        val large = q.analyze(listOf(listOf(bar(2.0, 20.0))), 20.0, outline = true)
        assertEquals(QualityGrade.CAUTION, small.grade)
        assertEquals(QualityGrade.GOOD, large.grade)
        assertEquals(small.recommendedMinHeightMm, large.recommendedMinHeightMm)
    }

    @Test
    fun sharpCornerIsNotAThinStroke() {
        // A single-line "V" with a 30° opening: its arms meet but are one stroke.
        val a = Math.toRadians(15.0)
        val v = listOf(-10 * sin(a) to -10 * cos(a), 0.0 to 0.0, 10 * sin(a) to -10 * cos(a))
        val r = q.analyze(listOf(listOf(v)), 10.0, outline = false)
        assertEquals(QualityGrade.GOOD, r.grade, "reasons: ${r.reasons}")
    }

    @Test
    fun closeParallelStrokesAreTight() {
        val lines = listOf(listOf(0.0 to 0.0, 10.0 to 0.0), listOf(0.0 to 0.4, 10.0 to 0.4))
        val r = q.analyze(listOf(lines), 10.0, outline = false)
        assertEquals(QualityGrade.UNSUITABLE, r.grade)
        assertEquals(0.4, r.minStrokeMm, 0.01)
        assertEquals(1, r.jumpCount)
    }

    @Test
    fun countsStitchesJumpsAndFragments() {
        val dots = (0 until 10).map { i -> listOf(i * 5.0 to 0.0, i * 5.0 + 0.3 to 0.0) }
        val r = q.analyze(listOf(dots), 10.0, outline = false)
        assertEquals(9, r.jumpCount)
        assertEquals(10, r.stitchCount)
        assertTrue(QualityReasons.MANY_JUMPS in r.reasons)
        assertTrue(QualityReasons.TINY_DETAILS in r.reasons)
        assertTrue(r.grade != QualityGrade.GOOD)
    }

    @Test
    fun emptyInputIsUnsuitable() {
        assertEquals(QualityGrade.UNSUITABLE, q.analyze(emptyList(), 10.0, outline = true).grade)
    }

    @Test
    fun simplifyDropsCollinearAndShortSegments() {
        val line = listOf(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.001, 3.0 to 0.0, 3.0 to 3.0)
        assertEquals(listOf(0.0 to 0.0, 3.0 to 0.0, 3.0 to 3.0), q.simplify(listOf(line), 0.05, 0.0).single())
        val jitter = listOf(0.0 to 0.0, 0.05 to 1.0, 0.0 to 2.0, 5.0 to 5.0)
        val simplified = q.simplify(listOf(jitter), 0.01, 2.5).single()
        assertEquals(0.0 to 0.0, simplified.first())
        assertEquals(5.0 to 5.0, simplified.last())
        assertEquals(2, simplified.size)
    }
}
