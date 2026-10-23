// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.edit.FontKind
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.lettering.FontQuality
import io.github.linker2k.myembro.engine.lettering.Grade
import io.github.linker2k.myembro.engine.lettering.FontQualityReport
import io.github.linker2k.myembro.engine.lettering.FillOptions
import io.github.linker2k.myembro.engine.lettering.FillBorder
import io.github.linker2k.myembro.engine.lettering.StitchStyle
import io.github.linker2k.myembro.ui.format.Dimensions
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LetteringSettingsTest {
    @Test fun fineThreadUpdatesOfflineEditorWarningsAndPracticalMinimum() {
        val weight = io.github.linker2k.myembro.engine.lettering.ThreadWeight.WT60
        val contour = listOf(0.0 to 0.0, 0.5 to 0.0, 0.5 to 10.0, 0.0 to 10.0, 0.0 to 0.0)
        val obj = TextObject("fine", fontKind = FontKind.OUTLINE, contours = listOf(contour),
            contourSourceHeight = 10.0, heightMm = 10.0, stitchStyle = StitchStyle.FILL)
        val normal = outlineTextQuality(obj)
        val fine = outlineTextQuality(obj.withThreadWeight(weight))
        assertEquals(24.0, normal.recommendedMinHeightMm, 0.001)
        assertEquals(18.0, fine.recommendedMinHeightMm, 0.001)
        assertEquals(Grade.CAUTION, fine.grade)
        assertEquals(4.5, filledLetteringMinimumHeight(fine.copy(recommendedMinHeightMm = 3.0), weight), 0.001)
    }

    @Test fun fillWarningsKeepMeasuredRecommendationAndPracticalMinimumInBothUnits() {
        val report = FontQualityReport(2.0, 3.0, 100, 1, 0, Grade.GOOD, emptyList())
        assertEquals(6.0, filledLetteringMinimumHeight(report))
        assertEquals(24.0, filledLetteringMinimumHeight(report.copy(recommendedMinHeightMm = 24.0)))
        assertEquals(Double.POSITIVE_INFINITY, filledLetteringMinimumHeight(report.copy(recommendedMinHeightMm = Double.POSITIVE_INFINITY)))
        assertEquals("12.7mm (½in)", Dimensions.length(12.7))
        val options = FillOptions(0.5, 45.0, 0.3, false, FillBorder.BEAN)
        val settings = LetteringSettings("자수", "jua", 12.7, 1.0, LetteringAlignment.CENTER,
            false, LetteringCurve.STRAIGHT, 0.0, stitchStyle = StitchStyle.FILL, fillOptions = options)
        assertEquals(options, settings.copy(text = "ㅇㅁB").fillOptions)
    }

    @Test fun editorChecksCachedOutlineQualityWithoutNeedingTheFont() {
        val thin = listOf(0.0 to 0.0, 5.0 to 0.0, 5.0 to 100.0, 0.0 to 100.0, 0.0 to 0.0)
        val text = TextObject("offline", text = "II", fontId = "deleted-font", fontKind = FontKind.OUTLINE,
            contours = listOf(thin, thin.map { (x, y) -> x + 30 to y }), contourCharacters = listOf(0, 1),
            heightMm = 10.0, contourSourceHeight = 100.0, stitchStyle = StitchStyle.FILL)
        val quality = outlineTextQuality(text)
        assertEquals(0.5, quality.minStrokeMm, 0.01)
        assertEquals(24.0, filledLetteringMinimumHeight(quality), 0.01)
        assertEquals(Grade.UNSUITABLE, quality.grade)
    }

    @Test fun sa431PreviewUsesActualAspectRatioAndKeepsQualityHeightIndependentOfHoop() {
        val hoop = HoopSpec("SA431", 600, 200) // 60 × 20 mm landscape sewing area
        val thinGlyph = listOf(listOf(0.0 to -10.0, 0.5 to -10.0, 0.5 to 0.0, 0.0 to 0.0, 0.0 to -10.0))
        val obj = TextObject("small", text = "I", fontKind = FontKind.OUTLINE, contours = thinGlyph,
            heightMm = 10.0, contourSourceHeight = 10.0)
        val pattern = obj.localPattern()
        assertFalse(letteringOutsideHoop(pattern, hoop))
        val viewport = letteringViewport(300f, pattern, hoop, false)
        assertEquals(0.5f, viewport.scale)
        assertEquals(3f, (hoop.width * viewport.scale) / (hoop.height * viewport.scale))
        val report = FontQuality.analyze(listOf(thinGlyph), 10.0, outline = true)
        assertEquals(Grade.UNSUITABLE, report.grade)
        assertEquals(24.0, report.recommendedMinHeightMm, 0.01)
        assertTrue(letteringOutsideHoop(obj.copy(heightMm = report.recommendedMinHeightMm).localPattern(), hoop))
        assertTrue(letteringOutsideHoop(obj.copy(heightMm = 21.0).localPattern(), hoop))
        val vertical = HoopSpec("SA431 portrait", 200, 600)
        assertFalse(letteringOutsideHoop(obj.copy(heightMm = 24.0).localPattern(), vertical))
        for (alignment in LetteringAlignment.entries) {
            val placed = placeLettering(obj, hoop, alignment).placedPattern()
            assertFalse(letteringOutsideHoop(placed, hoop))
            val fitted = letteringViewport(300f, placed, hoop, true)
            val bounds = assertNotNull(placed.bounds)
            assertEquals((bounds.minX.toDouble() + bounds.maxX) / 2, fitted.centerX)
            assertEquals((bounds.minY.toDouble() + bounds.maxY) / 2, fitted.centerY)
        }
    }

    @Test
    fun multilineLayoutKeepsOwnersLineGapsAndAlignment() {
        val shape = listOf(listOf(0.0 to -10.0, 10.0 to 0.0))
        val lines = listOf(listOf(LayoutGlyph(0, 10.0, shape), LayoutGlyph(1, 10.0, shape)),
            emptyList(), listOf(LayoutGlyph(4, 10.0, shape)))
        val settings = LetteringSettings("AA\n\nA", "jua", 10.0, 0.0, LetteringAlignment.LEFT, false, LetteringCurve.ARCH, 35.0,
            lineSpacingMm = 5.0)
        val left = layoutGlyphs(lines, 10.0, settings)
        assertEquals(listOf(0, 1, 4), left.characters)
        assertEquals(listOf(0.0, 0.0, 30.0), left.baselines)
        assertEquals(-10.0, left.contours.last().first().first)
        val center = layoutGlyphs(lines, 10.0, settings.copy(alignment = LetteringAlignment.CENTER))
        val right = layoutGlyphs(lines, 10.0, settings.copy(alignment = LetteringAlignment.RIGHT))
        assertEquals(-5.0, center.contours.last().first().first)
        assertEquals(0.0, right.contours.last().first().first)
        assertEquals(20.0, left.contours.last().first().second)
        assertEquals(-63.0, settings.copy(curved = true, curve = LetteringCurve.BELOW).arcDeg)
    }

    @Test
    fun characterColorsFollowCodePointsAcrossInsertionDeletionAndNfc() {
        assertEquals(hashMapOf(0 to 1, 3 to 2), remapCharacterColors("A😀B", "A😀\nB", mapOf(0 to 1, 2 to 2)))
        assertEquals(hashMapOf(1 to 2), remapCharacterColors("A😀B", "😀B", mapOf(0 to 1, 2 to 2)))
        assertEquals(hashMapOf(0 to 3), remapCharacterColors("자", "자", mapOf(0 to 3)))
    }
    @Test
    fun curveToggleAndModesProduceEngineAngles() {
        val settings = LetteringSettings("봄날", "jua", 18.0, 1.2, LetteringAlignment.CENTER, false, LetteringCurve.ARCH, 35.0)
        assertEquals(0.0, settings.arcDeg)
        assertEquals(63.0, settings.copy(curved = true).arcDeg)
        assertEquals(0.0, settings.copy(curved = true, curve = LetteringCurve.STRAIGHT).arcDeg)
        assertEquals(243.0, settings.copy(curved = true, curve = LetteringCurve.CIRCLE).arcDeg)
        assertEquals(360.0, settings.copy(curved = true, curve = LetteringCurve.CIRCLE, curvature = 200.0).arcDeg)
    }

    @Test
    fun multilineAlignmentOffsetsShorterLines() {
        assertEquals(-10.0, lineOffset(20.0, 40.0, LetteringAlignment.LEFT))
        assertEquals(0.0, lineOffset(20.0, 40.0, LetteringAlignment.CENTER))
        assertEquals(10.0, lineOffset(20.0, 40.0, LetteringAlignment.RIGHT))
    }

    @Test
    fun addedObjectKeepsEditableDataAndAlignsInsideHoop() {
        val outline = listOf(listOf(0.0 to -10.0, 20.0 to -10.0, 20.0 to 0.0, 0.0 to 0.0, 0.0 to -10.0))
        val obj = TextObject(id = "test", text = "자수", fontId = "jua", fontKind = FontKind.OUTLINE,
            contours = outline, heightMm = 10.0, contourSourceHeight = 10.0)
        val left = placeLettering(obj, HoopSpec.H100, LetteringAlignment.LEFT)
        val right = placeLettering(obj, HoopSpec.H100, LetteringAlignment.RIGHT)
        assertEquals(-450, assertNotNull(left.placedPattern().bounds).minX)
        assertEquals(450, assertNotNull(right.placedPattern().bounds).maxX)
        assertEquals(0.0, placeLettering(obj, HoopSpec.H100, LetteringAlignment.CENTER).transform.x)
        assertEquals(obj.contours, left.contours)
        assertEquals("자수", left.text)
        assertEquals("jua", left.fontId)
        assertTrue(left.localPattern().stitchCount > 0)
        assertEquals(0.0, obj.transform.x)
    }
}
