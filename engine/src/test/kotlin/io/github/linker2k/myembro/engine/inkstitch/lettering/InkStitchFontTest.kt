// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.lettering

import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.lettering.*

import io.github.linker2k.myembro.engine.model.Command
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InkStitchFontTest {
    // User units are mm here (viewBox 100 on a 100 mm page), as in some Ink/Stitch files; Ink/Stitch
    // itself works in px, so the parser must apply the viewBox mapping.
    private val svg = """<?xml version="1.0"?>
<svg xmlns="http://www.w3.org/2000/svg" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape"
     xmlns:sodipodi="http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd" xmlns:xlink="http://www.w3.org/1999/xlink"
     xmlns:inkstitch="http://inkstitch.org/namespace" width="100mm" height="100mm" viewBox="0 0 100 100">
  <sodipodi:namedview><sodipodi:guide position="0,50" orientation="0,1" inkscape:label="baseline"/></sodipodi:namedview>
  <defs><path id="unused" d="M 0,0 H 100" style="stroke:#000"/></defs>
  <g inkscape:groupmode="layer" inkscape:label="GlyphLayer-H" style="display:none" transform="translate(10,0)">
    <path d="M 0,40 V 50 M 2,50 V 40 M -1,45 H 3" style="fill:none;stroke:#ff0000"
          inkstitch:satin_column="True" inkstitch:zigzag_spacing_mm="0.5" inkstitch:center_walk_underlay="True"/>
    <path d="M 6,40 V 50" style="fill:none;stroke:#0000ff;display:none"/>
    <path d="M 1,40 L 8,40" style="fill:none;stroke:#000" inkscape:connector-type="polyline"/>
    <use xlink:href="#inkstitch_trim" x="0" y="0"/>
  </g>
  <g inkscape:groupmode="layer" inkscape:label="GlyphLayer-I">
    <path d="M 0,40 V 50" style="fill:none;stroke:#00ff00" inkstitch:running_stitch_length_mm="2"/>
    <path d="M 0,40 V 50" style="fill:none;stroke:#00ff00" inkstitch:stroke_method="ripple_stitch"/>
  </g>
  <g inkscape:groupmode="layer" inkscape:label="GlyphLayer-O">
    <path d="M 0,40 H 8 V 50 H 0 Z M 2,42 H 6 V 48 H 2 Z" style="fill:#123456;fill-rule:evenodd"
          inkstitch:angle="45" inkstitch:row_spacing_mm="0.3" inkstitch:fill_underlay="false"/>
    <use xlink:href="#unused"/>
  </g>
</svg>"""
    private val json = """{"name": "Test", "font_license": "SIL Open Font License v1.1", "size": 10, "min_scale": 0.8,
        "max_scale": 1.5, "horiz_adv_x": {"H": 20}, "kerning_pairs": {"H I": 4}, "horiz_adv_x_space": 30}"""
    private val font = InkStitchFont.parse(svg.byteInputStream(), json)
    private val px = 1 / InkStitchFont.MM_PER_PX

    @Test fun readsGlyphLayersInPx() {
        assertEquals(setOf("H", "I", "O"), font.glyphs.keys)
        val h = font.glyphs.getValue("H")
        assertEquals(1, h.elements.size, "hidden path, connector and command are not sewn")
        assertTrue(abs(font.referenceHeightMm!! - 10.0) < 0.01, "10 user units of 1 mm")
        assertTrue(abs(font.baseline - 50 * px) < 1e-6, "guide is y-up from the page bottom")
        assertEquals(8.0, font.minHeightMm!!, 1e-6)
        assertEquals(mapOf("stroke:ripple_stitch" to 1), font.unsupported)
        // Glyph extents include the hidden path and the unattached connector: Ink/Stitch spaces by
        // every path in the layer except command connectors (those with a connection end).
        assertEquals(18 * px, h.maxX, 1e-6)
    }

    @Test fun rungsCutTheRails() {
        val satin = font.glyphs.getValue("H").elements.single() as InkSatin
        assertEquals(2, satin.rails.size)
        assertEquals(1, satin.rungs.size)
        val sections = TemporarySatin.sections(satin)!!
        assertEquals(2, sections.size)
        val (a, b) = sections.first()
        // The second rail was drawn the other way; it is reversed to run with the first.
        assertTrue(abs(a.first().y - b.first().y) < 1e-6 && abs(a.last().y - 45 * px) < 1e-6 && abs(b.last().y - 45 * px) < 1e-6)
        assertEquals(0xFFFF0000.toInt(), satin.color)
    }

    @Test fun fillKeepsRuleAndParameters() {
        val elements = font.glyphs.getValue("O").elements
        val fill = elements.first() as InkFill
        // The clone of a stroke in defs sews as that stroke, as Ink/Stitch resolves clones.
        assertTrue(elements.drop(1).any { it is InkRun && it.color == 0xFF000000.toInt() })
        assertEquals(io.github.linker2k.myembro.engine.geometry.FillRule.EVEN_ODD, fill.rule)
        assertEquals(45.0, fill.params.number("angle"))
        assertTrue(!fill.params.bool("fill_underlay", true))
    }

    @Test fun rendersAtTheRequestedHeight() {
        val pattern = InkStitchLettering.render(font, "H", 20.0)
        val sewn = pattern.stitches.filter { it.command == Command.STITCH }
        val height = (sewn.maxOf { it.y } - sewn.minOf { it.y }) / 10.0
        assertTrue(abs(height - 20.0) < 0.3, "height $height")
        // Stitch spacing stays 0.5 mm on each rail when the letter doubles: about 2 × 20 / 0.5 rows.
        assertTrue(pattern.stitchCount in 70..140, "stitches ${pattern.stitchCount}")
        assertTrue(sewn.zipWithNext().all { (p, q) -> hypot((q.x - p.x).toDouble(), (q.y - p.y).toDouble()) <= 70.5 })
    }

    @Test fun layoutUsesAdvanceKerningAndWordSpacing() {
        val placed = InkStitchLettering.layout(font, "HI O", 10.0)
        val scale = placed.first().scale
        val h = font.glyphs.getValue("H"); val i = font.glyphs.getValue("I"); val o = font.glyphs.getValue("O")
        // H at the origin; I after H's advance (20) minus kerning (4) plus I's own left extent.
        assertEquals(-h.minX * scale, placed[0].dx, 1e-9)
        assertEquals((20 - h.minX + i.minX - 4 - i.minX) * scale, placed[1].dx, 1e-9)
        val afterI = 20 - h.minX + i.minX - 4 + (i.maxX - i.minX)
        assertEquals((afterI + 30 - o.minX) * scale, placed[2].dx, 1e-9)
    }

    @Test fun fontColorsChangeThreadsUnlessOneThreadIsGiven() {
        val colored = InkStitchLettering.render(font, "HIO", 10.0)
        // H red, I green, O fill navy and its black clone.
        assertEquals(4, colored.threads.size)
        assertEquals(3, colored.colorChangeCount)
        val mono = InkStitchLettering.render(font, "HIO", 10.0, thread = io.github.linker2k.myembro.engine.model.EmbThread(0xFF000000.toInt()))
        assertEquals(1, mono.threads.size)
        assertEquals(0, mono.colorChangeCount)
    }

    @Test fun refusesDoctypes() {
        val evil = """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]><svg>&x;</svg>"""
        assertFailsWith<Exception> { InkStitchFont.parse(evil.byteInputStream(), json) }
    }

    @Test fun textObjectsResolveTheFontByIdAndSurviveTheCodec() {
        InkStitchFontRegistry.put("test-ink", font)
        val text = io.github.linker2k.myembro.engine.edit.TextObject("t", text = "HI\nH", fontId = "test-ink", heightMm = 10.0,
            spacing = 0.0, fontKind = io.github.linker2k.myembro.engine.edit.FontKind.INKSTITCH,
            alignment = TextAlignment.CENTER, lineSpacing = 2.0)
        val pattern = text.localPattern()
        assertTrue(pattern.stitchCount > 50)
        val decoded = io.github.linker2k.myembro.engine.edit.TextObjectCodec.decode(
            kotlinx.serialization.json.JsonObject(io.github.linker2k.myembro.engine.edit.TextObjectCodec.encode(text)))
            as io.github.linker2k.myembro.engine.edit.TextObject
        assertEquals(io.github.linker2k.myembro.engine.edit.FontKind.INKSTITCH, decoded.fontKind)
        assertEquals(pattern.stitches, decoded.localPattern().stitches)
        val missing = text.copy(fontId = "not-installed").localPattern()
        assertEquals(0, missing.stitchCount)
        assertTrue(missing.warnings.isNotEmpty())
    }

    @Test fun alignmentShiftsShorterLines() {
        val left = InkStitchLettering.layout(font, "HI\nH", 10.0, alignment = TextAlignment.LEFT)
        val center = InkStitchLettering.layout(font, "HI\nH", 10.0, alignment = TextAlignment.CENTER)
        val right = InkStitchLettering.layout(font, "HI\nH", 10.0, alignment = TextAlignment.RIGHT)
        // Ink/Stitch text_align moves each line left by half (center) or all (right) of its own ink width.
        val shifts = left.indices.map { center[it].dx - left[it].dx }
        assertTrue(shifts.all { it < 0 })
        assertTrue(shifts[0] < shifts[2], "the wider first line moves further")
        left.indices.forEach { assertEquals(2 * shifts[it], right[it].dx - left[it].dx, 1e-9) }
        assertEquals(100 * left[0].scale, left[2].dy - left[0].dy, 1e-9, "font.json leading defaults to 100 px")
        val spaced = InkStitchLettering.layout(font, "HI\nH", 10.0, lineHeightMm = 20.0)
        assertEquals(20.0, spaced[2].dy - spaced[0].dy, 1e-9)
    }

    @Test fun everySecondLineIsSewnBackwards() {
        val glyphs = InkStitchLettering.layout(font, "HI\nHI\nHI", 10.0).map { it.glyph.name }
        assertEquals(listOf("H", "I", "I", "H", "H", "I"), glyphs)
        val forward = InkStitchLettering.layout(font, "HI\nHI", 10.0, backAndForth = false).map { it.glyph.name }
        assertEquals(listOf("H", "I", "H", "I"), forward)
        val oneWay = InkStitchFont(font.info.copy(reversible = false), font.glyphs, font.baseline, font.unsupported)
        assertEquals(forward, InkStitchLettering.layout(oneWay, "HI\nHI", 10.0).map { it.glyph.name })
    }
}
