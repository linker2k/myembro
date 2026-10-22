// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.test.*

class LetteringReeditTest {
    private fun obj() = TextObject(id = "text", text = "Saved", fontId = "offline-font", fontKind = FontKind.OUTLINE,
        heightMm = 18.3, spacing = 1.7, arcDeg = -221.123,
        thread = EmbThread(0xFF123456.toInt(), "Original", "12", "Brand"),
        contours = listOf(listOf(0.0 to 0.0, 10.0 to -20.0, 20.0 to 0.0)),
        contourSourceHeight = 20.0, transform = Transform(100.0, 50.0, 90.0, -1.0, 1.2))

    @Test fun `reopening preserves all initial settings including exact signed arc and custom thread`() {
        val original = obj()
        val settings = original.letteringSettings()
        assertEquals(original.text, settings.text)
        assertEquals(original.fontId, settings.fontId)
        assertEquals(original.heightMm, settings.heightMm)
        assertEquals(original.spacing, settings.spacingMm)
        assertEquals(original.arcDeg, settings.arcDeg)
        assertEquals(LetteringCurve.CIRCLE, settings.curve)
        assertSame(original, reuseSavedLettering(original, settings, original.thread))
    }

    @Test fun `offline size curve and color edits reuse saved contours and placement`() {
        val original = obj()
        val replacement = original.thread.copy(color = 0xFF654321.toInt())
        val settings = original.letteringSettings().copy(heightMm = 25.0, curvature = 50.0, arcOverride = null)
        val edited = reuseSavedLettering(original, settings, replacement)!!
        assertSame(original.contours, edited.contours)
        assertEquals(25.0, edited.heightMm)
        assertEquals(-270.0, edited.arcDeg)
        assertEquals(original.transform, edited.transform)
        assertEquals(replacement, edited.thread)
        assertEquals(original.fontId, edited.fontId)
    }

    @Test fun `content font spacing or alignment changes require fresh glyph layout`() {
        val original = obj()
        val initial = original.letteringSettings()
        for (changed in listOf(initial.copy(text = "Changed"), initial.copy(fontId = "jua"),
            initial.copy(spacingMm = 2.0), initial.copy(alignment = LetteringAlignment.LEFT)))
            assertNull(reuseSavedLettering(original, changed, original.thread))
    }
}
