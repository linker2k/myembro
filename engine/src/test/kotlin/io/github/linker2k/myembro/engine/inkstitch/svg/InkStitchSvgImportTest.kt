// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.inkstitch.elements.InkFill
import io.github.linker2k.myembro.engine.inkstitch.elements.InkRun
import io.github.linker2k.myembro.engine.inkstitch.elements.InkSatin
import io.github.linker2k.myembro.engine.model.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InkStitchSvgImportTest {
    private fun svg(body: String, root: String = "") = """<svg xmlns="http://www.w3.org/2000/svg"
        xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:inkstitch="http://inkstitch.org/namespace"
        xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" $root>$body</svg>"""

    private fun read(text: String) = InkStitchSvgImport.read(text.byteInputStream())

    private fun rejection(text: String) = assertFailsWith<SvgImportException> { InkStitchSvgImport.open(text.byteInputStream()) }.code

    @Test fun refusesWhatTheReleaseGateRefuses() {
        assertEquals(SvgRejection.INVALID_INPUT, rejection("<svg><path d=\"M0 0 L10 10\"/>"))
        assertEquals(SvgRejection.UNSUPPORTED_EXTERNAL_RESOURCE,
            rejection("""<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/hostname">]><svg xmlns="http://www.w3.org/2000/svg"><text>&x;</text></svg>"""))
        assertEquals(SvgRejection.INVALID_INPUT, rejection("<html/>"))
        assertEquals(SvgRejection.CYCLIC_REFERENCE,
            rejection(svg("""<defs><g id="a"><use href="#b"/></g><g id="b"><use href="#a"/></g></defs><use href="#a"/>""")))
        assertEquals(SvgRejection.CYCLIC_REFERENCE, rejection(svg("""<g id="a"><use href="#a"/></g>""")))
        assertEquals(SvgRejection.MISSING_REFERENCE, rejection(svg("""<use href="#nothing"/>""")))
        assertEquals(SvgRejection.UNSUPPORTED_EXTERNAL_RESOURCE, rejection(svg("""<use href="other.svg#a"/>""")))
        assertEquals(SvgRejection.INVALID_GEOMETRY, rejection(svg("""<text>only text</text>""")))
    }

    @Test fun fillsInheritGroupStyleAndUseTheViewBox() {
        // 100 user units on a 10 mm page: Ink/Stitch maps by the viewBox, translating by the unscaled origin.
        val direct = read(svg("""<path d="M0 0 H100 V50 H0 Z" fill="#b74e35" fill-rule="evenodd"/>""",
            """width="10mm" height="5mm" viewBox="-10 -10 100 50""""))
        val inherited = read(svg("""<g fill="#b74e35" fill-rule="evenodd"><path d="M0 0 H100 V50 H0 Z"/></g>""",
            """width="10mm" height="5mm" viewBox="-10 -10 100 50""""))
        val a = direct.elements.single() as InkFill
        val b = inherited.elements.single() as InkFill
        assertEquals(a.paths, b.paths)
        assertEquals(a.color, b.color)
        assertEquals(io.github.linker2k.myembro.engine.geometry.FillRule.EVEN_ODD, b.rule)
        val k = 10 * 96 / 25.4 / 100
        assertEquals(10.0, a.paths.single().points.minOf { it.x }, 1e-9)
        assertEquals(100 * k + 10, a.paths.single().points.maxOf { it.x }, 1e-9)
    }

    @Test fun groupTransformsCompose() {
        val e = read(svg("""<g transform="translate(10,5)"><g transform="scale(2)"><rect width="10" height="10"/></g></g>"""))
            .elements.single() as InkFill
        val xs = e.paths.single().points.map { it.x }
        assertEquals(10.0, xs.min(), 1e-9); assertEquals(30.0, xs.max(), 1e-9)
    }

    @Test fun classifiesLikeNodeToElements() {
        val e = read(svg("""
            <path d="M0 0 H10" style="fill:none;stroke:#00f"/>
            <path d="M0 0 V10 M2 0 V10" style="fill:none;stroke:#f00" inkstitch:satin_column="true"/>
            <path d="M0 0 H10" style="fill:none;stroke:#0f0;stroke-width:0.5" inkstitch:satin_column="true"/>
            <circle cx="5" cy="5" r="5" style="fill:red;fill-opacity:0;stroke:black"/>
            <path d="M0 0 H10 V10 Z" style="fill:#123;display:none"/>
            <g style="display:none"><rect width="5" height="5"/></g>
            <defs><rect width="5" height="5"/></defs>
            <path d="M0 0 H10 V10 Z" style="fill:#123" inkstitch:fill_method="meander_fill"/>
            <path d="M0 0 H10" style="stroke:#000;marker-start:url(#inkstitch-pattern-marker)"/>
            <image width="5" height="5"/>
        """)).let { it.elements to it.unsupported }
        val (elements, unsupported) = e
        assertTrue(elements[0] is InkRun)
        assertTrue(elements[1] is InkSatin)
        assertTrue(elements[2] is InkRun, "a single thin satin path sews as running stitch")
        assertTrue(elements[3] is InkRun, "fill-opacity 0 has no fill")
        assertEquals(4, elements.size)
        assertEquals(mapOf("fill:meander_fill" to 1, "image" to 1), unsupported)
    }

    @Test fun commandsAndParametersReachTheStitches() {
        val doc = svg("""
            <defs><symbol id="inkstitch_trim"><path d="M0 0"/></symbol><symbol id="inkstitch_ignore_object"><path d="M0 0"/></symbol></defs>
            <path id="a" d="M0 0 H20" style="fill:none;stroke:#000"/>
            <path id="b" d="M21 0 H40" style="fill:none;stroke:#000"/>
            <path id="c" d="M0 50 H40" style="fill:none;stroke:#000"/>
            <use id="t" xlink:href="#inkstitch_trim" x="0" y="0"/>
            <path d="M0 0 L1 1" inkscape:connection-start="#t" inkscape:connection-end="#a" style="stroke:#000"/>
            <use id="i" xlink:href="#inkstitch_ignore_object"/>
            <path d="M0 0 L1 1" inkscape:connection-start="#i" inkscape:connection-end="#c" style="stroke:#000"/>
        """, """width="100" height="100"""")
        val result = InkStitchSvgImport.open(doc.byteInputStream())
        assertEquals(2, result.elements.elements.size, "ignored object, symbols and connectors are not sewn")
        assertEquals("true", result.elements.elements[0].params["trim_after"])
        // a and b are 1 px apart, so they would be sewn across without the trim command.
        assertEquals(1, result.pattern.stitches.count { it.command == Command.TRIM })
    }

    @Test fun metadataSetsDocumentDefaults() {
        val m = read(svg("""<metadata><inkstitch:collapse_len_mm>5</inkstitch:collapse_len_mm>
            <inkstitch:min_stitch_len_mm>0.3</inkstitch:min_stitch_len_mm></metadata><rect width="10" height="10"/>""")).metadata
        assertEquals(5.0, m.collapseLenMm); assertEquals(0.3, m.minStitchLenMm); assertEquals(1.0, m.minSatinStrokeWidthMm)
    }
}
