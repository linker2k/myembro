// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

/**
 * Prepares a design file for a Brother machine: PES files are copied byte for byte (a
 * re-encode would drop editor objects and custom threads), anything else is converted to a
 * PES with [PesWriter] (minimal v1 by default, full v6 through settings).
 */
object PesExport {
    class Result(val bytes: ByteArray, val fileName: String, val converted: Boolean)

    fun prepare(data: ByteArray, fileName: String): Result = prepare(data, fileName, PesWriteSettings())

    /** Settings select conversion output and hoop. Existing PES passes through unchanged only after exact viewport validation. */
    fun prepare(data: ByteArray, fileName: String, settings: PesWriteSettings): Result {
        val base = fileName.substringBeforeLast('.', fileName).ifBlank { "design" }
        val isPes = data.size >= 4 && String(data, 0, 4, Charsets.ISO_8859_1) == "#PES"
        return if (isPes) {
            Result(io.github.linker2k.myembro.engine.sewout.PecViewportCheck.requireValid(data, settings.hoop), "$base.pes", converted = false)
        } else {
            Result(PesWriter.write(PatternReader.read(data, fileName), settings), "$base.pes", converted = true)
        }
    }

    /** First of `name`, `name (1)`, `name (2)`… whose file name is not [taken]. */
    fun uniqueName(fileName: String, taken: (String) -> Boolean): String {
        if (!taken(fileName)) return fileName
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
            if (!taken(candidate)) return candidate
            n++
        }
    }
}
