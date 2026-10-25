// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.sewout.*

/**
 * Prepares a design file for a Brother machine: PES files are copied byte for byte (a
 * re-encode would drop editor objects and custom threads), anything else is converted to a
 * PES with [PesWriter] (minimal v1 by default, full v6 through settings).
 */
object PesExport {
    class Result(val bytes: ByteArray, val fileName: String, val converted: Boolean,
        val hoop: HoopSpec = HoopSpec.H100, val origin: DesignOrigin = DesignOrigin.IMPORTED)

    fun prepare(data: ByteArray, fileName: String): Result = prepare(data, fileName, PesWriteSettings())

    /** Validate the exact released bytes. Existing PES is never re-encoded or repaired. */
    fun requireSafe(bytes: ByteArray, hoop: HoopSpec = HoopSpec.H100,
        origin: DesignOrigin = DesignOrigin.IMPORTED): ByteArray {
        PecViewportCheck.requireValid(bytes, hoop)
        // Machine choice belongs to the caller; this boundary enforces the selected hoop
        // and mandatory sewout rules without inventing device-specific capacity limits.
        val report = SewoutCheck.checkPes(bytes,
            machine = SewoutMachine("output", "Selected hoop", Specification(), Specification(), knownHoopsMm = emptySet()),
            options = SewoutOptions(hoop = hoop, origin = origin))
        require(report.analysisComplete && !report.hasBlockingIssues) {
            report.findings.filter { it.severity == Severity.BLOCKER }.joinToString("; ") { "${it.code}: ${it.message}" }
                .ifBlank { "ANALYSIS_INCOMPLETE" }
        }
        return bytes
    }

    /** Settings select conversion output and hoop. Existing PES passes through only after both safety checks. */
    fun prepare(data: ByteArray, fileName: String, settings: PesWriteSettings): Result {
        val base = fileName.substringBeforeLast('.', fileName).ifBlank { "design" }
        val isPes = data.size >= 4 && String(data, 0, 4, Charsets.ISO_8859_1) == "#PES"
        return if (isPes) {
            Result(requireSafe(data, settings.hoop), "$base.pes", converted = false, hoop = settings.hoop)
        } else {
            val pattern = PatternReader.read(data, fileName)
            val origin = SewoutCheck.originOf(pattern)
            Result(requireSafe(PesWriter.write(pattern, settings), settings.hoop, origin), "$base.pes",
                converted = true, hoop = settings.hoop, origin = origin)
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
