// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern

/** UI-only command/needle lookup. Does not modify the pattern or add machine commands. */
internal class ViewerPlaybackTimeline(pattern: EmbPattern) {
    val endIndex: Int = pattern.stitches.indexOfFirst { it.command == Command.END }
        .takeIf { it >= 0 } ?: pattern.stitches.lastIndex
    private val needles = IntArray((0..endIndex).count { pattern.stitches[it].command == Command.STITCH }).also { indices ->
        var next = 0
        for (i in 0..endIndex) if (pattern.stitches[i].command == Command.STITCH) indices[next++] = i
    }
    val needleCount get() = needles.size
    val marks = (0..endIndex).mapNotNull { index ->
        when (val command = pattern.stitches[index].command) {
            Command.COLOR_CHANGE, Command.TRIM -> PlaybackMark(index, (index + 1f) / (endIndex + 1), command)
            else -> null
        }
    }
    val colorChanges = marks.count { it.command == Command.COLOR_CHANGE }
    val trims = marks.count { it.command == Command.TRIM }

    fun needlesThrough(command: Int): Int {
        var lo = 0; var hi = needles.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (needles[mid] <= command) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Control commands take no artificial needle time. Waiting for the operator is not simulated. */
    fun advance(command: Int, count: Int): Int {
        if (count <= 0) return command.coerceIn(-1, endIndex)
        val target = needlesThrough(command).toLong() + count
        return if (target >= needles.size) endIndex else needles[(target - 1).toInt()]
    }
}

internal data class PlaybackMark(val commandIndex: Int, val fraction: Float, val command: Command)

/** Global/nonlocatable blockers stay in the report sheet; never invent a canvas location. */
internal fun viewerBlockingHighlights(
    pattern: EmbPattern,
    report: io.github.linker2k.myembro.engine.sewout.SewoutReport?,
): List<io.github.linker2k.myembro.engine.sewout.Highlight> = report?.findings.orEmpty()
    .filter { it.severity == io.github.linker2k.myembro.engine.sewout.Severity.BLOCKER }
    .mapNotNull { it.highlight }
    .mapNotNull { highlight -> viewerHighlightArea(pattern, highlight)?.let { highlight to it } }
    .distinctBy { (_, area) -> (area.minX + area.maxX) / 2 to (area.minY + area.maxY) / 2 }
    .map { it.first }
