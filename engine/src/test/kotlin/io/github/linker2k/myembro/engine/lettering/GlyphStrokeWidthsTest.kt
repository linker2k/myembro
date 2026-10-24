// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.StrokeWidthMeasurement
import org.locationtech.jts.io.WKTReader
import kotlin.test.*

class GlyphStrokeWidthsTest {
    private fun box(x:Double,y:Double,w:Double,h:Double)=listOf(x to y,(x+w) to y,(x+w) to (y+h),x to (y+h),x to y)
    private val font=GlyphStrokeWidths.FontOutlines("test-font@revision",2.0,listOf(
        GlyphStrokeWidths.Glyph(0,'정'.code,listOf(box(0.0,0.0,20.0,2.0))),
        GlyphStrokeWidths.Glyph(1,'민'.code,listOf(box(40.0,0.0,20.0,1.0))),
        GlyphStrokeWidths.Glyph(2,' '.code,emptyList())))

    @Test fun reportsPerGlyphMedianInMmAndRetainsBlankRows() {
        val result=GlyphStrokeWidths.measure(font,2.0)
        val expected=StrokeWidthMeasurement.measure(WKTReader().read("POLYGON((0 0,20 0,20 2,0 2,0 0))"))
        assertEquals(expected.medianMm,result.rows[0].medianWidthMm)
        assertEquals(listOf('정'.code,'민'.code,' '.code),result.rows.map {it.codePoint})
        assertTrue(result.rows[0].medianWidthMm!! > result.rows[1].medianWidthMm!!)
        assertNull(result.rows[2].medianWidthMm);assertEquals(0,result.rows[2].sampleCount)
    }
    @Test fun scalesWithHeightAtProportionalSamplingAndDoesNotMergeRepeatedCharacters() {
        val a=GlyphStrokeWidths.measure(font,2.0,0.1)
        val b=GlyphStrokeWidths.measure(font,4.0,0.2)
        assertEquals(2*a.rows[0].medianWidthMm!!,b.rows[0].medianWidthMm!!,1e-8)
        val repeat=font.copy(glyphs=listOf(font.glyphs[0],font.glyphs[0].copy(characterIndex=4)))
        val rows=GlyphStrokeWidths.measure(repeat,2.0).rows
        assertEquals(listOf(0,4),rows.map {it.characterIndex})
        assertEquals(rows[0].medianWidthMm,rows[1].medianWidthMm)
    }
    @Test fun evenOddHoleMatchesSharedMeasurement() {
        val glyph=GlyphStrokeWidths.Glyph(0,'ㅇ'.code,listOf(box(0.0,0.0,10.0,10.0),box(2.0,2.0,6.0,6.0)))
        val measured=GlyphStrokeWidths.measure(font.copy(sourceHeight=10.0,glyphs=listOf(glyph)),10.0)
        val expected=StrokeWidthMeasurement.measure(WKTReader().read("POLYGON((0 0,10 0,10 10,0 10,0 0),(2 2,2 8,8 8,8 2,2 2))"))
        assertEquals(expected.medianMm,measured.rows.single().medianWidthMm)
    }
    @Test fun rejectsInvalidInputInsteadOfInventingWidths() {
        for(height in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException>{GlyphStrokeWidths.measure(font,height)}
        assertFailsWith<IllegalArgumentException>{GlyphStrokeWidths.measure(font.copy(sourceHeight=0.0),2.0)}
        assertFailsWith<IllegalArgumentException>{GlyphStrokeWidths.measure(font,2.0,0.0)}
        assertFailsWith<IllegalArgumentException>{GlyphStrokeWidths.measure(font.copy(glyphs=listOf(font.glyphs[0],font.glyphs[0])),2.0)}
        assertFailsWith<IllegalArgumentException>{GlyphStrokeWidths.measure(font.copy(glyphs=listOf(font.glyphs[0].copy(codePoint=0xD800))),2.0)}
    }
}
