// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SvgFontTest {
    // Excerpt in the shape of the Hershey Text / Relief SingleLine files (DOCTYPE, metadata, entities).
    private val font = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd" >
        <svg xmlns="http://www.w3.org/2000/svg" version="1.1">
        <metadata>Font name: Test <glyph unicode="X"/> inside metadata is text</metadata>
        <defs>
        <font id="Test" horiz-adv-x="378" >
        <font-face
        font-family="Test Sans 1-stroke"
        units-per-em="1000"
        ascent="800"
        descent="-200"
        />
        <missing-glyph horiz-adv-x="300" />
        <!-- <glyph unicode="B" d="M 0 0 L 1 1"/> commented out -->
        <glyph unicode=" " glyph-name="space" horiz-adv-x="250" />
        <glyph unicode="A" glyph-name="A" horiz-adv-x="567" d="M 378 662 L 126 0 M 378 662 L 630 0 M 220 220 L 536 220" />
        <glyph glyph-name="Aacute" unicode="&#xc1;" horiz-adv-x="676"
        d="M155 213h367M606 0l-264 675h-4l-268 -675" />
        <glyph unicode="&amp;" d="M0 0 L10 10" />
        <glyph unicode="가" horiz-adv-x="900" d="M0 0 C0 100 100 100 100 0" />
        </font>
        </defs>
        </svg>
    """.trimIndent()

    @Test
    fun parsesFontFaceAndGlyphs() {
        val f = SvgFont.parse(font)
        assertEquals("Test Sans 1-stroke", f.familyName)
        assertEquals(1000.0, f.unitsPerEm)
        assertEquals(800.0, f.ascent)
        assertEquals(-200.0, f.descent)
        assertEquals(378.0, f.defaultAdvance)
        assertEquals(setOf(" ", "A", "Á", "&", "가"), f.glyphs.keys)
        assertTrue(f.supports('Á'))
        assertTrue(f.supports('가'))
        assertEquals(300.0, f.missingGlyph?.advance)
    }

    @Test
    fun strokeGlyphBecomesOnePolylinePerSubpath() {
        val a = assertNotNull(SvgFont.parse(font).glyph('A'))
        assertEquals(567.0, a.advance)
        assertEquals(3, a.strokes.size)
        assertEquals(listOf(SvgPoint(378.0, 662.0), SvgPoint(126.0, 0.0)), a.strokes[0])
        assertEquals(listOf(SvgPoint(220.0, 220.0), SvgPoint(536.0, 220.0)), a.strokes[2])
    }

    @Test
    fun relativeAndHorizontalCommands() {
        val g = assertNotNull(SvgFont.parse(font).glyph('Á'))
        assertEquals(listOf(SvgPoint(155.0, 213.0), SvgPoint(522.0, 213.0)), g.strokes[0])
        assertEquals(
            listOf(SvgPoint(606.0, 0.0), SvgPoint(342.0, 675.0), SvgPoint(338.0, 675.0), SvgPoint(70.0, 0.0)),
            g.strokes[1],
        )
    }

    @Test
    fun cubicIsFlattenedWithinTolerance() {
        val g = assertNotNull(SvgFont.parse(font, tolerance = 0.5).glyph('가'))
        val pts = g.strokes.single()
        assertEquals(SvgPoint(0.0, 0.0), pts.first())
        assertEquals(SvgPoint(100.0, 0.0), pts.last())
        assertTrue(pts.size > 8, "expected subdivision, got ${pts.size}")
        // Peak of this symmetric cubic is y = 75 at t = 0.5.
        assertTrue(pts.maxOf { it.y } in 74.0..75.0)
    }

    @Test
    fun layoutAdvancesAndUsesMissingGlyph() {
        val f = SvgFont.parse(font)
        val strokes = f.layout("A A")
        assertEquals(6, strokes.size)
        assertEquals(378.0 + 567 + 250, strokes[3][0].x)
        assertEquals(567.0 + 300 + 10 * 2, f.advance("A?", spacing = 10.0))
    }

    @Test
    fun closePathReturnsToStart() {
        val p = SvgPath.toPolylines("M0 0 L10 0 L10 10 Z m5 5 l1 0 z")
        assertEquals(2, p.size)
        assertEquals(SvgPoint(0.0, 0.0), p[0].last())
        assertEquals(4, p[0].size)
        // Relative move after Z starts from the closed subpath's start point.
        assertEquals(SvgPoint(5.0, 5.0), p[1].first())
    }

    @Test
    fun compactNumbersAndImplicitLineTo() {
        assertEquals(listOf<Any>('M', 1.5, 0.5, -1.0, -2.0, 1e-3), SvgPath.tokenize("M1.5.5-1-2 1e-3"))
        val p = SvgPath.toPolylines("m10 10 5 0 0 5")
        assertEquals(listOf(SvgPoint(10.0, 10.0), SvgPoint(15.0, 10.0), SvgPoint(15.0, 15.0)), p.single())
    }

    @Test
    fun smoothCurvesReflectControlPoints() {
        val p = SvgPath.toPolylines("M0 0 Q50 50 100 0 T200 0", 0.1).single()
        assertEquals(SvgPoint(200.0, 0.0), p.last())
        assertTrue(p.any { it.x > 100 && it.y < -20 }, "T must mirror the control point below the axis")
        val s = SvgPath.toPolylines("M0 0 C0 50 50 50 50 0 S100 -50 100 0", 0.1).single()
        assertTrue(s.any { it.x > 50 && it.y < -20 })
    }

    @Test
    fun arcStaysOnCircle() {
        // Half circle of radius 50 centered at (50, 0), compact flags "01".
        val p = SvgPath.toPolylines("M0 0 A50 50 0 01 100 0", 0.2).single()
        assertEquals(SvgPoint(100.0, 0.0), p.last())
        assertTrue(p.size > 6)
        for (pt in p) assertEquals(50.0, hypot(pt.x - 50, pt.y), 1e-6)
    }

    @Test
    fun rejectsGarbage() {
        assertFailsWith<IllegalArgumentException> { SvgPath.toPolylines("10 10 L 5 5") }
        assertFailsWith<IllegalArgumentException> { SvgPath.toPolylines("M 0 0 L 5") }
        assertFailsWith<IllegalArgumentException> { SvgFont.parse("<svg></svg>") }
    }
}
