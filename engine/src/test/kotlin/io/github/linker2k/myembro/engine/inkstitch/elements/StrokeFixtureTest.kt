// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/stroke.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbThread
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

/** Produces reproducible SVG -> groups -> common plan -> PES inputs for independent quality review. */
class StrokeFixtureTest {
    @Test fun writesSourceParameterizedSvgFixturesForExternalGate() {
        val output = File(System.getProperty("myembro.stroke.fixture.dir") ?: "build/inkstitch-stroke").apply { mkdirs() }
        val manifest = StringBuilder("fixture\tgroups\tplan_stitches\tpes_bytes\n")
        for (name in listOf("running", "manual", "zigzag", "bean")) {
            val resource = checkNotNull(javaClass.getResourceAsStream("/inkstitch/stroke/$name.svg"))
            val sourceBytes = resource.use { it.readBytes() }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val document = factory.newDocumentBuilder().parse(sourceBytes.inputStream())
            val node = document.getElementsByTagNameNS("http://www.w3.org/2000/svg", "path").item(0)
            val attributes = (0 until node.attributes.length).associate { i -> node.attributes.item(i).let { it.nodeName to it.nodeValue } }
            val parameters = StrokeParameters.fromAttributes(attributes)
            val paths = SvgPath.flatten(attributes.getValue("d"), tolerance = 0.01).map { line -> line.points.map { Point(it.x, it.y) } }
            val locks = LockStitches.fromParameters(LockStitchParameters(force_lock_stitches = parameters.force_lock_stitches))
            val rgb = attributes.getValue("stroke").removePrefix("#").toInt(16) or 0xff000000.toInt()
            val groups = Stroke(paths, EmbThread(rgb, name), parameters,
                strokeWidth = attributes.getValue("stroke-width").toDouble(), elementId = attributes.getValue("id"),
                lockStart = locks.start, lockEnd = locks.end).toStitchGroups(null)
            val plan = StitchPlanBuilder.fromGroups(groups)
            val pattern = GeneratedPatternProcessor.fromGroups(groups)
            val bytes = PesWriter.write(pattern)
            File(output, "$name.svg").writeBytes(sourceBytes)
            File(output, "$name.pes").writeBytes(bytes)
            manifest.append("$name\t${groups.size}\t${plan.colorBlocks.sumOf { it.stitches.size }}\t${bytes.size}\n")
            assertTrue(bytes.size > 512)
            assertEquals(pattern.stitches.last().x, PesReader.read(bytes).stitches.last().x)
            assertTrue(groups.all { it.lockStart != null && it.lockEnd != null })
        }
        File(output, "manifest.tsv").writeText(manifest.toString())
    }
}
