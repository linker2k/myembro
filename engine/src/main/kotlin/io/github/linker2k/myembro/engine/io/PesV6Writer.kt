// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.*
import java.io.ByteArrayOutputStream

/** Port of pyembroidery 1.5.1 PesWriter.py write_version_6/header/blocks/addendum (MIT).
 * Uses our shared PEC normalization to retain explicit trims and avoid extra needle holes.
 */
internal object PesV6Writer {
    private data class Segment(val flag: Int, val color: Int, val points: List<Pair<Int, Int>>)

    fun write(pattern: EmbPattern, inkstitchCommands: Boolean = false): ByteArray {
        val p = PecWriter.normalizePreservingThreads(pattern, inkstitchCommands)
        val chart = arrayListOf(p.threadAt(0))
        var thread = 0
        val editorStitches = p.stitches.map { s ->
            when (s.command) {
                Command.COLOR_CHANGE -> { chart += p.threadAt(++thread); s }
                Command.STOP -> { chart += p.threadAt(thread); s.copy(command = Command.COLOR_CHANGE) }
                else -> s
            }
        }
        val body = Buffer()
        body.ascii("#PES0060"); body.u32(0)
        val hasObjects = p.stitchCount != 0
        header(body, p.metadata, chart, hasObjects)
        if (hasObjects) {
            body.u16(0xFFFF); body.u16(0)
            val logs = blocks(body, editorStitches, chart, topLeftViewport = !inkstitchCommands)
            body.u32(0); body.u32(0)
            logs.indices.forEach { body.u32(it); body.u32(0) }
        } else {
            body.u16(0); body.u16(0)
        }
        val pecOffset = body.size()
        val payload = PecWriter.writePayload(p, inkstitchCommands, topLeftViewport = !inkstitchCommands)
        body.write(payload)
        // Upstream color_info includes the count byte followed by PEC palette indices.
        val count = (payload[48].toInt() and 255) + 1
        body.write(payload, 48, count + 1)
        repeat(maxOf(0, 128 - count - 1)) { body.write(0x20) }
        repeat(chart.size) { body.write(ByteArray(0x90)) }
        chart.forEach { t -> body.write(t.blue); body.write(t.green); body.write(t.red) }
        body.u16(0)
        return body.toByteArray().also { bytes ->
            repeat(4) { bytes[8 + it] = (pecOffset ushr (8 * it)).toByte() }
        }
    }

    private fun header(out: Buffer, metadata: Map<String, String>, chart: List<EmbThread>, objects: Boolean) {
        out.u16(1); out.ascii("02")
        for (key in listOf("name", "category", "author", "keywords", "comments")) out.string8(metadata[key])
        // pyembroidery's v6 design-page defaults; the declared hoop dimensions are 100x100 mm.
        for (v in listOf(0, 0, 100, 100, 0, 200, 200, 100, 100, 100, 7, 19, 1, 1, 0, 100, 1, 0)) out.u16(v)
        out.write(0) // image filename length
        for (v in listOf(1f, 0f, 0f, 1f, 0f, 0f)) out.f32(v)
        repeat(3) { out.u16(0) } // programmable fill, motif, feather counts
        out.u16(chart.size)
        for (t in chart) {
            out.string8(t.catalogNumber)
            out.write(t.red); out.write(t.green); out.write(t.blue)
            out.write(0); out.u32(0xA)
            out.string8(t.description); out.string8(t.brand); out.string8(t.chart)
        }
        out.u16(if (objects) 1 else 0)
    }

