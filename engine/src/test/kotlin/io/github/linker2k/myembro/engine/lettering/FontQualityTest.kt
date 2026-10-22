// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FontQualityTest {
    private fun bar(width: Double, x: Double = 0.0) =
        listOf(x to -10.0, x + width to -10.0, x + width to 0.0, x to 0.0, x to -10.0)

    @Test
    fun thickMediumAndThinOutlinesReceiveDifferentGrades() {
        for ((width, grade) in listOf(2.0 to Grade.GOOD, 0.9 to Grade.CAUTION, 0.4 to Grade.UNSUITABLE)) {
            val report = FontQuality.analyze(listOf(listOf(bar(width))), 10.0, outline = true)
            assertEquals(width, report.minStrokeMm, 1e-9)
            assertEquals(10.0 * 1.2 / width, report.recommendedMinHeightMm, 1e-9)
            assertEquals(grade, report.grade)
            assertEquals(width < 1.0, report.tightSpots > 0)
            assertTrue(report.reasons.isNotEmpty())
            assertTrue(report.reasons.any { "최소 외곽선" in it })
        }
    }

    @Test
    fun exactThresholdsAreNotOverWarned() {
        assertEquals(Grade.GOOD, FontQuality.analyze(listOf(listOf(bar(1.2))), 10.0, true).grade)
        val caution = FontQuality.analyze(listOf(listOf(bar(1.0))), 10.0, true)
        assertEquals(Grade.CAUTION, caution.grade)
        assertEquals(0, caution.tightSpots)
        assertEquals(Grade.CAUTION, FontQuality.analyze(listOf(listOf(bar(0.6))), 10.0, true).grade)
    }

    @Test
    fun excludesAdjacentEdgesAndDoesNotTreatDenseSameSideAsThin() {
        val dense = (0..4000).map { 0.0 to (-10.0 + it / 400.0) } +
            listOf(2.0 to 0.0) + (4000 downTo 0).map { 2.0 to (-10.0 + it / 400.0) } + listOf(0.0 to -10.0)
        val report = FontQuality.analyze(listOf(listOf(dense)), 10.0, true)
        assertEquals(2.0, report.minStrokeMm, 1e-9)
        assertEquals(Grade.GOOD, report.grade)
        assertEquals(0, report.tightSpots)
    }

    @Test
    fun measuresOuterToInnerContoursRegardlessOfWinding() {
        val outer = listOf(0.0 to -10.0, 6.0 to -10.0, 6.0 to 0.0, 0.0 to 0.0, 0.0 to -10.0)
        val inner = listOf(0.4 to -8.0, 4.0 to -8.0, 4.0 to -2.0, 0.4 to -2.0, 0.4 to -8.0)
        val a = FontQuality.analyze(listOf(listOf(outer, inner)), 10.0, true)
        val b = FontQuality.analyze(listOf(listOf(outer, inner.reversed())), 10.0, true)
        assertEquals(0.4, a.minStrokeMm, 1e-9)
        assertEquals(a.minStrokeMm, b.minStrokeMm, 1e-9)
        assertEquals(Grade.UNSUITABLE, a.grade)
        assertTrue(a.tightSpots > 0)
    }

    @Test
    fun curveSamplingFindsOpposingSidesWithoutCountingNeighbors() {
        val circle = (0..120).map { i ->
            val angle = if (i == 120) 0.0 else i * 2 * PI / 120
            2.0 * cos(angle) to 2.0 * sin(angle)
        }
        val report = FontQuality.analyze(listOf(listOf(circle)), 4.0, true)
        assertTrue(report.minStrokeMm in 3.8..4.01, "${report.minStrokeMm}")
        assertEquals(Grade.GOOD, report.grade)
    }

    @Test
    fun singleStrokeModeUsesDifferentStrokesAndReportsCrossings() {
        val a = listOf(0.0 to -10.0, 0.0 to 0.0)
        for (gap in listOf(0.4, 0.9, 2.0, 15.0)) {
            val b = listOf(gap to -10.0, gap to 0.0)
            val report = FontQuality.analyze(listOf(listOf(a, b)), 10.0, false)
            assertEquals(gap, report.minStrokeMm, 1e-9)
            assertEquals(gap < 1.0, report.tightSpots > 0)
        }
        val crossing = FontQuality.analyze(listOf(listOf(a, listOf(-1.0 to -5.0, 1.0 to -5.0))), 10.0, false)
        assertEquals(0.0, crossing.minStrokeMm)
        assertEquals(Double.POSITIVE_INFINITY, crossing.recommendedMinHeightMm)
        assertEquals(Grade.UNSUITABLE, crossing.grade)
        assertTrue(crossing.reasons.any { "확대만으로" in it })
    }

    @Test
    fun independentGlyphsDoNotCreateArtificialZeroGaps() {
        val glyph = listOf(bar(2.0))
        val one = FontQuality.analyze(listOf(glyph), 10.0, true)
        val two = FontQuality.analyze(listOf(glyph, glyph), 10.0, true)
        assertEquals(one.minStrokeMm, two.minStrokeMm)
        assertEquals(Grade.GOOD, two.grade)
        assertEquals(one.stitchCount * 2, two.stitchCount)
        assertEquals(one.jumpCount * 2, two.jumpCount)
    }

    @Test
    fun countsComeFromEngineWithoutRescalingMmGeometry() {
        val contours = listOf(bar(2.0), bar(1.5, 6.0))
        val expected = LetteringEngine.fromContours(contours, 25.0, sourceHeight = 25.0)
        val report = FontQuality.analyze(listOf(contours), 25.0, true)
        assertEquals(expected.stitchCount, report.stitchCount)
        assertEquals(expected.jumpCount, report.jumpCount)
        assertEquals(1.5, report.minStrokeMm, 1e-9)
        assertEquals(20.0, report.recommendedMinHeightMm, 1e-9)
    }

    @Test
    fun absentOrUnmeasurableGeometryHasExplicitSemantics() {
        for (input in listOf(emptyList(), listOf(emptyList()), listOf(listOf(listOf(0.0 to 0.0))))) {
            val report = FontQuality.analyze(input, 10.0, true)
            assertEquals(Grade.UNSUITABLE, report.grade)
            assertEquals(0, report.stitchCount)
            assertEquals(0, report.jumpCount)
        }
        val line = listOf(listOf(listOf(0.0 to 0.0, 10.0 to 0.0)))
        val single = FontQuality.analyze(line, 10.0, false)
        assertEquals(Grade.GOOD, single.grade)
        assertEquals(Double.POSITIVE_INFINITY, single.minStrokeMm)
        assertEquals(0.0, single.recommendedMinHeightMm)
        assertEquals(Grade.CAUTION, FontQuality.analyze(line, 10.0, true).grade)
    }

    @Test
    fun rejectsInvalidHeightAndCoordinates() {
        for (height in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { FontQuality.analyze(emptyList(), height, true) }
        }
        assertFailsWith<IllegalArgumentException> {
            FontQuality.analyze(listOf(listOf(listOf(Double.NaN to 0.0))), 10.0, true)
        }
    }
}
