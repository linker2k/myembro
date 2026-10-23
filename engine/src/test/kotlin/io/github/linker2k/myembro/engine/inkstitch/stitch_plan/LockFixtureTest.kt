// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitch_plan/lock_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.inkstitch.svg.PathEndPoints
import io.github.linker2k.myembro.engine.io.PecWriter
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

/** Reproducible source-parameterized fixtures, not an assertion of external quality-gate approval. */
class LockFixtureTest {
    @Test fun writesEverySourceLockTypeAndActualDefaultFromOneFixedSvg() {
        val output = File(System.getProperty("myembro.lock.fixture.dir") ?: "build/inkstitch-locks").apply { mkdirs() }
        val svg = checkNotNull(javaClass.getResourceAsStream("/inkstitch/locks/locks.svg")).use { it.readBytes() }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder().parse(svg.inputStream())
        val nodes = document.getElementsByTagNameNS("http://www.w3.org/2000/svg", "path")
        val names = mutableSetOf<String>()
        val manifest = StringBuilder("fixture\tlock_start\tlock_end\tstart_mm\tend_mm\tstart_percent\tend_percent\tbytes\n")
        File(output, "locks.svg").writeBytes(svg)
        for (index in 0 until nodes.length) {
            val node = nodes.item(index) as Element
            val name = node.getAttribute("id")
            names += name
            val attrs = (0 until node.attributes.length).associate { i -> node.attributes.item(i).let { it.nodeName to it.nodeValue } }
            val parameters = LockStitchParameters.fromAttributes(attrs)
            val body = PathEndPoints.parse(node.getAttribute("d")).map { Stitch(it) }
            val locks = LockStitches.fromParameters(parameters)
            val thread = EmbThread(0xFFB5492A.toInt(), name)
            val groups = listOf(StitchGroup(thread, body, lockStart = locks.start, lockEnd = locks.end, forceLockStitches = parameters.force_lock_stitches))
            assertTrue(body.zipWithNext().all { (a, b) -> a.distance(b) < 3.0 * io.github.linker2k.myembro.engine.inkstitch.svg.Units.PIXELS_PER_MM })
            val pattern = GeneratedPatternProcessor.fromGroups(groups)
            val expected = PecWriter.normalize(pattern)
            val bytes = PesWriter.write(pattern)
            val restored = PesReader.read(bytes)
            File(output, "$name.pes").writeBytes(bytes)
            File(output, "$name.pes.json").writeText(buildJsonObject {
                put("fixture", name); put("svg", "locks.svg#$name")
                put("sourceCommit", "b0edd96311ece82ee48816dc93466274b12c2a9c")
                put("source", patternJson(pattern)); put("expected", patternJson(expected))
                put("parameters", JsonObject(attrs.filterKeys { it.startsWith("inkstitch:") }.mapValues { JsonPrimitive(it.value) }))
            }.toString())
            assertEquals(expected.stitches, restored.stitches, name)
            assertEquals(expected.threads, restored.threads, name)
            assertTrue(pattern.metadata[GeneratedPatternProcessor.LOCK_INDICES_KEY]!!.isNotEmpty(), name)
            assertTrue(bytes.size > 512, name)
            manifest.append("$name\t${parameters.lock_start}\t${parameters.lock_end}\t${parameters.lock_start_scale_mm}\t${parameters.lock_end_scale_mm}\t${parameters.lock_start_scale_percent}\t${parameters.lock_end_scale_percent}\t${bytes.size}\n")
        }
        assertEquals(LockStitches.types.toSet() + "default", names)
        assertContentEquals(File(output, "half_stitch.pes").readBytes(), File(output, "default.pes").readBytes())
        File(output, "manifest.tsv").writeText(manifest.toString())
    }

    private fun patternJson(pattern: EmbPattern): JsonObject = buildJsonObject {
        put("stitches", JsonArray(pattern.stitches.map { buildJsonArray { add(it.x); add(it.y); add(it.command.name) } }))
        put("threads", JsonArray(pattern.threads.map { JsonPrimitive(it.color and 0xFFFFFF) }))
        put("metadata", JsonObject(pattern.metadata.mapValues { JsonPrimitive(it.value) }))
    }
}
