// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.*
import javax.xml.parsers.DocumentBuilderFactory
import java.util.Locale
import kotlin.test.*

class ImageElementsTest {
    private fun solid(w: Int, h: Int, widthMm: Double = w.toDouble()) =
        ImagePipeline.trace(RasterImage(w, h, IntArray(w * h) { 0xFF233A99.toInt() }),
            ImageTraceOptions(widthMm, minimumRegionAreaMm2 = 0.0, simplifyToleranceMm = 0.0))

    @Test fun wideAreaThinColumnAndLineHaveDifferentTypesAndExactUpstreamDefaults() {
        val fill = solid(20, 20).elements.single()
        assertEquals(ImageStitchType.FILL, fill.type)
        assertEquals("0.25", fill.parameters["row_spacing_mm"])
        assertEquals("tatami_fill", fill.parameters["fill_method"])
        assertEquals("4.0", fill.parameters["max_stitch_length_mm"])
        val satin = solid(30, 3).elements.single()
        assertEquals(ImageStitchType.SATIN, satin.type)
        assertEquals("0.4", satin.parameters["zigzag_spacing_mm"])
        assertEquals("true", satin.parameters["satin_column"])
        val running = solid(100, 2, widthMm = 20.0).elements.single()
        assertEquals(ImageStitchType.RUNNING, running.type)
        assertEquals("2.5", running.parameters["running_stitch_length_mm"])
        assertEquals("running_stitch", running.parameters["stroke_method"])
    }

    @Test fun verticalAndHorizontalColumnsHaveCoveredParallelRailsAndCorrectWidth() {
        for ((w, h) in listOf(30 to 3, 3 to 30)) {
            val element = solid(w, h).elements.single()
            val (a, b) = element.rails!!
            assertTrue(element.region.geometry.coversSegment(a.points.first(), a.points.last()))
            assertTrue(element.region.geometry.coversSegment(b.points.first(), b.points.last()))
            assertEquals(30.0, a.points.first().distance(b.points.first()), 1e-8)
            assertEquals(300.0, a.points.first().distance(a.points.last()), 1e-8)
            assertTrue(element.region.geometry.coversSegment(element.centerLine!!.points.first(), element.centerLine.points.last()))
        }
    }

    @Test fun notchBranchAndHoleNeverInventUnsafeSatinRails() {
        val pixels = IntArray(30 * 3) { i -> if (i % 30 == 15 && i / 30 == 0) 0 else 0xFF112233.toInt() }
        val result = ImagePipeline.trace(RasterImage(30, 3, pixels), ImageTraceOptions(30.0,
            minimumRegionAreaMm2 = 0.0, simplifyToleranceMm = 0.0))
        assertEquals(ImageStitchType.FILL, result.elements.single().type)
        assertTrue(ImageTraceNotice.COLUMN_REQUIRES_REVIEW in result.vector.notices)
        val branched = IntArray(20 * 20) { i -> if (i % 20 in 9..10 || i / 20 in 9..10) 0xFF112233.toInt() else 0 }
        assertEquals(ImageStitchType.FILL, ImagePipeline.trace(RasterImage(20, 20, branched),
            ImageTraceOptions(20.0)).elements.single().type)
    }

    @Test fun svgHandoffIsNamespaceAwareMillimetersAndLocaleIndependent() {
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            for (draft in listOf(solid(20, 20), solid(30, 3), solid(100, 2, 20.0))) {
                val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder().parse(draft.toSvg().byteInputStream())
                val root = xml.documentElement
                assertEquals("${draft.vector.widthMm.toInt()}mm", root.getAttribute("width"))
                val node = root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "path").item(0) as org.w3c.dom.Element
                val element = draft.elements.single()
                for ((key, value) in element.parameters) assertEquals(value, node.getAttributeNS("http://inkstitch.org/namespace", key))
                val geometry = SvgPath.flatten(node.getAttribute("d"), tolerance = 0.001)
                assertEquals(if (element.type == ImageStitchType.SATIN) 2 else 1, geometry.size)
                if (element.type == ImageStitchType.FILL) {
                    val fromSvg = SvgPath.polygons(node.getAttribute("d"), FillRule.EVEN_ODD)
                    assertEquals(element.region.areaMm2, fromSvg.area, 1e-8)
                }
                assertEquals(listOf(element.type), draft.elements.map { it.type })
            }
        } finally { Locale.setDefault(locale) }
    }

    @Test fun svgParameterValuesAreEscapedAndNamesValidated() {
        val draft = solid(20, 20)
        val escaped = draft.copy(elements = listOf(draft.elements.single().copy(parameters = mapOf("note" to "\"<&"))))
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(escaped.toSvg().byteInputStream())
        val path = xml.getElementsByTagName("path").item(0) as org.w3c.dom.Element
        assertEquals("\"<&", path.getAttribute("inkstitch:note"))
        assertFailsWith<IllegalArgumentException> {
            draft.copy(elements = listOf(draft.elements.single().copy(parameters = mapOf("bad\"=" to "x")))).toSvg()
        }
    }
}
