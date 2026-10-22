// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.PatternBuilder

/**
 * Brother PES/PEC reader, ported from pyembroidery (MIT) PesReader.py / PecReader.py.
 *
 * A PES file is a header (versions 1–10, carrying metadata and, from v5, the real thread
 * list) followed by a PEC block holding the actual stitches. We read only what the PES header
 * lets us read safely, then always decode stitches from the PEC block.
 */
object PesReader {
    private const val JUMP_CODE = 0x10
    private const val TRIM_CODE = 0x20
    private const val FLAG_LONG = 0x80

    fun read(data: ByteArray): EmbPattern {
        val f = ByteReader(data)
        val out = PatternBuilder()
        val magic = f.string(8)
        if (magic == "#PEC0001") {
            readPec(f, out, mutableListOf())
        } else {
            if (!magic.startsWith("#PES")) throw EmbFormatException("Not a PES file (magic '$magic')")
            val pecBlockPosition = f.s32le() ?: throw EmbFormatException("Truncated PES header")
            if (pecBlockPosition !in 12 until data.size) {
                throw EmbFormatException("PEC block offset $pecBlockPosition is outside the file")
            }
            val version = magic.substring(4)
            out.metadata["version"] = version
            // The optional header may not run into the PEC block, and is applied only if it
            // parses completely: a malformed header must not stop us from reading the stitches.
            val header = ByteReader(data, limit = pecBlockPosition).apply { seek(12) }
            val headerMeta = LinkedHashMap<String, String>()
            val chart = mutableListOf<EmbThread>()
            try {
                readHeader(version, header, headerMeta, chart)
                out.metadata += headerMeta
            } catch (e: TruncatedException) {
                chart.clear()
                out.warnings += "PES header is incomplete; using PEC colors"
            }
            f.seek(pecBlockPosition)
            readPec(f, out, chart)
        }
        out.interpolateDuplicateColorAsStop()
        return out.build()
    }

    private class TruncatedException : Exception()

    private fun <T> need(v: T?): T = v ?: throw TruncatedException()

    private fun readHeader(version: String, f: ByteReader, out: MutableMap<String, String>, chart: MutableList<EmbThread>) {
        when (version) {
            "0100" -> headerV9V10(f, out, chart, afterHoop = 38)
            "0090" -> headerV9V10(f, out, chart, afterHoop = 30)
            "0080" -> headerV5toV8(f, out, chart, beforeImage = 38, afterImage = 26)
            "0070", "0060" -> headerV5toV8(f, out, chart, beforeImage = 36, afterImage = 24)
            "0050", "0055", "0056" -> headerV5toV8(f, out, chart, beforeImage = 24, afterImage = 24)
            "0040" -> { f.skip(4); readMetadata(f, out) }
        }
    }

    /** Length-prefixed string; null when empty. Throws if the header ends mid-string. */
    private fun pesString(f: ByteReader): String? {
        val length = need(f.u8())
        if (length == 0) return null
        if (f.remaining < length) throw TruncatedException()
        return String(f.bytes(length), Charsets.UTF_8)
    }

    private fun putIfPresent(out: MutableMap<String, String>, key: String, value: String?) {
        if (!value.isNullOrEmpty()) out[key] = value
    }

    private fun readMetadata(f: ByteReader, out: MutableMap<String, String>) {
        for (key in listOf("name", "category", "author", "keywords", "comments")) {
            putIfPresent(out, key, pesString(f))
        }
    }

    private fun headerV5toV8(
        f: ByteReader, out: MutableMap<String, String>, chart: MutableList<EmbThread>, beforeImage: Int, afterImage: Int,
    ) {
        f.skip(4)
        readMetadata(f, out)
        f.skip(beforeImage)
        putIfPresent(out, "image_file", pesString(f))
        f.skip(afterImage)
        readThreadList(f, chart)
    }

    private fun headerV9V10(f: ByteReader, out: MutableMap<String, String>, chart: MutableList<EmbThread>, afterHoop: Int) {
        f.skip(4)
        readMetadata(f, out)
        f.skip(14)
        putIfPresent(out, "hoop_name", pesString(f))
        f.skip(afterHoop)
        putIfPresent(out, "image_file", pesString(f))
        f.skip(34)
        readThreadList(f, chart)
    }

