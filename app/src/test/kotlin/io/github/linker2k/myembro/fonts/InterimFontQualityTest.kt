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
    fun joinedStrokesAreNotGaps() {
        // "T": the stem ends on the bar's middle; "A"-like apex where two strokes start together.
        val t = listOf(listOf(0.0 to 0.0, 8.0 to 0.0), listOf(4.0 to 0.0, 4.0 to 10.0))
        val apex = listOf(listOf(5.0 to 0.0, 0.0 to 10.0), listOf(5.0 to 0.0, 10.0 to 10.0))
        val r = q.analyze(listOf(t, apex), 10.0, outline = false)
        assertEquals(QualityGrade.GOOD, r.grade, "reasons: ${r.reasons}, min ${r.minStrokeMm}")
    }

    @Test
    fun realHersheySansIsGoodAtTenMillimetres() {
        // Glyph paths from Hershey Sans 1-stroke (oskay/svg-fonts @ 8c71f2d9), font units, y up.
        val paths = listOf(
            "M 378 662 L 126 0 M 378 662 L 630 0 M 220 220 L 536 220",
            "M 220 662 L 220 0 M 220 662 L 504 662 L 598 630 L 630 598 L 662 536 L 662 472 L 630 410 L 598 378 L 504 346 " +
                "M 220 346 L 504 346 L 598 315 L 630 284 L 662 220 L 662 126 L 630 63 L 598 31.5 L 504 0 L 220 0",
            "M 662 504 L 630 567 L 567 630 L 504 662 L 378 662 L 315 630 L 252 567 L 220 504 L 189 410 L 189 252 L 220 158 " +
                "L 252 94.5 L 315 31.5 L 378 0 L 504 0 L 567 31.5 L 630 94.5 L 662 158",
        )
        val scale = 10.0 / 662 // cap height → 10 mm
        val glyphs = paths.map { d ->
            io.github.linker2k.myembro.engine.lettering.SvgPath.toPolylines(d, 1.0).map { s -> s.map { it.x * scale to -it.y * scale } }
        }
        val r = q.analyze(glyphs, 10.0, outline = false)
        assertEquals(QualityGrade.GOOD, r.grade, "reasons: ${r.reasons}, min ${r.minStrokeMm}")
        assertEquals(0, r.tightSpots)
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
