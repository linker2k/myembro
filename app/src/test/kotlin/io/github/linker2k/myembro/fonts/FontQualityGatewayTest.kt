// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.fonts

import io.github.linker2k.myembro.engine.lettering.FontQuality
import io.github.linker2k.myembro.engine.lettering.SvgPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FontQualityGatewayTest {
    private val q = FontQualityGateway

    /** Closed rectangle outline, like the edge of one vertical stroke. */
    private fun bar(width: Double, height: Double = 10.0) =
        listOf(0.0 to 0.0, width to 0.0, width to height, 0.0 to height, 0.0 to 0.0)

    // Glyph paths from Hershey Sans 1-stroke (oskay/svg-fonts @ 8c71f2d9), font units, y up.
    private val hersheyAbc = listOf(
        "M 378 662 L 126 0 M 378 662 L 630 0 M 220 220 L 536 220",
        "M 220 662 L 220 0 M 220 662 L 504 662 L 598 630 L 630 598 L 662 536 L 662 472 L 630 410 L 598 378 L 504 346 " +
            "M 220 346 L 504 346 L 598 315 L 630 284 L 662 220 L 662 126 L 630 63 L 598 31.5 L 504 0 L 220 0",
        "M 662 504 L 630 567 L 567 630 L 504 662 L 378 662 L 315 630 L 252 567 L 220 504 L 189 410 L 189 252 L 220 158 " +
            "L 252 94.5 L 315 31.5 L 378 0 L 504 0 L 567 31.5 L 630 94.5 L 662 158",
    )

    private fun hershey(heightMm: Double) = hersheyAbc.map { d ->
        SvgPath.toPolylines(d, 1.0).map { s -> s.map { it.x * heightMm / 662 to -it.y * heightMm / 662 } }
    }

    @Test
    fun outlinesAreTheEngineReportUnchanged() {
        val glyphs = listOf(listOf(bar(0.8)))
        assertEquals(FontQuality.analyze(glyphs, 10.0, outline = true), q.analyze(glyphs, 10.0, outline = true))
    }

    @Test
    fun engineAloneRatesJoinedSingleLineStrokesUnsuitable() {
        // The reason the adapter exists: every junction is a 0 mm gap to the engine.
        val raw = FontQuality.analyze(hershey(10.0), 10.0, outline = false)
        assertEquals(QualityGrade.UNSUITABLE, raw.grade)
        assertEquals(0.0, raw.minStrokeMm, 1e-9)
    }

    @Test
    fun realHersheySansIsUsableAtTenMillimetres() {
        val r = q.analyze(hershey(10.0), 10.0, outline = false)
        assertTrue(r.grade != QualityGrade.UNSUITABLE, "reasons: ${r.reasons}, min ${r.minStrokeMm}")
        assertTrue(r.recommendedMinHeightMm.isFinite(), "recommended ${r.recommendedMinHeightMm}")
        assertEquals(QualityGrade.GOOD, q.analyze(hershey(20.0), 20.0, outline = false).grade)
    }

    @Test
    fun stitchAndJumpCountsComeFromUncutStrokes() {
        val glyphs = hershey(10.0)
        val raw = FontQuality.analyze(glyphs, 10.0, outline = false)
        val r = q.analyze(glyphs, 10.0, outline = false)
        assertEquals(raw.stitchCount, r.stitchCount)
        assertEquals(raw.jumpCount, r.jumpCount)
    }

    @Test
    fun joinedStrokesAreNotGaps() {
        // "T": the stem ends on the bar; "X": two strokes cross; "A"-like apex where two strokes start together.
        val t = listOf(listOf(0.0 to 0.0, 8.0 to 0.0), listOf(4.0 to 0.0, 4.0 to 10.0))
        val x = listOf(listOf(0.0 to 0.0, 8.0 to 10.0), listOf(8.0 to 0.0, 0.0 to 10.0))
        val apex = listOf(listOf(5.0 to 0.0, 0.0 to 10.0), listOf(5.0 to 0.0, 10.0 to 10.0))
        val r = q.analyze(listOf(t, x, apex), 10.0, outline = false)
        assertEquals(QualityGrade.GOOD, r.grade, "reasons: ${r.reasons}, min ${r.minStrokeMm}")
    }

    @Test
    fun closeParallelStrokesStayUnsuitable() {
        val lines = listOf(listOf(0.0 to 0.0, 10.0 to 0.0), listOf(0.0 to 0.4, 10.0 to 0.4))
        val r = q.analyze(listOf(lines), 10.0, outline = false)
        assertEquals(QualityGrade.UNSUITABLE, r.grade)
        assertEquals(0.4, r.minStrokeMm, 0.01)
    }

    @Test
    fun parallelStrokesBesideAJunctionAreStillMeasured() {
        // "E"-like: arms 0.8 mm apart join a spine; away from the spine they are still too close.
        val e = listOf(
            listOf(0.0 to 0.0, 0.0 to 10.0),
            listOf(0.0 to 4.6, 8.0 to 4.6),
            listOf(0.0 to 5.4, 8.0 to 5.4),
        )
        val r = q.analyze(listOf(e), 10.0, outline = false)
        assertTrue(r.grade != QualityGrade.GOOD, "reasons: ${r.reasons}, min ${r.minStrokeMm}")
        assertEquals(0.8, r.minStrokeMm, 0.05)
    }

    @Test
    fun loopCrossedByATailIsNotAGap() {
        // "Q": a closed bowl (one stroke) and a tail crossing it; cutting must not split the loop.
        val bowl = (0..48).map { k -> val a = 2 * Math.PI * k / 48; 5 + 4 * kotlin.math.cos(a) to 5 + 5 * kotlin.math.sin(a) }
        val tail = listOf(6.0 to 8.0, 10.0 to 11.0)
        val r = q.analyze(listOf(listOf(bowl, tail)), 10.0, outline = false)
        assertEquals(QualityGrade.GOOD, r.grade, "reasons: ${r.reasons}, min ${r.minStrokeMm}")
    }

    @Test
    fun strokeRetracingTheStemIsNotAGap() {
        // "b"-like: the bowl stroke runs back up along the stem before turning, as EMS fonts do.
        val stem = listOf(0.0 to 0.0, 0.0 to 10.0)
        val bowl = listOf(0.0 to 10.0, 0.0 to 6.0, 3.0 to 5.0, 5.0 to 7.5, 3.0 to 10.0, 0.0 to 9.0)
        val r = q.analyze(listOf(listOf(stem, bowl)), 10.0, outline = false)
        assertTrue(r.minStrokeMm > 0.6, "reasons: ${r.reasons}, min ${r.minStrokeMm}")
    }

    @Test
    fun theRecommendedHeightDoesNotDependOnTheSampleHeight() {
        val at10 = q.analyze(hershey(10.0), 10.0, outline = false).recommendedMinHeightMm
        val at20 = q.analyze(hershey(20.0), 20.0, outline = false).recommendedMinHeightMm
        assertEquals(at10, at20, at10 * 0.1)
    }

    @Test
    fun overlappingOutlinesAreNotFixedBySize() {
        // Two contours 2 µm apart, as in handwriting fonts built from touching strokes.
        val r = q.analyze(listOf(listOf(bar(2.0), bar(2.0).map { (x, y) -> x + 2.002 to y + 3.0 })), 10.0, outline = true)
        assertEquals(QualityGrade.UNSUITABLE, r.grade)
        assertEquals(Double.POSITIVE_INFINITY, r.recommendedMinHeightMm)
        assertTrue(QualityReasons.NOT_SCALABLE in r.reasons)
        assertTrue(r.reasons.none { "키워 보세요" in it }, "reasons: ${r.reasons}")
    }

    @Test
    fun trimLeavesUnjoinedStrokesAlone() {
        val strokes = listOf(listOf(0.0 to 0.0, 5.0 to 0.0), listOf(0.0 to 3.0, 5.0 to 3.0))
        assertEquals(strokes, q.trimContacts(strokes, 0.25, 2.0))
    }

    @Test
    fun emptyInputIsUnsuitable() {
        assertEquals(QualityGrade.UNSUITABLE, q.analyze(emptyList(), 10.0, outline = false).grade)
        assertEquals(QualityGrade.UNSUITABLE, q.analyze(emptyList(), 10.0, outline = true).grade)
    }
}
