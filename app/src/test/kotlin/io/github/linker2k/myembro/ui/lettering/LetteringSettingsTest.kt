// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.edit.FontKind
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.TextObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LetteringSettingsTest {
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
