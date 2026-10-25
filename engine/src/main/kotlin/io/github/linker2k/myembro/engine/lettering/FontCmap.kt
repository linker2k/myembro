// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Code points a TTF/OTF file itself maps to glyphs. Android's `Paint.hasGlyph` also counts the
 * system fallback fonts, so a Latin-only font would "have" Hangul and be measured with the
 * fallback's glyphs. Reads only the `cmap` table (formats 4 and 12).
 */
class FontCmap private constructor(private val starts: IntArray, private val ends: IntArray) {
    operator fun contains(code: Int): Boolean {
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                code < starts[mid] -> hi = mid - 1
                code > ends[mid] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    fun ranges():List<IntRange> = starts.indices.map { starts[it]..ends[it] }

    fun has(text: String): Boolean = text.codePoints().allMatch { it in this }

    companion object {
        /** Null when the file is not a readable sfnt with a Unicode cmap. */
        fun read(file: File): FontCmap? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                fun bytes(offset: Long, length: Int): ByteBuffer {
                    val b = ByteArray(length)
                    raf.seek(offset)
                    raf.readFully(b)
                    return ByteBuffer.wrap(b)
                }
                val head = bytes(0, 12)
                val numTables = head.getShort(4).toInt() and 0xFFFF
                val dir = bytes(12, 16 * numTables)
                val entry = (0 until numTables).firstOrNull { dir.getInt(16 * it) == 0x636D6170 } ?: return@use null // 'cmap'
                val offset = dir.getInt(16 * entry + 8).toLong() and 0xFFFFFFFFL
                val length = dir.getInt(16 * entry + 12)
                parse(bytes(offset, length))
            }
        }.getOrNull()

        /** Parse a caller-supplied Unicode cmap table; buffer semantics are unchanged from the app reader. */
        fun parse(cmap: ByteBuffer): FontCmap? {
            val count = cmap.getShort(2).toInt() and 0xFFFF
            // Prefer the full-Unicode format 12 subtable, then any Unicode BMP format 4.
            val subtables = (0 until count).map { i ->
                val platform = cmap.getShort(4 + 8 * i).toInt() and 0xFFFF
                val encoding = cmap.getShort(6 + 8 * i).toInt() and 0xFFFF
                Triple(platform, encoding, cmap.getInt(8 + 8 * i))
            }.filter { (p, e, _) -> p == 0 || (p == 3 && (e == 1 || e == 10)) }
            val formats = subtables.map { it.third to (cmap.getShort(it.third).toInt() and 0xFFFF) }
            val chosen = formats.firstOrNull { it.second == 12 } ?: formats.firstOrNull { it.second == 4 } ?: return null
            val (at, format) = chosen
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            if (format == 12) {
                val groups = cmap.getInt(at + 12)
                for (g in 0 until groups) {
                    starts += cmap.getInt(at + 16 + 12 * g)
                    ends += cmap.getInt(at + 20 + 12 * g)
                }
            } else {
                val segX2 = cmap.getShort(at + 6).toInt() and 0xFFFF
                val endAt = at + 14
                val startAt = endAt + segX2 + 2
                val deltaAt = startAt + segX2
                val rangeAt = deltaAt + segX2
                for (s in 0 until segX2 / 2) {
                    val end = cmap.getShort(endAt + 2 * s).toInt() and 0xFFFF
                    val start = cmap.getShort(startAt + 2 * s).toInt() and 0xFFFF
                    if (start == 0xFFFF) continue
                    val delta = cmap.getShort(deltaAt + 2 * s).toInt()
                    val rangeOffset = cmap.getShort(rangeAt + 2 * s).toInt() and 0xFFFF
                    if (rangeOffset == 0) {
                        // Every code maps to (code + delta) mod 65536; glyph 0 means missing.
                        var c = start
                        while (c <= end) {
                            val runStart = c
                            while (c <= end && (c + delta) and 0xFFFF != 0) c++
                            if (c > runStart) { starts += runStart; ends += c - 1 }
                            c++
                        }
                    } else {
                        for (c in start..end) {
                            val glyphAt = rangeAt + 2 * s + rangeOffset + 2 * (c - start)
                            val glyph = cmap.getShort(glyphAt).toInt() and 0xFFFF
                            if (glyph != 0) { starts += c; ends += c }
                        }
                    }
                }
            }
            val order = starts.indices.sortedBy { starts[it] }
            return FontCmap(IntArray(order.size) { starts[order[it]] }, IntArray(order.size) { ends[order[it]] })
        }
    }
}
