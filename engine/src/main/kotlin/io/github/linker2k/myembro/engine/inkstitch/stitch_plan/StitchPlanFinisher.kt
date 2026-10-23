// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2010 Ink/Stitch Authors (see upstream git history); Kotlin changes: myembro contributors.
// Ported from Ink/Stitch lib/stitch_plan/stitch_plan.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// lib/stitch_plan/stitch_plan.py: stitch_groups_to_stitch_plan (ties, collapse_len=3mm, trim/jump ordering).
// lib/stitch_plan/color_block.py: filter_duplicate_stitches.
// App differences: quantized 0.3mm minimum (including locks/after JUMP), 0.5mm-cell safety rejection.
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.inkstitch.stitches.*
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.InkRunning
import kotlin.math.*

data class StartConcentration(val startCommand: Int, val endCommand: Int, val penetrations: Int,
    val maxCellPenetrations: Int, val maxPointPenetrations: Int, val shortStitches: Int)
data class StitchSafetyOptions(val minimumMm: Double = 0.3, val maximumMm: Double = 7.0,
    val gridMm: Double = 0.5, val maxCellPenetrations: Int = 8, val maxConsecutiveCell: Int = 6,
    val lockKind: LockKind = LockKind.COMPACT, val lockLengthMm: Double = 0.4) {
    init {
        require(minimumMm.isFinite() && minimumMm >= 0.3 && maximumMm.isFinite() && maximumMm >= minimumMm)
        require(gridMm.isFinite() && gridMm > 0 && maxCellPenetrations > 0 && maxConsecutiveCell > 0)
        require(lockLengthMm in 0.3..0.5)
    }
}
class UnsafeStitchPlanException(val cellX: Long, val cellY: Long, val penetrations: Int, val limit: Int) :
    IllegalArgumentException("생성 도안의 0.5mm 셀에 바늘이 ${penetrations}회 집중됩니다(상한 ${limit}회). 글자 크기·간격을 늘려 주세요.")

/** Common machine-coordinate boundary for GENERATED patterns only. Never rewrites an imported PES. */
object StitchPlanFinisher {
    private const val VERSION = "inkstitch-v1"
    private const val MARKER = "myembro.stitch-plan"
    private const val LOCKS = "myembro.lock-indices"
    private fun point(s: Stitch) = Point(s.x / 10.0, s.y / 10.0)
    private fun stitch(p: Point, command: Command = Command.STITCH) = Stitch((p.x * 10).roundToInt(), (p.y * 10).roundToInt(), command)
    private data class Tagged(val stitch: Stitch, val lock: Boolean = false)

