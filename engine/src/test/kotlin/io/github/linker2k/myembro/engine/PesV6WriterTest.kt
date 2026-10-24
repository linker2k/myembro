// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.*
import kotlin.test.*

class PesV6WriterTest {
    private val red = EmbThread(0xFFDC1234.toInt(), "Scarlet", "R1", "Maker", "Custom")
    private val blue = EmbThread(0xFF1267AB.toInt(), "Blue", "B2", "Maker", "Custom")
    private fun s(x: Int, y: Int, c: Command = Command.STITCH) = Stitch(x, y, c)
    private fun p(vararg stitches: Stitch, threads: List<EmbThread> = listOf(red), metadata: Map<String, String> = emptyMap()) =
        EmbPattern(stitches.toList(), threads, metadata)
    private fun u32(data: ByteArray, index: Int) = ByteBuffer.wrap(data, index, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun text(data: ByteArray) = data.toString(Charsets.ISO_8859_1)

    @Test fun fixturesAndBoundariesRoundTripAndExportForPythonOracle() {
        val cases = linkedMapOf<String, EmbPattern>()
        for (name in listOf("flower", "long_moves", "stops")) for (extension in listOf("pec", "v1.pes", "v6.pes", "dst")) {
            val file = "$name.$extension"
            cases[file] = PatternReader.read(requireNotNull(javaClass.getResource("/fixtures/$file")).readBytes(), file)
        }
        cases["empty"] = p()
        cases["split"] = p(s(6141, -6141), s(-2048, 2047, Command.JUMP))
        cases["trims-stops"] = p(s(0, 0), s(100, 20, Command.TRIM), s(200, 20, Command.JUMP),
            s(220, 40), s(220, 40, Command.STOP), s(250, 40), s(250, 40, Command.COLOR_CHANGE), s(280, 40), threads = listOf(red, blue))
        cases["identity"] = p(s(0, 0), s(0, 0, Command.COLOR_CHANGE), s(20, 20),
            threads = listOf(red, red.copy(description = "Another catalog", catalogNumber = "R2")))
        cases["empty-colors"] = p(s(0, 0, Command.COLOR_CHANGE), s(10, 10), s(10, 10, Command.COLOR_CHANGE), threads = listOf(red, blue, red))
        cases["255-blocks"] = EmbPattern((0 until 255).flatMap { i ->
            listOf(s(i, 0), s(i, 0, Command.STOP))
        }, listOf(red))
        cases["utf8"] = p(s(0, 0), s(20, 20), threads = listOf(red.copy(description = "빨강", brand = "자수")),
            metadata = mapOf("name" to "자수 도안", "author" to "만든이", "comments" to "한글 😀".repeat(80)))
        val output = File("build/writer-check").apply { mkdirs() }
        for ((name, source) in cases) {
            val expected = PecWriter.normalizePreservingThreads(io.github.linker2k.myembro.engine.io.PesWriter.prepareForOutput(source))
            val bytes = PesWriter.writeV6(source)
            val back = PesReader.read(bytes)
            assertTrue(back.warnings.isEmpty(), "$name: ${back.warnings}")
            assertEquals(expected.stitches, back.stitches, name)
            assertEquals(expected.threads.map { it.color and 0xFFFFFF }, back.threads.map { it.color and 0xFFFFFF }, name)
            val path = File(output, "$name.output-v6.pes")
            path.writeBytes(bytes)
            fun stitches(items: List<Stitch>) = JsonArray(items.map { buildJsonArray { add(it.x); add(it.y); add(it.command.name) } })
            val manifest = buildJsonObject {
                put("source", buildJsonObject {
                    put("stitches", stitches(source.stitches))
                    put("threads", JsonArray(source.threads.map { JsonPrimitive(it.color and 0xFFFFFF) }))
                    put("threadDetails", JsonArray(source.threads.map { t -> buildJsonObject {
                        put("color", t.color and 0xFFFFFF)
                        put("description", t.description?.let(::JsonPrimitive) ?: JsonNull)
                        put("catalogNumber", t.catalogNumber?.let(::JsonPrimitive) ?: JsonNull)
                        put("brand", t.brand?.let(::JsonPrimitive) ?: JsonNull)
                        put("chart", t.chart?.let(::JsonPrimitive) ?: JsonNull)
                    } }))
                    put("metadata", JsonObject(source.metadata.mapValues { JsonPrimitive(it.value) }))
                })
                put("expected", buildJsonObject {
                    put("stitches", stitches(expected.stitches))
                    put("threads", JsonArray(expected.threads.map { JsonPrimitive(it.color and 0xFFFFFF) }))
                })
            }
            File(path.path + ".json").writeText(manifest.toString())
        }
    }

    @Test fun fullV6HasEditorObjectsCustomThreadsMetadataAndAddendum() {
        val source = p(s(-50, -30), s(50, 30), metadata = mapOf("name" to "Test", "category" to "Sample", "author" to "myembro"))
        val bytes = PesWriter.writeV6(source)
        assertEquals("#PES0060", text(bytes.copyOfRange(0, 8)))
        val offset = u32(bytes, 8)
        assertTrue(offset > 100)
        assertEquals("LA:", text(bytes.copyOfRange(offset, offset + 3)))
        assertTrue(text(bytes.copyOfRange(12, offset)).contains("CEmbOne"))
        assertTrue(text(bytes.copyOfRange(12, offset)).contains("CSewSeg"))
        val back = PesReader.read(bytes)
        assertEquals(listOf(red), back.threads)
        assertEquals("Sample", back.metadata["category"])
        assertEquals("myembro", back.metadata["author"])
        assertEquals(listOf(s(100,60),s(100,60,Command.END)), back.stitches)
        assertEquals(0, bytes[bytes.lastIndex].toInt())
    }

    @Test fun explicitSettingsSelectVersionWithoutChangingDefaultOrPassThroughPes() {
        val source = p(s(0, 0), s(10, 10))
        assertContentEquals(PesWriter.writeV1(source), PesWriter.write(source))
        assertContentEquals(PesWriter.writeV6(source), PesWriter.write(source, PesWriteSettings(PesVersion.V6)))
        assertContentEquals(PesWriter.writeV6(source), PesWriter.write(source, PesVersion.V6))
        val pec = PecWriter.write(source)
        val converted = PesExport.prepare(pec, "design.pec", PesWriteSettings(PesVersion.V6))
        assertEquals("#PES0060", text(converted.bytes.copyOfRange(0, 8)))
        val v1 = PesWriter.writeV1(source)
        assertContentEquals(v1, PesExport.prepare(v1, "old.pes", PesWriteSettings(PesVersion.V6)).bytes)
    }

    @Test fun utf8ByteLengthsAreBoundedAndDecodableAndInputIsUnchanged() {
        val source = p(s(0, 0), s(10, 10), threads = listOf(red.copy(description = "빨강".repeat(100))),
            metadata = mapOf("name" to "한글😀".repeat(100)))
        val before = source.stitches.toList()
        val bytes = PesWriter.writeV6(source)
        val back = PesReader.read(bytes)
        assertTrue(back.warnings.isEmpty())
        assertTrue(back.metadata.getValue("name").toByteArray().size <= 255)
        assertFalse(back.metadata.getValue("name").contains('\uFFFD'))
        assertTrue(back.threads.single().description!!.toByteArray().size <= 255)
        assertFalse(back.threads.single().description!!.contains('\uFFFD'))
        assertEquals(before, source.stitches)
        assertEquals("빨강".repeat(100), source.threads.single().description)
    }

    @Test fun longSewingSectionSplitsWithoutU16PointCountWrapping() {
        val source = EmbPattern(List(65536) { s((it+1) % 100 + 1, (it+1) % 50 + 1) }, listOf(red))
        val bytes = PesWriter.writeV6(source)
        assertEquals(65536, PesReader.read(bytes).stitchCount)
        val marker = text(bytes).indexOf("CSewSeg")
        val section1 = marker + 7
        val firstCount = (bytes[section1 + 4].toInt() and 255) or ((bytes[section1 + 5].toInt() and 255) shl 8)
        assertEquals(65535, firstCount)
        val section2 = section1 + 6 + 65535 * 4 + 2
        val secondCount = (bytes[section2 + 4].toInt() and 255) or ((bytes[section2 + 5].toInt() and 255) shl 8)
        assertEquals(2, secondCount) // shared endpoint plus final point
    }

    @Test fun v6ChecksSectionCoordinateAndPecPaletteLimits() {
        assertFailsWith<IllegalArgumentException> { PesWriter.writeV6(p(s(1, 0), s(40000, 0))) }
        val manyThreads = List(65) { EmbThread(0xFF000000.toInt() + it) }
        val stitches = manyThreads.indices.flatMap { i -> listOf(s(i+1, 0), s(i+2, 0), s(i+2, 0, Command.COLOR_CHANGE)) }
        assertFailsWith<IllegalArgumentException> { PesWriter.writeV6(EmbPattern(stitches, manyThreads)) }
    }
}
