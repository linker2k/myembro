// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*

/** Confirmed sewing window, in project 0.1 mm coordinates. No hardware offsets are guessed. */
data class SewingPosition(
    val id: String,
    val name: String,
    val originX: Int,
    val originY: Int,
    val width: Int = 1000,
    val height: Int = 1000,
) {
    init { require(id.isNotBlank() && width > 0 && height > 0 && width <= 1000 && height <= 1000) }
    fun contains(x: Int, y: Int): Boolean =
        kotlin.math.abs(x.toLong() - originX) * 2 <= width && kotlin.math.abs(y.toLong() - originY) * 2 <= height
    fun toLocal(stitch: Stitch): Stitch = stitch.copy(x = Math.subtractExact(stitch.x, originX), y = Math.subtractExact(stitch.y, originY))
    fun toProject(stitch: Stitch): Stitch = stitch.copy(x = Math.addExact(stitch.x, originX), y = Math.addExact(stitch.y, originY))
}

data class UnassignedStitch(val sourceIndex: Int, val stitch: Stitch) {
    val message: String get() = "${sourceIndex + 1}번째 땀(${stitch.x / 10.0}, ${stitch.y / 10.0}mm)이 어느 프레임 위치에도 들어가지 않습니다."
}

data class PositionPart(val ordinal: Int, val position: SewingPosition, val pattern: EmbPattern, val sourceStitchIndices: List<Int>) {
    val stitchCount: Int get() = pattern.stitchCount
    val colors: List<EmbThread> get() = pattern.threads
    val instructions: String get() = "${ordinal}번 위치: ${position.name}. 프레임을 해당 위치에 고정한 뒤 봉제하세요. 기준점은 (${position.originX / 10.0}, ${position.originY / 10.0}mm)입니다."
}

data class PositionPesFile(val fileName: String, val bytes: ByteArray, val part: PositionPart) {
    val metadata: Map<String, String> get() = part.pattern.metadata
    val instructions: String get() = part.instructions
}

data class MultiPositionResult(val parts: List<PositionPart>, val errors: List<UnassignedStitch>, val sourceStitchCount: Int) {
    val warnings: List<String> get() = if (parts.size > 1) listOf("위치별 파일로 묶으면 위치 사이의 전체 겹침 봉제 순서가 원본과 달라질 수 있습니다. 위치 안내 순서대로 프레임을 옮겨 주세요.") else emptyList()
    /** Refuse partial exports when stitches were outside all windows. Empty windows produce no file. */
    fun writePes(baseName: String, settings: PesWriteSettings = PesWriteSettings()): List<PositionPesFile> {
        require(errors.isEmpty()) { errors.joinToString("\n") { it.message } }
        require(baseName.isNotBlank() && '/' !in baseName && '\\' !in baseName) { "Expected a file basename" }
        val stem = baseName.removeSuffix(".pes").removeSuffix(".PES")
        // ASCII position suffix follows Brother's filename recommendations.
        return parts.map { part -> PositionPesFile("${stem}_position${part.ordinal}.pes", PesWriter.write(part.pattern, settings), part) }
    }
}

/** Assigns needle-down positions, not geometric clipping. No stitch is duplicated or invented. */
object MultiPositionSplitter {
    fun split(project: Project, positions: List<SewingPosition>): MultiPositionResult = split(project.flatten(), positions)