    /** [rebuild] strips our previous locks before applying transformed geometry or a combined generated plan. */
    fun finish(pattern: EmbPattern, options: StitchSafetyOptions = StitchSafetyOptions(), rebuild: Boolean = false, centerOutput: Boolean = false): EmbPattern {
        if (!rebuild && pattern.metadata[MARKER] == VERSION) return pattern
        val oldLocks = if (rebuild) pattern.metadata[LOCKS].orEmpty().split(',').mapNotNull(String::toIntOrNull).toSet() else emptySet()
        val input = pattern.stitches.filterIndexed { i, _ -> i !in oldLocks }.takeWhile { it.command != Command.END }
        val output = arrayListOf<Tagged>()
        var needTieIn = true
        var run = arrayListOf<Point>()
        fun lastPoint() = output.lastOrNull()?.stitch?.let(::point)
        fun add(p: Point, command: Command = Command.STITCH, lock: Boolean = false) {
            val s = stitch(p, command)
            val last = output.lastOrNull()?.stitch
            if (command == Command.JUMP && last?.command == Command.JUMP && last.x == s.x && last.y == s.y) return
            output += Tagged(s, lock)
        }
        fun tieOff() {
            if (!needTieIn && run.size >= 2) InkLock.points(run, true, options.lockKind, options.lockLengthMm).forEach { add(it, lock = true) }
            run = arrayListOf()
            needTieIn = true
        }
        var index = 0
        while (index < input.size) {
            val s = input[index]
            when (s.command) {
                Command.TRIM, Command.COLOR_CHANGE, Command.STOP -> {
                    tieOff()
                    lastPoint()?.let { add(it, s.command) }
                }
                Command.JUMP -> {
                    val p = point(s); val last = lastPoint()
                    if (!needTieIn && last != null && last.distance(p) > 3.0) tieOff()
                    add(p, Command.JUMP)
                    run = arrayListOf(p)
                }
                Command.STITCH -> {
                    val p = point(s)
                    if (needTieIn) {
                        val anchor = lastPoint() ?: p
                        val upcoming = arrayListOf(anchor)
                        var i = index
                        while (i < input.size && input[i].command == Command.STITCH && upcoming.size < 32) {
                            upcoming += point(input[i]); i++
                        }
                        val locks = InkLock.points(upcoming, false, options.lockKind, options.lockLengthMm)
                        if (locks.isNotEmpty()) {
                            if (output.lastOrNull()?.stitch?.command != Command.JUMP || lastPoint() != locks.first()) add(locks.first(), Command.JUMP)
                            (if (locks.last() == anchor) locks else locks + anchor).forEach { add(it, lock = true) }
                            needTieIn = false; run = arrayListOf(anchor)
                        }
                    }
                    add(p); run += p
                }
                Command.END -> Unit
            }
            index++
        }
        tieOff()
        // Upstream minimum-stitch filtering, tightened at the final integer needle positions.
        val filtered = arrayListOf<Tagged>()
        fun filteredAdd(t: Tagged) {
            val last = filtered.lastOrNull()?.stitch
            if (t.stitch.command == Command.STITCH) {
                val from = last ?: t.stitch.copy(command = Command.JUMP)
                val distance = point(from).distance(point(t.stitch))
                if (distance < options.minimumMm - 1e-9) {
                    // Keep the original needle-up position at a rejected short corner. Simply
                    // dropping its coordinate would create a new diagonal across holes/corners.
                    if (distance > 0) filtered += Tagged(t.stitch.copy(command = Command.JUMP))
                    return
                }
            }
            filtered += t
        }
        for (t in output) {
            if (t.stitch.command == Command.STITCH) {
                val last = filtered.lastOrNull()?.stitch
                if (last != null) {
                    val a = point(last); val b = point(t.stitch)
                    if (a.distance(b) > options.maximumMm + 1e-9)
                        InkRunning.split(a, b, max(options.minimumMm, options.maximumMm - 0.15)).forEach { filteredAdd(Tagged(stitch(it), t.lock)) }
                }
            }
            filteredAdd(t)
        }
        if (centerOutput) {
            val needles=filtered.filter { it.stitch.command==Command.STITCH }
            if(needles.isNotEmpty()) {
                val cx=((needles.minOf { it.stitch.x }.toDouble()+needles.maxOf { it.stitch.x })/2).roundToInt()
                val cy=((needles.minOf { it.stitch.y }.toDouble()+needles.maxOf { it.stitch.y })/2).roundToInt()
                for(i in filtered.indices) { val t=filtered[i];filtered[i]=t.copy(stitch=t.stitch.copy(x=t.stitch.x-cx,y=t.stitch.y-cy)) }
            }
        }
        // Do not hide an unsafe plan by discarding needle penetrations or punching an arbitrary replacement route.
        val cells = hashMapOf<Pair<Long, Long>, Int>()
        var lastCell: Pair<Long, Long>? = null; var consecutive = 0
        filtered.forEach { t -> if (t.stitch.command == Command.STITCH) {
            val p = t.stitch
            val cell = floor(p.x / (options.gridMm * 10)).toLong() to floor(p.y / (options.gridMm * 10)).toLong()
            val count = (cells[cell] ?: 0) + 1; cells[cell] = count
            consecutive = if (cell == lastCell) consecutive + 1 else 1; lastCell = cell
            if (count > options.maxCellPenetrations) throw UnsafeStitchPlanException(cell.first, cell.second, count, options.maxCellPenetrations)
            if (consecutive > options.maxConsecutiveCell) throw UnsafeStitchPlanException(cell.first, cell.second, consecutive, options.maxConsecutiveCell)
        } }
        val sewn = filtered.any { it.stitch.command == Command.STITCH }
        val last = filtered.lastOrNull()?.stitch
        val commands = if (sewn) filtered.map { it.stitch } + Stitch(last?.x ?: 0, last?.y ?: 0, Command.END)
            else listOf(Stitch(0, 0, Command.END))
        val locks = filtered.indices.filter { filtered[it].lock }.joinToString(",")
        return EmbPattern(commands, if (sewn) pattern.threads else emptyList(),
            pattern.metadata + mapOf(MARKER to VERSION, LOCKS to locks), pattern.warnings)
    }

    /** First 20 actual penetrations after initial entry / TRIM / color change / STOP. JUMPs are not holes. */
    fun starts(pattern: EmbPattern, window: Int = 20, gridMm: Double = 0.5): List<StartConcentration> {
        require(window > 0 && gridMm > 0)
        val result = arrayListOf<StartConcentration>()
        var start = 0
        var points = arrayListOf<Stitch>()
        var shorts = 0
        var previous = Stitch(0, 0, Command.JUMP)
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
