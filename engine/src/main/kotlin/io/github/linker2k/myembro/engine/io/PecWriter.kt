// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * PEC output ported from pyembroidery 1.5.1 (MIT), PecWriter.py, PecGraphics.py and
 * EmbThread.py in https://github.com/EmbroidePy/pyembroidery (pyembroidery/ directory).
 * Pinned source distribution: https://pypi.org/project/pyembroidery/1.5.1/
 *
 * Differences: explicit trims are retained; ordinary jumps do not imply trims; no extra
 * needle-down stitches are inserted. Palette allocation is deterministic, reserving exact
 * matches before assigning the nearest unused color. See docs/writers.md for format losses.
 * Coordinates are absolute 0.1 mm, +y down; input patterns are never mutated.
 */
object PecWriter {
    private const val MAX_DELTA = 2047
    private const val GRAPHIC_STRIDE = 6
    private const val GRAPHIC_HEIGHT = 38

    /** Standalone PEC, including its signature, stitch block and one graphic per color. */
    fun write(pattern: EmbPattern): ByteArray =
        "#PEC0001".toByteArray(Charsets.US_ASCII) + writePayload(pattern)

    /**
     * The normalized commands and thread colors represented by [write].
     *
     * - Moves longer than a PEC delta are split; only the final part keeps its command, the
     *   intermediate parts are JUMPs (pyembroidery's default "jump needle" contingency), so a
     *   long move never punches extra needle holes into empty fabric.
     * - Standalone trims gain a zero-distance jump because PEC only has a combined trim/jump.
     * - Color/STOP blocks without any stitch are dropped (with their unused thread) instead of
     *   rejecting the design; a change to the very same thread becomes a STOP.
     * - The first END terminates the input; a missing END is appended.
     *
     * Rejects only what PEC cannot represent: oversize bounds, > 64 distinct threads, > 255 blocks.
     */
    fun normalize(pattern: EmbPattern): EmbPattern {
        val active = pattern.stitches.takeWhile { it.command != Command.END }.toMutableList()
        pattern.stitches.firstOrNull { it.command == Command.END }?.let { active += it }
        if (active.isNotEmpty()) {
            val width = active.maxOf { it.x }.toLong() - active.minOf { it.x }
            val height = active.maxOf { it.y }.toLong() - active.minOf { it.y }
            require(width <= 65535 && height <= 65535) { "PEC bounds exceed unsigned 16-bit dimensions" }
        }

        val out = ArrayList<Stitch>()
        var x = 0
        var y = 0
        fun motion(toX: Int, toY: Int, command: Command) {
            val dx = toX.toLong() - x
            val dy = toY.toLong() - y
            val parts = maxOf(1L, (maxOf(abs(dx), abs(dy)) + MAX_DELTA - 1) / MAX_DELTA).toInt()
            val startX = x
            val startY = y
            for (i in 1..parts) {
                x = (startX + Math.rint(dx.toDouble() * i / parts).toLong()).toInt()
                y = (startY + Math.rint(dy.toDouble() * i / parts).toLong()).toInt()
                out += Stitch(x, y, if (i == parts) command else Command.JUMP)
            }
        }
        for (s in active) {
            when (s.command) {
                Command.STITCH, Command.JUMP -> motion(s.x, s.y, s.command)
                else -> {
                    if (x != s.x || y != s.y) motion(s.x, s.y, Command.JUMP)
                    out += Stitch(x, y, s.command)
                }
            }
        }
        if (out.lastOrNull()?.command != Command.END) out += Stitch(x, y, Command.END)
        // Interpolating the initial move from (0,0) can enlarge the serialized bounds.
        require(out.maxOf { it.x }.toLong() - out.minOf { it.x } <= 65535 &&
            out.maxOf { it.y }.toLong() - out.minOf { it.y } <= 65535) {
            "Normalized PEC bounds exceed unsigned 16-bit dimensions"
        }

        val (blocks, threadIndices) = dropEmptyBlocks(out, pattern)
        val threads = threadIndices.map(pattern::threadAt)
        val indices = mapPalette(threads)
        require(1 + blocks.count { it.command == Command.STOP || it.command == Command.COLOR_CHANGE } <= 255) {
            "PEC supports at most 255 color blocks, including STOP duplicates"
        }
        val canonical = ArrayList<Stitch>(blocks.size)
        blocks.forEachIndexed { i, s ->
            canonical += s
            if (s.command == Command.TRIM && blocks.getOrNull(i + 1)?.command != Command.JUMP) {
                canonical += s.copy(command = Command.JUMP)
            }
        }
        return EmbPattern(canonical, indices.map(PecThreads::forIndex), pattern.metadata.toMap())
    }

