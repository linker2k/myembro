// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from pyembroidery 1.5.1 ExpReader.py (MIT).
package io.github.linker2k.myembro.engine.io
import io.github.linker2k.myembro.engine.model.PatternBuilder

object ExpReader {
    fun read(data: ByteArray) = PatternBuilder().apply {
        val f = ByteReader(data)
        while (f.remaining >= 2) {
            val a = f.u8()!!; val b = f.u8()!!
            if (a != 0x80) { stitch(a.toByte().toInt(), -b.toByte().toInt()); continue }
            if (f.remaining < 2) { warnings += "Truncated EXP control record"; break }
            val x = f.u8()!!.toByte().toInt(); val y = -f.u8()!!.toByte().toInt()
            when (b) {
                0x80 -> trim()
                0x02 -> stitch(x, y)
                0x04 -> move(x, y)
                0x01 -> { colorChange(); if (x != 0 || y != 0) move(x, y) }
                else -> break
            }
        }
        if (f.remaining != 0) warnings += "Truncated EXP stitch data"
        end()
    }.build()
}
