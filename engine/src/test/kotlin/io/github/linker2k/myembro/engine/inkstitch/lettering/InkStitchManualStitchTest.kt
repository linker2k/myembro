// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.lettering

import io.github.linker2k.myembro.engine.model.Command
import java.io.File
import kotlin.math.abs
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class InkStitchManualStitchTest {
    private fun font(path: String, glyph: String = "A") = InkStitchFont.parse(
        """<svg xmlns="http://www.w3.org/2000/svg" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" xmlns:inkstitch="http://inkstitch.org/namespace" width="40" height="40"><g inkscape:label="GlyphLayer-$glyph">$path</g></svg>""".byteInputStream(),
        """{"name":"manual","reversible":false}""")

    @Test fun manualStitchSewsNodesNotTheFlattenedCurve() {
        val f = font("""<path inkstitch:stroke_method="manual_stitch" style="fill:none;stroke:#000000" d="M 0,0 C 10,-20 30,-20 40,0 L 40,30"/>""")
        assertTrue(f.unsupported.isEmpty(), "${f.unsupported}")
        val run = f.glyphs.getValue("A").elements.single() as InkRun
        assertEquals(listOf(listOf(0.0 to 0.0, 40.0 to 0.0, 40.0 to 30.0)), run.paths.map { p -> p.points.map { it.x to it.y } })
        assertEquals("manual_stitch", run.params["stroke_method"])
    }

    @Test fun legacyManualFlagIsTheSameMethod() {
        val f = font("""<path inkstitch:manual_stitch="true" style="fill:none;stroke:#000000" d="M 0,0 L 10,0 L 10,10"/>""")
        assertEquals("manual_stitch", (f.glyphs.getValue("A").elements.single() as InkRun).params["stroke_method"])
    }

    @Test fun renderedNeedlePointsAreTheScaledNodes() {
        // 40 px tall 'H' at 10 mm: one node per 2.5 mm, so every stitch lies on a node.
        val f = font("""<path inkstitch:stroke_method="manual_stitch" style="fill:none;stroke:#000000" d="M 0,0 L 0,40 L 8,40 L 8,0"/>""", "H")
        val pattern = InkStitchLettering.render(f, "H", 10.0, forceBoundaryLocks = false)
        val sewn = pattern.stitches.filter { it.command == Command.STITCH }.map { it.x to it.y }.distinct()
        val x0 = sewn.minOf { it.first }; val y0 = sewn.minOf { it.second }
        assertEquals(setOf(0 to 0, 0 to 100, 20 to 100, 20 to 0), sewn.map { (it.first - x0) to (it.second - y0) }.toSet())
    }

    @Test fun referenceFallsBackToGreekEtaThenTallestLetter() {
        val path = { h: Int -> """<path style="fill:none;stroke:#000000" d="M 0,0 L 0,$h"/>""" }
        val svg = { layers: String -> InkStitchFont.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" width="40" height="40">$layers</svg>""".byteInputStream(), """{"name":"r"}""") }
        val greek = svg("""<g inkscape:label="GlyphLayer-Η">${path(20)}</g><g inkscape:label="GlyphLayer-Λ">${path(30)}</g>""")
        assertEquals("Η", greek.referenceGlyph?.name)
        // Letters beat taller punctuation; equal heights resolve to the lowest code point.
        val lower = svg("""<g inkscape:label="GlyphLayer-(">${path(40)}</g><g inkscape:label="GlyphLayer-q">${path(30)}</g><g inkscape:label="GlyphLayer-p">${path(30)}</g>""")
        assertEquals("p", lower.referenceGlyph?.name)
    }

    /**
     * Opt-in oracle: INKSTITCH_FONTS=<embroidery-fonts>/src INKSTITCH_MANUAL_ORACLE=<dir of <font>.json from
     * tools/inkstitch_manual_oracle.py>. Node lists must match upstream inkex strip_control_points.
     */
    @Test fun nodesMatchUpstreamOracle() {
        val src = System.getenv("INKSTITCH_FONTS")?.let(::File) ?: return
        val oracle = System.getenv("INKSTITCH_MANUAL_ORACLE")?.let(::File) ?: return
        for (file in oracle.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }) {
            val dir = File(src, file.nameWithoutExtension)
            val font = File(dir, "ltr.svg").inputStream().use { InkStitchFont.parse(it, File(dir, "font.json").readText()) }
            val expected = Json.parseToJsonElement(file.readText()).jsonObject
            var paths = 0; var nodes = 0; var worst = 0.0
            for ((glyph, list) in expected) {
                val want = list.jsonArray.map { p -> p.jsonArray.map { q -> q.jsonArray.let { it[0].jsonPrimitive.double to it[1].jsonPrimitive.double } } }
                val got = font.glyphs[glyph]?.elements.orEmpty().filterIsInstance<InkRun>()
                    .filter { it.params["stroke_method"] == "manual_stitch" }.flatMap { r -> r.paths.map { p -> p.points.map { it.x to it.y } } }
                assertEquals(want.map { it.size }, got.map { it.size }, "${dir.name} '$glyph' node counts")
                for ((a, b) in want.flatten().zip(got.flatten())) worst = maxOf(worst, abs(a.first - b.first), abs(a.second - b.second))
                paths += want.size; nodes += want.sumOf { it.size }
            }
            assertTrue(worst < 1e-3, "${dir.name}: worst node difference $worst px")
            println("MANUAL_ORACLE PASS ${dir.name} paths=$paths nodes=$nodes worstPx=${"%.2e".format(worst)}")
        }
    }
}
