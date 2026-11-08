// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from pyembroidery 1.5.1 Vp3Reader.py (MIT).
package io.github.linker2k.myembro.engine.io
import io.github.linker2k.myembro.engine.model.*

object Vp3Reader {
    fun read(data: ByteArray): EmbPattern {
        if (!data.take(6).toByteArray().contentEquals("%vsm%\u0000".toByteArray())) throw EmbFormatException("Invalid VP3 signature")
        val f = ByteReader(data)
        fun need(v: Int?) = v ?: throw EmbFormatException("Truncated VP3 data")
        fun be16() = (need(f.u8()) shl 8) or need(f.u8())
        fun be32() = (be16() shl 16) or be16()
        fun skip(n: Int) {
            if (n < 0 || n > f.remaining) throw EmbFormatException("Invalid VP3 block length")
            f.skip(n)
        }
        fun skipString() { skip(be16()) }
        fun string(): String {
            val n = be16()
            if (n > f.remaining) throw EmbFormatException("Truncated VP3 string")
            return f.bytes(n).toString(Charsets.UTF_8)
        }
        val out = PatternBuilder()
        var px = 0; var py = 0
        fun add(c: Command, dx: Int = 0, dy: Int = 0) { out.add(c, dx, dy); px += dx; py += dy }
        skip(6); skipString(); skip(7); skipString(); skip(32)
        val centerX = be32() / 100.0; val centerY = -be32() / 100.0
        skip(27); skipString(); skip(24); skipString()
        val count = be16()
        repeat(count) { block ->
            skip(3)
            val length = be32()
            if (length < 0 || length > f.remaining) throw EmbFormatException("Invalid VP3 color block length")
            val end = f.position + length
            val oldLimit = f.limit; f.limit = end
            val ax = be32() / 100.0 + centerX; val ay = -be32() / 100.0 + centerY
            // Preserve upstream's AND condition, including axis-zero behavior.
            if (ax != 0.0 && ay != 0.0) add(Command.JUMP, ax.toInt() - px, ay.toInt() - py)
            val colors = need(f.u8()); skip(1)
            var rgb = 0
            repeat(colors) { rgb = need(f.u24be()); skip(3) }
            skip(2)
            val catalog = string(); val description = string(); val brand = string()
            out.threads += EmbThread(0xff000000.toInt() or rgb, description = description, catalogNumber = catalog, brand = brand)
            skip(15); skip(3)
            while (f.remaining >= 2) {
                val a = need(f.u8()); val b = need(f.u8())
                if (a != 0x80) { add(Command.STITCH, a.toByte().toInt(), b.toByte().toInt()); continue }
                when (b) {
                    1 -> { val x = be16().toShort().toInt(); val y = be16().toShort().toInt(); add(Command.STITCH, x, y); skip(2) }
                    3 -> add(Command.TRIM)
                }
            }
            f.seek(end); f.limit = oldLimit
            if (block + 1 < count) add(Command.COLOR_CHANGE)
        }
        out.end()
        return out.build()
    }
}
