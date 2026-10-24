// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import java.io.File
import kotlin.test.*

/** Frozen oracle control semantics, tested at the actual PEC bytes rather than our normalizer alone. */
class InkstitchWriterProfileTest {
    private val red = PecThreads.forIndex(49)
    private val blue = PecThreads.forIndex(4)
    private fun s(x: Int, command: Command = Command.STITCH, y: Int = 0) = Stitch(x, y, command)
    private fun pattern(vararg commands: Stitch, threads: List<EmbThread> = listOf(red)) = EmbPattern(commands.toList(), threads)
    private fun write(pattern: EmbPattern, version: PesVersion = PesVersion.V1) = PesWriter.write(pattern, PesWriteSettings(version, inkstitchCommands = true))
    private fun offset(bytes: ByteArray): Int = (0..3).sumOf { (bytes[8 + it].toInt() and 255) shl (8 * it) }
    private fun codes(bytes: ByteArray): ByteArray {
        val start = offset(bytes)
        val length = (0..2).sumOf { (bytes[start + 514 + it].toInt() and 255) shl (8 * it) }
        return bytes.copyOfRange(start + 528, start + 512 + length)
    }
    private fun colors(bytes: ByteArray): List<Int> = offset(bytes).let { p -> (0..(bytes[p + 48].toInt() and 255)).map { bytes[p + 49 + it].toInt() and 255 } }
    private fun bytes(hex: String) = hex.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test fun explicitProfilePreservesTerminalStopButDefaultBytesRemainUnchanged() {
        val source = pattern(s(0, Command.JUMP), s(0), s(40), s(40, Command.TRIM), s(40, Command.STOP), s(40, Command.END))
        val normal = PesWriter.write(source)
        assertContentEquals(bytes("28 00 a0 00 a0 00 ff 00"), codes(normal))
        assertContentEquals(normal, PesWriter.write(source, PesWriteSettings()))
        assertContentEquals(normal, PesWriter.writeV1(source))
        assertContentEquals(PesWriter.writeV6(source), PesWriter.write(source, PesWriteSettings(PesVersion.V6)))
        val compatible = write(source)
        assertContentEquals(bytes("90 00 90 00 90 00 90 00 00 00 28 00 fe b0 02 ff 00"), codes(compatible))
        assertEquals(listOf(49), colors(normal))
        assertEquals(listOf(49, 49), colors(compatible))
        val decoded = PesReader.read(compatible)
        assertEquals(2, decoded.threads.size)
        assertEquals(Command.COLOR_CHANGE, decoded.stitches[decoded.stitches.lastIndex - 1].command)
    }

    @Test fun viewportPrefixAndFirstPatternJumpAreUntrimmedButLaterJumpsHaveTrimFlag() {
        val source = pattern(s(0, Command.JUMP), s(10, Command.JUMP), s(10), s(20, Command.JUMP), s(20), s(20, Command.END))
        val compatible = write(source)
        assertContentEquals(bytes("90 00 90 00 90 00 90 00 a0 0a a0 00 00 00 a0 0a a0 00 00 00 ff 00"), codes(compatible))
        val decoded = PesReader.read(compatible)
        assertEquals(2, decoded.trimCount)
        assertEquals(listOf(10, 20), decoded.stitches.filter { it.command == Command.STITCH }.map { it.x })
        assertEquals(0, PesReader.read(PesWriter.write(source)).trimCount)
        val placed = pattern(s(26, Command.JUMP, 26), s(26, y = 26), s(66, y = 26), s(66, Command.END, 26))
        assertEquals(listOf(0, 40), PesReader.read(write(placed)).stitches.filter { it.command == Command.STITCH }.map { it.x })
        assertEquals(listOf(66), PesReader.read(PesWriter.write(placed)).stitches.filter { it.command == Command.STITCH }.map { it.x })
    }

    @Test fun colorsPrecedeImplicitTrimAndTerminalExplicitTrimHasNoBytes() {
        val source = pattern(s(0, Command.JUMP), s(0), s(10), s(10, Command.COLOR_CHANGE), s(20, Command.JUMP), s(20), s(30), s(30, Command.TRIM), s(30, Command.END), threads = listOf(red, blue))
        val decoded = PesReader.read(write(source))
        assertEquals(listOf(Command.COLOR_CHANGE, Command.TRIM), decoded.stitches.filter { it.command in setOf(Command.TRIM, Command.STOP, Command.COLOR_CHANGE) }.map { it.command })
        assertEquals(listOf(49, 4), colors(write(source)))
        assertEquals(4, decoded.stitchCount)
        assertEquals(source.stitches.filter { it.command == Command.STITCH }, decoded.stitches.filter { it.command == Command.STITCH })
        val terminalTrim = pattern(s(0, Command.JUMP), s(0), s(10), s(10, Command.TRIM), s(10, Command.END))
        assertEquals(0, PesReader.read(write(terminalTrim)).trimCount)
    }

    @Test fun stopDuplicatesSurviveEmptyBlocksAndFullJumpFollowsExplicitTrim() {
        val source = pattern(s(0, Command.JUMP), s(0), s(10), s(10, Command.STOP), s(10, Command.JUMP), s(10), s(20), s(20, Command.STOP), s(20, Command.END))
        val compatible = write(source)
        assertEquals(listOf(49, 49, 49), colors(compatible))
        val decoded = PesReader.read(compatible)
        assertEquals(1, decoded.stopCount) // Internal duplicate has following sewing; trailing one stays a color.
        assertEquals(1, decoded.colorChangeCount)
        val trimmed = pattern(s(0), s(10), s(10, Command.TRIM), s(20), s(20, Command.END))
        val read = PesReader.read(write(trimmed))
        assertEquals(listOf(0, 10, 20), read.stitches.filter { it.command == Command.STITCH }.map { it.x })
        assertEquals(1, read.trimCount)
        assertEquals(2, read.jumpCount)
    }

    @Test fun v6RetainsCustomThreadRgbAndTerminalDuplicateChartWithProfile() {
        val custom = EmbThread(0xFF123456.toInt(), "Custom", "42", "Maker", "Chart")
        val source = pattern(s(0, Command.JUMP), s(0), s(10), s(10, Command.STOP), s(10, Command.END), threads = listOf(custom))
        val decoded = PesReader.read(write(source, PesVersion.V6))
        assertEquals(listOf(custom, custom), decoded.threads)
        assertEquals(1, decoded.colorChangeCount)
        assertEquals(0, decoded.trimCount)
    }

    @Test fun normalizationIsStableInputIsImmutableAndControlFixturesAreReviewable() {
        val source = pattern(s(0, Command.JUMP), s(0), s(40), s(40, Command.COLOR_CHANGE), s(12, Command.JUMP, 12), s(12, y = 12), s(28, y = 12), s(28, Command.TRIM, 12), s(28, Command.STOP, 12), s(28, Command.END, 12), threads = listOf(red, blue))
        val before = source.stitches.toList()
        val normalized = PecWriter.normalizeInkstitch(source)
        val again = PecWriter.normalizeInkstitch(normalized)
        assertEquals(normalized.stitches, again.stitches)
        assertEquals(normalized.threads, again.threads)
        assertEquals(before, source.stitches)
        assertEquals(listOf(red, blue), source.threads)
        val output = File("build/inkstitch-writer-check").apply { mkdirs() }
        for (version in PesVersion.entries) {
            File(output, "controls-$version.pes").writeBytes(write(source, version))
            File(output, "controls-default-$version.pes").writeBytes(PesWriter.write(source, version))
        }
    }
}