    /** Thread list follows three counts; if any are non-zero the layout is too complex to skip. */
    private fun readThreadList(f: ByteReader, chart: MutableList<EmbThread>) {
        repeat(3) { if (need(f.u16le()) != 0) return }
        val count = need(f.u16le())
        repeat(count) {
            val catalog = pesString(f)
            val rgb = need(f.u24be())
            f.skip(5)
            chart += EmbThread(
                color = (0xFF shl 24) or rgb,
                catalogNumber = catalog,
                description = pesString(f),
                brand = pesString(f),
                chart = pesString(f),
            )
        }
    }

    private fun readPec(f: ByteReader, out: PatternBuilder, chart: MutableList<EmbThread>) {
        f.skip(3) // "LA:"
        val label = f.string(16).trim()
        if (label.isNotEmpty()) out.metadata.putIfAbsent("name", label)
        f.skip(0xF)
        f.skip(2) // graphic stride, graphic height
        f.skip(0xC)
        val colorCountPos = f.position
        val colorChanges = f.u8() ?: throw EmbFormatException("Truncated PEC header")
        // The count is "color changes", i.e. colors - 1; 0xFF encodes a design with no colors.
        val colorCount = if (colorChanges == 0xFF) 0 else colorChanges + 1
        val colorBytes = f.bytes(colorCount).map { it.toInt() and 0xFF }
        if (colorBytes.size < colorCount) throw EmbFormatException("Truncated PEC color table")
        mapPecColors(colorBytes, out, chart)
        f.seek(colorCountPos + 0x1D2)
        // Block length counts from 5 bytes before the end of this field.
        val blockLength = f.u24le() ?: throw EmbFormatException("Truncated PEC header")
        val blockEnd = f.position - 5 + blockLength
        f.skip(0x0B)
        // Without a trustworthy block end we could decode the thumbnails as stitches.
        if (blockEnd <= f.position || blockEnd > f.size) {
            throw EmbFormatException("PEC stitch block length $blockLength is invalid")
        }
        f.limit = blockEnd
        readPecStitches(f, out)
    }

    private fun mapPecColors(colorBytes: List<Int>, out: PatternBuilder, chart: MutableList<EmbThread>) {
        when {
            chart.isEmpty() -> colorBytes.forEach { out.threads += PecThreads.forIndex(it) }
            chart.size >= colorBytes.size -> out.threads += chart
            else -> {
                // Tabled mode: each distinct PEC index takes the next thread from the PES chart.
                val map = HashMap<Int, EmbThread>()
                for (b in colorBytes) {
                    val idx = b % PecThreads.SET.size
                    val thread = map.getOrPut(idx) {
                        if (chart.isNotEmpty()) chart.removeAt(0) else PecThreads.forIndex(idx)
                    }
                    out.threads += thread
                }
            }
        }
    }

    private fun signed12(b: Int): Int {
        val v = b and 0xFFF
        return if (v > 0x7FF) v - 0x1000 else v
    }

    private fun signed7(b: Int): Int = if (b > 63) b - 128 else b

    private fun readPecStitches(f: ByteReader, out: PatternBuilder) {
        // 0xFF in the first byte is the end marker: no valid long-form coordinate sets bit 6.
        var ended = false
        while (true) {
            val val1 = f.u8() ?: break
            if (val1 == 0xFF) { ended = true; break }
            var val2 = f.u8() ?: break
            if (val1 == 0xFE && val2 == 0xB0) {
                f.u8() ?: break // color index, unused: colors come from the color table
                out.colorChange()
                continue
            }
            var jump = false
            var trim = false
            val x: Int
            if (val1 and FLAG_LONG != 0) {
                if (val1 and TRIM_CODE != 0) trim = true
                if (val1 and JUMP_CODE != 0) jump = true
                x = signed12((val1 shl 8) or val2)
                val2 = f.u8() ?: break
            } else {
                x = signed7(val1)
            }
            val y: Int
            if (val2 and FLAG_LONG != 0) {
                if (val2 and TRIM_CODE != 0) trim = true
                if (val2 and JUMP_CODE != 0) jump = true
                val val3 = f.u8() ?: break
                y = signed12((val2 shl 8) or val3)
            } else {
                y = signed7(val2)
            }
            when {
                jump -> out.move(x, y)
                trim -> { out.trim(); out.move(x, y) }
                else -> out.stitch(x, y)
            }
        }
        if (!ended) out.warnings += "Stitch data is truncated; showing the part that could be read"
        out.end()
    }
}

class EmbFormatException(message: String) : Exception(message)
