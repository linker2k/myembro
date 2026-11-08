// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from pyembroidery 1.5.1 JefReader.py (MIT), including default trim inference.
package io.github.linker2k.myembro.engine.io
import io.github.linker2k.myembro.engine.model.*

object JefReader {
    fun read(data: ByteArray): EmbPattern {
        val f = ByteReader(data)
        if (data.size < 116) throw EmbFormatException("JEF header is truncated")
        val offset = f.s32le()!!
        f.skip(20)
        val count = f.s32le()!!
        if (count < 0 || count > (data.size - 116) / 4 || offset < 116L + count * 4L || offset > data.size)
            throw EmbFormatException("Invalid JEF palette or stitch offset")
        f.skip(88)
        val palette = MutableList(count) {
            val index = kotlin.math.abs(f.s32le()!!.toLong())
            if (index == 0L) null else JefThreads.thread((index % JefThreads.size).toInt())
        }
        val out = PatternBuilder()
        var colorIndex = 1
        f.seek(offset)
        while (f.remaining >= 2) {
            val a = f.u8()!!; val b = f.u8()!!
            if (a != 0x80) { out.stitch(a.toByte().toInt(), -b.toByte().toInt()); continue }
            // Upstream consumes a second pair even for END (our writer ends with 80 10).
            if (f.remaining < 2) break
            val x = f.u8()!!.toByte().toInt(); val y = -f.u8()!!.toByte().toInt()
            when (b) {
                2 -> out.move(x, y)
                1 -> {
                    if (colorIndex >= palette.size) throw EmbFormatException("JEF color change exceeds palette")
                    if (palette[colorIndex] == null) { out.add(Command.STOP); palette.removeAt(colorIndex) }
                    else { out.colorChange(); colorIndex++ }
                }
                else -> break
            }
        }
        out.threads.addAll(palette.filterNotNull())
        out.end()
        out.interpolateTrims(null, 30, clipping = true)
        return out.build()
    }
}
