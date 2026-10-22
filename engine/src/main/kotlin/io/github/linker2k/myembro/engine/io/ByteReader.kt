// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

/**
 * Seekable little/big-endian reader over an in-memory file. Reads at or past [limit] return
 * null, so a section parser cannot run into the data that follows it.
 */
class ByteReader(private val data: ByteArray, var limit: Int = data.size) {
    var position: Int = 0
    val size: Int get() = data.size

    /** Bytes left before [limit]. */
    val remaining: Int get() = (limit - position).coerceAtLeast(0)

    fun seek(pos: Int) { position = pos }
    fun skip(n: Int) { position += n }

    fun u8(): Int? =
        if (position in 0 until minOf(limit, data.size)) data[position++].toInt() and 0xFF else { position++; null }

    fun u16le(): Int? {
        val a = u8() ?: return null
        val b = u8() ?: return null
        return a or (b shl 8)
    }

    fun u24le(): Int? {
        val a = u8() ?: return null
        val b = u8() ?: return null
        val c = u8() ?: return null
        return a or (b shl 8) or (c shl 16)
    }

    fun u24be(): Int? {
        val a = u8() ?: return null
        val b = u8() ?: return null
        val c = u8() ?: return null
        return (a shl 16) or (b shl 8) or c
    }

    fun s32le(): Int? {
        val lo = u16le() ?: return null
        val hi = u16le() ?: return null
        return lo or (hi shl 16)
    }

    fun bytes(n: Int): ByteArray {
        val end = minOf(limit, data.size)
        val from = position.coerceIn(0, end)
        val to = (position + n).coerceIn(from, end)
        position += n
        return data.copyOfRange(from, to)
    }

    /** Latin-1 string of [n] bytes (PES/PEC strings are 8-bit). */
    fun string(n: Int): String = String(bytes(n), Charsets.ISO_8859_1)
}
