// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor

enum class PesVersion { V1, V6 }

/** Per-export settings; keep an instance in application preferences rather than mutable globals. */
data class PesWriteSettings(val version: PesVersion = PesVersion.V1, val inkstitchCommands: Boolean = false)

/** PES output based on pyembroidery 1.5.1 PesWriter.py (MIT).
 * V1 retains the existing 22-byte compact container. V6 includes CEmbOne/CSewSeg,
 * custom thread RGB/metadata, PEC graphics and the v6 addendum. Neither is hardware-certified.
 */
object PesWriter {
    /** Terminal PES viewport: top-left (0,0), without modifying source/editor coordinates.
     * The initial origin is implicit; prune any stationary move introduced by translation. */
    fun prepareForOutput(pattern: EmbPattern): EmbPattern {
        val safe = GeneratedPatternProcessor.forSewout(pattern)
        val terminal = pattern.stitches.indexOfFirst { it.command == Command.END }
        val active = if (terminal < 0) pattern.stitches else pattern.stitches.take(terminal+1)
        if (active.isEmpty()) return safe
        val minX = active.minOf { it.x }; val minY = active.minOf { it.y }
        val width = active.maxOf { it.x }.toLong()-minX
        val height = active.maxOf { it.y }.toLong()-minY
        require(width <= 65535 && height <= 65535) { "PEC viewport exceeds unsigned 16-bit dimensions" }
        val safeEnd = safe.stitches.indexOfFirst { it.command == Command.END }
        val commands = if (safeEnd < 0) safe.stitches else safe.stitches.take(safeEnd+1)
        val local = EmbPattern(commands.map { it.copy(x=(it.x.toLong()-minX).toInt(),y=(it.y.toLong()-minY).toInt()) },
            safe.threads,safe.metadata,safe.warnings)
        return GeneratedPatternProcessor.forSewout(local)
    }

    /** App/default output uses the common T3 safety pass; explicit source-oracle profile remains verbatim. */
    fun write(pattern: EmbPattern): ByteArray = write(pattern, PesWriteSettings())

    fun write(pattern: EmbPattern, settings: PesWriteSettings): ByteArray = when (settings.version) {
        PesVersion.V1 -> if (settings.inkstitchCommands) writeV1Inkstitch(pattern) else writeV1(pattern)
        PesVersion.V6 -> PesV6Writer.write(if (settings.inkstitchCommands) pattern else prepareForOutput(pattern), settings.inkstitchCommands)
    }

    fun write(pattern: EmbPattern, version: PesVersion): ByteArray = write(pattern, PesWriteSettings(version))

    fun writeV1(pattern: EmbPattern): ByteArray {
        val header = ByteArray(22)
        "#PES0001".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[8] = 22
        return header + PecWriter.writePayload(prepareForOutput(pattern), topLeftViewport = true)
    }

    fun writeV6(pattern: EmbPattern): ByteArray = PesV6Writer.write(prepareForOutput(pattern))

    private fun writeV1Inkstitch(pattern: EmbPattern): ByteArray {
        val header = ByteArray(22)
        "#PES0001".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[8] = 22
        return header + PecWriter.writePayload(pattern, inkstitchCommands = true)
    }
}