    /**
     * Removes COLOR_CHANGE/STOP boundaries around blocks that sew nothing (common at the end of
     * imported designs). Motion commands inside dropped blocks are kept so positions stay right.
     * Returns the commands and, per remaining color block, the source thread index.
     */
    private fun dropEmptyBlocks(commands: List<Stitch>, pattern: EmbPattern): Pair<List<Stitch>, List<Int>> {
        val segments = mutableListOf(mutableListOf<Stitch>())
        val boundaries = mutableListOf<Command>()
        for (s in commands) {
            if (s.command == Command.COLOR_CHANGE || s.command == Command.STOP) {
                boundaries += s.command
                segments += mutableListOf<Stitch>()
            } else {
                segments.last() += s
            }
        }
        val segmentThread = IntArray(segments.size)
        var thread = 0
        for (i in segments.indices) {
            segmentThread[i] = thread
            if (i < boundaries.size && boundaries[i] == Command.COLOR_CHANGE) thread++
        }
        val kept = segments.indices.filter { i -> segments[i].any { it.command == Command.STITCH } }.toSet()
        val result = ArrayList<Stitch>(commands.size)
        val threads = mutableListOf<Int>()
        var x = 0
        var y = 0
        for (i in segments.indices) {
            if (i in kept) {
                val t = segmentThread[i]
                if (threads.isEmpty()) {
                    threads += t
                } else if (pattern.threadAt(threads.last()).sameThread(pattern.threadAt(t))) {
                    result += Stitch(x, y, Command.STOP)
                } else {
                    result += Stitch(x, y, Command.COLOR_CHANGE)
                    threads += t
                }
            }
            for (s in segments[i]) { result += s; x = s.x; y = s.y }
        }
        if (threads.isEmpty()) threads += 0
        return result to threads
    }

    /** PEC block without #PEC0001, suitable for embedding in a PES container. */
    internal fun writePayload(pattern: EmbPattern): ByteArray {
        val p = normalize(pattern)
        val palette = p.threads.map { thread ->
            (1 until PecThreads.SET.size).first { PecThreads.forIndex(it) == thread }
        }
        val colorIndices = mutableListOf(palette.first())
        var thread = 0
        for (s in p.stitches) {
            when (s.command) {
                Command.COLOR_CHANGE -> colorIndices += palette[++thread]
                Command.STOP -> colorIndices += palette[thread]
                else -> Unit
            }
        }
        val codes = ByteArrayOutputStream()
        var x = 0
        var y = 0
        var trim = false
        var colorTwo = true
        for (s in p.stitches) {
            val dx = (s.x.toLong() - x).toInt()
            val dy = (s.y.toLong() - y).toInt()
            when (s.command) {
                Command.TRIM -> trim = true
                Command.STITCH, Command.JUMP -> {
                    val flag = if (s.command == Command.STITCH) 0 else if (trim) 0x20 else 0x10
                    writeValue(codes, dx, flag)
                    writeValue(codes, dy, flag)
                    trim = false
                }
                Command.COLOR_CHANGE, Command.STOP -> {
                    codes.write(byteArrayOf(0xFE.toByte(), 0xB0.toByte(), if (colorTwo) 2 else 1))
                    colorTwo = !colorTwo
                }
                // Two-byte END also works with pyembroidery 1.5.1's look-ahead decoder.
                Command.END -> codes.write(byteArrayOf(0xFF.toByte(), 0))
            }
            x = s.x
            y = s.y
        }
        val length = 16 + codes.size()
        require(length <= 0xFFFFFF) { "PEC stitch block exceeds its 24-bit length field" }
        val output = ByteArrayOutputStream()
        // PEC label is byte-sized. Sanitize Unicode/control characters to keep offsets fixed.
        val name = (p.metadata["name"] ?: "Untitled").take(8)
            .map { if (it in ' '..'~') it else '?' }.joinToString("")
        output.write("LA:${name.padEnd(16)}\r".toByteArray(Charsets.US_ASCII))
        repeat(12) { output.write(0x20) }
        output.write(byteArrayOf(0xFF.toByte(), 0, GRAPHIC_STRIDE.toByte(), GRAPHIC_HEIGHT.toByte()))
        repeat(12) { output.write(0x20) }
        output.write(colorIndices.size - 1)
        colorIndices.forEach(output::write)
        while (output.size() < 512) output.write(0x20)
        check(output.size() == 512)
        val minX = p.stitches.minOf { it.x }
        val minY = p.stitches.minOf { it.y }
        val maxX = p.stitches.maxOf { it.x }
        val maxY = p.stitches.maxOf { it.y }
        output.u16(0)
        repeat(3) { output.write(length shr (it * 8)) }
        output.write(byteArrayOf(0x31, 0xFF.toByte(), 0xF0.toByte()))
        output.u16(maxX - minX)
        output.u16(maxY - minY)
        output.u16(0x1E0)
        output.u16(0x1B0)
        codes.writeTo(output)
        val blocks = mutableListOf(mutableListOf<Stitch>())
        p.stitches.forEach { s ->
            if (s.command == Command.STITCH) blocks.last() += s
            if (s.command == Command.COLOR_CHANGE || s.command == Command.STOP) blocks += mutableListOf<Stitch>()
        }
        fun graphic(points: List<Stitch>, buffer: Int): ByteArray {
            val image = blankGraphic()
            val scale = min((48.0 - buffer) / maxOf(1, maxX - minX), (38.0 - buffer) / maxOf(1, maxY - minY))
            val cx = (maxX.toDouble() + minX) / 2
            val cy = (maxY.toDouble() + minY) / 2
            for (s in points) {
                val gx = floor((s.x - cx) * scale + 24).toInt()
                val gy = floor((s.y - cy) * scale + 19).toInt()
                if (gx in 0..47 && gy in 0..37) {
                    val index = gy * GRAPHIC_STRIDE + gx / 8
                    image[index] = (image[index].toInt() or (1 shl (gx % 8))).toByte()
                }
            }
            return image
        }
        output.write(graphic(blocks.flatten(), 4))
        blocks.forEach { output.write(graphic(it, 5)) }
        return output.toByteArray()
    }

