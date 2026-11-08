// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.lettering

import io.github.linker2k.myembro.engine.model.Command
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class InkStitchVariantFilesTest {
    /** One 40 px page; the baseline guide is stored y-up from the page bottom, as Inkscape writes it. */
    private fun svg(baselineY: Double, vararg glyphs: Pair<String, String>) = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" xmlns:sodipodi="http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd" width="40" height="40"><sodipodi:namedview><sodipodi:guide inkscape:label="baseline" position="0,${40 - baselineY}"/></sodipodi:namedview>""" +
        glyphs.joinToString("") { (name, d) -> """<g inkscape:label="GlyphLayer-$name"><path style="fill:none;stroke:#000000" d="$d"/></g>""" } + "</svg>"

    private fun dir(files: Map<String, String>): File = Files.createTempDirectory("variant").toFile().apply {
        File(this, "font.json").writeText("""{"name":"variant","reversible":false}""")
        File(this, "ltr").mkdirs()
        files.forEach { (name, text) -> File(this, "ltr/$name").writeText(text) }
        deleteOnExit()
    }

    @Test fun eachGlyphSitsOnItsOwnFilesBaseline() {
        // 'H' stands on y=30 in one file, 'x' on y=20 in the other: both must sew on one line.
        val d = dir(mapOf("a.svg" to svg(30.0, "H" to "M 0,10 L 0,30 L 8,30"), "b.svg" to svg(20.0, "x" to "M 0,10 L 0,20 L 6,20")))
        val font = InkStitchFont.parse(d)
        assertEquals(30.0, font.glyphs.getValue("H").baseline!!, 1e-9)
        assertEquals(20.0, font.glyphs.getValue("x").baseline!!, 1e-9)
        val pattern = InkStitchLettering.render(font, "Hx", 10.0, forceBoundaryLocks = false)
        val sewn = pattern.stitches.filter { it.command == Command.STITCH }
        // The first stitch is H's top, the last is x's right foot; each foot is on its glyph's baseline.
        val hFoot = sewn.filter { it.x == sewn.first().x }.maxOf { it.y }
        assertEquals(hFoot, sewn.last().y, "H foot $hFoot, x foot ${sewn.last().y}")
        assertTrue(sewn.last().x > sewn.first().x)
    }

    @Test fun filesAreReadInNameOrderAndLaterFilesWin() {
        val d = dir(mapOf("zbi.svg" to svg(30.0, "A" to "M 0,0 L 0,30"), "mono.svg" to svg(30.0, "A" to "M 0,20 L 0,30", "H" to "M 0,0 L 0,30")))
        assertEquals(listOf("mono.svg", "zbi.svg"), InkStitchFont.variantFiles(d).map { it.name })
        val a = InkStitchFont.parse(d).glyphs.getValue("A")
        assertEquals(0.0, a.inkTop, 1e-9)
    }

    @Test fun singleFileFontsKeepLtrSvg() {
        val d = dir(emptyMap()).apply { File(this, "ltr.svg").writeText(svg(30.0, "H" to "M 0,0 L 0,30")) }
        assertEquals(listOf("ltr.svg"), InkStitchFont.variantFiles(d).map { it.name })
        assertTrue(InkStitchFont.variantFiles(File(d, "missing")).isEmpty())
    }
}