    private fun blocks(out: Buffer, stitches: List<Stitch>, chart: List<EmbThread>, topLeftViewport: Boolean = false): List<Pair<Int, Int>> {
        val minX = if (topLeftViewport) 0 else stitches.minOf { it.x }
        val maxX = stitches.maxOf { it.x }
        val minY = if (topLeftViewport) 0 else stitches.minOf { it.y }
        val maxY = stitches.maxOf { it.y }
        val width = maxX - minX
        val height = maxY - minY
        out.string16("CEmbOne")
        repeat(8) { out.u16(0) } // bounding-box fields, as in upstream
        for (v in listOf(1f, 0f, 0f, 1f, 1000f - width / 2f, 1000f + height / 2f)) out.f32(v)
        for (v in listOf(1, 0, 0, width, height)) out.u16(v)
        out.write(ByteArray(8))
        val sectionsOffset = out.size()
        out.u16(0)
        out.u16(0xFFFF); out.u16(0)
        out.string16("CSewSeg")
        val segments = mutableListOf<Segment>()
        var index = 0
        var thread = 0
        var sewnX = 0
        var sewnY = 0
        while (index < stitches.size) {
            val command = stitches[index].command
            var end = index + 1
            while (end < stitches.size && stitches[end].command == command) end++
            when (command) {
                Command.COLOR_CHANGE -> thread += end - index
                Command.JUMP -> segments += Segment(1, nearest(chart[thread], chart), listOf(
                    (sewnX - minX) to (sewnY - maxY),
                    (stitches[end - 1].x - minX) to (stitches[end - 1].y - maxY),
                ))
                Command.STITCH -> {
                    // Preserve continuity when splitting a section too large for its u16 count.
                    var start = index
                    while (start < end) {
                        val until = minOf(end, start + 65535)
                        val points = (start until until).map { (stitches[it].x - minX) to (stitches[it].y - maxY) }
                        segments += Segment(0, nearest(chart[thread], chart), points)
                        if (until == end) break
                        start = until - 1
                    }
                    sewnX = stitches[end - 1].x; sewnY = stitches[end - 1].y
                }
                else -> Unit
            }
            index = end
        }
        require(segments.size <= 65535) { "PES v6 supports at most 65535 sewing sections" }
        val log = mutableListOf<Pair<Int, Int>>()
        for ((i, segment) in segments.withIndex()) {
            if (i != 0) out.u16(0x8003)
            if (log.lastOrNull()?.second != segment.color) log += i to segment.color
            out.u16(segment.flag); out.u16(segment.color); out.u16(segment.points.size)
            for ((x, y) in segment.points) {
                require(x in -32768..32767 && y in -32768..32767) { "PES v6 sewing coordinates exceed signed 16-bit range" }
                out.u16(x and 65535); out.u16(y and 65535)
            }
        }
        out.u16(log.size)
        for ((section, color) in log) { out.u16(section); out.u16(color) }
        out.u16(0); out.u16(0)
        out.patch16(sectionsOffset, segments.size)
        return log
    }

    /** Same red-mean metric and last-on-tie behavior as EmbThread.find_nearest_color_index. */
    private fun nearest(thread: EmbThread, chart: List<EmbThread>): Int = chart.indices.minWith(
        compareBy<Int> { i ->
            val other = chart[i]
            val mean = Math.rint((thread.red + other.red) / 2.0).toInt()
            val r = thread.red - other.red; val g = thread.green - other.green; val b = thread.blue - other.blue
            (((512 + mean) * r * r) shr 8) + 4 * g * g + (((767 - mean) * b * b) shr 8)
        }.thenByDescending { it },
    )

    private class Buffer : ByteArrayOutputStream() {
        fun ascii(text: String) = write(text.toByteArray(Charsets.US_ASCII))
        fun u16(value: Int) {
            require(value in 0..65535)
            write(value); write(value ushr 8)
        }
        fun u32(value: Int) { repeat(4) { write(value ushr (8 * it)) } }
        fun f32(value: Float) = u32(value.toRawBits())
        fun string16(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); u16(bytes.size); write(bytes) }
        fun string8(value: String?) {
            val bytes = value?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
            // 0xFF is the MFC CArchive escape for longer lengths, so a one-byte length stops at 254.
            var length = minOf(254, bytes.size)
            while (length > 0 && length < bytes.size && bytes[length].toInt() and 0xC0 == 0x80) length--
            write(length); write(bytes, 0, length)
        }
        fun patch16(offset: Int, value: Int) { buf[offset] = value.toByte(); buf[offset + 1] = (value ushr 8).toByte() }
    }
}
