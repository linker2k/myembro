// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import io.github.linker2k.myembro.engine.io.DstReader
import io.github.linker2k.myembro.engine.io.EmbFormatException
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.PatternBuilder
import io.github.linker2k.myembro.engine.model.Stitch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Hand-built inputs for cases the pyembroidery fixtures cannot produce. */
class EdgeCaseTest {
    /** Minimal PEC file: palette of PEC color indices, then raw stitch-block bytes. */
    private fun pec(colors: IntArray, vararg codes: Int, colorCountByte: Int = colors.size - 1): ByteArray {
        val d = ByteArray(536 + codes.size)
        "#PEC0001".toByteArray().copyInto(d)
        d[56] = colorCountByte.toByte()
        colors.forEachIndexed { i, c -> d[57 + i] = c.toByte() }
        val len = 16 + codes.size
        d[522] = len.toByte(); d[523] = (len shr 8).toByte(); d[524] = (len shr 16).toByte()
        codes.forEachIndexed { i, c -> d[536 + i] = c.toByte() }
        return d
    }

    private fun commands(p: io.github.linker2k.myembro.engine.model.EmbPattern) = p.stitches.map { it.command }

    @Test
    fun duplicateColorBecomesStop() {
        val p = PesReader.read(pec(intArrayOf(5, 5), 0, 0, 10, 0, 0xFE, 0xB0, 2, 10, 0, 0xFF))
        assertEquals(listOf(Command.STITCH, Command.STITCH, Command.STOP, Command.STITCH, Command.END), commands(p))
        assertEquals(1, p.threads.size)
        assertEquals(1, p.colorBlocks.size, "STOP keeps the same thread")
        assertEquals(0, p.colorChangeCount)
        assertEquals(1, p.stopCount)
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun singleFfEndsBlockEvenWithThumbnailAfterIt() {
        // END written as a lone 0xFF followed by non-zero thumbnail bytes inside the file.
        val p = PesReader.read(pec(intArrayOf(5), 10, 0, 0xFF, 0x01, 0x00, 0x00, 0x00))
        assertEquals(listOf(Command.STITCH, Command.END), commands(p))
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun truncatedStitchDataIsReportedNotHidden() {
        for (codes in listOf(intArrayOf(10, 0, 0xFE, 0xB0), intArrayOf(10, 0, 0x81), intArrayOf(10, 0))) {
            val p = PesReader.read(pec(intArrayOf(5, 1), *codes))
            assertEquals(Command.END, p.stitches.last().command)
            assertTrue(p.warnings.any { "truncated" in it }, "no warning for ${codes.toList()}")
        }
    }

    @Test
    fun twelveBitCoordinates() {
        // x = +2047 (long, plain), y = -2048 (long, plain); then 7-bit x=-1, y=+63.
        val p = PesReader.read(pec(intArrayOf(5), 0x87, 0xFF, 0x88, 0x00, 0x7F, 0x3F, 0xFF))
        assertEquals(listOf(Stitch(2047, -2048, Command.STITCH), Stitch(2046, -1985, Command.STITCH)), p.stitches.dropLast(1))
    }

    @Test
    fun ffColorCountMeansNoColors() {
        val p = PesReader.read(pec(IntArray(0), 0xFF, colorCountByte = 0xFF))
        assertEquals(0, p.threads.size)
        assertEquals(listOf(Command.END), commands(p))
    }

    @Test
    fun rejectsBrokenContainers() {
        assertFailsWith<EmbFormatException> { PesReader.read("#PES0001".toByteArray() + byteArrayOf(0x7F, 0, 0, 0)) }
        assertFailsWith<EmbFormatException> { PesReader.read(pec(intArrayOf(5)).copyOf(57)) }
        assertFailsWith<EmbFormatException> { DstReader.read(ByteArray(0)) }
    }

    @Test
    fun streamingTrimInterpolationMatchesUpstreamAlgorithm() {
        val rnd = Random(42)
        repeat(300) {
            val stitches = List(rnd.nextInt(0, 60)) {
                val cmd = when (rnd.nextInt(10)) {
                    in 0..3 -> Command.STITCH
                    in 4..7 -> Command.JUMP
                    8 -> Command.TRIM
                    else -> Command.COLOR_CHANGE
                }
                // Small coordinates so zero-length jump runs (clipping) are common.
                Stitch(rnd.nextInt(-2, 3), rnd.nextInt(-2, 3), cmd)
            }
            for (trimAt in listOf(1, 2, 3)) for (distance in listOf(null, 1)) for (clip in listOf(true, false)) {
                val expected = upstreamInterpolateTrims(stitches, trimAt, distance, clip)
                val b = PatternBuilder()
                var x = 0; var y = 0
                for (s in stitches) { b.add(s.command, s.x - x, s.y - y); x = s.x; y = s.y }
                b.interpolateTrims(trimAt, distance, clip)
                assertEquals(expected, b.build().stitches, "input=$stitches trimAt=$trimAt d=$distance clip=$clip")
            }
        }
    }

    @Test
    fun trimInterpolationHandlesLargeJumpHeavyFiles() {
        // Correctness at scale only; speed is not asserted here because wall-clock limits are
        // flaky on shared CI runners. The streaming rewrite was ~5 ms for this input locally.
        val b = PatternBuilder()
        repeat(80_000) { b.stitch(1, 0); b.move(1, 0); b.move(1, 0); b.move(1, 0) }
        b.end()
        b.interpolateTrims(3, null, true)
        assertEquals(80_000, b.build().trimCount)
    }

    @Test
    fun sameRgbButDifferentThreadStaysAColorChange() {
        val b = PatternBuilder()
        b.threads += io.github.linker2k.myembro.engine.model.EmbThread(0xFF112233.toInt(), "Thread A", "1")
        b.threads += io.github.linker2k.myembro.engine.model.EmbThread(0xFF112233.toInt(), "Thread B", "2")
        b.stitch(10, 0); b.colorChange(); b.stitch(10, 0); b.end()
        b.interpolateDuplicateColorAsStop()
        val p = b.build()
        assertEquals(1, p.colorChangeCount)
        assertEquals(0, p.stopCount)
        assertEquals(2, p.threads.size)
    }

    @Test
    fun invalidBlockLengthIsRejectedInsteadOfReadingThumbnails() {
        val d = pec(intArrayOf(5), 10, 0, 0xFF, 0x20, 0x00)
        d[522] = 16; d[523] = 0; d[524] = 0 // ends before the first stitch byte
        assertFailsWith<EmbFormatException> { PesReader.read(d) }
        d[522] = 0xFF.toByte(); d[523] = 0xFF.toByte() // past the end of the file
        assertFailsWith<EmbFormatException> { PesReader.read(d) }
    }

    /** Literal in-place port of pyembroidery 1.5.1 `interpolate_trims`, used as the reference. */
    private fun upstreamInterpolateTrims(input: List<Stitch>, jumps: Int?, distance: Int?, clipping: Boolean): List<Stitch> {
        val st = input.toMutableList()
        var i = -1
        var ie = st.size - 1
        var x = 0; var y = 0
        var jumpCount = 0; var jumpStart = 0; var jumpDx = 0; var jumpDy = 0
        var jumping = false; var trimmed = true
        while (i < ie) {
            i++
            val s = st[i]
            val dx = s.x - x; val dy = s.y - y
            x = s.x; y = s.y
            if (s.command == Command.STITCH) { trimmed = false; jumping = false }
            else if (s.command == Command.COLOR_CHANGE || s.command == Command.TRIM) { trimmed = true; jumping = false }
            if (s.command == Command.JUMP) {
                if (!jumping) { jumpDx = 0; jumpDy = 0; jumpCount = 0; jumpStart = i; jumping = true }
                jumpCount++; jumpDx += dx; jumpDy += dy
                if (!trimmed) {
                    if (jumpCount == jumps || distance != null && (Math.abs(jumpDy) > distance || Math.abs(jumpDx) > distance)) {
                        val p = if (jumpStart == 0) Stitch(0, 0, Command.TRIM) else st[jumpStart - 1]
                        st.add(jumpStart, Stitch(p.x, p.y, Command.TRIM))
                        jumpStart++; i++; ie++
                        trimmed = true
                    }
                }
                if (clipping && jumpDx == 0 && jumpDy == 0) {
                    st.subList(jumpStart, i + 1).clear()
                    i = jumpStart - 1
                    ie = st.size - 1
                }
            }
        }
        return st
    }
}
