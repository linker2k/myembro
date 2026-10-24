// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor

enum class PesVersion { V1, V6 }

/** Per-export settings; keep an instance in application preferences rather than mutable globals. */
data class PesWriteSettings(val version: PesVersion = PesVersion.V1, val inkstitchCommands: Boolean = false)

/** PES output based on pyembroidery 1.5.1 PesWriter.py (MIT).
 * V1 retains the existing 22-byte compact container. V6 includes CEmbOne/CSewSeg,
 * custom thread RGB/metadata, PEC graphics and the v6 addendum. Neither is hardware-certified.
 */
object PesWriter {
    /** App/default output uses the common T3 safety pass; explicit source-oracle profile remains verbatim. */
    fun write(pattern: EmbPattern): ByteArray = write(pattern, PesWriteSettings())

    fun write(pattern: EmbPattern, settings: PesWriteSettings): ByteArray = when (settings.version) {
        PesVersion.V1 -> if (settings.inkstitchCommands) writeV1Inkstitch(pattern) else writeV1(pattern)
        PesVersion.V6 -> PesV6Writer.write(if (settings.inkstitchCommands) pattern else GeneratedPatternProcessor.forSewout(pattern), settings.inkstitchCommands)
    }

    fun write(pattern: EmbPattern, version: PesVersion): ByteArray = write(pattern, PesWriteSettings(version))

    fun writeV1(pattern: EmbPattern): ByteArray {
        val header = ByteArray(22)
        "#PES0001".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[8] = 22
        return header + PecWriter.writePayload(GeneratedPatternProcessor.forSewout(pattern))
    }

    fun writeV6(pattern: EmbPattern): ByteArray = PesV6Writer.write(GeneratedPatternProcessor.forSewout(pattern))

    private fun writeV1Inkstitch(pattern: EmbPattern): ByteArray {
        val header = ByteArray(22)
        "#PES0001".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[8] = 22
        return header + PecWriter.writePayload(pattern, inkstitchCommands = true)
    }
}