    fun split(pattern: EmbPattern, positions: List<SewingPosition>): MultiPositionResult {
        require(positions.isNotEmpty() && positions.size <= 16 && positions.map { it.id }.distinct().size == positions.size)
        val active = pattern.stitches.takeWhile { it.command != Command.END }
        val sourceIndices = active.indices.filter { active[it].command == Command.STITCH }
        require(sourceIndices.size <= 200_000) { "Split supports at most 200000 needle-down positions" }
        val assignment = IntArray(active.size) { -1 }
        val errors = mutableListOf<UnassignedStitch>()
        // Dynamic programming chooses overlap assignments with the minimum number of switches.
        // Ties prefer staying in the same window, then the earlier supplied window.
        var run = mutableListOf<Int>()
        fun assignRun() {
            if (run.isEmpty()) return
            val previous = Array(run.size) { IntArray(positions.size) { -1 } }
            var cost = IntArray(positions.size) { 1_000_000 }
            for ((row, source) in run.withIndex()) {
                val s = active[source]
                val next = IntArray(positions.size) { 1_000_000 }
                for (p in positions.indices) if (positions[p].contains(s.x, s.y)) {
                    if (row == 0) { next[p] = 0; continue }
                    var best = p
                    var bestCost = cost[p]
                    for (q in positions.indices) {
                        val candidate = cost[q] + if (q == p) 0 else 1
                        if (candidate < bestCost) { best = q; bestCost = candidate }
                    }
                    next[p] = bestCost; previous[row][p] = best
                }
                cost = next
            }
            var p = positions.indices.minBy { cost[it] }
            for (row in run.indices.reversed()) { assignment[run[row]] = p; p = previous[row][p] }
            run = mutableListOf()
        }
        for (source in sourceIndices) {
            val s = active[source]
            if (positions.none { it.contains(s.x, s.y) }) {
                assignRun(); errors += UnassignedStitch(source, s)
            } else run += source
        }
        assignRun()

        val stitches = List(positions.size) { mutableListOf<Stitch>() }
        val threads = List(positions.size) { mutableListOf<EmbThread>() }
        val indices = List(positions.size) { mutableListOf<Int>() }
        var threadIndex = 0
        var lastPosition = -1
        var connected = false
        fun trim(p: Int) {
            val out = stitches[p]
            val last = out.lastOrNull() ?: return
            if (last.command != Command.TRIM) out += last.copy(command = Command.TRIM)
        }
        for ((sourceIndex, s) in active.withIndex()) {
            when (s.command) {
                Command.COLOR_CHANGE -> { threadIndex++; connected = false }
                Command.JUMP, Command.TRIM -> { if (lastPosition >= 0) trim(lastPosition); connected = false }
                Command.STOP -> if (lastPosition >= 0) {
                    val last = stitches[lastPosition].lastOrNull()
                    if (last != null) stitches[lastPosition] += last.copy(command = Command.STOP)
                }
                Command.STITCH -> {
                    val p = assignment[sourceIndex]
                    if (p < 0) { if (lastPosition >= 0) trim(lastPosition); connected = false; lastPosition = -1; continue }
                    val out = stitches[p]
                    val thread = pattern.threadAt(threadIndex)
                    val sameThread = threads[p].lastOrNull()?.sameThread(thread) == true
                    if (lastPosition != p && lastPosition >= 0) trim(lastPosition)
                    if (threads[p].isEmpty()) threads[p] += thread
                    else if (!sameThread) {
                        trim(p)
                        out += out.last().copy(command = Command.COLOR_CHANGE)
                        threads[p] += thread
                    }
                    val local = positions[p].toLocal(s)
                    if (!connected || lastPosition != p || !sameThread) {
                        trim(p)
                        out += local.copy(command = Command.JUMP)
                    }
                    out += local
                    indices[p] += sourceIndex
                    lastPosition = p; connected = true
                }
                Command.END -> Unit
            }
        }
        val parts = positions.indices.filter { indices[it].isNotEmpty() }.map { p ->
            trim(p)
            val out = stitches[p]
            out += out.last().copy(command = Command.END)
            val position = positions[p]
            val metadata = pattern.metadata + mapOf(
                "name" to "${pattern.metadata["name"] ?: "design"} position ${p+1}",
                "multiposition.index" to (p+1).toString(), "multiposition.id" to position.id,
                "multiposition.name" to position.name, "multiposition.originX" to position.originX.toString(),
                "multiposition.originY" to position.originY.toString(), "multiposition.width" to position.width.toString(),
                "multiposition.height" to position.height.toString(),
                "comments" to "Position ${p+1}: ${position.name}; origin=(${position.originX},${position.originY}) in 0.1mm; mount frame before sewing.",
            )
            PositionPart(p+1, position, EmbPattern(out.toList(), threads[p].toList(), metadata), indices[p].toList())
        }
        return MultiPositionResult(parts, errors, sourceIndices.size)
    }
}
