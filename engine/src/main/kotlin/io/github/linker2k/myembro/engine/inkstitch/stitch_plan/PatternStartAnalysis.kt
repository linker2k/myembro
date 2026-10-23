// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.model.Stitch as MachineStitch
import kotlin.math.floor

data class StartConcentration(val startCommand: Int, val endCommand: Int, val penetrations: Int,
    val maxCellPenetrations: Int, val maxPointPenetrations: Int, val shortStitches: Int)

/** Read-only audit, not a lock generator or output filter. Thresholds belong to the independent gate. */
object PatternStartAnalysis {
    private fun point(s: MachineStitch) = Point(s.x / 10.0, s.y / 10.0)
    /** First 20 actual penetrations after initial entry / TRIM / color change / STOP. JUMPs are not holes. */
    fun starts(pattern: EmbPattern, window: Int = 20, gridMm: Double = 0.5): List<StartConcentration> {
        require(window > 0 && gridMm > 0)
        val result = arrayListOf<StartConcentration>()
        var start = 0
        var points = arrayListOf<MachineStitch>()
        var shorts = 0
        var previous = MachineStitch(0, 0, Command.JUMP)
        fun flush(end: Int) {
            if (points.isEmpty()) return
            val maxCell = points.groupingBy { floor(it.x / (gridMm * 10)).toLong() to floor(it.y / (gridMm * 10)).toLong() }.eachCount().values.max()
            val maxPoint = points.groupingBy { it.x to it.y }.eachCount().values.max()
            result += StartConcentration(start, end, points.size, maxCell, maxPoint, shorts)
        }
        pattern.stitches.forEachIndexed { i, s ->
            if (s.command in setOf(Command.TRIM, Command.COLOR_CHANGE, Command.STOP, Command.END)) {
                flush(i - 1); points = arrayListOf(); shorts = 0; start = i + 1
            } else if (s.command == Command.STITCH && points.size < window) {
                points += s
                if (point(previous).distance(point(s)) < 0.3 - 1e-9) shorts++
            }
            previous = s
        }
        flush(pattern.stitches.lastIndex)
        return result
    }
}
