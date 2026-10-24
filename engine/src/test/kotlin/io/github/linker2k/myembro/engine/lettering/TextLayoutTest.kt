// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Direct JVM use: no app settings, resources or Android classes. */
class TextLayoutTest {
    @Test fun normalizationRetainsCodePointsAndWhitespaceContract() {
        assertEquals("정 A    민\n\n😀\n", normalizedLetteringText("정 A\t민\r\n\r😀\n"))
    }

    @Test fun blankLinesAndBlankGlyphsRetainOwnersAndBaselines() {
        val lines = listOf(listOf(LayoutGlyph(0, 10.0, listOf(listOf(0.0 to -10.0, 10.0 to 0.0))),
            LayoutGlyph(1, 5.0, emptyList())), emptyList(),
            listOf(LayoutGlyph(4, 10.0, listOf(listOf(0.0 to -10.0, 10.0 to 0.0)))))
        val result = layoutGlyphs(lines, 10.0, 10.0, 1.0, LetteringAlignment.LEFT, 5.0)
        assertEquals(listOf(listOf(-8.0 to -10.0, 2.0 to 0.0),
            listOf(-8.0 to 20.0, 2.0 to 30.0)), result.contours)
        assertEquals(listOf(0, 4), result.characters)
        assertEquals(listOf(0.0, 30.0), result.baselines)
        assertEquals(LetteringLayout(emptyList(), emptyList(), emptyList()),
            layoutGlyphs(emptyList(), 1000.0, 15.0, 1.0, LetteringAlignment.RIGHT, 9.0))
    }

    @Test fun fontCoverageReaderIsAvailableWithoutAndroid() {
        val b = ByteBuffer.allocate(40)
        b.putShort(0).putShort(1).putShort(3).putShort(10).putInt(12)
        b.putShort(12).putShort(0).putInt(28).putInt(0).putInt(1)
        b.putInt(0xAC00).putInt(0xD7A3).putInt(1)
        val coverage = FontCmap.parse(b.apply { rewind() })!!
        assertTrue(coverage.has("정민"))
        assertFalse(coverage.has("A"))
    }
}
