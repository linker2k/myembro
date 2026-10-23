// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.digitizing.*
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.lettering.SatinColumn
import io.github.linker2k.myembro.engine.lettering.SatinOptions
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import java.awt.Color
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

/** Fixed SVG -> test-only rasterizer -> production preprocessing -> legacy stitch generator.
 * This bridge deliberately has no substitute for the pending shared plan finisher. Its raw PES
 * outputs are for independent gate review; passing this test does not claim sew-out readiness.
 */
class ImagePipelineFixtureTest {
    @Test fun fixedSvgInputsPreserveGeometryClassificationAndProduceReviewablePes() {
        val root = File("build/image-pipeline").apply { mkdirs() }
        val cases = linkedMapOf(
            "running-horizontal" to ImageStitchType.RUNNING, "running-vertical" to ImageStitchType.RUNNING,
            "satin-horizontal" to ImageStitchType.SATIN, "satin-vertical" to ImageStitchType.SATIN,
            "fill-square" to ImageStitchType.FILL, "fill-donut" to ImageStitchType.FILL, "fill-noise" to ImageStitchType.FILL,
        )
        val rows = mutableListOf<String>()
        for ((name, type) in cases) {
            val source = checkNotNull(javaClass.getResourceAsStream("/image-pipeline/$name.svg"))
            val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source)
            val svg = xml.documentElement
            val widthMm = svg.getAttribute("width").removeSuffix("mm").toDouble()
            val heightMm = svg.getAttribute("height").removeSuffix("mm").toDouble()
            val image = BufferedImage((widthMm * 10).toInt(), (heightMm * 10).toInt(), BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                graphics.scale(10.0, 10.0)
                val paths = svg.getElementsByTagName("path")
                for (i in 0 until paths.length) {
                    val path = paths.item(i) as org.w3c.dom.Element
                    val shape = Path2D.Double(Path2D.WIND_EVEN_ODD)
                    for (line in SvgPath.flatten(path.getAttribute("d"), tolerance = 0.001)) {
                        shape.moveTo(line.points.first().x, line.points.first().y)
                        line.points.drop(1).forEach { shape.lineTo(it.x, it.y) }
                        if (line.closed) shape.closePath()
                    }
                    graphics.color = Color(path.getAttribute("fill").removePrefix("#").toInt(16))
                    graphics.fill(shape)
                }
            } finally { graphics.dispose() }
            val raster = RasterImage(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
            val draft = ImagePipeline.trace(raster, ImageTraceOptions(widthMm, minimumRegionAreaMm2 = 0.5))
            assertEquals(type, draft.elements.single().type, name)
            assertEquals(heightMm, draft.vector.heightMm, 1e-8)
            if (name == "fill-noise") assertEquals(1, draft.vector.removedComponentCount)
            if (name == "fill-donut") assertEquals(800.0, draft.vector.regions.single().areaMm2, 1e-7)
            val element = draft.elements.single()
            val pattern = legacyCandidate(element)
            assertTrue(pattern.stitchCount > 10, name)
            assertEquals(Command.END, pattern.stitches.last().command)
            val pes = PesWriter.writeV6(pattern)
            val decoded = PesReader.read(pes)
            assertEquals(pattern.stitchCount, decoded.stitchCount, name)
            assertEquals(pattern.bounds, decoded.bounds, name)
            File(root, "$name.traced.svg").writeText(draft.toSvg())
            val output = File(root, "$name.pes").apply { writeBytes(pes) }
            val style = when (type) { ImageStitchType.FILL -> "tatami"; ImageStitchType.SATIN -> "satin"; ImageStitchType.RUNNING -> "running" }
            // The external gate's schema says font/height; here those identify the SVG fixture and
            // source canvas height, not an invented font or an untested lettering combination.
            rows += """{"font":"$name","height_mm":$heightMm,"style":"$style","path":"${output.absolutePath}"}"""
            File(root, "$name.matrix.json").writeText("""{"axes":{"fonts":["$name"],"heights_mm":[$heightMm],"styles":["$style"]},"cases":[${rows.last()}]}""")
        }
        File(root, "outputs.json").writeText("[${rows.joinToString(",\n")}]")
    }

    private fun legacyCandidate(element: ImageElement): EmbPattern = when (element.type) {
        ImageStitchType.FILL -> TatamiFill.generate(element.region.geometry, TatamiSettings(
            rowSpacing = element.parameters.getValue("row_spacing_mm").toDouble() * 10,
            stitchLength = element.parameters.getValue("max_stitch_length_mm").toDouble() * 10), element.region.color.thread)
        ImageStitchType.RUNNING -> RunningStitch.generate(listOf(element.centerLine!!),
            RunningSettings(element.parameters.getValue("running_stitch_length_mm").toDouble() * 10), element.region.color.thread)
        ImageStitchType.SATIN -> {
            val (a, b) = element.rails!!
            val pattern = SatinColumn.generate(a.points.map { it.x / 10 to it.y / 10 }, b.points.map { it.x / 10 to it.y / 10 },
                SatinOptions(densityMm = element.parameters.getValue("zigzag_spacing_mm").toDouble(), autoDensity = false), element.region.color.thread)
            // Legacy SatinColumn recenters; preserve the image's original placement in this bridge.
            val bounds = pattern.bounds!!
            val x = ((a.points.first().x + b.points.last().x) / 2 - (bounds.minX + bounds.maxX) / 2.0).toInt()
            val y = ((a.points.first().y + b.points.last().y) / 2 - (bounds.minY + bounds.maxY) / 2.0).toInt()
            EmbPattern(pattern.stitches.map { it.copy(x = it.x + x, y = it.y + y) }, pattern.threads, pattern.metadata)
        }
    }
}