    private fun writeValue(output: ByteArrayOutputStream, value: Int, flag: Int) {
        require(value in -MAX_DELTA..MAX_DELTA)
        if (flag == 0 && value > -64 && value < 63) {
            output.write(value and 0x7F)
        } else {
            val long = (value and 0xFFF) or 0x8000 or (flag shl 8)
            output.write(long shr 8)
            output.write(long)
        }
    }

    /** Stable variant of build_unique_palette, using upstream's red-mean distance and ties. */
    private fun mapPalette(threads: List<EmbThread>): List<Int> {
        // Keyed by thread identity: two threads that share an RGB value still get different
        // PEC entries, so a color change between them survives the round trip.
        fun key(t: EmbThread) = t.copy(color = t.color or (0xFF shl 24))
        val colors = threads.distinctBy(::key)
        require(colors.size <= 64) { "PEC can distinguish at most 64 unique threads" }
        val assigned = LinkedHashMap<EmbThread, Int>()
        val available = (1 until PecThreads.SET.size).toMutableSet()
        for (t in colors) {
            val exact = available.firstOrNull { PecThreads.forIndex(it).sameColor(t) }
            if (exact != null) { assigned[key(t)] = exact; available.remove(exact) }
        }
        for (t in colors) {
            if (key(t) in assigned) continue
            var nearest = -1
            var distance = Int.MAX_VALUE
            for (i in available) {
                val v = PecThreads.forIndex(i)
                val mean = Math.rint((t.red + v.red) / 2.0).toInt()
                val r = t.red - v.red
                val g = t.green - v.green
                val b = t.blue - v.blue
                val d = (((512 + mean) * r * r) shr 8) + 4 * g * g + (((767 - mean) * b * b) shr 8)
                if (d <= distance) { distance = d; nearest = i }
            }
            assigned[key(t)] = nearest
            available.remove(nearest)
        }
        return threads.map { assigned.getValue(key(it)) }
    }

    private fun blankGraphic(): ByteArray = ByteArray(GRAPHIC_STRIDE * GRAPHIC_HEIGHT).also { image ->
        for (y in 1..36) {
            val row = when (y) {
                1, 36 -> intArrayOf(0xF0, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F)
                2, 35 -> intArrayOf(8, 0, 0, 0, 0, 0x10)
                3, 34 -> intArrayOf(4, 0, 0, 0, 0, 0x20)
                else -> intArrayOf(2, 0, 0, 0, 0, 0x40)
            }
            row.forEachIndexed { x, b -> image[y * GRAPHIC_STRIDE + x] = b.toByte() }
        }
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        require(value in 0..65535)
        write(value)
        write(value shr 8)
    }
}
