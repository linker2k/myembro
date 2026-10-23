// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.fonts

import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontCmapTest {
    /** cmap with one format 4 subtable (3,1): segments as (start, end, delta), plus the 0xFFFF terminator. */
    private fun format4(vararg segments: Triple<Int, Int, Int>): ByteBuffer {
        val segs = segments.toList() + Triple(0xFFFF, 0xFFFF, 1)
        val n = segs.size
        val b = ByteBuffer.allocate(12 + 16 + 8 * n)
        b.putShort(0).putShort(1).putShort(3).putShort(1).putInt(12)
        b.putShort(4).putShort((16 + 8 * n).toShort()).putShort(0).putShort((2 * n).toShort()).putShort(0).putShort(0).putShort(0)
        segs.forEach { b.putShort(it.second.toShort()) }
        b.putShort(0)
        segs.forEach { b.putShort(it.first.toShort()) }
        segs.forEach { b.putShort(it.third.toShort()) }
        segs.forEach { b.putShort(0) }
        return b.apply { rewind() }
    }

    @Test
    fun format4RangesAndMissingGlyphs() {
        // 'A'..'Z' map to glyphs 1..26; U+00A0 maps to glyph 0, i.e. missing.
        val cmap = FontCmap.parse(format4(Triple('A'.code, 'Z'.code, 1 - 'A'.code), Triple(0xA0, 0xA0, -0xA0)))!!
        assertTrue(cmap.has("ABZ"))
        assertFalse('a'.code in cmap)
        assertFalse(0xA0 in cmap)
        assertFalse(cmap.has("가"))
    }

    @Test
    fun format12IsPreferred() {
        val b = ByteBuffer.allocate(4 + 16 + 16 + 16 + 12)
        b.putShort(0).putShort(2)
        b.putShort(3).putShort(1).putInt(20) // format 4 placeholder, never chosen
        b.putShort(3).putShort(10).putInt(36)
        b.putShort(4).putShort(16).putShort(0).putShort(2).putShort(0).putShort(0).putShort(0).putShort(0)
        b.putShort(12).putShort(0).putInt(28).putInt(0).putInt(1)
        b.putInt(0xAC00).putInt(0xD7A3).putInt(5)
        val cmap = FontCmap.parse(b.apply { rewind() })!!
        assertTrue(cmap.has("가힣"))
        assertFalse('A'.code in cmap)
    }

    @Test
    fun bundledJuaHasHangulAndLatin() {
        val cmap = FontCmap.read(File("src/main/res/font/jua_regular.ttf"))!!
        assertTrue(cmap.has("가나다자수ABCabc"))
        assertFalse(0x4E00 in cmap) // no Han
    }

    @Test
    fun notAFontIsNull() {
        val f = File.createTempFile("cmap", ".ttf").apply { writeText("not a font"); deleteOnExit() }
        assertNull(FontCmap.read(f))
        assertEquals(null, FontCmap.read(File("missing.ttf")))
    }
}
