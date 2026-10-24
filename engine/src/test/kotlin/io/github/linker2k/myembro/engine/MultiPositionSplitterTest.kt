// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import java.io.File
import kotlin.test.*

class MultiPositionSplitterTest {
    // Synthetic 100x170 coverage for algorithm tests, NOT a claim about SA434 mounting offsets.
    private val positions = listOf(SewingPosition("top", "위", 0, -350), SewingPosition("middle", "중앙", 0, 0), SewingPosition("bottom", "아래", 0, 350))
    private val red = PecThreads.forIndex(5)
    private val blue = PecThreads.forIndex(2)
    private fun design() = EmbPattern((-850..850 step 50).mapIndexed { i, y ->
        Stitch(if (i % 2 == 0) -500 else 500, y, Command.STITCH)
    }, listOf(red), mapOf("name" to "test_100x170"))

    @Test fun largeDesignConservesNeedleDownCoordinatesWithinEach100mmWindow() {
        val source = design()
        val result = MultiPositionSplitter.split(source, positions)
        assertTrue(result.errors.isEmpty())
        assertEquals(source.stitchCount, result.sourceStitchCount)
        assertEquals(source.stitchCount, result.parts.sumOf { it.stitchCount })
        assertEquals(source.stitches.indices.toList(), result.parts.flatMap { it.sourceStitchIndices }.sorted())
        for (part in result.parts) {
            assertTrue(part.pattern.bounds!!.width <= 1000)
            assertTrue(part.pattern.bounds!!.height <= 1000)
            assertTrue(part.pattern.stitches.all { it.x in -500..500 && it.y in -500..500 })
            val back = part.pattern.stitches.filter { it.command == Command.STITCH }.map(part.position::toProject)
            assertEquals(part.sourceStitchIndices.map { source.stitches[it] }, back)
            assertEquals(Command.TRIM, part.pattern.stitches[part.pattern.stitches.lastIndex - 1].command)
        }
        assertTrue(result.warnings.single().contains("봉제 순서"))
    }

    @Test fun lookaheadKeepsAnOverlapRunInOneWindowRatherThanGreedySwitching() {
        val source = EmbPattern(listOf(0, 100, 200, 250).map { Stitch(0, it, Command.STITCH) }, listOf(red))
        val result = MultiPositionSplitter.split(source, listOf(SewingPosition("a", "a", 0, -400), SewingPosition("b", "b", 0, 400)))
        assertEquals(1, result.parts.size)
        assertEquals("b", result.parts.single().position.id)
        assertEquals(4, result.parts.single().stitchCount)
    }

    @Test fun transitionsAndSourceJumpsNeverSewAcrossRemovedRunsAndThreadsAreRetained() {
        val source = EmbPattern(listOf(
            Stitch(0, -800, Command.STITCH), Stitch(0, 800, Command.STITCH),
            Stitch(0, -800, Command.JUMP), Stitch(0, -750, Command.STITCH),
            Stitch(0, -750, Command.COLOR_CHANGE), Stitch(0, -700, Command.STITCH),
            Stitch(0, -700, Command.STOP), Stitch(0, -650, Command.STITCH),
        ), listOf(red, blue))
        val result = MultiPositionSplitter.split(source, positions)
        val top = result.parts.first()
        assertEquals(listOf(red, blue), top.colors)
        assertEquals(1, top.pattern.colorChangeCount)
        assertEquals(1, top.pattern.stopCount)
        assertEquals(4, top.stitchCount)
        assertTrue(top.pattern.jumpCount >= 3)
        assertTrue(top.pattern.trimCount >= 3)
        assertEquals(source.stitchCount, result.parts.sumOf { it.stitchCount })
    }

