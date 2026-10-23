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
        for ((width, recommended) in listOf(2.0 to 6.0, 1.0 to 10.0, 0.5 to 20.0, 0.4 to 25.0)) {
            val r = FontQuality.analyze(listOf(listOf(bar(width))), 10.0, outline = true)
            assertEquals(width, r.inkP10Mm!!, 0.01)
            assertEquals(recommended, r.recommendedMinHeightMm, 1e-9)
            assertEquals(if (recommended > 6.0) QualityLimit.INK else QualityLimit.DETAIL, r.limitedBy)
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
        // A 2 mm stroke ending in a brush tip: the tip's end is excluded, the stroke width counts.
        val tipped = listOf(0.0 to -10.0, 2.0 to -10.0, 2.0 to -2.0, 1.0 to 0.0, 0.0 to -2.0)
        val r = FontQuality.analyze(listOf(listOf(tipped)), 10.0, true)
        assertTrue(r.inkP10Mm!! > 1.5, "${r.inkP10Mm}")
        assertEquals(6.0, r.recommendedMinHeightMm)
    }

    @Test
    fun narrowCountersAndSlitsAreGaps() {
        // Two 2 mm stems joined at the top: the slit between them must stay at least 0.6 mm.
        for ((slit, recommended) in listOf(0.3 to 20.0, 0.6 to 10.0, 1.5 to 6.0)) {
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
        assertEquals(6.0, r.recommendedMinHeightMm)
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
        assertEquals(6.0, r.recommendedMinHeightMm, "${r.reasons}")
        assertEquals(Grade.GOOD, r.grade)
    }

    @Test
    fun closeParallelStrokesAndSmallLoopsNeedHeight() {
        val lines = listOf(listOf(0.0 to 0.0, 10.0 to 0.0), listOf(0.0 to -0.8, 10.0 to -0.8))
        val parallel = FontQuality.analyze(listOf(lines), 10.0, false)
        assertEquals(0.8, parallel.gapP10Mm!!, 0.01)
        assertEquals(12.5, parallel.recommendedMinHeightMm)
        assertEquals(QualityLimit.GAP, parallel.limitedBy)
        // One path doubling back on itself (a hairpin) is measured like two strokes.
        val hairpin = FontQuality.analyze(listOf(listOf(listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to -0.8, 0.0 to -0.8))), 10.0, false)
        assertEquals(0.8, hairpin.gapP10Mm!!, 0.01)
        assertEquals(12.5, hairpin.recommendedMinHeightMm)
        // A stem with a 1.5 mm loop: 6 stitches of 1 mm around need a 6/π mm (2.0 on the grid) loop.
        val loop = FontQuality.analyze(listOf(listOf(listOf(0.0 to 0.0, 0.0 to -10.0), circle(2.0, -5.0, 0.75))), 10.0, false)
        assertEquals(1.5, loop.loopP10Mm!!, 0.02)
        assertEquals(QualityLimit.LOOP, loop.limitedBy)
        assertEquals(13.5, loop.recommendedMinHeightMm)
        // A 1 mm loop is small but round, so it still counts as a loop.
        val small = FontQuality.analyze(listOf(listOf(circle(0.0, -5.0, 0.5))), 10.0, false)
        assertEquals(1.0, small.loopP10Mm!!, 0.02)
        assertTrue(small.recommendedMinHeightMm in 20.0..20.5, "${small.recommendedMinHeightMm}") // a 96-gon is a hair shorter than its circle
    }

    @Test
    fun bundledParallelStrokesAreOneBolderLine() {
        // Hershey medium weights draw a line as parallel pen strokes 3–4 % of the height apart.
        val lines = listOf(listOf(0.0 to 0.0, 10.0 to 0.0), listOf(0.0 to -0.5, 10.0 to -0.5))
        assertEquals(6.0, FontQuality.analyze(listOf(lines), 10.0, false).recommendedMinHeightMm)
    }

    @Test
    fun limitsFollowTheThread() {
        // 40wt (0.4 mm): satin 2.5 diameters, a counter one diameter open after 0.2 mm spread.
        assertEquals(1.0, FontQuality.satinMinMm(ThreadWeight.WT40), 1e-9)
        assertEquals(0.6, FontQuality.gapMinMm(ThreadWeight.WT40), 1e-9)
        assertEquals(1.0, FontQuality.lineGapMinMm(ThreadWeight.WT40), 1e-9)
        // 60wt (0.3 mm): 0.75 and 0.5 mm, rounded up to the 0.1 mm stitch grid.
        assertEquals(0.8, FontQuality.satinMinMm(ThreadWeight.WT60), 1e-9)
        assertEquals(0.5, FontQuality.gapMinMm(ThreadWeight.WT60), 1e-9)
        assertEquals(0.8, FontQuality.lineGapMinMm(ThreadWeight.WT60), 1e-9)
        assertEquals(2.0, FontQuality.loopMinMm(), 1e-9)
        assertEquals(0.8, ThreadWeight.WT60.minimumFeatureMm, 1e-9)
        val thin = listOf(listOf(bar(0.8)))
        assertEquals(12.5, FontQuality.analyze(thin, 10.0, true, threadWeight = ThreadWeight.WT40).recommendedMinHeightMm)
        assertEquals(10.0, FontQuality.analyze(thin, 10.0, true, threadWeight = ThreadWeight.WT60).recommendedMinHeightMm)
        // Floors shrink with 60wt, but no general font goes to 5 mm or below.
        val bold = listOf(listOf(bar(3.0)))
        assertEquals(5.5, FontQuality.analyze(bold, 10.0, true, threadWeight = ThreadWeight.WT60).recommendedMinHeightMm)
        assertEquals(6.5, FontQuality.analyze(bold, 10.0, true, FontQuality.HANGUL_DETAIL_MM, ThreadWeight.WT60).recommendedMinHeightMm)
    }

    @Test
    fun brushSpecksAndOpenCornersAreNotStrokesOrGaps() {
        // A 0.1 × 0.3 mm speck beside a 2 mm stem is texture, not a 0.1 mm stroke.
        val speck = listOf(3.0 to -5.0, 3.3 to -5.0, 3.3 to -4.9, 3.0 to -4.9)
        assertEquals(2.0, FontQuality.analyze(listOf(listOf(bar(2.0), speck)), 10.0, true).inkP10Mm!!, 0.01)
        // The inside of a right-angled L faces sideways, not back: no gap.
        val l = listOf(0.0 to -10.0, 2.0 to -10.0, 2.0 to -2.0, 8.0 to -2.0, 8.0 to 0.0, 0.0 to 0.0)
        assertNull(FontQuality.analyze(listOf(listOf(l)), 10.0, true).gapP10Mm)
    }

    @Test
    fun hangulHasAHigherDetailFloor() {
        assertEquals(FontQuality.HANGUL_DETAIL_MM, FontQuality.detailFloorMm("가나"))
        assertEquals(FontQuality.LATIN_DETAIL_MM, FontQuality.detailFloorMm("abc"))
        val r = FontQuality.analyze(listOf(listOf(bar(3.0))), 10.0, true, FontQuality.HANGUL_DETAIL_MM)
        assertEquals(8.0, r.recommendedMinHeightMm)
        assertEquals(QualityLimit.DETAIL, r.limitedBy)
    }

    @Test
    fun emptyOrUnmeasurableInputStillHasAFiniteHeight() {
        for (input in listOf(emptyList(), listOf(emptyList()), listOf(listOf(listOf(0.0 to 0.0))))) {
            val r = FontQuality.analyze(input, 10.0, true)
            assertEquals(Grade.UNSUITABLE, r.grade)
            assertEquals(6.0, r.recommendedMinHeightMm)
        }
        val line = FontQuality.analyze(listOf(listOf(listOf(0.0 to 0.0, 10.0 to 0.0))), 10.0, false)
        assertEquals(6.0, line.recommendedMinHeightMm)
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
        assertTrue(r.recommendedMinHeightMm in 6.0..12.0, "${r.recommendedMinHeightMm} ${r.reasons}")
    }
}
