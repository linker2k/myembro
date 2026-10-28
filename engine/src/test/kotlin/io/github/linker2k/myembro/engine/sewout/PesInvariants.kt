// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.Command
import kotlin.math.hypot

/**
 * AGENTS.md P1–P5, P7, P8 measured on written PES bytes with an own PEC decode (not the writer's
 * guard). Empty list = no violation.
 */
internal object PesInvariants {
    fun violations(bytes: ByteArray, hoop: HoopSpec): List<String> {
        val out = ArrayList<String>()
        val moves = boundedPecStitches(bytes).filter { it.command == Command.STITCH || it.command == Command.JUMP }
        val minX = moves.minOf { it.x }; val minY = moves.minOf { it.y }
        val maxX = moves.maxOf { it.x }; val maxY = moves.maxOf { it.y }
        if (minX != 0 || minY != 0) out += "P1 min=($minX,$minY)"
        if (maxX > hoop.width - 1 || maxY > hoop.height - 1) out += "P2 max=($maxX,$maxY)"
        val p = (0..3).sumOf { (bytes[8 + it].toInt() and 255) shl (8 * it) }
        fun u16(i: Int) = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8)
        if (u16(p + 520) != maxX - minX || u16(p + 522) != maxY - minY)
            out += "P3 header=${u16(p + 520)}x${u16(p + 522)} actual=${maxX - minX}x${maxY - minY}"
        var x = 0; var y = 0; var run = 0; var worstRun = 0; var zero = 0; var longest = 0.0
        for (s in moves) {
            val length = hypot((s.x - x).toDouble(), (s.y - y).toDouble())
            if (length == 0.0) zero++
            if (s.command == Command.STITCH) {
                run = if (length == 0.0) run + 1 else 1
                worstRun = maxOf(worstRun, run)
                longest = maxOf(longest, length)
            } else run = 0
            x = s.x; y = s.y
        }
        if (zero > 0) out += "P4 zero-length moves=$zero"
        if (worstRun > 2) out += "P5 same-point run=$worstRun"
        val cells = moves.filter { it.command == Command.STITCH }.groupingBy { it.x / 5 to it.y / 5 }.eachCount()
        cells.values.maxOrNull()?.takeIf { it > SewoutLimits.MAX_PENETRATIONS_PER_CELL }?.let { out += "P7 cell=$it" }
        if (longest > 120.0) out += "P8 longest=${longest / 10}mm"
        return out
    }
}
