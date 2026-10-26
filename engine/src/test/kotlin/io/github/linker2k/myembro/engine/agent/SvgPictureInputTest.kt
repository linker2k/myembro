// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.inkstitch.svg.*
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.sewout.*
import java.io.File
import kotlin.test.*

class SvgPictureInputTest {
    private val start = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
    private val valid = start + "<rect width=\"40\" height=\"40\" fill=\"#ffcc00\"/><rect x=\"60\" y=\"60\" width=\"30\" height=\"30\" fill=\"#000000\"/></svg>"
    @Test fun twoColorsUseExistingVectorDefaultsAndPassSewout() {
        val safe = SvgPictureInput.parse(valid.toByteArray())
        val pattern = VectorArt.render(safe.art, 30.0).pattern
        assertEquals(2, pattern.colorBlocks.size)
        val pes = PesWriter.writeV1(pattern, HoopSpec.H100)
        val report = SewoutCheck.checkPes(pes, options = SewoutOptions(hoop = HoopSpec.H100, origin = DesignOrigin.APP_GENERATED))
        assertTrue(report.analysisComplete && !report.hasBlockingIssues, report.findings.toString())
        assertEquals(VectorArt.render(VectorArt.parseSvg(valid), 30.0).pattern.stitches, pattern.stitches)
        println("SVG_PICTURE 2 colors -> VectorArt defaults -> PES sewout PASS; stitches=${pattern.stitchCount}")
    }
    @Test fun corpusMaximaAreMeasuredAndAllBundledFilesPass() {
        val rows = File("../app/src/main/assets/emoji/bundled").listFiles()!!.filter { it.extension == "svg" }
            .map { SvgPictureInput.parse(it.readBytes()).metrics }
        assertEquals(30, rows.size)
        assertEquals(SvgPictureInput.MAX_BYTES, rows.maxOf { it.bytes })
        assertEquals(SvgPictureInput.MAX_ELEMENTS, rows.maxOf { it.elements })
        assertEquals(SvgPictureInput.MAX_PATH_POINTS, rows.maxOf { it.pathPoints })
        println("SVG_CORPUS 30/30 PASS; maxima bytes=4788 elements=14 points=202")
    }
    @Test fun boundaries() {
        for (size in listOf(SvgPictureInput.MAX_BYTES - 1, SvgPictureInput.MAX_BYTES, SvgPictureInput.MAX_BYTES + 1)) {
            val input = (valid + " ".repeat(size - valid.toByteArray().size)).toByteArray()
            if (size <= SvgPictureInput.MAX_BYTES) { assertEquals(size, SvgPictureInput.parse(input).metrics.bytes); assertEquals(size, SvgPictureInput.read(input.inputStream()).size) }
            else { assertFails { SvgPictureInput.parse(input) }; assertFails { SvgPictureInput.read(input.inputStream()) } }
        }
        for (n in listOf(13, 14, 15)) {
            val input = start + "<g/>".repeat(n - 2) + "<rect width=\"2\" height=\"2\"/></svg>"
            if (n <= 14) assertEquals(n, SvgPictureInput.parse(input.toByteArray()).metrics.elements) else assertFails { SvgPictureInput.parse(input.toByteArray()) }
        }
        for (n in listOf(201, 202, 203)) {
            val path = "M0 0 " + (1 until n).joinToString(" ") { "L${it % 100} ${it / 100}" }
            val input = "$start<path d=\"$path\"/></svg>".toByteArray()
            if (n <= 202) assertEquals(n, SvgPictureInput.parse(input).metrics.pathPoints) else assertFails { SvgPictureInput.parse(input) }
        }
        println("SVG_LIMITS bytes/elements/points n-1,n,n+1 PASS")
    }
    @Test fun refusesXxeReferencesScriptsAndUnsupportedElements() {
        for (input in listOf(
            "<!DOCTYPE svg [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>$start<path d=\"&xxe;\"/></svg>",
            "<!DOCTYPE svg SYSTEM 'https://example.com/test.dtd'>$valid",
            start + "<script>alert(1)</script></svg>", start + "<foreignObject/></svg>",
            start + "<image href=\"data:image/png;base64,AA==\"/></svg>",
            start + "<g href=\"https://example.com/x.svg\"/></svg>",
            start + "<g xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:href=\"file:///tmp/x\"/></svg>",
            start + "<rect width=\"2\" height=\"2\" style=\"fill:URL(https://example.com/x)\"/></svg>",
            start + "<text>Hello</text></svg>", start + "<rect onload=\"alert(1)\"/></svg>")) assertFails(input) { SvgPictureInput.parse(input.toByteArray()) }
        println("SVG_UNTRUSTED XXE/external references/script/foreignObject/image/text/events PASS")
    }
}
