// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.engine.io.PecWriter
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WriterParityTest {
    private val red = PecThreads.forIndex(5)
    private val blue = PecThreads.forIndex(2)
    private fun s(x: Int, y: Int, command: Command = Command.STITCH) = Stitch(x, y, command)
    private fun pattern(vararg stitches: Stitch, threads: List<EmbThread> = listOf(red)) =
        EmbPattern(stitches.toList(), threads)

    private fun u24(data: ByteArray, offset: Int) =
        (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8) or
            ((data[offset + 2].toInt() and 255) shl 16)

    /** Independent fixture expectation: PEC can only express a trim together with a jump. */
    private fun withTrimJumps(stitches: List<Stitch>): List<Stitch> = buildList {
        stitches.forEachIndexed { i, stitch ->
            add(stitch)
            if (stitch.command == Command.TRIM && stitches.getOrNull(i + 1)?.command != Command.JUMP) {
                add(stitch.copy(command = Command.JUMP))
            }
        }
    }

    @Test
    fun fixturesRoundTripAndExportForOracle() {
        val output = File("build/writer-check").apply { mkdirs() }
        for (name in listOf("flower", "long_moves", "stops")) {
            for (ext in listOf("pec", "v1.pes", "v6.pes", "dst")) {
                val filename = "$name.$ext"
                val source = requireNotNull(javaClass.getResource("/fixtures/$filename")).readBytes()
                val p = PatternReader.read(source, filename)
                // Existing fixtures already have 12-bit-safe moves. Palette RGBs come from
                // the reference chart; v6 custom colors use independently checked nearests.
                val colors = when {
                    ext == "v6.pes" -> when (name) {
                        "flower" -> listOf(56, 48, 58)
                        "long_moves" -> listOf(59)
                        else -> listOf(5, 32)
                    }.map { PecThreads.forIndex(it).color and 0xFFFFFF }
                    p.threads.isEmpty() -> List(p.colorChangeCount + 1) { p.threadAt(it).color and 0xFFFFFF }
                    else -> p.threads.map { it.color and 0xFFFFFF }
                }
                export(output, filename, p, withTrimJumps(p.stitches), colors)
            }
        }
        val long = pattern(s(6141, -6141), s(-2047, 2047, Command.JUMP))
        val longExpected = listOf(
            // A long move punches no holes on the way: intermediate parts are jumps.
            s(2047, -2047, Command.JUMP), s(4094, -4094, Command.JUMP), s(6141, -6141),
            s(4094, -4094, Command.JUMP), s(2047, -2047, Command.JUMP),
            s(0, 0, Command.JUMP), s(-2047, 2047, Command.JUMP), s(-2047, 2047, Command.END),
        )
        export(output, "split", long, longExpected, listOf(red.color and 0xFFFFFF))
        export(output, "negative-2048", pattern(s(-2048, 2047)),
            listOf(s(-1024, 1024, Command.JUMP), s(-2048, 2047), s(-2048, 2047, Command.END)), listOf(red.color and 0xFFFFFF))
        val stop = pattern(
            s(0, 0), s(0, 0, Command.STOP), s(10, 0), s(10, 0, Command.STOP),
            s(20, 0), s(20, 0, Command.COLOR_CHANGE), s(30, 0), s(30, 0, Command.END),
            threads = listOf(red, blue),
        )
        export(output, "multiple-stops", stop, stop.stitches, listOf(red, blue).map { it.color and 0xFFFFFF })
        val trim = pattern(
            s(0, 0), s(0, 0, Command.TRIM), s(10, 10), s(10, 10, Command.TRIM),
            s(1000, 2000, Command.JUMP), s(1001, 2001), s(1001, 2001, Command.TRIM),
            s(1001, 2001, Command.END),
        )
        export(output, "explicit-trims", trim, withTrimJumps(trim.stitches), listOf(red.color and 0xFFFFFF))
        export(output, "empty", EmbPattern(emptyList(), emptyList()), listOf(s(0, 0, Command.END)), listOf(0x0A55A3))
        val quantized = pattern(
            s(0, 0), s(0, 0, Command.COLOR_CHANGE), s(10, 0), s(10, 0, Command.END),
            threads = listOf(EmbThread(0xFF000001.toInt()), PecThreads.forIndex(20)),
        )
        val quantizedColors = PecWriter.normalize(quantized).threads.map { it.color and 0xFFFFFF }
        // Exact black is reserved, preventing a different near-black from becoming a STOP.
        assertEquals(0, quantizedColors[1])
        assertTrue(quantizedColors[0] != 0)
        export(output, "palette-collision", quantized, quantized.stitches, quantizedColors)
        val palette = allPaletteColors()
        export(output, "all-64-colors", palette, palette.stitches, palette.threads.map { it.color and 0xFFFFFF })
        val controls = pattern(s(5, 10, Command.TRIM), s(20, 30), s(25, 35, Command.END), s(99999, 99999))
        export(output, "control-motion", controls, listOf(
            s(5, 10, Command.JUMP), s(5, 10, Command.TRIM), s(5, 10, Command.JUMP), s(20, 30),
            s(25, 35, Command.JUMP), s(25, 35, Command.END),
        ), listOf(red.color and 0xFFFFFF))
    }

    private fun export(output: File, name: String, source: EmbPattern, expected: List<Stitch>, colors: List<Int>) {
        for ((extension, write) in listOf("pec" to PecWriter::write, "pes" to { p: EmbPattern -> PesWriter.write(p) })) {
            val data = write(source)
            val reread = PesReader.read(data)
            assertTrue(reread.warnings.isEmpty(), "$name.$extension: ${reread.warnings}")
            assertEquals(expected, reread.stitches, "$name.$extension: commands/coordinates")
            assertEquals(colors, reread.threads.map { it.color and 0xFFFFFF }, "$name.$extension: colors")
            assertEquals(expected, PecWriter.normalize(source).stitches)
            File(output, "$name.$extension").writeBytes(data)
            // The oracle gets both the original input and expected normalization, never just
            // a dump of our reader's result. Python independently checks this expectation.
            fun stitchesJson(stitches: List<Stitch>) = stitches.joinToString(",", "[", "]") {
                "[${it.x},${it.y},\"${it.command}\"]"
            }
            val threads = source.threads.joinToString(",", "[", "]") { (it.color and 0xFFFFFF).toString() }
            File(output, "$name.$extension.json").writeText(
                "{\"source\":{\"stitches\":${stitchesJson(source.stitches)},\"threads\":$threads}," +
                    "\"expected\":{\"stitches\":${stitchesJson(expected)},\"threads\":${colors.joinToString(",", "[", "]")}}}\n",
            )
        }
    }

    @Test
    fun shortAndLongWireEncodingMatchesReference() {
        val p = pattern(s(0, 0), s(62, -63), s(125, -127), s(2172, -2174), s(2172, -2174, Command.END))
        val bytes = PecWriter.write(p)
        val expected = intArrayOf(0, 0, 62, 65, 0x80, 0x3F, 0x8F, 0xC0, 0x87, 0xFF, 0x88, 1, 0xFF, 0)
        assertContentEquals(expected.map { it.toByte() }.toByteArray(), bytes.copyOfRange(536, 536 + expected.size))
        assertEquals(p.stitches, PesReader.read(bytes).stitches)
    }

    @Test
    fun negative2048IsSplitWithoutWrapping() {
        val p = pattern(s(-2048, 2047))
        val expected = listOf(s(-1024, 1024, Command.JUMP), s(-2048, 2047), s(-2048, 2047, Command.END))
        assertEquals(expected, PesReader.read(PecWriter.write(p)).stitches)
    }

    @Test
    fun headerLengthsGraphicsAndPesOffsetAreCorrect() {
        val p = pattern(s(0, 0), s(100, 50), s(100, 50, Command.STOP), s(200, 100), s(200, 100, Command.END))
        val pec = PecWriter.write(p)
        val pes = PesWriter.write(p)
        assertEquals("#PES0001", pes.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertEquals(22, u24(pes, 8))
        assertContentEquals(ByteArray(10), pes.copyOfRange(12, 22))
        assertContentEquals(pec.copyOfRange(8, pec.size), pes.copyOfRange(22, pes.size))
        assertEquals(1, pec[56].toInt())
        assertContentEquals(byteArrayOf(5, 5), pec.copyOfRange(57, 59))
        assertEquals(6, pec[42].toInt())
        assertEquals(38, pec[43].toInt())
        val end = 520 + u24(pec, 522)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0), pec.copyOfRange(end - 2, end))
        assertEquals(3 * 228, pec.size - end, "overall + two STOP-separated thumbnails")
        assertTrue(!pec.copyOfRange(end + 228, end + 456).contentEquals(pec.copyOfRange(end + 456, pec.size)))
        // A needle-down point at the origin of a degenerate design lands in the center.
        val dot = PecWriter.write(pattern(s(0, 0)))
        val dotEnd = 520 + u24(dot, 522)
        assertEquals(1, dot[dotEnd + 19 * 6 + 3].toInt() and 1)
    }

    @Test
    fun labelsAreFixedWidthAndInputIsNotMutated() {
        val stitches = mutableListOf(s(10, 10), s(10, 10, Command.STOP), s(20, 20), s(20, 20, Command.END))
        val threads = mutableListOf(red)
        val p = EmbPattern(stitches, threads, mapOf("name" to "한글\nlabel-long"))
        val before = stitches.toList()
        val data = PecWriter.write(p)
        assertEquals("LA:???label        \r", data.copyOfRange(8, 28).toString(Charsets.US_ASCII))
        assertEquals(before, stitches)
        assertEquals(listOf(red), threads)
        assertContentEquals(data, PecWriter.write(p), "deterministic bytes")
        assertTrue(PesReader.read(data).warnings.isEmpty())
    }

    @Test
    fun everyPaletteColorIsPreserved() {
        val p = allPaletteColors()
        val actual = PesReader.read(PecWriter.write(p))
        assertEquals(p.stitches, actual.stitches)
        assertEquals(p.threads.map { it.color }, actual.threads.map { it.color })
    }

    private fun allPaletteColors(): EmbPattern {
        val stitches = buildList {
            for (i in 1..64) {
                if (i > 1) add(s(i - 1, 0, Command.COLOR_CHANGE))
                add(s(i, 0))
            }
            add(s(64, 0, Command.END))
        }
        return EmbPattern(stitches, (1..64).map(PecThreads::forIndex))
    }

    @Test
    fun validatesPaletteColorBlockAndDimensionLimits() {
        fun stops(count: Int) = EmbPattern(buildList {
            add(s(0, 0))
            repeat(count) { add(s(it, 0, Command.STOP)); add(s(it + 1, 0)) }
        }, listOf(red))
        assertEquals(254, PesReader.read(PecWriter.write(stops(254))).stopCount)
        assertFailsWith<IllegalArgumentException> { PecWriter.write(stops(255)) }
        val tooManyColors = EmbPattern(buildList {
            repeat(65) { i ->
                if (i > 0) add(s(i - 1, 0, Command.COLOR_CHANGE))
                add(s(i, 0))
            }
        }, List(65) { EmbThread(0xFF010000.toInt() + it) })
        assertFailsWith<IllegalArgumentException> { PecWriter.write(tooManyColors) }
        assertTrue(PesReader.read(PecWriter.write(pattern(s(0, 0), s(65535, 0)))).warnings.isEmpty())
        assertFailsWith<IllegalArgumentException> { PecWriter.write(pattern(s(0, 0), s(65536, 0))) }
        assertFailsWith<IllegalArgumentException> { PecWriter.write(pattern(s(Int.MIN_VALUE, 0), s(Int.MAX_VALUE, 0))) }
        assertFailsWith<IllegalArgumentException> { PecWriter.normalize(pattern(s(100000, 0))) }
    }

    @Test
    fun unsewnBlocksAreDroppedAndSameThreadChangesBecomeStops() {
        val cases = listOf(
            pattern(s(0, 0, Command.STOP), s(10, 0)) to listOf(s(10, 0), s(10, 0, Command.END)),
            pattern(s(0, 0), s(0, 0, Command.STOP)) to listOf(s(0, 0), s(0, 0, Command.END)),
            pattern(s(0, 0), s(10, 0), s(10, 0, Command.COLOR_CHANGE), threads = listOf(red, blue)) to
                listOf(s(0, 0), s(10, 0), s(10, 0, Command.END)),
            pattern(s(0, 0), s(0, 0, Command.STOP), s(0, 0, Command.STOP), s(10, 0)) to
                listOf(s(0, 0), s(0, 0, Command.STOP), s(10, 0), s(10, 0, Command.END)),
            pattern(s(0, 0), s(0, 0, Command.COLOR_CHANGE), s(10, 0), threads = listOf(red, red)) to
                listOf(s(0, 0), s(0, 0, Command.STOP), s(10, 0), s(10, 0, Command.END)),
        )
        for ((source, expected) in cases) {
            val reread = PesReader.read(PecWriter.write(source))
            assertEquals(expected, reread.stitches, "source=${source.stitches}")
            assertEquals(1, reread.threads.size)
        }
    }

    @Test
    fun sameRgbDifferentThreadsKeepTheirColorChange() {
        val a = EmbThread(0xFF112233.toInt(), "Thread A", "1")
        val b = EmbThread(0xFF112233.toInt(), "Thread B", "2")
        val reread = PesReader.read(PecWriter.write(pattern(s(0, 0), s(0, 0, Command.COLOR_CHANGE), s(10, 0), threads = listOf(a, b))))
        assertEquals(1, reread.colorChangeCount)
        assertEquals(0, reread.stopCount)
        assertEquals(2, reread.threads.size)
    }
}
