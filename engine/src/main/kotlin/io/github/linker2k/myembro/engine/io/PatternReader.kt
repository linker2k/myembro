// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.EmbPattern

/** Picks a reader from the file contents, falling back to the file name for headerless formats. */
object PatternReader {
    fun read(data: ByteArray, fileName: String? = null): EmbPattern {
        val magic = String(data.copyOfRange(0, minOf(4, data.size)), Charsets.ISO_8859_1)
        return when {
            magic == "#PES" || magic == "#PEC" -> PesReader.read(data)
            fileName?.lowercase()?.endsWith(".dst") == true -> DstReader.read(data)
            else -> throw EmbFormatException("Unsupported file: ${fileName ?: "unknown"} (PES, PEC and DST are supported)")
        }
    }
}
