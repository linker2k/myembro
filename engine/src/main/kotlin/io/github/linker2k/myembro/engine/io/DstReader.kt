// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.PatternBuilder

/** Tajima DST reader, ported from pyembroidery (MIT) DstReader.py. Sequin commands are read as jumps. */
object DstReader {
    private fun bit(b: Int, pos: Int) = (b shr pos) and 1

    private fun decodeDx(b0: Int, b1: Int, b2: Int): Int =
        bit(b2, 2) * 81 - bit(b2, 3) * 81 +
            bit(b1, 2) * 27 - bit(b1, 3) * 27 +
            bit(b0, 2) * 9 - bit(b0, 3) * 9 +
            bit(b1, 0) * 3 - bit(b1, 1) * 3 +
            bit(b0, 0) - bit(b0, 1)

    private fun decodeDy(b0: Int, b1: Int, b2: Int): Int = -(
        bit(b2, 5) * 81 - bit(b2, 4) * 81 +
            bit(b1, 5) * 27 - bit(b1, 4) * 27 +
            bit(b0, 5) * 9 - bit(b0, 4) * 9 +
            bit(b1, 7) * 3 - bit(b1, 6) * 3 +
            bit(b0, 7) - bit(b0, 6)
        )

    fun read(data: ByteArray, trimAt: Int = 3): EmbPattern {
        val f = ByteReader(data)
        val out = PatternBuilder()
        readHeader(f.bytes(512), out)
        while (true) {
            val b0 = f.u8() ?: break
            val b1 = f.u8() ?: break
            val b2 = f.u8() ?: break
            val dx = decodeDx(b0, b1, b2)
            val dy = decodeDy(b0, b1, b2)
            when {
                b2 and 0b11110011 == 0b11110011 -> break
                b2 and 0b11000011 == 0b11000011 -> out.colorChange(dx, dy)
                b2 and 0b01000011 == 0b01000011 -> out.add(Command.JUMP, dx, dy) // sequin mode toggle
                b2 and 0b10000011 == 0b10000011 -> out.move(dx, dy)
                else -> out.stitch(dx, dy)
            }
        }
        out.end()
        out.interpolateTrims(trimAt, null, clipping = true)
        return out.build()
    }

    private fun readHeader(header: ByteArray, out: PatternBuilder) {
        val text = String(header, Charsets.ISO_8859_1)
        for (raw in text.split('\r', '\n')) {
            val line = raw.trim()
            if (line.length <= 3) continue
            val prefix = line.substring(0, 2).trim()
            val value = line.substring(3).trim()
            when (prefix) {
                "LA" -> out.metadata["name"] = value
                "AU" -> out.metadata["author"] = value
                "CP" -> out.metadata["copyright"] = value
                "TC" -> {
                    val parts = value.split(",").map { it.trim() }
                    val rgb = parts.getOrNull(0)?.removePrefix("#")?.toIntOrNull(16) ?: continue
                    out.threads += EmbThread(
                        color = (0xFF shl 24) or rgb,
                        description = parts.getOrNull(1),
                        catalogNumber = parts.getOrNull(2),
                    )
                }
                else -> out.metadata[prefix] = value
            }
        }
    }
}
