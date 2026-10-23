// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontQualityTest {
    /** A 10 mm tall vertical bar, +y down. */
    private fun bar(width: Double, x: Double = 0.0, height: Double = 10.0) =
        listOf(x to -height, x + width to -height, x + width to 0.0, x to 0.0)

    private fun circle(cx: Double, cy: Double, r: Double, n: Int = 96) =
        (0..n).map { i -> val a = 2 * PI * (i % n) / n; cx + r * cos(a) to cy + r * sin(a) }

    @Test
    fun strokeWidthSetsTheRecommendedHeight() {
        for ((width, recommended) in listOf(2.0 to 5.0, 1.0 to 10.0, 0.5 to 20.0, 0.4 to 25.0)) {
            val r = FontQuality.analyze(listOf(listOf(bar(width))), 10.0, outline = true)
            assertEquals(width, r.inkP10Mm!!, 0.01)
            assertEquals(recommended, r.recommendedMinHeightMm, 1e-9)
            assertEquals(if (recommended > 5.0) QualityLimit.INK else QualityLimit.DETAIL, r.limitedBy)
        }
        val half = FontQuality.analyze(listOf(listOf(bar(0.8))), 10.0, outline = true)
        assertEquals(12.5, half.recommendedMinHeightMm) // 12.5 exactly; 0.8 mm → ×1.25
        assertEquals(Grade.CAUTION, half.grade)
        assertEquals(Grade.UNSUITABLE, FontQuality.analyze(listOf(listOf(bar(0.4))), 10.0, true).grade)
        assertEquals(Grade.GOOD, FontQuality.analyze(listOf(listOf(bar(1.2))), 10.0, true).grade)
    }

    @Test
    fun overlappingContoursAreMergedFirst() {
        // A 2 mm stem drawn as two overlapping 1.2 mm contours, as static instances of variable fonts do.
        val one = FontQuality.analyze(listOf(listOf(bar(2.0))), 10.0, true)
        val two = FontQuality.analyze(listOf(listOf(bar(1.2), bar(1.2, x = 0.8))), 10.0, true)
        assertEquals(2.0, two.inkP10Mm!!, 0.01)
        assertEquals(one.recommendedMinHeightMm, two.recommendedMinHeightMm)
        assertNull(two.gapP10Mm)
    }

    @Test
    fun taperedStrokeEndsDoNotDecide() {
        // A 2 mm stroke ending in a long brush tip: the tip is excluded, the stroke width counts.
        val tipped = listOf(0.0 to -10.0, 2.0 to -10.0, 2.0 to -3.0, 1.0 to 0.0, 0.0 to -3.0)
        val r = FontQuality.analyze(listOf(listOf(tipped)), 10.0, true)
        assertEquals(2.0, r.inkP10Mm!!, 0.05)
        assertEquals(5.0, r.recommendedMinHeightMm)
    }

    @Test
    fun narrowCountersAndSlitsAreGaps() {
        // Two 2 mm stems joined at the top: the slit between them must stay at least 0.6 mm.
        for ((slit, recommended) in listOf(0.3 to 20.0, 0.6 to 10.0, 1.5 to 5.0)) {
            val w = 2.0 + slit + 2.0
            val n = listOf(0.0 to -10.0, w to -10.0, w to 0.0, w - 2.0 to 0.0, w - 2.0 to -8.0, 2.0 to -8.0, 2.0 to 0.0, 0.0 to 0.0)
            val r = FontQuality.analyze(listOf(listOf(n)), 10.0, true)
            assertEquals(slit, r.gapP10Mm!!, 0.01)
            assertEquals(recommended, r.recommendedMinHeightMm)
        }
        // A counter wound against its outline is a hole (nonzero); 1.5 mm stays clear of 0.6 mm.
        val outer = listOf(0.0 to -20.0, 5.0 to -20.0, 5.0 to 0.0, 0.0 to 0.0)
        val hole = listOf(1.75 to -18.0, 1.75 to -2.0, 3.25 to -2.0, 3.25 to -18.0)
        val o = FontQuality.analyze(listOf(listOf(outer, hole)), 10.0, true)
        assertEquals(1.75, o.inkP10Mm!!, 0.01)
        assertEquals(1.5, o.gapP10Mm!!, 0.01)
        assertEquals(6.0, o.recommendedMinHeightMm)
        // Wound the same way it is filled, like an overlap: no counter left.
        assertNull(FontQuality.analyze(listOf(listOf(outer, hole.reversed())), 10.0, true).gapP10Mm)
    }

    @Test
    fun oneThinSpotDoesNotDecideTheFont() {
        val thinNotch = listOf(0.0 to -20.0, 2.0 to -20.0, 2.0 to -10.2, 1.7 to -10.0, 2.0 to -9.8, 2.0 to 0.0, 0.0 to 0.0)
        val r = FontQuality.analyze(listOf(listOf(thinNotch)), 10.0, true)
        assertEquals(5.0, r.recommendedMinHeightMm)
    }

    @Test
    fun recommendationDoesNotDependOnTheSampleHeight() {
        val glyph = listOf(bar(0.7), bar(0.7, x = 1.5))
        val at10 = FontQuality.analyze(listOf(glyph), 10.0, true)
        val at20 = FontQuality.analyze(listOf(glyph.map { c -> c.map { (x, y) -> x * 2 to y * 2 } }), 20.0, true)
        assertEquals(at10.recommendedMinHeightMm, at20.recommendedMinHeightMm)
    }

    @Test
    fun singleLineJunctionsAreNeverZero() {
        val t = listOf(listOf(0.0 to -10.0, 8.0 to -10.0), listOf(4.0 to -10.0, 4.0 to 0.0))
        val x = listOf(listOf(0.0 to 0.0, 8.0 to -10.0), listOf(8.0 to 0.0, 0.0 to -10.0))
        val apex = listOf(listOf(5.0 to -10.0, 0.0 to 0.0), listOf(5.0 to -10.0, 10.0 to 0.0), listOf(2.0 to -4.0, 8.0 to -4.0))
        val r = FontQuality.analyze(listOf(t, x, apex), 10.0, outline = false)
        assertEquals(5.0, r.recommendedMinHeightMm)
        assertEquals(Grade.GOOD, r.grade)
    }

    @Test
    fun closeParallelStrokesAndSmallLoopsNeedHeight() {
        val lines = listOf(listOf(0.0 to 0.0, 10.0 to 0.0), listOf(0.0 to -0.8, 10.0 to -0.8))
        val parallel = FontQuality.analyze(listOf(lines), 10.0, false)
        assertEquals(0.8, parallel.gapP10Mm!!, 0.01)
        assertEquals(12.5, parallel.recommendedMinHeightMm)
        assertEquals(QualityLimit.GAP, parallel.limitedBy)
        // A stem with a 1 mm loop: the loop needs 1.5 mm.
        val loop = FontQuality.analyze(listOf(listOf(listOf(0.0 to 0.0, 0.0 to -10.0), circle(1.5, -5.0, 0.5))), 10.0, false)
        assertNotNull(loop.loopP10Mm)
        assertEquals(QualityLimit.LOOP, loop.limitedBy)
        assertTrue(loop.recommendedMinHeightMm in 14.0..16.0, "${loop.recommendedMinHeightMm}")
    }

    @Test
    fun bundledParallelStrokesAreOneBolderLine() {
        // Hershey medium weights draw a line as parallel pen strokes 3–4 % of the height apart.
        val lines = listOf(listOf(0.0 to 0.0, 10.0 to 0.0), listOf(0.0 to -0.35, 10.0 to -0.35))
        assertEquals(5.0, FontQuality.analyze(listOf(lines), 10.0, false).recommendedMinHeightMm)
    }

    @Test
    fun hangulHasAHigherDetailFloor() {
        assertEquals(FontQuality.HANGUL_DETAIL_MM, FontQuality.detailFloorMm("가나"))
        assertEquals(FontQuality.LATIN_DETAIL_MM, FontQuality.detailFloorMm("abc"))
        val r = FontQuality.analyze(listOf(listOf(bar(3.0))), 10.0, true, FontQuality.HANGUL_DETAIL_MM)
        assertEquals(7.0, r.recommendedMinHeightMm)
        assertEquals(QualityLimit.DETAIL, r.limitedBy)
    }

    @Test
    fun emptyOrUnmeasurableInputStillHasAFiniteHeight() {
        for (input in listOf(emptyList(), listOf(emptyList()), listOf(listOf(listOf(0.0 to 0.0))))) {
            val r = FontQuality.analyze(input, 10.0, true)
            assertEquals(Grade.UNSUITABLE, r.grade)
            assertEquals(5.0, r.recommendedMinHeightMm)
        }
        val line = FontQuality.analyze(listOf(listOf(listOf(0.0 to 0.0, 10.0 to 0.0))), 10.0, false)
        assertEquals(5.0, line.recommendedMinHeightMm)
        assertEquals(Double.POSITIVE_INFINITY, line.minStrokeMm)
    }

    @Test
    fun countsComeFromTheEngine() {
        val contours = listOf(bar(2.0), bar(1.5, 6.0))
        val expected = LetteringEngine.fromContours(contours, 25.0, sourceHeight = 25.0)
        val r = FontQuality.analyze(listOf(contours), 25.0, true)
        assertEquals(expected.stitchCount, r.stitchCount)
        assertEquals(expected.jumpCount, r.jumpCount)
    }

    @Test
    fun rejectsInvalidHeightAndCoordinates() {
        for (height in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { FontQuality.analyze(emptyList(), height, true) }
        }
        assertFailsWith<IllegalArgumentException> { FontQuality.analyze(listOf(listOf(listOf(Double.NaN to 0.0))), 10.0, true) }
    }

    // Range regressions on redistributable fonts in the repository (docs/lettering.md has the catalog table).

    private fun bundled(name: String) = AwtGlyphs.analyze(File("../app/src/main/res/font/$name.ttf"))

    @Test
    fun juaIsARoundedDisplayFontLimitedByItsJamoGaps() {
        val a = bundled("jua_regular")
        assertTrue(a.hangul)
        assertTrue(a.recommendedMinHeightMm in 9.0..16.0, "${a.recommendedMinHeightMm}")
        assertEquals(a.recommendedMinHeightMm, a.at20.recommendedMinHeightMm)
    }

    @Test
    fun gowunBatangIsARegularMyeongjoLimitedByHairlines() {
        val a = bundled("gowun_batang")
        assertTrue(a.recommendedMinHeightMm in 15.0..30.0, "${a.recommendedMinHeightMm}")
        assertEquals(QualityLimit.INK, a.at10.limitedBy)
    }

    @Test
    fun hersheySimplexIsASmallSingleLineFont() {
        val font = HersheyFont.load()
        val r = FontQuality.analyze(
            FontQuality.LATIN_SAMPLE.mapNotNull { font[it] }.map { g -> g.strokes.map { s -> s.map { it.x * 10.0 / font.capHeight to it.y * 10.0 / font.capHeight } } },
            10.0, outline = false,
        )
        assertTrue(r.recommendedMinHeightMm in 4.0..12.0, "${r.recommendedMinHeightMm} ${r.reasons}")
    }
}