    @Test fun unassignedStitchesAreReportedAndCannotBeSilentlyExported() {
        val source = EmbPattern(listOf(Stitch(0, 0, Command.STITCH), Stitch(501, 0, Command.STITCH),
            Stitch(0, 0, Command.END), Stitch(9999, 0, Command.STITCH)), listOf(red))
        val result = MultiPositionSplitter.split(source, positions)
        assertEquals(2, result.sourceStitchCount)
        assertEquals(1, result.parts.sumOf { it.stitchCount })
        assertEquals(1, result.errors.single().sourceIndex)
        assertTrue(result.errors.single().message.contains("어느 프레임 위치"))
        assertFailsWith<IllegalArgumentException> { result.writePes("sample") }
    }

    @Test fun projectEntryEmptyWindowsValidationAndImmutableInput() {
        val source = design()
        val project = Project(objects = listOf(StitchObject("test", "test", source)))
        val result = MultiPositionSplitter.split(project, positions)
        assertEquals(source.stitchCount, result.parts.sumOf { it.stitchCount })
        assertEquals(35, source.stitchCount)
        assertTrue(MultiPositionSplitter.split(EmbPattern(emptyList(), emptyList()), positions).writePes("empty").isEmpty())
        assertFailsWith<IllegalArgumentException> { MultiPositionSplitter.split(source, emptyList()) }
        assertFailsWith<IllegalArgumentException> { SewingPosition("x", "x", 0, 0, width = 1700) }
        assertFailsWith<IllegalArgumentException> { result.writePes("../x") }
    }

    @Test fun multiplePesFilesRoundTripAndExportForPythonOracle() {
        val result = MultiPositionSplitter.split(design(), positions)
        val output = File("build/multiposition-check").apply { mkdirs() }
        for (version in PesVersion.entries) for (file in result.writePes("test", PesWriteSettings(version))) {
            assertTrue(file.fileName.matches(Regex("test_position[1-3]\\.pes")))
            assertEquals(file.part.ordinal.toString(), file.metadata["multiposition.index"])
            assertTrue(file.instructions.contains("프레임"))
            val back = PesReader.read(file.bytes)
            assertTrue(back.warnings.isEmpty())
            val expected = if (version == PesVersion.V6) PecWriter.normalizePreservingThreads(io.github.linker2k.myembro.engine.io.PesWriter.prepareForOutput(file.part.pattern)) else PecWriter.normalize(io.github.linker2k.myembro.engine.io.PesWriter.prepareForOutput(file.part.pattern))
            assertEquals(expected.stitches, back.stitches)
            assertEquals(expected.threads.map { it.color }, back.threads.map { it.color })
            if (version == PesVersion.V6) assertTrue(back.metadata.getValue("comments").contains("origin="))
            val path = File(output, "${version}_${file.fileName}")
            path.writeBytes(file.bytes)
            fun stitches(p: EmbPattern) = p.stitches.joinToString(",", "[", "]") { "[${it.x},${it.y},\"${it.command}\"]" }
            fun colors(p: EmbPattern) = p.threads.joinToString(",", "[", "]") { (it.color and 0xFFFFFF).toString() }
            fun details(p: EmbPattern) = kotlinx.serialization.json.JsonArray(p.threads.map { t -> kotlinx.serialization.json.buildJsonObject {
                put("color", kotlinx.serialization.json.JsonPrimitive(t.color and 0xFFFFFF))
                for ((key, value) in mapOf("description" to t.description, "catalogNumber" to t.catalogNumber, "brand" to t.brand, "chart" to t.chart)) {
                    put(key, value?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                }
            } })
            val metadata = kotlinx.serialization.json.JsonObject(file.metadata.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })
            val manifest = "{\"source\":{\"stitches\":${stitches(file.part.pattern)},\"threads\":${colors(file.part.pattern)},\"threadDetails\":${details(file.part.pattern)},\"metadata\":$metadata},\"expected\":{\"stitches\":${stitches(expected)},\"threads\":${colors(expected)}}}"
            kotlinx.serialization.json.Json.parseToJsonElement(manifest)
            File(path.path + ".json").writeText(manifest)
        }
    }
}
